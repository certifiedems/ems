package com.ems.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.ems.entity.Exam;
import com.ems.enums.QuestionSeverity;

/**
 * How many questions a paper draws, and how many of them come from each
 * severity.
 *
 * <p>An admin sets the mix as three percentages because that is how the
 * decision is made — "a third easy, a third hard" — and because percentages
 * survive a change to the question total, where counts would silently stop
 * adding up. A paper, though, is built out of whole questions, so the
 * percentages have to become counts exactly once, in one place, or the number
 * of questions the candidate is shown and the number the pool is asked for
 * drift apart.</p>
 *
 * <p>The counts always sum to the total. Percentages rarely divide a total
 * cleanly — 30/40/30 of 25 is 7.5/10/7.5 — so the leftover after flooring goes
 * to the severities with the largest fractional part, ties broken by severity
 * order. That keeps every count within one question of its share and keeps the
 * result the same on every call, which matters because the same blueprint is
 * read when the paper is built and when the admin screen previews it.</p>
 */
public record ExamQuestionBlueprint(
        int totalQuestions,
        BigDecimal lowSeverityPercentage,
        BigDecimal mediumSeverityPercentage,
        BigDecimal highSeverityPercentage) {

    /**
     * What an exam gets when it says nothing — the 30 questions at 6/12/12 that
     * were hard-coded before the blueprint was the admin's to set.
     */
    public static final int DEFAULT_TOTAL_QUESTIONS = 30;
    public static final BigDecimal DEFAULT_LOW_PERCENTAGE = new BigDecimal("20.00");
    public static final BigDecimal DEFAULT_MEDIUM_PERCENTAGE = new BigDecimal("40.00");
    public static final BigDecimal DEFAULT_HIGH_PERCENTAGE = new BigDecimal("40.00");

    /** The shares of one paper, so they describe all of it and no more. */
    public static final BigDecimal REQUIRED_PERCENTAGE_TOTAL = new BigDecimal("100");

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * Reads the blueprint off an exam, falling back to the defaults for any part
     * of it the exam does not carry. Exams created before the columns existed
     * read as null through JPA, and so do the stubs in tests; either way the
     * answer is the paper the server used to build unconditionally, rather than
     * an exam with no questions in it.
     */
    public static ExamQuestionBlueprint of(Exam exam) {
        if (exam == null) {
            return defaults();
        }
        return new ExamQuestionBlueprint(
                exam.getTotalQuestions() == null ? DEFAULT_TOTAL_QUESTIONS : exam.getTotalQuestions(),
                exam.getLowSeverityPercentage() == null ? DEFAULT_LOW_PERCENTAGE : exam.getLowSeverityPercentage(),
                exam.getMediumSeverityPercentage() == null ? DEFAULT_MEDIUM_PERCENTAGE
                        : exam.getMediumSeverityPercentage(),
                exam.getHighSeverityPercentage() == null ? DEFAULT_HIGH_PERCENTAGE : exam.getHighSeverityPercentage());
    }

    public static ExamQuestionBlueprint defaults() {
        return new ExamQuestionBlueprint(DEFAULT_TOTAL_QUESTIONS, DEFAULT_LOW_PERCENTAGE,
                DEFAULT_MEDIUM_PERCENTAGE, DEFAULT_HIGH_PERCENTAGE);
    }

    public BigDecimal percentageFor(QuestionSeverity severity) {
        return switch (severity) {
            case LOW -> lowSeverityPercentage;
            case MEDIUM -> mediumSeverityPercentage;
            case HIGH -> highSeverityPercentage;
        };
    }

    /** The three shares added up; 100 for a mix that is valid. */
    public BigDecimal percentageTotal() {
        return lowSeverityPercentage.add(mediumSeverityPercentage).add(highSeverityPercentage);
    }

    /**
     * How many questions to draw from each severity. Sums to
     * {@link #totalQuestions}.
     */
    public Map<QuestionSeverity, Integer> questionCounts() {
        Map<QuestionSeverity, Integer> counts = new EnumMap<>(QuestionSeverity.class);
        Map<QuestionSeverity, BigDecimal> remainders = new EnumMap<>(QuestionSeverity.class);

        int allocated = 0;
        for (QuestionSeverity severity : QuestionSeverity.values()) {
            BigDecimal exact = percentageFor(severity)
                    .multiply(BigDecimal.valueOf(totalQuestions))
                    .divide(HUNDRED, 6, RoundingMode.HALF_UP);
            int floor = exact.setScale(0, RoundingMode.FLOOR).intValueExact();

            counts.put(severity, floor);
            remainders.put(severity, exact.subtract(BigDecimal.valueOf(floor)));
            allocated += floor;
        }

        /*
         * Hand the questions that flooring dropped to the severities that lost
         * the most to it. Sorting by severity as the tie-break keeps a mix like
         * 50/50/0 landing the same way every time rather than depending on map
         * iteration order.
         */
        Comparator<QuestionSeverity> largestRemainderFirst =
                Comparator.<QuestionSeverity, BigDecimal>comparing(remainders::get).reversed()
                        .thenComparing(Comparator.naturalOrder());
        List<QuestionSeverity> byRemainder = Arrays.stream(QuestionSeverity.values())
                .sorted(largestRemainderFirst)
                .toList();

        for (int i = 0; allocated < totalQuestions; i++, allocated++) {
            QuestionSeverity severity = byRemainder.get(i % byRemainder.size());
            counts.merge(severity, 1, Integer::sum);
        }

        return counts;
    }

    public int questionCountFor(QuestionSeverity severity) {
        return questionCounts().get(severity);
    }
}
