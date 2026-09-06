package com.ems.service.impl;

import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.ems.audit.AuditEvent;
import com.ems.constants.AppConstants;
import com.ems.entity.AuditLog;
import com.ems.repository.AuditLogRepository;
import com.ems.service.AuditService;
import com.ems.util.CorrelationIdUtil;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
public class AuditServiceImpl implements AuditService {

    private final AuditLogRepository auditLogRepository;
    private final AuditorAware<String> auditorProvider;

    /**
     * Runs in its own transaction so an audit row for a failed operation (a
     * bad login, an expired reset token) still gets written after the caller's
     * transaction rolls back, and commits independently of whatever the
     * caller does next. Any failure here is caught, not rethrown: recording
     * history must never be the reason the real operation fails.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditEvent event) {
        try {
            String actorEmail = event.actorEmail() != null
                    ? event.actorEmail()
                    : auditorProvider.getCurrentAuditor().orElse(AppConstants.SYSTEM_USER);

            AuditLog entry = AuditLog.builder()
                    .eventType(event.eventType())
                    .outcome(event.outcome())
                    .actorEmail(actorEmail)
                    .actorUserId(event.actorUserId())
                    .targetUserId(event.targetUserId())
                    .targetType(event.targetType())
                    .targetId(event.targetId())
                    .description(event.description())
                    .ipAddress(currentRemoteAddress())
                    .correlationId(CorrelationIdUtil.getOrCreateTraceId())
                    .occurredAt(Instant.now())
                    .build();

            auditLogRepository.save(entry);
        } catch (Exception ex) {
            log.error("Failed to persist audit log entry for eventType={}: {}",
                    event.eventType(), ex.getMessage(), ex);
        }
    }

    private String currentRemoteAddress() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
