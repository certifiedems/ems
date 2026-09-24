package com.ems.dto.request;

import jakarta.validation.constraints.Size;

/**
 * An administrator's typo fix to a testimonial. The candidate's original is kept.
 *
 * @param editedQuote the corrected wording; null or blank goes back to the original
 */
public record TestimonialQuoteEditRequest(
        @Size(max = 250, message = "Quote must be at most 250 characters") String editedQuote) {
}
