package com.ems.service.impl;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.ems.audit.AuditEvent;
import com.ems.audit.AuditEventType;
import com.ems.audit.AuditOutcome;
import com.ems.dto.request.TestimonialQuoteEditRequest;
import com.ems.dto.request.TestimonialReviewRequest;
import com.ems.dto.request.TestimonialSubmissionRequest;
import com.ems.dto.response.AdminTestimonialResponse;
import com.ems.dto.response.MyTestimonialsResponse;
import com.ems.dto.response.PublicTestimonialResponse;
import com.ems.dto.response.TestimonialResponse;
import com.ems.entity.Certification;
import com.ems.entity.Testimonial;
import com.ems.entity.User;
import com.ems.enums.CertificationLevel;
import com.ems.enums.CertificationStatus;
import com.ems.enums.TestimonialStatus;
import com.ems.exception.BusinessException;
import com.ems.exception.ResourceNotFoundException;
import com.ems.repository.CertificationRepository;
import com.ems.repository.TestimonialRepository;
import com.ems.repository.UserRepository;
import com.ems.service.AuditService;
import com.ems.service.TestimonialService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
@Transactional
public class TestimonialServiceImpl implements TestimonialService {

	/**
	 * Identifies the consent wording the candidate agreed to. Change it whenever
	 * the tick-box text in the candidate portal changes, so each row records
	 * which wording it was given under.
	 */
	static final String CONSENT_VERSION = "2026-09";

	private final TestimonialRepository testimonialRepository;
	private final CertificationRepository certificationRepository;
	private final UserRepository userRepository;
	private final AuditService auditService;

	@Override
	@Transactional(readOnly = true)
	public MyTestimonialsResponse getMine(String email) {
		User user = findUser(email);
		List<Testimonial> testimonials = testimonialRepository.findByUserOrderByCertificationLevelAsc(user);

		Set<CertificationLevel> written = testimonials.stream()
				.map(Testimonial::getCertificationLevel)
				.collect(Collectors.toCollection(() -> EnumSet.noneOf(CertificationLevel.class)));
		List<CertificationLevel> eligible = heldCertifications(user).keySet().stream()
				.filter(level -> !written.contains(level))
				.sorted()
				.toList();

		return new MyTestimonialsResponse(eligible, testimonials.stream().map(this::toResponse).toList());
	}

	@Override
	public TestimonialResponse submit(String email, TestimonialSubmissionRequest request) {
		User user = findUser(email);
		CertificationLevel level = request.certificationLevel();

		Certification certification = heldCertifications(user).get(level);
		if (certification == null) {
			throw new BusinessException("You can share a testimonial only for a level you are certified in.");
		}
		if (testimonialRepository.existsByUserAndCertificationLevel(user, level)) {
			throw new BusinessException(
					"You have already shared a testimonial for " + level + ". Withdraw it first to write a new one.",
					HttpStatus.CONFLICT);
		}

		Testimonial saved = testimonialRepository.save(Testimonial.builder()
				.user(user)
				.certification(certification)
				.certificationLevel(level)
				.rating(request.rating())
				.quote(request.quote().strip())
				.nameDisplay(request.nameDisplay())
				.jobTitle(blankToNull(request.jobTitle()))
				.company(blankToNull(request.company()))
				.status(TestimonialStatus.PENDING)
				.consentGivenAt(Instant.now())
				.consentVersion(CONSENT_VERSION)
				.build());
		return toResponse(saved);
	}

	@Override
	public void withdraw(String email, Long testimonialId) {
		User user = findUser(email);
		Testimonial testimonial = testimonialRepository.findByIdAndUser(testimonialId, user)
				.orElseThrow(() -> new ResourceNotFoundException("Testimonial not found"));
		testimonialRepository.delete(testimonial);
	}

	@Override
	@Transactional(readOnly = true)
	public List<PublicTestimonialResponse> getPublished() {
		return testimonialRepository
				.findTop12ByStatusAndCertificationCertificationStatusNotOrderByReviewedAtDesc(
						TestimonialStatus.APPROVED, CertificationStatus.REVOKED)
				.stream()
				.map(t -> new PublicTestimonialResponse(
						t.getId(),
						t.publishedQuote(),
						publicName(t),
						t.getJobTitle(),
						t.getCompany(),
						t.getCertificationLevel()))
				.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public List<AdminTestimonialResponse> listForAdmin(TestimonialStatus status) {
		List<Testimonial> testimonials = status == null
				? testimonialRepository.findAllByOrderByCreatedDateDesc()
				: testimonialRepository.findByStatusOrderByCreatedDateDesc(status);
		return testimonials.stream().map(this::toAdminResponse).toList();
	}

	@Override
	public AdminTestimonialResponse review(String adminEmail, Long testimonialId, TestimonialReviewRequest request) {
		if (request.status() == TestimonialStatus.PENDING) {
			throw new BusinessException("A testimonial can only be approved or rejected.");
		}
		Testimonial testimonial = findTestimonial(testimonialId);
		testimonial.setStatus(request.status());
		testimonial.setReviewNote(blankToNull(request.reviewNote()));
		testimonial.setReviewedBy(adminEmail);
		testimonial.setReviewedAt(Instant.now());
		Testimonial saved = testimonialRepository.save(testimonial);

		audit(saved, (request.status() == TestimonialStatus.APPROVED ? "Approved" : "Rejected")
				+ " " + saved.getCertificationLevel() + " testimonial #" + saved.getId());
		return toAdminResponse(saved);
	}

	@Override
	public AdminTestimonialResponse editQuote(String adminEmail, Long testimonialId,
			TestimonialQuoteEditRequest request) {
		Testimonial testimonial = findTestimonial(testimonialId);
		String edited = blankToNull(request.editedQuote());
		// Saving the original wording back counts as clearing the edit.
		testimonial.setEditedQuote(edited == null || edited.equals(testimonial.getQuote()) ? null : edited);
		Testimonial saved = testimonialRepository.save(testimonial);

		audit(saved, (saved.getEditedQuote() == null ? "Restored original wording of" : "Edited wording of")
				+ " testimonial #" + saved.getId());
		return toAdminResponse(saved);
	}

	/**
	 * The candidate's certification at each level they hold, revoked ones aside.
	 * Where a level was certified more than once, the latest certification wins:
	 * the query returns newest first and the merge keeps the first seen.
	 */
	private Map<CertificationLevel, Certification> heldCertifications(User user) {
		return certificationRepository.findByUserOrderByIssueDateDesc(user).stream()
				.filter(c -> c.getCertificationStatus() != CertificationStatus.REVOKED)
				.collect(Collectors.toMap(Certification::getCertificationLevel, Function.identity(),
						(latest, older) -> latest));
	}

	/** The name shown beside the quote, following the candidate's choice; null when anonymous. */
	static String publicName(Testimonial testimonial) {
		User user = testimonial.getUser();
		String first = user.getFirstName() == null ? "" : user.getFirstName().strip();
		String last = user.getLastName() == null ? "" : user.getLastName().strip();
		return switch (testimonial.getNameDisplay()) {
			case FULL_NAME -> (first + " " + last).strip();
			case FIRST_NAME_INITIAL -> last.isEmpty() ? first : first + " " + last.charAt(0) + ".";
			case ANONYMOUS -> null;
		};
	}

	private void audit(Testimonial testimonial, String description) {
		auditService.record(AuditEvent.builder()
				.eventType(AuditEventType.ADMIN_ACTION)
				.outcome(AuditOutcome.SUCCESS)
				.targetUserId(testimonial.getUser().getUserId())
				.targetType("TESTIMONIAL")
				.targetId(String.valueOf(testimonial.getId()))
				.description(description)
				.build());
	}

	private User findUser(String email) {
		return userRepository.findByEmailIgnoreCase(email)
				.orElseThrow(() -> new ResourceNotFoundException("User not found"));
	}

	private Testimonial findTestimonial(Long testimonialId) {
		return testimonialRepository.findById(testimonialId)
				.orElseThrow(() -> new ResourceNotFoundException("Testimonial not found"));
	}

	private static String blankToNull(String value) {
		return StringUtils.hasText(value) ? value.strip() : null;
	}

	private TestimonialResponse toResponse(Testimonial t) {
		return new TestimonialResponse(
				t.getId(),
				t.getCertificationLevel(),
				t.getRating(),
				t.getQuote(),
				t.publishedQuote(),
				t.getNameDisplay(),
				t.getJobTitle(),
				t.getCompany(),
				t.getStatus(),
				t.getReviewNote(),
				t.getReviewedAt(),
				t.getCreatedDate());
	}

	private AdminTestimonialResponse toAdminResponse(Testimonial t) {
		User user = t.getUser();
		return new AdminTestimonialResponse(
				t.getId(),
				user.getUserId(),
				(user.getFirstName() + " " + user.getLastName()).strip(),
				user.getEmail(),
				t.getCertificationLevel(),
				t.getCertification().getCertificationStatus(),
				t.getRating(),
				t.getQuote(),
				t.getEditedQuote(),
				t.getNameDisplay(),
				publicName(t),
				t.getJobTitle(),
				t.getCompany(),
				t.getStatus(),
				t.getReviewNote(),
				t.getReviewedBy(),
				t.getReviewedAt(),
				t.getConsentGivenAt(),
				t.getCreatedDate());
	}
}
