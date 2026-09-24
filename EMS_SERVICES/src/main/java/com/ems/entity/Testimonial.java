package com.ems.entity;

import java.time.Instant;

import com.ems.enums.CertificationLevel;
import com.ems.enums.TestimonialNameDisplay;
import com.ems.enums.TestimonialStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A certified candidate's quote about the certification, shown on the public
 * sign-in screen once an administrator approves it. One per candidate per level.
 *
 * <p>{@link #quote} is what the candidate wrote and is never overwritten; an
 * administrator's typo fix lives in {@link #editedQuote}. See V39 for the rest.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true, of = "id")
@Entity
@Table(name = "testimonials", uniqueConstraints = @UniqueConstraint(
        name = "uq_testimonials_user_level", columnNames = { "user_ref", "certification_level" }))
public class Testimonial extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_ref", nullable = false)
    private User user;

    /** The certification that made the candidate eligible; revoking it hides the quote. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "certification_ref", nullable = false)
    private Certification certification;

    @Enumerated(EnumType.STRING)
    @Column(name = "certification_level", nullable = false, length = 10)
    private CertificationLevel certificationLevel;

    @Column(name = "rating", nullable = false)
    private Integer rating;

    @Column(name = "quote", nullable = false, length = 300)
    private String quote;

    @Column(name = "edited_quote", length = 300)
    private String editedQuote;

    @Enumerated(EnumType.STRING)
    @Column(name = "name_display", nullable = false, length = 30)
    private TestimonialNameDisplay nameDisplay;

    @Column(name = "job_title", length = 100)
    private String jobTitle;

    @Column(name = "company", length = 100)
    private String company;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TestimonialStatus status;

    /** Why a quote was not published; shown to the candidate. */
    @Column(name = "review_note", length = 300)
    private String reviewNote;

    @Column(name = "reviewed_by", length = 100)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "consent_given_at", nullable = false)
    private Instant consentGivenAt;

    @Column(name = "consent_version", nullable = false, length = 20)
    private String consentVersion;

    /** The words the public site shows: the admin's typo fix if there is one. */
    public String publishedQuote() {
        return editedQuote != null ? editedQuote : quote;
    }
}
