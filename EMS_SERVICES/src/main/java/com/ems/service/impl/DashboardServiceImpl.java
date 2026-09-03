package com.ems.service.impl;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ems.dto.response.CertificationEligibilityResponse;
import com.ems.dto.response.CertificationSummaryResponse;
import com.ems.dto.response.UserDashboardResponse;
import com.ems.entity.CertificationApplication;
import com.ems.entity.Certification;
import com.ems.entity.ExamAttempt;
import com.ems.entity.ExamSession;
import com.ems.entity.User;
import com.ems.enums.CertificationApplicationStatus;
import com.ems.enums.CertificationLevel;
import com.ems.enums.CertificationStatus;
import com.ems.enums.ExamStatus;
import com.ems.enums.ResultStatus;
import com.ems.exception.ResourceNotFoundException;
import com.ems.repository.CertificationApplicationRepository;
import com.ems.repository.CertificationRepository;
import com.ems.repository.ExamAttemptRepository;
import com.ems.repository.ExamSessionRepository;
import com.ems.repository.UserRepository;
import com.ems.service.CertificationJourneyService;
import com.ems.service.DashboardService;
import com.ems.util.ExamStartWindow;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
@Transactional(readOnly = true)
public class DashboardServiceImpl implements DashboardService {

	private static final String RESTART_NOTE = "Exam invalidated after 3 proctoring violations. Re-apply and complete payment to restart from question 1.";

	/** Statuses that mean "this level is already being worked on, don't offer to apply again". */
	private static final Set<CertificationApplicationStatus> OPEN_APPLICATION_STATUSES = EnumSet.of(
			CertificationApplicationStatus.APPLIED,
			CertificationApplicationStatus.ELIGIBLE,
			CertificationApplicationStatus.IN_PROGRESS);

	/** How many entries the activity feed carries; the dashboard shows a short tail, not an audit log. */
	private static final int ACTIVITY_FEED_LIMIT = 8;

    private final UserRepository userRepository;
    private final CertificationRepository certificationRepository;
    private final CertificationApplicationRepository certificationApplicationRepository;
	private final ExamSessionRepository examSessionRepository;
	private final ExamAttemptRepository examAttemptRepository;
	private final CertificationJourneyService certificationJourneyService;

    @Override
	@Transactional
    @Cacheable(cacheNames = "dashboard", key = "#email")
    public UserDashboardResponse getCurrentUserDashboard(String email) {
	User user = userRepository.findByEmailIgnoreCase(email)
		.orElseThrow(() -> new ResourceNotFoundException("User not found"));

	List<Certification> certifications = certificationRepository.findByUserOrderByIssueDateDesc(user);
	List<CertificationSummaryResponse> certificationHistory = certifications.stream()
		.map(this::toCertificationSummary)
		.toList();

	List<CertificationSummaryResponse> activeCertifications = certifications.stream()
		.filter(c -> c.getCertificationStatus() == CertificationStatus.ACTIVE)
		.map(this::toCertificationSummary)
		.toList();

	List<CertificationApplication> applications = new ArrayList<>(
			certificationApplicationRepository.findByUserOrderByAppliedOnDesc(user));
	reconcileInvalidatedInProgressApplications(applications);

	List<UserDashboardResponse.DashboardExamStatus> examStatuses = applications.stream()
		.map(application -> new UserDashboardResponse.DashboardExamStatus(
			application.getId(),
			application.getCertificationLevel(),
			application.getApplicationStatus(),
			application.getPaymentStatus(),
			application.getAppliedOn(),
			application.getRemarks(),
			application.getScheduledExamTime(),
			ExamStartWindow.opensAt(application.getScheduledExamTime()),
			ExamStartWindow.closesAt(application.getScheduledExamTime()),
			application.getExam() == null ? null : application.getExam().getScheduledStartTime(),
			application.getExam() == null ? null : application.getExam().getScheduledEndTime(),
			hasAttemptInProgress(application)))
		.toList();

	// Terminated attempts count here too: the tile answers "how many attempts
	// did not earn a pass", and an attempt ended by proctoring is one of them.
	long failedApplications = examStatuses.stream()
		.filter(status -> status.applicationStatus().isUnsuccessfulAttempt())
		.count();
	long passedApplications = examStatuses.stream()
		.filter(status -> status.applicationStatus() == CertificationApplicationStatus.PASSED)
		.count();
	long expiredCertifications = certifications.stream()
		.filter(certification -> certification.getCertificationStatus() == CertificationStatus.EXPIRED)
		.count();

	List<ExamAttempt> attempts = examAttemptRepository
		.findByExamSessionUserEmailIgnoreCaseOrderBySubmittedAtDesc(user.getEmail());

	List<UserDashboardResponse.DashboardLevelProgress> levelProgress =
		buildLevelProgress(user, certifications, applications, attempts);
	List<UserDashboardResponse.DashboardActivity> recentActivity =
		buildRecentActivity(applications, attempts, certifications);

	return new UserDashboardResponse(
		new UserDashboardResponse.DashboardUserDetails(
			user.getUserId(),
			user.getFirstName(),
			user.getLastName(),
			user.getEmail(),
			user.getMobileNumber(),
			user.getCurrentSkillLevel(),
			user.getCurrentOrganization(),
			user.getQualification(),
			// Must match UserProfileController's mapping; /api/users/profile/photo
			// never existed and 404'd for anyone who followed it.
			user.getProfilePhotoKey() == null ? null : "/api/users/me/photo"),
		activeCertifications,
		certificationHistory,
		examStatuses,
		levelProgress,
		recentActivity,
		new UserDashboardResponse.DashboardReportSummary(
			examStatuses.size(),
			activeCertifications.size(),
			expiredCertifications,
			passedApplications,
			failedApplications));
    }

    /**
     * One entry per rung of the ladder, in ascending order, whether or not the
     * candidate has touched it — the dashboard draws L1/L2/L3 as a fixed
     * timeline, so a level with no history still needs a row.
     *
     * The AVAILABLE/LOCKED split delegates to the same eligibility rule the
     * apply endpoint enforces, so the dashboard can never offer a "Start" that
     * the next screen would reject.
     */
    private List<UserDashboardResponse.DashboardLevelProgress> buildLevelProgress(
	    User user,
	    List<Certification> certifications,
	    List<CertificationApplication> applications,
	    List<ExamAttempt> attempts) {

	List<UserDashboardResponse.DashboardLevelProgress> progress = new ArrayList<>();

	for (CertificationLevel level : CertificationLevel.values()) {
	    Certification activeCertification = certifications.stream()
		    .filter(certification -> certification.getCertificationLevel() == level)
		    .filter(certification -> certification.getCertificationStatus() == CertificationStatus.ACTIVE)
		    .findFirst()
		    .orElse(null);

	    CertificationApplication openApplication = applications.stream()
		    .filter(application -> application.getCertificationLevel() == level)
		    .filter(application -> OPEN_APPLICATION_STATUSES.contains(application.getApplicationStatus()))
		    .findFirst()
		    .orElse(null);

	    List<ExamAttempt> levelAttempts = attempts.stream()
		    .filter(attempt -> levelOf(attempt) == level)
		    .toList();

	    BigDecimal bestPercentage = levelAttempts.stream()
		    .map(ExamAttempt::getPercentage)
		    .filter(java.util.Objects::nonNull)
		    .max(Comparator.naturalOrder())
		    .orElse(null);

	    UserDashboardResponse.LevelState state;
	    String blockedReason = null;

	    if (activeCertification != null) {
		state = UserDashboardResponse.LevelState.COMPLETED;
	    } else if (openApplication != null) {
		state = UserDashboardResponse.LevelState.IN_PROGRESS;
	    } else {
		CertificationEligibilityResponse eligibility =
			certificationJourneyService.getEligibility(user.getEmail(), level);
		if (eligibility.eligible()) {
		    state = UserDashboardResponse.LevelState.AVAILABLE;
		} else {
		    state = UserDashboardResponse.LevelState.LOCKED;
		    blockedReason = eligibility.message();
		}
	    }

	    progress.add(new UserDashboardResponse.DashboardLevelProgress(
		    level,
		    state,
		    bestPercentage,
		    levelAttempts.size(),
		    activeCertification == null ? null : activeCertification.getId(),
		    activeCertification == null ? null : activeCertification.getExpiryDate(),
		    openApplication == null ? null : openApplication.getId(),
		    blockedReason));
	}

	return progress;
    }

    /**
     * A merged, newest-first tail of what has actually happened to this
     * candidate: applications raised, attempts marked, certificates issued.
     * Assembled here rather than in the browser because the three sources are
     * separate collections with unrelated timestamp types.
     */
    private List<UserDashboardResponse.DashboardActivity> buildRecentActivity(
	    List<CertificationApplication> applications,
	    List<ExamAttempt> attempts,
	    List<Certification> certifications) {

	List<UserDashboardResponse.DashboardActivity> feed = new ArrayList<>();

	for (CertificationApplication application : applications) {
	    feed.add(new UserDashboardResponse.DashboardActivity(
		    UserDashboardResponse.ActivityType.APPLICATION,
		    "Application #" + application.getId() + " submitted for "
			    + application.getCertificationLevel(),
		    toInstant(application.getAppliedOn())));
	}

	for (ExamAttempt attempt : attempts) {
	    if (attempt.getSubmittedAt() == null) {
		continue;
	    }
	    CertificationLevel level = levelOf(attempt);
	    String outcome = attempt.getResultStatus() == ResultStatus.PASS ? "passed" : "not cleared";
	    feed.add(new UserDashboardResponse.DashboardActivity(
		    UserDashboardResponse.ActivityType.RESULT,
		    (level == null ? "Exam" : level + " exam") + " " + outcome
			    + " with " + formatPercentage(attempt.getPercentage()),
		    attempt.getSubmittedAt()));
	}

	for (Certification certification : certifications) {
	    feed.add(new UserDashboardResponse.DashboardActivity(
		    UserDashboardResponse.ActivityType.CERTIFICATE,
		    certification.getCertificationLevel() + " certificate issued",
		    toInstant(certification.getIssueDate())));
	}

	return feed.stream()
		.sorted(Comparator.comparing(
			UserDashboardResponse.DashboardActivity::occurredAt,
			Comparator.nullsLast(Comparator.reverseOrder())))
		.limit(ACTIVITY_FEED_LIMIT)
		.toList();
    }

    private CertificationLevel levelOf(ExamAttempt attempt) {
	ExamSession session = attempt.getExamSession();
	if (session == null || session.getExam() == null) {
	    return null;
	}
	return session.getExam().getCertificationLevel();
    }

    private String formatPercentage(BigDecimal percentage) {
	if (percentage == null) {
	    return "no score";
	}
	return percentage.stripTrailingZeros().toPlainString() + "%";
    }

    private Instant toInstant(LocalDate date) {
	return date == null ? null : date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private void reconcileInvalidatedInProgressApplications(List<CertificationApplication> applications) {
	for (CertificationApplication application : applications) {
	    if (application.getApplicationStatus() != CertificationApplicationStatus.IN_PROGRESS || application.getExam() == null) {
		continue;
	    }

	    /*
	     * Scoped to the application, which is the whole correctness of this
	     * loop. Asking for the candidate's latest session for the exam instead
	     * answered a different question, and a previous attempt's invalidated
	     * session then closed a newly paid application — every time, because a
	     * fresh application has no session of its own to find. The candidate
	     * paid for an exam they could never start.
	     */
	    ExamSession latestSession = examSessionRepository
		    .findTopByCertificationApplicationOrderBySessionStartTimeDescIdDesc(application)
		    .orElse(null);

	    if (latestSession == null || latestSession.getSessionStatus() != ExamStatus.INVALIDATED) {
		continue;
	    }

	    // Same event as the invalidation handler's, reached late, so it must
	    // reach the same outcome: the attempt was ended, never scored.
	    application.setApplicationStatus(CertificationApplicationStatus.TERMINATED);
	    String remarks = application.getRemarks();
	    if (remarks == null || remarks.isBlank()) {
		application.setRemarks(RESTART_NOTE);
	    } else if (!remarks.contains(RESTART_NOTE)) {
		application.setRemarks(remarks + " | " + RESTART_NOTE);
	    }
	    certificationApplicationRepository.save(application);
	}
    }

    /** Whether this application's attempt is still open and rejoinable. */
    private boolean hasAttemptInProgress(CertificationApplication application) {
	return examSessionRepository
		.findTopByCertificationApplicationOrderBySessionStartTimeDescIdDesc(application)
		.map(session -> session.getSessionStatus() == ExamStatus.IN_PROGRESS)
		.orElse(false);
    }

    private CertificationSummaryResponse toCertificationSummary(Certification certification) {
	return new CertificationSummaryResponse(
		certification.getId(),
		certification.getCertificationLevel(),
		certification.getCertificationStatus(),
		certification.getIssueDate(),
		certification.getExpiryDate());
    }
}
