package com.ems.service;

import com.ems.audit.AuditEvent;

/**
 * Writes to the admin-facing audit trail (see {@link com.ems.entity.AuditLog}).
 *
 * <p>{@link #record} is best-effort: a failure to persist an audit row is
 * logged and swallowed rather than propagated, so a broken audit table can
 * never be the reason a login, payment or admin action itself fails.
 */
public interface AuditService {

    void record(AuditEvent event);
}
