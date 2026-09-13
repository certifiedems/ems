package com.ems.dto.response;

import java.time.Instant;

import com.ems.enums.CertificationLevel;

/**
 * A level's attempt allowance, as the admin console edits it.
 */
public record ExamAttemptPolicyResponse(

        CertificationLevel certificationLevel,

        /** Sittings one payment covers, the first one included. */
        int attemptsPerPayment,

        /** Null until this level has been saved. Echo it back on save. */
        Long version,

        /** Who last saved this level; null while it is still the built-in single attempt. */
        String updatedBy,

        Instant updatedAt,

        /**
         * How many papers with no question in common the level's question bank
         * can build, for the published exam at this level that needs the most.
         * Null when the level has no published exam.
         *
         * <p>Each attempt is drawn from questions the candidate has not seen
         * first, so this is how many attempts are guaranteed to be entirely
         * fresh. An allowance above it still works: the later attempts reuse the
         * questions the candidate saw longest ago.</p>
         */
        Integer distinctPapers) {
}
