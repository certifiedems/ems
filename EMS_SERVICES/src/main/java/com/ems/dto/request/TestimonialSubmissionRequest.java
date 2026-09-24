package com.ems.dto.request;

import com.ems.enums.CertificationLevel;
import com.ems.enums.TestimonialNameDisplay;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A certified candidate's testimonial, as submitted from the candidate portal.
 *
 * @param certificationLevel the level it is about; the candidate must hold it
 * @param rating             1 to 5 stars; seen by administrators only
 * @param quote              the candidate's own words
 * @param nameDisplay        how to name them beside the quote
 * @param jobTitle           optional, shown beside the quote
 * @param company            optional, shown beside the quote
 * @param consent            must be true: the candidate agrees to publication
 */
public record TestimonialSubmissionRequest(
        @NotNull CertificationLevel certificationLevel,
        @NotNull @Min(1) @Max(5) Integer rating,
        @NotBlank @Size(min = 20, max = 250, message = "Quote must be between 20 and 250 characters") String quote,
        @NotNull TestimonialNameDisplay nameDisplay,
        @Size(max = 100) String jobTitle,
        @Size(max = 100) String company,
        @AssertTrue(message = "Please agree to your testimonial being published") boolean consent) {
}
