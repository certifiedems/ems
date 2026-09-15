package com.ems.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.ems.enums.CertificationApplicationStatus;
import com.ems.enums.CertificationLevel;
import com.ems.enums.ExamStatus;
import com.ems.enums.ExamTrackerStage;
import com.ems.enums.PaymentStatus;
import com.ems.enums.ResultStatus;

/**
 * One row of the admin exam tracker: a paid or booked application, followed
 * from its slot through the attempt to the result.
 *
 * <p>{@code slot} is null until a slot is booked, {@code session} until the
 * attempt starts, and {@code result} until it is scored.</p>
 */
public record AdminExamBookingResponse(
        Long applicationId,
        ExamTrackerStage stage,
        CertificationApplicationStatus applicationStatus,
        PaymentStatus paymentStatus,
        LocalDate appliedOn,
        int attemptNumber,
        int attemptsAllowed,
        Candidate candidate,
        ExamDetails exam,
        Slot slot,
        Session session,
        Result result) {

    public record Candidate(String userId, String name, String email, String mobileNumber) {
    }

    /** {@code questionsPerAttempt} is the exam's blueprint: how many questions one paper draws. */
    public record ExamDetails(
            Long examId,
            String examCode,
            String examName,
            CertificationLevel certificationLevel,
            Integer durationMinutes,
            Integer questionsPerAttempt,
            BigDecimal passingPercentage) {
    }

    /**
     * The booked sitting. {@code end} is when the exam's time runs out for a
     * candidate who starts on the dot; the start window is when the attempt may
     * begin at all, and {@code startWindowOpen} says whether that is now.
     */
    public record Slot(
            Instant start,
            Instant end,
            Instant startWindowOpensAt,
            Instant startWindowClosesAt,
            boolean startWindowOpen) {
    }

    /**
     * The attempt as it ran. {@code questionsAnswered} comes from the result once
     * the attempt is scored, and from the latest autosave until then.
     */
    public record Session(
            Long sessionId,
            ExamStatus status,
            Instant startedAt,
            Instant endedAt,
            int questionsAssigned,
            int questionsAnswered,
            int markedForReview,
            Integer lastQuestionNumber,
            Instant progressSavedAt,
            int violationCount) {
    }

    public record Result(
            int totalQuestions,
            int attemptedQuestions,
            int correctAnswers,
            int wrongAnswers,
            BigDecimal obtainedMarks,
            BigDecimal percentage,
            ResultStatus resultStatus,
            Instant submittedAt) {
    }
}
