package com.ems.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ems.entity.Exam;
import com.ems.enums.QuestionSeverity;

class ExamQuestionBlueprintTest {

    private static ExamQuestionBlueprint blueprint(int total, String low, String medium, String high) {
        return new ExamQuestionBlueprint(total, new BigDecimal(low), new BigDecimal(medium), new BigDecimal(high));
    }

    @Test
    @DisplayName("a mix that divides cleanly gives each severity its exact share")
    void exactSplit() {
        Map<QuestionSeverity, Integer> counts = blueprint(30, "30.00", "40.00", "30.00").questionCounts();

        assertThat(counts.get(QuestionSeverity.LOW)).isEqualTo(9);
        assertThat(counts.get(QuestionSeverity.MEDIUM)).isEqualTo(12);
        assertThat(counts.get(QuestionSeverity.HIGH)).isEqualTo(9);
    }

    @Test
    @DisplayName("the defaults reproduce the 6/12/12 paper the constants used to build")
    void defaultsMatchLegacyPaper() {
        Map<QuestionSeverity, Integer> counts = ExamQuestionBlueprint.defaults().questionCounts();

        assertThat(counts.get(QuestionSeverity.LOW)).isEqualTo(6);
        assertThat(counts.get(QuestionSeverity.MEDIUM)).isEqualTo(12);
        assertThat(counts.get(QuestionSeverity.HIGH)).isEqualTo(12);
    }

    @Test
    @DisplayName("an exam with no blueprint stored still builds the standard paper")
    void nullColumnsFallBackToDefaults() {
        ExamQuestionBlueprint resolved = ExamQuestionBlueprint.of(Exam.builder().id(1L).build());

        assertThat(resolved).isEqualTo(ExamQuestionBlueprint.defaults());
        assertThat(resolved.questionCounts().values()).containsExactlyInAnyOrder(6, 12, 12);
    }

    @Test
    @DisplayName("a mix that does not divide cleanly still sums to the question total")
    void remaindersAreDistributed() {
        Map<QuestionSeverity, Integer> counts = blueprint(25, "30.00", "40.00", "30.00").questionCounts();

        assertThat(counts.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(25);
        assertThat(counts.get(QuestionSeverity.MEDIUM)).isEqualTo(10);
        // 7.5 each for LOW and HIGH; one of them takes the spare question.
        assertThat(counts.get(QuestionSeverity.LOW) + counts.get(QuestionSeverity.HIGH)).isEqualTo(15);
    }

    @Test
    @DisplayName("counts always sum to the total, whatever the mix")
    void countsAlwaysSumToTotal() {
        for (int total = 1; total <= 120; total++) {
            for (int low = 0; low <= 100; low += 5) {
                for (int medium = 0; medium <= 100 - low; medium += 5) {
                    int high = 100 - low - medium;
                    Map<QuestionSeverity, Integer> counts = blueprint(total,
                            String.valueOf(low), String.valueOf(medium), String.valueOf(high)).questionCounts();

                    assertThat(counts.values().stream().mapToInt(Integer::intValue).sum())
                            .as("total=%d mix=%d/%d/%d", total, low, medium, high)
                            .isEqualTo(total);
                    assertThat(counts.values()).allSatisfy(count -> assertThat(count).isNotNegative());
                }
            }
        }
    }

    @Test
    @DisplayName("a severity given no share contributes no questions")
    void zeroShareDrawsNothing() {
        Map<QuestionSeverity, Integer> counts = blueprint(20, "0.00", "50.00", "50.00").questionCounts();

        assertThat(counts.get(QuestionSeverity.LOW)).isZero();
        assertThat(counts.get(QuestionSeverity.MEDIUM)).isEqualTo(10);
        assertThat(counts.get(QuestionSeverity.HIGH)).isEqualTo(10);
    }
}
