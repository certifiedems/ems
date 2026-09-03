package com.ems.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The exam-workflow façade over {@link PaymentVerificationRequest}; see that
 * record for why the gateway fields, not {@code success}, decide the outcome.
 */
public record PaymentCompletionRequest(
        @NotNull Boolean success,
        @Size(max = 100) String providerReference,
        @Size(max = 100) String razorpayOrderId,
        @Size(max = 100) String razorpayPaymentId,
        @Size(max = 255) String razorpaySignature) {

    public PaymentCompletionRequest(Boolean success, String providerReference) {
        this(success, providerReference, null, null, null);
    }
}
