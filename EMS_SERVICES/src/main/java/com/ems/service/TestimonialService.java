package com.ems.service;

import java.util.List;

import com.ems.dto.request.TestimonialQuoteEditRequest;
import com.ems.dto.request.TestimonialReviewRequest;
import com.ems.dto.request.TestimonialSubmissionRequest;
import com.ems.dto.response.AdminTestimonialResponse;
import com.ems.dto.response.MyTestimonialsResponse;
import com.ems.dto.response.PublicTestimonialResponse;
import com.ems.dto.response.TestimonialResponse;
import com.ems.enums.TestimonialStatus;

/**
 * Testimonials from certified candidates: written in the candidate portal,
 * reviewed by an administrator, shown on the public sign-in screen.
 */
public interface TestimonialService {

    /** The caller's testimonials, and the levels they may still write one for. */
    MyTestimonialsResponse getMine(String email);

    /** Records a testimonial for a level the caller is certified in. It starts PENDING. */
    TestimonialResponse submit(String email, TestimonialSubmissionRequest request);

    /** Deletes the caller's testimonial, taking it off the site if it was published. */
    void withdraw(String email, Long testimonialId);

    /** Approved testimonials for the public site, newest approval first. */
    List<PublicTestimonialResponse> getPublished();

    /** The admin queue; a null status lists every testimonial. */
    List<AdminTestimonialResponse> listForAdmin(TestimonialStatus status);

    AdminTestimonialResponse review(String adminEmail, Long testimonialId, TestimonialReviewRequest request);

    AdminTestimonialResponse editQuote(String adminEmail, Long testimonialId, TestimonialQuoteEditRequest request);
}
