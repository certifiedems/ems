package com.ems.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import com.ems.audit.AuditEvent;
import com.ems.dto.request.TestimonialQuoteEditRequest;
import com.ems.dto.request.TestimonialReviewRequest;
import com.ems.dto.request.TestimonialSubmissionRequest;
import com.ems.dto.response.AdminTestimonialResponse;
import com.ems.dto.response.MyTestimonialsResponse;
import com.ems.dto.response.PublicTestimonialResponse;
import com.ems.entity.Certification;
import com.ems.entity.Testimonial;
import com.ems.entity.User;
import com.ems.enums.CertificationLevel;
import com.ems.enums.CertificationStatus;
import com.ems.enums.TestimonialNameDisplay;
import com.ems.enums.TestimonialStatus;
import com.ems.exception.BusinessException;
import com.ems.exception.ResourceNotFoundException;
import com.ems.repository.CertificationRepository;
import com.ems.repository.TestimonialRepository;
import com.ems.repository.UserRepository;
import com.ems.service.AuditService;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TestimonialServiceImplTest {

	private static final String EMAIL = "priya@example.com";
	private static final String ADMIN = "admin@example.com";

	@Mock
	private TestimonialRepository testimonialRepository;

	@Mock
	private CertificationRepository certificationRepository;

	@Mock
	private UserRepository userRepository;

	@Mock
	private AuditService auditService;

	private TestimonialServiceImpl service;

	private User user;

	@BeforeEach
	void setUp() {
		service = new TestimonialServiceImpl(testimonialRepository, certificationRepository, userRepository,
				auditService);
		user = User.builder().id(1L).userId("EMS-0001").firstName("Priya").lastName("Sharma").email(EMAIL).build();
		when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
		when(testimonialRepository.save(any(Testimonial.class))).thenAnswer(inv -> inv.getArgument(0));
	}

	@Test
	void eligibleLevelsAreHeldLevelsWithoutATestimonialAndNeverRevokedOnes() {
		when(certificationRepository.findByUserOrderByIssueDateDesc(user)).thenReturn(List.of(
				certification(CertificationLevel.L2, CertificationStatus.REVOKED),
				certification(CertificationLevel.L1, CertificationStatus.ACTIVE),
				certification(CertificationLevel.L3, CertificationStatus.EXPIRED)));
		when(testimonialRepository.findByUserOrderByCertificationLevelAsc(user)).thenReturn(List.of(
				testimonial(CertificationLevel.L1, TestimonialNameDisplay.FULL_NAME)));

		MyTestimonialsResponse mine = service.getMine(EMAIL);

		assertThat(mine.eligibleLevels()).containsExactly(CertificationLevel.L3);
		assertThat(mine.testimonials()).hasSize(1);
	}

	@Test
	void submitStartsPendingAgainstTheLatestCertificationAndRecordsConsent() {
		Certification latest = certification(CertificationLevel.L1, CertificationStatus.ACTIVE);
		Certification older = certification(CertificationLevel.L1, CertificationStatus.EXPIRED);
		when(certificationRepository.findByUserOrderByIssueDateDesc(user)).thenReturn(List.of(latest, older));

		service.submit(EMAIL, request(CertificationLevel.L1, "  Got promoted to line technician after L1.  "));

		ArgumentCaptor<Testimonial> saved = ArgumentCaptor.forClass(Testimonial.class);
		verify(testimonialRepository).save(saved.capture());
		assertThat(saved.getValue().getStatus()).isEqualTo(TestimonialStatus.PENDING);
		assertThat(saved.getValue().getCertification()).isSameAs(latest);
		assertThat(saved.getValue().getQuote()).isEqualTo("Got promoted to line technician after L1.");
		assertThat(saved.getValue().getConsentGivenAt()).isNotNull();
		assertThat(saved.getValue().getConsentVersion()).isEqualTo(TestimonialServiceImpl.CONSENT_VERSION);
		assertThat(saved.getValue().getJobTitle()).isNull();
	}

	@Test
	void submitIsRefusedForALevelTheCandidateDoesNotHold() {
		when(certificationRepository.findByUserOrderByIssueDateDesc(user)).thenReturn(List.of(
				certification(CertificationLevel.L1, CertificationStatus.REVOKED)));

		assertThatThrownBy(() -> service.submit(EMAIL, request(CertificationLevel.L1, "A quote long enough to pass.")))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("certified in");
		verify(testimonialRepository, never()).save(any());
	}

	@Test
	void secondTestimonialForTheSameLevelIsAConflict() {
		when(certificationRepository.findByUserOrderByIssueDateDesc(user)).thenReturn(List.of(
				certification(CertificationLevel.L1, CertificationStatus.ACTIVE)));
		when(testimonialRepository.existsByUserAndCertificationLevel(user, CertificationLevel.L1)).thenReturn(true);

		assertThatThrownBy(() -> service.submit(EMAIL, request(CertificationLevel.L1, "A quote long enough to pass.")))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
	}

	@Test
	void withdrawDeletesOnlyTheCallersOwnTestimonial() {
		Testimonial own = testimonial(CertificationLevel.L1, TestimonialNameDisplay.FULL_NAME);
		when(testimonialRepository.findByIdAndUser(7L, user)).thenReturn(Optional.of(own));
		when(testimonialRepository.findByIdAndUser(8L, user)).thenReturn(Optional.empty());

		service.withdraw(EMAIL, 7L);
		verify(testimonialRepository).delete(own);

		assertThatThrownBy(() -> service.withdraw(EMAIL, 8L)).isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void publicListShowsTheEditedWordingAndTheChosenName() {
		Testimonial edited = testimonial(CertificationLevel.L1, TestimonialNameDisplay.FIRST_NAME_INITIAL);
		edited.setEditedQuote("Fixed typo.");
		Testimonial anonymous = testimonial(CertificationLevel.L2, TestimonialNameDisplay.ANONYMOUS);
		when(testimonialRepository.findTop12ByStatusAndCertificationCertificationStatusNotOrderByReviewedAtDesc(
				TestimonialStatus.APPROVED, CertificationStatus.REVOKED)).thenReturn(List.of(edited, anonymous));

		List<PublicTestimonialResponse> published = service.getPublished();

		assertThat(published.get(0).quote()).isEqualTo("Fixed typo.");
		assertThat(published.get(0).displayName()).isEqualTo("Priya S.");
		assertThat(published.get(1).quote()).isEqualTo("Original words.");
		assertThat(published.get(1).displayName()).isNull();
	}

	@Test
	void reviewStampsTheReviewerAndIsAudited() {
		Testimonial pending = testimonial(CertificationLevel.L1, TestimonialNameDisplay.FULL_NAME);
		when(testimonialRepository.findById(7L)).thenReturn(Optional.of(pending));

		AdminTestimonialResponse response = service.review(ADMIN, 7L,
				new TestimonialReviewRequest(TestimonialStatus.APPROVED, "  "));

		assertThat(response.status()).isEqualTo(TestimonialStatus.APPROVED);
		assertThat(response.reviewedBy()).isEqualTo(ADMIN);
		assertThat(response.reviewedAt()).isNotNull();
		assertThat(response.reviewNote()).isNull();
		verify(auditService).record(any(AuditEvent.class));
	}

	@Test
	void reviewCannotMoveATestimonialBackToPending() {
		assertThatThrownBy(() -> service.review(ADMIN, 7L,
				new TestimonialReviewRequest(TestimonialStatus.PENDING, null)))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void editKeepsTheOriginalAndSavingTheOriginalBackClearsTheEdit() {
		Testimonial testimonial = testimonial(CertificationLevel.L1, TestimonialNameDisplay.FULL_NAME);
		when(testimonialRepository.findById(7L)).thenReturn(Optional.of(testimonial));

		service.editQuote(ADMIN, 7L, new TestimonialQuoteEditRequest("Fixed typo."));
		assertThat(testimonial.getQuote()).isEqualTo("Original words.");
		assertThat(testimonial.getEditedQuote()).isEqualTo("Fixed typo.");

		service.editQuote(ADMIN, 7L, new TestimonialQuoteEditRequest("Original words."));
		assertThat(testimonial.getEditedQuote()).isNull();
	}

	private Certification certification(CertificationLevel level, CertificationStatus status) {
		return Certification.builder()
				.user(user)
				.certificationLevel(level)
				.certificationStatus(status)
				.issueDate(LocalDate.of(2026, 1, 1))
				.expiryDate(LocalDate.of(2029, 1, 1))
				.build();
	}

	private Testimonial testimonial(CertificationLevel level, TestimonialNameDisplay nameDisplay) {
		return Testimonial.builder()
				.id(7L)
				.user(user)
				.certification(certification(level, CertificationStatus.ACTIVE))
				.certificationLevel(level)
				.rating(5)
				.quote("Original words.")
				.nameDisplay(nameDisplay)
				.status(TestimonialStatus.PENDING)
				.build();
	}

	private static TestimonialSubmissionRequest request(CertificationLevel level, String quote) {
		return new TestimonialSubmissionRequest(level, 5, quote, TestimonialNameDisplay.FULL_NAME, " ", null, true);
	}
}
