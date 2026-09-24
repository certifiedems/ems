package com.ems.enums;

/** Where a candidate's testimonial stands in the admin review. */
public enum TestimonialStatus {
    /** Submitted, not yet reviewed. Not shown publicly. */
    PENDING,
    /** Shown on the public site. */
    APPROVED,
    /** Reviewed and not shown. The candidate sees the review note. */
    REJECTED
}
