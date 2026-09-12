package com.ems.service;

import java.math.BigDecimal;
import java.util.List;

import com.ems.dto.request.PaymentInitiationRequest;
import com.ems.dto.request.PaymentRefundRequest;
import com.ems.dto.request.PaymentVerificationRequest;
import com.ems.dto.response.PaymentResponse;
import com.ems.enums.PaymentStatus;
import com.ems.service.payment.PaymentInstrument;

public interface PaymentService {

    PaymentResponse initiatePayment(String email, Long applicationId, PaymentInitiationRequest request);

    PaymentResponse verifyPayment(String email, String transactionId, PaymentVerificationRequest request);

    PaymentResponse refundPayment(String transactionId, PaymentRefundRequest request);

    /**
     * Settles a payment from an out-of-band gateway callback.
     *
     * <p>Identified by the gateway's own order id because a webhook has no user
     * session and does not know our transaction id. This is the authoritative
     * path: the browser callback is a convenience that lets the candidate move
     * on immediately, but a closed tab, a dropped connection or a payment that
     * captures late all leave the webhook as the only thing that will ever
     * report the outcome.</p>
     *
     * <p>Idempotent. Gateways retry, and the browser callback races it, so being
     * called twice for one payment is the normal case rather than the
     * exceptional one.</p>
     *
     * <p>{@code instrument} is how the payer paid, where the callback said so;
     * it is recorded with the outcome and may be null.</p>
     */
    void settleFromGatewayCallback(
            String providerOrderId,
            String providerReference,
            PaymentStatus status,
            BigDecimal paidAmount,
            String paidCurrency,
            PaymentInstrument instrument);

    /**
     * Asks a payment's gateway what became of it, on an admin's request, and
     * applies the answer where that is safe.
     *
     * <p>The recovery path for a payment whose browser callback and webhook both
     * went missing. Only one transition is ever made: a PENDING or FAILED payment
     * that the gateway confirms as captured, for the amount billed, becomes
     * SUCCESS. Nothing is downgraded — the candidate may still be in checkout,
     * and a payment marked failed here would turn away the webhook that later
     * reports its capture. The payment method, and the gateway mode of a payment
     * recorded before modes were tracked, are filled in from the same answer.</p>
     *
     * @return what was found, worded for the admin who asked
     */
    String reconcileWithGateway(String transactionId);

    List<PaymentResponse> getPaymentHistory(String email);

    /**
     * Renders the caller's own receipt for {@code transactionId}.
     *
     * <p>Scoped to the authenticated payer: a transaction id belonging to
     * someone else reads as not found rather than as a permission error, so the
     * endpoint cannot be used to probe which ids exist.
     */
    PaymentReceiptContent downloadReceipt(String email, String transactionId);

    /**
     * Renders the receipt for any payment, for the admin console — typically to
     * answer a candidate who cannot find their own.
     */
    PaymentReceiptContent downloadReceiptForAdmin(String transactionId);
}
