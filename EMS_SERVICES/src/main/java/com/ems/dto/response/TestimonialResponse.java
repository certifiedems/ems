package com.ems.dto.response;

import java.time.Instant;
import java.time.LocalDateTime;

import com.ems.enums.CertificationLevel;
import com.ems.enums.TestimonialNameDisplay;
import com.ems.enums.TestimonialStatus;

/** A candidate's own testimonial, as they see it on their profile. */
public record TestimonialResponse(
        Long id,
        CertificationLevel certificationLevel,
        Integer rating,
        String quote,

        /** What the public site shows; differs from {@code quote} only after an admin typo fix. */
        String publishedQuote,
        TestimonialNameDisplay nameDisplay,
        String jobTitle,
        String company,
        TestimonialStatus status,
        String reviewNote,
        Instant reviewedAt,
        LocalDateTime submittedAt) {
}
