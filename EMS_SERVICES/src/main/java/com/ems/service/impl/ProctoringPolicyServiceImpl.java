package com.ems.service.impl;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ems.audit.AuditEvent;
import com.ems.audit.AuditEventType;
import com.ems.audit.AuditOutcome;
import com.ems.dto.request.ProctoringPolicyRequest;
import com.ems.dto.response.CandidateProctoringPolicyResponse;
import com.ems.dto.response.ExamProctoringPolicySummaryResponse;
import com.ems.dto.response.ProctoringPolicyResponse;
import com.ems.entity.CertificationApplication;
import com.ems.entity.Exam;
import com.ems.entity.ExamSession;
import com.ems.entity.ProctoringPolicy;
import com.ems.entity.User;
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
import com.ems.service.ProctoringPolicyService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
@Transactional
public class ProctoringPolicyServiceImpl implements ProctoringPolicyService {

	static final String SCOPE_DEFAULT = "DEFAULT";
	static final String SCOPE_EXAM = "EXAM";

	static final String STALE_EDIT_MESSAGE =
			"These proctoring rules were changed by someone else while you were editing. Reload to see the latest rules.";

	private final ProctoringPolicyRepository proctoringPolicyRepository;
	private final ExamRepository examRepository;
	private final ExamSessionRepository examSessionRepository;
	private final UserRepository userRepository;
	private final CertificationApplicationRepository certificationApplicationRepository;
	private final AuditService auditService;
	private final ObjectMapper objectMapper;

	@Override
	@Transactional(readOnly = true)
	public EffectiveProctoringPolicy resolveForExam(Long examId) {
		Optional<ProctoringPolicy> examPolicy = examId == null ? Optional.empty() : findExamPolicy(examId);
		return examPolicy
				.or(this::findDefaultPolicy)
				.map(ProctoringPolicyServiceImpl::toEffective)
				.orElseGet(EffectiveProctoringPolicy::builtIn);
	}

	@Override
	@Transactional(readOnly = true)
	public EffectiveProctoringPolicy resolveForSession(ExamSession session) {
		String snapshot = session.getProctoringPolicyJson();
		if (snapshot != null && !snapshot.isBlank()) {
			try {
				return objectMapper.readValue(snapshot, EffectiveProctoringPolicy.class);
			} catch (JsonProcessingException ex) {
				// The lesser harm: the exam's current rules are what the attempt
				// would have captured had nothing changed since it started.
				log.warn("Unreadable proctoring rules on sessionId={}; using the exam's current rules: {}",
						session.getId(), ex.getOriginalMessage());
			}
		}
		return resolveForExam(session.getExam() == null ? null : session.getExam().getId());
	}

	@Override
	@Transactional(readOnly = true)
	public String snapshotForExam(Long examId) {
		try {
			return objectMapper.writeValueAsString(resolveForExam(examId));
		} catch (JsonProcessingException ex) {
			// A record of ints and enums does not fail to serialise; the checked
			// exception still has to go somewhere.
			throw new IllegalStateException("Could not capture the proctoring rules for exam " + examId, ex);
		}
	}

	@Override
	@Transactional(readOnly = true)
	public ProctoringPolicyResponse getDefaultPolicy() {
		return findDefaultPolicy()
				.map(policy -> toResponse(SCOPE_DEFAULT, null, false, toEffective(policy), policy))
				.orElseGet(() -> toResponse(SCOPE_DEFAULT, null, false, EffectiveProctoringPolicy.builtIn(), null));
	}

	@Override
	public ProctoringPolicyResponse updateDefaultPolicy(ProctoringPolicyRequest request) {
		ProctoringPolicy policy = findDefaultPolicy()
				.orElseGet(() -> ProctoringPolicy.builder().scopeKey(ProctoringPolicy.DEFAULT_SCOPE_KEY).build());
		ProctoringPolicy saved = applyAndSave(policy, request);

		log.info("Default proctoring rules saved: {}", describe(saved));
		audit(ProctoringPolicy.DEFAULT_SCOPE_KEY, "Saved default proctoring rules: " + describe(saved));
		return toResponse(SCOPE_DEFAULT, null, false, toEffective(saved), saved);
	}

	@Override
	@Transactional(readOnly = true)
	public List<ExamProctoringPolicySummaryResponse> listExamPolicies() {
		Set<Long> withOwnPolicy = new HashSet<>(proctoringPolicyRepository.findExamIdsWithOwnPolicy());
		return examRepository.findAll(Sort.by("certificationLevel", "examCode")).stream()
				.map(exam -> new ExamProctoringPolicySummaryResponse(
						exam.getId(),
						exam.getExamCode(),
						exam.getExamName(),
						exam.getCertificationLevel(),
						exam.isPublished(),
						withOwnPolicy.contains(exam.getId())))
				.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public ProctoringPolicyResponse getExamPolicy(Long examId) {
		Exam exam = findExam(examId);
		return findExamPolicy(examId)
				.map(policy -> toResponse(SCOPE_EXAM, exam, false, toEffective(policy), policy))
				.orElseGet(() -> inheritedResponse(exam));
	}

	@Override
	public ProctoringPolicyResponse updateExamPolicy(Long examId, ProctoringPolicyRequest request) {
		Exam exam = findExam(examId);
		ProctoringPolicy policy = findExamPolicy(examId)
				.orElseGet(() -> ProctoringPolicy.builder()
						.scopeKey(ProctoringPolicy.scopeKeyForExam(examId))
						.exam(exam)
						.build());
		ProctoringPolicy saved = applyAndSave(policy, request);

		log.info("Proctoring rules saved for examCode={}: {}", exam.getExamCode(), describe(saved));
		audit(String.valueOf(examId),
				"Saved proctoring rules for exam " + exam.getExamCode() + ": " + describe(saved));
		return toResponse(SCOPE_EXAM, exam, false, toEffective(saved), saved);
	}

	@Override
	public ProctoringPolicyResponse resetExamPolicy(Long examId) {
		Exam exam = findExam(examId);
		findExamPolicy(examId).ifPresent(policy -> {
			proctoringPolicyRepository.delete(policy);
			log.info("Proctoring rules removed for examCode={}; the default applies", exam.getExamCode());
			audit(String.valueOf(examId),
					"Reverted exam " + exam.getExamCode() + " to the default proctoring rules");
		});
		return inheritedResponse(exam);
	}

	@Override
	@Transactional(readOnly = true)
	public CandidateProctoringPolicyResponse getPolicyForApplication(String email, Long applicationId) {
		User user = userRepository.findByEmailIgnoreCase(email)
				.orElseThrow(() -> new ResourceNotFoundException("User not found"));
		CertificationApplication application = certificationApplicationRepository.findByIdAndUser(applicationId, user)
				.orElseThrow(() -> new ResourceNotFoundException("Exam application not found"));

		// exam_ref is nullable on applications. Without an exam there is no exam
		// policy to find, and the default is what an attempt would run under.
		Long examId = application.getExam() == null ? null : application.getExam().getId();

		// A running attempt keeps the rules it started under, so a candidate who
		// rejoins is shown the rules the server is actually judging them by.
		EffectiveProctoringPolicy policy = examSessionRepository
				.findTopByCertificationApplicationOrderBySessionStartTimeDescIdDesc(application)
				.filter(session -> session.getSessionStatus() == ExamStatus.IN_PROGRESS)
				.map(this::resolveForSession)
				.orElseGet(() -> resolveForExam(examId));

		return new CandidateProctoringPolicyResponse(
				examId,
				policy.strikeLimit(),
				policy.voiceMinDbAboveFloor(),
				policy.backgroundNoiseMinDbAboveFloor(),
				policy.unidentifiedSoundMinDbAboveFloor(),
				policy.configurableRules());
	}

	private Optional<ProctoringPolicy> findDefaultPolicy() {
		return proctoringPolicyRepository.findByScopeKey(ProctoringPolicy.DEFAULT_SCOPE_KEY);
	}

	private Optional<ProctoringPolicy> findExamPolicy(Long examId) {
		return proctoringPolicyRepository.findByScopeKey(ProctoringPolicy.scopeKeyForExam(examId));
	}

	private Exam findExam(Long examId) {
		return examRepository.findById(examId)
				.orElseThrow(() -> new ResourceNotFoundException("Exam not found"));
	}

	/** An exam with no rules of its own, shown with the rules its attempts actually run under. */
	private ProctoringPolicyResponse inheritedResponse(Exam exam) {
		EffectiveProctoringPolicy inherited = findDefaultPolicy()
				.map(ProctoringPolicyServiceImpl::toEffective)
				.orElseGet(EffectiveProctoringPolicy::builtIn);
		return toResponse(SCOPE_EXAM, exam, true, inherited, null);
	}

	private ProctoringPolicy applyAndSave(ProctoringPolicy policy, ProctoringPolicyRequest request) {
		if (policy.getId() != null && request.version() != null && !request.version().equals(policy.getVersion())) {
			throw new BusinessException(STALE_EDIT_MESSAGE, HttpStatus.CONFLICT);
		}

		Map<ViolationType, ViolationEnforcement> rules = completeRules(request.rules());

		policy.setStrikeLimit(request.strikeLimit());
		policy.setUnidentifiedSoundGrace(request.unidentifiedSoundGrace());
		policy.setVoiceMinDbAboveFloor(request.voiceMinDbAboveFloor());
		policy.setBackgroundNoiseMinDbAboveFloor(request.backgroundNoiseMinDbAboveFloor());
		policy.setUnidentifiedSoundMinDbAboveFloor(request.unidentifiedSoundMinDbAboveFloor());
		// Changed in place: on a loaded policy this is Hibernate's own collection,
		// and swapping in a new map would orphan the rows it tracks.
		policy.getRules().clear();
		policy.getRules().putAll(rules);

		try {
			return proctoringPolicyRepository.saveAndFlush(policy);
		} catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException ex) {
			// Another admin saved this row first, or created this scope's first
			// policy a moment earlier: the version and the unique scope key each let
			// exactly one of the two through.
			throw new BusinessException(STALE_EDIT_MESSAGE, HttpStatus.CONFLICT);
		}
	}

	/**
	 * The submitted rules, checked and completed against every configurable type.
	 *
	 * <p>Stored complete, so a saved policy keeps meaning what the admin saw when
	 * they saved it even if a type's built-in enforcement changes in a later
	 * release.</p>
	 */
	private static Map<ViolationType, ViolationEnforcement> completeRules(
			Map<ViolationType, ViolationEnforcement> submitted) {
		Map<ViolationType, ViolationEnforcement> rules = new EnumMap<>(ViolationType.class);
		submitted.forEach((type, enforcement) -> {
			if (!type.isAdminConfigurable()) {
				throw new BusinessException(type + " cannot be configured by a proctoring policy",
						HttpStatus.BAD_REQUEST);
			}
			if (enforcement == null) {
				throw new BusinessException("Choose how " + type + " is enforced", HttpStatus.BAD_REQUEST);
			}
			rules.put(type, enforcement);
		});
		for (ViolationType type : ViolationType.values()) {
			if (type.isAdminConfigurable()) {
				rules.putIfAbsent(type, type.defaultEnforcement());
			}
		}
		return rules;
	}

	private static EffectiveProctoringPolicy toEffective(ProctoringPolicy policy) {
		return new EffectiveProctoringPolicy(
				policy.getStrikeLimit(),
				policy.getUnidentifiedSoundGrace(),
				policy.getVoiceMinDbAboveFloor(),
				policy.getBackgroundNoiseMinDbAboveFloor(),
				policy.getUnidentifiedSoundMinDbAboveFloor(),
				policy.getRules());
	}

	/**
	 * @param saved the scope's own row, or null when the rules shown are not read
	 *              from one — the built-in rules, or an exam's inherited default
	 */
	private static ProctoringPolicyResponse toResponse(String scope, Exam exam, boolean inheritsDefault,
			EffectiveProctoringPolicy effective, ProctoringPolicy saved) {
		return new ProctoringPolicyResponse(
				scope,
				exam == null ? null : exam.getId(),
				exam == null ? null : exam.getExamCode(),
				exam == null ? null : exam.getExamName(),
				inheritsDefault,
				effective.strikeLimit(),
				effective.unidentifiedSoundGrace(),
				effective.voiceMinDbAboveFloor(),
				effective.backgroundNoiseMinDbAboveFloor(),
				effective.unidentifiedSoundMinDbAboveFloor(),
				effective.configurableRules(),
				saved == null ? null : saved.getVersion(),
				saved == null ? null : lastEditedBy(saved),
				saved == null ? null : lastEditedAt(saved));
	}

	private static String lastEditedBy(ProctoringPolicy policy) {
		return policy.getUpdatedBy() != null ? policy.getUpdatedBy() : policy.getCreatedBy();
	}

	/** Spring Data's default auditing clock stamps local time in the JVM's zone. */
	private static Instant lastEditedAt(ProctoringPolicy policy) {
		LocalDateTime editedAt = policy.getUpdatedDate() != null ? policy.getUpdatedDate() : policy.getCreatedDate();
		return editedAt == null ? null : editedAt.atZone(ZoneId.systemDefault()).toInstant();
	}

	/** One line an audit reader can act on: the numbers, and every type that is not a plain strike. */
	private static String describe(ProctoringPolicy policy) {
		List<ViolationType> off = new ArrayList<>();
		List<ViolationType> recordOnly = new ArrayList<>();
		new TreeMap<>(policy.getRules()).forEach((type, enforcement) -> {
			if (enforcement == ViolationEnforcement.DISABLED) {
				off.add(type);
			} else if (enforcement == ViolationEnforcement.RECORD_ONLY) {
				recordOnly.add(type);
			}
		});

		return ("strike limit %d; sound grace %d; voice >= %d dB, noise >= %d dB, other sounds >= %d dB "
				+ "above the room; off %s; record only %s")
				.formatted(
						policy.getStrikeLimit(),
						policy.getUnidentifiedSoundGrace(),
						policy.getVoiceMinDbAboveFloor(),
						policy.getBackgroundNoiseMinDbAboveFloor(),
						policy.getUnidentifiedSoundMinDbAboveFloor(),
						off,
						recordOnly);
	}

	private void audit(String targetId, String description) {
		auditService.record(AuditEvent.builder()
				.eventType(AuditEventType.ADMIN_ACTION)
				.outcome(AuditOutcome.SUCCESS)
				.targetType("PROCTORING_POLICY")
				.targetId(targetId)
				.description(description)
				.build());
	}
}
