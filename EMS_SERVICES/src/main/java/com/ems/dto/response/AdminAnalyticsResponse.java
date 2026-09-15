package com.ems.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.ems.enums.CertificationLevel;

/**
 * The admin overview's board figures: all-time totals, the candidate funnel,
 * and month-by-month trends for revenue, candidates and certifications.
 *
 * <p>Months are calendar months in {@code timeZone}, oldest first, and always
 * run up to and including the current one, so a month with no activity is a
 * zero rather than a gap in the chart.</p>
 */
public record AdminAnalyticsResponse(
        Instant generatedAt,
        String timeZone,
        String currency,
        Totals totals,
        Funnel funnel,
        List<LevelFigures> levels,
        List<MonthFigures> months) {

    /**
     * All-time figures.
     *
     * <p>{@code revenue} is live money only. Payments taken on test keys or
     * through a simulated checkout moved no money, and are reported apart in
     * {@code nonLiveAmount} so a figure shown to a board is never inflated by
     * them. Payments recorded before the gateway mode was tracked count as
     * revenue: they predate test keys being told apart at all.</p>
     *
     * <p>{@code passRatePercentage} is null until an attempt has been scored.</p>
     */
    public record Totals(
            BigDecimal revenue,
            long paidTransactions,
            BigDecimal nonLiveAmount,
            long nonLiveTransactions,
            long registeredCandidates,
            long certifiedCandidates,
            long certificationsIssued,
            long activeCertifications,
            long attemptsScored,
            long attemptsPassed,
            BigDecimal passRatePercentage,
            long exams,
            long questions,
            long violations) {
    }

    /** How many different candidates have reached each step, all time. */
    public record Funnel(long registered, long applied, long paid, long sat, long certified) {
    }

    public record LevelFigures(CertificationLevel level, long issued, long active) {
    }

    /**
     * One calendar month. {@code month} is {@code yyyy-MM}; {@code totalCandidates}
     * is the running total at the month's end (or now, for the current month).
     */
    public record MonthFigures(
            String month,
            BigDecimal revenue,
            long paidTransactions,
            long newCandidates,
            long totalCandidates,
            long certificationsIssued,
            long attemptsScored,
            long attemptsPassed) {
    }
}
