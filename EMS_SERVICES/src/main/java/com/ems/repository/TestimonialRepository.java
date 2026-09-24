package com.ems.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.ems.entity.Testimonial;
import com.ems.entity.User;
import com.ems.enums.CertificationLevel;
import com.ems.enums.CertificationStatus;
import com.ems.enums.TestimonialStatus;

public interface TestimonialRepository extends JpaRepository<Testimonial, Long> {

    List<Testimonial> findByUserOrderByCertificationLevelAsc(User user);

    boolean existsByUserAndCertificationLevel(User user, CertificationLevel certificationLevel);

    Optional<Testimonial> findByIdAndUser(Long id, User user);

    /**
     * The quotes the public site shows: approved, newest approval first, and not
     * backed by a certification that has since been revoked.
     */
    @EntityGraph(attributePaths = "user")
    List<Testimonial> findTop12ByStatusAndCertificationCertificationStatusNotOrderByReviewedAtDesc(
            TestimonialStatus status, CertificationStatus excludedCertificationStatus);

    @EntityGraph(attributePaths = { "user", "certification" })
    List<Testimonial> findByStatusOrderByCreatedDateDesc(TestimonialStatus status);

    @EntityGraph(attributePaths = { "user", "certification" })
    List<Testimonial> findAllByOrderByCreatedDateDesc();
}
