package com.ems.service.payment;

import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * How the payer actually paid — UPI, card, net banking, wallet — as the gateway
 * reports it.
 *
 * <p>Not something this app asks for. Checkout lists the methods and the payer
 * picks one inside the gateway's own window, so the gateway's payment object is
 * the only place the answer exists.</p>
 *
 * @param method normalised method name: {@code UPI}, {@code CARD},
 *               {@code NETBANKING}, {@code WALLET}, {@code EMI}, or whatever
 *               else the gateway reports, upper-cased
 * @param detail the instrument within that method where the gateway names one —
 *               the bank, the wallet, the UPI id, or a card's network and last
 *               four digits; {@code null} when it does not
 */
public record PaymentInstrument(String method, String detail) {

    private static final int METHOD_MAX_LENGTH = 30;
    private static final int DETAIL_MAX_LENGTH = 100;

    /**
     * Reads the instrument off a Razorpay payment entity.
     *
     * <p>Returns {@code null} rather than an empty instrument when the entity
     * names no method, so a caller can tell "the gateway did not say" apart from
     * an answer and never overwrite a recorded method with nothing.</p>
     *
     * <p>Card details are only present when Razorpay includes the {@code card}
     * object — webhooks usually do, a plain payment fetch does not — so a card
     * payment may be recorded as {@code CARD} with no detail.</p>
     */
    public static PaymentInstrument fromRazorpay(JsonNode payment) {
        String method = text(payment, "method");
        if (method == null) {
            return null;
        }

        String normalised = method.toUpperCase(Locale.ROOT);
        String detail = switch (normalised) {
            case "UPI" -> firstPresent(text(payment, "vpa"), text(payment.get("upi"), "vpa"));
            case "CARD" -> describeCard(payment.get("card"));
            case "EMI" -> firstPresent(describeCard(payment.get("card")), text(payment, "bank"));
            case "NETBANKING" -> text(payment, "bank");
            case "WALLET" -> text(payment, "wallet");
            default -> firstPresent(text(payment, "bank"), text(payment, "wallet"));
        };

        return new PaymentInstrument(truncate(normalised, METHOD_MAX_LENGTH), truncate(detail, DETAIL_MAX_LENGTH));
    }

    /** "Visa credit ending 1111", or as much of it as the gateway supplied. */
    private static String describeCard(JsonNode card) {
        StringBuilder description = new StringBuilder();
        for (String part : new String[] { text(card, "network"), text(card, "type") }) {
            if (part != null) {
                description.append(description.isEmpty() ? "" : " ").append(part);
            }
        }
        String last4 = text(card, "last4");
        if (last4 != null) {
            description.append(description.isEmpty() ? "" : " ").append("ending ").append(last4);
        }
        return description.isEmpty() ? null : description.toString();
    }

    private static String firstPresent(String first, String second) {
        return first != null ? first : second;
    }

    private static String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }
}
