package com.ems.controller;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ems.dto.request.TestimonialSubmissionRequest;
import com.ems.dto.response.ApiResponse;
import com.ems.dto.response.MyTestimonialsResponse;
import com.ems.dto.response.PublicTestimonialResponse;
import com.ems.dto.response.TestimonialResponse;
import com.ems.exception.UnauthorizedException;
import com.ems.service.TestimonialService;
import com.ems.util.CorrelationIdUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Testimonials from the candidate's side, plus the public list the sign-in
 * screen shows. {@code /public} is the only unauthenticated route here.
 */
@RestController
@RequestMapping("/api/testimonials")
@RequiredArgsConstructor
@Validated
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
public class TestimonialController {

    private final TestimonialService testimonialService;

    /**
     * Approved testimonials for the sign-in screen. Cached for a few minutes:
     * every visitor to the site asks for it, and an approval showing up five
     * minutes late costs nothing.
     */
    @GetMapping("/public")
    public ResponseEntity<ApiResponse<List<PublicTestimonialResponse>>> getPublished() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(ApiResponse.success("Testimonials fetched", testimonialService.getPublished(),
                        CorrelationIdUtil.getOrCreateTraceId()));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MyTestimonialsResponse>> getMine(Authentication authentication) {
        return ok("Testimonials fetched", testimonialService.getMine(requireUser(authentication)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TestimonialResponse>> submit(
            Authentication authentication,
            @Valid @RequestBody TestimonialSubmissionRequest request) {
        return ok("Thank you! Your testimonial will appear once it has been reviewed.",
                testimonialService.submit(requireUser(authentication), request));
    }

    @DeleteMapping("/{testimonialId}")
    public ResponseEntity<ApiResponse<Void>> withdraw(
            Authentication authentication,
            @PathVariable Long testimonialId) {
        testimonialService.withdraw(requireUser(authentication), testimonialId);
        return ok("Your testimonial has been withdrawn and deleted.", null);
    }

    private String requireUser(Authentication authentication) {
        if (authentication == null) {
            throw new UnauthorizedException("Authentication required");
        }
        return authentication.getName();
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(String message, T data) {
        return ResponseEntity.ok(ApiResponse.success(message, data, CorrelationIdUtil.getOrCreateTraceId()));
    }
}
