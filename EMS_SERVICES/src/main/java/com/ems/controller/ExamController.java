package com.ems.controller;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ems.dto.request.ExamDurationUpdateRequest;
import com.ems.dto.request.ExamPassingMarksUpdateRequest;
import com.ems.dto.request.ExamScheduleRequest;
import com.ems.dto.request.ExamUpsertRequest;
import com.ems.dto.response.ApiResponse;
import com.ems.dto.response.ExamBookingWindowResponse;
import com.ems.dto.response.ExamResponse;
import com.ems.dto.response.MessageResponse;
import com.ems.enums.CertificationLevel;
import com.ems.enums.ExamStatus;
import com.ems.service.ExamService;
import com.ems.util.CorrelationIdUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/exams")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasRole('ADMIN')")
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
public class ExamController {

    private final ExamService examService;

    @PostMapping
    public ResponseEntity<ApiResponse<ExamResponse>> create(@Valid @RequestBody ExamUpsertRequest request) {
        return ok("Exam created successfully", examService.create(request));
    }

    @PutMapping("/{examId}")
    public ResponseEntity<ApiResponse<ExamResponse>> update(
            @PathVariable Long examId,
            @Valid @RequestBody ExamUpsertRequest request) {
        return ok("Exam updated successfully", examService.update(examId, request));
    }

    @DeleteMapping("/{examId}")
    public ResponseEntity<ApiResponse<MessageResponse>> delete(@PathVariable Long examId) {
        examService.delete(examId);
        return ok("Exam deleted successfully", new MessageResponse("Exam removed"));
    }

    @PostMapping("/{examId}/publish")
    public ResponseEntity<ApiResponse<ExamResponse>> publish(@PathVariable Long examId) {
        return ok("Exam published successfully", examService.publish(examId));
    }

    /*
     * A literal path segment, so it is matched ahead of the "/{examId}" template
     * and never read as an exam id.
     */
    @GetMapping("/booking-windows")
    public ResponseEntity<ApiResponse<List<ExamBookingWindowResponse>>> bookingWindows() {
        return ok("Booking windows retrieved successfully", examService.getBookingWindows());
    }

    @PostMapping("/{examId}/schedule")
    public ResponseEntity<ApiResponse<ExamResponse>> schedule(
            @PathVariable Long examId,
            @Valid @RequestBody ExamScheduleRequest request) {
        return ok("Exam scheduled successfully", examService.schedule(examId, request));
    }

    @PatchMapping("/{examId}/duration")
    public ResponseEntity<ApiResponse<ExamResponse>> updateDuration(
            @PathVariable Long examId,
            @Valid @RequestBody ExamDurationUpdateRequest request) {
        return ok("Exam duration updated", examService.updateDuration(examId, request));
    }

    @PatchMapping("/{examId}/passing-marks")
    public ResponseEntity<ApiResponse<ExamResponse>> updatePassingMarks(
            @PathVariable Long examId,
            @Valid @RequestBody ExamPassingMarksUpdateRequest request) {
        return ok("Exam passing marks updated", examService.updatePassingMarks(examId, request));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<ExamResponse>>> search(
            @RequestParam(required = false) String examCode,
            @RequestParam(required = false) String examName,
            @RequestParam(required = false) CertificationLevel certificationLevel,
            @RequestParam(required = false) ExamStatus examStatus,
            @RequestParam(required = false) Boolean published) {
        return ok("Exams fetched successfully",
                examService.search(examCode, examName, certificationLevel, examStatus, published));
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(String message, T data) {
        return ResponseEntity.ok(ApiResponse.success(message, data, CorrelationIdUtil.getOrCreateTraceId()));
    }
}
