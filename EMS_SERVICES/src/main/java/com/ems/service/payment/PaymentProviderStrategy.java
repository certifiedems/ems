package com.ems.service.payment;

import com.ems.dto.request.PaymentRefundRequest;
import com.ems.dto.request.PaymentVerificationRequest;
import com.ems.entity.Payment;
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

    PaymentProviderResult initiate(Payment payment);

    PaymentProviderResult verify(Payment payment, PaymentVerificationRequest request);

    PaymentProviderResult refund(Payment payment, PaymentRefundRequest request);
}
