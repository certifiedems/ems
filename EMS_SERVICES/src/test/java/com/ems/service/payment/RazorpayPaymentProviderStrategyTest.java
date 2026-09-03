package com.ems.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import com.ems.config.RazorpayProperties;
import com.ems.dto.request.PaymentRefundRequest;
import com.ems.dto.request.PaymentVerificationRequest;
import com.ems.entity.Payment;
import com.ems.enums.PaymentStatus;
import com.ems.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Covers what decides whether a candidate is let into an exam: not the browser's
 * report of success, but the signature and the read-back that follow it.
 *
 * <p>The cases worth writing are the ones where a caller is lying or a gateway
 * disagrees with us — a forged signature, someone else's order, a captured
 * amount that is not the fee we billed. Each of those must end in FAILED rather
 * than in access granted.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RazorpayPaymentProviderStrategyTest {

    private static final String ORDER_ID = "order_ABC123";
    private static final String PAYMENT_ID = "pay_XYZ789";
    private static final String SIGNATURE = "a-valid-looking-signature";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private RazorpayClient razorpayClient;

    @Mock
    private ObjectProvider<RazorpayClient> clientProvider;

    private RazorpayProperties properties;
    private RazorpayPaymentProviderStrategy strategy;

    @BeforeEach
    void setUp() {
        properties = new RazorpayProperties();
        properties.setEnabled(true);
        properties.setKeyId("rzp_test_key");
        properties.setKeySecret("secret");
        when(clientProvider.getIfAvailable()).thenReturn(razorpayClient);
        strategy = new RazorpayPaymentProviderStrategy(properties, clientProvider);
    }

    @Test
    @DisplayName("falls back to the simulated gateway when no credentials are configured")
    void simulatesWithoutCredentials() {
        properties.setEnabled(false);

        PaymentProviderResult result = strategy.initiate(payment());

        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(result.publicKey()).isNull();
        assertThat(result.providerOrderId()).isNull();
        verify(razorpayClient, never()).createOrder(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("hands the browser an order and the publishable key, never the secret")
    void initiateReturnsOrderAndPublishableKey() {
        when(razorpayClient.createOrder(any(), anyString(), anyString(), any()))
                .thenReturn(node("{\"id\":\"" + ORDER_ID + "\",\"amount\":99900}"));

        PaymentProviderResult result = strategy.initiate(payment());

        assertThat(result.providerOrderId()).isEqualTo(ORDER_ID);
        assertThat(result.publicKey()).isEqualTo("rzp_test_key");
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("settles a captured payment that matches the billed amount")
    void verifiesCapturedPayment() {
        givenValidSignature();
        when(razorpayClient.fetchPayment(PAYMENT_ID)).thenReturn(capturedPayment(99900, "INR", ORDER_ID));

        PaymentProviderResult result = strategy.verify(pendingPayment(), verification());

        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(result.providerReference()).isEqualTo(PAYMENT_ID);
    }

    @Test
    @DisplayName("a forged signature fails the payment instead of settling it")
    void rejectsForgedSignature() {
        when(razorpayClient.isValidPaymentSignature(anyString(), anyString(), anyString())).thenReturn(false);

        PaymentProviderResult result = strategy.verify(pendingPayment(), verification());

        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(razorpayClient, never()).fetchPayment(anyString());
    }

    @Test
    @DisplayName("refuses a callback that names an order we did not create")
    void rejectsMismatchedOrder() {
        PaymentVerificationRequest foreignOrder =
                new PaymentVerificationRequest(true, PAYMENT_ID, "order_SOMEONE_ELSE", PAYMENT_ID, SIGNATURE);

        assertThatThrownBy(() -> strategy.verify(pendingPayment(), foreignOrder))
                .isInstanceOf(BusinessException.class);
        verify(razorpayClient, never()).fetchPayment(anyString());
    }

    @Test
    @DisplayName("a signed callback for a smaller charge than we billed still fails")
    void rejectsUnderpayment() {
        givenValidSignature();
        // Signed by Razorpay, genuinely captured — but for 1 rupee, not 999.
        when(razorpayClient.fetchPayment(PAYMENT_ID)).thenReturn(capturedPayment(100, "INR", ORDER_ID));

        PaymentProviderResult result = strategy.verify(pendingPayment(), verification());

        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("an authorized but uncaptured payment stays pending rather than granting access")
    void authorizedPaymentStaysPending() {
        givenValidSignature();
        when(razorpayClient.fetchPayment(PAYMENT_ID))
                .thenReturn(node("{\"status\":\"authorized\",\"amount\":99900,\"currency\":\"INR\",\"order_id\":\""
                        + ORDER_ID + "\"}"));

        PaymentProviderResult result = strategy.verify(pendingPayment(), verification());

        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("a dismissed checkout is recorded as failed without calling the gateway")
    void cancelledCheckoutFailsQuietly() {
        PaymentProviderResult result = strategy.verify(
                pendingPayment(), new PaymentVerificationRequest(false, null));

        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(razorpayClient, never()).fetchPayment(anyString());
    }

    @Test
    @DisplayName("refuses to refund a payment that was never captured")
    void refusesRefundWithoutCapturedPayment() {
        Payment neverPaid = pendingPayment();
        // Initiation leaves the order id in both fields; there is no payment to
        // refund until verification replaces the reference with a payment id.
        neverPaid.setProviderReference(ORDER_ID);

        assertThatThrownBy(() -> strategy.refund(neverPaid, new PaymentRefundRequest("duplicate charge")))
                .isInstanceOf(BusinessException.class);
        verify(razorpayClient, never()).createRefund(anyString(), any(), any());
    }

    private void givenValidSignature() {
        when(razorpayClient.isValidPaymentSignature(ORDER_ID, PAYMENT_ID, SIGNATURE)).thenReturn(true);
    }

    private static PaymentVerificationRequest verification() {
        return new PaymentVerificationRequest(true, PAYMENT_ID, ORDER_ID, PAYMENT_ID, SIGNATURE);
    }

    private static Payment payment() {
        return Payment.builder()
                .transactionId("TXN123")
                .amount(new BigDecimal("999.00"))
                .currency("INR")
                .paymentStatus(PaymentStatus.PENDING)
                .build();
    }

    private static Payment pendingPayment() {
        Payment payment = payment();
        payment.setProviderOrderId(ORDER_ID);
        return payment;
    }

    private static JsonNode capturedPayment(long minorUnits, String currency, String orderId) {
        return node("{\"status\":\"captured\",\"amount\":" + minorUnits + ",\"currency\":\"" + currency
                + "\",\"order_id\":\"" + orderId + "\"}");
    }

    private static JsonNode node(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
