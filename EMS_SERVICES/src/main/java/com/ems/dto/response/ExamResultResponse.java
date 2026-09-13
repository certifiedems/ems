package com.ems.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

import com.ems.enums.ResultStatus;

public record ExamResultResponse(
        Long attemptId,
        Long sessionId,
        Long applicationId,
        String examCode,
        Integer totalQuestions,
        Integer attemptedQuestions,
        Integer correctAnswers,
        Integer wrongAnswers,
        BigDecimal totalMarks,
        BigDecimal obtainedMarks,
        BigDecimal percentage,
        ResultStatus resultStatus,
        Instant submittedAt,
        int attemptNumber,
        int attemptsAllowed,
        int attemptsRemaining,

        /** Whether the candidate's next attempt is already paid for. */
        boolean retakeAvailable) {
}
