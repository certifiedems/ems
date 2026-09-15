package com.ems.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.ems.enums.PaymentGatewayMode;
import com.ems.enums.PaymentStatus;
import com.ems.enums.ProctoringAction;
import com.ems.enums.QuestionSeverity;
import com.ems.enums.ViolationType;

/**
 * One application end to end, for the admin exam tracker's detail screen:
 * the payment, the slot, the attempt with every question it drew and what was
 * answered to each, the violations recorded against it and the certificate it
 * earned.
 *
 * <p>{@code questions} is empty until the attempt starts, because the paper is
 * drawn at that moment and not when the slot is booked.</p>
 */
public record AdminExamBookingDetailResponse(
        AdminExamBookingResponse booking,
        PaymentDetails payment,
        SessionDetails sessionDetails,
        AnswerSource answerSource,
        List<QuestionLine> questions,
        List<ViolationLine> violations,
        CertificateDetails certificate) {

    /** Where the answers in {@code questions} were read from. */
    public enum AnswerSource {

        /** The answers the attempt was scored on. */
        SUBMISSION,

        /**
         * The session's latest autosave: the live answers of a running attempt, or
         * the closest record left of one submitted before submissions were kept.
         */
        AUTOSAVE,

        /** No answers on record. */
        NONE
    }

    public enum AnswerState {
        CORRECT,
        WRONG,
        UNANSWERED,

        /**
         * No verdict is possible: the attempt was scored before its answers were
         * stored, or the question has since been deleted.
         */
        NOT_JUDGED
    }

    /** The payment behind the sitting; for a retake, the payment of the application that covers it. */
    public record PaymentDetails(
            String transactionId,
            PaymentStatus status,
            BigDecimal amount,
            String currency,
            String paymentMethod,
            String paymentMethodDetail,
            PaymentGatewayMode gatewayMode,
            Instant paidAt) {
    }

    public record SessionDetails(
            String ipAddress,
            String browserFingerprint,
            Instant rulesAcceptedAt,
            String rulesVersion) {
    }

    /**
     * One question of the paper, in the order the candidate was given it.
     * A question deleted since carries only its id, number and answer.
     */
    public record QuestionLine(
            int number,
            Long questionId,
            String questionCode,
            String category,
            String questionType,
            QuestionSeverity severity,
            BigDecimal marks,
            String questionText,
            List<String> options,
            List<String> correctOptions,
            List<String> selectedOptions,
            AnswerState state,
            boolean markedForReview) {
    }

    public record ViolationLine(
            Long violationId,
            ViolationType violationType,
            Integer violationLevel,
            String description,
            Instant detectedAt,
            ProctoringAction actionTaken) {
    }

    public record CertificateDetails(String certificateNumber, LocalDate issueDate, LocalDate expiryDate) {
    }
}
