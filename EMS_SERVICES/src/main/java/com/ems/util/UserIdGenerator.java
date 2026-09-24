package com.ems.util;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.ems.exception.BusinessException;
import com.ems.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Derives the public user id from the details a candidate already supplies at
 * registration, so nobody has to invent a handle and no two accounts can claim
 * the same one.
 *
 * <p>The id reads back as the person it belongs to — initials, the email local
 * part, then a fingerprint of the three inputs — while staying stable for a
 * given set of details rather than depending on the order rows were inserted.
 */
@Component
@RequiredArgsConstructor
public class UserIdGenerator {

	/** Matches the users.user_id column. */
	private static final int MAX_LENGTH = 50;

	private static final int NAME_SEGMENT_LENGTH = 2;
	private static final int EMAIL_SEGMENT_LENGTH = 10;
	private static final int FINGERPRINT_LENGTH = 4;
	private static final String FALLBACK_SEGMENT = "EMS";

	/**
	 * Only reached if the fingerprint itself collides, which needs two accounts
	 * sharing an email — already rejected before this point.
	 */
	private static final int MAX_ATTEMPTS = 100;

	private final UserRepository userRepository;

	public String generate(String firstName, String lastName, String email) {
		String base = buildBase(firstName, lastName, email);

		String candidate = base;
		for (int attempt = 2; userRepository.existsByUserId(candidate); attempt++) {
			if (attempt > MAX_ATTEMPTS) {
				throw new BusinessException(
						"Could not allocate a user ID for this registration", HttpStatus.CONFLICT);
			}
			String discriminator = String.valueOf(attempt);
			candidate = truncate(base, MAX_LENGTH - discriminator.length() - 1) + "-" + discriminator;
		}
		return candidate;
	}

	private String buildBase(String firstName, String lastName, String email) {
		String namePart = sanitize(firstName, NAME_SEGMENT_LENGTH) + sanitize(lastName, NAME_SEGMENT_LENGTH);
		String emailPart = sanitize(localPart(email), EMAIL_SEGMENT_LENGTH);

		return blankToFallback(namePart)
				+ "-" + blankToFallback(emailPart)
				+ "-" + fingerprint(firstName, lastName, email);
	}

	/**
	 * Keeps only the characters that survive being read aloud or typed back in:
	 * accents, spaces and punctuation all drop out, so names in any script that
	 * transliterates to ASCII still yield a usable segment.
	 */
	private String sanitize(String value, int maxLength) {
		if (value == null) {
			return "";
		}
		String cleaned = value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
		return truncate(cleaned, maxLength);
	}

	private String localPart(String email) {
		if (email == null) {
			return "";
		}
		int at = email.indexOf('@');
		return at < 0 ? email : email.substring(0, at);
	}

	/**
	 * Separates two people whose names and email prefixes read alike — say the
	 * same name at two providers — without exposing how many accounts exist.
	 */
	private String fingerprint(String firstName, String lastName, String email) {
		String seed = normalize(email) + "|" + normalize(firstName) + "|" + normalize(lastName);
		String hex = TokenHashUtil.sha256Hex(seed);
		return hex.substring(0, FINGERPRINT_LENGTH).toUpperCase(Locale.ROOT);
	}

	private String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}

	private String blankToFallback(String value) {
		return value.isEmpty() ? FALLBACK_SEGMENT : value;
	}

	private String truncate(String value, int maxLength) {
		return value.length() <= maxLength ? value : value.substring(0, maxLength);
	}
}
