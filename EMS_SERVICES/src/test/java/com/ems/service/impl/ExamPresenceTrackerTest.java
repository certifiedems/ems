package com.ems.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class ExamPresenceTrackerTest {

	private static final Long SESSION = 300L;

	private static final Instant START = Instant.parse("2026-09-12T10:00:00Z");

	private final ExamPresenceTracker tracker = new ExamPresenceTracker();

	private static Instant at(int seconds) {
		return START.plusSeconds(seconds);
	}

	@Test
	void oneCopyCheckingInIsNeverReported() {
		for (int second = 0; second <= 900; second += 30) {
			assertThat(tracker.recordHeartbeat(SESSION, "tab-a", at(second))).isFalse();
		}
	}

	/** The case the resume flow exists for, which must never read as a second login. */
	@Test
	void rejoiningInAFreshTabAfterACrashIsNotReported() {
		tracker.recordHeartbeat(SESSION, "tab-a", at(0));
		tracker.recordHeartbeat(SESSION, "tab-a", at(30));

		// The browser dies; a new tab resumes ten seconds after the last heartbeat.
		assertThat(tracker.recordHeartbeat(SESSION, "tab-b", at(40))).isFalse();
		assertThat(tracker.recordHeartbeat(SESSION, "tab-b", at(70))).isFalse();
		assertThat(tracker.recordHeartbeat(SESSION, "tab-b", at(100))).isFalse();
		assertThat(tracker.recordHeartbeat(SESSION, "tab-b", at(130))).isFalse();
	}

	@Test
	void twoLiveCopiesAreReportedOnceBothHaveCheckedIn() {
		assertThat(tracker.recordHeartbeat(SESSION, "laptop", at(0))).isFalse();
		assertThat(tracker.recordHeartbeat(SESSION, "phone", at(10))).isFalse();
		assertThat(tracker.recordHeartbeat(SESSION, "laptop", at(30))).isTrue();
	}

	@Test
	void anEpisodeIsReportedOncePerCooldown() {
		int reports = 0;
		for (int second = 0; second < 330; second += 10) {
			String client = second % 30 == 0 ? "laptop" : (second % 30 == 10 ? "phone" : null);
			if (client != null && tracker.recordHeartbeat(SESSION, client, at(second))) {
				reports++;
			}
		}
		assertThat(reports).as("one report inside the five-minute cooldown").isEqualTo(1);

		// Still open in both places once the cooldown has run out.
		assertThat(tracker.recordHeartbeat(SESSION, "laptop", at(330))).isTrue();
	}

	@Test
	void aCopyThatWakesWhileAnotherIsLiveIsReported() {
		tracker.recordHeartbeat(SESSION, "laptop", at(0));
		tracker.recordHeartbeat(SESSION, "laptop", at(30));
		// The laptop sleeps; the candidate carries on from a phone.
		tracker.recordHeartbeat(SESSION, "phone", at(60));
		tracker.recordHeartbeat(SESSION, "phone", at(90));
		tracker.recordHeartbeat(SESSION, "phone", at(120));

		// The laptop wakes with the exam still open: two live copies again.
		assertThat(tracker.recordHeartbeat(SESSION, "laptop", at(130))).isFalse();
		assertThat(tracker.recordHeartbeat(SESSION, "phone", at(150))).isTrue();
	}

	@Test
	void differentAttemptsAreTrackedSeparately() {
		tracker.recordHeartbeat(300L, "tab-a", at(0));
		tracker.recordHeartbeat(301L, "tab-b", at(10));

		assertThat(tracker.recordHeartbeat(300L, "tab-a", at(30))).isFalse();
		assertThat(tracker.recordHeartbeat(301L, "tab-b", at(40))).isFalse();
	}
}
