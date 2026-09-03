package com.ems.entity;

import java.math.BigDecimal;
import java.time.Instant;

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
@Table(name = "payments")
public class Payment extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false, unique = true, length = 100)
    private String transactionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_ref", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_ref", nullable = false)
    private Exam exam;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "certification_application_ref")
    private CertificationApplication certificationApplication;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 10)
    private String currency;

    @Column(name = "provider", nullable = false, length = 30)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus;

    @Column(name = "payment_date")
    private Instant paymentDate;

    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    /**
     * The gateway-side order this payment is being made against, where the
     * gateway has such a concept (Razorpay does; a QR payload does not).
     *
     * <p>Kept separate from {@link #providerReference} because the two are
     * different identifiers with different lifetimes: the order exists from
     * initiation, the payment id only once the payer has actually paid, and
     * signature verification needs both at once.</p>
     */
    @Column(name = "provider_order_id", length = 100)
    private String providerOrderId;
}
