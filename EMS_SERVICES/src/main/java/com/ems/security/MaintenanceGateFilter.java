package com.ems.security;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ems.config.MaintenanceProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Closes the API with 503 while {@code app.maintenance.enabled} is on.
 *
 * <p>The point is not to hide an outage but to create one deliberately: during a
 * Flyway migration or a schema-changing deploy, a half-applied database will
 * happily accept writes it cannot honour. A 503 with {@code Retry-After} is the
 * standard, machine-readable way to say "not now, come back" — browsers, the
 * frontend's own probe, and Razorpay's webhook retries all understand it.
 *
 * <p>Sits after {@link JwtAuthenticationFilter} in the chain, so the
 * SecurityContext is already populated and the admin bypass below can read it,
 * but still ahead of authorization — an anonymous caller gets the 503 that
 * explains the situation rather than a 401 that does not.
 */
@Component
public class MaintenanceGateFilter extends OncePerRequestFilter {

    /**
     * Paths that stay open while the gate is shut.
     *
     * <p>The status endpoint is the whole point — it is how the UI learns why it
     * is being refused. The three auth endpoints are open so an administrator can
     * still obtain a token and use the bypass below to verify the system before
     * reopening it; a non-admin who signs in during a window simply meets a 503
     * on their next call and lands on the maintenance screen, which is correct.
     * Actuator is open so the platform's own health checks do not read planned
     * maintenance as a crashed instance and start cycling the container.
     */
    private static final List<String> ALWAYS_OPEN = List.of(
            "/api/system/",
            "/api/auth/login",
            "/api/auth/refresh-token",
            "/api/auth/logout",
            "/actuator/");

    private final MaintenanceProperties maintenanceProperties;
    private final ObjectMapper objectMapper;

    public MaintenanceGateFilter(MaintenanceProperties maintenanceProperties, ObjectMapper objectMapper) {
        this.maintenanceProperties = maintenanceProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!maintenanceProperties.isEnabled() || !isGated(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isAdmin()) {
            filterChain.doFilter(request, response);
            return;
        }

        writeMaintenanceResponse(response);
    }

    private boolean isGated(HttpServletRequest request) {
        String path = request.getRequestURI();

        // Only the API is gated. Anything else this host serves — and, more
        // importantly, the CORS preflight — must pass: a blocked OPTIONS carries
        // no Access-Control headers, so the browser would report a CORS failure
        // and the UI would never get to read the 503 it was meant to explain.
        if (!path.startsWith("/api/") || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        return ALWAYS_OPEN.stream().noneMatch(path::startsWith);
    }

    private boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
    }

    private void writeMaintenanceResponse(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(maintenanceProperties.getRetryAfterSeconds()));

        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "success", false,
                "status", "MAINTENANCE",
                "message", maintenanceProperties.getMessage(),
                "eta", maintenanceProperties.getEta(),
                "retryAfterSeconds", maintenanceProperties.getRetryAfterSeconds()));
    }
}
