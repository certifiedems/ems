package com.ems.dto.response;

import java.time.Instant;

import lombok.Builder;

/**
 * Liveness payload for the frontend's maintenance screen.
 *
 * <p>Deliberately free of any database or downstream dependency: it has to stay
 * answerable in exactly the situations it exists to describe — mid-migration,
 * mid-deploy, or with the connection pool exhausted.
 */
@Builder
public record SystemStatusResponse(
        /** "UP" or "MAINTENANCE". */
        String status,
        boolean maintenance,
        /** Operator-supplied explanation; empty while the service is up. */
        String message,
        /** Free-text estimate ("about 30 minutes"); empty when unknown. */
        String eta,
        /** Seconds the client should wait before probing again. */
        int retryAfterSeconds,
        /** Build identifier, set by the deploy pipeline via APP_VERSION. */
        String version,
        Instant serverTime) {
}
