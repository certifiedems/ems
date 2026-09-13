package com.ems.service;

import java.util.List;

import com.ems.dto.request.ExamAttemptPolicyRequest;
import com.ems.dto.response.ExamAttemptPolicyResponse;
import com.ems.enums.CertificationLevel;

public interface ExamAttemptPolicyService {

    /**
     * How many sittings a payment made now buys at this level: the saved
     * policy, or a single attempt when nothing has been saved.
     */
    int attemptsPerPayment(CertificationLevel level);

    /** Every level's allowance, whether saved or still the built-in single attempt. */
    List<ExamAttemptPolicyResponse> listPolicies();

    /** Saves a level's allowance. Payments already made keep the allowance they were made under. */
    ExamAttemptPolicyResponse updatePolicy(CertificationLevel level, ExamAttemptPolicyRequest request);
}
