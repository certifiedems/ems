package com.ems.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.ems.config.AuditorAwareConfig;
import com.ems.config.JpaAuditingConfig;
import com.ems.entity.CertificationApplication;
import com.ems.entity.Exam;
import com.ems.entity.ExamSession;
import com.ems.entity.User;
import com.ems.enums.CertificationApplicationStatus;
import com.ems.enums.CertificationLevel;
import com.ems.enums.ExamStatus;
import com.ems.enums.PaymentStatus;

/**
 * Proves the queries behind exam retakes parse and answer what the services ask
 * of them: which papers a candidate has already been given at a level, and
 * which retake is the newest on a payment.
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
class ExamAttemptRepositoryQueryTest {

    @Autowired
    private ExamSessionRepository examSessionRepository;

    @Autowired
    private CertificationApplicationRepository certificationApplicationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ExamRepository examRepository;

    private User user;

    @BeforeEach
    void seed() {
        user = user("EMS-0007", "candidate@example.com", "9000000001");
    }

    @Test
    void papersSeenAtALevelAreOnlyThisCandidatesAndOnlyThatLevels() {
        Exam l1 = exam("EX-L1", CertificationLevel.L1);
        Exam l2 = exam("EX-L2", CertificationLevel.L2);
        User other = user("EMS-0008", "other@example.com", "9000000002");

        session(user, l1, "[1,2,3]");
        session(user, l2, "[7,8,9]");
        session(other, l1, "[4,5,6]");
        // A session with no paper drawn has asked nothing.
        session(user, l1, null);

        List<Object[]> papers = examSessionRepository.findPapersSeenAtLevel(user, CertificationLevel.L1);

        assertThat(papers).hasSize(1);
        assertThat(papers.get(0)[0]).isEqualTo("[1,2,3]");
        assertThat(papers.get(0)[1]).isInstanceOf(Instant.class);
    }

    @Test
    void theNewestRetakeOnAPaymentIsItsHighestAttempt() {
        Exam l1 = exam("EX-L1", CertificationLevel.L1);
        CertificationApplication paid = certificationApplicationRepository.save(application(l1, 1, null));
        certificationApplicationRepository.save(application(l1, 2, paid));
        CertificationApplication third = certificationApplicationRepository.save(application(l1, 3, paid));

        assertThat(certificationApplicationRepository.findTopByPaidApplicationOrderByAttemptNumberDesc(paid))
                .map(CertificationApplication::getId)
                .contains(third.getId());
        assertThat(certificationApplicationRepository.findByPaidApplicationOrderByAttemptNumberAsc(paid))
                .extracting(CertificationApplication::getAttemptNumber)
                .containsExactly(2, 3);
    }

    private User user(String userId, String email, String mobileNumber) {
        return userRepository.save(User.builder()
                .userId(userId)
                .firstName("Ada")
                .lastName("Lovelace")
                .email(email)
                .mobileNumber(mobileNumber)
                .passwordHash("hash")
                .currentSkillLevel("L1")
                .enabled(true)
                .build());
    }

    private Exam exam(String examCode, CertificationLevel level) {
        return examRepository.save(Exam.builder()
                .examCode(examCode)
                .examName("Exam " + examCode)
                .certificationLevel(level)
                .durationMinutes(60)
                .totalMarks(new BigDecimal("100.00"))
                .passingPercentage(new BigDecimal("60.00"))
                .examStatus(ExamStatus.SCHEDULED)
                .published(true)
                .build());
    }

    private void session(User candidate, Exam exam, String selectedQuestionIdsJson) {
        examSessionRepository.save(ExamSession.builder()
                .sessionToken(UUID.randomUUID())
                .user(candidate)
                .exam(exam)
                .sessionStartTime(Instant.now())
                .sessionStatus(ExamStatus.FAILED)
                .violationCount(0)
                .selectedQuestionIdsJson(selectedQuestionIdsJson)
                .build());
    }

    private CertificationApplication application(Exam exam, int attemptNumber, CertificationApplication paid) {
        return CertificationApplication.builder()
                .user(user)
                .exam(exam)
                .certificationLevel(CertificationLevel.L1)
                .applicationStatus(CertificationApplicationStatus.FAILED)
                .paymentStatus(PaymentStatus.SUCCESS)
                .appliedOn(LocalDate.now())
                .attemptNumber(attemptNumber)
                .attemptsAllowed(3)
                .paidApplication(paid)
                .build();
    }
}
