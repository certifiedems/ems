package com.ems.dto.request;

import java.time.Instant;

import com.ems.enums.PaymentGatewayMode;
import com.ems.enums.PaymentStatus;

/**
 * The admin payment console's filters. Every field is optional; a filter with
 * all of them null matches every payment.
 *
 * <p>Shared by the list and the report download so the file an admin exports is
 * always exactly the rows they were looking at.</p>
 *
 * @param search        matched against candidate name, email and user id, our
 *                      transaction id, and the gateway's order and payment ids
 * @param paymentMethod as recorded from the gateway, e.g. {@code UPI}
 * @param from          inclusive lower bound on when the payment was opened
 * @param to            exclusive upper bound on when the payment was opened
 */
public record AdminPaymentFilter(
        String search,
        PaymentStatus status,
        PaymentGatewayMode gatewayMode,
        String paymentMethod,
        Instant from,
        Instant to) {
}
