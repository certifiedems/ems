package com.ems.enums;

/**
 * What a proctoring policy does with one kind of detection.
 *
 * <p>Chosen per {@link ViolationType} by an administrator, on the default policy
 * or on an exam's own. See {@link com.ems.service.EffectiveProctoringPolicy}.</p>
 */
public enum ViolationEnforcement {

    /**
     * Not monitored. The exam client does not raise it, and a detection that
     * arrives anyway — from a client that loaded its rules before an admin
     * changed them — is dropped without a record.
     */
    DISABLED,

    /** Recorded with its evidence, but never counted toward the strike limit. */
    RECORD_ONLY,

    /** Recorded and counted toward the strike limit. */
    STRIKE
}
