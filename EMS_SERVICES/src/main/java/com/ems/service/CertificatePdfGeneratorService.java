package com.ems.service;

import java.time.LocalDate;

import com.ems.enums.CertificationLevel;

public interface CertificatePdfGeneratorService {

    /**
     * Artwork revision stamped into every rendered PDF.
     *
     * <p>The file on disk is only a cache of the certificate record, so a
     * redesign has to be able to invalidate files rendered by an earlier build.
     * Every generated PDF carries this value in its document information, and a
     * download whose stored file carries anything else is re-rendered before it
     * is served. Bump it whenever the drawn output changes.
     */
    String DESIGN_VERSION = "4";

    /** Document-information key holding {@link #DESIGN_VERSION}. */
    String DESIGN_VERSION_KEY = "EmsCertificateDesign";

    byte[] generateCertificatePdf(CertificatePdfData data);

    record CertificatePdfData(
            String certificateNumber,
            String candidateName,
            String userId,
            CertificationLevel certificationLevel,
            LocalDate issueDate,
            LocalDate expiryDate,
            String verificationUrl,
            byte[] qrCodePng) {
    }
}
