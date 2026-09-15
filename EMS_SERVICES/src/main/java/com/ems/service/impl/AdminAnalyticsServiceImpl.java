package com.ems.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ems.dto.response.AdminAnalyticsResponse;
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
import com.ems.service.AdminAnalyticsService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Tallies the board figures in memory from narrow projections.
 *
 * <p>Month bucketing is done here rather than in SQL because it has to happen
 * in the admin's time zone, and date truncation by zone is spelled differently
 * on PostgreSQL and H2. The projections carry only the columns counted, so a
 * full history stays cheap to read.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
@Transactional(readOnly = true)
public class AdminAnalyticsServiceImpl implements AdminAnalyticsService {

	/** The longest trend the overview draws; three years of months is already a wide chart. */
	static final int MAX_MONTHS = 36;

	private static final String DEFAULT_CURRENCY = "INR";

	private final PaymentRepository paymentRepository;
	private final UserRepository userRepository;
	private final CertificationRepository certificationRepository;
	private final CertificationApplicationRepository certificationApplicationRepository;
	private final ExamSessionRepository examSessionRepository;
	private final ExamAttemptRepository examAttemptRepository;
	private final ExamRepository examRepository;
	private final QuestionRepository questionRepository;
	private final ViolationRepository violationRepository;

	@Override
	public AdminAnalyticsResponse getBoardAnalytics(int months, ZoneId zone) {
		return buildAnalytics(Math.max(1, Math.min(months, MAX_MONTHS)), zone, Instant.now());
	}

	AdminAnalyticsResponse buildAnalytics(int months, ZoneId zone, Instant now) {
		YearMonth currentMonth = YearMonth.from(now.atZone(zone));
		Map<YearMonth, MonthTally> tallies = new LinkedHashMap<>();
		for (YearMonth month = currentMonth.minusMonths(months - 1L); !month.isAfter(currentMonth);
				month = month.plusMonths(1)) {
			tallies.put(month, new MonthTally());
		}

		// — Revenue
		List<Object[]> payments = paymentRepository.findSuccessfulPaymentFigures();
		String currency = primaryCurrency(payments);
		BigDecimal revenue = BigDecimal.ZERO;
		long paidTransactions = 0;
		BigDecimal nonLiveAmount = BigDecimal.ZERO;
		long nonLiveTransactions = 0;
		long otherCurrencyPayments = 0;
		for (Object[] row : payments) {
			BigDecimal amount = (BigDecimal) row[0];
			if (amount == null) {
				continue;
			}
			// Amounts in different currencies cannot be added into one figure.
			if (!currency.equalsIgnoreCase(trimToEmpty((String) row[1]))) {
				otherCurrencyPayments++;
				continue;
			}
			PaymentGatewayMode mode = (PaymentGatewayMode) row[2];
			if (mode == PaymentGatewayMode.TEST || mode == PaymentGatewayMode.SIMULATED) {
				nonLiveAmount = nonLiveAmount.add(amount);
				nonLiveTransactions++;
				continue;
			}
			revenue = revenue.add(amount);
			paidTransactions++;

			// A payment belongs to the month it was paid in; the opening time only
			// stands in for payments that never recorded one.
			Instant paidAt = row[3] != null ? (Instant) row[3] : fromAuditClock((LocalDateTime) row[4]);
			MonthTally tally = tallyFor(tallies, paidAt, zone);
			if (tally != null) {
				tally.revenue = tally.revenue.add(amount);
				tally.paidTransactions++;
			}
		}
		if (otherCurrencyPayments > 0) {
			log.warn("Board revenue leaves out {} successful payment(s) not in {}", otherCurrencyPayments, currency);
		}

		// — Candidates
		List<Instant> registeredAt = userRepository.findCandidateRegistrationTimes().stream()
				.filter(Objects::nonNull)
				.map(AdminAnalyticsServiceImpl::fromAuditClock)
				.sorted()
				.toList();
		for (Instant at : registeredAt) {
			MonthTally tally = tallyFor(tallies, at, zone);
			if (tally != null) {
				tally.newCandidates++;
			}
		}

		// — Certifications
		Set<Long> certifiedCandidates = new HashSet<>();
		Map<CertificationLevel, long[]> issuedAndActiveByLevel = new EnumMap<>(CertificationLevel.class);
		for (CertificationLevel level : CertificationLevel.values()) {
			issuedAndActiveByLevel.put(level, new long[2]);
		}
		long certificationsIssued = 0;
		long activeCertifications = 0;
		for (Object[] row : certificationRepository.findIssuanceFigures()) {
			Long userId = (Long) row[0];
			CertificationLevel level = (CertificationLevel) row[1];
			CertificationStatus status = (CertificationStatus) row[2];
			LocalDate issueDate = (LocalDate) row[3];
			boolean active = status == CertificationStatus.ACTIVE;

			certificationsIssued++;
			if (active) {
				activeCertifications++;
			}
			// A revoked certification was issued, but its holder is not certified by it.
			if (userId != null && status != CertificationStatus.REVOKED) {
				certifiedCandidates.add(userId);
			}
			if (level != null) {
				long[] figures = issuedAndActiveByLevel.get(level);
				figures[0]++;
				if (active) {
					figures[1]++;
				}
			}
			// A date has no zone: it falls in its own calendar month wherever it is read.
			MonthTally tally = issueDate == null ? null : tallies.get(YearMonth.from(issueDate));
			if (tally != null) {
				tally.certificationsIssued++;
			}
		}

		// — Attempts
		long attemptsScored = 0;
		long attemptsPassed = 0;
		for (Object[] row : examAttemptRepository.findSubmissionOutcomes()) {
			boolean passed = row[1] == ResultStatus.PASS;
			attemptsScored++;
			if (passed) {
				attemptsPassed++;
			}
			MonthTally tally = tallyFor(tallies, (Instant) row[0], zone);
			if (tally != null) {
				tally.attemptsScored++;
				if (passed) {
					tally.attemptsPassed++;
				}
			}
		}
		BigDecimal passRate = attemptsScored == 0
				? null
				: BigDecimal.valueOf(attemptsPassed * 100)
						.divide(BigDecimal.valueOf(attemptsScored), 1, RoundingMode.HALF_UP);

		List<AdminAnalyticsResponse.MonthFigures> monthFigures = new ArrayList<>(tallies.size());
		int registeredByMonthEnd = 0;
		for (Map.Entry<YearMonth, MonthTally> entry : tallies.entrySet()) {
			Instant monthEnd = entry.getKey().plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
			while (registeredByMonthEnd < registeredAt.size()
					&& registeredAt.get(registeredByMonthEnd).isBefore(monthEnd)) {
				registeredByMonthEnd++;
			}
			MonthTally tally = entry.getValue();
			monthFigures.add(new AdminAnalyticsResponse.MonthFigures(
					entry.getKey().toString(),
					tally.revenue,
					tally.paidTransactions,
					tally.newCandidates,
					registeredByMonthEnd,
					tally.certificationsIssued,
					tally.attemptsScored,
					tally.attemptsPassed));
		}

		List<AdminAnalyticsResponse.LevelFigures> levels = issuedAndActiveByLevel.entrySet().stream()
				.map(entry -> new AdminAnalyticsResponse.LevelFigures(
						entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
				.toList();

		return new AdminAnalyticsResponse(
				now,
				zone.getId(),
				currency,
				new AdminAnalyticsResponse.Totals(
						revenue,
						paidTransactions,
						nonLiveAmount,
						nonLiveTransactions,
						registeredAt.size(),
						certifiedCandidates.size(),
						certificationsIssued,
						activeCertifications,
						attemptsScored,
						attemptsPassed,
						passRate,
						examRepository.count(),
						questionRepository.count(),
						violationRepository.count()),
				new AdminAnalyticsResponse.Funnel(
						registeredAt.size(),
						certificationApplicationRepository.countDistinctApplicants(),
						certificationApplicationRepository.countDistinctPaidApplicants(),
						examSessionRepository.countDistinctCandidates(),
						certifiedCandidates.size()),
				levels,
				monthFigures);
	}

	/** The currency most successful payments were taken in; the revenue figure is in it. */
	private static String primaryCurrency(List<Object[]> payments) {
		return payments.stream()
				.map(row -> trimToEmpty((String) row[1]))
				.filter(currency -> !currency.isEmpty())
				.collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
				.entrySet().stream()
				.max(Map.Entry.comparingByValue())
				.map(Map.Entry::getKey)
				.orElse(DEFAULT_CURRENCY);
	}

	private static MonthTally tallyFor(Map<YearMonth, MonthTally> tallies, Instant at, ZoneId zone) {
		return at == null ? null : tallies.get(YearMonth.from(at.atZone(zone)));
	}

	private static String trimToEmpty(String value) {
		return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
	}

	/**
	 * An audit timestamp as an instant. {@code created_date} is stamped with
	 * {@code LocalDateTime.now()} in the server's zone; see
	 * {@link AdminPortalServiceImpl} for the same conversion on the payment list.
	 */
	private static Instant fromAuditClock(LocalDateTime auditTimestamp) {
		return auditTimestamp == null ? null : auditTimestamp.atZone(ZoneId.systemDefault()).toInstant();
	}

	/** One month's running counts while the projections are walked. */
	private static final class MonthTally {
		private BigDecimal revenue = BigDecimal.ZERO;
		private long paidTransactions;
		private long newCandidates;
		private long certificationsIssued;
		private long attemptsScored;
		private long attemptsPassed;
	}
}
