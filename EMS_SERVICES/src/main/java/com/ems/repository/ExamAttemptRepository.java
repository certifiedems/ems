package com.ems.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.ems.entity.ExamAttempt;
import com.ems.entity.ExamSession;
import com.ems.entity.User;
import com.ems.enums.ResultStatus;

public interface ExamAttemptRepository extends JpaRepository<ExamAttempt, Long> {

    Optional<ExamAttempt> findByExamSession(ExamSession examSession);

    Optional<ExamAttempt> findByExamSessionIdAndExamSessionUserEmailIgnoreCase(
            Long sessionId, String email);

    List<ExamAttempt> findByExamSessionUserEmailIgnoreCaseOrderBySubmittedAtDesc(String email);

    List<ExamAttempt> findByExamSessionUserAndResultStatusOrderBySubmittedAtDesc(
            User user, ResultStatus resultStatus);

    boolean existsByExamSession(ExamSession examSession);

    List<ExamAttempt> findAllByOrderBySubmittedAtDesc();

    /** The results of these sessions in one query; a session not yet scored has none. */
    List<ExamAttempt> findByExamSessionIn(Collection<ExamSession> examSessions);

    /**
     * Every scored attempt as {@code [submittedAt, resultStatus]}, for the board
     * analytics' pass rate. Only the two columns: an attempt row also carries its
     * submitted answers, which the figures have no use for.
     */
    @Query("SELECT a.submittedAt, a.resultStatus FROM ExamAttempt a WHERE a.submittedAt IS NOT NULL")
    List<Object[]> findSubmissionOutcomes();
}
