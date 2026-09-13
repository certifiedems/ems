package com.ems.dto.response;

import com.ems.enums.CertificationLevel;

public record ExamProctoringPolicySummaryResponse(
        Long examId,
        String examCode,
        String examName,
        CertificationLevel certificationLevel,
        boolean published,

        /** True when the exam runs under rules of its own rather than the default. */
        boolean customPolicy) {
}
