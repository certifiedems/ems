package com.ems.util;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * How one answer is marked: which options count as chosen, and when the choice
 * is right.
 *
 * <p>Kept here rather than in the result service because two places need the
 * same verdict: scoring a submission, and the admin exam tracker showing which
 * questions a candidate got wrong. If the two disagreed, the tracker would mark
 * a question wrong on an attempt whose score had counted it right.</p>
 */
public final class AnswerMarking {

    private AnswerMarking() {
    }

    /** The chosen options with blanks dropped, trimmed and lower-cased for comparison. */
    public static List<String> normalize(List<String> options) {
        if (options == null) {
            return List.of();
        }
        return options.stream()
                .filter(option -> option != null && !option.isBlank())
                .map(option -> option.trim().toLowerCase(Locale.ROOT))
                .toList();
    }

    /** Whether anything was chosen at all; an answer of only blanks is no answer. */
    public static boolean isAnswered(List<String> selectedOptions) {
        return !normalize(selectedOptions).isEmpty();
    }

    /**
     * Whether the chosen options are exactly the correct ones: all of them and
     * nothing else, in any order and any case. An unanswered question is never
     * correct.
     */
    public static boolean isCorrect(List<String> selectedOptions, List<String> correctOptions) {
        List<String> selected = normalize(selectedOptions);
        return !selected.isEmpty() && Set.copyOf(selected).equals(Set.copyOf(normalize(correctOptions)));
    }
}
