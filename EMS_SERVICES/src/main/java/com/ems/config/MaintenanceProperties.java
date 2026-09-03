package com.ems.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operator switch for planned downtime — a database migration, a schema change,
 * or a CI/CD deploy that needs the API quiet while it runs.
 *
 * <p>Every field is environment-driven (see {@code app.maintenance} in
 * application.yml) so the switch is thrown by changing a variable on the host
 * and restarting, with no code change and no rebuild of the frontend. While it
 * is on, {@code MaintenanceGateFilter} answers the API with 503 and
 * {@code SystemStatusController} keeps reporting the reason, which is what the
 * UI renders on its maintenance screen.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.maintenance")
public class MaintenanceProperties {

    /** Whether the API is closed for planned work. Off unless deliberately set. */
    private boolean enabled = false;

    /** Shown to users on the maintenance screen. Keep it plain and specific. */
    private String message = "We are performing scheduled maintenance. The service will be back shortly.";

    /**
     * Free text, not a timestamp — "about 30 minutes", "by 02:00 IST". Empty
     * means the UI simply omits the line rather than inventing an estimate.
     */
    private String eta = "";

    /**
     * Seconds to put in the {@code Retry-After} header on the 503. Also what the
     * UI waits before its next probe, so a long window does not turn into a
     * retry storm from every open tab.
     */
    private int retryAfterSeconds = 60;
}
