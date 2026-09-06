package com.ems.service.impl;

import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ems.audit.AuditEvent;
import com.ems.audit.AuditEventType;
import com.ems.audit.AuditOutcome;
import com.ems.dto.request.RecordingMetadataRequest;
import com.ems.dto.request.SessionMonitoringUpdateRequest;
import com.ems.dto.request.ViolationReportRequest;
import com.ems.dto.response.ProctorEvidenceResponse;
import com.ems.dto.response.ProctoringSessionResponse;
import com.ems.dto.response.VideoRecordingResponse;
import com.ems.dto.response.ViolationResponse;
import com.ems.dto.response.ViolationSummaryResponse;
import com.ems.entity.ExamSession;
import com.ems.entity.ProctorEvidence;
import com.ems.entity.VideoRecording;
import com.ems.entity.Violation;
import com.ems.enums.ExamStatus;
import com.ems.enums.ProctoringAction;
import com.ems.enums.ViolationType;
import com.ems.exception.BusinessException;
import com.ems.exception.ResourceNotFoundException;
import com.ems.repository.ExamSessionRepository;
import com.ems.repository.ProctorEvidenceRepository;
import com.ems.repository.VideoRecordingRepository;
import com.ems.repository.ViolationRepository;
import com.ems.service.AuditService;
import com.ems.service.ProctorEvidenceContent;
import com.ems.service.ProctorEvidenceStorageService;
import com.ems.service.ProctoringService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
@Transactional
public class ProctoringServiceImpl implements ProctoringService {

	/**
	 * Types accepted by the legacy reporting endpoint. Kept aligned with the AI
	 * pipeline's catalogue so a detection raised by the worker can also be replayed
	 * through {@code /api/proctoring/sessions/{id}/violations}.
	 */
	private static final Set<ViolationType> TRACKED_VIOLATIONS = EnumSet.of(
			ViolationType.TAB_SWITCH,
			ViolationType.WINDOW_MINIMIZED,
			ViolationType.WINDOW_FOCUS_LOST,
			ViolationType.WEBCAM_OFF,
			ViolationType.BROWSER_MONITORING,
			ViolationType.SESSION_TAMPERING,
			ViolationType.MULTIPLE_LOGIN,
			ViolationType.PHONE_DETECTED,
			ViolationType.MULTIPLE_FACES,
			ViolationType.FACE_NOT_VISIBLE,
			ViolationType.FACE_TURNED_AWAY,
			ViolationType.EYES_OFF_SCREEN,
			ViolationType.BACKGROUND_NOISE,
			ViolationType.FULLSCREEN_EXIT,
			ViolationType.SCREEN_SHARE_STOPPED,
			ViolationType.SCREEN_SHARE_DENIED,
			ViolationType.NETWORK_LOSS,
			ViolationType.SCREEN_RECORDING_SUSPECTED);

	private final ExamSessionRepository examSessionRepository;
	private final VideoRecordingRepository videoRecordingRepository;
	private final ViolationRepository violationRepository;
	private final ProctorEvidenceRepository proctorEvidenceRepository;
	private final ProctorEvidenceStorageService proctorEvidenceStorageService;
	private final ExamInvalidationHandler examInvalidationHandler;
	private final AuditService auditService;

	@Override
	public VideoRecordingResponse recordVideoMetadata(String email, Long sessionId, RecordingMetadataRequest request) {
		ExamSession session = findOwnedSession(email, sessionId);
		validateSessionStillActive(session);

		VideoRecording recording = VideoRecording.builder()
				.examSession(session)
				.fileLocation(request.fileLocation())
				.recordingStartTime(request.recordingStartTime())
				.recordingEndTime(request.recordingEndTime())
				.recordingDurationSeconds(request.recordingDurationSeconds())
				.build();

		VideoRecording savedRecording = videoRecordingRepository.save(recording);
		log.info("Proctoring recording metadata saved: sessionId={}, recordingId={}",
				sessionId, savedRecording.getId());
		return toRecordingResponse(savedRecording);
	}

	@Override
	public ViolationResponse reportViolation(String email, Long sessionId, ViolationReportRequest request) {
		ExamSession session = findOwnedSession(email, sessionId);
		validateSessionStillActive(session);
		validateViolationType(request.violationType());

		int nextViolationLevel = session.getViolationCount() + 1;
		ProctoringAction actionTaken = nextViolationLevel >= 3
				? ProctoringAction.EXAM_TERMINATED
				: ProctoringAction.WARNING;

		Violation violation = Violation.builder()
				.examSession(session)
				.violationType(request.violationType())
				.violationLevel(nextViolationLevel)
				.description(request.description())
				.detectedAt(Instant.now())
				.actionTaken(actionTaken)
				.build();

		Violation savedViolation = violationRepository.save(violation);

		session.setViolationCount(nextViolationLevel);
		if (actionTaken == ProctoringAction.EXAM_TERMINATED) {
			session.setSessionStatus(ExamStatus.INVALIDATED);
			session.setSessionEndTime(Instant.now());
		}
		examSessionRepository.save(session);

		if (actionTaken == ProctoringAction.EXAM_TERMINATED) {
			markLatestApplicationAsFailedForRestart(session);
			auditService.record(AuditEvent.builder()
					.eventType(AuditEventType.EXAM_INVALIDATED)
					.outcome(AuditOutcome.SUCCESS)
					.actorEmail(email)
					.actorUserId(session.getUser().getUserId())
					.targetUserId(session.getUser().getUserId())
					.targetType("EXAM_SESSION")
					.targetId(String.valueOf(sessionId))
					.description("Exam terminated after 3rd proctoring violation ("
							+ request.violationType() + ")")
					.build());
		}

		log.info("Proctoring violation recorded: sessionId={}, violationType={}, level={}, action={}",
				sessionId, request.violationType(), nextViolationLevel, actionTaken);
		return toViolationResponse(savedViolation);
	}

	private void markLatestApplicationAsFailedForRestart(ExamSession session) {
		examInvalidationHandler.markLatestApplicationAsFailedForRestart(session);
	}

	@Override
	@Transactional(readOnly = true)
	public List<ViolationResponse> getSessionViolations(String email, Long sessionId) {
		ExamSession session = findOwnedSession(email, sessionId);
		return violationRepository.findByExamSessionOrderByDetectedAtDesc(session).stream()
				.map(this::toViolationResponse)
				.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public ViolationSummaryResponse getSessionViolationSummary(String email, Long sessionId) {
		ExamSession session = findOwnedSession(email, sessionId);
		return buildViolationSummary(session);
	}

	@Override
	@Transactional(readOnly = true)
	public List<ViolationResponse> getSessionViolationsForAdmin(Long sessionId) {
		ExamSession session = findSessionById(sessionId);
		return violationRepository.findByExamSessionOrderByDetectedAtDesc(session).stream()
				.map(this::toViolationResponse)
				.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public ViolationSummaryResponse getSessionViolationSummaryForAdmin(Long sessionId) {
		ExamSession session = findSessionById(sessionId);
		return buildViolationSummary(session);
	}

	@Override
	public ProctoringSessionResponse updateSessionMonitoring(String email, Long sessionId,
			SessionMonitoringUpdateRequest request) {
		ExamSession session = findOwnedSession(email, sessionId);
		validateSessionStillActive(session);

		if (request.browserFingerprint() != null && !request.browserFingerprint().isBlank()) {
			session.setBrowserFingerprint(request.browserFingerprint().trim());
		}
		if (request.ipAddress() != null && !request.ipAddress().isBlank()) {
			session.setIpAddress(request.ipAddress().trim());
		}

		ExamSession savedSession = examSessionRepository.save(session);
		return toSessionResponse(savedSession);
	}

	@Override
	@Transactional(readOnly = true)
	public ProctoringSessionResponse getSessionSummary(String email, Long sessionId) {
		return toSessionResponse(findOwnedSession(email, sessionId));
	}

	@Override
	@Transactional(readOnly = true)
	public List<ProctoringSessionResponse> getActiveSessions() {
		return examSessionRepository.findBySessionStatusOrderBySessionStartTimeDesc(ExamStatus.IN_PROGRESS).stream()
				.map(this::toSessionResponse)
				.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public List<ProctorEvidenceResponse> getSessionEvidenceForAdmin(Long sessionId) {
		findSessionById(sessionId);
		return proctorEvidenceRepository.findEvidenceMetadataBySession(sessionId);
	}

	@Override
	@Transactional(readOnly = true)
	public ProctorEvidenceContent loadEvidenceFrameForAdmin(Long evidenceId) {
		ProctorEvidence evidence = proctorEvidenceRepository.findById(evidenceId)
				.orElseThrow(() -> new ResourceNotFoundException("Proctoring evidence not found"));

		/*
		 * Rows written before frames moved to R2 still carry their bytes in
		 * evidence_payload, and no backfill moves them, so both branches stay
		 * live for as long as those rows are retained.
		 */
		return switch (evidence.getStorageKind()) {
			case OBJECT_STORAGE -> proctorEvidenceStorageService.loadEvidence(evidence.getObjectStorageKey());
			case INLINE_BASE64 -> decodeInlineEvidence(evidence);
		};
	}

	private ProctorEvidenceContent decodeInlineEvidence(ProctorEvidence evidence) {
		String payload = evidence.getEvidencePayload();
		if (payload == null || payload.isBlank()) {
			throw new ResourceNotFoundException("Proctoring evidence frame is missing");
		}

		try {
			return new ProctorEvidenceContent(
					Base64.getDecoder().decode(payload), evidence.getMediaType(), null);
		} catch (IllegalArgumentException ex) {
			// The write path validated this on the way in, so a failure here means
			// the stored row is corrupt rather than the request being bad.
			log.warn("Stored evidence payload is not valid base64: evidenceId={}", evidence.getId());
			throw new BusinessException("Proctoring evidence frame is corrupt", HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	private ExamSession findOwnedSession(String email, Long sessionId) {
		return examSessionRepository.findByIdAndUserEmailIgnoreCase(sessionId, email)
				.orElseThrow(() -> new ResourceNotFoundException("Exam session not found"));
	}

	private ExamSession findSessionById(Long sessionId) {
		return examSessionRepository.findById(sessionId)
				.orElseThrow(() -> new ResourceNotFoundException("Exam session not found"));
	}

	private void validateViolationType(ViolationType violationType) {
		if (!TRACKED_VIOLATIONS.contains(violationType)) {
			throw new BusinessException("Unsupported violation type for violation management", HttpStatus.BAD_REQUEST);
		}
	}

	private void validateSessionStillActive(ExamSession session) {
		if (session.getSessionStatus() != ExamStatus.IN_PROGRESS) {
			throw new BusinessException("Exam session is not active for proctoring updates", HttpStatus.BAD_REQUEST);
		}
	}

	private ProctoringSessionResponse toSessionResponse(ExamSession session) {
		return new ProctoringSessionResponse(
				session.getId(),
				session.getSessionToken(),
				session.getUser().getUserId(),
				session.getExam().getExamCode(),
				session.getSessionStatus(),
				session.getViolationCount(),
				session.getBrowserFingerprint(),
				session.getIpAddress(),
				session.getSessionStartTime(),
				session.getSessionEndTime(),
				violationRepository.findByExamSessionOrderByDetectedAtDesc(session).stream()
						.map(this::toViolationResponse)
						.toList(),
				videoRecordingRepository.findByExamSessionOrderByRecordingStartTimeDesc(session).stream()
						.map(this::toRecordingResponse)
						.toList());
	}

	private ViolationResponse toViolationResponse(Violation violation) {
		String message = violation.getActionTaken() == ProctoringAction.EXAM_TERMINATED
				? "3rd violation detected. Exam terminated automatically."
				: "Violation recorded. Warning issued to candidate.";

		return new ViolationResponse(
				violation.getId(),
				violation.getExamSession().getId(),
				violation.getViolationType(),
				violation.getViolationLevel(),
				violation.getDescription(),
				violation.getDetectedAt(),
				violation.getActionTaken(),
				message,
				violation.getActionTaken() == ProctoringAction.EXAM_TERMINATED);
	}

	private ViolationSummaryResponse buildViolationSummary(ExamSession session) {
		int totalViolations = (int) violationRepository.countByExamSession(session);
		int warningCount = (int) violationRepository.countByExamSessionAndActionTaken(session, ProctoringAction.WARNING);
		boolean terminated = session.getSessionStatus() == ExamStatus.INVALIDATED;

		Violation latestViolation = violationRepository.findTopByExamSessionOrderByDetectedAtDesc(session).orElse(null);
		ViolationType lastType = latestViolation == null ? null : latestViolation.getViolationType();
		String lastActionMessage = latestViolation == null
				? "No violations reported yet"
				: toViolationResponse(latestViolation).policyMessage();

		return new ViolationSummaryResponse(
				session.getId(),
				totalViolations,
				warningCount,
				/*
				 * The session's own counter, not a count of violation rows. Rows
				 * include the detections that cost nothing — the forgiven sounds
				 * and the review-only types — so counting them would hand the
				 * client a number higher than the one termination is judged on,
				 * which is the exact mistake that used to end exams early.
				 */
				session.getViolationCount(),
				ViolationStrikeRecorder.STRIKE_LIMIT,
				terminated,
				session.getSessionStatus(),
				lastType,
				lastActionMessage);
	}

	private VideoRecordingResponse toRecordingResponse(VideoRecording recording) {
		return new VideoRecordingResponse(
				recording.getId(),
				recording.getExamSession().getId(),
				recording.getFileLocation(),
				recording.getRecordingStartTime(),
				recording.getRecordingEndTime(),
				recording.getRecordingDurationSeconds());
	}
}
