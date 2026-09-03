package com.ems.dto.response;

import java.time.Instant;

import com.ems.enums.EvidenceStorageKind;

/**
 * Metadata for one captured proctoring frame.
 *
 * <p>Carries no image bytes on purpose: an invigilator listing a session's
 * evidence must not stream megabytes of frames out of the database or the
 * bucket. Clients fetch the frame itself from
 * {@code /api/proctoring/evidence/{evidenceId}/frame}, which resolves whichever
 * storage kind this row reports.</p>
 */
public record ProctorEvidenceResponse(
        Long evidenceId,
        Long violationId,
        EvidenceStorageKind storageKind,
        String mediaType,
        long payloadBytes,
        Integer frameWidth,
        Integer frameHeight,
        Instant capturedAt) {
}
