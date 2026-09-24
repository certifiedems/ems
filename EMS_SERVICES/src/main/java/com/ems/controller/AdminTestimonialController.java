package com.ems.controller;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ems.dto.request.TestimonialQuoteEditRequest;
import com.ems.dto.request.TestimonialReviewRequest;
import com.ems.dto.response.AdminTestimonialResponse;
import com.ems.dto.response.ApiResponse;
import com.ems.enums.TestimonialStatus;
import com.ems.service.TestimonialService;
import com.ems.util.CorrelationIdUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Admin review of candidate testimonials: approve, reject, fix typos. */
@RestController
@RequestMapping("/api/admin/testimonials")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasRole('ADMIN')")
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
public class AdminTestimonialController {

    private final TestimonialService testimonialService;

    /** Every testimonial, newest first; {@code status} narrows it to one state. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<AdminTestimonialResponse>>> list(
            @RequestParam(required = false) TestimonialStatus status) {
        return ok("Testimonials fetched", testimonialService.listForAdmin(status));
    }

    @PatchMapping("/{testimonialId}/status")
    public ResponseEntity<ApiResponse<AdminTestimonialResponse>> review(
            Authentication authentication,
            @PathVariable Long testimonialId,
            @Valid @RequestBody TestimonialReviewRequest request) {
        AdminTestimonialResponse response =
                testimonialService.review(authentication.getName(), testimonialId, request);
        return ok(response.status() == TestimonialStatus.APPROVED
                ? "Testimonial approved and published"
                : "Testimonial rejected", response);
    }

    @PatchMapping("/{testimonialId}/quote")
    public ResponseEntity<ApiResponse<AdminTestimonialResponse>> editQuote(
            Authentication authentication,
            @PathVariable Long testimonialId,
            @Valid @RequestBody TestimonialQuoteEditRequest request) {
        return ok("Testimonial wording saved",
                testimonialService.editQuote(authentication.getName(), testimonialId, request));
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(String message, T data) {
        return ResponseEntity.ok(ApiResponse.success(message, data, CorrelationIdUtil.getOrCreateTraceId()));
    }
}
