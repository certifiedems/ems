package com.ems.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ems.dto.request.ExamProgressSaveRequest;
import com.ems.dto.request.ExamStartRequest;
import com.ems.dto.request.ExamWorkflowApplicationRequest;
import com.ems.dto.request.PaymentCompletionRequest;
import com.ems.dto.request.PaymentInitiationRequest;
import com.ems.dto.request.WorkflowExamScheduleRequest;
import com.ems.dto.response.ExamAttemptAllowanceResponse;
import com.ems.dto.response.ExamProgressResponse;
import com.ems.dto.response.ExamSessionQuestionResponse;
import com.ems.dto.response.ExamSlotAvailabilityResponse;
import com.ems.dto.response.ExamStartResponse;
import com.ems.dto.response.ExamWorkflowApplicationResponse;
import com.ems.dto.response.PaymentResponse;
import com.ems.dto.response.WorkflowExamOptionResponse;
import com.ems.enums.CertificationLevel;

public interface ExamWorkflowService {

    WorkflowExamOptionResponse getWorkflowOptions(String email, CertificationLevel certificationLevel);

    /** How many sittings a payment made now buys at this level, for the candidate about to pay. */
    ExamAttemptAllowanceResponse getAttemptAllowance(CertificationLevel certificationLevel);

    ExamWorkflowApplicationResponse createApplication(String email,
            ExamWorkflowApplicationRequest request);

    PaymentResponse initiatePayment(String email, Long applicationId,
            PaymentInitiationRequest request);

    PaymentResponse completePayment(String email, Long applicationId,
            PaymentCompletionRequest request);

    ExamWorkflowApplicationResponse scheduleExam(String email, Long applicationId,
            WorkflowExamScheduleRequest request);

    /**
     * The slots this application's exam can be booked into, starting at or after
     * {@code from} and before {@code to}, with the seats left in each.
     */
    ExamSlotAvailabilityResponse getSlotAvailability(String email, Long applicationId, Instant from, Instant to);

    ExamStartResponse startExam(String email, Long applicationId, ExamStartRequest request);

    List<ExamWorkflowApplicationResponse> getReApplyableApplications(String email);

    /** Fetches the full payload for a specific question within an active exam session. {@code questionNumber} is 1-indexed (1..30). */
    ExamSessionQuestionResponse getSessionQuestion(String email, UUID sessionToken, int questionNumber);

    /** Re-applies for the same certification level and exam as a previously FAILED or INVALIDATED application. */
    ExamWorkflowApplicationResponse reApply(String email, Long failedApplicationId);

    /**
     * Records the candidate's answers so far against a running session, so an
     * interrupted attempt can be resumed with its work intact.
     */
    ExamProgressResponse saveProgress(String email, UUID sessionToken, ExamProgressSaveRequest request);

    /** The draft last saved for a running session, for a client that reconnects. */
    ExamProgressResponse getProgress(String email, UUID sessionToken);
}
