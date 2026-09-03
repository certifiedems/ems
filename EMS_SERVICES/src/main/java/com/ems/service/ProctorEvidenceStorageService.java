package com.ems.service;

/**
 * Storage backend for proctoring evidence frames.
 *
 * <p>Exists so {@code proctor_evidence_blobs} can hold a pointer instead of a
 * multi-hundred-KB base64 string. Evidence grows without bound — every violation
 * of every attempt, forever — so it must not compete with exam data (questions,
 * answers, submissions) for the database's storage quota.</p>
 *
 * <p>Implementations are selected by Spring profile, exactly like
 * {@link ProfilePhotoStorageService}: object storage in {@code prod}, inline
 * base64 everywhere else so local and CI runs need no R2 credentials.</p>
 */
public interface ProctorEvidenceStorageService {

    /**
     * Whether frames should be offloaded rather than written inline.
     *
     * <p>The write path branches on this rather than on the active profile, so a
     * deployment can fall back to inline storage by config alone if R2 is
     * unreachable.</p>
     */
    boolean isObjectStorageEnabled();

    /**
     * Uploads one decoded frame and returns the key to persist in
     * {@code object_storage_key}.
     *
     * @throws com.ems.exception.BusinessException if the upload fails; callers on
     *         the violation write path are expected to catch this and degrade to
     *         inline storage rather than lose the strike.
     */
    String storeEvidence(byte[] frameBytes, String mediaType, Long sessionId, Long violationId);

    /** Fetches a previously stored frame for invigilator review. */
    ProctorEvidenceContent loadEvidence(String objectStorageKey);

    /** Removes a stored frame. Silently ignores a blank or already-absent key. */
    void deleteEvidence(String objectStorageKey);
}
