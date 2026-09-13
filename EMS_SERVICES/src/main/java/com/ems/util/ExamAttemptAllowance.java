package com.ems.util;

import com.ems.entity.CertificationApplication;
import com.ems.enums.PaymentStatus;

/**
 * How many sittings a payment buys, and how many of them are left.
 *
 * <p>One payment covers as many attempts as the level's policy allowed at the
 * moment it was paid. The first attempt is the paid application itself; each
 * further one is a retake application that points back at it, created without a
 * charge after an attempt that did not pass. The rules for reading that chain
 * live here rather than in a service because several places need the same
 * answer — re-applying, the start and scheduling refusals, the dashboard rows
 * and the result screen — and a candidate told "2 attempts left" by one of them
 * must not be asked to pay by another.</p>
 */
public final class ExamAttemptAllowance {

    /** A level with no saved policy: one attempt per payment, as before retakes existed. */
    public static final int DEFAULT_ATTEMPTS_PER_PAYMENT = 1;

    public static final int MIN_ATTEMPTS_PER_PAYMENT = 1;

    public static final int MAX_ATTEMPTS_PER_PAYMENT = 10;

    private ExamAttemptAllowance() {
    }

    /** Which sitting on its payment this application is; 1 when unrecorded. */
    public static int attemptNumber(CertificationApplication application) {
        Integer number = application.getAttemptNumber();
        return number == null || number < 1 ? 1 : number;
    }

    /**
     * How many sittings the payment behind this application covers.
     *
     * <p>Null reads as one. It is only null on an application that has not been
     * paid, which has bought nothing yet, or on one paid before attempts were
     * recorded, which bought a single sitting. Reading it as the level's current
     * policy instead would hand every past failure a free retake the moment an
     * administrator raised the allowance.</p>
     */
    public static int attemptsAllowed(CertificationApplication application) {
        Integer allowed = application.getAttemptsAllowed();
        return allowed == null || allowed < 1 ? DEFAULT_ATTEMPTS_PER_PAYMENT : allowed;
    }

    /**
     * Sittings the payment still covers after this one.
     *
     * <p>Zero unless the payment stands. A refund takes the unused attempts with
     * it, and an application that was never paid has no attempts to spare.</p>
     */
    public static int attemptsRemaining(CertificationApplication application) {
        if (application.getPaymentStatus() != PaymentStatus.SUCCESS) {
            return 0;
        }
        return Math.max(0, attemptsAllowed(application) - attemptNumber(application));
    }

    /**
     * Whether the next sitting is already paid for: this attempt ended without a
     * pass, and the payment behind it covers another.
     */
    public static boolean retakeAvailable(CertificationApplication application) {
        return application.getApplicationStatus() != null
                && application.getApplicationStatus().isUnsuccessfulAttempt()
                && attemptsRemaining(application) > 0;
    }

    /** The application the fee was paid against: this one, or the one it is a retake of. */
    public static CertificationApplication paidApplication(CertificationApplication application) {
        return application.getPaidApplication() == null ? application : application.getPaidApplication();
    }
}
