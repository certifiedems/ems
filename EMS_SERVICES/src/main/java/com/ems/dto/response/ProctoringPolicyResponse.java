package com.ems.dto.response;

import java.time.Instant;
import java.util.Map;

import com.ems.enums.ViolationEnforcement;
import com.ems.enums.ViolationType;

/**
 * Proctoring rules as the admin console edits them.
 */
public record ProctoringPolicyResponse(

        /** {@code DEFAULT} for the platform-wide rules, {@code EXAM} for one exam's. */
        String scope,

        Long examId,
        String examCode,
        String examName,

        /**
         * True for an exam with no rules of its own. The values shown are the
         * default policy's, and saving creates the exam's own copy of them.
         */
        boolean inheritsDefault,

        int strikeLimit,
        int unidentifiedSoundGrace,
        int voiceMinDbAboveFloor,
        int backgroundNoiseMinDbAboveFloor,
        int unidentifiedSoundMinDbAboveFloor,

        /** One entry for every violation type an administrator can configure. */
        Map<ViolationType, ViolationEnforcement> rules,

        /** Null until this scope has been saved. Echo it back on save. */
        Long version,

        /** Who last saved these rules; null while they are still the built-in ones. */
        String updatedBy,

        Instant updatedAt) {
}
