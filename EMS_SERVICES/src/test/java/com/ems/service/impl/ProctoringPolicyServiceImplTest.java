package com.ems.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import com.ems.audit.AuditEvent;
import com.ems.dto.request.ProctoringPolicyRequest;
import com.ems.dto.response.CandidateProctoringPolicyResponse;
import com.ems.dto.response.ProctoringPolicyResponse;
import com.ems.entity.CertificationApplication;
import com.ems.entity.Exam;
import com.ems.entity.ExamSession;
import com.ems.entity.ProctoringPolicy;
import com.ems.entity.User;
import com.ems.enums.CertificationLevel;
import com.ems.enums.ExamStatus;
import com.ems.enums.ViolationEnforcement;
import com.ems.enums.ViolationType;
import com.ems.exception.BusinessException;
import com.ems.exception.ResourceNotFoundException;
import com.ems.repository.CertificationApplicationRepository;
import com.ems.repository.ExamRepository;
import com.ems.repository.ExamSessionRepository;
import com.ems.repository.ProctoringPolicyRepository;
import com.ems.repository.UserRepository;
import com.ems.service.AuditService;
import com.ems.service.EffectiveProctoringPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProctoringPolicyServiceImplTest {

	private static final Long EXAM_ID = 20L;

	@Mock
	private ProctoringPolicyRepository proctoringPolicyRepository;

	@Mock
	private ExamRepository examRepository;

	@Mock
	private ExamSessionRepository examSessionRepository;

	@Mock
	private UserRepository userRepository;

	@Mock
	private CertificationApplicationRepository certificationApplicationRepository;

	@Mock
	private AuditService auditService;

	private ProctoringPolicyServiceImpl service;

	private Exam exam;

	@BeforeEach
	void setUp() {
		service = new ProctoringPolicyServiceImpl(
				proctoringPolicyRepository,
				examRepository,
				examSessionRepository,
				userRepository,
				certificationApplicationRepository,
				auditService,
				new ObjectMapper());

		exam = Exam.builder()
				.id(EXAM_ID)
				.examCode("EX-L2")
				.examName("Practitioner")
				.certificationLevel(CertificationLevel.L2)
				.build();

		when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam));
		when(proctoringPolicyRepository.findByScopeKey(any())).thenReturn(Optional.empty());
		when(proctoringPolicyRepository.saveAndFlush(any(ProctoringPolicy.class))).thenAnswer(invocation -> {
			ProctoringPolicy policy = invocation.getArgument(0);
			if (policy.getId() == null) {
				policy.setId(1L);
				policy.setVersion(0L);
			} else {
				policy.setVersion(policy.getVersion() + 1);
			}
			return policy;
		});
	}

	/** The guarantee that made shipping this safe: saving nothing changes nothing. */
	@Test
	void resolveForExam_withNothingSavedEnforcesTheBuiltInRules() {
		EffectiveProctoringPolicy policy = service.resolveForExam(EXAM_ID);

		assertThat(policy).isEqualTo(EffectiveProctoringPolicy.builtIn());
		assertThat(policy.strikeLimit()).isEqualTo(3);
		assertThat(policy.unidentifiedSoundMinDbAboveFloor()).isEqualTo(15);
		assertThat(policy.enforcementFor(ViolationType.EYES_OFF_SCREEN)).isEqualTo(ViolationEnforcement.RECORD_ONLY);
		assertThat(policy.enforcementFor(ViolationType.PROCTOR_SETUP_INVALID)).isEqualTo(ViolationEnforcement.RECORD_ONLY);
		assertThat(policy.enforcementFor(ViolationType.PHONE_DETECTED)).isEqualTo(ViolationEnforcement.STRIKE);
	}

	@Test
	void resolveForExam_prefersTheExamsOwnRulesOverTheDefault() {
		savedPolicy(ProctoringPolicy.DEFAULT_SCOPE_KEY, null, 5);
		savedPolicy(ProctoringPolicy.scopeKeyForExam(EXAM_ID), exam, 2);

		assertThat(service.resolveForExam(EXAM_ID).strikeLimit()).isEqualTo(2);
		assertThat(service.resolveForExam(99L).strikeLimit())
				.as("an exam with no rules of its own runs under the default")
				.isEqualTo(5);
	}

	@Test
	void updateDefaultPolicy_storesEveryConfigurableRuleAndAuditsTheChange() {
		ProctoringPolicyResponse response = service.updateDefaultPolicy(
				request(4, Map.of(ViolationType.TAB_SWITCH, ViolationEnforcement.DISABLED), null));

		ArgumentCaptor<ProctoringPolicy> captor = ArgumentCaptor.forClass(ProctoringPolicy.class);
		verify(proctoringPolicyRepository).saveAndFlush(captor.capture());
		ProctoringPolicy saved = captor.getValue();

		assertThat(saved.getScopeKey()).isEqualTo(ProctoringPolicy.DEFAULT_SCOPE_KEY);
		assertThat(saved.getExam()).isNull();
		assertThat(saved.getStrikeLimit()).isEqualTo(4);
		assertThat(saved.getRules())
				.hasSize(configurableTypeCount())
				.containsEntry(ViolationType.TAB_SWITCH, ViolationEnforcement.DISABLED)
				.containsEntry(ViolationType.EYES_OFF_SCREEN, ViolationEnforcement.RECORD_ONLY)
				.doesNotContainKey(ViolationType.WINDOW_MINIMIZED);

		assertThat(response.scope()).isEqualTo("DEFAULT");
		assertThat(response.version()).isZero();
		assertThat(response.rules()).containsEntry(ViolationType.TAB_SWITCH, ViolationEnforcement.DISABLED);
		verify(auditService).record(any(AuditEvent.class));
	}

	@Test
	void updateDefaultPolicy_rejectsATypeNoAdminCanConfigure() {
		assertThatThrownBy(() -> service.updateDefaultPolicy(
				request(3, Map.of(ViolationType.WINDOW_MINIMIZED, ViolationEnforcement.DISABLED), null)))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("cannot be configured");

		verify(proctoringPolicyRepository, never()).saveAndFlush(any(ProctoringPolicy.class));
	}

	@Test
	void updateExamPolicy_createsTheExamsOwnRules() {
		ProctoringPolicyResponse response = service.updateExamPolicy(EXAM_ID, request(6, Map.of(), null));

		ArgumentCaptor<ProctoringPolicy> captor = ArgumentCaptor.forClass(ProctoringPolicy.class);
		verify(proctoringPolicyRepository).saveAndFlush(captor.capture());
		assertThat(captor.getValue().getScopeKey()).isEqualTo("EXAM:20");
		assertThat(captor.getValue().getExam()).isSameAs(exam);

		assertThat(response.scope()).isEqualTo("EXAM");
		assertThat(response.examCode()).isEqualTo("EX-L2");
		assertThat(response.inheritsDefault()).isFalse();
		assertThat(response.strikeLimit()).isEqualTo(6);
	}

	/** Two admins on the same exam: the second save must not silently undo the first. */
	@Test
	void updateExamPolicy_refusesAnEditMadeAgainstAnOlderVersion() {
		savedPolicy(ProctoringPolicy.scopeKeyForExam(EXAM_ID), exam, 3).setVersion(4L);

		assertThatThrownBy(() -> service.updateExamPolicy(EXAM_ID, request(5, Map.of(), 3L)))
				.isInstanceOfSatisfying(BusinessException.class,
						ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT));

		verify(proctoringPolicyRepository, never()).saveAndFlush(any(ProctoringPolicy.class));
	}

	@Test
	void getExamPolicy_withoutRulesOfItsOwnShowsTheInheritedDefault() {
		savedPolicy(ProctoringPolicy.DEFAULT_SCOPE_KEY, null, 5);

		ProctoringPolicyResponse response = service.getExamPolicy(EXAM_ID);

		assertThat(response.inheritsDefault()).isTrue();
		assertThat(response.strikeLimit()).isEqualTo(5);
		// Nothing has been saved for this exam, so there is no version to echo.
		assertThat(response.version()).isNull();
	}

	@Test
	void resetExamPolicy_deletesTheExamsRulesAndFallsBackToTheDefault() {
		ProctoringPolicy own = savedPolicy(ProctoringPolicy.scopeKeyForExam(EXAM_ID), exam, 2);

		ProctoringPolicyResponse response = service.resetExamPolicy(EXAM_ID);

		verify(proctoringPolicyRepository).delete(own);
		verify(auditService).record(any(AuditEvent.class));
		assertThat(response.inheritsDefault()).isTrue();
		assertThat(response.strikeLimit()).isEqualTo(EffectiveProctoringPolicy.DEFAULT_STRIKE_LIMIT);
	}

	@Test
	void getPolicyForApplication_returnsTheRulesOfTheApplicationsExam() {
		User user = User.builder().id(7L).email("candidate@example.com").build();
		CertificationApplication application = CertificationApplication.builder().id(30L).user(user).exam(exam).build();
		when(userRepository.findByEmailIgnoreCase("candidate@example.com")).thenReturn(Optional.of(user));
		when(certificationApplicationRepository.findByIdAndUser(30L, user)).thenReturn(Optional.of(application));
		savedPolicy(ProctoringPolicy.scopeKeyForExam(EXAM_ID), exam, 4)
				.getRules().put(ViolationType.VOICE_DETECTED, ViolationEnforcement.DISABLED);

		CandidateProctoringPolicyResponse response = service.getPolicyForApplication("candidate@example.com", 30L);

		assertThat(response.examId()).isEqualTo(EXAM_ID);
		assertThat(response.strikeLimit()).isEqualTo(4);
		assertThat(response.rules())
				.containsEntry(ViolationType.VOICE_DETECTED, ViolationEnforcement.DISABLED)
				.doesNotContainKey(ViolationType.WINDOW_MINIMIZED);
	}

	@Test
	void getPolicyForApplication_refusesAnApplicationTheCallerDoesNotOwn() {
		User user = User.builder().id(7L).email("candidate@example.com").build();
		when(userRepository.findByEmailIgnoreCase("candidate@example.com")).thenReturn(Optional.of(user));
		when(certificationApplicationRepository.findByIdAndUser(31L, user)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getPolicyForApplication("candidate@example.com", 31L))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void snapshotForExam_readsBackAsTheSameRules() {
		ProctoringPolicy own = savedPolicy(ProctoringPolicy.scopeKeyForExam(EXAM_ID), exam, 4);
		own.setUnidentifiedSoundGrace(0);
		own.setVoiceMinDbAboveFloor(6);
		own.getRules().put(ViolationType.TAB_SWITCH, ViolationEnforcement.DISABLED);

		ExamSession session = ExamSession.builder()
				.id(300L)
				.exam(exam)
				.proctoringPolicyJson(service.snapshotForExam(EXAM_ID))
				.build();

		assertThat(service.resolveForSession(session)).isEqualTo(service.resolveForExam(EXAM_ID));
	}

	/** The fairness guarantee: rules saved mid-exam do not reach an attempt already under way. */
	@Test
	void resolveForSession_ignoresRulesSavedAfterTheAttemptStarted() {
		ProctoringPolicy own = savedPolicy(ProctoringPolicy.scopeKeyForExam(EXAM_ID), exam, 4);
		ExamSession session = ExamSession.builder()
				.id(300L)
				.exam(exam)
				.proctoringPolicyJson(service.snapshotForExam(EXAM_ID))
				.build();

		own.setStrikeLimit(1);

		assertThat(service.resolveForSession(session).strikeLimit()).isEqualTo(4);
	}

	@Test
	void resolveForSession_withoutCapturedRulesFollowsTheExamsCurrentRules() {
		savedPolicy(ProctoringPolicy.scopeKeyForExam(EXAM_ID), exam, 6);
		ExamSession startedBeforeCapture = ExamSession.builder().id(301L).exam(exam).build();

		assertThat(service.resolveForSession(startedBeforeCapture).strikeLimit()).isEqualTo(6);
	}

	@Test
	void getPolicyForApplication_showsARunningAttemptTheRulesItStartedUnder() {
		User user = User.builder().id(7L).email("candidate@example.com").build();
		CertificationApplication application = CertificationApplication.builder().id(30L).user(user).exam(exam).build();
		when(userRepository.findByEmailIgnoreCase("candidate@example.com")).thenReturn(Optional.of(user));
		when(certificationApplicationRepository.findByIdAndUser(30L, user)).thenReturn(Optional.of(application));

		// Started under the built-in rules...
		ExamSession running = ExamSession.builder()
				.id(302L)
				.exam(exam)
				.sessionStatus(ExamStatus.IN_PROGRESS)
				.proctoringPolicyJson(service.snapshotForExam(EXAM_ID))
				.build();
		when(examSessionRepository.findTopByCertificationApplicationOrderBySessionStartTimeDescIdDesc(application))
				.thenReturn(Optional.of(running));
		// ...and the exam's rules changed while it ran.
		savedPolicy(ProctoringPolicy.scopeKeyForExam(EXAM_ID), exam, 5);

		CandidateProctoringPolicyResponse response = service.getPolicyForApplication("candidate@example.com", 30L);

		assertThat(response.strikeLimit()).isEqualTo(EffectiveProctoringPolicy.DEFAULT_STRIKE_LIMIT);
	}

	private ProctoringPolicy savedPolicy(String scopeKey, Exam scopeExam, int strikeLimit) {
		ProctoringPolicy policy = ProctoringPolicy.builder()
				.id(scopeExam == null ? 1L : 2L)
				.scopeKey(scopeKey)
				.exam(scopeExam)
				.strikeLimit(strikeLimit)
				.unidentifiedSoundGrace(2)
				.unidentifiedSoundMinDbAboveFloor(15)
				.version(0L)
				.build();
		when(proctoringPolicyRepository.findByScopeKey(scopeKey)).thenReturn(Optional.of(policy));
		return policy;
	}

	private static ProctoringPolicyRequest request(int strikeLimit,
			Map<ViolationType, ViolationEnforcement> rules, Long version) {
		return new ProctoringPolicyRequest(strikeLimit, 2, 0, 0, 15, rules, version);
	}

	private static int configurableTypeCount() {
		return (int) Arrays.stream(ViolationType.values()).filter(ViolationType::isAdminConfigurable).count();
	}
}
