package com.ems.controller;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ems.dto.request.ExamAttemptPolicyRequest;
import com.ems.dto.response.ApiResponse;
import com.ems.dto.response.ExamAttemptPolicyResponse;
import com.ems.enums.CertificationLevel;
import com.ems.service.ExamAttemptPolicyService;
import com.ems.util.CorrelationIdUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Admin console for how many attempts one exam payment buys at each
 * certification level.
 */
@RestController
@RequestMapping("/api/admin/attempt-policies")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasRole('ADMIN')")
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
public class AdminExamAttemptPolicyController {

    private final ExamAttemptPolicyService examAttemptPolicyService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ExamAttemptPolicyResponse>>> listPolicies() {
        return ok("Exam attempt allowances fetched", examAttemptPolicyService.listPolicies());
    }

    @PutMapping("/{level}")
    public ResponseEntity<ApiResponse<ExamAttemptPolicyResponse>> updatePolicy(
            @PathVariable CertificationLevel level,
            @Valid @RequestBody ExamAttemptPolicyRequest request) {
        return ok(level + " attempt allowance saved", examAttemptPolicyService.updatePolicy(level, request));
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(String message, T data) {
        return ResponseEntity.ok(ApiResponse.success(message, data, CorrelationIdUtil.getOrCreateTraceId()));
    }
}
