package com.ems.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.ems.dto.response.AdminExamBookingDetailResponse;
import com.ems.dto.response.AdminExamBookingDetailResponse.AnswerSource;
import com.ems.dto.response.AdminExamBookingDetailResponse.AnswerState;
import com.ems.dto.response.AdminExamBookingDetailResponse.QuestionLine;
import com.ems.dto.response.AdminExamBookingResponse;
import com.ems.entity.CertificationApplication;
import com.ems.entity.Exam;
import com.ems.entity.ExamAttempt;
import com.ems.entity.ExamSession;
import com.ems.entity.Question;
import com.ems.entity.User;
import com.ems.enums.CertificationApplicationStatus;
import com.ems.enums.CertificationLevel;
import com.ems.enums.ExamStatus;
import com.ems.enums.ExamTrackerStage;
import com.ems.enums.PaymentStatus;
import com.ems.enums.QuestionSeverity;
import com.ems.enums.ResultStatus;
import com.ems.repository.CertificateRepository;
import com.ems.repository.CertificationApplicationRepository;
import com.ems.repository.ExamAttemptRepository;
import com.ems.repository.ExamSessionRepository;
import com.ems.repository.PaymentRepository;
import com.ems.repository.PolicyAcknowledgmentRepository;
import com.ems.repository.QuestionRepository;
import com.ems.repository.ViolationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Covers the two judgements the exam tracker makes on its own: which stage an
 * application has reached, and the verdict on each answer of an attempt,
 * including attempts scored before their answers were kept.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminExamTrackerServiceImplTest {

	@Mock
	private CertificationApplicationRepository certificationApplicationRepository;

	@Mock
	private ExamSessionRepository examSessionRepository;

	@Mock
	private ExamAttemptRepository examAttemptRepository;

	@Mock
	private QuestionRepository questionRepository;

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private ViolationRepository violationRepository;

	@Mock
	private CertificateRepository certificateRepository;

	@Mock
	private PolicyAcknowledgmentRepository policyAcknowledgmentRepository;

	private AdminExamTrackerServiceImpl service;

	private User user;
	private Exam exam;
	private Instant now;

	@BeforeEach
	void setUp() {
		service = new AdminExamTrackerServiceImpl(
				certificationApplicationRepository,
				examSessionRepository,
				examAttemptRepository,
				questionRepository,
				paymentRepository,
				violationRepository,
				certificateRepository,
				policyAcknowledgmentRepository,
				new ObjectMapper());

		user = User.builder().id(1L).userId("EMS-1").firstName("Asha").lastName("Rao").email("asha@example.com").build();
		exam = Exam.builder()
				.id(2L)
				.examCode("EX-L1")
				.examName("Level 1")
				.certificationLevel(CertificationLevel.L1)
				.durationMinutes(60)
				.passingPercentage(new BigDecimal("60"))
				.build();
		now = Instant.now();
	}

	// — Stage

	@Test
	void stage_paidWithNoSlot_isAwaitingSlot() {
		assertThat(AdminExamTrackerServiceImpl.stageOf(application(40L, null), null, null, now))
				.isEqualTo(ExamTrackerStage.AWAITING_SLOT);
	}

	/**
	 * Missed means the start window has closed, not that the booked minute has
	 * passed: a candidate five minutes late can still start, and is still upcoming.
	 */
	@Test
	void stage_isUpcomingUntilTheStartWindowCloses_thenMissed() {
		assertThat(stage(application(40L, now.plus(Duration.ofHours(2))))).isEqualTo(ExamTrackerStage.UPCOMING);
		assertThat(stage(application(40L, now.minus(Duration.ofMinutes(5))))).isEqualTo(ExamTrackerStage.UPCOMING);
		assertThat(stage(application(40L, now.minus(Duration.ofMinutes(11))))).isEqualTo(ExamTrackerStage.MISSED);
	}

	@Test
	void stage_followsTheSessionRatherThanTheClock() {
		CertificationApplication application = application(40L, now.minus(Duration.ofMinutes(45)));

		assertThat(AdminExamTrackerServiceImpl.stageOf(application, session(50L, ExamStatus.IN_PROGRESS), null, now))
				.isEqualTo(ExamTrackerStage.LIVE);
		assertThat(AdminExamTrackerServiceImpl.stageOf(application, session(50L, ExamStatus.INVALIDATED), null, now))
				.isEqualTo(ExamTrackerStage.TERMINATED);
		assertThat(AdminExamTrackerServiceImpl.stageOf(
				application, session(50L, ExamStatus.PASSED), ExamAttempt.builder().build(), now))
				.isEqualTo(ExamTrackerStage.COMPLETED);
	}

	@Test
	void stage_refundedOrRejectedWithoutASitting_isClosed() {
		CertificationApplication refunded = application(40L, now.plus(Duration.ofDays(1)));
		refunded.setPaymentStatus(PaymentStatus.REFUNDED);
		CertificationApplication rejected = application(41L, null);
		rejected.setApplicationStatus(CertificationApplicationStatus.REJECTED);

		assertThat(stage(refunded)).isEqualTo(ExamTrackerStage.CLOSED);
		assertThat(stage(rejected)).isEqualTo(ExamTrackerStage.CLOSED);
	}

	// — List

	@Test
	void list_readsEachApplicationsLatestSession_andPutsTheSoonestSlotFirst() {
		CertificationApplication sittingNow = application(41L, now.minus(Duration.ofMinutes(10)));
		CertificationApplication later = application(40L, now.plus(Duration.ofDays(3)));
		CertificationApplication unbooked = application(42L, null);

		ExamSession earlier = session(60L, ExamStatus.INVALIDATED);
		earlier.setCertificationApplication(sittingNow);
		earlier.setSessionStartTime(now.minus(Duration.ofDays(1)));
		ExamSession latest = session(61L, ExamStatus.IN_PROGRESS);
		latest.setCertificationApplication(sittingNow);
		latest.setSessionStartTime(now.minus(Duration.ofMinutes(8)));
		latest.setSelectedQuestionIdsJson("[1,2,3,4]");
		latest.setAnswersDraftJson("{\"1\":[\"A\"],\"2\":[\" \"],\"3\":[\"C\"]}");

		when(certificationApplicationRepository.findTrackedWithCandidateAndExam())
				.thenReturn(List.of(unbooked, later, sittingNow));
		when(examSessionRepository.findByCertificationApplicationIn(anyCollection()))
				.thenReturn(List.of(latest, earlier));
		when(examAttemptRepository.findByExamSessionIn(anyCollection())).thenReturn(List.of());

		List<AdminExamBookingResponse> bookings = service.listBookings();

		assertThat(bookings).extracting(AdminExamBookingResponse::applicationId).containsExactly(41L, 40L, 42L);
		AdminExamBookingResponse live = bookings.get(0);
		assertThat(live.stage()).isEqualTo(ExamTrackerStage.LIVE);
		assertThat(live.session().sessionId()).isEqualTo(61L);
		assertThat(live.session().questionsAssigned()).isEqualTo(4);
		// A blank autosaved answer is not an answer.
		assertThat(live.session().questionsAnswered()).isEqualTo(2);
	}

	// — Detail

	@Test
	void detail_judgesEachQuestionOnTheSubmission_inPaperOrder() {
		CertificationApplication application = application(40L, now.minus(Duration.ofHours(2)));
		ExamSession session = session(50L, ExamStatus.FAILED);
		session.setSelectedQuestionIdsJson("[30,10,20]");
		session.setMarkedForReviewJson("[20]");
		// The autosave disagrees with the submission; the submission wins.
		session.setAnswersDraftJson("{\"10\":[\"A\"],\"20\":[\"B\"]}");
		ExamAttempt attempt = attempt(session, "{\"10\":[\"b\"],\"20\":[\"A\"],\"30\":[]}");

		stubDetail(application, session, attempt);

		AdminExamBookingDetailResponse detail = service.getBooking(40L);

		assertThat(detail.answerSource()).isEqualTo(AnswerSource.SUBMISSION);
		assertThat(detail.booking().stage()).isEqualTo(ExamTrackerStage.COMPLETED);
		assertThat(detail.questions()).extracting(QuestionLine::questionId).containsExactly(30L, 10L, 20L);
		assertThat(detail.questions()).extracting(QuestionLine::state)
				.containsExactly(AnswerState.UNANSWERED, AnswerState.CORRECT, AnswerState.WRONG);
		assertThat(detail.questions()).extracting(QuestionLine::markedForReview).containsExactly(false, false, true);
	}

	@Test
	void detail_ofAnAttemptScoredBeforeAnswersWereKept_fallsBackToTheAutosave() {
		CertificationApplication application = application(40L, now.minus(Duration.ofHours(2)));
		ExamSession session = session(50L, ExamStatus.FAILED);
		session.setSelectedQuestionIdsJson("[10,20]");
		session.setAnswersDraftJson("{\"10\":[\"B\"]}");
		ExamAttempt attempt = attempt(session, null);

		stubDetail(application, session, attempt);

		AdminExamBookingDetailResponse detail = service.getBooking(40L);

		assertThat(detail.answerSource()).isEqualTo(AnswerSource.AUTOSAVE);
		assertThat(detail.questions()).extracting(QuestionLine::state)
				.containsExactly(AnswerState.CORRECT, AnswerState.UNANSWERED);
	}

	/** With a result but no answers anywhere, "unanswered" would be a claim nobody can back. */
	@Test
	void detail_ofAnAttemptWithNoAnswersOnRecord_judgesNoQuestion() {
		CertificationApplication application = application(40L, now.minus(Duration.ofHours(2)));
		ExamSession session = session(50L, ExamStatus.PASSED);
		session.setSelectedQuestionIdsJson("[10,20]");
		ExamAttempt attempt = attempt(session, null);

		stubDetail(application, session, attempt);

		AdminExamBookingDetailResponse detail = service.getBooking(40L);

		assertThat(detail.answerSource()).isEqualTo(AnswerSource.NONE);
		assertThat(detail.questions()).extracting(QuestionLine::state)
				.containsOnly(AnswerState.NOT_JUDGED);
	}

	@Test
	void detail_beforeTheAttemptStarts_hasNoPaperYet() {
		CertificationApplication application = application(40L, now.plus(Duration.ofDays(2)));

		stubDetail(application, null, null);

		AdminExamBookingDetailResponse detail = service.getBooking(40L);

		assertThat(detail.booking().stage()).isEqualTo(ExamTrackerStage.UPCOMING);
		assertThat(detail.booking().slot().end()).isEqualTo(application.getScheduledExamTime().plus(Duration.ofHours(1)));
		assertThat(detail.questions()).isEmpty();
		assertThat(detail.answerSource()).isEqualTo(AnswerSource.NONE);
	}

	private ExamTrackerStage stage(CertificationApplication application) {
		return AdminExamTrackerServiceImpl.stageOf(application, null, null, now);
	}

	private void stubDetail(CertificationApplication application, ExamSession session, ExamAttempt attempt) {
		when(certificationApplicationRepository.findByIdWithAllRelationships(application.getId()))
				.thenReturn(Optional.of(application));
		when(examSessionRepository.findTopByCertificationApplicationOrderBySessionStartTimeDescIdDesc(application))
				.thenReturn(Optional.ofNullable(session));
		when(paymentRepository.findTopByCertificationApplicationIdOrderByCreatedDateDesc(application.getId()))
				.thenReturn(Optional.empty());
		when(questionRepository.findAllById(anyIterable())).thenReturn(List.of(
				question(10L), question(20L), question(30L)));
		if (session != null) {
			when(examAttemptRepository.findByExamSession(session)).thenReturn(Optional.ofNullable(attempt));
			when(violationRepository.findByExamSessionOrderByDetectedAtDesc(session)).thenReturn(List.of());
			when(policyAcknowledgmentRepository.findLatestByUserAndExamSession(user, session.getId()))
					.thenReturn(Optional.empty());
		}
		if (attempt != null) {
			when(certificateRepository.findByExamAttempt(attempt)).thenReturn(Optional.empty());
		}
	}

	private CertificationApplication application(Long id, Instant slot) {
		return CertificationApplication.builder()
				.id(id)
				.user(user)
				.exam(exam)
				.certificationLevel(CertificationLevel.L1)
				.applicationStatus(CertificationApplicationStatus.IN_PROGRESS)
				.paymentStatus(PaymentStatus.SUCCESS)
				.appliedOn(LocalDate.of(2026, 9, 1))
				.scheduledExamTime(slot)
				.build();
	}

	private ExamSession session(Long id, ExamStatus status) {
		return ExamSession.builder()
				.id(id)
				.user(user)
				.exam(exam)
				.sessionStatus(status)
				.sessionStartTime(now.minus(Duration.ofMinutes(20)))
				.build();
	}

	private static ExamAttempt attempt(ExamSession session, String submittedAnswersJson) {
		return ExamAttempt.builder()
				.id(70L)
				.examSession(session)
				.totalQuestions(3)
				.attemptedQuestions(2)
				.correctAnswers(1)
				.wrongAnswers(1)
				.obtainedMarks(BigDecimal.ONE)
				.percentage(new BigDecimal("33.33"))
				.resultStatus(ResultStatus.FAIL)
				.submittedAt(Instant.now())
				.submittedAnswersJson(submittedAnswersJson)
				.build();
	}

	/** Every test question offers A and B, and B is right. */
	private static Question question(Long id) {
		return Question.builder()
				.id(id)
				.questionCode("Q-" + id)
				.certificationLevel(CertificationLevel.L1)
				.questionCategory("THEORY")
				.questionType("SINGLE_CHOICE")
				.questionText("Question " + id)
				.optionsJson("[\"A\",\"B\"]")
				.correctOptionsJson("[\"B\"]")
				.severity(QuestionSeverity.LOW)
				.marks(BigDecimal.ONE)
				.build();
	}
}
