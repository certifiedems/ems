package com.ems.entity;

import com.ems.enums.CertificationLevel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * How many attempts one payment buys at a certification level.
 *
 * <p>One row per level, saved from the admin console. A level with no row gets
 * a single attempt per payment — see
 * {@link com.ems.util.ExamAttemptAllowance#DEFAULT_ATTEMPTS_PER_PAYMENT}.</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true, of = "id")
@Entity
@Table(name = "certification_attempt_policies")
public class CertificationAttemptPolicy extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "certification_level", nullable = false, unique = true, length = 10)
    private CertificationLevel certificationLevel;

    /** Sittings one payment covers, the first one included. */
    @Column(name = "attempts_per_payment", nullable = false)
    private int attemptsPerPayment;

    /** Guards against two administrators overwriting each other. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
