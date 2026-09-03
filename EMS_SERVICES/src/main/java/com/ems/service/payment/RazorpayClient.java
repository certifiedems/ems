package com.ems.service.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.ems.config.RazorpayProperties;
import com.ems.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;

import lombok.extern.slf4j.Slf4j;

/**
 * Minimal Razorpay REST binding: orders, payment lookup, and refunds.
 *
 * <p>Written against the HTTP API rather than the {@code razorpay-java} SDK on
 * purpose. The SDK would add okhttp and org.json to a build that needs neither,
 * and the three calls this system makes are a few lines each over the
 * {@link RestClient} already on the classpath. It also keeps the failure mode
 * ours: every non-2xx surfaces as a {@link BusinessException} carrying
 * Razorpay's own {@code error.description}, instead of a checked SDK exception
 * that each caller would have to unwrap.</p>
 *
 * <p>Amounts cross this boundary in the smallest currency unit — paise for INR —
 * so every conversion goes through {@link #toMinorUnits(BigDecimal)} rather than
 * being open-coded at call sites, where a factor-of-100 slip is a real charge.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.payment.razorpay.enabled", havingValue = "true")
public class RazorpayClient {

    private final RazorpayProperties properties;
    private final RestClient restClient;

    public RazorpayClient(RazorpayProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder
                .baseUrl(properties.getApiBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, basicAuthHeader(properties))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Creates the order Checkout will be opened against.
     *
     * <p>{@code receipt} carries our transaction id so a row in the Razorpay
     * dashboard can be traced back to a payment here without a lookup table, and
     * {@code notes} carries the application id for the same reason on the
     * webhook side.</p>
     */
    public JsonNode createOrder(BigDecimal amount, String currency, String receipt, Map<String, String> notes) {
        Map<String, Object> body = Map.of(
                "amount", toMinorUnits(amount),
                "currency", currency,
                "receipt", receipt,
                // Auto-capture. Without it a successful authorisation sits
                // uncaptured and is voided by Razorpay after five days, which
                // reads to the candidate as a payment that silently vanished.
                "payment_capture", 1,
                "notes", notes);
        return post("/orders", body, "create order");
    }

    /** Reads a payment back from Razorpay — the authority on what was charged. */
    public JsonNode fetchPayment(String paymentId) {
        return get("/payments/" + paymentId, "fetch payment");
    }

    public JsonNode createRefund(String paymentId, BigDecimal amount, Map<String, String> notes) {
        Map<String, Object> body = Map.of(
                "amount", toMinorUnits(amount),
                "speed", "normal",
                "notes", notes);
        return post("/payments/" + paymentId + "/refund", body, "refund payment");
    }

    /**
     * Verifies the handshake Checkout hands back to the browser.
     *
     * <p>The browser is not trusted to report its own success: the signature is
     * {@code HMAC_SHA256(order_id|payment_id, key_secret)}, which only a party
     * holding the secret can produce. Compared in constant time so a caller
     * cannot recover a valid signature byte by byte from response timing.</p>
     */
    public boolean isValidPaymentSignature(String orderId, String paymentId, String signature) {
        if (orderId == null || paymentId == null || signature == null) {
            return false;
        }
        return matches(hmacSha256Hex(orderId + "|" + paymentId, properties.getKeySecret()), signature);
    }

    /**
     * Verifies a webhook callback against the raw request body.
     *
     * <p>Must be given the bytes exactly as received: re-serialising the parsed
     * JSON reorders keys and drops whitespace, and the digest is over the
     * original octets.</p>
     */
    public boolean isValidWebhookSignature(String rawBody, String signature) {
        if (!properties.hasWebhookSecret() || rawBody == null || signature == null) {
            return false;
        }
        return matches(hmacSha256Hex(rawBody, properties.getWebhookSecret()), signature);
    }

    /** Rupees to paise. Razorpay rejects anything that is not a whole minor unit. */
    public static long toMinorUnits(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    /** Paise back to rupees, for comparing a gateway amount against our own. */
    public static BigDecimal fromMinorUnits(long minorUnits) {
        return BigDecimal.valueOf(minorUnits).movePointLeft(2);
    }

    private JsonNode post(String path, Object body, String action) {
        try {
            return restClient.post()
                    .uri(path)
                    .body(body)
                    .retrieve()
                    .onStatus(status -> status.isError(), (request, response) -> {
                        throw describeFailure(action, response.getStatusCode().value(),
                                new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
                    })
                    .body(JsonNode.class);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Razorpay call failed: action={}, path={}", action, path, ex);
            throw new BusinessException("Payment gateway is unreachable. Please try again.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private JsonNode get(String path, String action) {
        try {
            return restClient.get()
                    .uri(path)
                    .retrieve()
                    .onStatus(status -> status.isError(), (request, response) -> {
                        throw describeFailure(action, response.getStatusCode().value(),
                                new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
                    })
                    .body(JsonNode.class);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Razorpay call failed: action={}, path={}", action, path, ex);
            throw new BusinessException("Payment gateway is unreachable. Please try again.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Razorpay's own wording is logged but not returned: it can name internal
     * gateway state, and the candidate can act on none of it.
     */
    private BusinessException describeFailure(String action, int status, String responseBody) {
        log.error("Razorpay rejected request: action={}, status={}, body={}", action, status, responseBody);
        return new BusinessException("Payment gateway could not " + action + ". Please try again.",
                HttpStatus.BAD_GATEWAY);
    }

    private static String basicAuthHeader(RazorpayProperties properties) {
        String credentials = properties.getKeyId() + ":" + properties.getKeySecret();
        return "Basic " + java.util.Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacSha256Hex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to compute Razorpay signature", ex);
        }
    }

    private static boolean matches(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.trim().getBytes(StandardCharsets.UTF_8));
    }
}
