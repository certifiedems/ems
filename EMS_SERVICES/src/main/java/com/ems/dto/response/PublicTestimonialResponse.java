package com.ems.dto.response;

import com.ems.enums.CertificationLevel;

/**
 * An approved testimonial as the public site shows it. Carries nothing the
 * candidate did not agree to publish.
 *
 * @param displayName null when the candidate chose to stay anonymous
 */
public record PublicTestimonialResponse(
        Long id,
        String quote,
        String displayName,
        String jobTitle,
        String company,
        CertificationLevel certificationLevel) {
}
