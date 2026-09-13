package com.ems.entity;

import java.time.Instant;
import java.time.LocalDate;

import com.ems.enums.CertificationApplicationStatus;
import com.ems.enums.CertificationLevel;
import com.ems.enums.PaymentStatus;

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
@Table(name = "certification_applications")
public class CertificationApplication extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_ref", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_ref")
    private Exam exam;

    @Enumerated(EnumType.STRING)
    @Column(name = "certification_level", nullable = false, length = 10)
    private CertificationLevel certificationLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "application_status", nullable = false, length = 20)
    private CertificationApplicationStatus applicationStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus;

    @Column(name = "applied_on", nullable = false)
    private LocalDate appliedOn;

    @Column(name = "scheduled_exam_time")
    private Instant scheduledExamTime;

    @Column(name = "remarks", length = 1000)
    private String remarks;

    /**
     * Which sitting on its payment this application is: 1 for the application
     * the fee was paid against, 2 and up for the retakes that payment covers.
     */
    @Builder.Default
    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber = 1;

    /**
     * How many sittings the payment behind this application covers.
     *
     * <p>Fixed when the payment succeeds, from the level's attempt policy at that
     * moment, and copied onto each retake: an administrator's later change
     * reaches payments made after it, never one already made. Null until paid;
     * see {@link com.ems.util.ExamAttemptAllowance} for how that is read.</p>
     */
    @Column(name = "attempts_allowed")
    private Integer attemptsAllowed;

    /**
     * The application whose payment covers this one; null on that application
     * itself. Always the paid application rather than the attempt before, so
     * every retake reaches the payment in one step.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paid_application_ref")
    private CertificationApplication paidApplication;
}
