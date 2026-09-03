package com.ems.controller;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ems.config.MaintenanceProperties;
import com.ems.dto.response.ApiResponse;
import com.ems.dto.response.SystemStatusResponse;
import com.ems.util.CorrelationIdUtil;

import lombok.RequiredArgsConstructor;

/**
 * The one endpoint the UI can call when it suspects the API is gone.
 *
 * <p>Lives under {@code /api} rather than beside the actuator probes on purpose:
 * CORS is registered for {@code /api/**} only, so a browser on the deployed
 * frontend origin can reach this but would be blocked calling
 * {@code /actuator/health}. It is also unauthenticated — a client whose token
 * expired while the backend was down still needs to be told why.
 *
 * <p>No {@code @ConditionalOnProperty} guard, unlike most controllers here: this
 * has to answer in every data mode, because "which mode is running" is one of
 * the things a deploy changes.
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemStatusController {

    private final MaintenanceProperties maintenanceProperties;

    @Value("${app.version:dev}")
    private String appVersion;

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<SystemStatusResponse>> getStatus() {
        boolean maintenance = maintenanceProperties.isEnabled();

        SystemStatusResponse status = SystemStatusResponse.builder()
                .status(maintenance ? "MAINTENANCE" : "UP")
                .maintenance(maintenance)
                // Nothing to explain while the service is up, and echoing the
                // configured copy anyway would let a stale message leak onto a
                // healthy screen if the UI ever rendered it unconditionally.
                .message(maintenance ? maintenanceProperties.getMessage() : "")
                .eta(maintenance ? maintenanceProperties.getEta() : "")
                .retryAfterSeconds(maintenanceProperties.getRetryAfterSeconds())
                .version(appVersion)
                .serverTime(Instant.now())
                .build();

        return ResponseEntity.ok(ApiResponse.success(
                "System status fetched successfully", status, CorrelationIdUtil.getOrCreateTraceId()));
    }
}
