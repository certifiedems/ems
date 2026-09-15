package com.ems.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ems.dto.response.AdminExamBookingDetailResponse;
import com.ems.dto.response.AdminExamBookingDetailResponse.AnswerSource;
import com.ems.dto.response.AdminExamBookingDetailResponse.AnswerState;
import com.ems.dto.response.AdminExamBookingResponse;
import com.ems.entity.CertificationApplication;
import com.ems.entity.Exam;
import com.ems.entity.ExamAttempt;
import com.ems.entity.ExamSession;
import com.ems.entity.PolicyAcknowledgment;
import com.ems.entity.Question;
import com.ems.entity.User;
import com.ems.enums.CertificationApplicationStatus;
import com.ems.enums.ExamStatus;
import com.ems.enums.ExamTrackerStage;
import com.ems.enums.PaymentStatus;
import com.ems.exception.ResourceNotFoundException;
import com.ems.repository.CertificateRepository;
import com.ems.repository.CertificationApplicationRepository;
import com.ems.repository.ExamAttemptRepository;
import com.ems.repository.ExamSessionRepository;
import com.ems.repository.PaymentRepository;
import com.ems.repository.PolicyAcknowledgmentRepository;
import com.ems.repository.QuestionRepository;
import com.ems.repository.ViolationRepository;
import com.ems.service.AdminExamTrackerService;
import com.ems.util.AnswerMarking;
import com.ems.util.ExamAttemptAllowance;
import com.ems.util.ExamStartWindow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
@Transactional(readOnly = true)
public class AdminExamTrackerServiceImpl implements AdminExamTrackerService {

	private static final TypeReference<List<Long>> LONG_LIST_TYPE = new TypeReference<>() {
	};
	private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
	};
	private static final TypeReference<Map<String, List<String>>> ANSWERS_TYPE = new TypeReference<>() {
	};

	/** Statuses under which an application is still owed its sitting. */
	private static final Set<CertificationApplicationStatus> OPEN_STATUSES = EnumSet.of(
			CertificationApplicationStatus.APPLIED,
			CertificationApplicationStatus.ELIGIBLE,
			CertificationApplicationStatus.IN_PROGRESS);

	/**
	 * Which of an application's sessions is its attempt: the latest started, the
	 * one the start and dashboard code already treat as the application's.
	 */
	private static final Comparator<ExamSession> SESSION_RECENCY = Comparator
			.comparing(ExamSession::getSessionStartTime, Comparator.nullsFirst(Comparator.naturalOrder()))
			.thenComparing(ExamSession::getId, Comparator.nullsFirst(Comparator.naturalOrder()));

	/** Soonest slot first; applications with no slot yet after every booked one, newest first. */
	private static final Comparator<AdminExamBookingResponse> BOOKING_ORDER = Comparator
			.comparing((AdminExamBookingResponse booking) -> booking.slot() == null ? null : booking.slot().start(),
					Comparator.nullsLast(Comparator.naturalOrder()))
			.thenComparing(AdminExamBookingResponse::applicationId, Comparator.reverseOrder());

	private final CertificationApplicationRepository certificationApplicationRepository;
	private final ExamSessionRepository examSessionRepository;
	private final ExamAttemptRepository examAttemptRepository;
	private final QuestionRepository questionRepository;
	private final PaymentRepository paymentRepository;
	private final ViolationRepository violationRepository;
	private final CertificateRepository certificateRepository;
	private final PolicyAcknowledgmentRepository policyAcknowledgmentRepository;
	private final ObjectMapper objectMapper;

	@Override
	public List<AdminExamBookingResponse> listBookings() {
		List<CertificationApplication> applications =
				certificationApplicationRepository.findTrackedWithCandidateAndExam();
		if (applications.isEmpty()) {
			return List.of();
		}

		// Sessions and results for every row in two queries, rather than two per row.
		Map<Long, ExamSession> sessionByApplication = examSessionRepository
				.findByCertificationApplicationIn(applications).stream()
				.collect(Collectors.toMap(
						session -> session.getCertificationApplication().getId(),
						Function.identity(),
						BinaryOperator.maxBy(SESSION_RECENCY)));
		Map<Long, ExamAttempt> attemptBySession = sessionByApplication.isEmpty()
				? Map.of()
				: examAttemptRepository.findByExamSessionIn(sessionByApplication.values()).stream()
						.collect(Collectors.toMap(
								attempt -> attempt.getExamSession().getId(),
								Function.identity(),
								(first, second) -> first));

		Instant now = Instant.now();
		return applications.stream()
				.map(application -> {
					ExamSession session = sessionByApplication.get(application.getId());
					ExamAttempt attempt = session == null ? null : attemptBySession.get(session.getId());
					return toBooking(application, session, attempt, now);
				})
				.sorted(BOOKING_ORDER)
				.toList();
	}

	@Override
	public AdminExamBookingDetailResponse getBooking(Long applicationId) {
		CertificationApplication application = certificationApplicationRepository
				.findByIdWithAllRelationships(applicationId)
				.orElseThrow(() -> new ResourceNotFoundException("Application not found"));
		ExamSession session = examSessionRepository
				.findTopByCertificationApplicationOrderBySessionStartTimeDescIdDesc(application)
				.orElse(null);
		ExamAttempt attempt = session == null
				? null
				: examAttemptRepository.findByExamSession(session).orElse(null);

		/*
		 * The submission is the record the score was worked out from, so it wins.
		 * The autosave is the only record of a running attempt, of one ended by
		 * proctoring, and of one scored before submissions were kept — close to
		 * what was submitted, but not guaranteed to be it, which is why the
		 * source travels with the answers.
		 */
		Map<Long, List<String>> submitted = attempt == null ? null : readAnswers(attempt.getSubmittedAnswersJson());
		Map<Long, List<String>> autosaved = session == null ? null : readAnswers(session.getAnswersDraftJson());
		AnswerSource answerSource;
		Map<Long, List<String>> answers;
		if (submitted != null) {
			answerSource = AnswerSource.SUBMISSION;
			answers = submitted;
		} else if (autosaved != null) {
			answerSource = AnswerSource.AUTOSAVE;
			answers = autosaved;
		} else {
			answerSource = AnswerSource.NONE;
			answers = Map.of();
		}
		// With nothing saved and no result, nothing has been answered yet. With a
		// result but no answers on record, nothing can be said about any question.
		boolean answersRecorded = answerSource != AnswerSource.NONE || attempt == null;

		return new AdminExamBookingDetailResponse(
				toBooking(application, session, attempt, Instant.now()),
				toPaymentDetails(application),
				session == null ? null : toSessionDetails(application.getUser(), session),
				answerSource,
				session == null ? List.of() : toQuestionLines(session, answers, answersRecorded),
				session == null ? List.of() : toViolationLines(session),
				attempt == null ? null : toCertificateDetails(attempt));
	}

	/**
	 * Where an application stands. What the attempt itself recorded wins over the
	 * application status: a session says what happened, where the application
	 * only says what was decided about it afterwards.
	 */
	static ExamTrackerStage stageOf(CertificationApplication application, ExamSession session,
			ExamAttempt attempt, Instant now) {
		if (attempt != null) {
			return ExamTrackerStage.COMPLETED;
		}
		if (session != null) {
			ExamStatus sessionStatus = session.getSessionStatus();
			if (sessionStatus == ExamStatus.IN_PROGRESS) {
				return ExamTrackerStage.LIVE;
			}
			if (sessionStatus == ExamStatus.INVALIDATED) {
				return ExamTrackerStage.TERMINATED;
			}
			if (sessionStatus != null && sessionStatus != ExamStatus.SCHEDULED) {
				return ExamTrackerStage.COMPLETED;
			}
		}

		CertificationApplicationStatus status = application.getApplicationStatus();
		if (status == CertificationApplicationStatus.TERMINATED) {
			return ExamTrackerStage.TERMINATED;
		}
		if (!OPEN_STATUSES.contains(status) || application.getPaymentStatus() != PaymentStatus.SUCCESS) {
			return ExamTrackerStage.CLOSED;
		}
		Instant slot = application.getScheduledExamTime();
		if (slot == null) {
			return ExamTrackerStage.AWAITING_SLOT;
		}
		return ExamStartWindow.hasClosed(slot, now) ? ExamTrackerStage.MISSED : ExamTrackerStage.UPCOMING;
	}

	/**
	 * The verdict on one answer, by the rule the score was worked out with.
	 *
	 * @param answersRecorded false when the attempt was scored but none of its
	 *                        answers survive
	 */
	static AnswerState answerState(Question question, List<String> selectedOptions, List<String> correctOptions,
			boolean answersRecorded) {
		if (!answersRecorded) {
			return AnswerState.NOT_JUDGED;
		}
		if (!AnswerMarking.isAnswered(selectedOptions)) {
			return AnswerState.UNANSWERED;
		}
		if (question == null) {
			return AnswerState.NOT_JUDGED;
		}
		return AnswerMarking.isCorrect(selectedOptions, correctOptions) ? AnswerState.CORRECT : AnswerState.WRONG;
	}

	private AdminExamBookingResponse toBooking(CertificationApplication application, ExamSession session,
			ExamAttempt attempt, Instant now) {
		User user = application.getUser();
		Exam exam = application.getExam();
		return new AdminExamBookingResponse(
				application.getId(),
				stageOf(application, session, attempt, now),
				application.getApplicationStatus(),
				application.getPaymentStatus(),
				application.getAppliedOn(),
				ExamAttemptAllowance.attemptNumber(application),
				ExamAttemptAllowance.attemptsAllowed(application),
				new AdminExamBookingResponse.Candidate(
						user.getUserId(), fullName(user), user.getEmail(), user.getMobileNumber()),
				exam == null ? null : new AdminExamBookingResponse.ExamDetails(
						exam.getId(),
						exam.getExamCode(),
						exam.getExamName(),
						exam.getCertificationLevel(),
						exam.getDurationMinutes(),
						exam.getTotalQuestions(),
						exam.getPassingPercentage()),
				toSlot(application.getScheduledExamTime(), exam, now),
				session == null ? null : toSession(session, attempt),
				attempt == null ? null : toResult(attempt));
	}

	private static AdminExamBookingResponse.Slot toSlot(Instant start, Exam exam, Instant now) {
		if (start == null) {
			return null;
		}
		Instant end = exam == null || exam.getDurationMinutes() == null
				? null
				: start.plus(Duration.ofMinutes(exam.getDurationMinutes()));
		return new AdminExamBookingResponse.Slot(
				start,
				end,
				ExamStartWindow.opensAt(start),
				ExamStartWindow.closesAt(start),
				ExamStartWindow.isOpen(start, now));
	}

	private AdminExamBookingResponse.Session toSession(ExamSession session, ExamAttempt attempt) {
		int answered = attempt != null
				? attempt.getAttemptedQuestions()
				: countAnswered(readAnswers(session.getAnswersDraftJson()));
		return new AdminExamBookingResponse.Session(
				session.getId(),
				session.getSessionStatus(),
				session.getSessionStartTime(),
				session.getSessionEndTime(),
				readIds(session.getSelectedQuestionIdsJson()).size(),
				answered,
				readIds(session.getMarkedForReviewJson()).size(),
				session.getLastQuestionNumber(),
				session.getProgressSavedAt(),
				session.getViolationCount());
	}

	private static AdminExamBookingResponse.Result toResult(ExamAttempt attempt) {
		return new AdminExamBookingResponse.Result(
				attempt.getTotalQuestions(),
				attempt.getAttemptedQuestions(),
				attempt.getCorrectAnswers(),
				attempt.getWrongAnswers(),
				attempt.getObtainedMarks(),
				attempt.getPercentage(),
				attempt.getResultStatus(),
				attempt.getSubmittedAt());
	}

	/** The paper in the order it was set, each question with its answer and the verdict on it. */
	private List<AdminExamBookingDetailResponse.QuestionLine> toQuestionLines(ExamSession session,
			Map<Long, List<String>> answers, boolean answersRecorded) {
		List<Long> paper = readIds(session.getSelectedQuestionIdsJson());
		if (paper.isEmpty()) {
			return List.of();
		}
		Set<Long> markedForReview = new HashSet<>(readIds(session.getMarkedForReviewJson()));
		Map<Long, Question> questionById = questionRepository.findAllById(paper).stream()
				.collect(Collectors.toMap(Question::getId, Function.identity()));

		List<AdminExamBookingDetailResponse.QuestionLine> lines = new ArrayList<>(paper.size());
		for (int index = 0; index < paper.size(); index++) {
			Long questionId = paper.get(index);
			Question question = questionById.get(questionId);
			List<String> selected = answers.getOrDefault(questionId, List.of());
			List<String> correct = question == null ? List.of() : readStrings(question.getCorrectOptionsJson());
			lines.add(new AdminExamBookingDetailResponse.QuestionLine(
					index + 1,
					questionId,
					question == null ? null : question.getQuestionCode(),
					question == null ? null : question.getQuestionCategory(),
					question == null ? null : question.getQuestionType(),
					question == null ? null : question.getSeverity(),
					question == null ? null : question.getMarks(),
					question == null ? null : question.getQuestionText(),
					question == null ? List.of() : readStrings(question.getOptionsJson()),
					correct,
					selected,
					answerState(question, selected, correct, answersRecorded),
					markedForReview.contains(questionId)));
		}
		return lines;
	}

	/** The payment behind the sitting; a retake is covered by its paid application's. */
	private AdminExamBookingDetailResponse.PaymentDetails toPaymentDetails(CertificationApplication application) {
		Long paidApplicationId = ExamAttemptAllowance.paidApplication(application).getId();
		return paymentRepository.findTopByCertificationApplicationIdOrderByCreatedDateDesc(paidApplicationId)
				.map(payment -> new AdminExamBookingDetailResponse.PaymentDetails(
						payment.getTransactionId(),
						payment.getPaymentStatus(),
						payment.getAmount(),
						payment.getCurrency(),
						payment.getPaymentMethod(),
						payment.getPaymentMethodDetail(),
						payment.getGatewayMode(),
						payment.getPaymentDate()))
				.orElse(null);
	}

	private AdminExamBookingDetailResponse.SessionDetails toSessionDetails(User user, ExamSession session) {
		PolicyAcknowledgment acknowledgment = policyAcknowledgmentRepository
				.findLatestByUserAndExamSession(user, session.getId())
				.orElse(null);
		return new AdminExamBookingDetailResponse.SessionDetails(
				session.getIpAddress(),
				session.getBrowserFingerprint(),
				acknowledgment == null ? null : acknowledgment.getAcknowledgedAt(),
				acknowledgment == null ? null : acknowledgment.getPolicyVersion());
	}

	private List<AdminExamBookingDetailResponse.ViolationLine> toViolationLines(ExamSession session) {
		return violationRepository.findByExamSessionOrderByDetectedAtDesc(session).stream()
				.map(violation -> new AdminExamBookingDetailResponse.ViolationLine(
						violation.getId(),
						violation.getViolationType(),
						violation.getViolationLevel(),
						violation.getDescription(),
						violation.getDetectedAt(),
						violation.getActionTaken()))
				.toList();
	}

	private AdminExamBookingDetailResponse.CertificateDetails toCertificateDetails(ExamAttempt attempt) {
		return certificateRepository.findByExamAttempt(attempt)
				.map(certificate -> new AdminExamBookingDetailResponse.CertificateDetails(
						certificate.getCertificateNumber(),
						certificate.getIssueDate(),
						certificate.getExpiryDate()))
				.orElse(null);
	}

	private List<Long> readIds(String json) {
		List<Long> ids = read(json, LONG_LIST_TYPE);
		return ids == null ? List.of() : ids;
	}

	private List<String> readStrings(String json) {
		List<String> values = read(json, STRING_LIST_TYPE);
		return values == null ? List.of() : values;
	}

	/** A stored answer map keyed by question id, or null when there is none. Keys that are not ids are skipped. */
	private Map<Long, List<String>> readAnswers(String json) {
		Map<String, List<String>> stored = read(json, ANSWERS_TYPE);
		if (stored == null) {
			return null;
		}
		Map<Long, List<String>> answers = new LinkedHashMap<>();
		stored.forEach((key, options) -> {
			try {
				answers.put(Long.valueOf(key.trim()), options == null ? List.of() : options);
			} catch (NumberFormatException ex) {
				log.warn("Skipping stored answer under non-numeric question id '{}'", key);
			}
		});
		return answers;
	}

	/**
	 * A stored JSON column, or null when it is empty or unreadable. This is a
	 * read-only view: a damaged column is logged and left out rather than
	 * failing the whole screen over it.
	 */
	private <T> T read(String json, TypeReference<T> type) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			return objectMapper.readValue(json, type);
		} catch (JsonProcessingException ex) {
			log.warn("Unreadable JSON column in the exam tracker: {}", ex.getOriginalMessage());
			return null;
		}
	}

	private static int countAnswered(Map<Long, List<String>> answers) {
		return answers == null
				? 0
				: (int) answers.values().stream().filter(AnswerMarking::isAnswered).count();
	}

	private static String fullName(User user) {
		String first = user.getFirstName() == null ? "" : user.getFirstName();
		String last = user.getLastName() == null ? "" : user.getLastName();
		return (first + " " + last).trim();
	}
}
