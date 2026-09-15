package com.ems.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.ems.dto.response.AdminAnalyticsResponse;
import com.ems.dto.response.AdminAnalyticsResponse.LevelFigures;
import com.ems.dto.response.AdminAnalyticsResponse.MonthFigures;
import com.ems.enums.CertificationLevel;
import com.ems.enums.CertificationStatus;
import com.ems.enums.PaymentGatewayMode;
import com.ems.enums.ResultStatus;
import com.ems.repository.CertificationApplicationRepository;
import com.ems.repository.CertificationRepository;
import com.ems.repository.ExamAttemptRepository;
import com.ems.repository.ExamRepository;
import com.ems.repository.ExamSessionRepository;
import com.ems.repository.PaymentRepository;
import com.ems.repository.QuestionRepository;
import com.ems.repository.UserRepository;
import com.ems.repository.ViolationRepository;

/**
 * The board figures: live money only, calendar months in the admin's zone, and
 * running totals that remember everyone who came before the chart's range.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminAnalyticsServiceImplTest {

	private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
	private static final Instant NOW = Instant.parse("2026-09-15T06:00:00Z");

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private UserRepository userRepository;

	@Mock
	private CertificationRepository certificationRepository;

	@Mock
	private CertificationApplicationRepository certificationApplicationRepository;

	@Mock
	private ExamSessionRepository examSessionRepository;

	@Mock
	private ExamAttemptRepository examAttemptRepository;

	@Mock
	private ExamRepository examRepository;

	@Mock
	private QuestionRepository questionRepository;

	@Mock
	private ViolationRepository violationRepository;

	private AdminAnalyticsServiceImpl service;

	@BeforeEach
	void setUp() {
		service = new AdminAnalyticsServiceImpl(
				paymentRepository,
				userRepository,
				certificationRepository,
				certificationApplicationRepository,
				examSessionRepository,
				examAttemptRepository,
				examRepository,
				questionRepository,
				violationRepository);

		when(paymentRepository.findSuccessfulPaymentFigures()).thenReturn(List.of());
		when(userRepository.findCandidateRegistrationTimes()).thenReturn(List.of());
		when(certificationRepository.findIssuanceFigures()).thenReturn(List.of());
		when(examAttemptRepository.findSubmissionOutcomes()).thenReturn(List.of());
	}

	@Test
	void revenue_countsLiveMoneyOnly_andReportsTestAndSimulatedPaymentsApart() {
		when(paymentRepository.findSuccessfulPaymentFigures()).thenReturn(rows(
				payment("1500.00", PaymentGatewayMode.LIVE, "2026-09-02T04:00:00Z"),
				// Recorded before the gateway mode was tracked: counts as revenue.
				payment("1500.00", null, "2026-08-10T04:00:00Z"),
				payment("999.00", PaymentGatewayMode.TEST, "2026-09-03T04:00:00Z"),
				payment("999.00", PaymentGatewayMode.SIMULATED, "2026-09-04T04:00:00Z")));

		AdminAnalyticsResponse analytics = service.buildAnalytics(3, KOLKATA, NOW);

		assertThat(analytics.totals().revenue()).isEqualByComparingTo("3000.00");
		assertThat(analytics.totals().paidTransactions()).isEqualTo(2);
		assertThat(analytics.totals().nonLiveAmount()).isEqualByComparingTo("1998.00");
		assertThat(analytics.totals().nonLiveTransactions()).isEqualTo(2);
		assertThat(analytics.months()).extracting(MonthFigures::month).containsExactly("2026-07", "2026-08", "2026-09");
		assertThat(month(analytics, "2026-07").revenue()).isEqualByComparingTo("0");
		assertThat(month(analytics, "2026-08").revenue()).isEqualByComparingTo("1500.00");
		assertThat(month(analytics, "2026-09").revenue()).isEqualByComparingTo("1500.00");
	}

	@Test
	void months_areCalendarMonthsInTheAdminsZone() {
		// 20:00 UTC on 31 August is already 1 September in India.
		when(paymentRepository.findSuccessfulPaymentFigures()).thenReturn(rows(
				payment("500.00", PaymentGatewayMode.LIVE, "2026-08-31T20:00:00Z")));

		assertThat(month(service.buildAnalytics(2, KOLKATA, NOW), "2026-09").revenue()).isEqualByComparingTo("500.00");
		assertThat(month(service.buildAnalytics(2, ZoneOffset.UTC, NOW), "2026-08").revenue()).isEqualByComparingTo("500.00");
	}

	@Test
	void candidates_runningTotalIncludesThoseWhoRegisteredBeforeTheRange() {
		when(userRepository.findCandidateRegistrationTimes()).thenReturn(List.of(
				auditClock("2025-01-10T06:00:00Z"),
				auditClock("2026-08-10T06:00:00Z"),
				auditClock("2026-09-01T06:00:00Z"),
				auditClock("2026-09-12T06:00:00Z")));

		AdminAnalyticsResponse analytics = service.buildAnalytics(2, KOLKATA, NOW);

		assertThat(analytics.months()).extracting(MonthFigures::newCandidates).containsExactly(1L, 2L);
		assertThat(analytics.months()).extracting(MonthFigures::totalCandidates).containsExactly(2L, 4L);
		assertThat(analytics.totals().registeredCandidates()).isEqualTo(4);
		assertThat(analytics.funnel().registered()).isEqualTo(4);
	}

	@Test
	void certifications_andPassRate() {
		when(certificationRepository.findIssuanceFigures()).thenReturn(rows(
				new Object[] { 1L, CertificationLevel.L1, CertificationStatus.ACTIVE, LocalDate.of(2026, 9, 2) },
				new Object[] { 1L, CertificationLevel.L2, CertificationStatus.EXPIRED, LocalDate.of(2025, 3, 2) },
				new Object[] { 2L, CertificationLevel.L1, CertificationStatus.REVOKED, LocalDate.of(2026, 9, 5) }));
		when(examAttemptRepository.findSubmissionOutcomes()).thenReturn(rows(
				new Object[] { Instant.parse("2026-09-02T06:00:00Z"), ResultStatus.PASS },
				new Object[] { Instant.parse("2026-09-03T06:00:00Z"), ResultStatus.FAIL },
				new Object[] { Instant.parse("2026-09-04T06:00:00Z"), ResultStatus.FAIL }));

		AdminAnalyticsResponse analytics = service.buildAnalytics(1, KOLKATA, NOW);

		assertThat(analytics.totals().certificationsIssued()).isEqualTo(3);
		assertThat(analytics.totals().activeCertifications()).isEqualTo(1);
		// A revoked certification was issued, but does not make its holder certified.
		assertThat(analytics.totals().certifiedCandidates()).isEqualTo(1);
		assertThat(analytics.levels()).extracting(LevelFigures::issued).containsExactly(2L, 1L, 0L);
		assertThat(analytics.months().get(0).certificationsIssued()).isEqualTo(2);
		assertThat(analytics.totals().passRatePercentage()).isEqualByComparingTo("33.3");
	}

	@Test
	void passRate_isAbsentUntilAnAttemptIsScored() {
		assertThat(service.buildAnalytics(12, KOLKATA, NOW).totals().passRatePercentage()).isNull();
	}

	@Test
	void months_areClampedToTheSupportedRange() {
		assertThat(service.getBoardAnalytics(0, KOLKATA).months()).hasSize(1);
		assertThat(service.getBoardAnalytics(500, KOLKATA).months()).hasSize(AdminAnalyticsServiceImpl.MAX_MONTHS);
	}

	/** Projection rows; not generic, so a single row is not mistaken for the whole varargs array. */
	private static List<Object[]> rows(Object[]... rows) {
		return Arrays.asList(rows);
	}

	private static Object[] payment(String amount, PaymentGatewayMode mode, String paidAt) {
		return new Object[] { new BigDecimal(amount), "INR", mode, Instant.parse(paidAt), null };
	}

	/** An instant as {@code created_date} holds it: wall time in the server's zone. */
	private static LocalDateTime auditClock(String instant) {
		return LocalDateTime.ofInstant(Instant.parse(instant), ZoneId.systemDefault());
	}

	private static MonthFigures month(AdminAnalyticsResponse analytics, String month) {
		return analytics.months().stream()
				.filter(figures -> figures.month().equals(month))
				.findFirst()
				.orElseThrow();
	}
}
