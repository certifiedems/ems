package com.ems.dto.response;

import java.time.Instant;

public record AdminUserResponse(
        Long id,
        String userId,
        String firstName,
        String lastName,
        String email,
        String mobileNumber,
        String currentSkillLevel,
        String currentOrganization,
        String qualification,
        Integer yearsOfExperience,
        boolean enabled,
        boolean accountNonLocked,
        Instant createdAt,
        Instant lastLoginAt) {
}
