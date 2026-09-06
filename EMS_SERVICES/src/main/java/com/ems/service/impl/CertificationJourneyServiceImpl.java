package com.ems.service.impl;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ems.audit.AuditEvent;
import com.ems.audit.AuditEventType;
import com.ems.audit.AuditOutcome;
import com.ems.dto.request.CertificationApplicationRequest;
import com.ems.dto.request.CertificationCompletionRequest;
import com.ems.dto.response.CertificationApplicationResponse;
import com.ems.dto.response.CertificationEligibilityResponse;
import com.ems.dto.response.CertificationHistoryEventResponse;
import com.ems.dto.response.CertificationJourneyHistoryResponse;
import com.ems.dto.response.CertificationSummaryResponse;
import com.ems.entity.Certification;
import com.ems.entity.CertificationApplication;
import com.ems.entity.CertificationHistory;
import com.ems.entity.User;
import com.ems.enums.CertificationApplicationStatus;
import com.ems.enums.CertificationHistoryEventType;
import com.ems.enums.CertificationLevel;
import com.ems.enums.CertificationStatus;
import com.ems.exception.BusinessException;
import com.ems.exception.ResourceNotFoundException;
import com.ems.repository.CertificationApplicationRepository;
import com.ems.repository.CertificationHistoryRepository;
import com.ems.repository.CertificationRepository;
import com.ems.repository.UserRepository;
import com.ems.service.AuditService;
import com.ems.service.CertificationJourneyService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
@Transactional
public class CertificationJourneyServiceImpl implements CertificationJourneyService {

    private static final Set<CertificationApplicationStatus> OPEN_APPLICATION_STATUSES = Set.of(
	    CertificationApplicationStatus.APPLIED,
	    CertificationApplicationStatus.ELIGIBLE,
	    CertificationApplicationStatus.IN_PROGRESS);

    private final UserRepository userRepository;
    private final CertificationRepository certificationRepository;
    private final CertificationApplicationRepository certificationApplicationRepository;
    private final CertificationHistoryRepository certificationHistoryRepository;
    private final AuditService auditService;

    @Override
    @Transactional(readOnly = true)
    public CertificationEligibilityResponse getEligibility(String email, CertificationLevel requestedLevel) {
	User user = findUserByEmail(email);
	return determineEligibility(user, requestedLevel);
    }

    @Override
    public CertificationApplicationResponse apply(String email, CertificationApplicationRequest request) {
	User user = findUserByEmail(email);

	CertificationEligibilityResponse eligibility = determineEligibility(user, request.certificationLevel());
	if (!eligibility.eligible()) {
	    throw new BusinessException(eligibility.message(), HttpStatus.BAD_REQUEST);
	}

	boolean hasOpenApplication = certificationApplicationRepository.existsByUserAndCertificationLevelAndApplicationStatusIn(
		user,
		request.certificationLevel(),
		OPEN_APPLICATION_STATUSES);
	if (hasOpenApplication) {
	    throw new BusinessException(
		    "An application is already in progress for " + request.certificationLevel(),
		    HttpStatus.CONFLICT);
	}

	CertificationApplication application = CertificationApplication.builder()
		.user(user)
		.certificationLevel(request.certificationLevel())
		.applicationStatus(CertificationApplicationStatus.APPLIED)
		.appliedOn(LocalDate.now())
		.remarks(request.remarks())
		.build();

	CertificationApplication savedApplication = certificationApplicationRepository.save(application);
	log.info("Certification application created: userId={}, level={}, applicationId={}",
		user.getUserId(), request.certificationLevel(), savedApplication.getId());
	return toApplicationResponse(savedApplication);
    }

    @Override
    public CertificationApplicationResponse completeApplication(Long applicationId, CertificationCompletionRequest request) {
	CertificationApplication application = certificationApplicationRepository.findById(applicationId)
		.orElseThrow(() -> new ResourceNotFoundException("Certification application not found"));

	if (Boolean.TRUE.equals(request.passed())) {
	    application.setApplicationStatus(CertificationApplicationStatus.PASSED);
	    application.setRemarks(request.remarks());
	    issueCertification(application);
	} else {
	    application.setApplicationStatus(CertificationApplicationStatus.FAILED);
	    application.setRemarks(request.remarks());
	}

	CertificationApplication savedApplication = certificationApplicationRepository.save(application);
	auditService.record(AuditEvent.builder()
		.eventType(AuditEventType.ADMIN_ACTION)
		.outcome(AuditOutcome.SUCCESS)
		.targetUserId(savedApplication.getUser().getUserId())
		.targetType("CERTIFICATION_APPLICATION")
		.targetId(String.valueOf(applicationId))
		.description("Marked application #" + applicationId + " as "
			+ savedApplication.getApplicationStatus())
		.build());
	return toApplicationResponse(savedApplication);
    }

    @Override
    @Transactional(readOnly = true)
    public CertificationJourneyHistoryResponse getHistory(String email) {
	User user = findUserByEmail(email);

	List<CertificationApplicationResponse> applications = certificationApplicationRepository
		.findByUserOrderByAppliedOnDesc(user).stream()
		.map(this::toApplicationResponse)
		.toList();

	List<CertificationSummaryResponse> certifications = certificationRepository
		.findByUserOrderByIssueDateDesc(user).stream()
		.map(this::toCertificationSummary)
		.toList();

	List<CertificationHistoryEventResponse> events = certificationHistoryRepository
		.findByCertificationUserOrderByEventTimestampDesc(user).stream()
		.map(this::toHistoryEvent)
		.toList();

	return new CertificationJourneyHistoryResponse(applications, certifications, events);
    }

    private CertificationEligibilityResponse determineEligibility(User user, CertificationLevel requestedLevel) {
	return switch (requestedLevel) {
	    case L1 -> new CertificationEligibilityResponse(
		    requestedLevel,
		    true,
		    "Eligible to apply for L1",
		    null,
		    null);
	    case L2 -> evaluateSequentialEligibility(user, requestedLevel, CertificationLevel.L1);
	    case L3 -> evaluateSequentialEligibility(user, requestedLevel, CertificationLevel.L2);
	};
    }

    private CertificationEligibilityResponse evaluateSequentialEligibility(
	    User user,
	    CertificationLevel requestedLevel,
	    CertificationLevel prerequisiteLevel) {
	Certification prerequisiteCertification = certificationRepository
		.findFirstByUserAndCertificationLevelAndCertificationStatusOrderByExpiryDateDesc(
			user,
			prerequisiteLevel,
			CertificationStatus.ACTIVE)
		.orElse(null);

	if (prerequisiteCertification == null) {
	    CertificationApplication latestPrerequisiteApplication = certificationApplicationRepository
		    .findTopByUserAndCertificationLevelOrderByAppliedOnDescIdDesc(user, prerequisiteLevel)
		    .orElse(null);

	    if (latestPrerequisiteApplication != null
		    && latestPrerequisiteApplication.getApplicationStatus().isUnsuccessfulAttempt()) {
		return new CertificationEligibilityResponse(
			requestedLevel,
			false,
			prerequisiteLevel + " examination is failed. Pass " + prerequisiteLevel
				+ " before applying for " + requestedLevel,
			prerequisiteLevel,
			null);
	    }

	    return new CertificationEligibilityResponse(
		    requestedLevel,
		    false,
		    prerequisiteLevel + " certification must be passed first",
		    prerequisiteLevel,
		    null);
	}

	if (prerequisiteCertification.getExpiryDate().isBefore(LocalDate.now())) {
	    return new CertificationEligibilityResponse(
		    requestedLevel,
		    false,
		    prerequisiteLevel + " certification is expired and recertification is required",
		    prerequisiteLevel,
		    prerequisiteCertification.getExpiryDate());
	}

	return new CertificationEligibilityResponse(
		requestedLevel,
		true,
		"Eligible to apply for " + requestedLevel,
		prerequisiteLevel,
		prerequisiteCertification.getExpiryDate());
    }

    private void issueCertification(CertificationApplication application) {
	Certification certification = Certification.builder()
		.user(application.getUser())
		.certificationLevel(application.getCertificationLevel())
		.certificationStatus(CertificationStatus.ACTIVE)
		.issueDate(LocalDate.now())
		.expiryDate(LocalDate.now().plusYears(1))
		.build();

	Certification savedCertification = certificationRepository.save(certification);
	CertificationHistory history = CertificationHistory.builder()
		.certification(savedCertification)
		.eventType(CertificationHistoryEventType.ISSUED)
		.eventDescription("Certification issued after successful completion of "
			+ application.getCertificationLevel())
		.eventTimestamp(Instant.now())
		.build();
	certificationHistoryRepository.save(history);
    }

    private User findUserByEmail(String email) {
	return userRepository.findByEmailIgnoreCase(email)
		.orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private CertificationApplicationResponse toApplicationResponse(CertificationApplication application) {
	return new CertificationApplicationResponse(
		application.getId(),
		application.getUser().getUserId(),
		application.getCertificationLevel(),
		application.getApplicationStatus(),
		application.getAppliedOn(),
		application.getRemarks());
    }

    private CertificationSummaryResponse toCertificationSummary(Certification certification) {
	return new CertificationSummaryResponse(
		certification.getId(),
		certification.getCertificationLevel(),
		certification.getCertificationStatus(),
		certification.getIssueDate(),
		certification.getExpiryDate());
    }

    private CertificationHistoryEventResponse toHistoryEvent(CertificationHistory history) {
	return new CertificationHistoryEventResponse(
		history.getCertification().getId(),
		history.getEventType(),
		history.getEventDescription(),
		history.getEventTimestamp());
    }
}
