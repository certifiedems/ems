package com.ems.service;

import java.util.List;

import com.ems.dto.request.ProctoringPolicyRequest;
import com.ems.dto.response.CandidateProctoringPolicyResponse;
import com.ems.dto.response.ExamProctoringPolicySummaryResponse;
import com.ems.dto.response.ProctoringPolicyResponse;
import com.ems.entity.ExamSession;

public interface ProctoringPolicyService {

    /**
     * The rules an attempt at this exam is judged by: the exam's own policy if it
     * has one, else the default policy, else the built-in rules.
     */
    EffectiveProctoringPolicy resolveForExam(Long examId);

    /**
     * The rules an attempt is judged by: the ones captured when it started, so an
     * administrator's change never alters an attempt already under way. An
     * attempt begun before rules were captured follows {@link #resolveForExam}.
     */
    EffectiveProctoringPolicy resolveForSession(ExamSession session);

    /** The exam's current rules, serialised for capture on an attempt as it starts. */
    String snapshotForExam(Long examId);

    ProctoringPolicyResponse getDefaultPolicy();

    ProctoringPolicyResponse updateDefaultPolicy(ProctoringPolicyRequest request);

    /** Every exam, and whether it runs under rules of its own. */
    List<ExamProctoringPolicySummaryResponse> listExamPolicies();

    /** The rules an exam runs under, whether its own or inherited from the default. */
    ProctoringPolicyResponse getExamPolicy(Long examId);

    ProctoringPolicyResponse updateExamPolicy(Long examId, ProctoringPolicyRequest request);

    /** Drops an exam's own rules, so its attempts go back to the default. */
    ProctoringPolicyResponse resetExamPolicy(Long examId);

    /** The rules the exam client runs under, for an application the caller owns. */
    CandidateProctoringPolicyResponse getPolicyForApplication(String email, Long applicationId);
}
