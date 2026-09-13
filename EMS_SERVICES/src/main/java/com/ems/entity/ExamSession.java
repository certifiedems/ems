package com.ems.entity;

import java.time.Instant;
import java.util.UUID;

import com.ems.enums.ExamStatus;

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
@Table(name = "exam_sessions")
public class ExamSession extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_token", nullable = false, unique = true)
    private UUID sessionToken;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_ref", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_ref", nullable = false)
    private Exam exam;

    /**
     * The application this attempt was sat under.
     *
     * <p>Nullable only for sessions written before the column existed, whose
     * application cannot always be identified after the fact. A {@code null}
     * means "unknown", never "none" — code that decides whether an attempt has
     * been used must not read an unlinked legacy session as a free one.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_ref")
    private CertificationApplication certificationApplication;

    @Column(name = "session_start_time")
    private Instant sessionStartTime;

    @Column(name = "session_end_time")
    private Instant sessionEndTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "session_status", nullable = false, length = 20)
    private ExamStatus sessionStatus;

    @Column(name = "violation_count", nullable = false)
    private int violationCount;

    @Column(name = "browser_fingerprint", length = 255)
    private String browserFingerprint;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "selected_question_ids_json")
    private String selectedQuestionIdsJson;

    /**
     * The candidate's answers so far, autosaved while the attempt is running.
     *
     * <p>Answers used to live only in the browser until the moment of submission,
     * which made every interruption total: a dropped connection, a flat battery
     * or a closed laptop lost an hour's work even though the session itself
     * survived and could be rejoined. Held here as
     * {@code {"<questionId>": ["option", ...]}} so a resumed attempt comes back
     * with what was already answered, from any machine.</p>
     *
     * <p>This is a draft, never a result. Scoring reads the answers posted with
     * the submission; this column only decides what the candidate sees when they
     * come back.</p>
     */
    @Column(name = "answers_draft_json")
    private String answersDraftJson;

    /** Questions the candidate flagged to revisit, as a JSON array of ids. */
    @Column(name = "marked_for_review_json")
    private String markedForReviewJson;

    /** 1-indexed question the candidate was last on, so a resume lands there. */
    @Column(name = "last_question_number")
    private Integer lastQuestionNumber;

    /** When the draft above was last written; null until the first autosave. */
    @Column(name = "progress_saved_at")
    private Instant progressSavedAt;

    /**
     * The proctoring rules this attempt is judged by, captured when it started.
     *
     * <p>Fixed per attempt so an administrator's change reaches attempts that
     * start afterwards and never one already under way: a candidate shown one
     * strike limit at the start is not terminated under another. Null on attempts
     * begun before rules were captured, which follow the exam's current rules.
     * See {@link com.ems.service.ProctoringPolicyService#resolveForSession}.</p>
     */
    @Column(name = "proctoring_policy_json")
    private String proctoringPolicyJson;
}
