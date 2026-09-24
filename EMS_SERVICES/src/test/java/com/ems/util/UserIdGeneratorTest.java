package com.ems.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.ems.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserIdGeneratorTest {

	@Mock
	private UserRepository userRepository;

	@InjectMocks
	private UserIdGenerator userIdGenerator;

	@Test
	void generatesIdFromInitialsAndEmailLocalPart() {
		when(userRepository.existsByUserId("VISE-VIGNESHWAR-" + fingerprintOf(
				"vigneshwaran147@gmail.com", "Vignesh", "Selvam"))).thenReturn(false);

		String userId = userIdGenerator.generate("Vignesh", "Selvam", "vigneshwaran147@gmail.com");

		assertThat(userId).startsWith("VISE-VIGNESHWAR-");
		assertThat(userId).hasSize("VISE-VIGNESHWAR-".length() + 4);
	}

	@Test
	void isStableForTheSameDetailsAndIgnoresCasingAndPadding() {
		String first = userIdGenerator.generate("Vignesh", "Selvam", "vigneshwaran147@gmail.com");
		String second = userIdGenerator.generate("  vignesh ", "SELVAM", " Vigneshwaran147@Gmail.com ");

		assertThat(second).isEqualTo(first);
	}

	@Test
	void separatesPeopleWhoShareANameAndEmailPrefix() {
		String atGmail = userIdGenerator.generate("Vignesh", "Selvam", "vignesh@gmail.com");
		String atOutlook = userIdGenerator.generate("Vignesh", "Selvam", "vignesh@outlook.com");

		assertThat(atGmail).isNotEqualTo(atOutlook);
	}

	@Test
	void dropsAccentsSpacesAndPunctuationFromEverySegment() {
		String userId = userIdGenerator.generate("Jean-Luc", "O'Brien", "jean.luc+ems@example.com");

		assertThat(userId).matches("^[A-Z0-9]+-[A-Z0-9]+-[A-Z0-9]{4}$");
	}

	@Test
	void fallsBackWhenNoSegmentSurvivesSanitising() {
		String userId = userIdGenerator.generate("陳", "偉", "陳偉@example.com");

		assertThat(userId).startsWith("EMS-EMS-");
	}

	@Test
	void appendsADiscriminatorWhenTheIdIsAlreadyTaken() {
		String taken = userIdGenerator.generate("Vignesh", "Selvam", "vigneshwaran147@gmail.com");
		when(userRepository.existsByUserId(taken)).thenReturn(true);

		String userId = userIdGenerator.generate("Vignesh", "Selvam", "vigneshwaran147@gmail.com");

		assertThat(userId).isEqualTo(taken + "-2");
	}

	@Test
	void staysWithinTheUserIdColumnLength() {
		String userId = userIdGenerator.generate(
				"Bartholomew", "Featherstonehaugh", "bartholomew.featherstonehaugh@example.com");

		assertThat(userId.length()).isLessThanOrEqualTo(50);
	}

	private String fingerprintOf(String email, String firstName, String lastName) {
		String seed = email.trim().toLowerCase() + "|" + firstName.trim().toLowerCase()
				+ "|" + lastName.trim().toLowerCase();
		return TokenHashUtil.sha256Hex(seed).substring(0, 4).toUpperCase();
	}
}
