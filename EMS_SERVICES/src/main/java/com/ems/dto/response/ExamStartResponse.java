package com.ems.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ems.enums.CertificationLevel;

public record ExamStartResponse(
        Long applicationId,
        Long examId,
        String examCode,
        CertificationLevel certificationLevel,
        UUID sessionToken,
        Long examSessionId,
        Instant startedAt,
        int questionCount,
        List<Long> questionIds,
        ExamQuestionPayloadResponse firstQuestion,

        /**
         * Seconds left in this attempt, counted from when the session began.
         *
         * <p>Sent because the client cannot be trusted to work it out. It used to
         * start its countdown at the exam's full duration on every start, resume
         * included, which made re-entering an attempt a way to buy another full
         * sitting — repeatable as often as the candidate liked. Measured here
         * against the stored start time, it cannot be extended by anything the
         * candidate does to their browser.</p>
         *
         * <p>Zero means the attempt is out of time and the client should submit
         * immediately.</p>
         */
        long remainingSeconds,

        /** True when this call rejoined an attempt already in progress. */
        boolean resumed,

        /**
         * What the candidate had already answered, on a resume.
         *
         * <p>Null on a fresh start, and null on a resume of an attempt that was
         * interrupted before the first autosave landed. Rejoining used to return
         * an empty paper: the session and its clock survived the interruption but
         * the answers did not, because they existed only in the browser that was
         * cut off. This is the same attempt handed back intact.</p>
         */
        ExamProgressResponse savedProgress,

        /**
         * Which sitting on its payment this attempt is, and how many the payment
         * covers, so the exam screen can tell a terminated candidate whether
         * their next attempt is already paid for.
         */
        int attemptNumber,
        int attemptsAllowed) {
}
