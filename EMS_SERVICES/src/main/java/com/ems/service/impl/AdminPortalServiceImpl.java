package com.ems.service.impl;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ems.audit.AuditEvent;
import com.ems.audit.AuditEventType;
import com.ems.audit.AuditOutcome;
import com.ems.dto.response.AdminAuditLogResponse;
import com.ems.dto.response.AdminUserResponse;
import com.ems.dto.response.AdminPaymentResponse;
import com.ems.dto.response.AdminViolationResponse;
import com.ems.dto.response.CertificateResponse;
import com.ems.dto.response.CertificateVerificationResponse;
import com.ems.dto.response.CertificationApplicationResponse;
import com.ems.dto.response.CertificationSummaryResponse;
import com.ems.dto.response.QuestionResponse;
import com.ems.dto.response.VideoRecordingResponse;
import com.ems.entity.AuditLog;
import com.ems.entity.Certificate;
import com.ems.entity.CertificationApplication;
import com.ems.entity.Exam;
import com.ems.entity.ExamSession;
import com.ems.entity.Payment;
import com.ems.entity.User;
import com.ems.entity.VideoRecording;
import com.ems.entity.Violation;
import com.ems.enums.CertificationApplicationStatus;
import com.ems.enums.CertificationLevel;
import com.ems.enums.ProctoringAction;
import com.ems.enums.QuestionSeverity;
import com.ems.exception.ResourceNotFoundException;
import com.ems.repository.AuditLogRepository;
import com.ems.repository.CertificationApplicationRepository;
import com.ems.repository.CertificationRepository;
import com.ems.repository.CertificateRepository;
import com.ems.repository.ExamSessionRepository;
import com.ems.repository.PaymentRepository;
import com.ems.repository.UserRepository;
import com.ems.repository.VideoRecordingRepository;
import com.ems.repository.ViolationRepository;
import com.ems.service.AdminPortalService;
import com.ems.service.AuditService;
import com.ems.service.CertificateService;
import com.ems.service.CertificateTemplate;
import com.ems.service.QuestionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
@Transactional(readOnly = true)
public class AdminPortalServiceImpl implements AdminPortalService {

	private final UserRepository userRepository;
	private final PaymentRepository paymentRepository;
	private final CertificateRepository certificateRepository;
	private final CertificationRepository certificationRepository;
	private final CertificationApplicationRepository certificationApplicationRepository;
	private final ViolationRepository violationRepository;
	private final VideoRecordingRepository videoRecordingRepository;
	private final ExamSessionRepository examSessionRepository;
	private final AuditLogRepository auditLogRepository;
	private final QuestionService questionService;
	private final CertificateService certificateService;
	private final AuditService auditService;

	@Override
	public List<AdminUserResponse> searchUsers(String searchText, Boolean enabled) {
		String searchPattern = toLikePattern(searchText);

		return userRepository.search(searchPattern, enabled).stream()
				.map(this::toAdminUserResponse)
				.toList();
	}

	private String toLikePattern(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
	}

	@Override
	public AdminUserResponse getUserById(Long userId) {
		return toAdminUserResponse(findUser(userId));
	}

	@Override
	@Transactional
	@CacheEvict(cacheNames = { "reports", "dashboard" }, allEntries = true)
	public AdminUserResponse setUserEnabled(Long userId, boolean enabled) {
		User user = findUser(userId);
		user.setEnabled(enabled);
		User saved = userRepository.save(user);
		log.info("Admin toggled enabled={} for userId={}", enabled, saved.getUserId());
		auditService.record(AuditEvent.builder()
				.eventType(AuditEventType.ADMIN_ACTION)
				.outcome(AuditOutcome.SUCCESS)
				.targetUserId(saved.getUserId())
				.targetType("USER")
				.targetId(String.valueOf(saved.getId()))
				.description((enabled ? "Enabled" : "Disabled") + " user account " + saved.getUserId())
				.build());
		return toAdminUserResponse(saved);
	}

	@Override
	@Transactional
	@CacheEvict(cacheNames = { "reports", "dashboard" }, allEntries = true)
	public AdminUserResponse setUserLocked(Long userId, boolean locked) {
		User user = findUser(userId);
		user.setAccountNonLocked(!locked);
		User saved = userRepository.save(user);
		log.info("Admin toggled accountNonLocked={} for userId={}", !locked, saved.getUserId());
		auditService.record(AuditEvent.builder()
				.eventType(AuditEventType.ADMIN_ACTION)
				.outcome(AuditOutcome.SUCCESS)
				.targetUserId(saved.getUserId())
				.targetType("USER")
				.targetId(String.valueOf(saved.getId()))
				.description((locked ? "Locked" : "Unlocked") + " user account " + saved.getUserId())
				.build());
		return toAdminUserResponse(saved);
	}

	@Override
	public List<QuestionResponse> searchQuestions(String questionCode, CertificationLevel level,
			QuestionSeverity severity, Boolean active, String searchText) {
		return questionService.search(questionCode, level, severity, active, searchText);
	}

	@Override
	public List<AdminPaymentResponse> getAllPayments() {
		return paymentRepository.findAllByOrderByCreatedDateDesc().stream()
				.map(this::toAdminPaymentResponse)
				.toList();
	}

	@Override
	public List<CertificationApplicationResponse> getAllApplications() {
		return certificationApplicationRepository.findAll().stream()
				.map(app -> new CertificationApplicationResponse(
						app.getId(),
						app.getUser().getUserId(),
						app.getCertificationLevel(),
						app.getApplicationStatus(),
						app.getAppliedOn(),
						app.getRemarks()))
				.toList();
	}

	@Override
	public List<CertificationSummaryResponse> getAllCertifications() {
		return certificationRepository.findAll().stream()
				.map(cert -> new CertificationSummaryResponse(
						cert.getId(),
						cert.getCertificationLevel(),
						cert.getCertificationStatus(),
						cert.getIssueDate(),
						cert.getExpiryDate()))
				.toList();
	}

	@Override
	public List<CertificateResponse> getAllCertificates() {
		return certificateRepository.findAll().stream()
				.map(this::toAdminCertificateResponse)
				.toList();
	}

	private CertificateResponse toAdminCertificateResponse(Certificate certificate) {
		// Descriptive copy comes from the level template, exactly as it does for
		// the candidate-facing endpoint, so both views name a certificate the
		// same way the issued PDF does.
		CertificateTemplate template = CertificateTemplate
				.forLevel(certificate.getCertification().getCertificationLevel());
		User user = certificate.getExamAttempt().getExamSession().getUser();
		return new CertificateResponse(
				certificate.getCertificateNumber(),
				user.getFirstName() + " " + user.getLastName(),
				certificate.getCertification().getUser().getUserId(),
				certificate.getCertification().getCertificationLevel(),
				certificate.getIssueDate(),
				certificate.getExpiryDate(),
				certificate.getVerificationUrl(),
				"/api/certificates/" + certificate.getCertificateNumber() + "/download/admin",
				template.awardTitle(),
				template.eyebrow(),
				template.tierLine(),
				template.citationText(),
				template.competencies(),
				template.levelIndex(),
				CertificateTemplate.TOTAL_LEVELS);
	}

	@Override
	public CertificateVerificationResponse verifyCertificate(String certificateNumber) {
		return certificateService.verify(certificateNumber);
	}

	@Override
	public List<AdminViolationResponse> getAllViolations() {
		return violationRepository.findAllByOrderByDetectedAtDesc().stream()
				.map(this::toAdminViolationResponse)
				.toList();
	}

	@Override
	public List<AdminViolationResponse> getViolationsForSession(Long sessionId) {
		ExamSession session = findSession(sessionId);
		return violationRepository.findByExamSessionOrderByDetectedAtDesc(session).stream()
				.map(this::toAdminViolationResponse)
				.toList();
	}

	@Override
	public List<VideoRecordingResponse> getAllRecordings() {
		return videoRecordingRepository.findAllByOrderByRecordingStartTimeDesc().stream()
				.map(this::toRecordingResponse)
				.toList();
	}

	@Override
	public List<VideoRecordingResponse> getRecordingsForSession(Long sessionId) {
		ExamSession session = findSession(sessionId);
		return videoRecordingRepository.findByExamSessionOrderByRecordingStartTimeDesc(session).stream()
				.map(this::toRecordingResponse)
				.toList();
	}

	@Override
	public List<AdminAuditLogResponse> searchAuditLogs(AuditEventType eventType, AuditOutcome outcome, String actor,
			String targetUserId, Instant from, Instant to, int limit) {
		int cappedLimit = Math.max(1, Math.min(limit, 1000));
		return auditLogRepository.search(
				eventType,
				outcome,
				toLikePattern(actor),
				targetUserId == null || targetUserId.isBlank() ? null : targetUserId.trim().toLowerCase(Locale.ROOT),
				from,
				to,
				PageRequest.of(0, cappedLimit))
				.stream()
				.map(this::toAdminAuditLogResponse)
				.toList();
	}

	private AdminAuditLogResponse toAdminAuditLogResponse(AuditLog auditLog) {
		return new AdminAuditLogResponse(
				auditLog.getId(),
				auditLog.getEventType(),
				auditLog.getOutcome(),
				auditLog.getActorEmail(),
				auditLog.getActorUserId(),
				auditLog.getTargetUserId(),
				auditLog.getTargetType(),
				auditLog.getTargetId(),
				auditLog.getDescription(),
				auditLog.getIpAddress(),
				auditLog.getCorrelationId(),
				auditLog.getOccurredAt());
	}

	private User findUser(Long userId) {
		return userRepository.findById(userId)
				.orElseThrow(() -> new ResourceNotFoundException("User not found"));
	}

	private ExamSession findSession(Long sessionId) {
		return examSessionRepository.findById(sessionId)
				.orElseThrow(() -> new ResourceNotFoundException("Exam session not found"));
	}

	private AdminUserResponse toAdminUserResponse(User user) {
		return new AdminUserResponse(
				user.getId(),
				user.getUserId(),
				user.getFirstName(),
				user.getLastName(),
				user.getEmail(),
				user.getMobileNumber(),
				user.getCurrentSkillLevel(),
				user.getCurrentOrganization(),
				user.getQualification(),
				user.getYearsOfExperience(),
				user.isEnabled(),
				user.isAccountNonLocked());
	}

	private AdminPaymentResponse toAdminPaymentResponse(Payment payment) {
		CertificationApplication application = payment.getCertificationApplication();
		User user = payment.getUser();
		Exam exam = payment.getExam();

		return new AdminPaymentResponse(
				payment.getId(),
				payment.getTransactionId(),
				payment.getPaymentStatus(),
				payment.getAmount(),
				payment.getCurrency(),
				payment.getProvider(),
				payment.getPaymentDate(),
				payment.getProviderReference(),
				application == null ? null : application.getId(),
				application == null ? null : application.getApplicationStatus(),
				application == null ? null : application.getAppliedOn(),
				user.getUserId(),
				user.getFirstName() + " " + user.getLastName(),
				user.getEmail(),
				exam.getId(),
				exam.getExamCode(),
				exam.getExamName(),
				exam.getCertificationLevel());
	}

	private AdminViolationResponse toAdminViolationResponse(Violation violation) {
		ExamSession session = violation.getExamSession();
		User user = session.getUser();
		Exam exam = session.getExam();
		CertificationApplication application = certificationApplicationRepository
				.findTopByUserAndExamAndApplicationStatusInOrderByAppliedOnDescIdDesc(
						user,
						exam,
						// Every status, so the invigilator sees the application behind a
						// violation whatever became of it. Spelled as allOf rather than a
						// hand-written list, which had already fallen a status behind.
						EnumSet.allOf(CertificationApplicationStatus.class))
				.orElse(null);

		String message = switch (violation.getActionTaken()) {
			case EXAM_TERMINATED -> "3rd violation detected. Exam terminated automatically.";
			default -> "Violation recorded. Warning issued to candidate.";
		};

		return new AdminViolationResponse(
				violation.getId(),
				session.getId(),
				application == null ? null : application.getId(),
				user.getUserId(),
				user.getFirstName() + " " + user.getLastName(),
				user.getEmail(),
				exam.getId(),
				exam.getExamCode(),
				exam.getExamName(),
				exam.getCertificationLevel(),
				session.getSessionStatus(),
				violation.getViolationType(),
				violation.getViolationLevel(),
				violation.getDescription(),
				violation.getDetectedAt(),
				violation.getActionTaken(),
				message,
				violation.getActionTaken() == ProctoringAction.EXAM_TERMINATED);
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
