package com.ems.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ems.audit.AuditEventType;
import com.ems.audit.AuditOutcome;
import com.ems.entity.AuditLog;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * Filters are all optional so the admin console can narrow a firehose of
     * events by any combination of type, outcome, actor or target without a
     * different query per combination. {@code actor} matches either the
     * actor's email or business user id, since the console lets an admin
     * search by whichever one they have on hand.
     */
    @Query("""
            select a from AuditLog a
            where (:eventType is null or a.eventType = :eventType)
            and (:outcome is null or a.outcome = :outcome)
            and (:actor is null
                 or lower(a.actorEmail) like :actor
                 or lower(a.actorUserId) like :actor)
            and (:targetUserId is null or lower(a.targetUserId) = :targetUserId)
            and (:from is null or a.occurredAt >= :from)
            and (:to is null or a.occurredAt <= :to)
            order by a.occurredAt desc
            """)
    List<AuditLog> search(
            @Param("eventType") AuditEventType eventType,
            @Param("outcome") AuditOutcome outcome,
            @Param("actor") String actor,
            @Param("targetUserId") String targetUserId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);
}
