package com.ems.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ems.audit.AuditEvent;
import com.ems.audit.AuditEventType;
import com.ems.audit.AuditOutcome;
import com.ems.dto.request.ExamProgressSaveRequest;
import com.ems.dto.request.ExamStartRequest;
import com.ems.dto.request.ExamWorkflowApplicationRequest;
import com.ems.dto.request.PaymentCompletionRequest;
import com.ems.dto.request.PaymentInitiationRequest;
import com.ems.dto.request.PaymentVerificationRequest;
import com.ems.dto.request.QuestionAnswerSubmissionRequest;
import com.ems.dto.request.WorkflowExamScheduleRequest;
import com.ems.dto.response.CertificationEligibilityResponse;
import com.ems.dto.response.ExamProgressResponse;
import com.ems.dto.response.ExamQuestionPayloadResponse;
import com.ems.dto.response.ExamSessionQuestionResponse;
import com.ems.dto.response.ExamStartResponse;
import com.ems.dto.response.ExamWorkflowApplicationResponse;
import com.ems.dto.response.PaymentResponse;
import com.ems.dto.response.WorkflowExamOptionResponse;
import com.ems.entity.Certification;
import com.ems.entity.CertificationApplication;
import com.ems.entity.Exam;
import com.ems.entity.ExamSession;
import com.ems.entity.Question;
import com.ems.entity.User;
import com.ems.enums.CertificationApplicationStatus;
import com.ems.enums.CertificationLevel;
import com.ems.enums.CertificationStatus;
import com.ems.enums.ExamStatus;
import com.ems.enums.PaymentStatus;
import com.ems.enums.QuestionSeverity;
import com.ems.enums.QuestionType;
import com.ems.exception.BusinessException;
import com.ems.exception.ResourceNotFoundException;
import com.ems.repository.CertificationApplicationRepository;
import com.ems.repository.CertificationRepository;
import com.ems.repository.ExamRepository;
import com.ems.repository.ExamSessionRepository;
import com.ems.repository.QuestionRepository;
import com.ems.repository.UserRepository;
import com.ems.service.AuditService;
import com.ems.service.CertificationJourneyService;
import com.ems.service.ExamWorkflowService;
import com.ems.service.PaymentService;
import com.ems.util.ExamQuestionBlueprint;
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
@Transactional
public class ExamWorkflowServiceImpl implements ExamWorkflowService {

	/** Fallback when an exam carries no duration; matches the client's old default. */
	private static final int DEFAULT_EXAM_DURATION_MINUTES = 60;
	private static final String VIOLATION_RESTART_MESSAGE = "Exam was terminated after 3 violations. Re-apply and complete payment to restart from question 1.";

	private static final TypeReference<List<Long>> LONG_LIST_TYPE = new TypeReference<>() {
	};

	/** Answer drafts are keyed by question id as a string, JSON-object style. */
	private static final TypeReference<Map<String, List<String>>> ANSWER_MAP_TYPE = new TypeReference<>() {
	};

	/** How a booking-window bound is written into a refusal. See {@link #formatWindowBound}. */
	private static final DateTimeFormatter WINDOW_BOUND_FORMAT = DateTimeFormatter
			.ofPattern("d MMM uuuu, HH:mm 'UTC'")
			.withZone(ZoneOffset.UTC);

	private final CertificationJourneyService certificationJourneyService;
	private final CertificationApplicationRepository certificationApplicationRepository;
	private final CertificationRepository certificationRepository;
	private final UserRepository userRepository;
	private final ExamRepository examRepository;
	private final QuestionRepository questionRepository;
	private final ExamSessionRepository examSessionRepository;
	private final PaymentService paymentService;
	private final ObjectMapper objectMapper;
	private final AuditService auditService;

	@Override
	@Transactional(readOnly = true)
	public WorkflowExamOptionResponse getWorkflowOptions(String email, CertificationLevel certificationLevel) {
		User user = findUser(email);
		CertificationEligibilityResponse eligibility = certificationJourneyService.getEligibility(email, certificationLevel);

		List<Certification> activeCertifications = certificationRepository
				.findByUserAndCertificationStatusOrderByIssueDateDesc(user, CertificationStatus.ACTIVE);

		List<WorkflowExamOptionResponse.CertificationInfo> certInfos = activeCertifications.stream()
				.map(cert -> new WorkflowExamOptionResponse.CertificationInfo(
						cert.getCertificationLevel(),
						cert.getCertificationStatus().toString(),
						cert.getIssueDate(),
						cert.getExpiryDate()))
				.toList();

		WorkflowExamOptionResponse.UserWorkflowInfo userInfo = new WorkflowExamOptionResponse.UserWorkflowInfo(
				user.getUserId(),
				user.getEmail(),
				user.getFirstName() + " " + user.getLastName(),
				user.getCurrentSkillLevel(),
				certInfos);

		if (!eligibility.eligible()) {
			return new WorkflowExamOptionResponse(userInfo, eligibility, List.of());
		}

		List<WorkflowExamOptionResponse.WorkflowExamOption> availableExams = examRepository
				.search(null, null, certificationLevel, ExamStatus.SCHEDULED, true).stream()
				.map(exam -> new WorkflowExamOptionResponse.WorkflowExamOption(
						exam.getId(),
						exam.getExamCode(),
						exam.getExamName(),
						exam.getCertificationLevel(),
						exam.getDurationMinutes(),
						exam.getTotalMarks(),
						exam.getPassingPercentage(),
						exam.getScheduledStartTime(),
						exam.getScheduledEndTime()))
				.toList();

		return new WorkflowExamOptionResponse(userInfo, eligibility, availableExams);
	}

	@Override
	@CacheEvict(cacheNames = "dashboard", allEntries = true)
	public ExamWorkflowApplicationResponse createApplication(String email, ExamWorkflowApplicationRequest request) {
		User user = findUser(email);
		CertificationEligibilityResponse eligibility = certificationJourneyService
				.getEligibility(email, request.certificationLevel());
		if (!eligibility.eligible()) {
			throw new BusinessException(eligibility.message(), HttpStatus.BAD_REQUEST);
		}

		Exam exam = examRepository.findById(request.examId())
				.orElseThrow(() -> new ResourceNotFoundException("Exam not found"));

		if (!exam.isPublished()) {
			throw new BusinessException("Selected exam is not published", HttpStatus.BAD_REQUEST);
		}
		if (exam.getCertificationLevel() != request.certificationLevel()) {
			throw new BusinessException(
					"Selected exam does not match requested certification level",
					HttpStatus.BAD_REQUEST);
		}
		/*
		 * Refused here, before an application exists and long before anyone is
		 * charged. Without this a candidate could apply for an exam whose
		 * booking window has closed, pay for it, and only then reach the
		 * scheduling step to be told there is no date they can pick — holding a
		 * paid application that can never be used. The window is the one
		 * precondition of this flow that is checked at the far end of it.
		 */
		String bookingIssue = evaluateBookingWindowIssue(exam);
		if (bookingIssue != null) {
			throw new BusinessException(bookingIssue, HttpStatus.BAD_REQUEST);
		}

		CertificationApplication application = CertificationApplication.builder()
				.user(user)
				.exam(exam)
				.certificationLevel(request.certificationLevel())
				.applicationStatus(CertificationApplicationStatus.APPLIED)
				.paymentStatus(PaymentStatus.PENDING)
				.appliedOn(LocalDate.now())
				.remarks(request.remarks())
				.build();

		CertificationApplication saved = certificationApplicationRepository.save(application);
		return toWorkflowApplicationResponse(saved);
	}

	@Override
	@CacheEvict(cacheNames = "dashboard", allEntries = true)
	public PaymentResponse initiatePayment(String email, Long applicationId, PaymentInitiationRequest request) {
		/*
		 * The last gap where money could still be taken for a sitting that can
		 * never happen. Applying already checks the window, but an application
		 * made while the window was open can be paid for after it has shut —
		 * the candidate leaves the tab open overnight, or the window lapses
		 * between the two clicks. Checked here rather than at completion:
		 * refusing after the gateway has charged them is the one outcome worse
		 * than letting it through.
		 */
		CertificationApplication application = findApplication(email, applicationId);
		if (application.getExam() != null) {
			String bookingIssue = evaluateBookingWindowIssue(application.getExam());
			if (bookingIssue != null) {
				throw new BusinessException(bookingIssue, HttpStatus.BAD_REQUEST);
			}
		}
		return paymentService.initiatePayment(email, applicationId, request);
	}

	@Override
	@CacheEvict(cacheNames = "dashboard", allEntries = true)
	public PaymentResponse completePayment(String email, Long applicationId, PaymentCompletionRequest request) {
		findApplication(email, applicationId);

		PaymentResponse latestPayment = paymentService.getPaymentHistory(email).stream()
				.filter(payment -> payment.applicationId() != null && payment.applicationId().equals(applicationId))
				.findFirst()
				.orElseThrow(() -> new ResourceNotFoundException("Payment not found for application"));

		// Forwarded whole: the gateway handshake is what settles the payment, and
		// dropping it here would leave verification with nothing but the
		// browser's own claim of success.
		return paymentService.verifyPayment(
				email,
				latestPayment.transactionId(),
				new PaymentVerificationRequest(
						request.success(),
						request.providerReference(),
						request.razorpayOrderId(),
						request.razorpayPaymentId(),
						request.razorpaySignature()));
	}

	@Override
	@CacheEvict(cacheNames = "dashboard", allEntries = true)
	public ExamWorkflowApplicationResponse scheduleExam(String email, Long applicationId,
			WorkflowExamScheduleRequest request) {
		CertificationApplication application = findApplication(email, applicationId);
		if (application.getPaymentStatus() != PaymentStatus.SUCCESS) {
			throw new BusinessException("Payment must be completed before scheduling the examination",
					HttpStatus.BAD_REQUEST);
		}

		if (application.getExam() == null) {
			throw new BusinessException("Application is not linked to an exam", HttpStatus.BAD_REQUEST);
		}

		/*
		 * Scheduling is for an attempt that has not happened yet. Once one has,
		 * re-scheduling is only ever the first half of sitting the exam a second
		 * time on one payment, and it was reachable straight from the applications
		 * list: a spent application still offered "Continue to Next Step", which
		 * led here and then to start.
		 *
		 * A still-running session is the exception, since rejoining it is not a
		 * second attempt — but it does not need scheduling either, so it is
		 * refused here and handled at start.
		 */
		String reScheduleIssue = evaluateReScheduleIssue(application);
		if (reScheduleIssue != null) {
			throw new BusinessException(reScheduleIssue, HttpStatus.BAD_REQUEST);
		}

		Exam exam = application.getExam();
		Instant now = Instant.now();
		Instant bookingOpensAt = exam.getScheduledStartTime();
		Instant bookingClosesAt = exam.getScheduledEndTime();

		/*
		 * Checked before anything about the requested time, because when the
		 * exam's own window has run out there is no time the candidate could
		 * name that would be accepted. Reported as a fault in their pick — which
		 * is what "cannot be later than the exam window end" reads as — it sends
		 * them back to the picker to try another date, and another, with every
		 * one of them refused for the same reason they were not told.
		 */
		if (bookingClosesAt != null && now.isAfter(bookingClosesAt)) {
			throw new BusinessException(
					"Booking for this exam closed on " + formatWindowBound(bookingClosesAt)
							+ ", so there is no slot left to move to. Contact support to have the exam"
							+ " window reopened — your payment stays on this application.",
					HttpStatus.BAD_REQUEST);
		}

		/*
		 * A slot is only worth booking if it can still be attended. The bound is
		 * the close of the window rather than the booked time itself, because a
		 * candidate confirming "now" sends an instant that is already a few
		 * seconds old by the time it arrives, and on a slow connection a few
		 * minutes old — refusing that would block the one booking they are most
		 * likely to make.
		 */
		if (ExamStartWindow.hasClosed(request.scheduledExamTime(), now)) {
			throw new BusinessException(
					"That time has already passed. Pick a slot you can still attend.",
					HttpStatus.BAD_REQUEST);
		}

		/*
		 * Both bounds are named in full. The candidate is being turned away from
		 * a date they chose deliberately, and a refusal that does not say which
		 * dates would have worked leaves them guessing at a range only the
		 * server can see.
		 */
		if (bookingOpensAt != null && request.scheduledExamTime().isBefore(bookingOpensAt)) {
			throw new BusinessException(
					"This exam opens for booking on " + formatWindowBound(bookingOpensAt)
							+ ". Pick a time from then onwards.",
					HttpStatus.BAD_REQUEST);
		}
		if (bookingClosesAt != null && request.scheduledExamTime().isAfter(bookingClosesAt)) {
			throw new BusinessException(
					"This exam can only be booked up to " + formatWindowBound(bookingClosesAt)
							+ ". Pick an earlier time.",
					HttpStatus.BAD_REQUEST);
		}

		application.setScheduledExamTime(request.scheduledExamTime());
		CertificationApplication saved = certificationApplicationRepository.save(application);
		return toWorkflowApplicationResponse(saved);
	}

	@Override
	@CacheEvict(cacheNames = "dashboard", allEntries = true)
	public ExamStartResponse startExam(String email, Long applicationId, ExamStartRequest request) {
		CertificationApplication application = findApplication(email, applicationId);

		String readinessIssue = evaluateStartReadinessIssue(application);
		if (readinessIssue != null) {
			throw new BusinessException(readinessIssue, HttpStatus.BAD_REQUEST);
		}

		/*
		 * Scoped to this application, not to the candidate's history with the
		 * exam. The two differ the moment anyone re-applies, and reading the
		 * second as the first is what used to resume an attempt begun under an
		 * application the candidate had already spent.
		 */
		ExamSession existingSession = examSessionRepository
				.findTopByCertificationApplicationOrderBySessionStartTimeDescIdDesc(application)
				.orElse(null);

		if (existingSession != null) {
			return resumeOrRefuse(application, existingSession);
		}

		/*
		 * Checked here, below the resume branch, and not with the other
		 * readiness rules. Those describe the application, which does not change
		 * once an attempt is running; this one describes the clock, and a
		 * session that already exists began inside its own window and carries
		 * its own timer from that moment. Closing the window on a rejoin would
		 * take an attempt the candidate paid for and started on time away from
		 * them for having lost their connection eleven minutes in.
		 */
		String windowIssue = evaluateStartWindowIssue(application);
		if (windowIssue != null) {
			throw new BusinessException(windowIssue, HttpStatus.BAD_REQUEST);
		}

		List<Question> selectedQuestions = buildProportionalQuestionSet(
				application.getExam(), application.getCertificationLevel());
		Collections.shuffle(selectedQuestions);

		List<Long> selectedIds = selectedQuestions.stream().map(Question::getId).toList();
		ExamSession session = ExamSession.builder()
				.sessionToken(UUID.randomUUID())
				.user(application.getUser())
				.exam(application.getExam())
				.certificationApplication(application)
				.sessionStartTime(Instant.now())
				.sessionStatus(ExamStatus.IN_PROGRESS)
				.violationCount(0)
				.selectedQuestionIdsJson(writeLongList(selectedIds))
				.build();

		ExamSession savedSession = examSessionRepository.save(session);
		ExamQuestionPayloadResponse firstQuestion = toQuestionPayload(selectedQuestions.get(0));

		log.info("Exam started applicationId={} sessionToken={} questionCount={}",
				applicationId, savedSession.getSessionToken(), selectedIds.size());
		auditService.record(AuditEvent.builder()
				.eventType(AuditEventType.EXAM_START)
				.outcome(AuditOutcome.SUCCESS)
				.actorEmail(email)
				.actorUserId(application.getUser().getUserId())
				.targetUserId(application.getUser().getUserId())
				.targetType("EXAM_SESSION")
				.targetId(String.valueOf(savedSession.getId()))
				.description("Started exam " + application.getExam().getExamCode()
						+ " (application #" + applicationId + ")")
				.build());

		return new ExamStartResponse(
				application.getId(),
				application.getExam().getId(),
				application.getExam().getExamCode(),
				application.getCertificationLevel(),
				savedSession.getSessionToken(),
				savedSession.getId(),
				savedSession.getSessionStartTime(),
				selectedIds.size(),
				selectedIds,
				firstQuestion,
				examDurationSeconds(application),
				false,
				null);
	}

	/**
	 * Decides what a second start on an application that already has an attempt
	 * means: rejoining one that is genuinely still running, or trying to buy a
	 * second sitting on one payment.
	 *
	 * <p>The distinction is the session's own status, which is the only record of
	 * how the attempt ended. {@code IN_PROGRESS} is an interrupted sitting — a
	 * closed laptop, a dropped connection, a browser that crashed — and rejoining
	 * it costs the candidate nothing they did not already have, because the clock
	 * still runs from the original start and the strikes are still on the session.
	 * Anything else is an attempt that reached an end: terminated by proctoring,
	 * or submitted and scored. Those are spent, and the way back is a new
	 * application and a new payment.</p>
	 */
	private ExamStartResponse resumeOrRefuse(CertificationApplication application, ExamSession existingSession) {
		if (existingSession.getSessionStatus() != ExamStatus.IN_PROGRESS) {
			throw new BusinessException(
					existingSession.getSessionStatus() == ExamStatus.INVALIDATED
							? "This attempt was terminated by proctoring. Re-apply and complete payment to restart from question 1."
							: "This application has already been used for an exam attempt. Re-apply and complete payment to sit it again.",
					HttpStatus.BAD_REQUEST);
		}

		List<Long> selectedIds = readLongList(existingSession.getSelectedQuestionIdsJson());
		if (selectedIds.isEmpty()) {
			throw new BusinessException("Active exam session has no selected questions", HttpStatus.INTERNAL_SERVER_ERROR);
		}

		Question firstQuestion = questionRepository.findById(selectedIds.get(0))
				.orElseThrow(() -> new ResourceNotFoundException("Question not found in existing exam session"));

		long remainingSeconds = remainingSeconds(application, existingSession);

		ExamProgressResponse savedProgress = toProgressResponse(existingSession, remainingSeconds);

		log.info("Exam resume applicationId={} sessionToken={} questionCount={} remainingSeconds={} answersRestored={}",
				application.getId(), existingSession.getSessionToken(), selectedIds.size(), remainingSeconds,
				savedProgress == null ? 0 : savedProgress.answers().size());

		return new ExamStartResponse(
				application.getId(),
				application.getExam().getId(),
				application.getExam().getExamCode(),
				application.getCertificationLevel(),
				existingSession.getSessionToken(),
				existingSession.getId(),
				existingSession.getSessionStartTime(),
				selectedIds.size(),
				selectedIds,
				toQuestionPayload(firstQuestion),
				remainingSeconds,
				true,
				savedProgress);
	}

	/** Time left in an attempt, measured from when its session began. */
	private long remainingSeconds(CertificationApplication application, ExamSession session) {
		long durationSeconds = examDurationSeconds(
				application != null && application.getExam() != null ? application.getExam() : session.getExam());
		Instant startedAt = session.getSessionStartTime();
		if (startedAt == null) {
			return durationSeconds;
		}
		long elapsed = Duration.between(startedAt, Instant.now()).toSeconds();
		return Math.max(0L, durationSeconds - elapsed);
	}

	private long examDurationSeconds(CertificationApplication application) {
		return examDurationSeconds(application == null ? null : application.getExam());
	}

	private long examDurationSeconds(Exam exam) {
		Integer durationMinutes = exam == null ? null : exam.getDurationMinutes();
		return (durationMinutes == null ? DEFAULT_EXAM_DURATION_MINUTES : durationMinutes) * 60L;
	}

	@Override
	@Transactional(readOnly = true)
	public ExamSessionQuestionResponse getSessionQuestion(String email, UUID sessionToken, int questionNumber) {
		ExamSession session = examSessionRepository.findBySessionTokenAndUserEmailIgnoreCase(sessionToken, email)
				.orElseThrow(() -> new ResourceNotFoundException("Active exam session not found"));

		if (session.getSessionStatus() != ExamStatus.IN_PROGRESS) {
			throw new BusinessException("Exam session is no longer active", HttpStatus.BAD_REQUEST);
		}

		List<Long> questionIds = readLongList(session.getSelectedQuestionIdsJson());
		int totalQuestions = questionIds.size();
		if (questionNumber < 1 || questionNumber > totalQuestions) {
			throw new BusinessException(
					String.format("Question number must be between 1 and %d", totalQuestions),
					HttpStatus.BAD_REQUEST);
		}

		Long questionId = questionIds.get(questionNumber - 1);
		Question question = questionRepository.findById(questionId)
				.orElseThrow(() -> new ResourceNotFoundException("Question not found in session"));

		return new ExamSessionQuestionResponse(
				sessionToken,
				questionNumber,
				totalQuestions,
				toQuestionPayload(question));
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Last write wins, deliberately. The client sends the whole draft on every
	 * save rather than a delta, because these arrive over exactly the network
	 * that is failing — a save queued while offline and flushed on reconnect can
	 * overtake a later one, and merging two partial views of the same paper would
	 * be guesswork. A whole draft that arrives out of order costs at most the
	 * answers given between the two saves, and the next save restores them.</p>
	 *
	 * <p>Refused once the session is no longer running, so a stale client that
	 * reconnects after its attempt was terminated or submitted cannot write over
	 * the record of it.</p>
	 */
	@Override
	public ExamProgressResponse saveProgress(String email, UUID sessionToken, ExamProgressSaveRequest request) {
		ExamSession session = examSessionRepository.findBySessionTokenAndUserEmailIgnoreCase(sessionToken, email)
				.orElseThrow(() -> new ResourceNotFoundException("Active exam session not found"));

		if (session.getSessionStatus() != ExamStatus.IN_PROGRESS) {
			throw new BusinessException("Exam session is no longer active", HttpStatus.BAD_REQUEST);
		}

		// Answers for questions outside this session's paper are dropped rather
		// than rejected: a save is a background call the candidate never sees, and
		// failing the whole draft over one stray id would silently stop autosaving
		// for the rest of the attempt.
		Set<Long> paper = new HashSet<>(readLongList(session.getSelectedQuestionIdsJson()));
		Map<String, List<String>> answers = new LinkedHashMap<>();
		for (QuestionAnswerSubmissionRequest answer : request.answers()) {
			if (answer == null || answer.questionId() == null || !paper.contains(answer.questionId())) {
				continue;
			}
			answers.put(
					String.valueOf(answer.questionId()),
					answer.selectedOptions() == null ? List.of() : List.copyOf(answer.selectedOptions()));
		}

		List<Long> markedForReview = request.markedForReview() == null
				? List.of()
				: request.markedForReview().stream().filter(paper::contains).toList();

		session.setAnswersDraftJson(writeJson(answers, "Failed to save exam progress"));
		session.setMarkedForReviewJson(writeJson(markedForReview, "Failed to save exam progress"));
		session.setLastQuestionNumber(clampQuestionNumber(request.currentQuestionNumber(), paper.size()));
		session.setProgressSavedAt(Instant.now());

		ExamSession saved = examSessionRepository.save(session);

		log.debug("Exam progress saved sessionToken={} answered={} marked={}",
				sessionToken, answers.size(), markedForReview.size());

		return toProgressResponse(saved, remainingSeconds(saved.getCertificationApplication(), saved));
	}

	@Override
	@Transactional(readOnly = true)
	public ExamProgressResponse getProgress(String email, UUID sessionToken) {
		ExamSession session = examSessionRepository.findBySessionTokenAndUserEmailIgnoreCase(sessionToken, email)
				.orElseThrow(() -> new ResourceNotFoundException("Active exam session not found"));

		ExamProgressResponse progress = toProgressResponse(
				session,
				remainingSeconds(session.getCertificationApplication(), session));

		// A session that has never autosaved still has a token and a clock to
		// report; only the draft is empty.
		return progress != null
				? progress
				: new ExamProgressResponse(
						session.getSessionToken(),
						List.of(),
						List.of(),
						null,
						null,
						remainingSeconds(session.getCertificationApplication(), session));
	}

	/** The stored draft, or {@code null} when nothing has been autosaved yet. */
	private ExamProgressResponse toProgressResponse(ExamSession session, long remainingSeconds) {
		if (session.getProgressSavedAt() == null) {
			return null;
		}

		Map<String, List<String>> answers = readAnswerMap(session.getAnswersDraftJson());
		List<ExamProgressResponse.SavedAnswer> savedAnswers = answers.entrySet().stream()
				.map(entry -> new ExamProgressResponse.SavedAnswer(
						Long.valueOf(entry.getKey()),
						entry.getValue() == null ? List.<String>of() : entry.getValue()))
				.toList();

		return new ExamProgressResponse(
				session.getSessionToken(),
				savedAnswers,
				readLongList(session.getMarkedForReviewJson()),
				session.getLastQuestionNumber(),
				session.getProgressSavedAt(),
				remainingSeconds);
	}

	/**
	 * Keeps a resume from landing on a question that is not on the paper.
	 *
	 * <p>Returns {@code null} rather than clamping to 1 when the client sent
	 * nothing, so "no opinion" stays distinguishable from "question 1".</p>
	 */
	private Integer clampQuestionNumber(Integer requested, int questionCount) {
		if (requested == null || questionCount <= 0) {
			return null;
		}
		return Math.min(Math.max(requested, 1), questionCount);
	}

	@Override
	@Transactional
	@CacheEvict(cacheNames = "dashboard", allEntries = true)
	public ExamWorkflowApplicationResponse reApply(String email, Long failedApplicationId) {
		User user = findUser(email);

		CertificationApplication failedApplication = certificationApplicationRepository
				.findByIdAndUserWithExam(failedApplicationId, user)
				.orElseThrow(() -> new ResourceNotFoundException("Exam application not found"));

		CertificationApplicationStatus currentStatus = failedApplication.getApplicationStatus();
		if (!currentStatus.allowsReApplication()) {
			throw new BusinessException(
					"Re-application is only allowed for FAILED, TERMINATED, EXPIRED or REJECTED applications. "
							+ "Current status: " + currentStatus,
					HttpStatus.BAD_REQUEST);
		}

		CertificationEligibilityResponse eligibility = certificationJourneyService
				.getEligibility(email, failedApplication.getCertificationLevel());
		if (!eligibility.eligible()) {
			throw new BusinessException(eligibility.message(), HttpStatus.BAD_REQUEST);
		}

		boolean hasOpen = certificationApplicationRepository
				.existsByUserAndCertificationLevelAndApplicationStatusIn(
						failedApplication.getUser(),
						failedApplication.getCertificationLevel(),
						Set.of(
								CertificationApplicationStatus.APPLIED,
								CertificationApplicationStatus.ELIGIBLE,
								CertificationApplicationStatus.IN_PROGRESS));

		if (hasOpen) {
			throw new BusinessException(
					"An application is already in progress for " + failedApplication.getCertificationLevel(),
					HttpStatus.CONFLICT);
		}

		/*
		 * Checked before the new application is created, for the same reason as
		 * in createApplication: a re-application is followed straight away by a
		 * second payment, and one taken for a sitting that can never be booked
		 * is the worst outcome this flow has.
		 *
		 * An exam that has been unpublished or moved off SCHEDULED is refused
		 * here too. It used to be quietly dropped to null, which produced an
		 * application linked to no exam — payable, and then permanently stuck at
		 * "Application is not linked to an exam".
		 */
		Exam exam = failedApplication.getExam();
		if (exam == null || !exam.isPublished() || exam.getExamStatus() != ExamStatus.SCHEDULED) {
			throw new BusinessException(
					"The " + failedApplication.getCertificationLevel()
							+ " exam is not open for applications at the moment. Contact support before"
							+ " paying again — nothing has been charged.",
					HttpStatus.BAD_REQUEST);
		}
		String bookingIssue = evaluateBookingWindowIssue(exam);
		if (bookingIssue != null) {
			throw new BusinessException(bookingIssue, HttpStatus.BAD_REQUEST);
		}

		CertificationApplication newApplication = CertificationApplication.builder()
				.user(user)
				.exam(exam)
				.certificationLevel(failedApplication.getCertificationLevel())
				.applicationStatus(CertificationApplicationStatus.APPLIED)
				.paymentStatus(PaymentStatus.PENDING)
				.appliedOn(LocalDate.now())
				.remarks("Re-application after " + currentStatus + " on application #" + failedApplicationId)
				.build();

		CertificationApplication saved = certificationApplicationRepository.save(newApplication);

		ExamWorkflowApplicationResponse response = new ExamWorkflowApplicationResponse(
				saved.getId(),
				user.getUserId(),
				exam == null ? null : exam.getId(),
				exam == null ? null : exam.getExamCode(),
				saved.getCertificationLevel(),
				saved.getApplicationStatus(),
				saved.getPaymentStatus(),
				saved.getAppliedOn(),
				saved.getScheduledExamTime(),
				ExamStartWindow.opensAt(saved.getScheduledExamTime()),
				ExamStartWindow.closesAt(saved.getScheduledExamTime()),
				exam == null ? null : exam.getScheduledStartTime(),
				exam == null ? null : exam.getScheduledEndTime(),
				saved.getRemarks(),
				false,
				isViolationRestartRequired(saved),
				resolveRestartMessage(saved));

		log.info("re-apply userId={} level={} sourceApplicationId={} newApplicationId={}",
				email,
				failedApplication.getCertificationLevel(),
				failedApplicationId,
				saved.getId());

		return response;
	}

	@Override
	@Transactional(readOnly = true)
	public List<ExamWorkflowApplicationResponse> getReApplyableApplications(String email) {
		User user = findUser(email);
		return certificationApplicationRepository.findFailedApplicationsForReApply(user).stream()
				.map(app -> toWorkflowApplicationResponse(app, true))
				.toList();
	}

	private ExamWorkflowApplicationResponse toWorkflowApplicationResponse(CertificationApplication application) {
		return toWorkflowApplicationResponse(application, false);
	}

	private ExamWorkflowApplicationResponse toWorkflowApplicationResponse(CertificationApplication application,
			boolean canReApply) {
		return new ExamWorkflowApplicationResponse(
				application.getId(),
				application.getUser().getUserId(),
				application.getExam() == null ? null : application.getExam().getId(),
				application.getExam() == null ? null : application.getExam().getExamCode(),
				application.getCertificationLevel(),
				application.getApplicationStatus(),
				application.getPaymentStatus(),
				application.getAppliedOn(),
				application.getScheduledExamTime(),
				ExamStartWindow.opensAt(application.getScheduledExamTime()),
				ExamStartWindow.closesAt(application.getScheduledExamTime()),
				application.getExam() == null ? null : application.getExam().getScheduledStartTime(),
				application.getExam() == null ? null : application.getExam().getScheduledEndTime(),
				application.getRemarks(),
				canReApply,
				isViolationRestartRequired(application),
				resolveRestartMessage(application));
	}

	/**
	 * Whether this application was closed by proctoring rather than by a score.
	 *
	 * <p>Read from the status now that terminations have one of their own. This
	 * used to substring-match a marker phrase against the free-text remarks,
	 * which meant an operator editing a remark could silently change what the
	 * API reported about how an exam ended.</p>
	 */
	private boolean isViolationRestartRequired(CertificationApplication application) {
		return application.getApplicationStatus() == CertificationApplicationStatus.TERMINATED;
	}

	private String resolveRestartMessage(CertificationApplication application) {
		return isViolationRestartRequired(application) ? VIOLATION_RESTART_MESSAGE : null;
	}

	private ExamQuestionPayloadResponse toQuestionPayload(Question question) {
		return new ExamQuestionPayloadResponse(
				question.getId(),
				question.getQuestionCode(),
				question.getQuestionText(),
				parseQuestionType(question.getQuestionType()),
				question.getSeverity(),
				readStringList(question.getOptionsJson()));
	}

	private QuestionType parseQuestionType(String questionType) {
		return switch (questionType) {
			case "Single Choice", "SINGLE_CHOICE" -> QuestionType.SINGLE_CHOICE;
			case "Multiple Choice", "MULTIPLE_CHOICE" -> QuestionType.MULTIPLE_CHOICE;
			default -> throw new BusinessException("Unsupported question type: " + questionType,
					HttpStatus.INTERNAL_SERVER_ERROR);
		};
	}

	private List<String> readStringList(String rawJson) {
		try {
			return objectMapper.readValue(rawJson, new TypeReference<List<String>>() {
			});
		} catch (JsonProcessingException ex) {
			throw new BusinessException("Failed to load question payload", HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	private List<Long> readLongList(String rawJson) {
		if (rawJson == null || rawJson.isBlank()) {
			return List.of();
		}
		try {
			return objectMapper.readValue(rawJson, LONG_LIST_TYPE);
		} catch (JsonProcessingException ex) {
			throw new BusinessException("Failed to read session question list", HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Reads a saved answer draft, treating unreadable JSON as no draft at all.
	 *
	 * <p>The one place in this class where a parse failure is not an error: a
	 * corrupt draft costs the candidate the answers it held, but failing the
	 * start call over it would cost them the attempt as well — they would be
	 * locked out of a session they cannot rejoin and cannot re-sit without paying
	 * again.</p>
	 */
	private Map<String, List<String>> readAnswerMap(String rawJson) {
		if (rawJson == null || rawJson.isBlank()) {
			return Map.of();
		}
		try {
			return objectMapper.readValue(rawJson, ANSWER_MAP_TYPE);
		} catch (JsonProcessingException | IllegalArgumentException ex) {
			log.warn("Discarding unreadable answer draft: {}", ex.getMessage());
			return Map.of();
		}
	}

	private String writeJson(Object value, String failureMessage) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException ex) {
			throw new BusinessException(failureMessage, HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	private String writeLongList(List<Long> values) {
		try {
			return objectMapper.writeValueAsString(values);
		} catch (JsonProcessingException ex) {
			throw new BusinessException("Failed to initialize exam session questions", HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * Draws one paper to the blueprint the admin set on this exam: its question
	 * total, split across the severities in the proportions it carries. An exam
	 * that carries no blueprint — one created before it was configurable — draws
	 * the standard 30-question paper, so no attempt is left without questions.
	 */
	private List<Question> buildProportionalQuestionSet(Exam exam, CertificationLevel level) {
		ExamQuestionBlueprint blueprint = ExamQuestionBlueprint.of(exam);
		Map<QuestionSeverity, Integer> counts = blueprint.questionCounts();

		List<Question> combined = new ArrayList<>(blueprint.totalQuestions());
		for (QuestionSeverity severity : QuestionSeverity.values()) {
			combined.addAll(pickSeverity(level, severity, counts.get(severity)));
		}
		return combined;
	}

	private List<Question> pickSeverity(CertificationLevel level, QuestionSeverity severity, int count) {
		if (count <= 0) {
			return new ArrayList<>();
		}
		List<Question> pool = new ArrayList<>(
				questionRepository.findByCertificationLevelAndSeverityInAndActiveTrue(level, List.of(severity)));
		if (pool.size() < count) {
			throw new BusinessException(
					String.format("Not enough %s severity questions for level %s: required %d, found %d",
							severity, level, count, pool.size()),
					HttpStatus.BAD_REQUEST);
		}
		Collections.shuffle(pool);
		return new ArrayList<>(pool.subList(0, count));
	}

	private User findUser(String email) {
		return userRepository.findByEmailIgnoreCase(email)
				.orElseThrow(() -> new ResourceNotFoundException("User not found"));
	}

	private CertificationApplication findApplication(String email, Long applicationId) {
		User user = findUser(email);
		return certificationApplicationRepository.findByIdAndUser(applicationId, user)
				.orElseThrow(() -> new ResourceNotFoundException("Exam application not found"));
	}

	/**
	 * Why this application may not be (re)scheduled, or {@code null} if it may.
	 *
	 * <p>Legacy sessions with no application link are deliberately not consulted:
	 * an unlinked session is one whose application is unknown, and refusing to
	 * schedule on the strength of an attempt that may belong to someone else's
	 * application would strand a paid candidate. The start path is the backstop
	 * for those.</p>
	 */
	/**
	 * A booking-window bound as it appears in a refusal the candidate reads.
	 *
	 * <p>Stated in UTC and labelled as such rather than guessed at in a local
	 * zone. The server has no reliable way to know the candidate's — the request
	 * carries an instant, not an offset — and a time printed without a zone is
	 * read as local, so a bound five and a half hours out would look like the
	 * server refusing a date that is plainly inside the range it just named. The
	 * picker shows the same bounds in the browser's own zone; this is the
	 * backstop, and a backstop has to be unambiguous rather than convenient.</p>
	 */
	private static String formatWindowBound(Instant bound) {
		return WINDOW_BOUND_FORMAT.format(bound);
	}

	/**
	 * Why this exam cannot be applied for right now, or null when it can.
	 *
	 * <p>Only the booking window, and only the two ways it can rule an exam
	 * out: it has closed, or it has not opened. Both mean the same thing to a
	 * candidate about to pay — there is no date they would be allowed to
	 * pick — and both used to be discovered one step after the money.</p>
	 */
	private String evaluateBookingWindowIssue(Exam exam) {
		Instant now = Instant.now();
		if (exam.getScheduledEndTime() != null && now.isAfter(exam.getScheduledEndTime())) {
			return "This exam stopped taking bookings on " + formatWindowBound(exam.getScheduledEndTime())
					+ ", so a slot cannot be scheduled for it. Contact support before paying —"
					+ " nothing has been charged.";
		}
		if (exam.getScheduledStartTime() != null && now.isBefore(exam.getScheduledStartTime())) {
			return "This exam opens for booking on " + formatWindowBound(exam.getScheduledStartTime())
					+ ". Apply from then onwards.";
		}
		return null;
	}

	private String evaluateReScheduleIssue(CertificationApplication application) {
		ExamSession existingSession = examSessionRepository
				.findTopByCertificationApplicationOrderBySessionStartTimeDescIdDesc(application)
				.orElse(null);

		if (existingSession == null) {
			return null;
		}

		return switch (existingSession.getSessionStatus()) {
			case IN_PROGRESS -> "An attempt is already in progress for this application. Resume it instead of rescheduling.";
			case INVALIDATED -> "This attempt was terminated by proctoring. Re-apply and complete payment to restart from question 1.";
			default -> "This application has already been used for an exam attempt. Re-apply and complete payment to sit it again.";
		};
	}

	private String evaluateStartReadinessIssue(CertificationApplication application) {
		// Split so the candidate is told which of the two happened. They lead to
		// the same place, but "your exam was terminated" and "you did not pass"
		// are not the same news, and the merged wording made a terminated
		// candidate wonder which one they were being told.
		if (application.getApplicationStatus() == CertificationApplicationStatus.TERMINATED) {
			return "This attempt was terminated by proctoring. Re-apply and complete payment to restart from question 1.";
		}
		if (application.getApplicationStatus() == CertificationApplicationStatus.FAILED) {
			return "This application is closed after a completed attempt. Re-apply and complete payment before starting again.";
		}
		if (application.getPaymentStatus() != PaymentStatus.SUCCESS) {
			return "Payment must be completed before starting the examination";
		}
		if (application.getScheduledExamTime() == null) {
			return "Examination must be scheduled before it can be started";
		}
		if (application.getExam() == null) {
			return "Application is not linked to an exam";
		}
		return null;
	}

	/**
	 * Why a first sitting cannot begin at this moment, or {@code null} if it can.
	 *
	 * <p>Both refusals name rescheduling, because it is the way out of either
	 * one: the booking can be moved right up until the attempt starts, and the
	 * payment covers the sitting rather than the date.</p>
	 */
	private String evaluateStartWindowIssue(CertificationApplication application) {
		Instant scheduled = application.getScheduledExamTime();
		Instant now = Instant.now();
		if (ExamStartWindow.isOpen(scheduled, now)) {
			return null;
		}

		if (ExamStartWindow.hasClosed(scheduled, now)) {
			return String.format(
					"Your booked exam slot closed %s ago. Reschedule to pick a new time — "
							+ "your payment and application stay as they are.",
					humanize(Duration.between(ExamStartWindow.closesAt(scheduled), now)));
		}

		return String.format(
				"Your exam opens in %s, %d minutes ahead of your booked time, and closes %d minutes after it. "
						+ "Come back then, or reschedule if you want to sit it sooner.",
				humanize(Duration.between(now, ExamStartWindow.opensAt(scheduled))),
				ExamStartWindow.GRACE.toMinutes(),
				ExamStartWindow.GRACE.toMinutes());
	}

	/**
	 * A duration as a short phrase.
	 *
	 * <p>The messages talk in elapsed time rather than naming the hour because
	 * the server holds an {@link Instant} and no timezone for the candidate;
	 * rendering one as a wall clock would quote a time they do not recognise.
	 * The client has the window bounds and can show local times properly.</p>
	 */
	private static String humanize(Duration duration) {
		// Rounded up, not truncated. A slot two hours, forty-nine minutes and
		// fifty-nine seconds away is "2 hours 50 minutes": truncating shaves a
		// minute off every wait, and told a candidate to come back a minute
		// before the window would actually let them in. The floor of one minute
		// keeps a sub-minute gap from reading as no time at all.
		long minutes = Math.max(1, (duration.toSeconds() + 59) / 60);
		if (minutes < 60) {
			return plural(minutes, "minute");
		}

		long hours = minutes / 60;
		if (hours < 24) {
			long remainingMinutes = minutes % 60;
			return remainingMinutes == 0
					? plural(hours, "hour")
					: plural(hours, "hour") + " " + plural(remainingMinutes, "minute");
		}

		long days = hours / 24;
		long remainingHours = hours % 24;
		return remainingHours == 0
				? plural(days, "day")
				: plural(days, "day") + " " + plural(remainingHours, "hour");
	}

	private static String plural(long value, String unit) {
		return value + " " + unit + (value == 1 ? "" : "s");
	}
}
