package com.ems.service.payment;

import com.ems.enums.PaymentStatus;

/**
 * What a gateway told us about one payment attempt.
 *
 * <p>{@code providerOrderId} and {@code publicKey} exist for gateways whose
 * checkout runs in the payer's browser: the browser needs the order to pay
 * against and the publishable key to open the widget, and both come from the
 * initiating call rather than from configuration the client can read. Gateways
 * that redirect or render a QR instead leave them null and use
 * {@code redirectUrl} / {@code qrCodePayload}.</p>
 */
public record PaymentProviderResult(
        PaymentStatus paymentStatus,
        String providerReference,
        String redirectUrl,
        String qrCodePayload,
        String providerOrderId,
        String publicKey) {

    /** For gateways that need neither a browser-side order nor a publishable key. */
    public PaymentProviderResult(
            PaymentStatus paymentStatus,
            String providerReference,
            String redirectUrl,
            String qrCodePayload) {
        this(paymentStatus, providerReference, redirectUrl, qrCodePayload, null, null);
    }
}
