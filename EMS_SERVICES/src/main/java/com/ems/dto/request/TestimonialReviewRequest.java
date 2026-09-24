package com.ems.dto.request;

import com.ems.enums.TestimonialStatus;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * An administrator's decision on a testimonial.
 *
 * @param status     APPROVED or REJECTED
 * @param reviewNote optional; shown to the candidate, mostly to say why a quote was not published
 */
public record TestimonialReviewRequest(
        @NotNull TestimonialStatus status,
        @Size(max = 300) String reviewNote) {
}
