package com.ems.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ems.dto.request.ExamProgressSaveRequest;
import com.ems.dto.request.ExamStartRequest;
import com.ems.dto.request.ExamWorkflowApplicationRequest;
import com.ems.dto.request.PaymentCompletionRequest;
import com.ems.dto.request.PaymentInitiationRequest;
import com.ems.dto.request.WorkflowExamScheduleRequest;
import com.ems.dto.response.ApiResponse;
import com.ems.dto.response.ExamAttemptAllowanceResponse;
import com.ems.dto.response.ExamProgressResponse;
import com.ems.dto.response.ExamSessionQuestionResponse;
import com.ems.dto.response.ExamSlotAvailabilityResponse;
import com.ems.dto.response.ExamStartResponse;
import com.ems.dto.response.ExamWorkflowApplicationResponse;
import com.ems.dto.response.PaymentResponse;
import com.ems.dto.response.WorkflowExamOptionResponse;
import com.ems.enums.CertificationLevel;
import com.ems.exception.UnauthorizedException;
import com.ems.service.ExamWorkflowService;
import com.ems.util.CorrelationIdUtil;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/exam-workflow")
@RequiredArgsConstructor
@Validated
@Tag(name = "Exam Workflow", description = "Apply, pay, schedule and take certification exams")
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
public class ExamWorkflowController {

    private final ExamWorkflowService examWorkflowService;

    @GetMapping("/options/{level}")
    @Operation(summary = "Get exam workflow options for a certification level")
    public ResponseEntity<ApiResponse<WorkflowExamOptionResponse>> getOptions(
            Authentication authentication,
            @PathVariable CertificationLevel level) {
        String email = requireUser(authentication);
        return ok("Exam workflow options fetched successfully",
                examWorkflowService.getWorkflowOptions(email, level));
    }

    /**
     * How many sittings a payment made now buys at a level. Shown on the payment
     * screen, so a candidate knows before paying what a failed attempt costs them.
     */
    @GetMapping("/attempt-allowance/{level}")
    @Operation(summary = "Get how many exam attempts one payment covers at a certification level")
    public ResponseEntity<ApiResponse<ExamAttemptAllowanceResponse>> getAttemptAllowance(
            Authentication authentication,
            @PathVariable CertificationLevel level) {
        requireUser(authentication);
        return ok("Exam attempt allowance fetched successfully",
                examWorkflowService.getAttemptAllowance(level));
    }

    @PostMapping("/applications")
    @Operation(summary = "Apply for a certification exam")
    public ResponseEntity<ApiResponse<ExamWorkflowApplicationResponse>> createApplication(
            Authentication authentication,
            @Valid @RequestBody ExamWorkflowApplicationRequest request) {
        String email = requireUser(authentication);
        return ok("Exam workflow application created successfully",
                examWorkflowService.createApplication(email, request));
    }

    @PostMapping("/applications/{applicationId}/payments/initiate")
    @Operation(summary = "Initiate payment for an exam application")
    public ResponseEntity<ApiResponse<PaymentResponse>> initiatePayment(
            Authentication authentication,
            @PathVariable Long applicationId,
            @Valid @RequestBody PaymentInitiationRequest request) {
        String email = requireUser(authentication);
        PaymentResponse response = examWorkflowService.initiatePayment(email, applicationId, request);
        return ok("Payment initiated successfully", response);
    }

    @PostMapping("/applications/{applicationId}/payments/complete")
    @Operation(summary = "Complete payment for an exam application")
    public ResponseEntity<ApiResponse<PaymentResponse>> completePayment(
            Authentication authentication,
            @PathVariable Long applicationId,
            @Valid @RequestBody PaymentCompletionRequest request) {
        String email = requireUser(authentication);
        return ok("Payment completed successfully",
                examWorkflowService.completePayment(email, applicationId, request));
    }

    @PostMapping("/applications/{applicationId}/schedule")
    @Operation(summary = "Schedule the exam date/time for an application")
    public ResponseEntity<ApiResponse<ExamWorkflowApplicationResponse>> scheduleExam(
            Authentication authentication,
            @PathVariable Long applicationId,
            @Valid @RequestBody WorkflowExamScheduleRequest request) {
        String email = requireUser(authentication);
        return ok("Exam scheduled successfully",
                examWorkflowService.scheduleExam(email, applicationId, request));
    }

    /**
     * The slots an application's exam can be booked into between two instants,
     * with the seats left in each. The client asks for the candidate's local day.
     */
    @GetMapping("/applications/{applicationId}/slots")
    @Operation(summary = "List the exam slots an application can book, with the seats left in each")
    public ResponseEntity<ApiResponse<ExamSlotAvailabilityResponse>> getSlotAvailability(
            Authentication authentication,
            @PathVariable Long applicationId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        String email = requireUser(authentication);
        return ok("Exam slots fetched successfully",
                examWorkflowService.getSlotAvailability(email, applicationId, from, to));
    }

    /**
     * Starts an exam session.
     * Returns {@code questionIds} (ordered list of all 30 IDs) and
     * {@code firstQuestion} (full payload for Q1). Subsequent questions are
     * fetched on demand via GET /sessions/{sessionToken}/questions/{questionNumber}.
     * Question distribution: 20% LOW (6), 40% MEDIUM (12), 40% HIGH (12).
     */
    @PostMapping("/applications/{applicationId}/start")
    @Operation(
            summary = "Start exam session",
            description = "Returns the session token, ordered question ID list, and full payload for Q1. "
                    + "Questions are composed of 6 LOW / 12 MEDIUM / 12 HIGH severity questions. "
                    + "Fetch subsequent questions by calling GET /sessions/{sessionToken}/questions/{number}.")
    public ResponseEntity<ApiResponse<ExamStartResponse>> startExam(
            Authentication authentication,
            @PathVariable Long applicationId,
            @RequestBody(required = false) ExamStartRequest request) {
        String email = requireUser(authentication);
        ExamStartResponse response = examWorkflowService.startExam(email, applicationId, request);
        return ok("Exam started successfully", response);
    }

    /**
     * Fetches the full question payload for a specific question number within an
     * active exam session. Called by the UI whenever the candidate clicks on a
     * question number in the navigation panel.
     *
     * @param sessionToken   UUID of the active exam session (from startExam response)
     * @param questionNumber 1-indexed question number (1..30)
     */
    @GetMapping("/sessions/{sessionToken}/questions/{questionNumber}")
    @Operation(
            summary = "Get question by number for active session",
            description = "Returns the full question payload for the requested question number (1-indexed). "
                    + "Call this whenever the candidate navigates to a different question.")
    public ResponseEntity<ApiResponse<ExamSessionQuestionResponse>> getSessionQuestion(
            Authentication authentication,
            @PathVariable UUID sessionToken,
            @PathVariable int questionNumber) {
        String email = requireUser(authentication);
        return ok("Question fetched successfully",
                examWorkflowService.getSessionQuestion(email, sessionToken, questionNumber));
    }

    /**
     * Autosaves the candidate's answers mid-attempt.
     *
     * <p>Called on a timer and whenever the candidate moves between questions,
     * so that an attempt cut short by a dropped connection, a flat battery or a
     * closed laptop can be rejoined with its answers intact. Idempotent: the
     * whole draft is sent each time, and the newest write wins.</p>
     */
    @PostMapping("/sessions/{sessionToken}/progress")
    @Operation(
            summary = "Autosave answers for an active exam session",
            description = "Stores the candidate's answers so far so an interrupted attempt can be resumed "
                    + "without losing work. Send the complete answer set on every call.")
    public ResponseEntity<ApiResponse<ExamProgressResponse>> saveProgress(
            Authentication authentication,
            @PathVariable UUID sessionToken,
            @Valid @RequestBody ExamProgressSaveRequest request) {
        String email = requireUser(authentication);
        return ok("Exam progress saved successfully",
                examWorkflowService.saveProgress(email, sessionToken, request));
    }

    @GetMapping("/sessions/{sessionToken}/progress")
    @Operation(
            summary = "Get the last autosaved answers for an exam session",
            description = "Returns the stored draft and the time left in the attempt.")
    public ResponseEntity<ApiResponse<ExamProgressResponse>> getProgress(
            Authentication authentication,
            @PathVariable UUID sessionToken) {
        String email = requireUser(authentication);
        return ok("Exam progress fetched successfully",
                examWorkflowService.getProgress(email, sessionToken));
    }

    @PostMapping("/applications/{applicationId}/re-apply")
    @Operation(
            summary = "Re-apply after a failed exam",
            description = "Creates a new application for the same certification level as the FAILED/EXPIRED/REJECTED application.")
    public ResponseEntity<ApiResponse<ExamWorkflowApplicationResponse>> reApply(
            Authentication authentication,
            @PathVariable Long applicationId) {
        String email = requireUser(authentication);
        ExamWorkflowApplicationResponse response = examWorkflowService.reApply(email, applicationId);
        return ok("Re-application created successfully", response);
    }

    @GetMapping("/re-applyable")
    @Operation(summary = "Get all failed/expired applications eligible for re-apply (common UI endpoint)")
    public ResponseEntity<ApiResponse<List<ExamWorkflowApplicationResponse>>> getReApplyableApplications(
            Authentication authentication) {
        String email = requireUser(authentication);
        List<ExamWorkflowApplicationResponse> response =
                examWorkflowService.getReApplyableApplications(email);
        return ok("Reapplyable applications fetched successfully", response);
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
