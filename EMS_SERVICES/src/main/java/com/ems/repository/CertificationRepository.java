package com.ems.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.ems.entity.Certification;
import com.ems.entity.User;
import com.ems.enums.CertificationLevel;
import com.ems.enums.CertificationStatus;

public interface CertificationRepository extends JpaRepository<Certification, Long> {

    List<Certification> findByUserOrderByIssueDateDesc(User user);

    List<Certification> findByUserOrderByExpiryDateDesc(User user);

    List<Certification> findByUserAndCertificationStatusOrderByIssueDateDesc(
            User user, CertificationStatus status);

    Optional<Certification> findFirstByUserAndCertificationLevelAndCertificationStatusOrderByExpiryDateDesc(
            User user,
            CertificationLevel certificationLevel,
            CertificationStatus certificationStatus);

    Optional<Certification> findFirstByUserIdAndCertificationLevelAndCertificationStatusOrderByExpiryDateDesc(
            Long userId,
            CertificationLevel certificationLevel,
            CertificationStatus certificationStatus);

    Optional<Certification> findFirstByUserIdAndCertificationLevelOrderByExpiryDateDesc(
            Long userId,
            CertificationLevel certificationLevel);

    /** Every certification as {@code [userId, level, status, issueDate]}, for the board analytics. */
    @Query("SELECT c.user.id, c.certificationLevel, c.certificationStatus, c.issueDate FROM Certification c")
    List<Object[]> findIssuanceFigures();
}
