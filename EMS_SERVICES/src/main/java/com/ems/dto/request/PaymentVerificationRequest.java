package com.ems.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What the browser reports back after a checkout attempt.
 *
 * <p>{@code success} is the payer's claim, not the verdict. For a live gateway
 * the three {@code razorpay*} fields are what actually settles the payment: the
 * server recomputes the signature over the order and payment ids and then reads
 * the payment back from the gateway, so a client asserting {@code success=true}
 * without a valid signature is recorded as a failure.</p>
 *
 * <p>The Razorpay fields are optional because the same endpoint serves the
 * mock providers and the pre-gateway flow, which have no signature to send.</p>
 */
public record PaymentVerificationRequest(
        @NotNull Boolean success,
        @Size(max = 100) String providerReference,
        @Size(max = 100) String razorpayOrderId,
        @Size(max = 100) String razorpayPaymentId,
        @Size(max = 255) String razorpaySignature) {

    /** Retains the pre-gateway shape used by the mock providers and tests. */
    public PaymentVerificationRequest(Boolean success, String providerReference) {
        this(success, providerReference, null, null, null);
    }
}
