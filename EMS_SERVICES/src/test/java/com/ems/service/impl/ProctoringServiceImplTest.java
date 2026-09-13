package com.ems.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ems.dto.request.ViolationReportRequest;
import com.ems.dto.response.ViolationResponse;
import com.ems.entity.CertificationApplication;
import com.ems.entity.Exam;
import com.ems.entity.ExamSession;
import com.ems.entity.User;
import com.ems.entity.Violation;
import com.ems.enums.CertificationApplicationStatus;
import com.ems.enums.CertificationLevel;
import com.ems.enums.ExamStatus;
import com.ems.enums.PaymentStatus;
import com.ems.enums.ProctoringAction;
import com.ems.enums.ViolationEnforcement;
import com.ems.enums.ViolationType;
import com.ems.exception.BusinessException;
import com.ems.repository.CertificationApplicationRepository;
import com.ems.repository.ExamSessionRepository;
import com.ems.repository.VideoRecordingRepository;
import com.ems.repository.ProctorEvidenceRepository;
import com.ems.repository.ViolationRepository;
import com.ems.service.AuditService;
import com.ems.service.EffectiveProctoringPolicy;
import com.ems.service.ProctorEvidenceStorageService;
import com.ems.service.ProctoringPolicyService;

@ExtendWith(MockitoExtension.class)
class ProctoringServiceImplTest {

	@Mock
	private ExamSessionRepository examSessionRepository;

	@Mock
	private VideoRecordingRepository videoRecordingRepository;

	@Mock
	private ViolationRepository violationRepository;

	@Mock
	private CertificationApplicationRepository certificationApplicationRepository;

	/**
	 * Wired by hand rather than via {@code @InjectMocks}: the invalidation rule was
	 * extracted into a collaborator that these tests must exercise for real, and
	 * Mockito does not guarantee that one {@code @InjectMocks} field is constructed
	 * before another that depends on it.
	 */
	@Mock
	private ProctorEvidenceRepository proctorEvidenceRepository;

	@Mock
	private ProctorEvidenceStorageService proctorEvidenceStorageService;

	@Mock
	private AuditService auditService;

	@Mock
	private ProctoringPolicyService proctoringPolicyService;

	private ProctoringServiceImpl proctoringService;

	@BeforeEach
	void setUp() {
		ExamInvalidationHandler examInvalidationHandler =
				new ExamInvalidationHandler(certificationApplicationRepository);
		proctoringService = new ProctoringServiceImpl(
				examSessionRepository,
				videoRecordingRepository,
				violationRepository,
				proctorEvidenceRepository,
				proctorEvidenceStorageService,
				examInvalidationHandler,
				auditService,
				proctoringPolicyService);

		// No policy saved unless a test says otherwise: the built-in rules.
		lenient().when(proctoringPolicyService.resolveForSession(any())).thenReturn(EffectiveProctoringPolicy.builtIn());
	}

	@Test
	void reportViolation_thirdViolationInvalidatesSessionAndForcesReapply() {
		User user = User.builder().id(100L).userId("U-100").email("user@example.com").build();
		Exam exam = Exam.builder().id(200L).examCode("EX-L1").certificationLevel(CertificationLevel.L1).build();
		ExamSession session = ExamSession.builder()
				.id(300L)
				.sessionToken(UUID.randomUUID())
				.user(user)
				.exam(exam)
				.sessionStatus(ExamStatus.IN_PROGRESS)
				.violationCount(2)
				.build();

		CertificationApplication application = CertificationApplication.builder()
				.id(400L)
				.user(user)
				.exam(exam)
				.applicationStatus(CertificationApplicationStatus.APPLIED)
				.paymentStatus(PaymentStatus.SUCCESS)
				.remarks("Scheduled")
				.build();

		when(examSessionRepository.findByIdAndUserEmailIgnoreCase(300L, "user@example.com"))
				.thenReturn(Optional.of(session));
		when(violationRepository.save(any(Violation.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(examSessionRepository.save(any(ExamSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(certificationApplicationRepository.findTopByUserAndExamOrderByAppliedOnDescIdDesc(user, exam))
				.thenReturn(Optional.of(application));

		ViolationResponse response = proctoringService.reportViolation(
				"user@example.com",
				300L,
				new ViolationReportRequest(ViolationType.TAB_SWITCH, "Tab switch detected"));

		assertThat(response.actionTaken()).isEqualTo(ProctoringAction.EXAM_TERMINATED);
		assertThat(response.examTerminated()).isTrue();
		assertThat(session.getViolationCount()).isEqualTo(3);
		assertThat(session.getSessionStatus()).isEqualTo(ExamStatus.INVALIDATED);
		assertThat(session.getSessionEndTime()).isNotNull();
		assertThat(application.getApplicationStatus()).isEqualTo(CertificationApplicationStatus.TERMINATED);
		assertThat(application.getRemarks()).contains("Re-apply and complete payment");

		verify(certificationApplicationRepository).save(application);
	}

	@Test
	void reportViolation_firstViolationDoesNotChangeApplicationStatus() {
		User user = User.builder().id(101L).userId("U-101").email("first@example.com").build();
		Exam exam = Exam.builder().id(201L).examCode("EX-L2").certificationLevel(CertificationLevel.L2).build();
		ExamSession session = ExamSession.builder()
				.id(301L)
				.sessionToken(UUID.randomUUID())
				.user(user)
				.exam(exam)
				.sessionStatus(ExamStatus.IN_PROGRESS)
				.violationCount(0)
				.build();

		when(examSessionRepository.findByIdAndUserEmailIgnoreCase(301L, "first@example.com"))
				.thenReturn(Optional.of(session));
		when(violationRepository.save(any(Violation.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(examSessionRepository.save(any(ExamSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

		ViolationResponse response = proctoringService.reportViolation(
				"first@example.com",
				301L,
				new ViolationReportRequest(ViolationType.WEBCAM_OFF, "Webcam disconnected"));

		assertThat(response.actionTaken()).isEqualTo(ProctoringAction.WARNING);
		assertThat(response.examTerminated()).isFalse();
		assertThat(session.getSessionStatus()).isEqualTo(ExamStatus.IN_PROGRESS);
		assertThat(session.getViolationCount()).isEqualTo(1);

		verify(certificationApplicationRepository, never()).save(any(CertificationApplication.class));
	}

	@Test
	void reportViolation_rejectsATypeTheExamDoesNotMonitor() {
		User user = User.builder().id(102L).userId("U-102").email("off@example.com").build();
		Exam exam = Exam.builder().id(202L).examCode("EX-L3").certificationLevel(CertificationLevel.L3).build();
		ExamSession session = ExamSession.builder()
				.id(302L)
				.sessionToken(UUID.randomUUID())
				.user(user)
				.exam(exam)
				.sessionStatus(ExamStatus.IN_PROGRESS)
				.violationCount(0)
				.build();

		when(examSessionRepository.findByIdAndUserEmailIgnoreCase(302L, "off@example.com"))
				.thenReturn(Optional.of(session));
		when(proctoringPolicyService.resolveForSession(session)).thenReturn(new EffectiveProctoringPolicy(
				3, 2, 0, 0, 15, Map.of(ViolationType.TAB_SWITCH, ViolationEnforcement.DISABLED)));

		assertThatThrownBy(() -> proctoringService.reportViolation(
				"off@example.com",
				302L,
				new ViolationReportRequest(ViolationType.TAB_SWITCH, "Tab switch detected")))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("not monitored");

		assertThat(session.getViolationCount()).isZero();
		verify(violationRepository, never()).save(any(Violation.class));
	}

	@Test
	void reportViolation_thirdStrikeUnderAHigherLimitIsOnlyAWarning() {
		User user = User.builder().id(103L).userId("U-103").email("limit@example.com").build();
		Exam exam = Exam.builder().id(203L).examCode("EX-L1B").certificationLevel(CertificationLevel.L1).build();
		ExamSession session = ExamSession.builder()
				.id(303L)
				.sessionToken(UUID.randomUUID())
				.user(user)
				.exam(exam)
				.sessionStatus(ExamStatus.IN_PROGRESS)
				.violationCount(2)
				.build();

		when(examSessionRepository.findByIdAndUserEmailIgnoreCase(303L, "limit@example.com"))
				.thenReturn(Optional.of(session));
		when(proctoringPolicyService.resolveForSession(session)).thenReturn(new EffectiveProctoringPolicy(
				4, 2, 0, 0, 15, Map.of()));
		when(violationRepository.save(any(Violation.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(examSessionRepository.save(any(ExamSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

		ViolationResponse response = proctoringService.reportViolation(
				"limit@example.com",
				303L,
				new ViolationReportRequest(ViolationType.TAB_SWITCH, "Tab switch detected"));

		assertThat(response.actionTaken()).isEqualTo(ProctoringAction.WARNING);
		assertThat(response.examTerminated()).isFalse();
		assertThat(session.getViolationCount()).isEqualTo(3);
		assertThat(session.getSessionStatus()).isEqualTo(ExamStatus.IN_PROGRESS);
		verify(certificationApplicationRepository, never()).save(any(CertificationApplication.class));
	}
}
