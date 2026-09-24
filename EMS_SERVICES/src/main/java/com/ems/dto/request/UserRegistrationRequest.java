package com.ems.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The user id is not accepted from the caller: it is derived from the name and
 * email by {@link com.ems.util.UserIdGenerator} once registration validates.
 */
@Getter
@Setter
@NoArgsConstructor
public class UserRegistrationRequest {

    @NotBlank
    @Size(max = 100)
    private String firstName;

    @NotBlank
    @Size(max = 100)
    private String lastName;

    @NotBlank
    @Email
    @Size(max = 255)
    private String email;

    @NotBlank
    @Size(max = 20)
    private String mobileNumber;

    @NotBlank
    @Size(min = 8, max = 128)
    private String password;

    private String profilePhoto;

    @Size(max = 1000)
    private String address;

    private Integer yearsOfExperience;

    @NotBlank
    @Pattern(regexp = "^(L1|L2|L3)$", message = "Current skill level must be L1, L2, or L3")
    private String currentSkillLevel;

    @Size(max = 255)
    private String currentOrganization;

    @Size(max = 255)
    private String qualification;

    @Size(max = 255)
    private String fatherName;
}
