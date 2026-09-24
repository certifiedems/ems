package com.ems.dto.response;

import java.time.Instant;
import java.time.LocalDateTime;

import com.ems.enums.CertificationLevel;
import com.ems.enums.CertificationStatus;
import com.ems.enums.TestimonialNameDisplay;
import com.ems.enums.TestimonialStatus;

/** A testimonial in the admin review queue. */
public record AdminTestimonialResponse(
        Long id,
        String candidateUserId,
        String candidateName,
        String candidateEmail,
        CertificationLevel certificationLevel,

        /** A REVOKED certification keeps the quote off the site whatever its status. */
        CertificationStatus certificationStatus,
        Integer rating,

        /** Exactly what the candidate wrote. */
        String quote,

        /** The admin's typo fix, or null. */
        String editedQuote,
        TestimonialNameDisplay nameDisplay,

        /** The name as the public site would show it; null when anonymous. */
        String publicName,
        String jobTitle,
        String company,
        TestimonialStatus status,
        String reviewNote,
        String reviewedBy,
        Instant reviewedAt,
        Instant consentGivenAt,
        LocalDateTime submittedAt) {
}
