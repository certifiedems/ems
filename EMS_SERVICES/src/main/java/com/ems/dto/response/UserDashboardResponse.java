package com.ems.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.ems.enums.CertificationApplicationStatus;
import com.ems.enums.CertificationLevel;
import com.ems.enums.PaymentStatus;

public record UserDashboardResponse(
        DashboardUserDetails user,
        List<CertificationSummaryResponse> activeCertifications,
        List<CertificationSummaryResponse> certificationHistory,
        List<DashboardExamStatus> examStatuses,
        List<DashboardLevelProgress> levelProgress,
        List<DashboardActivity> recentActivity,
        DashboardReportSummary reportSummary) {

    public record DashboardUserDetails(
            String userId,
            String firstName,
            String lastName,
            String email,
            String mobileNumber,
            String currentSkillLevel,
            String currentOrganization,
            String qualification,
            String profilePhotoUrl) {
    }

    public record DashboardExamStatus(
            Long applicationId,
            CertificationLevel certificationLevel,
            CertificationApplicationStatus applicationStatus,
            PaymentStatus paymentStatus,
            LocalDate appliedOn,
            String remarks,

            /**
             * When the candidate booked their sitting; null until they schedule.
             *
             * <p>The applications list has to choose between three next steps —
             * pay, schedule, or start — and payment status alone only separates
             * the first from the other two. Without this it sent every paid
             * candidate back to the scheduling screen, including the ones who had
             * already scheduled and only wanted to begin.</p>
             */
            Instant scheduledExamTime,

            /**
             * The stretch of time in which the booking may be started; both null
             * until the candidate schedules.
             *
             * <p>The applications list and the dashboard both offer a "Start
             * Exam" button, and a booking that is not due for three days must
             * not carry one. They read the bounds from here instead of holding
             * their own copy of the grace period, so the button and the server's
             * answer cannot drift apart.</p>
             */
            Instant examWindowStart,
            Instant examWindowEnd,

            /**
             * The stretch of calendar time in which a slot may be booked at all.
             *
             * <p>The exam's own window, not the grace around a booking, so it
             * bounds every slot the candidate could pick rather than the one
             * they already picked. Null when the application carries no exam, or
             * when the exam leaves that bound open.</p>
             *
             * <p>The applications list reads it to know whether "Reschedule" can
             * lead anywhere: once this has closed there is no time left to move
             * to, and offering the button sends the candidate to a picker that
             * will refuse every date they try.</p>
             */
            Instant bookingOpensAt,
            Instant bookingClosesAt,

            /**
             * Whether an attempt on this application is still running.
             *
             * <p>The status alone cannot say. {@code IN_PROGRESS} means the
             * application is paid for, which covers both a candidate who has not
             * sat down yet and one who is mid-exam, and the client offered both
             * of them the same "continue" that led back to scheduling. It now
             * has to tell them apart: one needs the scheduling step, the other
             * needs to be put back into the exam they are already sitting.</p>
             */
            boolean attemptInProgress) {
    }

    /**
     * Where the candidate stands on one rung of the L1 → L2 → L3 ladder.
     *
     * The dashboard draws this as a progress timeline, so it needs the same
     * answer the apply flow would give — hence {@code state} is derived from the
     * real eligibility rule rather than re-guessed in the browser from the
     * certification list, which cannot see prerequisite expiry or an
     * application that is already open.
     */
    public record DashboardLevelProgress(
            CertificationLevel certificationLevel,
            LevelState state,
            /** Best percentage across every submitted attempt at this level; null if never sat. */
            BigDecimal bestPercentage,
            /** Number of submitted attempts at this level. */
            int attempts,
            Long certificationId,
            LocalDate certificationExpiryDate,
            /** Set when an application is mid-flight, so the UI can deep-link into it. */
            Long openApplicationId,
            /** Why the level is locked; null unless state is LOCKED. */
            String blockedReason) {
    }

    public enum LevelState {
        /** Holds a live certification for this level. */
        COMPLETED,
        /** An application is applied / scheduled / being sat right now. */
        IN_PROGRESS,
        /** Prerequisites met — the candidate can apply. */
        AVAILABLE,
        /** Prerequisite level not held (or expired). */
        LOCKED
    }

    public record DashboardActivity(
            ActivityType type,
            String message,
            Instant occurredAt) {
    }

    public enum ActivityType {
        APPLICATION,
        RESULT,
        CERTIFICATE
    }

    public record DashboardReportSummary(
            long totalApplications,
            long activeCertifications,
            long expiredCertifications,
            long passedApplications,
            long failedApplications) {
    }
}
