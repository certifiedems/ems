package com.ems.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

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
import com.ems.entity.ExamSlotBookingLock;
import com.ems.entity.User;
import com.ems.enums.CertificationApplicationStatus;
import com.ems.enums.CertificationLevel;
import com.ems.enums.ExamStatus;
import com.ems.enums.PaymentStatus;

/**
 * Proves the queries behind exam slot capacity parse and answer what booking
 * asks of them: which bookings hold a seat in a stretch of time, and that the
 * booking lock can be taken.
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
class ExamSlotBookingQueryTest {

    private static final Instant SLOT = Instant.parse("2026-09-20T10:00:00Z");
    private static final Instant SLOT_END = SLOT.plus(75, ChronoUnit.MINUTES);

    @Autowired
    private CertificationApplicationRepository certificationApplicationRepository;

    @Autowired
    private ExamSlotBookingLockRepository examSlotBookingLockRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ExamRepository examRepository;

    private User user;
    private Exam l1;
    private Exam l2;

    @BeforeEach
    void seed() {
        user = userRepository.save(User.builder()
                .userId("EMS-0007")
                .firstName("Ada")
                .lastName("Lovelace")
                .email("candidate@example.com")
                .mobileNumber("9000000001")
                .passwordHash("hash")
                .currentSkillLevel("L1")
                .enabled(true)
                .build());
        l1 = exam("EX-L1", CertificationLevel.L1, 60);
        l2 = exam("EX-L2", CertificationLevel.L2, 90);
    }

    @Test
    void onlyPaidLiveBookingsInsideTheRangeHoldASeat() {
        booking(l1, SLOT, CertificationApplicationStatus.APPLIED, PaymentStatus.SUCCESS);
        // A finished attempt held its seat while it ran.
        booking(l2, SLOT, CertificationApplicationStatus.FAILED, PaymentStatus.SUCCESS);

        booking(l1, SLOT, CertificationApplicationStatus.APPLIED, PaymentStatus.PENDING);
        booking(l1, SLOT, CertificationApplicationStatus.APPLIED, PaymentStatus.REFUNDED);
        booking(l1, SLOT, CertificationApplicationStatus.REJECTED, PaymentStatus.SUCCESS);
        booking(l1, SLOT, CertificationApplicationStatus.EXPIRED, PaymentStatus.SUCCESS);
        booking(l1, SLOT_END, CertificationApplicationStatus.APPLIED, PaymentStatus.SUCCESS);
        CertificationApplication beingBooked =
                booking(l1, SLOT, CertificationApplicationStatus.APPLIED, PaymentStatus.SUCCESS);

        List<Object[]> rows = certificationApplicationRepository
                .findSeatHoldingBookings(SLOT, SLOT_END, beingBooked.getId());

        assertThat(rows.stream().map(row -> row[0]).toList()).containsOnly(SLOT);
        assertThat(rows.stream().map(row -> row[1]).toList()).containsExactlyInAnyOrder(60, 90);
    }

    @Test
    void theBookingLockCanBeTakenForUpdate() {
        examSlotBookingLockRepository.save(new ExamSlotBookingLock(ExamSlotBookingLock.BOOKING_LOCK_ID));

        assertThat(examSlotBookingLockRepository.findByIdForUpdate(ExamSlotBookingLock.BOOKING_LOCK_ID))
                .isPresent();
    }

    private Exam exam(String examCode, CertificationLevel level, int durationMinutes) {
        return examRepository.save(Exam.builder()
                .examCode(examCode)
                .examName("Exam " + examCode)
                .certificationLevel(level)
                .durationMinutes(durationMinutes)
                .totalMarks(new BigDecimal("100.00"))
                .passingPercentage(new BigDecimal("60.00"))
                .examStatus(ExamStatus.SCHEDULED)
                .published(true)
                .build());
    }

    private CertificationApplication booking(Exam exam, Instant scheduledExamTime,
            CertificationApplicationStatus applicationStatus, PaymentStatus paymentStatus) {
        return certificationApplicationRepository.save(CertificationApplication.builder()
                .user(user)
                .exam(exam)
                .certificationLevel(exam.getCertificationLevel())
                .applicationStatus(applicationStatus)
                .paymentStatus(paymentStatus)
                .appliedOn(LocalDate.now())
                .scheduledExamTime(scheduledExamTime)
                .build());
    }
}
