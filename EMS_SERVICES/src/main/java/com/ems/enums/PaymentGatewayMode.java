package com.ems.enums;

/**
 * What kind of money a payment was taken in.
 *
 * <p>Stamped on the payment when it is opened, from the credentials in force at
 * that moment, because that is the only time it can be known: keys are swapped
 * between test and live without touching rows already written, and Razorpay's
 * payment object carries no mode flag of its own to read back later.</p>
 */
public enum PaymentGatewayMode {

    /** A real gateway on live keys. Real money moved. */
    LIVE,

    /** A real gateway on test keys. The full checkout ran; nothing was charged. */
    TEST,

    /** No gateway at all. The outcome was asserted by the browser. */
    SIMULATED
}
