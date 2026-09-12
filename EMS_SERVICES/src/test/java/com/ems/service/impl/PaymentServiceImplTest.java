package com.ems.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.ems.dto.request.PaymentInitiationRequest;
import com.ems.dto.request.PaymentRefundRequest;
import com.ems.entity.CertificationApplication;
import com.ems.entity.Exam;
import com.ems.entity.Payment;
import com.ems.entity.User;
import com.ems.enums.CertificationApplicationStatus;
import com.ems.enums.CertificationLevel;
import com.ems.enums.PaymentGatewayMode;
import com.ems.enums.PaymentProvider;
import com.ems.enums.PaymentStatus;
import com.ems.exception.BusinessException;
import com.ems.repository.CertificationApplicationRepository;
import com.ems.repository.PaymentRepository;
import com.ems.repository.UserRepository;
import com.ems.service.AuditService;
import com.ems.service.PaymentReceiptPdfGeneratorService;
import com.ems.service.payment.PaymentInstrument;
import com.ems.service.payment.PaymentProviderResult;
import com.ems.service.payment.PaymentProviderStrategy;

/**
 * Covers what the admin payment console relies on: every payment knows whether
 * it was live money, and the recovery and refund actions can only move a
 * payment in the safe direction.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceImplTest {

    private static final String EMAIL = "candidate@example.com";
    private static final String TRANSACTION_ID = "TXN0000000000001";
    private static final String ORDER_ID = "order_ABC123";
    private static final String PAYMENT_ID = "pay_XYZ789";

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private CertificationApplicationRepository certificationApplicationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PaymentReceiptPdfGeneratorService receiptPdfGeneratorService;

    @Mock
    private AuditService auditService;

    @Mock
    private PaymentProviderStrategy razorpay;

    private PaymentServiceImpl service;
    private User user;
    private CertificationApplication application;

    @BeforeEach
    void setUp() {
        when(razorpay.provider()).thenReturn(PaymentProvider.RAZORPAY);
        when(razorpay.isSimulated()).thenReturn(false);
        when(razorpay.gatewayMode()).thenReturn(PaymentGatewayMode.LIVE);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        user = User.builder()
                .id(1L)
                .userId("user-001")
                .firstName("Asha")
                .lastName("Rao")
                .email(EMAIL)
                .build();
        Exam exam = Exam.builder()
                .id(10L)
                .examCode("L1-FOUND-001")
                .examName("Foundation")
                .certificationLevel(CertificationLevel.L1)
                .build();
        application = CertificationApplication.builder()
                .id(100L)
                .user(user)
                .exam(exam)
                .certificationLevel(CertificationLevel.L1)
                .applicationStatus(CertificationApplicationStatus.APPLIED)
                .paymentStatus(PaymentStatus.PENDING)
                .build();

        service = new PaymentServiceImpl(paymentRepository, certificationApplicationRepository, userRepository,
                receiptPdfGeneratorService, List.of(razorpay), auditService);
    }

    @Test
    @DisplayName("a payment is stamped with the gateway mode in force when it is opened")
    void initiateStampsGatewayMode() {
        when(razorpay.gatewayMode()).thenReturn(PaymentGatewayMode.TEST);
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
        when(certificationApplicationRepository.findByIdAndUser(100L, user)).thenReturn(Optional.of(application));
        when(razorpay.initiate(any(Payment.class))).thenReturn(
                new PaymentProviderResult(PaymentStatus.PENDING, ORDER_ID, null, null, ORDER_ID, "rzp_test_key"));

        service.initiatePayment(EMAIL, 100L, new PaymentInitiationRequest("RAZORPAY", "INR"));

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getGatewayMode()).isEqualTo(PaymentGatewayMode.TEST);
    }

    @Test
    @DisplayName("a pending payment the gateway confirms as captured is settled, with mode and method filled in")
    void reconcileSettlesConfirmedCapture() {
        Payment payment = gatewayPayment(PaymentStatus.PENDING);
        // Recorded before live/test was tracked.
        payment.setGatewayMode(null);
        when(paymentRepository.findByTransactionId(TRANSACTION_ID)).thenReturn(Optional.of(payment));
        when(razorpay.lookup(payment)).thenReturn(PaymentProviderResult.readBack(
                PaymentStatus.SUCCESS, PAYMENT_ID, new PaymentInstrument("UPI", "candidate@okhdfcbank")));

        String outcome = service.reconcileWithGateway(TRANSACTION_ID);

        assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.getProviderReference()).isEqualTo(PAYMENT_ID);
        assertThat(payment.getPaymentDate()).isNotNull();
        assertThat(payment.getPaymentMethod()).isEqualTo("UPI");
        assertThat(payment.getPaymentMethodDetail()).isEqualTo("candidate@okhdfcbank");
        assertThat(payment.getGatewayMode()).isEqualTo(PaymentGatewayMode.LIVE);
        assertThat(application.getPaymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(application.getApplicationStatus()).isEqualTo(CertificationApplicationStatus.IN_PROGRESS);
        assertThat(outcome).contains("SUCCESS");
    }

    @Test
    @DisplayName("a gateway check never marks a pending payment failed: the candidate may still be paying")
    void reconcileNeverDowngrades() {
        Payment payment = gatewayPayment(PaymentStatus.PENDING);
        when(paymentRepository.findByTransactionId(TRANSACTION_ID)).thenReturn(Optional.of(payment));
        when(razorpay.lookup(payment)).thenReturn(PaymentProviderResult.readBack(
                PaymentStatus.FAILED, "pay_DECLINED", new PaymentInstrument("CARD", null)));

        service.reconcileWithGateway(TRANSACTION_ID);

        assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getProviderReference()).isEqualTo(ORDER_ID);
        assertThat(application.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("a payment recorded as failed is rescued when the gateway shows a capture on its order")
    void reconcileRescuesFailedPayment() {
        Payment payment = gatewayPayment(PaymentStatus.FAILED);
        application.setPaymentStatus(PaymentStatus.FAILED);
        when(paymentRepository.findByTransactionId(TRANSACTION_ID)).thenReturn(Optional.of(payment));
        when(razorpay.lookup(payment)).thenReturn(PaymentProviderResult.readBack(PaymentStatus.SUCCESS, PAYMENT_ID, null));

        service.reconcileWithGateway(TRANSACTION_ID);

        assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(application.getPaymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    @DisplayName("a refunded payment stays refunded whatever the gateway reports")
    void reconcileLeavesSettledStatusAlone() {
        Payment payment = gatewayPayment(PaymentStatus.REFUNDED);
        when(paymentRepository.findByTransactionId(TRANSACTION_ID)).thenReturn(Optional.of(payment));
        when(razorpay.lookup(payment)).thenReturn(PaymentProviderResult.readBack(
                PaymentStatus.SUCCESS, PAYMENT_ID, new PaymentInstrument("NETBANKING", "HDFC")));

        service.reconcileWithGateway(TRANSACTION_ID);

        assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getPaymentMethod()).isEqualTo("NETBANKING");
    }

    @Test
    @DisplayName("a payment that never reached a gateway cannot be checked against one")
    void reconcileRefusesPaymentWithoutGateway() {
        when(razorpay.gatewayMode()).thenReturn(PaymentGatewayMode.SIMULATED);
        Payment payment = gatewayPayment(PaymentStatus.PENDING);
        payment.setGatewayMode(PaymentGatewayMode.SIMULATED);
        payment.setProviderOrderId(null);
        when(paymentRepository.findByTransactionId(TRANSACTION_ID)).thenReturn(Optional.of(payment));
        when(razorpay.lookup(payment)).thenReturn(null);

        assertThatThrownBy(() -> service.reconcileWithGateway(TRANSACTION_ID))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a live payment is not refunded through a gateway now configured for a different mode")
    void refundRefusesModeMismatch() {
        // Razorpay switched off in an incident: the simulated fallback would
        // otherwise record a refund of live money that never left the account.
        when(razorpay.gatewayMode()).thenReturn(PaymentGatewayMode.SIMULATED);
        Payment payment = gatewayPayment(PaymentStatus.SUCCESS);
        when(paymentRepository.findByTransactionId(TRANSACTION_ID)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.refundPayment(TRANSACTION_ID, new PaymentRefundRequest("duplicate charge")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("LIVE");
        verify(razorpay, never()).refund(any(), any());
        assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    @DisplayName("a webhook capture settles the payment, records how it was paid, and opens the application")
    void webhookCaptureSettles() {
        Payment payment = gatewayPayment(PaymentStatus.PENDING);
        when(paymentRepository.findByProviderOrderId(ORDER_ID)).thenReturn(Optional.of(payment));

        service.settleFromGatewayCallback(ORDER_ID, PAYMENT_ID, PaymentStatus.SUCCESS,
                new BigDecimal("999.00"), "INR", new PaymentInstrument("UPI", "candidate@ybl"));

        assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.getPaymentMethod()).isEqualTo("UPI");
        assertThat(application.getPaymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(application.getApplicationStatus()).isEqualTo(CertificationApplicationStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("a late capture on a second order does not reopen an application another payment already covers")
    void webhookCaptureLeavesPaidApplicationAlone() {
        Payment duplicate = gatewayPayment(PaymentStatus.PENDING);
        application.setPaymentStatus(PaymentStatus.SUCCESS);
        application.setApplicationStatus(CertificationApplicationStatus.PASSED);
        when(paymentRepository.findByProviderOrderId(ORDER_ID)).thenReturn(Optional.of(duplicate));

        service.settleFromGatewayCallback(ORDER_ID, PAYMENT_ID, PaymentStatus.SUCCESS,
                new BigDecimal("999"), "INR", new PaymentInstrument("CARD", "Visa credit ending 1111"));

        assertThat(duplicate.getPaymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(duplicate.getPaymentMethod()).isEqualTo("CARD");
        assertThat(application.getApplicationStatus()).isEqualTo(CertificationApplicationStatus.PASSED);
        verify(certificationApplicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("a late failure on a stale order does not lock out a candidate whose application is paid")
    void webhookFailureDoesNotDowngradePaidApplication() {
        Payment stale = gatewayPayment(PaymentStatus.PENDING);
        application.setPaymentStatus(PaymentStatus.SUCCESS);
        application.setApplicationStatus(CertificationApplicationStatus.IN_PROGRESS);
        when(paymentRepository.findByProviderOrderId(ORDER_ID)).thenReturn(Optional.of(stale));

        service.settleFromGatewayCallback(ORDER_ID, "pay_DECLINED", PaymentStatus.FAILED, null, null, null);

        assertThat(stale.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(application.getPaymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(application.getApplicationStatus()).isEqualTo(CertificationApplicationStatus.IN_PROGRESS);
    }

    private Payment gatewayPayment(PaymentStatus status) {
        return Payment.builder()
                .id(1L)
                .transactionId(TRANSACTION_ID)
                .user(user)
                .exam(application.getExam())
                .certificationApplication(application)
                .amount(new BigDecimal("999.00"))
                .currency("INR")
                .provider(PaymentProvider.RAZORPAY.name())
                .paymentStatus(status)
                .providerOrderId(ORDER_ID)
                .providerReference(ORDER_ID)
                .gatewayMode(PaymentGatewayMode.LIVE)
                .build();
    }
}
