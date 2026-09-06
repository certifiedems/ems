package com.ems.service.impl;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ems.audit.AuditEvent;
import com.ems.audit.AuditEventType;
import com.ems.audit.AuditOutcome;
import com.ems.dto.request.PaymentInitiationRequest;
import com.ems.dto.request.PaymentRefundRequest;
import com.ems.dto.request.PaymentVerificationRequest;
import com.ems.dto.response.PaymentResponse;
import com.ems.entity.CertificationApplication;
import com.ems.entity.Payment;
import com.ems.entity.User;
import com.ems.enums.CertificationApplicationStatus;
import com.ems.enums.CertificationLevel;
import com.ems.enums.PaymentProvider;
import com.ems.enums.PaymentStatus;
import com.ems.exception.BusinessException;
import com.ems.exception.ResourceNotFoundException;
import com.ems.repository.CertificationApplicationRepository;
import com.ems.repository.PaymentRepository;
import com.ems.repository.UserRepository;
import com.ems.service.AuditService;
import com.ems.service.PaymentReceiptContent;
import com.ems.service.PaymentReceiptPdfGeneratorService;
import com.ems.service.PaymentReceiptPdfGeneratorService.PaymentReceiptData;
import com.ems.service.PaymentService;
import com.ems.service.payment.PaymentProviderResult;
import com.ems.service.payment.PaymentProviderStrategy;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
@Transactional
public class PaymentServiceImpl implements PaymentService {

	private static final BigDecimal L1_FEE = BigDecimal.valueOf(999);
	private static final BigDecimal L2_FEE = BigDecimal.valueOf(1999);
	private static final BigDecimal L3_FEE = BigDecimal.valueOf(2499);

	private final PaymentRepository paymentRepository;
	private final CertificationApplicationRepository certificationApplicationRepository;
	private final UserRepository userRepository;
	private final PaymentReceiptPdfGeneratorService receiptPdfGeneratorService;
	private final Map<PaymentProvider, PaymentProviderStrategy> providerStrategies;
	private final AuditService auditService;

	public PaymentServiceImpl(
			PaymentRepository paymentRepository,
			CertificationApplicationRepository certificationApplicationRepository,
			UserRepository userRepository,
			PaymentReceiptPdfGeneratorService receiptPdfGeneratorService,
			List<PaymentProviderStrategy> providerStrategies,
			AuditService auditService) {
		this.paymentRepository = paymentRepository;
		this.certificationApplicationRepository = certificationApplicationRepository;
		this.userRepository = userRepository;
		this.receiptPdfGeneratorService = receiptPdfGeneratorService;
		this.providerStrategies = providerStrategies.stream()
				.collect(Collectors.toMap(PaymentProviderStrategy::provider, Function.identity()));
		this.auditService = auditService;
	}

	@Override
	@CacheEvict(cacheNames = "dashboard", allEntries = true)
	public PaymentResponse initiatePayment(String email, Long applicationId, PaymentInitiationRequest request) {
		CertificationApplication application = findApplication(email, applicationId);
		if (application.getExam() == null) {
			throw new BusinessException("Application is not linked to an exam", HttpStatus.BAD_REQUEST);
		}
		if (application.getPaymentStatus() == PaymentStatus.SUCCESS) {
			boolean hasSuccessfulPaymentRecord = paymentRepository
					.existsByCertificationApplicationIdAndPaymentStatus(application.getId(), PaymentStatus.SUCCESS);

			if (!hasSuccessfulPaymentRecord && application.getApplicationStatus() == CertificationApplicationStatus.APPLIED) {
				log.warn("Repairing stale payment status for applicationId={} (status APPLIED but no successful payment rows)",
						application.getId());
				application.setPaymentStatus(PaymentStatus.PENDING);
				certificationApplicationRepository.save(application);
			} else {
				throw new BusinessException("Payment is already completed for this application", HttpStatus.CONFLICT);
			}
		}

		PaymentProvider provider = parseProvider(request.provider());
		requireSettleableProvider(provider);
		Payment payment = Payment.builder()
				.transactionId(UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT))
				.user(application.getUser())
				.exam(application.getExam())
				.certificationApplication(application)
				.amount(resolveAmountByLevel(application.getCertificationLevel()))
				.currency(request.currency().trim().toUpperCase(Locale.ROOT))
				.provider(provider.name())
				.paymentStatus(PaymentStatus.PENDING)
				.build();

		Payment savedPayment = paymentRepository.save(payment);
		PaymentProviderResult initiation = strategy(provider).initiate(savedPayment);
		savedPayment.setProviderReference(initiation.providerReference());
		savedPayment.setProviderOrderId(initiation.providerOrderId());
		savedPayment.setPaymentStatus(initiation.paymentStatus());
		Payment persistedPayment = paymentRepository.save(savedPayment);

		application.setPaymentStatus(persistedPayment.getPaymentStatus());
		certificationApplicationRepository.save(application);

		log.info("Payment initiated: transactionId={}, provider={}, applicationId={}",
				persistedPayment.getTransactionId(), provider, applicationId);
		return toResponse(persistedPayment, initiation);
	}

	@Override
	@CacheEvict(cacheNames = "dashboard", allEntries = true)
	public PaymentResponse verifyPayment(String email, String transactionId, PaymentVerificationRequest request) {
		User user = findUser(email);
		Payment payment = paymentRepository.findByTransactionIdAndUser(transactionId, user)
				.orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

		/*
		 * A settled payment is final. The browser callback and the gateway
		 * webhook both land here and either can arrive first, so re-verifying is
		 * routine -- but a second pass must not be able to reopen a payment that
		 * already succeeded, which is exactly what a replayed or malformed
		 * callback would do by reporting failure against a captured charge.
		 */
		if (payment.getPaymentStatus() == PaymentStatus.SUCCESS) {
			log.info("Payment already settled, verification ignored: transactionId={}", transactionId);
			return toResponse(payment, null);
		}

		PaymentProvider provider = parseProvider(payment.getProvider());
		PaymentProviderResult verification = strategy(provider).verify(payment, request);
		payment.setPaymentStatus(verification.paymentStatus());
		payment.setProviderReference(verification.providerReference());
		// Stamped only once money has actually moved: a failed or still-pending
		// attempt has no payment date, and the receipt prints this field.
		if (verification.paymentStatus() == PaymentStatus.SUCCESS) {
			payment.setPaymentDate(Instant.now());
		}
		Payment savedPayment = paymentRepository.save(payment);

		CertificationApplication application = payment.getCertificationApplication();
		if (application != null) {
			application.setPaymentStatus(savedPayment.getPaymentStatus());
			if (savedPayment.getPaymentStatus() == PaymentStatus.SUCCESS) {
				application.setApplicationStatus(CertificationApplicationStatus.IN_PROGRESS);
			}
			certificationApplicationRepository.save(application);
		}

		auditService.record(AuditEvent.builder()
				.eventType(AuditEventType.PAYMENT)
				.outcome(savedPayment.getPaymentStatus() == PaymentStatus.SUCCESS
						? AuditOutcome.SUCCESS : AuditOutcome.FAILURE)
				.actorEmail(user.getEmail())
				.actorUserId(user.getUserId())
				.targetUserId(user.getUserId())
				.targetType("PAYMENT")
				.targetId(transactionId)
				.description("Payment verified as " + savedPayment.getPaymentStatus())
				.build());

		return toResponse(savedPayment, verification);
	}

	@Override
	@CacheEvict(cacheNames = "dashboard", allEntries = true)
	public PaymentResponse refundPayment(String transactionId, PaymentRefundRequest request) {
		Payment payment = paymentRepository.findByTransactionId(transactionId)
				.orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

		if (payment.getPaymentStatus() != PaymentStatus.SUCCESS) {
			throw new BusinessException("Only successful payments can be refunded", HttpStatus.BAD_REQUEST);
		}

		PaymentProvider provider = parseProvider(payment.getProvider());
		PaymentProviderResult refund = strategy(provider).refund(payment, request);
		payment.setPaymentStatus(refund.paymentStatus());
		payment.setProviderReference(refund.providerReference());
		Payment savedPayment = paymentRepository.save(payment);

		CertificationApplication application = payment.getCertificationApplication();
		if (application != null) {
			application.setPaymentStatus(PaymentStatus.REFUNDED);
			certificationApplicationRepository.save(application);
		}

		User user = payment.getUser();
		auditService.record(AuditEvent.builder()
				.eventType(AuditEventType.ADMIN_ACTION)
				.outcome(AuditOutcome.SUCCESS)
				.targetUserId(user == null ? null : user.getUserId())
				.targetType("PAYMENT")
				.targetId(transactionId)
				.description("Refunded payment " + transactionId)
				.build());

		return toResponse(savedPayment, refund);
	}

	@Override
	@CacheEvict(cacheNames = "dashboard", allEntries = true)
	public void settleFromGatewayCallback(
			String providerOrderId,
			String providerReference,
			PaymentStatus status,
			BigDecimal paidAmount,
			String paidCurrency) {
		Payment payment = paymentRepository.findByProviderOrderId(providerOrderId).orElse(null);
		if (payment == null) {
			// Not an error: one Razorpay account can serve more than this
			// system, and webhooks are delivered for every order on it.
			log.warn("Gateway callback for unknown order, ignored: providerOrderId={}", providerOrderId);
			return;
		}

		/*
		 * Only PENDING is open to a callback. A settled payment stays settled --
		 * gateways retry deliveries for days and the browser callback races them,
		 * so a webhook must never be able to undo a success or reanimate a
		 * refund. Refunds are excluded here too: they are recorded by the admin
		 * refund path, and a late payment.captured for a refunded charge would
		 * otherwise mark it paid again.
		 */
		if (payment.getPaymentStatus() != PaymentStatus.PENDING) {
			log.info("Gateway callback ignored, payment already {}: transactionId={}",
					payment.getPaymentStatus(), payment.getTransactionId());
			return;
		}

		/*
		 * Defence in depth. The signature already proves the callback is
		 * Razorpay's and the order id ties it to a fee we set, so a mismatch here
		 * should be impossible -- which is exactly why it is worth refusing to
		 * grant exam access on, rather than trusting that it stays impossible.
		 */
		if (status == PaymentStatus.SUCCESS && !billedAmountMatches(payment, paidAmount, paidCurrency)) {
			log.error("Gateway callback amount mismatch, refusing to settle: transactionId={}, "
							+ "billed={} {}, callback reported={} {}",
					payment.getTransactionId(), payment.getAmount(), payment.getCurrency(),
					paidAmount, paidCurrency);
			// No authenticated caller here -- this is Razorpay's server calling
			// ours -- so actorEmail is left unset and AuditService resolves it to
			// SYSTEM, same as every other webhook-originated entry below.
			auditService.record(AuditEvent.builder()
					.eventType(AuditEventType.PAYMENT)
					.outcome(AuditOutcome.FAILURE)
					.targetUserId(payment.getUser() == null ? null : payment.getUser().getUserId())
					.targetType("PAYMENT")
					.targetId(payment.getTransactionId())
					.description("Gateway callback refused: billed " + payment.getAmount() + " " + payment.getCurrency()
							+ " but callback reported " + paidAmount + " " + paidCurrency)
					.build());
			return;
		}

		payment.setPaymentStatus(status);
		if (providerReference != null && !providerReference.isBlank()) {
			payment.setProviderReference(providerReference);
		}
		if (status == PaymentStatus.SUCCESS) {
			payment.setPaymentDate(Instant.now());
		}
		Payment savedPayment = paymentRepository.save(payment);

		CertificationApplication application = savedPayment.getCertificationApplication();
		if (application != null) {
			application.setPaymentStatus(status);
			if (status == PaymentStatus.SUCCESS) {
				application.setApplicationStatus(CertificationApplicationStatus.IN_PROGRESS);
			}
			certificationApplicationRepository.save(application);
		}

		log.info("Payment settled from gateway callback: transactionId={}, status={}, providerReference={}",
				savedPayment.getTransactionId(), status, providerReference);
		auditService.record(AuditEvent.builder()
				.eventType(AuditEventType.PAYMENT)
				.outcome(status == PaymentStatus.SUCCESS ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE)
				.targetUserId(savedPayment.getUser() == null ? null : savedPayment.getUser().getUserId())
				.targetType("PAYMENT")
				.targetId(savedPayment.getTransactionId())
				.description("Payment settled via gateway callback as " + status)
				.build());
	}

	@Override
	@Transactional(readOnly = true)
	public List<PaymentResponse> getPaymentHistory(String email) {
		User user = findUser(email);
		return paymentRepository.findByUserOrderByCreatedDateDesc(user).stream()
				.map(payment -> toResponse(payment, null))
				.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public PaymentReceiptContent downloadReceipt(String email, String transactionId) {
		User user = findUser(email);
		Payment payment = paymentRepository.findByTransactionIdAndUser(transactionId, user)
				.orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

		// A pending payment has not settled, so there is nothing to receipt yet.
		if (payment.getPaymentStatus() == PaymentStatus.PENDING) {
			throw new BusinessException("Receipt is available once the payment has been processed",
					HttpStatus.CONFLICT);
		}

		byte[] pdf = receiptPdfGeneratorService.generateReceiptPdf(new PaymentReceiptData(
				payment.getTransactionId(),
				(user.getFirstName() + " " + user.getLastName()).trim(),
				user.getUserId(),
				user.getEmail(),
				describe(payment),
				payment.getAmount(),
				payment.getCurrency(),
				payment.getProvider(),
				payment.getProviderReference(),
				payment.getPaymentStatus(),
				payment.getPaymentDate()));

		return new PaymentReceiptContent(
				new ByteArrayResource(pdf),
				MediaType.APPLICATION_PDF_VALUE,
				"receipt-" + payment.getTransactionId() + ".pdf");
	}

	/**
	 * Whether a callback describes the charge we actually raised.
	 *
	 * <p>A callback that reports no amount at all is accepted: the gateway is
	 * still the one that said the order was paid, and the order carries our
	 * amount. Only a stated amount that disagrees is treated as a mismatch.</p>
	 */
	private boolean billedAmountMatches(Payment payment, BigDecimal paidAmount, String paidCurrency) {
		if (paidAmount == null) {
			return true;
		}
		return paidAmount.compareTo(payment.getAmount()) == 0
				&& (paidCurrency == null || payment.getCurrency().equalsIgnoreCase(paidCurrency));
	}

	/**
	 * Refuses a simulated provider once any real one is configured.
	 *
	 * <p>The provider list is the candidate's to choose from, and three of the
	 * four entries settle by simply asserting success. That is harmless while
	 * every provider is simulated -- the whole system is then a sandbox -- but
	 * the moment Razorpay is given live credentials, picking "Stripe" instead
	 * becomes a checkout that grants a paid exam for nothing. So the presence of
	 * one real gateway retires the pretend ones.</p>
	 */
	private void requireSettleableProvider(PaymentProvider provider) {
		if (!strategy(provider).isSimulated()) {
			return;
		}
		boolean liveGatewayConfigured = providerStrategies.values().stream()
				.anyMatch(candidate -> !candidate.isSimulated());
		if (liveGatewayConfigured) {
			log.warn("Rejected simulated provider while a live gateway is configured: provider={}", provider);
			throw new BusinessException("This payment method is not available", HttpStatus.BAD_REQUEST);
		}
	}

	private PaymentProviderStrategy strategy(PaymentProvider provider) {
		PaymentProviderStrategy strategy = providerStrategies.get(provider);
		if (strategy == null) {
			throw new BusinessException("Unsupported payment provider: " + provider, HttpStatus.BAD_REQUEST);
		}
		return strategy;
	}

	private PaymentProvider parseProvider(String rawProvider) {
		if (rawProvider == null || rawProvider.isBlank()) {
			throw new BusinessException("Unsupported payment provider: " + rawProvider, HttpStatus.BAD_REQUEST);
		}

		String normalized = rawProvider.trim().toUpperCase(Locale.ROOT)
				.replace('-', '_')
				.replace(' ', '_');
		if ("UPI".equals(normalized)) {
			return PaymentProvider.UPI_QR;
		}

		try {
			return PaymentProvider.valueOf(normalized);
		} catch (IllegalArgumentException ex) {
			throw new BusinessException("Unsupported payment provider: " + rawProvider, HttpStatus.BAD_REQUEST);
		}
	}

	private BigDecimal resolveAmountByLevel(CertificationLevel level) {
		return switch (level) {
			case L1 -> L1_FEE;
			case L2 -> L2_FEE;
			case L3 -> L3_FEE;
		};
	}

	private User findUser(String email) {
		return userRepository.findByEmailIgnoreCase(email)
				.orElseThrow(() -> new ResourceNotFoundException("User not found"));
	}

	private CertificationApplication findApplication(String email, Long applicationId) {
		User user = findUser(email);
		return certificationApplicationRepository.findByIdAndUser(applicationId, user)
				.orElseThrow(() -> new ResourceNotFoundException("Exam application not found"));
	}

	/**
	 * The line-item wording for a payment.
	 *
	 * <p>Built from the persisted application and exam rather than stored per
	 * row, so historical payments re-read with today's phrasing and no caller
	 * can influence what a receipt claims was purchased.
	 */
	private String describe(Payment payment) {
		CertificationApplication application = payment.getCertificationApplication();
		CertificationLevel level = application != null
				? application.getCertificationLevel()
				: payment.getExam().getCertificationLevel();

		String subject = level == null
				? payment.getExam().getExamName()
				: "Level " + level.name().substring(1) + " certification";

		return application == null
				? subject + " exam fee"
				: subject + " exam application fee";
	}

	private PaymentResponse toResponse(Payment payment, PaymentProviderResult result) {
		return new PaymentResponse(
				payment.getId(),
				payment.getTransactionId(),
				payment.getCertificationApplication() == null ? null : payment.getCertificationApplication().getId(),
				payment.getExam().getId(),
				describe(payment),
				payment.getAmount(),
				payment.getCurrency(),
				payment.getProvider(),
				payment.getPaymentStatus(),
				payment.getPaymentDate(),
				payment.getProviderReference(),
				result == null ? null : result.redirectUrl(),
				result == null ? null : result.qrCodePayload(),
				payment.getProviderOrderId(),
				// Only ever the publishable key, and only on the call that opens
				// checkout: history and receipts have no use for it.
				result == null ? null : result.publicKey());
	}
}
