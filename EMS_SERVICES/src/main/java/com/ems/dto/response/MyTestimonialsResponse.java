package com.ems.dto.response;

import java.util.List;

import com.ems.enums.CertificationLevel;

/**
 * @param eligibleLevels levels the candidate is certified in and has not yet written a testimonial for
 * @param testimonials   the testimonials they have written
 */
public record MyTestimonialsResponse(
        List<CertificationLevel> eligibleLevels,
        List<TestimonialResponse> testimonials) {
}
