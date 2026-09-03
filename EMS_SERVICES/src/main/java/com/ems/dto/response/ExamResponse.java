package com.ems.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

import com.ems.enums.CertificationLevel;
import com.ems.enums.ExamStatus;

/**
 * <p>Carries both the mix the admin set and the whole-question counts it works
 * out to. The counts are derived, but they are derived here rather than in the
 * client so that the paper the admin screen previews is the paper the server
 * will actually build — rounding a 30/40/30 split of 25 questions two ways
 * would show one number and deliver another.</p>
 */
public record ExamResponse(
        Long id,
        String examCode,
        String examName,
        CertificationLevel certificationLevel,
        Integer durationMinutes,
        BigDecimal totalMarks,
        BigDecimal passingPercentage,
        Integer totalQuestions,
        BigDecimal lowSeverityPercentage,
        BigDecimal mediumSeverityPercentage,
        BigDecimal highSeverityPercentage,
        Integer lowSeverityQuestions,
        Integer mediumSeverityQuestions,
        Integer highSeverityQuestions,
        ExamStatus examStatus,
        boolean published,
        Instant scheduledStartTime,
        Instant scheduledEndTime,
        Instant createdAt,
        Instant updatedAt) {
}
