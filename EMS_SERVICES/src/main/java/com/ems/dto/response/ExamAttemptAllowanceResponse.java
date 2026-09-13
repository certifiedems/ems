package com.ems.dto.response;

import com.ems.enums.CertificationLevel;

/**
 * What a payment made now buys at a level, for the candidate about to make it.
 *
 * @param attemptsPerPayment sittings the payment will cover, the first one included
 */
public record ExamAttemptAllowanceResponse(
        CertificationLevel certificationLevel,
        int attemptsPerPayment) {
}
