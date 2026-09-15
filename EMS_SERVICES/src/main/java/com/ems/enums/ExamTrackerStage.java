package com.ems.enums;

/**
 * Where one paid or booked application stands on the way from payment to
 * result, as the admin exam tracker groups it.
 *
 * <p>Derived, never stored: it is read off the application, its latest session
 * and that session's result at the moment of asking, so it cannot fall out of
 * step with them.</p>
 */
public enum ExamTrackerStage {

    /** Paid for, but no slot booked yet. */
    AWAITING_SLOT,

    /** A slot is booked and can still be attended; the attempt has not started. */
    UPCOMING,

    /** The attempt is running now. */
    LIVE,

    /** The slot's start window closed without the attempt being started. */
    MISSED,

    /** The attempt was submitted and scored. */
    COMPLETED,

    /** The attempt was ended by proctoring before it could be scored. */
    TERMINATED,

    /** Closed without a sitting: rejected, expired, refunded, or a result with no session on record. */
    CLOSED
}
