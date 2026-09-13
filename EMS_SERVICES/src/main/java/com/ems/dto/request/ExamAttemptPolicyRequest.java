package com.ems.dto.request;

import com.ems.util.ExamAttemptAllowance;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * A level's attempt allowance, as saved from the admin console.
 *
 * @param attemptsPerPayment sittings one payment covers, the first one included
 * @param version            the version the admin loaded; a stale one is refused so a
 *                           second admin's save is not silently overwritten. Null skips
 *                           the check.
 */
public record ExamAttemptPolicyRequest(

        @NotNull(message = "attemptsPerPayment is required")
        @Min(value = ExamAttemptAllowance.MIN_ATTEMPTS_PER_PAYMENT,
                message = "attemptsPerPayment must be at least {value}")
        @Max(value = ExamAttemptAllowance.MAX_ATTEMPTS_PER_PAYMENT,
                message = "attemptsPerPayment must not exceed {value}")
        Integer attemptsPerPayment,

        Long version) {
}
