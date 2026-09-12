package com.ems.dto.response;

/**
 * The result of checking a payment with its gateway: what was found, in words
 * an admin can act on, and the payment as it now stands.
 */
public record AdminPaymentReconciliation(
        String message,
        AdminPaymentResponse payment) {
}
