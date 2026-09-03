package com.ems.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ems.dto.request.ExamProgressSaveRequest;
import com.ems.dto.request.ExamStartRequest;
import com.ems.dto.request.QuestionAnswerSubmissionRequest;
import com.ems.dto.request.WorkflowExamScheduleRequest;
import com.ems.dto.response.ExamProgressResponse;
import com.ems.dto.response.ExamStartResponse;
import com.ems.dto.response.ExamWorkflowApplicationResponse;
import com.ems.entity.CertificationApplication;
import com.ems.entity.Exam;
import com.ems.entity.ExamSession;
import com.ems.entity.Question;
import com.ems.entity.User;
import com.ems.enums.CertificationApplicationStatus;
import com.ems.enums.CertificationLevel;
import com.ems.enums.ExamStatus;
import com.ems.enums.PaymentStatus;
import com.ems.enums.QuestionSeverity;
import com.ems.exception.BusinessException;
import com.ems.repository.CertificationApplicationRepository;
import com.ems.repository.CertificationRepository;
import com.ems.repository.ExamRepository;
import com.ems.repository.ExamSessionRepository;
import com.ems.repository.QuestionRepository;
import com.ems.repository.UserRepository;
import com.ems.service.CertificationJourneyService;
import com.ems.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ExamWorkflowServiceImplTest {

	private static final String EMAIL = "candidate@example.com";

	@Mock
	private CertificationJourneyService certificationJourneyService;

	@Mock
	private CertificationApplicationRepository certificationApplicationRepository;

	@Mock
	private CertificationRepository certificationRepository;

	@Mock
	private UserRepository userRepository;

	@Mock
	private ExamRepository examRepository;

	@Mock
	private QuestionRepository questionRepository;

	@Mock
	private ExamSessionRepository examSessionRepository;

	@Mock
	private PaymentService paymentService;

	private ExamWorkflowServiceImpl examWorkflowService;

	@BeforeEach
	void setUp() {
		examWorkflowService = new ExamWorkflowServiceImpl(
				certificationJourneyService,
				certificationApplicationRepository,
				certificationRepository,
				userRepository,
				examRepository,
				questionRepository,
				examSessionRepository,
				paymentService,
				new ObjectMapper());
	}

	@Test
	void startExam_whenApplicationFailed_throwsReapplyAndPaymentMessage() {
		User user = User.builder().id(10L).email("blocked@example.com").build();
		Exam exam = Exam.builder().id(20L).certificationLevel(CertificationLevel.L1).build();
		CertificationApplication application = CertificationApplication.builder()
				.id(30L)
				.user(user)
				.exam(exam)
				.applicationStatus(CertificationApplicationStatus.FAILED)
				.paymentStatus(PaymentStatus.SUCCESS)
				.scheduledExamTime(Instant.now().plusSeconds(300))
				.build();

		when(userRepository.findByEmailIgnoreCase("blocked@example.com")).thenReturn(Optional.of(user));
		when(certificationApplicationRepository.findByIdAndUser(30L, user)).thenReturn(Optional.of(application));

		BusinessException ex = assertThrows(
				BusinessException.class,
				() -> examWorkflowService.startExam(
						"blocked@example.com",
						30L,
						new ExamStartRequest(null, Boolean.TRUE, Instant.now())));

		assertThat(ex.getMessage()).contains("Re-apply and complete payment");
		verifyNoInteractions(questionRepository, examSessionRepository);
	}

	/**
	 * A terminated attempt is spent. Starting again on the same application must
	 * be refused even though the application itself still looks startable —
	 * which is how a candidate whose exam was ended for violations used to walk
	 * back in through the applications list without paying again.
	 */
	@Test
	void startExam_whenOwnSessionWasInvalidated_refusesAndDemandsReApplication() {
		CertificationApplication application = startableApplication();
		ExamSession invalidated = sessionFor(application, ExamStatus.INVALIDATED, Instant.now().minusSeconds(600));

		when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(application.getUser()));
		when(certificationApplicationRepository.findByIdAndUser(30L, application.getUser()))
				.thenReturn(Optional.of(application));
		when(examSessionRepository.findTopByCertificationApplicationOrderBySessionStartTimeDescIdDesc(application))
				.thenReturn(Optional.of(invalidated));

		BusinessException ex = assertThrows(
				BusinessException.class,
				() -> examWorkflowService.startExam(EMAIL, 30L, new ExamStartRequest(null, Boolean.TRUE, Instant.now())));

		assertThat(ex.getMessage()).contains("terminated by proctoring");
		assertThat(ex.getMessage()).contains("Re-apply and complete payment");
		verify(examSessionRepository, never()).save(any(ExamSession.class));
	}

	/**
	 * Rejoining an interrupted attempt gets back what was left of it, not a fresh
	 * sitting. The client used to start its own countdown at the full duration on
	 * every start, so re-entering bought another complete exam each time.
	 */
	@Test
	void startExam_whenResumingLiveSession_returnsRemainingTimeNotFullDuration() throws Exception {
		CertificationApplication application = startableApplication();
		Question question = Question.builder().id(500L).questionCode("Q-1").questionText("?")
				.questionType("Single Choice").severity(QuestionSeverity.LOW).optionsJson("[\"a\",\"b\"]").build();

		ExamSession live = sessionFor(application, ExamStatus.IN_PROGRESS, Instant.now().minusSeconds(900));
		live.setSelectedQuestionIdsJson("[500]");

		when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(application.getUser()));
		when(certificationApplicationRepository.findByIdAndUser(30L, application.getUser()))
				.thenReturn(Optional.of(application));
		when(examSessionRepository.findTopByCertificationApplicationOrderBySessionStartTimeDescIdDesc(application))
				.thenReturn(Optional.of(live));
		when(questionRepository.findById(500L)).thenReturn(Optional.of(question));

		ExamStartResponse response = examWorkflowService.startExam(
				EMAIL, 30L, new ExamStartRequest(null, Boolean.TRUE, Instant.now()));

		assertThat(response.resumed()).isTrue();
		assertThat(response.sessionToken()).isEqualTo(live.getSessionToken());
		// 60-minute exam, 15 minutes gone.
		assertThat(response.remainingSeconds()).isBetween(2690L, 2700L);
		verify(examSessionRepository, never()).save(any(ExamSession.class));
	}

	/**
	 * The point of the draft: an attempt cut off mid-paper comes back with its
	 * answers, not just its clock. Before this, resuming returned the same
	 * questions and an empty answer sheet, because the answers had only ever
	 * existed in the browser that was cut off.
	 */
	@Test
	void startExam_whenResumingSessionWithSavedDraft_returnsTheAnswersAlreadyGiven() {
		CertificationApplication application = startableApplication();
		Question question = Question.builder().id(500L).questionCode("Q-1").questionText("?")
				.questionType("Single Choice").severity(QuestionSeverity.LOW).optionsJson("[\"a\",\"b\"]").build();

		ExamSession live = sessionFor(application, ExamStatus.IN_PROGRESS, Instant.now().minusSeconds(900));
		live.setSelectedQuestionIdsJson("[500,501]");
		live.setAnswersDraftJson("{\"500\":[\"a\"],\"501\":[\"b\",\"c\"]}");
		live.setMarkedForReviewJson("[501]");
		live.setLastQuestionNumber(2);
		live.setProgressSavedAt(Instant.now().minusSeconds(20));

		when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(application.getUser()));
		when(certificationApplicationRepository.findByIdAndUser(30L, application.getUser()))
				.thenReturn(Optional.of(application));
		when(examSessionRepository.findTopByCertificationApplicationOrderBySessionStartTimeDescIdDesc(application))
				.thenReturn(Optional.of(live));
		when(questionRepository.findById(500L)).thenReturn(Optional.of(question));

		ExamStartResponse response = examWorkflowService.startExam(
				EMAIL, 30L, new ExamStartRequest(null, Boolean.TRUE, Instant.now()));

		assertThat(response.resumed()).isTrue();
		assertThat(response.savedProgress()).isNotNull();
		assertThat(response.savedProgress().currentQuestionNumber()).isEqualTo(2);
		assertThat(response.savedProgress().markedForReview()).containsExactly(501L);
		assertThat(response.savedProgress().answers())
				.extracting(ExamProgressResponse.SavedAnswer::questionId)
				.containsExactlyInAnyOrder(500L, 501L);
	}

	/** A fresh start has nothing to restore, and must not claim otherwise. */
	@Test
	void startExam_whenSessionNeverAutosaved_reportsNoSavedProgress() {
		CertificationApplication application = startableApplication();
		Question question = Question.builder().id(500L).questionCode("Q-1").questionText("?")
				.questionType("Single Choice").severity(QuestionSeverity.LOW).optionsJson("[\"a\",\"b\"]").build();

		ExamSession live = sessionFor(application, ExamStatus.IN_PROGRESS, Instant.now().minusSeconds(60));
		live.setSelectedQuestionIdsJson("[500]");

		when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(application.getUser()));
		when(certificationApplicationRepository.findByIdAndUser(30L, application.getUser()))
				.thenReturn(Optional.of(application));
		when(examSessionRepository.findTopByCertificationApplicationOrderBySessionStartTimeDescIdDesc(application))
				.thenReturn(Optional.of(live));
		when(questionRepository.findById(500L)).thenReturn(Optional.of(question));

		ExamStartResponse response = examWorkflowService.startExam(
				EMAIL, 30L, new ExamStartRequest(null, Boolean.TRUE, Instant.now()));

		assertThat(response.savedProgress()).isNull();
	}

	/**
	 * The draft is scoped to the paper the candidate was actually given. An
	 * answer for a question that is not on it is dropped rather than rejected —
	 * failing the whole save would silently stop autosaving for the rest of the
	 * attempt, and the candidate never sees these calls.
	 */
	@Test
	void saveProgress_dropsAnswersForQuestionsOutsideTheSessionPaper() {
		CertificationApplication application = startableApplication();
		ExamSession live = sessionFor(application, ExamStatus.IN_PROGRESS, Instant.now().minusSeconds(120));
		live.setSelectedQuestionIdsJson("[500,501]");

		when(examSessionRepository.findBySessionTokenAndUserEmailIgnoreCase(live.getSessionToken(), EMAIL))
				.thenReturn(Optional.of(live));
		when(examSessionRepository.save(any(ExamSession.class))).thenAnswer(call -> call.getArgument(0));

		ExamProgressResponse response = examWorkflowService.saveProgress(
				EMAIL,
				live.getSessionToken(),
				new ExamProgressSaveRequest(
						List.of(
								new QuestionAnswerSubmissionRequest(500L, List.of("a")),
								new QuestionAnswerSubmissionRequest(999L, List.of("x"))),
						List.of(501L, 999L),
						2));

		assertThat(response.answers())
				.extracting(ExamProgressResponse.SavedAnswer::questionId)
				.containsExactly(500L);
		assertThat(response.markedForReview()).containsExactly(501L);
		assertThat(response.currentQuestionNumber()).isEqualTo(2);
	}

	/**
	 * A client that reconnects after its attempt ended must not be able to write
	 * over the record of it.
	 */
	@Test
	void saveProgress_whenSessionNoLongerRunning_isRefused() {
		CertificationApplication application = startableApplication();
		ExamSession finished = sessionFor(application, ExamStatus.COMPLETED, Instant.now().minusSeconds(4000));

		when(examSessionRepository.findBySessionTokenAndUserEmailIgnoreCase(finished.getSessionToken(), EMAIL))
				.thenReturn(Optional.of(finished));

		BusinessException ex = assertThrows(
				BusinessException.class,
				() -> examWorkflowService.saveProgress(
						EMAIL,
						finished.getSessionToken(),
						new ExamProgressSaveRequest(List.of(), List.of(), 1)));

		assertThat(ex.getMessage()).contains("no longer active");
		verify(examSessionRepository, never()).save(any(ExamSession.class));
	}

	/**
	 * The resume lands on the question the candidate was on, so a number beyond
	 * the paper — a stale client, a shorter re-generated paper — is pulled back
	 * to one that exists rather than stored and handed out.
	 */
	@Test
	void saveProgress_clampsCurrentQuestionNumberToThePaper() {
		CertificationApplication application = startableApplication();
		ExamSession live = sessionFor(application, ExamStatus.IN_PROGRESS, Instant.now().minusSeconds(120));
		live.setSelectedQuestionIdsJson("[500,501]");

		when(examSessionRepository.findBySessionTokenAndUserEmailIgnoreCase(live.getSessionToken(), EMAIL))
				.thenReturn(Optional.of(live));
		when(examSessionRepository.save(any(ExamSession.class))).thenAnswer(call -> call.getArgument(0));

		ExamProgressResponse response = examWorkflowService.saveProgress(
				EMAIL,
				live.getSessionToken(),
				new ExamProgressSaveRequest(List.of(), List.of(), 99));

		assertThat(response.currentQuestionNumber()).isEqualTo(2);
	}

	/**
	 * A booking is a commitment to a time, so turning up hours early is refused
	 * rather than quietly honoured. The candidate is not stuck: the same booking
	 * can be moved, which is what the message points them at.
	 */
	@Test
	void startExam_whenBookedSlotIsStillHoursAway_refusesAndOffersRescheduling() {
		CertificationApplication application = startableApplication();
		application.setScheduledExamTime(Instant.now().plus(3, ChronoUnit.HOURS));

		when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(application.getUser()));
		when(certificationApplicationRepository.findByIdAndUser(30L, application.getUser()))
				.thenReturn(Optional.of(application));
		when(examSessionRepository.findTopByCertificationApplicationOrderBySessionStartTimeDescIdDesc(application))
				.thenReturn(Optional.empty());

		BusinessException ex = assertThrows(
				BusinessException.class,
				() -> examWorkflowService.startExam(EMAIL, 30L, new ExamStartRequest(null, Boolean.TRUE, Instant.now())));

		assertThat(ex.getMessage()).contains("Your exam opens in 2 hours 50 minutes");
		assertThat(ex.getMessage()).contains("reschedule");
		verifyNoInteractions(questionRepository);
		verify(examSessionRepository, never()).save(any(ExamSession.class));
	}

	/** A slot that has been and gone cannot be sat late; it has to be re-booked. */
	@Test
	void startExam_whenBookedSlotHasPassed_refusesAndOffersRescheduling() {
		CertificationApplication application = startableApplication();
		application.setScheduledExamTime(Instant.now().minus(45, ChronoUnit.MINUTES));

		when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(application.getUser()));
		when(certificationApplicationRepository.findByIdAndUser(30L, application.getUser()))
				.thenReturn(Optional.of(application));
		when(examSessionRepository.findTopByCertificationApplicationOrderBySessionStartTimeDescIdDesc(application))
				.thenReturn(Optional.empty());

		BusinessException ex = assertThrows(
				BusinessException.class,
				() -> examWorkflowService.startExam(EMAIL, 30L, new ExamStartRequest(null, Boolean.TRUE, Instant.now())));

		assertThat(ex.getMessage()).contains("closed 35 minutes ago");
		assertThat(ex.getMessage()).contains("Reschedule");
		verifyNoInteractions(questionRepository);
		verify(examSessionRepository, never()).save(any(ExamSession.class));
	}

	/** Ten minutes ahead of the booked time is inside the grace, not early. */
	@Test
	void startExam_whenWithinGraceBeforeBookedSlot_startsTheAttempt() {
		CertificationApplication application = startableApplication();
		application.setScheduledExamTime(Instant.now().plus(9, ChronoUnit.MINUTES));

		when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(application.getUser()));
		when(certificationApplicationRepository.findByIdAndUser(30L, application.getUser()))
				.thenReturn(Optional.of(application));
		when(examSessionRepository.findTopByCertificationApplicationOrderBySessionStartTimeDescIdDesc(application))
				.thenReturn(Optional.empty());
		stubQuestionPool();
		when(examSessionRepository.save(any(ExamSession.class))).thenAnswer(call -> call.getArgument(0));

		ExamStartResponse response = examWorkflowService.startExam(
				EMAIL, 30L, new ExamStartRequest(null, Boolean.TRUE, Instant.now()));

		assertThat(response.resumed()).isFalse();
		assertThat(response.sessionToken()).isNotNull();
		assertThat(response.questionIds()).hasSize(30);
	}

	/**
	 * The paper follows the exam's blueprint, not a constant.
	 *
	 * <p>This is the whole point of making the mix configurable, and it is the
	 * one assertion that would still pass if the server quietly kept building
	 * 30-question papers: the counts here are deliberately nothing like the
	 * 6/12/12 default.</p>
	 */
	@Test
	void startExam_drawsThePaperTheExamsBlueprintDescribes() {
		CertificationApplication application = startableApplication();
		application.getExam().setTotalQuestions(20);
		application.getExam().setLowSeverityPercentage(new BigDecimal("50.00"));
		application.getExam().setMediumSeverityPercentage(new BigDecimal("25.00"));
		application.getExam().setHighSeverityPercentage(new BigDecimal("25.00"));

		when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(application.getUser()));
		when(certificationApplicationRepository.findByIdAndUser(30L, application.getUser()))
				.thenReturn(Optional.of(application));
		when(examSessionRepository.findTopByCertificationApplicationOrderBySessionStartTimeDescIdDesc(application))
				.thenReturn(Optional.empty());
		stubQuestionPool();
		when(examSessionRepository.save(any(ExamSession.class))).thenAnswer(call -> call.getArgument(0));

		ExamStartResponse response = examWorkflowService.startExam(
				EMAIL, 30L, new ExamStartRequest(null, Boolean.TRUE, Instant.now()));

		assertThat(response.questionIds()).hasSize(20);
		assertThat(response.questionCount()).isEqualTo(20);

		/*
		 * stubQuestionPool numbers each severity's questions from
		 * severity.ordinal() * 100, so the id a question carries says which pool
		 * it was drawn from.
		 */
		Map<QuestionSeverity, Long> drawnPerSeverity = response.questionIds().stream()
				.collect(java.util.stream.Collectors.groupingBy(
						id -> QuestionSeverity.values()[(int) (id / 100)],
						java.util.stream.Collectors.counting()));

		assertThat(drawnPerSeverity).containsEntry(QuestionSeverity.LOW, 10L)
				.containsEntry(QuestionSeverity.MEDIUM, 5L)
				.containsEntry(QuestionSeverity.HIGH, 5L);
	}

	/** A severity given no share is not drawn from, and its pool is not required. */
	@Test
	void startExam_whenASeverityHasNoShare_drawsNoneOfIt() {
		CertificationApplication application = startableApplication();
		application.getExam().setTotalQuestions(10);
		application.getExam().setLowSeverityPercentage(BigDecimal.ZERO);
		application.getExam().setMediumSeverityPercentage(new BigDecimal("50.00"));
		application.getExam().setHighSeverityPercentage(new BigDecimal("50.00"));

		when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(application.getUser()));
		when(certificationApplicationRepository.findByIdAndUser(30L, application.getUser()))
				.thenReturn(Optional.of(application));
		when(examSessionRepository.findTopByCertificationApplicationOrderBySessionStartTimeDescIdDesc(application))
				.thenReturn(Optional.empty());
		stubQuestionPool(QuestionSeverity.MEDIUM, QuestionSeverity.HIGH);
		when(examSessionRepository.save(any(ExamSession.class))).thenAnswer(call -> call.getArgument(0));

		ExamStartResponse response = examWorkflowService.startExam(
				EMAIL, 30L, new ExamStartRequest(null, Boolean.TRUE, Instant.now()));

		assertThat(response.questionIds()).hasSize(10);
		assertThat(response.questionIds()).noneMatch(id -> id < 100);
		verify(questionRepository, never()).findByCertificationLevelAndSeverityInAndActiveTrue(
				CertificationLevel.L1, List.of(QuestionSeverity.LOW));
	}

	/**
	 * The window governs when an attempt may begin, not how long it may run. A
	 * candidate who started on time and lost their connection is rejoining the
	 * sitting they already paid for, however far past the slot the reconnection
	 * lands — the session's own clock is what limits them.
	 */
	@Test
	void startExam_whenRejoiningLiveSessionAfterWindowClosed_stillResumes() {
		CertificationApplication application = startableApplication();
		application.setScheduledExamTime(Instant.now().minus(3, ChronoUnit.HOURS));
		Question question = Question.builder().id(500L).questionCode("Q-1").questionText("?")
				.questionType("Single Choice").severity(QuestionSeverity.LOW).optionsJson("[\"a\",\"b\"]").build();

		ExamSession live = sessionFor(application, ExamStatus.IN_PROGRESS, Instant.now().minusSeconds(600));
		live.setSelectedQuestionIdsJson("[500]");

		when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(application.getUser()));
		when(certificationApplicationRepository.findByIdAndUser(30L, application.getUser()))
				.thenReturn(Optional.of(application));
		when(examSessionRepository.findTopByCertificationApplicationOrderBySessionStartTimeDescIdDesc(application))
				.thenReturn(Optional.of(live));
		when(questionRepository.findById(500L)).thenReturn(Optional.of(question));

		ExamStartResponse response = examWorkflowService.startExam(
				EMAIL, 30L, new ExamStartRequest(null, Boolean.TRUE, Instant.now()));

		assertThat(response.resumed()).isTrue();
		assertThat(response.sessionToken()).isEqualTo(live.getSessionToken());
	}

	/**
	 * The other half of the rule: a candidate who cannot make their slot moves
	 * it. Nothing about the application or the payment changes, and the window
	 * comes back with the new time.
	 */
	@Test
	void scheduleExam_beforeAnyAttempt_movesTheBookingAndItsWindow() {
		CertificationApplication application = startableApplication();
		Instant newSlot = Instant.now().plus(2, ChronoUnit.DAYS);

		when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(application.getUser()));
		when(certificationApplicationRepository.findByIdAndUser(30L, application.getUser()))
				.thenReturn(Optional.of(application));
		when(examSessionRepository.findTopByCertificationApplicationOrderBySessionStartTimeDescIdDesc(application))
				.thenReturn(Optional.empty());
		when(certificationApplicationRepository.save(any(CertificationApplication.class)))
				.thenAnswer(call -> call.getArgument(0));

		ExamWorkflowApplicationResponse response = examWorkflowService.scheduleExam(
				EMAIL, 30L, new WorkflowExamScheduleRequest(newSlot));

		assertThat(response.scheduledExamTime()).isEqualTo(newSlot);
		assertThat(response.examWindowStart()).isEqualTo(newSlot.minus(10, ChronoUnit.MINUTES));
		assertThat(response.examWindowEnd()).isEqualTo(newSlot.plus(10, ChronoUnit.MINUTES));
		assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
	}

	/** Booking a slot that could never be attended is refused at the source. */
	@Test
	void scheduleExam_whenSlotWindowHasAlreadyClosed_isRefused() {
		CertificationApplication application = startableApplication();

		when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(application.getUser()));
		when(certificationApplicationRepository.findByIdAndUser(30L, application.getUser()))
				.thenReturn(Optional.of(application));
		when(examSessionRepository.findTopByCertificationApplicationOrderBySessionStartTimeDescIdDesc(application))
				.thenReturn(Optional.empty());

		BusinessException ex = assertThrows(
				BusinessException.class,
				() -> examWorkflowService.scheduleExam(
						EMAIL,
						30L,
						new WorkflowExamScheduleRequest(Instant.now().minus(30, ChronoUnit.MINUTES))));

		assertThat(ex.getMessage()).contains("already passed");
		verify(certificationApplicationRepository, never()).save(any(CertificationApplication.class));
	}

	/** Enough questions at every severity for one full paper. */
	private void stubQuestionPool() {
		stubQuestionPool(QuestionSeverity.values());
	}

	/**
	 * Enough questions at the named severities, and none stubbed at the others.
	 *
	 * <p>Strict stubbing then does half the asserting: a paper that draws from a
	 * severity it was not given a share of fails on the missing stub rather than
	 * quietly passing.</p>
	 */
	private void stubQuestionPool(QuestionSeverity... severities) {
		for (QuestionSeverity severity : severities) {
			List<Question> pool = new ArrayList<>();
			for (int i = 0; i < 12; i++) {
				pool.add(Question.builder()
						.id((long) (severity.ordinal() * 100 + i))
						.questionCode("Q-" + severity + "-" + i)
						.questionText("?")
						.questionType("Single Choice")
						.severity(severity)
						.optionsJson("[\"a\",\"b\"]")
						.build());
			}
			when(questionRepository.findByCertificationLevelAndSeverityInAndActiveTrue(
					CertificationLevel.L1, List.of(severity))).thenReturn(pool);
		}
	}

	private CertificationApplication startableApplication() {
		User user = User.builder().id(10L).email(EMAIL).build();
		Exam exam = Exam.builder().id(20L).examCode("EX-L1")
				.certificationLevel(CertificationLevel.L1).durationMinutes(60).build();
		return CertificationApplication.builder()
				.id(30L)
				.user(user)
				.exam(exam)
				.certificationLevel(CertificationLevel.L1)
				.applicationStatus(CertificationApplicationStatus.IN_PROGRESS)
				.paymentStatus(PaymentStatus.SUCCESS)
				.scheduledExamTime(Instant.now().minusSeconds(60))
				.build();
	}

	private ExamSession sessionFor(CertificationApplication application, ExamStatus status, Instant startedAt) {
		return ExamSession.builder()
				.id(24L)
				.sessionToken(UUID.randomUUID())
				.user(application.getUser())
				.exam(application.getExam())
				.certificationApplication(application)
				.sessionStartTime(startedAt)
				.sessionStatus(status)
				.build();
	}
}
