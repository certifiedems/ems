package com.ems.entity;

import java.math.BigDecimal;
import java.time.Instant;

import com.ems.enums.CertificationLevel;
import com.ems.enums.ExamStatus;
import com.ems.util.ExamQuestionBlueprint;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
@Table(name = "exams")
public class Exam extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exam_code", nullable = false, unique = true, length = 50)
    private String examCode;

    @Column(name = "exam_name", nullable = false, length = 255)
    private String examName;

    @Enumerated(EnumType.STRING)
    @Column(name = "certification_level", nullable = false, length = 10)
    private CertificationLevel certificationLevel;

    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes;

    @Column(name = "total_marks", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalMarks;

    @Column(name = "passing_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal passingPercentage;

    /**
     * How many questions one attempt at this exam draws, and how that total is
     * shared across the three severities. The mix is held as percentages, not
     * counts, because it is set that way and stays meaningful when the total
     * moves; {@link ExamQuestionBlueprint} turns it into whole questions at the
     * moment a paper is built.
     *
     * <p>The columns are NOT NULL, so these default rather than being left
     * unset: an exam built without a blueprint would otherwise fail on insert
     * instead of falling back. The values mirror the column DEFAULT clauses and
     * the paper the server built before the mix was configurable.</p>
     */
    @Builder.Default
    @Column(name = "total_questions", nullable = false)
    private Integer totalQuestions = ExamQuestionBlueprint.DEFAULT_TOTAL_QUESTIONS;

    @Builder.Default
    @Column(name = "low_severity_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal lowSeverityPercentage = ExamQuestionBlueprint.DEFAULT_LOW_PERCENTAGE;

    @Builder.Default
    @Column(name = "medium_severity_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal mediumSeverityPercentage = ExamQuestionBlueprint.DEFAULT_MEDIUM_PERCENTAGE;

    @Builder.Default
    @Column(name = "high_severity_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal highSeverityPercentage = ExamQuestionBlueprint.DEFAULT_HIGH_PERCENTAGE;

    @Enumerated(EnumType.STRING)
    @Column(name = "exam_status", nullable = false, length = 20)
    private ExamStatus examStatus;

    @Column(name = "published", nullable = false)
    private boolean published;

    @Column(name = "scheduled_start_time")
    private Instant scheduledStartTime;

    @Column(name = "scheduled_end_time")
    private Instant scheduledEndTime;
}
