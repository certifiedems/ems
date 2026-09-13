package com.ems.service.impl;

import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.ems.dto.response.ViolationSummaryResponse;
import com.ems.enums.ExamStatus;
import com.ems.enums.ViolationType;
import com.ems.service.ExamHeartbeatService;
import com.ems.service.ProctoringService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
public class ExamHeartbeatServiceImpl implements ExamHeartbeatService {

	static final String MULTIPLE_LOGIN_DESCRIPTION =
			"The exam was open in two places at once: two browsers, tabs or devices kept checking in for this attempt.";

	private final ProctoringService proctoringService;
	private final ExamPresenceTracker examPresenceTracker;
	private final ViolationStrikeRecorder violationStrikeRecorder;

	/**
	 * Deliberately not transactional. The summary is read, a violation if any is
	 * written in the recorder's own transaction, and the summary is read again;
	 * one surrounding transaction would hand that second read the session as it
	 * was before the strike.
	 */
	@Override
	public ViolationSummaryResponse heartbeat(String email, Long sessionId, String clientId) {
		// Also the ownership check: a session the caller does not own is a 404 here.
		ViolationSummaryResponse state = proctoringService.getSessionViolationSummary(email, sessionId);
		if (state.sessionStatus() != ExamStatus.IN_PROGRESS) {
			return state;
		}

		if (!examPresenceTracker.recordHeartbeat(sessionId, clientId, Instant.now())) {
			return state;
		}

		log.warn("Attempt open in two places at once: sessionId={} clientId={}", sessionId, clientId);
		violationStrikeRecorder.recordDetectedByServer(sessionId, ViolationType.MULTIPLE_LOGIN, MULTIPLE_LOGIN_DESCRIPTION);
		return proctoringService.getSessionViolationSummary(email, sessionId);
	}
}
