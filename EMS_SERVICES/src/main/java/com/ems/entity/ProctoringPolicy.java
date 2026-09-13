package com.ems.entity;

import java.util.HashMap;
import java.util.Map;

import com.ems.enums.ViolationEnforcement;
import com.ems.enums.ViolationType;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyEnumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Proctoring rules an administrator has saved.
 *
 * <p>One row per scope. The row with no exam is the platform default; a row with
 * an exam replaces that default outright for attempts at that exam. With no row
 * at all the built-in rules apply — see
 * {@link com.ems.service.EffectiveProctoringPolicy#builtIn()}.</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true, of = "id")
@Entity
@Table(name = "proctoring_policies")
public class ProctoringPolicy extends BaseAuditEntity {

    public static final String DEFAULT_SCOPE_KEY = "DEFAULT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * {@code DEFAULT}, or {@code EXAM:<examId>}.
     *
     * <p>Carries the uniqueness {@code exam_ref} cannot: a unique column still
     * admits any number of NULLs, and the default policy is the row whose exam is
     * NULL.</p>
     */
    @Column(name = "scope_key", nullable = false, unique = true, length = 40)
    private String scopeKey;

    /** The exam these rules apply to; null on the default policy. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_ref")
    private Exam exam;

    @Column(name = "strike_limit", nullable = false)
    private int strikeLimit;

    @Column(name = "unidentified_sound_grace", nullable = false)
    private int unidentifiedSoundGrace;

    @Column(name = "voice_min_db_above_floor", nullable = false)
    private int voiceMinDbAboveFloor;

    @Column(name = "background_noise_min_db_above_floor", nullable = false)
    private int backgroundNoiseMinDbAboveFloor;

    @Column(name = "unidentified_sound_min_db_above_floor", nullable = false)
    private int unidentifiedSoundMinDbAboveFloor;

    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "proctoring_policy_rules", joinColumns = @JoinColumn(name = "policy_ref"))
    @MapKeyEnumerated(EnumType.STRING)
    @MapKeyColumn(name = "violation_type", length = 80)
    @Enumerated(EnumType.STRING)
    @Column(name = "enforcement", nullable = false, length = 20)
    private Map<ViolationType, ViolationEnforcement> rules = new HashMap<>();

    /**
     * Guards against two administrators overwriting each other.
     *
     * <p>Also what makes a save that only changes {@link #rules} register as an
     * update of this row: a change to an owned collection bumps the version, so
     * the audit columns move with it instead of still naming whoever last touched
     * the strike limit.</p>
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public static String scopeKeyForExam(Long examId) {
        return "EXAM:" + examId;
    }
}
