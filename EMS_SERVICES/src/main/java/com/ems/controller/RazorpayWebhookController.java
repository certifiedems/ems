package com.ems.controller;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ems.enums.PaymentStatus;
import com.ems.service.PaymentService;
import com.ems.service.payment.PaymentInstrument;
import com.ems.service.payment.RazorpayClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Razorpay's server-to-server callback.
 *
 * <p>This is the authoritative settlement path, not a backup for it. The browser
 * callback only fires if the candidate's tab survives to see the result; a
 * closed laptop, a dead network, or a bank page that takes its time all end with
 * money taken and nothing told to us. Razorpay retries this endpoint for hours,
 * so it is what makes those payments arrive.</p>
 *
 * <p>Unauthenticated by design — Razorpay holds no JWT. Trust comes from the
 * HMAC over the raw body instead, which is why the body is bound as a
 * {@link String}: re-serialising parsed JSON changes the bytes the signature was
 * computed over and every callback would fail to verify.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/payments/webhooks")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.payment.razorpay.enabled", havingValue = "true")
public class RazorpayWebhookController {

    private static final String EVENT_PAYMENT_CAPTURED = "payment.captured";
    private static final String EVENT_PAYMENT_FAILED = "payment.failed";

    private final PaymentService paymentService;
    private final ObjectProvider<RazorpayClient> razorpayClientProvider;
    private final ObjectMapper objectMapper;

    @PostMapping("/razorpay")
    public ResponseEntity<Void> handle(
            @RequestHeader(name = "X-Razorpay-Signature", required = false) String signature,
            @RequestBody String rawBody) {

        RazorpayClient client = razorpayClientProvider.getIfAvailable();
        if (client == null || !client.isValidWebhookSignature(rawBody, signature)) {
            log.warn("Rejected Razorpay webhook with invalid signature");
            /*
             * 401 rather than 400: Razorpay retries on 5xx and stops on 4xx, and
             * an unsigned body is not something a retry will fix.
             */
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            JsonNode event = objectMapper.readTree(rawBody);
            String eventName = event.path("event").asText();
            JsonNode entity = event.path("payload").path("payment").path("entity");
            String orderId = text(entity, "order_id");
            String paymentId = text(entity, "id");

            if (orderId == null) {
                log.info("Razorpay webhook carried no order, ignored: event={}", eventName);
                return ResponseEntity.ok().build();
            }

            // Kept for failures as well as captures: "the UPI attempt failed" is
            // what an admin needs when a candidate asks why they were not let in.
            PaymentInstrument instrument = PaymentInstrument.fromRazorpay(entity);

            switch (eventName) {
                case EVENT_PAYMENT_CAPTURED -> paymentService.settleFromGatewayCallback(
                        orderId,
                        paymentId,
                        PaymentStatus.SUCCESS,
                        RazorpayClient.fromMinorUnits(entity.path("amount").asLong()),
                        text(entity, "currency"),
                        instrument);
                case EVENT_PAYMENT_FAILED -> paymentService.settleFromGatewayCallback(
                        orderId, paymentId, PaymentStatus.FAILED, null, null, instrument);
                // Everything else Razorpay is subscribed to (refunds, settlements,
                // disputes) is acknowledged so it is not retried, and left to the
                // dashboard until this system has a use for it.
                default -> log.debug("Razorpay webhook not handled: event={}", eventName);
            }
        } catch (Exception ex) {
            /*
             * Signed and therefore genuine, but we could not act on it. A 500
             * tells Razorpay to retry, which is the right outcome for a
             * transient database failure and harmless for a permanent one:
             * settlement is idempotent.
             */
            log.error("Failed to process Razorpay webhook", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        return ResponseEntity.ok().build();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
