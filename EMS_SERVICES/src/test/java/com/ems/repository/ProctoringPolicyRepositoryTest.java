package com.ems.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import com.ems.config.AuditorAwareConfig;
import com.ems.config.JpaAuditingConfig;
import com.ems.entity.Exam;
import com.ems.entity.ProctoringPolicy;
import com.ems.enums.CertificationLevel;
import com.ems.enums.ExamStatus;
import com.ems.enums.ViolationEnforcement;
import com.ems.enums.ViolationType;

/**
 * Proves {@link ProctoringPolicy} maps: the rules collection round-trips, the
 * scope key really is unique, and a rules-only edit versions the row.
 */
@DataJpaTest
@AutoConfigureTestDatabase
@Import({ JpaAuditingConfig.class, AuditorAwareConfig.class })
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        // The default `dev` profile pins the PostgreSQL dialect; override it so
        // Hibernate emits H2-compatible DML against the replaced test datasource.
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
class ProctoringPolicyRepositoryTest {

    @Autowired
    private ProctoringPolicyRepository proctoringPolicyRepository;

    @Autowired
    private ExamRepository examRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void rulesRoundTripPerScope() {
        Exam exam = examRepository.save(Exam.builder()
                .examCode("EX-L1")
                .examName("Foundation")
                .certificationLevel(CertificationLevel.L1)
                .durationMinutes(60)
                .totalMarks(new BigDecimal("100.00"))
                .passingPercentage(new BigDecimal("60.00"))
                .examStatus(ExamStatus.SCHEDULED)
                .published(true)
                .build());

        proctoringPolicyRepository.saveAndFlush(policy(ProctoringPolicy.DEFAULT_SCOPE_KEY, null, 3,
                Map.of(ViolationType.TAB_SWITCH, ViolationEnforcement.DISABLED)));
        proctoringPolicyRepository.saveAndFlush(policy(ProctoringPolicy.scopeKeyForExam(exam.getId()), exam, 5,
                Map.of(ViolationType.EYES_OFF_SCREEN, ViolationEnforcement.STRIKE)));
        entityManager.clear();

        ProctoringPolicy loaded = proctoringPolicyRepository
                .findByScopeKey(ProctoringPolicy.scopeKeyForExam(exam.getId()))
                .orElseThrow();

        assertThat(loaded.getStrikeLimit()).isEqualTo(5);
        assertThat(loaded.getRules()).containsExactly(Map.entry(ViolationType.EYES_OFF_SCREEN, ViolationEnforcement.STRIKE));
        assertThat(loaded.getCreatedBy()).isNotBlank();
        assertThat(proctoringPolicyRepository.findExamIdsWithOwnPolicy()).containsExactly(exam.getId());
    }

    @Test
    void onlyOneDefaultPolicyCanExist() {
        proctoringPolicyRepository.saveAndFlush(policy(ProctoringPolicy.DEFAULT_SCOPE_KEY, null, 3, Map.of()));

        assertThatThrownBy(() -> proctoringPolicyRepository.saveAndFlush(
                policy(ProctoringPolicy.DEFAULT_SCOPE_KEY, null, 4, Map.of())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * The stale-edit check and the "last saved by" line both depend on this: an
     * admin who only flips a rule has still changed the policy.
     */
    @Test
    void changingOnlyTheRulesVersionsTheRow() {
        ProctoringPolicy saved = proctoringPolicyRepository.saveAndFlush(policy(ProctoringPolicy.DEFAULT_SCOPE_KEY, null, 3,
                Map.of(ViolationType.TAB_SWITCH, ViolationEnforcement.STRIKE)));
        Long firstVersion = saved.getVersion();

        saved.getRules().put(ViolationType.TAB_SWITCH, ViolationEnforcement.DISABLED);
        ProctoringPolicy updated = proctoringPolicyRepository.saveAndFlush(saved);

        assertThat(updated.getVersion()).isGreaterThan(firstVersion);
        entityManager.clear();
        assertThat(proctoringPolicyRepository.findByScopeKey(ProctoringPolicy.DEFAULT_SCOPE_KEY).orElseThrow().getRules())
                .containsEntry(ViolationType.TAB_SWITCH, ViolationEnforcement.DISABLED);
    }

    private static ProctoringPolicy policy(String scopeKey, Exam exam, int strikeLimit,
            Map<ViolationType, ViolationEnforcement> rules) {
        return ProctoringPolicy.builder()
                .scopeKey(scopeKey)
                .exam(exam)
                .strikeLimit(strikeLimit)
                .unidentifiedSoundGrace(2)
                .voiceMinDbAboveFloor(0)
                .backgroundNoiseMinDbAboveFloor(0)
                .unidentifiedSoundMinDbAboveFloor(15)
                .rules(new HashMap<>(rules))
                .build();
    }
}
