package com.ems.controller;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ems.audit.AuditEventType;
import com.ems.audit.AuditOutcome;
import com.ems.dto.request.AdminPaymentFilter;
import com.ems.dto.response.AdminAuditLogResponse;
import com.ems.dto.response.AdminPaymentReconciliation;
import com.ems.dto.response.AdminUserResponse;
import com.ems.dto.response.AdminPaymentResponse;
import com.ems.dto.response.AdminViolationResponse;
import com.ems.dto.response.ApiResponse;
import com.ems.dto.response.CertificateResponse;
import com.ems.dto.response.CertificateVerificationResponse;
import com.ems.dto.response.CertificationApplicationResponse;
import com.ems.dto.response.CertificationSummaryResponse;
import com.ems.dto.response.QuestionResponse;
import com.ems.dto.response.VideoRecordingResponse;
import com.ems.enums.CertificationLevel;
import com.ems.enums.PaymentGatewayMode;
import com.ems.enums.PaymentStatus;
import com.ems.enums.QuestionSeverity;
import com.ems.enums.ReportFormat;
import com.ems.service.AdminPortalService;
import com.ems.service.PaymentReceiptContent;
import com.ems.service.ReportFileContent;
import com.ems.util.CorrelationIdUtil;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasRole('ADMIN')")
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
public class AdminPortalController {

    private final AdminPortalService adminPortalService;

    // — Users

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<List<AdminUserResponse>>> searchUsers(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean enabled) {
        return ok("Users fetched successfully", adminPortalService.searchUsers(search, enabled));
    }

    @GetMapping("/users/{userId}")
    public ResponseEntity<ApiResponse<AdminUserResponse>> getUserById(@PathVariable Long userId) {
        return ok("User fetched successfully", adminPortalService.getUserById(userId));
    }

    @PatchMapping("/users/{userId}/enable")
    public ResponseEntity<ApiResponse<AdminUserResponse>> enableUser(@PathVariable Long userId) {
        return ok("User enabled", adminPortalService.setUserEnabled(userId, true));
    }

    @PatchMapping("/users/{userId}/disable")
    public ResponseEntity<ApiResponse<AdminUserResponse>> disableUser(@PathVariable Long userId) {
        return ok("User disabled", adminPortalService.setUserEnabled(userId, false));
    }

    @PatchMapping("/users/{userId}/lock")
    public ResponseEntity<ApiResponse<AdminUserResponse>> lockUser(@PathVariable Long userId) {
        return ok("User account locked", adminPortalService.setUserLocked(userId, true));
    }

    @PatchMapping("/users/{userId}/unlock")
    public ResponseEntity<ApiResponse<AdminUserResponse>> unlockUser(@PathVariable Long userId) {
        return ok("User account unlocked", adminPortalService.setUserLocked(userId, false));
    }

    // — Questions

    @GetMapping("/questions")
    public ResponseEntity<ApiResponse<List<QuestionResponse>>> searchQuestions(
            @RequestParam(required = false) String questionCode,
            @RequestParam(required = false) CertificationLevel level,
            @RequestParam(required = false) QuestionSeverity severity,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String search) {
        return ok("Questions fetched successfully",
                adminPortalService.searchQuestions(questionCode, level, severity, active, search));
    }

    // — Payments

    /**
     * Every payment, narrowed by any combination of the filters. {@code from} is
     * inclusive and {@code to} exclusive, both on when the payment was opened.
     */
    @GetMapping("/payments")
    public ResponseEntity<ApiResponse<List<AdminPaymentResponse>>> searchPayments(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) PaymentGatewayMode gatewayMode,
            @RequestParam(required = false) String paymentMethod,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ok("Payments fetched successfully", adminPortalService.searchPayments(
                new AdminPaymentFilter(search, status, gatewayMode, paymentMethod, from, to)));
    }

    /**
     * The payment report: the rows {@code GET /payments} returns for the same
     * filters, as EXCEL or CSV.
     *
     * <p>{@code timeZone} is the browser's IANA zone, so timestamps in the file
     * read as the admin's local time. A missing or unrecognised zone falls back
     * to UTC rather than failing the download.</p>
     */
    @GetMapping("/payments/export")
    public ResponseEntity<byte[]> exportPayments(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) PaymentGatewayMode gatewayMode,
            @RequestParam(required = false) String paymentMethod,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "EXCEL") ReportFormat format,
            @RequestParam(required = false) String timeZone) {
        ReportFileContent content = adminPortalService.exportPayments(
                new AdminPaymentFilter(search, status, gatewayMode, paymentMethod, from, to),
                format,
                resolveZone(timeZone));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, content.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + content.fileName() + "\"")
                .body(content.content());
    }

    @PostMapping("/payments/{transactionId}/reconcile")
    public ResponseEntity<ApiResponse<AdminPaymentResponse>> reconcilePayment(@PathVariable String transactionId) {
        AdminPaymentReconciliation result = adminPortalService.reconcilePayment(transactionId);
        return ok(result.message(), result.payment());
    }

    @GetMapping("/payments/{transactionId}/receipt")
    public ResponseEntity<Resource> downloadPaymentReceipt(@PathVariable String transactionId) {
        PaymentReceiptContent content = adminPortalService.downloadPaymentReceipt(transactionId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, content.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + content.fileName() + "\"")
                .body(content.resource());
    }

    // — Certifications

    @GetMapping("/certification-applications")
    public ResponseEntity<ApiResponse<List<CertificationApplicationResponse>>> getAllApplications() {
        return ok("Certification applications fetched successfully", adminPortalService.getAllApplications());
    }

    @GetMapping("/certifications")
    public ResponseEntity<ApiResponse<List<CertificationSummaryResponse>>> getAllCertifications() {
        return ok("Certifications fetched successfully", adminPortalService.getAllCertifications());
    }

    // — Certificates

    @GetMapping("/certificates")
    public ResponseEntity<ApiResponse<List<CertificateResponse>>> getAllCertificates() {
        return ok("Certificates fetched successfully", adminPortalService.getAllCertificates());
    }

    @GetMapping("/certificates/verify/{certificateNumber}")
    public ResponseEntity<ApiResponse<CertificateVerificationResponse>> verifyCertificate(
            @PathVariable String certificateNumber) {
        return ok("Certificate verification completed",
                adminPortalService.verifyCertificate(certificateNumber));
    }

    // — Violations

    @GetMapping("/violations")
    public ResponseEntity<ApiResponse<List<AdminViolationResponse>>> getAllViolations() {
        return ok("Violations fetched successfully", adminPortalService.getAllViolations());
    }

    @GetMapping("/sessions/{sessionId}/violations")
    public ResponseEntity<ApiResponse<List<AdminViolationResponse>>> getSessionViolations(
            @PathVariable Long sessionId) {
        return ok("Session violations fetched successfully",
                adminPortalService.getViolationsForSession(sessionId));
    }

    // — Recordings

    @GetMapping("/recordings")
    public ResponseEntity<ApiResponse<List<VideoRecordingResponse>>> getAllRecordings() {
        return ok("Recordings fetched successfully", adminPortalService.getAllRecordings());
    }

    @GetMapping("/sessions/{sessionId}/recordings")
    public ResponseEntity<ApiResponse<List<VideoRecordingResponse>>> getSessionRecordings(
            @PathVariable Long sessionId) {
        return ok("Session recordings fetched successfully",
                adminPortalService.getRecordingsForSession(sessionId));
    }

    // — Audit trail

    @GetMapping("/audit-logs")
    public ResponseEntity<ApiResponse<List<AdminAuditLogResponse>>> searchAuditLogs(
            @RequestParam(required = false) AuditEventType eventType,
            @RequestParam(required = false) AuditOutcome outcome,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String targetUserId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, defaultValue = "200") int limit) {
        return ok("Audit logs fetched successfully",
                adminPortalService.searchAuditLogs(eventType, outcome, actor, targetUserId, from, to, limit));
    }

    private static ZoneId resolveZone(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(timeZone.trim());
        } catch (DateTimeException ex) {
            return ZoneOffset.UTC;
        }
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(String message, T data) {
        return ResponseEntity.ok(ApiResponse.success(message, data, CorrelationIdUtil.getOrCreateTraceId()));
    }
}
