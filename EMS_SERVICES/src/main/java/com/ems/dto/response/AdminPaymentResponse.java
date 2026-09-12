package com.ems.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.ems.enums.CertificationApplicationStatus;
import com.ems.enums.CertificationLevel;
import com.ems.enums.PaymentGatewayMode;
import com.ems.enums.PaymentStatus;

public record AdminPaymentResponse(
        Long paymentId,
        String transactionId,
        PaymentStatus paymentStatus,
        BigDecimal amount,
        String currency,
        String provider,
        // How the candidate paid (UPI, CARD, NETBANKING, WALLET, ...) and the
        // instrument within it, as the gateway reported them. Null until the
        // gateway reports, and always null for simulated payments.
        String paymentMethod,
        String paymentMethodDetail,
        // LIVE, TEST or SIMULATED. Null only for gateway payments recorded
        // before the mode was tracked.
        PaymentGatewayMode gatewayMode,
        // When the attempt was opened. Every row has one; paymentDate is only
        // set once money has actually moved.
        Instant createdAt,
        Instant paymentDate,
        String providerReference,
        String providerOrderId,
        Long applicationId,
        CertificationApplicationStatus applicationStatus,
        LocalDate appliedOn,
        String userId,
        String candidateName,
        String candidateEmail,
        Long examId,
        String examCode,
        String examName,
        CertificationLevel certificationLevel) {
}
