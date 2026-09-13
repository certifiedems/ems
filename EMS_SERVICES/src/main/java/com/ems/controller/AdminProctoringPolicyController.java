package com.ems.controller;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ems.dto.request.ProctoringPolicyRequest;
import com.ems.dto.response.ApiResponse;
import com.ems.dto.response.ExamProctoringPolicySummaryResponse;
import com.ems.dto.response.ProctoringPolicyResponse;
import com.ems.service.ProctoringPolicyService;
import com.ems.util.CorrelationIdUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Admin console for the proctoring rules: which violations are monitored, how
 * each one is enforced, the strike limit and the sound thresholds.
 */
@RestController
@RequestMapping("/api/admin/proctoring-policies")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasRole('ADMIN')")
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
public class AdminProctoringPolicyController {

    private final ProctoringPolicyService proctoringPolicyService;

    @GetMapping("/default")
    public ResponseEntity<ApiResponse<ProctoringPolicyResponse>> getDefaultPolicy() {
        return ok("Default proctoring rules fetched", proctoringPolicyService.getDefaultPolicy());
    }

    @PutMapping("/default")
    public ResponseEntity<ApiResponse<ProctoringPolicyResponse>> updateDefaultPolicy(
            @Valid @RequestBody ProctoringPolicyRequest request) {
        return ok("Default proctoring rules saved", proctoringPolicyService.updateDefaultPolicy(request));
    }

    @GetMapping("/exams")
    public ResponseEntity<ApiResponse<List<ExamProctoringPolicySummaryResponse>>> listExamPolicies() {
        return ok("Exam proctoring rules fetched", proctoringPolicyService.listExamPolicies());
    }

    @GetMapping("/exams/{examId}")
    public ResponseEntity<ApiResponse<ProctoringPolicyResponse>> getExamPolicy(@PathVariable Long examId) {
        return ok("Exam proctoring rules fetched", proctoringPolicyService.getExamPolicy(examId));
    }

    @PutMapping("/exams/{examId}")
    public ResponseEntity<ApiResponse<ProctoringPolicyResponse>> updateExamPolicy(
            @PathVariable Long examId,
            @Valid @RequestBody ProctoringPolicyRequest request) {
        return ok("Exam proctoring rules saved", proctoringPolicyService.updateExamPolicy(examId, request));
    }

    /** Removes the exam's own rules; its attempts go back to the default policy. */
    @DeleteMapping("/exams/{examId}")
    public ResponseEntity<ApiResponse<ProctoringPolicyResponse>> resetExamPolicy(@PathVariable Long examId) {
        return ok("Exam now uses the default proctoring rules", proctoringPolicyService.resetExamPolicy(examId));
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(String message, T data) {
        return ResponseEntity.ok(ApiResponse.success(message, data, CorrelationIdUtil.getOrCreateTraceId()));
    }
}
