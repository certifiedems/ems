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
import com.ems.entity.Certification;
import com.ems.entity.CertificationApplication;
import com.ems.entity.Exam;
import com.ems.entity.ExamAttempt;
import com.ems.entity.ExamSession;
import com.ems.entity.Payment;
import com.ems.entity.Role;
import com.ems.entity.User;
import com.ems.enums.CertificationApplicationStatus;
import com.ems.enums.CertificationLevel;
import com.ems.enums.CertificationStatus;
import com.ems.enums.ExamStatus;
import com.ems.enums.PaymentGatewayMode;
import com.ems.enums.PaymentStatus;
import com.ems.enums.ResultStatus;
import com.ems.enums.RoleName;

/**
 * Proves the queries behind the admin exam tracker and the board figures parse
 * and select what their callers expect. The services are tested against mocks;
 * this is where a query that does not mean what its name says would show.
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
class AdminTrackerAndAnalyticsQueryTest {

    private static final Instant SLOT = Instant.parse("2026-09-20T10:00:00Z");

    @Autowired
    private CertificationApplicationRepository certificationApplicationRepository;

    @Autowired
    private ExamSessionRepository examSessionRepository;

    @Autowired
    private ExamAttemptRepository examAttemptRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private CertificationRepository certificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private ExamRepository examRepository;

    private User candidate;
    private User other;
    private Exam exam;

    @BeforeEach
    void seed() {
        candidate = user("EMS-0001", "one@example.com", "9000000001");
        other = user("EMS-0002", "two@example.com", "9000000002");
        exam = examRepository.save(Exam.builder()
                .examCode("EX-L1")
                .examName("Exam L1")
                .certificationLevel(CertificationLevel.L1)
                .durationMinutes(60)
                .totalMarks(new BigDecimal("100.00"))
                .passingPercentage(new BigDecimal("60.00"))
                .examStatus(ExamStatus.SCHEDULED)
                .published(true)
                .build());
    }

    @Test
    void theTrackerFollowsBookedAndPaidApplications_butNotUnpaidIntents() {
        User third = user("EMS-0003", "three@example.com", "9000000003");
        CertificationApplication booked = application(candidate, SLOT, PaymentStatus.SUCCESS);
        CertificationApplication paidNotBooked = application(other, null, PaymentStatus.SUCCESS);
        application(third, null, PaymentStatus.PENDING);

        assertThat(certificationApplicationRepository.findTrackedWithCandidateAndExam())
                .extracting(CertificationApplication::getId)
                .containsExactlyInAnyOrder(booked.getId(), paidNotBooked.getId());
        assertThat(certificationApplicationRepository.countDistinctApplicants()).isEqualTo(3);
        assertThat(certificationApplicationRepository.countDistinctPaidApplicants()).isEqualTo(2);
    }

    @Test
    void sessionsAndResultsAreFoundForAWholePageOfApplications() {
        CertificationApplication first = application(candidate, SLOT, PaymentStatus.SUCCESS);
        CertificationApplication second = application(other, SLOT, PaymentStatus.SUCCESS);
        ExamSession scoredSession = session(candidate, first, ExamStatus.PASSED);
        ExamSession liveSession = session(other, second, ExamStatus.IN_PROGRESS);
        ExamAttempt attempt = examAttemptRepository.save(ExamAttempt.builder()
                .examSession(scoredSession)
                .totalQuestions(2)
                .attemptedQuestions(1)
                .correctAnswers(1)
                .wrongAnswers(0)
                .obtainedMarks(BigDecimal.ONE)
                .percentage(new BigDecimal("50.00"))
                .resultStatus(ResultStatus.PASS)
                .submittedAt(SLOT.plusSeconds(1800))
                .submittedAnswersJson("{\"1\":[\"A\"],\"2\":[]}")
                .build());

        assertThat(examSessionRepository.findByCertificationApplicationIn(List.of(first)))
                .extracting(ExamSession::getId)
                .containsExactly(scoredSession.getId());
        assertThat(examAttemptRepository.findByExamSessionIn(List.of(scoredSession, liveSession)))
                .extracting(ExamAttempt::getId)
                .containsExactly(attempt.getId());
        assertThat(examSessionRepository.countDistinctCandidates()).isEqualTo(2);
        assertThat(examAttemptRepository.findSubmissionOutcomes())
                .singleElement()
                .satisfies(row -> assertThat(row[1]).isEqualTo(ResultStatus.PASS));
        assertThat(examAttemptRepository.findById(attempt.getId()))
                .get()
                .extracting(ExamAttempt::getSubmittedAnswersJson)
                .isEqualTo("{\"1\":[\"A\"],\"2\":[]}");
    }

    @Test
    void boardProjections_countSuccessfulPayments_andLeaveAdministratorsOut() {
        Role adminRole = roleRepository.save(Role.builder().name(RoleName.ADMIN).description("Administrator").build());
        User admin = user("EMS-ADMIN", "admin@example.com", "9000000009");
        admin.getRoles().add(adminRole);
        userRepository.save(admin);

        paymentRepository.save(payment(candidate, "TXN-1", PaymentStatus.SUCCESS));
        paymentRepository.save(payment(other, "TXN-2", PaymentStatus.FAILED));
        certificationRepository.save(Certification.builder()
                .user(candidate)
                .certificationLevel(CertificationLevel.L1)
                .certificationStatus(CertificationStatus.ACTIVE)
                .issueDate(LocalDate.of(2026, 9, 1))
                .expiryDate(LocalDate.of(2027, 9, 1))
                .build());

        assertThat(userRepository.findCandidateRegistrationTimes()).hasSize(2);
        assertThat(paymentRepository.findSuccessfulPaymentFigures())
                .singleElement()
                .satisfies(row -> {
                    assertThat((BigDecimal) row[0]).isEqualByComparingTo("1500.00");
                    assertThat(row[1]).isEqualTo("INR");
                    assertThat(row[2]).isEqualTo(PaymentGatewayMode.LIVE);
                });
        assertThat(certificationRepository.findIssuanceFigures())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row[0]).isEqualTo(candidate.getId());
                    assertThat(row[1]).isEqualTo(CertificationLevel.L1);
                    assertThat(row[3]).isEqualTo(LocalDate.of(2026, 9, 1));
                });
    }

    private User user(String userId, String email, String mobileNumber) {
        return userRepository.save(User.builder()
                .userId(userId)
                .firstName("First")
                .lastName("Last")
                .email(email)
                .mobileNumber(mobileNumber)
                .passwordHash("hash")
                .currentSkillLevel("L1")
                .enabled(true)
                .build());
    }

    private CertificationApplication application(User user, Instant slot, PaymentStatus paymentStatus) {
        return certificationApplicationRepository.save(CertificationApplication.builder()
                .user(user)
                .exam(exam)
                .certificationLevel(CertificationLevel.L1)
                .applicationStatus(CertificationApplicationStatus.IN_PROGRESS)
                .paymentStatus(paymentStatus)
                .appliedOn(LocalDate.now())
                .scheduledExamTime(slot)
                .build());
    }

    private ExamSession session(User user, CertificationApplication application, ExamStatus status) {
        return examSessionRepository.save(ExamSession.builder()
                .sessionToken(UUID.randomUUID())
                .user(user)
                .exam(exam)
                .certificationApplication(application)
                .sessionStartTime(SLOT)
                .sessionStatus(status)
                .violationCount(0)
                .selectedQuestionIdsJson("[1,2]")
                .build());
    }

    private Payment payment(User user, String transactionId, PaymentStatus status) {
        return Payment.builder()
                .transactionId(transactionId)
                .user(user)
                .exam(exam)
                .amount(new BigDecimal("1500.00"))
                .currency("INR")
                .provider("RAZORPAY")
                .paymentStatus(status)
                .paymentDate(SLOT)
                .gatewayMode(PaymentGatewayMode.LIVE)
                .build();
    }
}
