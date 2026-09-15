package com.ems.entity;

import java.math.BigDecimal;
import java.time.Instant;

import com.ems.enums.ResultStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true, of = "id")
@Entity
@Table(name = "exam_attempts")
public class ExamAttempt extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_session_ref", nullable = false)
    private ExamSession examSession;

    @Column(name = "total_questions", nullable = false)
    private Integer totalQuestions;

    @Column(name = "attempted_questions", nullable = false)
    private Integer attemptedQuestions;

    @Column(name = "correct_answers", nullable = false)
    private Integer correctAnswers;

    @Column(name = "wrong_answers", nullable = false)
    private Integer wrongAnswers;

    @Column(name = "obtained_marks", nullable = false, precision = 10, scale = 2)
    private BigDecimal obtainedMarks;

    @Column(name = "percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal percentage;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_status", nullable = false, length = 10)
    private ResultStatus resultStatus;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    /**
     * The answers this attempt was scored on, as
     * {@code {"<questionId>": ["option", ...]}} — the shape the session's
     * autosave draft uses.
     *
     * <p>Kept so an administrator can see which questions were answered wrong,
     * not only how many. Null on attempts submitted before it was recorded.</p>
     */
    @Column(name = "submitted_answers_json")
    private String submittedAnswersJson;
}
