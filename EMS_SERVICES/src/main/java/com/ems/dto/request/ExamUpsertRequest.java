package com.ems.dto.request;

import java.math.BigDecimal;

import com.ems.enums.CertificationLevel;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * @param totalQuestions             how many questions one attempt draws
 * @param lowSeverityPercentage      share of the paper drawn from LOW questions
 * @param mediumSeverityPercentage   share drawn from MEDIUM questions
 * @param highSeverityPercentage     share drawn from HIGH questions
 *
 *        <p>The four blueprint fields are optional as a group: a caller that
 *        omits them gets the standard 30-question paper at 20/40/40 rather than
 *        an exam with nothing in it. The three shares must add up to 100, which
 *        is checked in the service because no single-field annotation can see
 *        the other two.</p>
 */
public record ExamUpsertRequest(
        @NotBlank @Size(max = 50) String examCode,
        @NotBlank @Size(max = 255) String examName,
        @NotNull CertificationLevel certificationLevel,
        @NotNull @Positive Integer durationMinutes,
        @NotNull @DecimalMin("0.0") BigDecimal totalMarks,
        @NotNull @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal passingPercentage,
        @Positive Integer totalQuestions,
        @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal lowSeverityPercentage,
        @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal mediumSeverityPercentage,
        @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal highSeverityPercentage) {
}
