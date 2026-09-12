package com.ems.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
import com.ems.entity.Exam;
import com.ems.entity.Payment;
import com.ems.entity.User;
import com.ems.enums.CertificationLevel;
import com.ems.enums.ExamStatus;
import com.ems.enums.PaymentGatewayMode;
import com.ems.enums.PaymentStatus;

/**
 * Runs the admin payment search against a real database.
 *
 * <p>Every filter on it is optional, which is exactly the kind of query that
 * parses fine and still filters wrongly: a null check that swallows a real
 * value, or a date bound on the wrong side. The console's list and its report
 * download both stand on this query, so the filters are exercised here rather
 * than mocked away.</p>
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
class PaymentRepositorySearchQueryTest {

    private static final String LIVE_UPI = "TXNLIVEUPI000001";
    private static final String TEST_CARD = "TXNTESTCARD00002";
    private static final String SIMULATED = "TXNSIMULATED0003";

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ExamRepository examRepository;

    @BeforeEach
    void seed() {
        User asha = userRepository.save(user("EMS-0101", "Asha", "Rao", "asha@example.com", "9000000101"));
        User ravi = userRepository.save(user("EMS-0102", "Ravi", "Kumar", "ravi@example.com", "9000000102"));
        Exam exam = examRepository.save(Exam.builder()
                .examCode("EX-PAY-L1")
                .examName("Foundation")
                .certificationLevel(CertificationLevel.L1)
                .durationMinutes(60)
                .totalMarks(new BigDecimal("100.00"))
                .passingPercentage(new BigDecimal("60.00"))
                .examStatus(ExamStatus.SCHEDULED)
                .published(true)
                .build());

        paymentRepository.save(payment(LIVE_UPI, asha, exam, PaymentStatus.SUCCESS, PaymentGatewayMode.LIVE,
                "UPI", "order_LIVE1"));
        paymentRepository.save(payment(TEST_CARD, asha, exam, PaymentStatus.PENDING, PaymentGatewayMode.TEST,
                "CARD", "order_TEST2"));
        paymentRepository.save(payment(SIMULATED, ravi, exam, PaymentStatus.FAILED, PaymentGatewayMode.SIMULATED,
                null, null));
    }

    @Test
    void noFiltersReturnsEveryPayment() {
        assertThat(search(null, null, null, null, null, null))
                .extracting(Payment::getTransactionId)
                .containsExactlyInAnyOrder(LIVE_UPI, TEST_CARD, SIMULATED);
    }

    @Test
    void filtersByStatusGatewayModeAndPaymentMethod() {
        assertThat(search(null, PaymentStatus.SUCCESS, null, null, null, null))
                .extracting(Payment::getTransactionId).containsExactly(LIVE_UPI);
        assertThat(search(null, null, PaymentGatewayMode.TEST, null, null, null))
                .extracting(Payment::getTransactionId).containsExactly(TEST_CARD);
        assertThat(search(null, null, null, "CARD", null, null))
                .extracting(Payment::getTransactionId).containsExactly(TEST_CARD);
        assertThat(search(null, PaymentStatus.SUCCESS, PaymentGatewayMode.TEST, null, null, null)).isEmpty();
    }

    @Test
    void searchMatchesFullNameEmailTransactionAndGatewayOrder() {
        assertThat(search("%asha rao%", null, null, null, null, null))
                .extracting(Payment::getTransactionId).containsExactlyInAnyOrder(LIVE_UPI, TEST_CARD);
        assertThat(search("%ravi@example%", null, null, null, null, null))
                .extracting(Payment::getTransactionId).containsExactly(SIMULATED);
        assertThat(search("%txnliveupi%", null, null, null, null, null))
                .extracting(Payment::getTransactionId).containsExactly(LIVE_UPI);
        assertThat(search("%order_test2%", null, null, null, null, null))
                .extracting(Payment::getTransactionId).containsExactly(TEST_CARD);
    }

    @Test
    void createdDateBoundsAreInclusiveFromAndExclusiveTo() {
        LocalDateTime now = LocalDateTime.now();

        assertThat(search(null, null, null, null, now.minusHours(1), now.plusHours(1))).hasSize(3);
        assertThat(search(null, null, null, null, now.plusHours(1), null)).isEmpty();
        assertThat(search(null, null, null, null, null, now.minusHours(1))).isEmpty();
    }

    private List<Payment> search(String searchText, PaymentStatus status, PaymentGatewayMode gatewayMode,
            String paymentMethod, LocalDateTime from, LocalDateTime to) {
        return paymentRepository.searchForAdmin(searchText, status, gatewayMode, paymentMethod, from, to);
    }

    private static User user(String userId, String firstName, String lastName, String email, String mobile) {
        return User.builder()
                .userId(userId)
                .firstName(firstName)
                .lastName(lastName)
                .email(email)
                .mobileNumber(mobile)
                .passwordHash("hash")
                .currentSkillLevel("L1")
                .enabled(true)
                .build();
    }

    private static Payment payment(String transactionId, User user, Exam exam, PaymentStatus status,
            PaymentGatewayMode gatewayMode, String paymentMethod, String orderId) {
        return Payment.builder()
                .transactionId(transactionId)
                .user(user)
                .exam(exam)
                .amount(new BigDecimal("999.00"))
                .currency("INR")
                .provider("RAZORPAY")
                .paymentStatus(status)
                .gatewayMode(gatewayMode)
                .paymentMethod(paymentMethod)
                .providerOrderId(orderId)
                .providerReference(orderId)
                .build();
    }
}
