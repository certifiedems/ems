package com.ems.dto.response;

import java.time.Instant;
import java.time.LocalDate;

import com.ems.enums.CertificationApplicationStatus;
import com.ems.enums.CertificationLevel;
import com.ems.enums.PaymentStatus;

public record ExamWorkflowApplicationResponse(
        Long applicationId,
        String userId,
        Long examId,
        String examCode,
        CertificationLevel certificationLevel,
        CertificationApplicationStatus applicationStatus,
        PaymentStatus paymentStatus,
        LocalDate appliedOn,
        Instant scheduledExamTime,

        /**
         * The stretch of time in which this booking may be started; both null
         * until the candidate schedules.
         *
         * <p>Sent rather than left to the client to work out from
         * {@code scheduledExamTime}, so the grace either side of the slot is
         * stated in one place. A client that hardcoded its own copy would go on
         * offering a live "Start" after the rule moved, and the candidate would
         * meet the refusal only after clicking it.</p>
         */
        Instant examWindowStart,
        Instant examWindowEnd,

        /**
         * The stretch of calendar time in which a slot may be booked at all.
         *
         * <p>Not the same thing as {@code examWindowStart/End} above, and the
         * two are easy to confuse: those describe the few minutes around a
         * booking that already exists, this describes the exam's own window and
         * so bounds every booking that could be made. Null when the application
         * carries no exam, or when the exam leaves that bound open.</p>
         *
         * <p>Sent because a client that cannot see this bound has no choice but
         * to offer every date as bookable and let the candidate find the edge by
         * being refused — which is exactly how a candidate whose exam window has
         * closed ends up retrying dates forever.</p>
         */
        Instant bookingOpensAt,
        Instant bookingClosesAt,

        String remarks,
        boolean canReApply,
        boolean restartRequired,
        String restartMessage,

        /** Which sitting on its payment this application is; 1 for the paid attempt. */
        int attemptNumber,

        /** Sittings the payment covers; 1 until the application is paid. */
        int attemptsAllowed,

        /** Sittings the payment still covers after this one. */
        int attemptsRemaining,

        /**
         * Whether the next attempt is already paid for: this one ended without a
         * pass and the payment covers another. When true, re-applying starts that
         * attempt free of charge, and it goes straight to scheduling.
         */
        boolean retakeAvailable) {
}
