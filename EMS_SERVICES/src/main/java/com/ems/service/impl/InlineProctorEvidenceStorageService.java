package com.ems.service.impl;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.ems.exception.BusinessException;
import com.ems.service.ProctorEvidenceContent;
import com.ems.service.ProctorEvidenceStorageService;

/**
 * Non-prod {@link ProctorEvidenceStorageService}: keeps every frame inline.
 *
 * <p>Mirrors the {@code @Profile("!prod")} half of the profile-photo split so a
 * developer or CI run needs no R2 credentials. Reporting the backend as disabled
 * is the whole implementation — the write path then takes its
 * {@code INLINE_BASE64} branch, which is what local H2 has always done.</p>
 */
@Service
@Profile("!prod")
public class InlineProctorEvidenceStorageService implements ProctorEvidenceStorageService {

    @Override
    public boolean isObjectStorageEnabled() {
        return false;
    }

    @Override
    public String storeEvidence(byte[] frameBytes, String mediaType, Long sessionId, Long violationId) {
        throw new UnsupportedOperationException(
                "Object storage is not configured outside the prod profile");
    }

    /**
     * Only reachable if a non-prod instance is pointed at a database that already
     * holds {@code OBJECT_STORAGE} rows — the bytes live in a bucket this profile
     * has no credentials for, so say so rather than return a broken image.
     */
    @Override
    public ProctorEvidenceContent loadEvidence(String objectStorageKey) {
        throw new BusinessException(
                "Proctoring evidence is stored in object storage, which is not configured in this environment",
                HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Override
    public void deleteEvidence(String objectStorageKey) {
        // Nothing is ever uploaded from this profile, so there is nothing to remove.
    }
}
