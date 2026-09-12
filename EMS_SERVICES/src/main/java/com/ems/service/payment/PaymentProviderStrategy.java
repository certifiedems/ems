package com.ems.service.payment;

import com.ems.dto.request.PaymentRefundRequest;
import com.ems.dto.request.PaymentVerificationRequest;
import com.ems.entity.Payment;
import com.ems.enums.PaymentGatewayMode;
import com.ems.enums.PaymentProvider;

public interface PaymentProviderStrategy {

    PaymentProvider provider();

    /**
     * Whether this strategy only pretends to take money.
     *
     * <p>Simulated providers exist so the exam workflow can be run end to end
     * without a gateway account. That is a development convenience and a
     * production vulnerability: the moment one real gateway is configured, a
     * simulated one is a checkout that grants exam access for free. The default
     * is {@code true} because a strategy that has not thought about this is, by
     * definition, not settling real money.</p>
     */
    default boolean isSimulated() {
        return true;
    }

    /**
     * What kind of money a payment opened through this strategy right now is.
     *
     * <p>Read once, when the payment is opened, and stored on it. The default
     * follows {@link #isSimulated()}: a strategy that settles real money and has
     * no notion of test credentials is live.</p>
     */
    default PaymentGatewayMode gatewayMode() {
        return isSimulated() ? PaymentGatewayMode.SIMULATED : PaymentGatewayMode.LIVE;
    }

    PaymentProviderResult initiate(Payment payment);

    PaymentProviderResult verify(Payment payment, PaymentVerificationRequest request);

    PaymentProviderResult refund(Payment payment, PaymentRefundRequest request);

    /**
     * Asks the gateway what has become of a payment, with no payer involved.
     *
     * <p>The admin console's way to recover a payment whose browser callback and
     * webhook both went missing. Reports only — deciding what to do with the
     * answer is the caller's job. Returns {@code null} when there is no gateway
     * to ask: a simulated strategy, or a payment that never reached one.</p>
     */
    default PaymentProviderResult lookup(Payment payment) {
        return null;
    }
}
