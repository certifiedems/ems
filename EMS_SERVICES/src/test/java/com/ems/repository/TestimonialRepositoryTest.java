package com.ems.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import com.ems.config.AuditorAwareConfig;
import com.ems.config.JpaAuditingConfig;
import com.ems.entity.Certification;
import com.ems.entity.Testimonial;
import com.ems.entity.User;
import com.ems.enums.CertificationLevel;
import com.ems.enums.CertificationStatus;
import com.ems.enums.TestimonialNameDisplay;
import com.ems.enums.TestimonialStatus;

/**
 * Proves the public list's filter (approved, certification not revoked, newest
 * approval first) and the one-per-level rule hold against a real database.
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
class TestimonialRepositoryTest {

    @Autowired
    private TestimonialRepository testimonialRepository;

    @Autowired
    private CertificationRepository certificationRepository;

    @Autowired
    private UserRepository userRepository;

    private User user;

    @BeforeEach
    void setUp() {
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
    }

    @Test
    void publicListIsApprovedOnlyWithoutRevokedCertificationsNewestApprovalFirst() {
        Testimonial older = save(CertificationLevel.L1, CertificationStatus.ACTIVE, TestimonialStatus.APPROVED,
                Instant.parse("2026-09-01T00:00:00Z"));
        Testimonial newer = save(CertificationLevel.L2, CertificationStatus.EXPIRED, TestimonialStatus.APPROVED,
                Instant.parse("2026-09-10T00:00:00Z"));
        save(CertificationLevel.L3, CertificationStatus.REVOKED, TestimonialStatus.APPROVED,
                Instant.parse("2026-09-20T00:00:00Z"));

        assertThat(testimonialRepository
                .findTop12ByStatusAndCertificationCertificationStatusNotOrderByReviewedAtDesc(
                        TestimonialStatus.APPROVED, CertificationStatus.REVOKED))
                .extracting(Testimonial::getId)
                .containsExactly(newer.getId(), older.getId());
    }

    @Test
    void pendingTestimonialsStayOffThePublicList() {
        save(CertificationLevel.L1, CertificationStatus.ACTIVE, TestimonialStatus.PENDING, null);

        assertThat(testimonialRepository
                .findTop12ByStatusAndCertificationCertificationStatusNotOrderByReviewedAtDesc(
                        TestimonialStatus.APPROVED, CertificationStatus.REVOKED))
                .isEmpty();
        assertThat(testimonialRepository.findByStatusOrderByCreatedDateDesc(TestimonialStatus.PENDING)).hasSize(1);
    }

    @Test
    void oneTestimonialPerCandidatePerLevel() {
        save(CertificationLevel.L1, CertificationStatus.ACTIVE, TestimonialStatus.PENDING, null);

        assertThat(testimonialRepository.existsByUserAndCertificationLevel(user, CertificationLevel.L1)).isTrue();
        assertThatThrownBy(() -> save(CertificationLevel.L1, CertificationStatus.ACTIVE,
                TestimonialStatus.PENDING, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Testimonial save(CertificationLevel level, CertificationStatus certificationStatus,
            TestimonialStatus status, Instant reviewedAt) {
        Certification certification = certificationRepository.save(Certification.builder()
                .user(user)
                .certificationLevel(level)
                .certificationStatus(certificationStatus)
                .issueDate(LocalDate.of(2026, 1, 1))
                .expiryDate(LocalDate.of(2029, 1, 1))
                .build());
        return testimonialRepository.saveAndFlush(Testimonial.builder()
                .user(user)
                .certification(certification)
                .certificationLevel(level)
                .rating(5)
                .quote("L" + level + " helped me move into a line technician role.")
                .nameDisplay(TestimonialNameDisplay.FULL_NAME)
                .status(status)
                .reviewedAt(reviewedAt)
                .consentGivenAt(Instant.parse("2026-08-30T00:00:00Z"))
                .consentVersion("2026-09")
                .build());
    }
}
