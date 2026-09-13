package com.ems.service.impl;

import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.ems.entity.CertificationApplication;
import com.ems.entity.ExamSession;
import com.ems.enums.CertificationApplicationStatus;
import com.ems.repository.CertificationApplicationRepository;
import com.ems.util.ExamAttemptAllowance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Single owner of the "exam invalidated by proctoring" side effect.
 *
 * <p>Extracted so the legacy {@code /api/proctoring} path and the AI proctoring
 * path at {@code /api/proctor/log-violation} cannot drift: both terminate a
 * candidate through exactly the same rule, which marks the live certification
 * application {@code TERMINATED}. The candidate restarts from question one — on
 * a retake their payment still covers, or by re-applying and paying again once
 * it covers no more.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
public class ExamInvalidationHandler {

    /** What happened. Every note starts with it, so a note is never appended twice. */
    static final String INVALIDATION_NOTE =
            "Exam invalidated for reaching the proctoring violation limit.";

    static final String RESTART_NOTE =
            INVALIDATION_NOTE + " Re-apply and complete payment to restart from question 1.";

    static final String RETAKE_NOTE =
            INVALIDATION_NOTE + " Your payment covers another attempt — start it from question 1 without paying again.";

    private final CertificationApplicationRepository certificationApplicationRepository;

    /**
     * Marks the candidate's live application as terminated after their session
     * was invalidated. Safe to call more than once for the same session: an
     * application already in a terminal state is left untouched.
     *
     * <p>{@code TERMINATED} rather than {@code FAILED}: the attempt was never
     * scored, and recording it as a failed exam misreports what happened to
     * anyone reading the candidate's history afterwards.</p>
     */
    public void markLatestApplicationAsFailedForRestart(ExamSession session) {
        CertificationApplication application = resolveApplication(session);

        if (application == null) {
            log.warn("No certification application found to mark as terminated after exam invalidation: sessionId={} examCode={}",
                    session.getId(), session.getExam().getExamCode());
            return;
        }

        if (application.getApplicationStatus() != CertificationApplicationStatus.APPLIED
                && application.getApplicationStatus() != CertificationApplicationStatus.ELIGIBLE
                && application.getApplicationStatus() != CertificationApplicationStatus.IN_PROGRESS) {
            return;
        }

        application.setApplicationStatus(CertificationApplicationStatus.TERMINATED);
        application.setRemarks(appendInvalidationNote(application));
        certificationApplicationRepository.save(application);

        log.info("Certification application marked TERMINATED after proctoring invalidation: sessionId={} applicationId={}",
                session.getId(), application.getId());
    }

    /**
     * The application's remarks with the invalidation note added, unless one is
     * already there. Also used by the dashboard, which reaches the same event
     * late and must record it the same way.
     *
     * <p>Read after the status is set to {@code TERMINATED}: whether the note
     * offers a free retake depends on the attempt having ended.</p>
     */
    static String appendInvalidationNote(CertificationApplication application) {
        String note = ExamAttemptAllowance.retakeAvailable(application) ? RETAKE_NOTE : RESTART_NOTE;
        String existingRemarks = application.getRemarks();
        if (existingRemarks == null || existingRemarks.isBlank()) {
            return note;
        }
        if (existingRemarks.contains(INVALIDATION_NOTE)) {
            return existingRemarks;
        }
        return existingRemarks + " | " + note;
    }

    private CertificationApplication resolveApplication(ExamSession session) {
        /*
         * The session's own link first. A retake is a newer application for the
         * same exam, so once a candidate has one, "their latest application for
         * this exam" can be a different attempt from the one being terminated.
         * The lookups below are for sessions written before sessions carried it.
         */
        if (session.getCertificationApplication() != null) {
            return session.getCertificationApplication();
        }
        return certificationApplicationRepository
                .findTopByUserAndExamAndApplicationStatusOrderByAppliedOnDescIdDesc(
                        session.getUser(),
                        session.getExam(),
                        CertificationApplicationStatus.IN_PROGRESS)
                .or(() -> certificationApplicationRepository
                        .findTopByUserAndExamAndApplicationStatusInOrderByAppliedOnDescIdDesc(
                                session.getUser(),
                                session.getExam(),
                                Set.of(CertificationApplicationStatus.APPLIED, CertificationApplicationStatus.ELIGIBLE)))
                .or(() -> certificationApplicationRepository
                        .findTopByUserAndExamOrderByAppliedOnDescIdDesc(session.getUser(), session.getExam()))
                .orElse(null);
    }
}
