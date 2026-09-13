package com.ems.service.impl;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ems.audit.AuditEvent;
import com.ems.audit.AuditEventType;
import com.ems.audit.AuditOutcome;
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
import com.ems.service.ExamAttemptPolicyService;
import com.ems.util.ExamAttemptAllowance;
import com.ems.util.ExamQuestionBlueprint;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
@Transactional
public class ExamAttemptPolicyServiceImpl implements ExamAttemptPolicyService {

	static final String STALE_EDIT_MESSAGE =
			"This level's attempt allowance was changed by someone else while you were editing. Reload to see the latest value.";

	private final CertificationAttemptPolicyRepository certificationAttemptPolicyRepository;
	private final ExamRepository examRepository;
	private final QuestionRepository questionRepository;
	private final AuditService auditService;

	@Override
	@Transactional(readOnly = true)
	public int attemptsPerPayment(CertificationLevel level) {
		return certificationAttemptPolicyRepository.findByCertificationLevel(level)
				.map(CertificationAttemptPolicy::getAttemptsPerPayment)
				.orElse(ExamAttemptAllowance.DEFAULT_ATTEMPTS_PER_PAYMENT);
	}

	@Override
	@Transactional(readOnly = true)
	public List<ExamAttemptPolicyResponse> listPolicies() {
		Map<CertificationLevel, CertificationAttemptPolicy> saved = new EnumMap<>(CertificationLevel.class);
		certificationAttemptPolicyRepository.findAll()
				.forEach(policy -> saved.put(policy.getCertificationLevel(), policy));
		return Arrays.stream(CertificationLevel.values())
				.map(level -> toResponse(level, saved.get(level)))
				.toList();
	}

	@Override
	public ExamAttemptPolicyResponse updatePolicy(CertificationLevel level, ExamAttemptPolicyRequest request) {
		CertificationAttemptPolicy policy = certificationAttemptPolicyRepository.findByCertificationLevel(level)
				.orElseGet(() -> CertificationAttemptPolicy.builder().certificationLevel(level).build());
		if (policy.getId() != null && request.version() != null && !request.version().equals(policy.getVersion())) {
			throw new BusinessException(STALE_EDIT_MESSAGE, HttpStatus.CONFLICT);
		}

		int previous = policy.getId() == null
				? ExamAttemptAllowance.DEFAULT_ATTEMPTS_PER_PAYMENT
				: policy.getAttemptsPerPayment();
		policy.setAttemptsPerPayment(request.attemptsPerPayment());

		CertificationAttemptPolicy saved;
		try {
			saved = certificationAttemptPolicyRepository.saveAndFlush(policy);
		} catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException ex) {
			// Another admin saved this level first, or created its first row a
			// moment earlier: the version and the unique level each let exactly
			// one of the two through.
			throw new BusinessException(STALE_EDIT_MESSAGE, HttpStatus.CONFLICT);
		}

		log.info("Attempt allowance saved: level={} attemptsPerPayment={} (was {})",
				level, saved.getAttemptsPerPayment(), previous);
		auditService.record(AuditEvent.builder()
				.eventType(AuditEventType.ADMIN_ACTION)
				.outcome(AuditOutcome.SUCCESS)
				.targetType("ATTEMPT_POLICY")
				.targetId(level.name())
				.description("Set " + level + " attempts per payment to " + saved.getAttemptsPerPayment()
						+ " (was " + previous + ")")
				.build());
		return toResponse(level, saved);
	}

	/**
	 * @param saved the level's own row, or null while it still has the built-in
	 *              single attempt
	 */
	private ExamAttemptPolicyResponse toResponse(CertificationLevel level, CertificationAttemptPolicy saved) {
		return new ExamAttemptPolicyResponse(
				level,
				saved == null ? ExamAttemptAllowance.DEFAULT_ATTEMPTS_PER_PAYMENT : saved.getAttemptsPerPayment(),
				saved == null ? null : saved.getVersion(),
				saved == null ? null : lastEditedBy(saved),
				saved == null ? null : lastEditedAt(saved),
				distinctPapers(level));
	}

	/**
	 * How many attempts at this level can each be drawn entirely from questions
	 * the candidate has not seen: the active pool at each severity divided by
	 * what one paper takes from it, for whichever published exam runs out first.
	 */
	private Integer distinctPapers(CertificationLevel level) {
		List<Exam> exams = examRepository.search(null, null, level, null, true);
		if (exams.isEmpty()) {
			return null;
		}

		Map<QuestionSeverity, Long> pool = new EnumMap<>(QuestionSeverity.class);
		for (QuestionSeverity severity : QuestionSeverity.values()) {
			pool.put(severity, questionRepository.countByCertificationLevelAndSeverityAndActiveTrue(level, severity));
		}

		long fewest = Long.MAX_VALUE;
		for (Exam exam : exams) {
			for (Map.Entry<QuestionSeverity, Integer> share : ExamQuestionBlueprint.of(exam).questionCounts().entrySet()) {
				if (share.getValue() > 0) {
					fewest = Math.min(fewest, pool.get(share.getKey()) / share.getValue());
				}
			}
		}
		return fewest == Long.MAX_VALUE ? null : (int) Math.min(fewest, Integer.MAX_VALUE);
	}

	private static String lastEditedBy(CertificationAttemptPolicy policy) {
		return policy.getUpdatedBy() != null ? policy.getUpdatedBy() : policy.getCreatedBy();
	}

	/** Spring Data's default auditing clock stamps local time in the JVM's zone. */
	private static Instant lastEditedAt(CertificationAttemptPolicy policy) {
		LocalDateTime editedAt = policy.getUpdatedDate() != null ? policy.getUpdatedDate() : policy.getCreatedDate();
		return editedAt == null ? null : editedAt.atZone(ZoneId.systemDefault()).toInstant();
	}
}
