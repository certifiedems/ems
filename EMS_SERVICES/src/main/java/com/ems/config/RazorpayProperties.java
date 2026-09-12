package com.ems.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Razorpay gateway credentials, bound from {@code app.payment.razorpay.*}.
 *
 * <p>Only {@link #keyId} ever reaches a browser: it is the publishable half of
 * the pair and Checkout needs it to open. {@link #keySecret} signs server-to-
 * server calls and {@link #webhookSecret} authenticates callbacks, so neither
 * appears in any response DTO.</p>
 *
 * <p>{@link #isConfigured()} is what decides between the live gateway and the
 * built-in mock: a developer with no keys keeps the simulated flow that the rest
 * of the workflow tests depend on, and production fails loudly instead only when
 * {@code enabled} is set without credentials.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.payment.razorpay")
public class RazorpayProperties {

    /**
     * Master switch. False keeps the mock strategy even when keys are present,
     * which is the escape hatch if the gateway has to be taken out of the path
     * without a redeploy.
     */
    private boolean enabled = false;

    /** Publishable key ({@code rzp_test_*} / {@code rzp_live_*}). */
    private String keyId;

    /** Secret key. Server-side only. */
    private String keySecret;

    /** Secret configured against the webhook in the Razorpay dashboard. */
    private String webhookSecret;

    /** Overridable for tests pointing at a stub server. */
    private String apiBaseUrl = "https://api.razorpay.com/v1";

    public boolean isConfigured() {
        return enabled
                && keyId != null && !keyId.isBlank()
                && keySecret != null && !keySecret.isBlank();
    }

    /**
     * Whether the configured key is a test key.
     *
     * <p>Only an explicit {@code rzp_test_} prefix counts. Any other key is taken
     * as live, because filing real money under test is the mistake that hides
     * revenue from a report; the reverse only overstates it.</p>
     */
    public boolean isTestKey() {
        return keyId != null && keyId.trim().startsWith("rzp_test_");
    }

    public boolean hasWebhookSecret() {
        return webhookSecret != null && !webhookSecret.isBlank();
    }
}
