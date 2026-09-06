package com.ems.dto.response;

import java.time.Instant;

import com.ems.audit.AuditEventType;
import com.ems.audit.AuditOutcome;

public record AdminAuditLogResponse(
        Long id,
        AuditEventType eventType,
        AuditOutcome outcome,
        String actorEmail,
        String actorUserId,
        String targetUserId,
        String targetType,
        String targetId,
        String description,
        String ipAddress,
        String correlationId,
        Instant occurredAt) {
}
