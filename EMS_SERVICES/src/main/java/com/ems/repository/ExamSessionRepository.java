package com.ems.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ems.entity.CertificationApplication;
import com.ems.entity.ExamSession;
import com.ems.entity.Exam;
import com.ems.entity.User;
import com.ems.enums.ExamStatus;

public interface ExamSessionRepository extends JpaRepository<ExamSession, Long> {

    Optional<ExamSession> findByIdAndUserEmailIgnoreCase(Long id, String email);

    /**
     * Loads the session under a {@code SELECT ... FOR UPDATE} row lock.
     *
     * <p>Required by the AI proctoring pipeline: the worker, the audio monitor and
     * the browser-security listeners can all flag a violation within the same
     * millisecond, and a plain read-modify-write on {@code violation_count} would
     * lose increments (two threads both read 2, both write 3). Serialising on the
     * session row guarantees strike N+1 is always derived from the committed N.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ExamSession s WHERE s.id = :id")
    Optional<ExamSession> findByIdForUpdate(@Param("id") Long id);

    /**
     * Resolves the candidate's most recent session for an exam, restricted to the
     * authenticated principal. Used to translate the client's
     * {@code (examId, studentId)} payload into a session the caller actually owns.
     */
    Optional<ExamSession> findTopByUserEmailIgnoreCaseAndExamIdAndSessionStatusOrderByIdDesc(
            String email, Long examId, ExamStatus sessionStatus);

    Optional<ExamSession> findTopByUserEmailIgnoreCaseAndExamIdOrderByIdDesc(String email, Long examId);

    List<ExamSession> findBySessionStatusOrderBySessionStartTimeDesc(ExamStatus sessionStatus);

    Optional<ExamSession> findBySessionToken(UUID sessionToken);

    Optional<ExamSession> findBySessionTokenAndUserEmailIgnoreCase(UUID sessionToken, String email);

    Optional<ExamSession> findTopByUserAndExamOrderBySessionStartTimeDescIdDesc(User user, Exam exam);

    /**
     * The latest attempt sat under one application.
     *
     * <p>Prefer this over the user-and-exam lookup above wherever the question is
     * about a specific application. That one answers "the candidate's latest
     * session for this exam", which is a different question as soon as the
     * candidate has more than one application for it, and answering the second
     * with the first is what let a previous attempt's verdict close a newly paid
     * application.</p>
     */
    Optional<ExamSession> findTopByCertificationApplicationOrderBySessionStartTimeDescIdDesc(
            CertificationApplication certificationApplication);

    /**
     * Every paper this candidate has been given at a level, as
     * {@code [selectedQuestionIdsJson, sessionStartTime]} rows.
     *
     * <p>Read when a new attempt's paper is drawn, so the questions a candidate
     * has already seen are the last ones chosen for them. Only the two columns
     * are selected: a session row also carries the answer draft and the
     * proctoring snapshot, and neither is needed to know what was asked.</p>
     */
    @Query("SELECT s.selectedQuestionIdsJson, s.sessionStartTime FROM ExamSession s "
            + "WHERE s.user = :user AND s.exam.certificationLevel = :level "
            + "AND s.selectedQuestionIdsJson IS NOT NULL")
    List<Object[]> findPapersSeenAtLevel(
            @Param("user") User user,
            @Param("level") com.ems.enums.CertificationLevel level);
}
