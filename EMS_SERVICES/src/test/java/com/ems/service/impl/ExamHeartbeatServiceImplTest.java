package com.ems.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ems.dto.response.ViolationSummaryResponse;
import com.ems.enums.ExamStatus;
import com.ems.enums.ViolationType;
import com.ems.service.ProctoringService;

@ExtendWith(MockitoExtension.class)
class ExamHeartbeatServiceImplTest {

	private static final String EMAIL = "candidate@example.com";
	private static final Long SESSION_ID = 300L;

	@Mock
	private ProctoringService proctoringService;

	@Mock
	private ExamPresenceTracker examPresenceTracker;

	@Mock
	private ViolationStrikeRecorder violationStrikeRecorder;

	private ExamHeartbeatServiceImpl service;

	@BeforeEach
	void setUp() {
		service = new ExamHeartbeatServiceImpl(proctoringService, examPresenceTracker, violationStrikeRecorder);
	}

	@Test
	void aLoneCopyOfTheExamJustGetsTheSessionState() {
		ViolationSummaryResponse state = summary(ExamStatus.IN_PROGRESS, 0, null);
		when(proctoringService.getSessionViolationSummary(EMAIL, SESSION_ID)).thenReturn(state);
		when(examPresenceTracker.recordHeartbeat(eq(SESSION_ID), eq("tab-a"), any(Instant.class))).thenReturn(false);

		assertThat(service.heartbeat(EMAIL, SESSION_ID, "tab-a")).isSameAs(state);
		verifyNoInteractions(violationStrikeRecorder);
	}

	@Test
	void aSecondLiveCopyIsRecordedAsMultipleLoginAndTheUpdatedStateReturned() {
		ViolationSummaryResponse before = summary(ExamStatus.IN_PROGRESS, 0, null);
		ViolationSummaryResponse after = summary(ExamStatus.IN_PROGRESS, 1, ViolationType.MULTIPLE_LOGIN);
		when(proctoringService.getSessionViolationSummary(EMAIL, SESSION_ID)).thenReturn(before, after);
		when(examPresenceTracker.recordHeartbeat(eq(SESSION_ID), eq("tab-a"), any(Instant.class))).thenReturn(true);

		assertThat(service.heartbeat(EMAIL, SESSION_ID, "tab-a")).isSameAs(after);
		verify(violationStrikeRecorder).recordDetectedByServer(
				eq(SESSION_ID), eq(ViolationType.MULTIPLE_LOGIN), any(String.class));
	}

	@Test
	void aFinishedAttemptIsNotTrackedAtAll() {
		ViolationSummaryResponse state = summary(ExamStatus.INVALIDATED, 3, ViolationType.PHONE_DETECTED);
		when(proctoringService.getSessionViolationSummary(EMAIL, SESSION_ID)).thenReturn(state);

		assertThat(service.heartbeat(EMAIL, SESSION_ID, "tab-a")).isSameAs(state);
		verifyNoInteractions(examPresenceTracker, violationStrikeRecorder);
	}

	private static ViolationSummaryResponse summary(ExamStatus status, int strikes, ViolationType lastType) {
		return new ViolationSummaryResponse(SESSION_ID, strikes, 0, strikes, 3,
				status == ExamStatus.INVALIDATED, status, lastType, "state");
	}
}
