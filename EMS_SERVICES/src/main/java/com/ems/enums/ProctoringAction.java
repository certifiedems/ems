package com.ems.enums;

public enum ProctoringAction {
    LOGGED,
    WARNING,
    /**
     * Recorded with its evidence, without counting toward the strike limit: the
     * outcome for a type the policy enforces as
     * {@link ViolationEnforcement#RECORD_ONLY}.
     */
    FLAGGED_FOR_REVIEW,
    EXAM_TERMINATED
}
