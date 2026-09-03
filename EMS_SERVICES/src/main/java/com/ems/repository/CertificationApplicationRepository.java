package com.ems.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.ems.entity.CertificationApplication;
import com.ems.entity.Exam;
import com.ems.entity.User;
import com.ems.enums.CertificationApplicationStatus;
import com.ems.enums.CertificationLevel;

public interface CertificationApplicationRepository extends JpaRepository<CertificationApplication, Long> {

    /*
     * The exam comes along on the same query. The dashboard reads it off every
     * row it gets back, for the window in which a slot can still be booked, and
     * left lazy that is one extra select per application on the screen a
     * candidate lands on straight after signing in.
     */
    @EntityGraph(attributePaths = "exam")
    List<CertificationApplication> findByUserOrderByAppliedOnDesc(User user);

    java.util.Optional<CertificationApplication> findByIdAndUser(Long id, User user);

    Optional<CertificationApplication> findTopByUserAndExamOrderByAppliedOnDescIdDesc(User user, Exam exam);

    Optional<CertificationApplication> findTopByUserAndExamAndApplicationStatusOrderByAppliedOnDescIdDesc(
            User user,
            Exam exam,
            CertificationApplicationStatus applicationStatus);

    Optional<CertificationApplication> findTopByUserAndExamAndApplicationStatusInOrderByAppliedOnDescIdDesc(
            User user,
            Exam exam,
            Collection<CertificationApplicationStatus> applicationStatuses);

    Optional<CertificationApplication> findTopByUserAndCertificationLevelOrderByAppliedOnDescIdDesc(
            User user,
            CertificationLevel certificationLevel);

    @Query("SELECT DISTINCT ca FROM CertificationApplication ca " +
            "LEFT JOIN FETCH ca.exam " +
            "WHERE ca.id = :id AND ca.user = :user")
    java.util.Optional<CertificationApplication> findByIdAndUserWithExam(Long id, User user);

    @Query("SELECT DISTINCT ca FROM CertificationApplication ca " +
            "LEFT JOIN FETCH ca.user u " +
            "LEFT JOIN FETCH ca.exam " +
            "WHERE ca.id = :id")
    java.util.Optional<CertificationApplication> findByIdWithAllRelationships(Long id);

    boolean existsByUserAndCertificationLevelAndApplicationStatusIn(
            User user,
            CertificationLevel certificationLevel,
            Collection<CertificationApplicationStatus> statuses);

    @Query("SELECT ca FROM CertificationApplication ca " +
            "LEFT JOIN FETCH ca.exam " +
            "WHERE ca.user = :user " +
            "AND ca.applicationStatus IN ('FAILED', 'TERMINATED', 'EXPIRED', 'REJECTED') " +
            "ORDER BY ca.appliedOn DESC")
    java.util.List<CertificationApplication> findFailedApplicationsForReApply(User user);

    /**
     * Per exam, how many paid applications are still owed a sitting.
     *
     * <p>Answers the only question that makes an exam's booking window urgent:
     * how many people are stuck behind it. The predicate is deliberately the
     * same set of facts {@code scheduleExam} checks before accepting a
     * booking — paid for, still open, no attempt started — so a row counted here
     * is a row that really would be refused while the window is shut. A looser
     * count would put a number beside a closed window that nobody could
     * reconcile against the candidates actually complaining.</p>
     *
     * <p>The split is on whether the candidate still has a slot they can turn up
     * to. The first figure covers both halves of "needs to book": never booked,
     * and booked but missed. The second is everyone whose slot is still ahead of
     * them — untouched by a window closing, and kept separate so reopening one
     * is not read as rescuing people who were never stuck.</p>
     *
     * @param missedBefore now minus the start-window grace; a slot earlier than
     *                     this can no longer be attended
     * @return rows of {@code [examId, waiting, booked]}; an exam with no such
     *         applications is simply absent
     */
    @Query("SELECT ca.exam.id, "
            + "SUM(CASE WHEN ca.scheduledExamTime IS NULL OR ca.scheduledExamTime < :missedBefore "
            + "         THEN 1L ELSE 0L END), "
            + "SUM(CASE WHEN ca.scheduledExamTime IS NOT NULL AND ca.scheduledExamTime >= :missedBefore "
            + "         THEN 1L ELSE 0L END) "
            + "FROM CertificationApplication ca "
            + "WHERE ca.exam IS NOT NULL "
            + "AND ca.paymentStatus = com.ems.enums.PaymentStatus.SUCCESS "
            + "AND ca.applicationStatus IN ("
            + "  com.ems.enums.CertificationApplicationStatus.APPLIED, "
            + "  com.ems.enums.CertificationApplicationStatus.ELIGIBLE, "
            + "  com.ems.enums.CertificationApplicationStatus.IN_PROGRESS) "
            + "AND NOT EXISTS (SELECT 1 FROM ExamSession es WHERE es.certificationApplication = ca) "
            + "GROUP BY ca.exam.id")
    List<Object[]> countSchedulableApplicationsByExam(Instant missedBefore);
}
