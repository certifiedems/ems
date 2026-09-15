package com.ems.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The marking rule the score and the admin exam tracker share. If these drift,
 * the tracker marks questions wrong on an attempt whose score counted them right.
 */
class AnswerMarkingTest {

    @Test
    void isCorrect_ignoresOrderCaseAndSurroundingSpace() {
        assertThat(AnswerMarking.isCorrect(
                List.of(" ohm's law ", "KIRCHHOFF"),
                List.of("Kirchhoff", "Ohm's law"))).isTrue();
    }

    @Test
    void isCorrect_needsEveryCorrectOptionAndNothingElse() {
        List<String> correct = List.of("A", "B");

        assertThat(AnswerMarking.isCorrect(List.of("A"), correct)).isFalse();
        assertThat(AnswerMarking.isCorrect(List.of("A", "B", "C"), correct)).isFalse();
    }

    @Test
    void anAnswerOfOnlyBlanks_isUnansweredAndNeverCorrect() {
        List<String> blanks = Arrays.asList("", "  ", null);

        assertThat(AnswerMarking.isAnswered(blanks)).isFalse();
        assertThat(AnswerMarking.isAnswered(null)).isFalse();
        assertThat(AnswerMarking.isCorrect(blanks, List.of())).isFalse();
    }
}
