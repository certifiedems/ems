package com.ems.audit;

import lombok.Builder;

/**
 * What to write to the audit trail, handed to {@link com.ems.service.AuditService}.
 *
 * <p>{@code actorEmail}/{@code actorUserId} may be left null when the caller runs
 * inside the actor's own authenticated request (an admin flipping a user's
 * status, say): the service resolves the current principal from the security
 * context in that case. Login, registration and password-reset flows pass the
 * actor explicitly instead, since the security context does not yet — or in a
 * failed login, never — hold that identity.
 *
 * <p>{@code targetUserId} names whose record the event is about, which for a
 * self-service action (login, password change) is the actor themselves, and
 * for an admin action is the affected user, so the admin console can filter
 * "everything that happened to user X" regardless of who did it.
 */
@Builder
public record AuditEvent(
        AuditEventType eventType,
        AuditOutcome outcome,
        String actorEmail,
        String actorUserId,
        String targetUserId,
        String targetType,
        String targetId,
        String description) {
}
