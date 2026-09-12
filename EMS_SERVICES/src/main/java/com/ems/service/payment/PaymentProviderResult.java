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
 *
 * <p>{@code instrument} is how the payer paid, where the gateway said. It is
 * null for simulated gateways and for any call that did not read a payment back
 * from the gateway.</p>
 */
public record PaymentProviderResult(
        PaymentStatus paymentStatus,
        String providerReference,
        String redirectUrl,
        String qrCodePayload,
        String providerOrderId,
        String publicKey,
        PaymentInstrument instrument) {

    /** For gateways whose checkout needs an order and a publishable key, before anything is paid. */
    public PaymentProviderResult(
            PaymentStatus paymentStatus,
            String providerReference,
            String redirectUrl,
            String qrCodePayload,
            String providerOrderId,
            String publicKey) {
        this(paymentStatus, providerReference, redirectUrl, qrCodePayload, providerOrderId, publicKey, null);
    }

    /** For gateways that need neither a browser-side order nor a publishable key. */
    public PaymentProviderResult(
            PaymentStatus paymentStatus,
            String providerReference,
            String redirectUrl,
            String qrCodePayload) {
        this(paymentStatus, providerReference, redirectUrl, qrCodePayload, null, null, null);
    }

    /** A payment as read back from the gateway: its outcome, its id there, and how it was paid. */
    public static PaymentProviderResult readBack(
            PaymentStatus paymentStatus,
            String providerReference,
            PaymentInstrument instrument) {
        return new PaymentProviderResult(paymentStatus, providerReference, null, null, null, null, instrument);
    }
}
