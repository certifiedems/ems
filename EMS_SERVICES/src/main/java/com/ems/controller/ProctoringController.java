package com.ems.controller;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ems.dto.request.RecordingMetadataRequest;
import com.ems.dto.request.SessionMonitoringUpdateRequest;
import com.ems.dto.request.ViolationReportRequest;
import com.ems.dto.response.ApiResponse;
import com.ems.dto.response.ProctorEvidenceResponse;
import com.ems.dto.response.ProctoringSessionResponse;
import com.ems.dto.response.VideoRecordingResponse;
import com.ems.dto.response.ViolationResponse;
import com.ems.dto.response.ViolationSummaryResponse;
import com.ems.exception.UnauthorizedException;
import com.ems.service.ProctorEvidenceContent;
import com.ems.service.ProctoringService;
import com.ems.util.CorrelationIdUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/proctoring")
@RequiredArgsConstructor
@Validated
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
public class ProctoringController {

    private final ProctoringService proctoringService;

    @PostMapping("/sessions/{sessionId}/recordings")
    public ResponseEntity<ApiResponse<VideoRecordingResponse>> recordVideoMetadata(
            Authentication authentication,
            @PathVariable Long sessionId,
            @Valid @RequestBody RecordingMetadataRequest request) {
        String email = requireUser(authentication);
        return ok("Recording metadata saved",
                proctoringService.recordVideoMetadata(email, sessionId, request));
    }

    @PostMapping("/sessions/{sessionId}/violations")
    public ResponseEntity<ApiResponse<ViolationResponse>> reportViolation(
            Authentication authentication,
            @PathVariable Long sessionId,
            @Valid @RequestBody ViolationReportRequest request) {
        String email = requireUser(authentication);
        return ok("Violation reported",
                proctoringService.reportViolation(email, sessionId, request));
    }

    @GetMapping("/sessions/{sessionId}/violations")
    public ResponseEntity<ApiResponse<List<ViolationResponse>>> getSessionViolations(
            Authentication authentication,
            @PathVariable Long sessionId) {
        String email = requireUser(authentication);
        return ok("Violations fetched successfully",
                proctoringService.getSessionViolations(email, sessionId));
    }

    @GetMapping("/sessions/{sessionId}/violations/summary")
    public ResponseEntity<ApiResponse<ViolationSummaryResponse>> getViolationSummary(
            Authentication authentication,
            @PathVariable Long sessionId) {
        String email = requireUser(authentication);
        return ok("Violation summary fetched",
                proctoringService.getSessionViolationSummary(email, sessionId));
    }

    @PutMapping("/sessions/{sessionId}/monitoring")
    public ResponseEntity<ApiResponse<ProctoringSessionResponse>> updateMonitoring(
            Authentication authentication,
            @PathVariable Long sessionId,
            @Valid @RequestBody SessionMonitoringUpdateRequest request) {
        String email = requireUser(authentication);
        return ok("Session monitoring updated",
                proctoringService.updateSessionMonitoring(email, sessionId, request));
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<ApiResponse<ProctoringSessionResponse>> getSessionSummary(
            Authentication authentication,
            @PathVariable Long sessionId) {
        String email = requireUser(authentication);
        return ok("Session summary fetched",
                proctoringService.getSessionSummary(email, sessionId));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/sessions/active")
    public ResponseEntity<ApiResponse<List<ProctoringSessionResponse>>> getActiveSessions() {
        return ok("Active sessions fetched", proctoringService.getActiveSessions());
    }

    /**
     * Frame metadata for a session, newest first — the invigilator review timeline.
     *
     * <p>Returns no image bytes; each entry is fetched individually so opening a
     * timeline costs one small query rather than a bucket read per violation.</p>
     */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/sessions/{sessionId}/evidence")
    public ResponseEntity<ApiResponse<List<ProctorEvidenceResponse>>> getSessionEvidence(
            @PathVariable Long sessionId) {
        return ok("Proctoring evidence fetched", proctoringService.getSessionEvidenceForAdmin(sessionId));
    }

    /**
     * Streams one captured frame.
     *
     * <p>Served through this authenticated endpoint rather than a public or signed
     * bucket URL, for the same reason profile photos are: access control stays in
     * one place, and a frame of a candidate's room never becomes a shareable link.
     * {@code no-store} keeps it out of intermediary caches.</p>
     */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/evidence/{evidenceId}/frame")
    public ResponseEntity<byte[]> getEvidenceFrame(@PathVariable Long evidenceId) {
        ProctorEvidenceContent content = proctoringService.loadEvidenceFrameForAdmin(evidenceId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, content.mediaType())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(content.bytes());
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
