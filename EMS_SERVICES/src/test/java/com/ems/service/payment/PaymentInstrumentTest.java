package com.ems.service.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Covers how a Razorpay payment entity becomes the "Payment mode" an admin sees.
 * The shapes below are the ones Razorpay actually sends: the detail lives in a
 * different field for every method, and some of it is only present sometimes.
 */
class PaymentInstrumentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("UPI records the payer's UPI id")
    void upi() {
        assertThat(PaymentInstrument.fromRazorpay(node("{\"method\":\"upi\",\"vpa\":\"candidate@okhdfcbank\"}")))
                .isEqualTo(new PaymentInstrument("UPI", "candidate@okhdfcbank"));
    }

    @Test
    @DisplayName("UPI falls back to the id nested under the upi object")
    void upiNestedVpa() {
        assertThat(PaymentInstrument.fromRazorpay(
                node("{\"method\":\"upi\",\"vpa\":null,\"upi\":{\"vpa\":\"candidate@ybl\",\"flow\":\"intent\"}}")))
                .isEqualTo(new PaymentInstrument("UPI", "candidate@ybl"));
    }

    @Test
    @DisplayName("a card is described by network, type and last four when Razorpay includes it")
    void cardWithDetails() {
        assertThat(PaymentInstrument.fromRazorpay(node(
                "{\"method\":\"card\",\"card\":{\"network\":\"Visa\",\"type\":\"credit\",\"last4\":\"1111\"}}")))
                .isEqualTo(new PaymentInstrument("CARD", "Visa credit ending 1111"));
    }

    @Test
    @DisplayName("a card without the card object is still recorded as a card")
    void cardWithoutDetails() {
        assertThat(PaymentInstrument.fromRazorpay(node("{\"method\":\"card\",\"card_id\":\"card_123\"}")))
                .isEqualTo(new PaymentInstrument("CARD", null));
    }

    @Test
    @DisplayName("net banking records the bank and wallets record the wallet")
    void netbankingAndWallet() {
        assertThat(PaymentInstrument.fromRazorpay(node("{\"method\":\"netbanking\",\"bank\":\"HDFC\"}")))
                .isEqualTo(new PaymentInstrument("NETBANKING", "HDFC"));
        assertThat(PaymentInstrument.fromRazorpay(node("{\"method\":\"wallet\",\"wallet\":\"paytm\"}")))
                .isEqualTo(new PaymentInstrument("WALLET", "paytm"));
    }

    @Test
    @DisplayName("EMI without card details falls back to the bank")
    void emiFallsBackToBank() {
        assertThat(PaymentInstrument.fromRazorpay(node("{\"method\":\"emi\",\"bank\":\"ICIC\"}")))
                .isEqualTo(new PaymentInstrument("EMI", "ICIC"));
    }

    @Test
    @DisplayName("a method this app has not seen before is kept, not dropped")
    void unknownMethodIsKept() {
        assertThat(PaymentInstrument.fromRazorpay(node("{\"method\":\"paylater\",\"wallet\":\"lazypay\"}")))
                .isEqualTo(new PaymentInstrument("PAYLATER", "lazypay"));
    }

    @Test
    @DisplayName("no method means no instrument, so nothing already recorded is overwritten")
    void noMethod() {
        assertThat(PaymentInstrument.fromRazorpay(node("{\"status\":\"captured\"}"))).isNull();
        assertThat(PaymentInstrument.fromRazorpay(node("{\"method\":\"  \"}"))).isNull();
        assertThat(PaymentInstrument.fromRazorpay(MAPPER.missingNode())).isNull();
        assertThat(PaymentInstrument.fromRazorpay(null)).isNull();
    }

    @Test
    @DisplayName("detail is cut to fit its column rather than failing the settlement")
    void detailIsTruncated() {
        String longVpa = "a".repeat(150) + "@okaxis";

        PaymentInstrument instrument = PaymentInstrument.fromRazorpay(
                node("{\"method\":\"upi\",\"vpa\":\"" + longVpa + "\"}"));

        assertThat(instrument.detail()).hasSize(100);
    }

    private static JsonNode node(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
