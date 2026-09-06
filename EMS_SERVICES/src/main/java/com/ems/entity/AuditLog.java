package com.ems.entity;

import java.time.Instant;

import com.ems.audit.AuditEventType;
import com.ems.audit.AuditOutcome;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One row in the admin-facing audit trail: who did what, to whom, and whether
 * it succeeded.
 *
 * <p>Deliberately not a {@link BaseAuditEntity}: that base class's
 * {@code createdBy} is populated from whatever the security context holds at
 * persist time, which is anonymous for login/register/password-reset requests
 * — exactly the events this table exists to record. {@link #actorEmail} is
 * set explicitly by {@link com.ems.service.AuditService} instead, so it is
 * always the true actor, not whatever the request happened to be authenticated
 * as.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private AuditEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private AuditOutcome outcome;

    @Column(name = "actor_email", length = 255)
    private String actorEmail;

    @Column(name = "actor_user_id", length = 50)
    private String actorUserId;

    @Column(name = "target_user_id", length = 50)
    private String targetUserId;

    @Column(name = "target_type", length = 40)
    private String targetType;

    @Column(name = "target_id", length = 100)
    private String targetId;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
