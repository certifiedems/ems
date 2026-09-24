package com.ems.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The user id is not accepted from the caller: it is derived from the name and
 * email by {@link com.ems.util.UserIdGenerator} once registration validates.
 */
public record RegisterRequest(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 20) String mobileNumber,
        @NotBlank @Size(min = 8, max = 128) String password,
        String profilePhoto,
        @Size(max = 1000) String address,
        Integer yearsOfExperience,
        @NotBlank @Pattern(regexp = "^(L1|L2|L3)$", message = "Current skill level must be L1, L2, or L3") String currentSkillLevel,
        @Size(max = 255) String currentOrganization,
        @Size(max = 255) String qualification,
        @Size(max = 255) String fatherName) {
}
