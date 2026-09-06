package com.ems.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ems.audit.AuditEvent;
import com.ems.audit.AuditEventType;
import com.ems.audit.AuditOutcome;
import com.ems.dto.request.ExamResultSubmissionRequest;
import com.ems.dto.request.QuestionAnswerSubmissionRequest;
import com.ems.dto.response.ExamResultResponse;
import com.ems.entity.Certification;
import com.ems.entity.CertificationApplication;
import com.ems.entity.ExamAttempt;
import com.ems.entity.ExamSession;
import com.ems.entity.Question;
import com.ems.entity.User;
import com.ems.enums.CertificationApplicationStatus;
import com.ems.enums.CertificationStatus;
import com.ems.enums.ExamStatus;
import com.ems.enums.ResultStatus;
import com.ems.event.ExamPassedEvent;
import com.ems.exception.BusinessException;
import com.ems.exception.ResourceNotFoundException;
import com.ems.repository.CertificationApplicationRepository;
import com.ems.repository.CertificationRepository;
import com.ems.repository.ExamAttemptRepository;
import com.ems.repository.ExamSessionRepository;
import com.ems.repository.QuestionRepository;
import com.ems.service.AuditService;
import com.ems.service.ResultEvaluationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
@Transactional
public class ResultEvaluationServiceImpl implements ResultEvaluationService {

	private static final TypeReference<List<Long>> LONG_LIST_TYPE = new TypeReference<>() {
	};
	private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
	};

	private final ExamSessionRepository examSessionRepository;
	private final ExamAttemptRepository examAttemptRepository;
	private final QuestionRepository questionRepository;
	private final CertificationApplicationRepository certificationApplicationRepository;
	private final CertificationRepository certificationRepository;
	private final ObjectMapper objectMapper;
	private final ApplicationEventPublisher eventPublisher;
	private final AuditService auditService;

	@Override
	@CacheEvict(cacheNames = { "reports", "dashboard" }, allEntries = true)
	public ExamResultResponse evaluateResult(String email, Long sessionId, ExamResultSubmissionRequest request) {
		ExamSession session = findOwnedSession(email, sessionId);
		validateSessionEvaluable(session);

		if (examAttemptRepository.existsByExamSession(session)) {
			throw new BusinessException("Result already evaluated for this exam session", HttpStatus.BAD_REQUEST);
		}

		List<Long> selectedQuestionIds = readLongList(session.getSelectedQuestionIdsJson());
		if (selectedQuestionIds.isEmpty()) {
			throw new BusinessException("No selected questions found for session", HttpStatus.BAD_REQUEST);
		}

		Map<Long, Question> questionById = questionRepository.findAllById(selectedQuestionIds).stream()
				.collect(Collectors.toMap(Question::getId, q -> q));

		if (questionById.size() < selectedQuestionIds.size()) {
			throw new BusinessException("Some session questions are unavailable for evaluation", HttpStatus.BAD_REQUEST);
		}

		Map<Long, List<String>> submittedAnswers = new LinkedHashMap<>();
		for (QuestionAnswerSubmissionRequest answer : request.answers()) {
			if (!selectedQuestionIds.contains(answer.questionId())) {
				throw new BusinessException("Submitted question does not belong to this exam session", HttpStatus.BAD_REQUEST);
			}
			submittedAnswers.putIfAbsent(answer.questionId(), answer.selectedOptions());
		}

		int totalQuestions = selectedQuestionIds.size();
		int attemptedQuestions = 0;
		int correctAnswers = 0;
		BigDecimal obtainedMarks = BigDecimal.ZERO;

		BigDecimal selectedTotalMarks = selectedQuestionIds.stream()
				.map(questionById::get)
				.map(Question::getMarks)
				.reduce(BigDecimal.ZERO, BigDecimal::add);

		for (Map.Entry<Long, List<String>> submitted : submittedAnswers.entrySet()) {
			List<String> selectedOptions = normalizeOptions(submitted.getValue());
			if (selectedOptions.isEmpty()) {
				continue;
			}

			attemptedQuestions++;
			Question question = questionById.get(submitted.getKey());
			List<String> correctOptions = normalizeOptions(readStringList(question.getCorrectOptionsJson()));

			if (asNormalizedSet(selectedOptions).equals(asNormalizedSet(correctOptions))) {
				correctAnswers++;
				obtainedMarks = obtainedMarks.add(question.getMarks());
			}
		}

		int wrongAnswers = attemptedQuestions - correctAnswers;
		BigDecimal totalMarks = selectedTotalMarks.compareTo(BigDecimal.ZERO) > 0
				? selectedTotalMarks
				: session.getExam().getTotalMarks();

		BigDecimal percentage = BigDecimal.ZERO;
		if (totalMarks.compareTo(BigDecimal.ZERO) > 0) {
			percentage = obtainedMarks.multiply(BigDecimal.valueOf(100))
					.divide(totalMarks, 2, RoundingMode.HALF_UP);
		}

		ResultStatus resultStatus = percentage.compareTo(session.getExam().getPassingPercentage()) >= 0
				? ResultStatus.PASS
				: ResultStatus.FAIL;

		Instant submittedAt = Instant.now();
		ExamAttempt savedAttempt = examAttemptRepository.save(ExamAttempt.builder()
				.examSession(session)
				.totalQuestions(totalQuestions)
				.attemptedQuestions(attemptedQuestions)
				.correctAnswers(correctAnswers)
				.wrongAnswers(wrongAnswers)
				.obtainedMarks(obtainedMarks)
				.percentage(percentage)
				.resultStatus(resultStatus)
				.submittedAt(submittedAt)
				.build());

		session.setSessionEndTime(submittedAt);
		session.setSessionStatus(resultStatus == ResultStatus.PASS ? ExamStatus.PASSED : ExamStatus.FAILED);
		examSessionRepository.save(session);

		updateApplicationStatus(session, resultStatus);
		issueCertificationIfPassed(session, resultStatus);

		log.info("Result evaluated: sessionId={}, attempted={}, correct={}, wrong={}, percentage={}, status={}",
				sessionId, attemptedQuestions, correctAnswers, wrongAnswers, percentage, resultStatus);
		auditService.record(AuditEvent.builder()
				.eventType(AuditEventType.EXAM_END)
				.outcome(AuditOutcome.SUCCESS)
				.actorEmail(email)
				.actorUserId(session.getUser().getUserId())
				.targetUserId(session.getUser().getUserId())
				.targetType("EXAM_SESSION")
				.targetId(String.valueOf(sessionId))
				.description("Exam submitted, result " + resultStatus + " (" + percentage + "%)")
				.build());

		return toResultResponse(savedAttempt, totalMarks);
	}

	private void issueCertificationIfPassed(ExamSession session, ResultStatus resultStatus) {
		if (resultStatus != ResultStatus.PASS) {
			return;
		}

		// Clearing the exam is what earns the certificate, so the PDF for this
		// level is rendered once the result commits rather than only when the
		// candidate asks for it from the result screen.
		eventPublisher.publishEvent(new ExamPassedEvent(session.getUser().getEmail(), session.getId()));

		Long userId = session.getUser().getId();
		var level = session.getExam().getCertificationLevel();
		boolean alreadyActive = certificationRepository
				.findFirstByUserIdAndCertificationLevelAndCertificationStatusOrderByExpiryDateDesc(
						userId,
						level,
						CertificationStatus.ACTIVE)
				.isPresent();
		if (alreadyActive) {
			return;
		}

		certificationRepository.save(Certification.builder()
				.user(session.getUser())
				.certificationLevel(level)
				.certificationStatus(CertificationStatus.ACTIVE)
				.issueDate(LocalDate.now())
				.expiryDate(LocalDate.now().plusYears(1))
				.build());
	}

	@Override
	@Transactional(readOnly = true)
	public ExamResultResponse getResult(String email, Long sessionId) {
		return toResultResponse(findOwnedAttempt(email, sessionId));
	}

	@Override
	@Transactional(readOnly = true)
	public List<ExamResultResponse> getMyResults(String email) {
		return examAttemptRepository.findByExamSessionUserEmailIgnoreCaseOrderBySubmittedAtDesc(email).stream()
				.map(this::toResultResponse)
				.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public ExamResultResponse getResultForAdmin(Long sessionId) {
		ExamSession session = examSessionRepository.findById(sessionId)
				.orElseThrow(() -> new ResourceNotFoundException("Exam session not found"));
		ExamAttempt attempt = examAttemptRepository.findByExamSession(session)
				.orElseThrow(() -> new ResourceNotFoundException("Result not found for exam session"));
		return toResultResponse(attempt);
	}

	private void updateApplicationStatus(ExamSession session, ResultStatus resultStatus) {
		User user = session.getUser();
		CertificationApplication application = certificationApplicationRepository
				.findTopByUserAndExamOrderByAppliedOnDescIdDesc(user, session.getExam())
				.orElse(null);

		if (application == null) {
			return;
		}

		application.setApplicationStatus(resultStatus == ResultStatus.PASS
				? CertificationApplicationStatus.PASSED
				: CertificationApplicationStatus.FAILED);
		certificationApplicationRepository.save(application);
	}

	private ExamSession findOwnedSession(String email, Long sessionId) {
		return examSessionRepository.findByIdAndUserEmailIgnoreCase(sessionId, email)
				.orElseThrow(() -> new ResourceNotFoundException("Exam session not found"));
	}

	private ExamAttempt findOwnedAttempt(String email, Long sessionId) {
		return examAttemptRepository.findByExamSessionIdAndExamSessionUserEmailIgnoreCase(sessionId, email)
				.orElseThrow(() -> new ResourceNotFoundException("Result not found for exam session"));
	}

	private void validateSessionEvaluable(ExamSession session) {
		if (session.getSessionStatus() == ExamStatus.INVALIDATED) {
			throw new BusinessException("Invalidated exam session cannot be evaluated", HttpStatus.BAD_REQUEST);
		}
		if (session.getSessionStatus() != ExamStatus.IN_PROGRESS) {
			throw new BusinessException("Only active exam sessions can be evaluated", HttpStatus.BAD_REQUEST);
		}
	}

	private List<Long> readLongList(String rawJson) {
		try {
			return objectMapper.readValue(rawJson, LONG_LIST_TYPE);
		} catch (JsonProcessingException ex) {
			throw new BusinessException("Failed to read selected question set", HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	private List<String> readStringList(String rawJson) {
		try {
			return objectMapper.readValue(rawJson, STRING_LIST_TYPE);
		} catch (JsonProcessingException ex) {
			throw new BusinessException("Failed to read stored correct answers", HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	private List<String> normalizeOptions(List<String> options) {
		if (options == null) {
			return List.of();
		}

		return options.stream()
				.filter(option -> option != null && !option.isBlank())
				.map(option -> option.trim().toLowerCase(Locale.ROOT))
				.toList();
	}

	private Set<String> asNormalizedSet(List<String> values) {
		return values.stream().collect(Collectors.toSet());
	}

	private ExamResultResponse toResultResponse(ExamAttempt attempt) {
		return toResultResponse(attempt, resolveTotalMarks(attempt.getExamSession()));
	}

	private ExamResultResponse toResultResponse(ExamAttempt attempt, BigDecimal totalMarks) {
		Long applicationId = certificationApplicationRepository
				.findTopByUserAndExamOrderByAppliedOnDescIdDesc(
						attempt.getExamSession().getUser(),
						attempt.getExamSession().getExam())
				.map(app -> app.getId())
				.orElse(null);

		return new ExamResultResponse(
				attempt.getId(),
				attempt.getExamSession().getId(),
				applicationId,
				attempt.getExamSession().getExam().getExamCode(),
				attempt.getTotalQuestions(),
				attempt.getAttemptedQuestions(),
				attempt.getCorrectAnswers(),
				attempt.getWrongAnswers(),
				totalMarks,
				attempt.getObtainedMarks(),
				attempt.getPercentage(),
				attempt.getResultStatus(),
				attempt.getSubmittedAt());
	}

	private BigDecimal resolveTotalMarks(ExamSession session) {
		List<Long> selectedQuestionIds = readLongList(session.getSelectedQuestionIdsJson());
		if (selectedQuestionIds.isEmpty()) {
			return session.getExam().getTotalMarks();
		}

		Map<Long, Question> questionById = questionRepository.findAllById(selectedQuestionIds).stream()
				.collect(Collectors.toMap(Question::getId, q -> q));

		List<Question> orderedQuestions = new ArrayList<>();
		for (Long questionId : selectedQuestionIds) {
			Question question = questionById.get(questionId);
			if (question != null) {
				orderedQuestions.add(question);
			}
		}

		BigDecimal selectedTotalMarks = orderedQuestions.stream()
				.map(Question::getMarks)
				.reduce(BigDecimal.ZERO, BigDecimal::add);

		if (selectedTotalMarks.compareTo(BigDecimal.ZERO) > 0) {
			return selectedTotalMarks;
		}
		return session.getExam().getTotalMarks();
	}
}
