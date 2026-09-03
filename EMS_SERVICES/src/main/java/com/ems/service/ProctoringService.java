package com.ems.service;

import java.util.List;

import com.ems.dto.request.RecordingMetadataRequest;
import com.ems.dto.request.SessionMonitoringUpdateRequest;
import com.ems.dto.request.ViolationReportRequest;
import com.ems.dto.response.ProctorEvidenceResponse;
import com.ems.dto.response.ProctoringSessionResponse;
import com.ems.dto.response.VideoRecordingResponse;
import com.ems.dto.response.ViolationResponse;
import com.ems.dto.response.ViolationSummaryResponse;

public interface ProctoringService {

    VideoRecordingResponse recordVideoMetadata(String email, Long sessionId,
            RecordingMetadataRequest request);

    ViolationResponse reportViolation(String email, Long sessionId, ViolationReportRequest request);

    List<ViolationResponse> getSessionViolations(String email, Long sessionId);

    ViolationSummaryResponse getSessionViolationSummary(String email, Long sessionId);

    List<ViolationResponse> getSessionViolationsForAdmin(Long sessionId);

    ViolationSummaryResponse getSessionViolationSummaryForAdmin(Long sessionId);

    ProctoringSessionResponse updateSessionMonitoring(String email, Long sessionId,
            SessionMonitoringUpdateRequest request);

    ProctoringSessionResponse getSessionSummary(String email, Long sessionId);

    List<ProctoringSessionResponse> getActiveSessions();

    /** Frame metadata for a session's proctoring evidence, newest first. Image bytes excluded. */
    List<ProctorEvidenceResponse> getSessionEvidenceForAdmin(Long sessionId);

    /**
     * Resolves one evidence frame to bytes, whichever storage kind holds it.
     *
     * <p>The branch lives here rather than in the caller so reviewer-facing code
     * never has to know that some rows predate the move to object storage.</p>
     */
    ProctorEvidenceContent loadEvidenceFrameForAdmin(Long evidenceId);
}
