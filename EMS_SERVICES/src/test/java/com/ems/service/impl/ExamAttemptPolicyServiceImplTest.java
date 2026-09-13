package com.ems.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.ems.audit.AuditEvent;
import com.ems.dto.request.ExamAttemptPolicyRequest;
import com.ems.dto.response.ExamAttemptPolicyResponse;
import com.ems.entity.CertificationAttemptPolicy;
import com.ems.entity.Exam;
import com.ems.enums.CertificationLevel;
import com.ems.enums.QuestionSeverity;
import com.ems.exception.BusinessException;
import com.ems.repository.CertificationAttemptPolicyRepository;
import com.ems.repository.ExamRepository;
import com.ems.repository.QuestionRepository;
import com.ems.service.AuditService;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExamAttemptPolicyServiceImplTest {

	@Mock
	private CertificationAttemptPolicyRepository certificationAttemptPolicyRepository;

	@Mock
	private ExamRepository examRepository;

	@Mock
	private QuestionRepository questionRepository;

	@Mock
	private AuditService auditService;

	private ExamAttemptPolicyServiceImpl service;

	@BeforeEach
	void setUp() {
		service = new ExamAttemptPolicyServiceImpl(
				certificationAttemptPolicyRepository,
				examRepository,
				questionRepository,
				auditService);

		when(certificationAttemptPolicyRepository.findByCertificationLevel(any())).thenReturn(Optional.empty());
		when(certificationAttemptPolicyRepository.saveAndFlush(any(CertificationAttemptPolicy.class)))
				.thenAnswer(invocation -> {
					CertificationAttemptPolicy policy = invocation.getArgument(0);
					if (policy.getId() == null) {
						policy.setId(1L);
						policy.setVersion(0L);
					} else {
						policy.setVersion(policy.getVersion() + 1);
					}
					return policy;
				});
	}

	/** The guarantee that made shipping this safe: saving nothing means pay once, sit once. */
	@Test
	void attemptsPerPayment_withNothingSavedIsASingleAttempt() {
		assertThat(service.attemptsPerPayment(CertificationLevel.L2)).isEqualTo(1);
	}

	@Test
	void updatePolicy_savesTheLevelsAllowanceAndAuditsIt() {
		ExamAttemptPolicyResponse response = service.updatePolicy(
				CertificationLevel.L1, new ExamAttemptPolicyRequest(3, null));

		assertThat(response.certificationLevel()).isEqualTo(CertificationLevel.L1);
		assertThat(response.attemptsPerPayment()).isEqualTo(3);
		assertThat(response.version()).isZero();
		verify(auditService).record(any(AuditEvent.class));
	}

	/** A second admin's save must not silently overwrite the first's. */
	@Test
	void updatePolicy_refusesAnEditMadeAgainstAStaleVersion() {
		CertificationAttemptPolicy saved = CertificationAttemptPolicy.builder()
				.id(1L)
				.certificationLevel(CertificationLevel.L1)
				.attemptsPerPayment(3)
				.version(4L)
				.build();
		when(certificationAttemptPolicyRepository.findByCertificationLevel(CertificationLevel.L1))
				.thenReturn(Optional.of(saved));

		assertThatThrownBy(() -> service.updatePolicy(CertificationLevel.L1, new ExamAttemptPolicyRequest(2, 3L)))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("changed by someone else");

		verify(certificationAttemptPolicyRepository, never()).saveAndFlush(any());
		assertThat(saved.getAttemptsPerPayment()).isEqualTo(3);
	}

	/**
	 * An exam with no blueprint of its own draws 30 questions at 20/40/40 —
	 * 6 LOW, 12 MEDIUM and 12 HIGH. A bank of 20, 30 and 25 holds three LOW
	 * shares but only two MEDIUM and two HIGH, so two attempts can be entirely
	 * fresh.
	 */
	@Test
	void listPolicies_reportsHowManyFreshPapersTheQuestionBankCanBuild() {
		Exam exam = Exam.builder().id(20L).examCode("EX-L1").certificationLevel(CertificationLevel.L1).build();
		when(certificationAttemptPolicyRepository.findAll()).thenReturn(List.of());
		when(examRepository.search(null, null, CertificationLevel.L1, null, true)).thenReturn(List.of(exam));
		when(questionRepository.countByCertificationLevelAndSeverityAndActiveTrue(
				CertificationLevel.L1, QuestionSeverity.LOW)).thenReturn(20L);
		when(questionRepository.countByCertificationLevelAndSeverityAndActiveTrue(
				CertificationLevel.L1, QuestionSeverity.MEDIUM)).thenReturn(30L);
		when(questionRepository.countByCertificationLevelAndSeverityAndActiveTrue(
				CertificationLevel.L1, QuestionSeverity.HIGH)).thenReturn(25L);

		List<ExamAttemptPolicyResponse> policies = service.listPolicies();

		assertThat(policies).extracting(ExamAttemptPolicyResponse::certificationLevel)
				.containsExactly(CertificationLevel.L1, CertificationLevel.L2, CertificationLevel.L3);
		assertThat(policies.get(0).attemptsPerPayment()).isEqualTo(1);
		assertThat(policies.get(0).distinctPapers()).isEqualTo(2);
		// No published exam at L2, so there is no paper to measure the bank against.
		assertThat(policies.get(1).distinctPapers()).isNull();
	}
}
