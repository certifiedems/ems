package com.ems.service.impl;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Which copies of the exam page are alive for each running attempt.
 *
 * <p>Answers one question: is this attempt open in two places at once — two
 * browsers, two tabs, a second device? Every copy of the exam page draws its own
 * client id and sends it with each heartbeat.</p>
 *
 * <p>The proof is both copies checking in after each other appeared. A new copy
 * appearing is not enough on its own: a candidate whose browser crashed reopens
 * the exam in a fresh tab while the dead tab's last heartbeat is still recent,
 * and that is a rejoin, not a second login. The dead tab never checks in again,
 * so it never completes the proof; a second live copy completes it within one
 * heartbeat.</p>
 *
 * <p>Held in memory. Heartbeats are the most frequent request the system serves,
 * and this adds no database work to them. The trade-off is that presence is lost
 * on a restart and not shared between instances — and both failures can only
 * miss a second login, never invent one.</p>
 */
@Component
public class ExamPresenceTracker {

	/** A copy that has not checked in for this long is taken to be gone: two and a half 30-second heartbeats. */
	static final Duration LIVENESS = Duration.ofSeconds(75);

	/**
	 * One report per episode. Two copies left open both keep checking in, and
	 * without this every heartbeat would cost the candidate another strike.
	 */
	static final Duration REPORT_COOLDOWN = Duration.ofMinutes(5);

	private final Cache<Long, SessionPresence> sessions = Caffeine.newBuilder()
			.expireAfterAccess(Duration.ofMinutes(30))
			.maximumSize(100_000)
			.build();

	/**
	 * Notes one heartbeat, and says whether it proves the attempt is open in two
	 * places and that episode has not already been reported within the cooldown.
	 */
	public boolean recordHeartbeat(Long sessionId, String clientId, Instant now) {
		SessionPresence presence = sessions.get(sessionId, id -> new SessionPresence());
		synchronized (presence) {
			presence.clients.values().removeIf(client -> client.lastSeen.isBefore(now.minus(LIVENESS)));

			Sighting self = presence.clients.computeIfAbsent(clientId, id -> new Sighting(now));
			self.lastSeen = now;

			boolean openElsewhere = presence.clients.entrySet().stream()
					.filter(entry -> !entry.getKey().equals(clientId))
					.map(Map.Entry::getValue)
					// Each copy has checked in since the other one first appeared.
					.anyMatch(other -> other.lastSeen.isAfter(self.firstSeen) && self.lastSeen.isAfter(other.firstSeen));

			if (!openElsewhere) {
				return false;
			}
			if (presence.lastReportedAt != null && presence.lastReportedAt.plus(REPORT_COOLDOWN).isAfter(now)) {
				return false;
			}
			presence.lastReportedAt = now;
			return true;
		}
	}

	private static final class SessionPresence {

		private final Map<String, Sighting> clients = new HashMap<>();

		private Instant lastReportedAt;
	}

	private static final class Sighting {

		private final Instant firstSeen;

		private Instant lastSeen;

		private Sighting(Instant firstSeen) {
			this.firstSeen = firstSeen;
			this.lastSeen = firstSeen;
		}
	}
}
