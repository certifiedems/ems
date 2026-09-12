package com.ems.service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import com.ems.audit.AuditEventType;
import com.ems.audit.AuditOutcome;
import com.ems.dto.request.AdminPaymentFilter;
import com.ems.dto.response.AdminAuditLogResponse;
import com.ems.dto.response.AdminPaymentReconciliation;
import com.ems.dto.response.AdminPaymentResponse;
import com.ems.dto.response.AdminUserResponse;
import com.ems.dto.response.AdminViolationResponse;
import com.ems.dto.response.CertificateResponse;
import com.ems.dto.response.CertificateVerificationResponse;
import com.ems.dto.response.CertificationApplicationResponse;
import com.ems.dto.response.CertificationSummaryResponse;
import com.ems.dto.response.QuestionResponse;
import com.ems.dto.response.VideoRecordingResponse;
import com.ems.enums.CertificationLevel;
import com.ems.enums.QuestionSeverity;
import com.ems.enums.ReportFormat;

public interface AdminPortalService {

    List<AdminUserResponse> searchUsers(String searchText, Boolean enabled);

    AdminUserResponse getUserById(Long userId);

    AdminUserResponse setUserEnabled(Long userId, boolean enabled);

    AdminUserResponse setUserLocked(Long userId, boolean locked);

    List<QuestionResponse> searchQuestions(String questionCode, CertificationLevel level,
            QuestionSeverity severity, Boolean active, String searchText);

    List<AdminPaymentResponse> searchPayments(AdminPaymentFilter filter);

    /**
     * The rows {@link #searchPayments} returns for the same filter, as a
     * spreadsheet.
     *
     * @param zone the zone the admin reads time in; timestamps are written in it
     *             and the column headers name it
     */
    ReportFileContent exportPayments(AdminPaymentFilter filter, ReportFormat format, ZoneId zone);

    AdminPaymentReconciliation reconcilePayment(String transactionId);

    PaymentReceiptContent downloadPaymentReceipt(String transactionId);

    List<CertificationApplicationResponse> getAllApplications();

    List<CertificationSummaryResponse> getAllCertifications();

    List<CertificateResponse> getAllCertificates();

    CertificateVerificationResponse verifyCertificate(String certificateNumber);

    List<AdminViolationResponse> getAllViolations();

    List<AdminViolationResponse> getViolationsForSession(Long sessionId);

    List<VideoRecordingResponse> getAllRecordings();

    List<VideoRecordingResponse> getRecordingsForSession(Long sessionId);

    List<AdminAuditLogResponse> searchAuditLogs(AuditEventType eventType, AuditOutcome outcome, String actor,
            String targetUserId, Instant from, Instant to, int limit);
}
