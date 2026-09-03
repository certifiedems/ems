package com.ems.service.payment;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.ems.config.RazorpayProperties;
import com.ems.dto.request.PaymentRefundRequest;
import com.ems.dto.request.PaymentVerificationRequest;
import com.ems.entity.Payment;
import com.ems.enums.PaymentProvider;
import com.ems.enums.PaymentStatus;
import com.ems.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;

import lombok.extern.slf4j.Slf4j;

/**
 * Razorpay, with a simulated fallback.
 *
 * <p>When {@link RazorpayProperties#isConfigured()} the gateway is real: an
 * order is created up front, the browser pays against it, and settlement is
 * decided by signature plus a read-back from Razorpay. When it is not — a
 * developer with no keys, or the switch turned off in an incident — the old
 * simulated behaviour stands, so the exam workflow and its tests keep running
 * end to end without credentials. The two paths are kept in one strategy rather
 * than two beans so the choice cannot drift out of sync with what was persisted
 * against a payment already in flight.</p>
 */
@Slf4j
@Component
public class RazorpayPaymentProviderStrategy implements PaymentProviderStrategy {

    /** Razorpay's terminal state for money actually taken. */
    private static final String STATUS_CAPTURED = "captured";
    /** Authorised but not yet captured — real money, held, not ours yet. */
    private static final String STATUS_AUTHORIZED = "authorized";

    private final RazorpayProperties properties;
    private final ObjectProvider<RazorpayClient> clientProvider;

    public RazorpayPaymentProviderStrategy(
            RazorpayProperties properties,
            ObjectProvider<RazorpayClient> clientProvider) {
        this.properties = properties;
        this.clientProvider = clientProvider;
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.RAZORPAY;
    }

    @Override
    public boolean isSimulated() {
        return !properties.isConfigured();
    }

    @Override
    public PaymentProviderResult initiate(Payment payment) {
        if (!properties.isConfigured()) {
            return simulatedInitiation(payment);
        }

        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("transactionId", payment.getTransactionId());
        if (payment.getCertificationApplication() != null) {
            notes.put("applicationId", String.valueOf(payment.getCertificationApplication().getId()));
        }

        JsonNode order = client().createOrder(
                payment.getAmount(),
                payment.getCurrency(),
                payment.getTransactionId(),
                notes);
        String orderId = text(order, "id");

        log.info("Razorpay order created: orderId={}, transactionId={}", orderId, payment.getTransactionId());
        return new PaymentProviderResult(
                PaymentStatus.PENDING,
                orderId,
                null,
                null,
                orderId,
                properties.getKeyId());
    }

    @Override
    public PaymentProviderResult verify(Payment payment, PaymentVerificationRequest request) {
        if (!properties.isConfigured()) {
            return simulatedVerification(request);
        }

        /*
         * A cancelled or dismissed Checkout has no payment id to verify, so a
         * false claim is taken at face value — it can only ever downgrade a
         * payment. A true claim gets no such courtesy: it is decided below.
         */
        if (!Boolean.TRUE.equals(request.success()) && request.razorpayPaymentId() == null) {
            return new PaymentProviderResult(PaymentStatus.FAILED, payment.getProviderReference(), null, null);
        }

        String orderId = payment.getProviderOrderId();
        if (orderId == null || orderId.isBlank()) {
            throw new BusinessException("This payment has no Razorpay order to verify against",
                    HttpStatus.CONFLICT);
        }
        if (request.razorpayPaymentId() == null || request.razorpaySignature() == null) {
            throw new BusinessException("Razorpay payment id and signature are required", HttpStatus.BAD_REQUEST);
        }
        /*
         * The order id also comes back from Checkout. It has to match the one we
         * created, or the signature would be verified against an order the payer
         * chose — a valid signature for somebody else's cheaper order.
         */
        if (request.razorpayOrderId() != null && !orderId.equals(request.razorpayOrderId())) {
            log.warn("Razorpay order mismatch: expected={}, received={}, transactionId={}",
                    orderId, request.razorpayOrderId(), payment.getTransactionId());
            throw new BusinessException("Payment could not be verified", HttpStatus.BAD_REQUEST);
        }

        String paymentId = request.razorpayPaymentId();
        if (!client().isValidPaymentSignature(orderId, paymentId, request.razorpaySignature())) {
            log.warn("Razorpay signature rejected: orderId={}, paymentId={}, transactionId={}",
                    orderId, paymentId, payment.getTransactionId());
            return new PaymentProviderResult(PaymentStatus.FAILED, paymentId, null, null);
        }

        /*
         * The signature proves the callback came from Razorpay; it does not prove
         * what was paid. Reading the payment back is what establishes that the
         * captured amount, currency and order are the ones we billed — the
         * gateway is the authority, the browser is a courier.
         */
        return new PaymentProviderResult(
                settlementStatus(client().fetchPayment(paymentId), payment, orderId),
                paymentId,
                null,
                null);
    }

    @Override
    public PaymentProviderResult refund(Payment payment, PaymentRefundRequest request) {
        if (!properties.isConfigured()) {
            return new PaymentProviderResult(
                    PaymentStatus.REFUNDED,
                    "RZP-REFUND-" + payment.getTransactionId(),
                    null,
                    null);
        }

        String paymentId = payment.getProviderReference();
        if (paymentId == null || paymentId.isBlank() || paymentId.equals(payment.getProviderOrderId())) {
            throw new BusinessException("This payment has no captured Razorpay payment to refund",
                    HttpStatus.CONFLICT);
        }

        JsonNode refund = client().createRefund(
                paymentId,
                payment.getAmount(),
                Map.of("reason", request.reason(), "transactionId", payment.getTransactionId()));

        log.info("Razorpay refund created: refundId={}, paymentId={}, transactionId={}",
                text(refund, "id"), paymentId, payment.getTransactionId());
        return new PaymentProviderResult(PaymentStatus.REFUNDED, paymentId, null, null);
    }

    /**
     * Turns a Razorpay payment object into our status.
     *
     * <p>An authorised-but-uncaptured payment is deliberately not treated as
     * success. Orders are created with auto-capture, so seeing {@code authorized}
     * means capture has not landed yet; leaving it PENDING lets the webhook
     * settle it rather than granting exam access against money we do not hold.</p>
     */
    private PaymentStatus settlementStatus(JsonNode gatewayPayment, Payment payment, String expectedOrderId) {
        String status = text(gatewayPayment, "status");
        String paidOrderId = text(gatewayPayment, "order_id");
        long paidMinorUnits = gatewayPayment.path("amount").asLong(-1);
        String paidCurrency = text(gatewayPayment, "currency");

        boolean amountMatches = paidMinorUnits == RazorpayClient.toMinorUnits(payment.getAmount());
        boolean currencyMatches = payment.getCurrency().equalsIgnoreCase(paidCurrency);
        boolean orderMatches = expectedOrderId.equals(paidOrderId);

        if (!amountMatches || !currencyMatches || !orderMatches) {
            log.error("Razorpay payment does not match the billed amount: transactionId={}, "
                            + "expected={} {} on order {}, gateway reported={} {} on order {}",
                    payment.getTransactionId(),
                    payment.getAmount(), payment.getCurrency(), expectedOrderId,
                    paidMinorUnits < 0 ? null : RazorpayClient.fromMinorUnits(paidMinorUnits),
                    paidCurrency, paidOrderId);
            return PaymentStatus.FAILED;
        }

        if (STATUS_CAPTURED.equals(status)) {
            return PaymentStatus.SUCCESS;
        }
        if (STATUS_AUTHORIZED.equals(status)) {
            log.warn("Razorpay payment authorized but not captured; awaiting webhook: transactionId={}",
                    payment.getTransactionId());
            return PaymentStatus.PENDING;
        }

        log.warn("Razorpay payment not settled: transactionId={}, gatewayStatus={}",
                payment.getTransactionId(), status);
        return PaymentStatus.FAILED;
    }

    private PaymentProviderResult simulatedInitiation(Payment payment) {
        return new PaymentProviderResult(
                PaymentStatus.PENDING,
                "RZP-" + payment.getTransactionId(),
                "https://checkout.razorpay.com/pay/" + payment.getTransactionId(),
                null);
    }

    private PaymentProviderResult simulatedVerification(PaymentVerificationRequest request) {
        return new PaymentProviderResult(
                Boolean.TRUE.equals(request.success()) ? PaymentStatus.SUCCESS : PaymentStatus.FAILED,
                request.providerReference(),
                null,
                null);
    }

    private RazorpayClient client() {
        RazorpayClient client = clientProvider.getIfAvailable();
        if (client == null) {
            throw new BusinessException("Razorpay is not available", HttpStatus.SERVICE_UNAVAILABLE);
        }
        return client;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
