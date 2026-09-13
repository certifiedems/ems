package com.ems.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One copy of the exam page checking in.
 *
 * @param clientId a random id the exam page draws once per browser tab and keeps
 *                 across reloads of that tab. It identifies a copy of the page,
 *                 not a person, and is held only in memory on the server.
 */
public record ExamHeartbeatRequest(

        @NotBlank(message = "clientId is required")
        @Size(max = 64, message = "clientId must not exceed 64 characters")
        String clientId) {
}
