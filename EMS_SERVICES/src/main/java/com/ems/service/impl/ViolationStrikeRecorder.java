package com.ems.service.impl;

import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ems.dto.request.ViolationRequestDTO;
import com.ems.dto.response.ViolationLogResponse;
import com.ems.entity.ExamSession;
import com.ems.entity.ProctorEvidence;
import com.ems.entity.Violation;
import com.ems.enums.EvidenceStorageKind;
import com.ems.enums.ExamStatus;
import com.ems.enums.ProctoringAction;
import com.ems.enums.ViolationType;
import com.ems.exception.BusinessException;
import com.ems.exception.ResourceNotFoundException;
import com.ems.repository.ExamSessionRepository;
import com.ems.repository.ProctorEvidenceRepository;
import com.ems.repository.ViolationRepository;
import com.ems.service.ProctorEvidenceStorageService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Transactional core of the AI proctoring write path.
 *
 * <p>Deliberately a separate bean from {@link AiProctorServiceImpl}: that class
 * carries {@code @Async}, and putting {@code @Async} and {@code @Transactional}
 * on one method makes proxy ordering (and therefore the transaction boundary)
 * hard to reason about. Here the async hop happens first, then this bean opens a
 * clean transaction on the worker thread.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.data.mode", havingValue = "sql", matchIfMissing = true)
public class ViolationStrikeRecorder {

    /** Strikes tolerated before the attempt is invalidated. */
    public static final int STRIKE_LIMIT = 3;

    /**
     * Unidentified sounds allowed before they start costing strikes.
     *
     * <p>A cough, a sneeze, a chair — once or twice across an hour is a person
     * sitting in a room, and an exam that ends on it is measuring the candidate's
     * throat. What is not innocent is the same thing happening again and again:
     * repetition is the whole difference between a noise and a signal to someone
     * out of frame, and it is the only part of this a microphone can actually
     * establish. So the first {@value} are recorded and forgiven; from the next
     * one on, each costs a strike like any other detection.</p>
     *
     * <p>Counted per attempt rather than over a rolling window, because that is
     * the version a candidate can be told in one sentence and a support desk can
     * defend without replaying timestamps.</p>
     */
    public static final int UNIDENTIFIED_SOUND_GRACE = 2;

    /** Matches an optional RFC 2397 data-URI prefix on an inbound frame. */
    private static final Pattern DATA_URI_PREFIX =
            Pattern.compile("^data:(?<mime>[\\w.+-]+/[\\w.+-]+)?(;charset=[\\w-]+)?;base64,", Pattern.CASE_INSENSITIVE);

    private static final String DEFAULT_MEDIA_TYPE = "image/jpeg";

    private final ExamSessionRepository examSessionRepository;
    private final ViolationRepository violationRepository;
    private final ProctorEvidenceRepository proctorEvidenceRepository;
    private final ProctorEvidenceStorageService proctorEvidenceStorageService;
    private final ExamInvalidationHandler examInvalidationHandler;

    /**
     * Records one violation against a session the caller owns.
     *
     * <p>Runs {@code REQUIRES_NEW} so a violation is durable on its own terms and is
     * never rolled back by an unrelated failure further up the request.</p>
     *
     * <p>Concurrency: the session row is re-read under {@code PESSIMISTIC_WRITE}
     * inside this transaction. Every competing detection therefore serialises on
     * that row, and each one observes the committed count of its predecessor. The
     * ownership check is performed against the pre-locked lookup, so the lock is
     * only ever taken for a caller already proven to own the session.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ViolationLogResponse record(String callerEmail, ViolationRequestDTO request) {
        ExamSession unlockedSession = resolveOwnedSession(callerEmail, request);

        // Re-read under a row lock; the unlocked read above only established ownership.
        ExamSession session = examSessionRepository.findByIdForUpdate(unlockedSession.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Exam session not found"));

        // A session invalidated by a racing detection must not accrue further strikes.
        if (session.getSessionStatus() != ExamStatus.IN_PROGRESS) {
            return alreadyTerminatedResponse(session, request);
        }

        /*
         * Low-confidence detections are recorded with their evidence but left out
         * of the strike counter. Gaze direction is inferred from a couple of pixels
         * of iris displacement and camera geometry from face proportions; neither
         * is sound enough to end an attempt on its own, and three of them in quick
         * succession must not be able to.
         */
        boolean reviewOnlyType = !request.violationType().countsAsStrike();
        boolean forgivenSound = !reviewOnlyType && withinUnidentifiedSoundGrace(session, request.violationType());
        boolean countsAsStrike = !reviewOnlyType && !forgivenSound;
        int strikeCount = countsAsStrike ? session.getViolationCount() + 1 : session.getViolationCount();
        boolean terminated = countsAsStrike && strikeCount >= STRIKE_LIMIT;

        ProctoringAction actionTaken;
        if (reviewOnlyType) {
            actionTaken = ProctoringAction.FLAGGED_FOR_REVIEW;
        } else if (forgivenSound) {
            // Not FLAGGED_FOR_REVIEW: nothing about this detection is uncertain.
            // The engine is confident a sound occurred and the rule has decided
            // it is forgiven, which is what LOGGED has always meant.
            actionTaken = ProctoringAction.LOGGED;
        } else if (terminated) {
            actionTaken = ProctoringAction.EXAM_TERMINATED;
        } else {
            actionTaken = ProctoringAction.WARNING;
        }

        Instant detectedAt = Instant.now();

        Violation violation = violationRepository.save(Violation.builder()
                .examSession(session)
                .violationType(request.violationType())
                /*
                 * The strike this detection produced, or 0 for a review-only type
                 * that produced none. 0 was rejected by the original column
                 * constraint, which took every detection to be a strike, so every
                 * review-only violation logged before the candidate earned their
                 * first strike was lost on insert -- silently, because the write
                 * is asynchronous and the API had already answered. See V22.
                 */
                .violationLevel(strikeCount)
                .description(buildDescription(request))
                .detectedAt(detectedAt)
                .actionTaken(actionTaken)
                .build());

        Long evidenceId = persistEvidence(session, violation, request, detectedAt);

        // Left untouched for a review-only detection: the session row carries the
        // strike count, and writing an unchanged value back would be a pointless
        // update on a row every competing detection is serialising against.
        if (countsAsStrike) {
            session.setViolationCount(strikeCount);
            if (terminated) {
                session.setSessionStatus(ExamStatus.INVALIDATED);
                session.setSessionEndTime(detectedAt);
            }
            examSessionRepository.save(session);
        }

        if (terminated) {
            examInvalidationHandler.markLatestApplicationAsFailedForRestart(session);
        }

        log.info("AI proctoring violation recorded: sessionId={} type={} strike={}/{} action={} evidence={}",
                session.getId(), request.violationType(), strikeCount, STRIKE_LIMIT, actionTaken, evidenceId != null);

        return new ViolationLogResponse(
                violation.getId(),
                session.getId(),
                request.violationType(),
                strikeCount,
                STRIKE_LIMIT,
                Math.max(0, STRIKE_LIMIT - strikeCount),
                terminated,
                actionTaken,
                evidenceId != null,
                evidenceId,
                detectedAt,
                buildOutcomeMessage(countsAsStrike, forgivenSound, terminated, strikeCount));
    }

    /**
     * Whether this detection is one of the unidentified sounds a candidate is
     * allowed before they begin to count.
     *
     * <p>Read inside the same transaction that holds the session row lock, so two
     * detections racing each other cannot both see the same "prior" count and
     * both be forgiven. Only prior rows are counted — this one has not been
     * written yet — so the grace is spent by the {@code GRACE + 1}-th sound.</p>
     */
    private boolean withinUnidentifiedSoundGrace(ExamSession session, ViolationType violationType) {
        if (violationType != ViolationType.SOUND_DETECTED) {
            return false;
        }
        long alreadyHeard = violationRepository.countByExamSessionAndViolationType(session, violationType);
        return alreadyHeard < UNIDENTIFIED_SOUND_GRACE;
    }

    /**
     * Candidate-facing outcome text.
     *
     * <p>A review flag says so plainly rather than borrowing the strike wording: a
     * candidate told "strike 1 of 3" for a detection that carries no strike will
     * behave as though it did, and one told nothing at all learns that the
     * behaviour is unobserved.</p>
     */
    private String buildOutcomeMessage(boolean countsAsStrike, boolean forgivenSound,
            boolean terminated, int strikeCount) {
        /*
         * Told plainly that the next one bites. A candidate who hears "no strike
         * was recorded" and nothing else learns that noise is free, which is the
         * opposite of what the grace is for — it exists to forgive a cough, not
         * to conceal that the room is being listened to.
         */
        if (forgivenSound) {
            return ("An unidentified sound was recorded but not counted. Unidentified sounds are "
                    + "forgiven %d times per attempt; the next one will count as a strike. "
                    + "You currently have %d of %d.")
                    .formatted(UNIDENTIFIED_SOUND_GRACE, strikeCount, STRIKE_LIMIT);
        }
        if (!countsAsStrike) {
            return "Flagged for invigilator review. No strike was recorded; you currently have %d of %d."
                    .formatted(strikeCount, STRIKE_LIMIT);
        }
        if (terminated) {
            return "Strike %d of %d recorded. The exam has been terminated and the attempt invalidated."
                    .formatted(STRIKE_LIMIT, STRIKE_LIMIT);
        }
        return "Strike %d of %d recorded. %d more will terminate the exam."
                .formatted(strikeCount, STRIKE_LIMIT, STRIKE_LIMIT - strikeCount);
    }

    /**
     * Resolves the session from {@code (examId, studentId)} but authorises it from the
     * JWT principal. The client-supplied {@code studentId} is only ever compared, never
     * used to look anything up, so a tampered payload cannot reach another candidate.
     */
    private ExamSession resolveOwnedSession(String callerEmail, ViolationRequestDTO request) {
        ExamSession session = examSessionRepository
                .findTopByUserEmailIgnoreCaseAndExamIdAndSessionStatusOrderByIdDesc(
                        callerEmail, request.examId(), ExamStatus.IN_PROGRESS)
                .or(() -> examSessionRepository
                        .findTopByUserEmailIgnoreCaseAndExamIdOrderByIdDesc(callerEmail, request.examId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No exam session found for the authenticated candidate on exam " + request.examId()));

        String ownerUserId = session.getUser().getUserId();
        if (ownerUserId == null || !ownerUserId.equalsIgnoreCase(request.studentId())) {
            log.warn("Rejected proctoring violation: studentId mismatch. caller={} claimed={} owner={} sessionId={}",
                    callerEmail, request.studentId(), ownerUserId, session.getId());
            throw new BusinessException(
                    "studentId does not match the authenticated candidate", HttpStatus.FORBIDDEN);
        }

        return session;
    }

    /** Idempotent-ish short circuit for detections that arrive after termination. */
    private ViolationLogResponse alreadyTerminatedResponse(ExamSession session, ViolationRequestDTO request) {
        boolean invalidated = session.getSessionStatus() == ExamStatus.INVALIDATED;
        log.debug("Ignoring violation for non-active session: sessionId={} status={} type={}",
                session.getId(), session.getSessionStatus(), request.violationType());

        return new ViolationLogResponse(
                null,
                session.getId(),
                request.violationType(),
                session.getViolationCount(),
                STRIKE_LIMIT,
                Math.max(0, STRIKE_LIMIT - session.getViolationCount()),
                invalidated,
                invalidated ? ProctoringAction.EXAM_TERMINATED : ProctoringAction.LOGGED,
                false,
                null,
                Instant.now(),
                invalidated
                        ? "The exam was already terminated; no further strikes are recorded."
                        : "The session is no longer active; the violation was not counted.");
    }

    /**
     * Writes the frame into the isolated blob table.
     *
     * <p>Evidence is best-effort by design: a malformed or oversized frame must never
     * prevent the strike itself from being recorded, so failures here are logged and
     * swallowed rather than rolled back.</p>
     *
     * <p>Where object storage is configured, only a key is written and
     * {@code evidence_payload} is left null. Evidence is the one table here that
     * grows without bound — every violation of every attempt — so keeping the bytes
     * out of the database is what stops it from consuming the same storage quota as
     * questions, answers and submissions.</p>
     */
    private Long persistEvidence(ExamSession session, Violation violation,
            ViolationRequestDTO request, Instant capturedAt) {
        DecodedFrame frame = decodeFrame(session, request);
        if (frame == null) {
            return null;
        }

        String objectStorageKey = offloadFrame(session, violation, frame);

        ProctorEvidence.ProctorEvidenceBuilder evidence = ProctorEvidence.builder()
                .violation(violation)
                .examSession(session)
                .mediaType(frame.mediaType())
                .payloadBytes(frame.byteLength())
                .capturedAt(capturedAt);

        /*
         * Exactly one of the two columns is populated, which is what the
         * chk_evidence_payload_present constraint on the table requires.
         */
        if (objectStorageKey != null) {
            evidence.storageKind(EvidenceStorageKind.OBJECT_STORAGE)
                    .objectStorageKey(objectStorageKey);
        } else {
            evidence.storageKind(EvidenceStorageKind.INLINE_BASE64)
                    .evidencePayload(frame.base64Payload());
        }

        return proctorEvidenceRepository.save(evidence.build()).getId();
    }

    /**
     * Uploads the frame to object storage, or returns {@code null} to leave it inline.
     *
     * <p>An upload failure degrades to the inline column rather than propagating:
     * losing the frame — or worse, the strike — because a bucket was briefly
     * unreachable is a far worse outcome than one oversized row. The fallback is
     * logged at WARN so a persistently broken bucket is visible rather than silently
     * refilling the database.</p>
     */
    private String offloadFrame(ExamSession session, Violation violation, DecodedFrame frame) {
        if (!proctorEvidenceStorageService.isObjectStorageEnabled()) {
            return null;
        }

        try {
            return proctorEvidenceStorageService.storeEvidence(
                    frame.bytes(), frame.mediaType(), session.getId(), violation.getId());
        } catch (RuntimeException ex) {
            log.warn("Evidence upload failed for sessionId={} violationId={}; storing inline instead: {}",
                    session.getId(), violation.getId(), ex.getMessage());
            return null;
        }
    }

    /**
     * Validates and normalises an inbound frame.
     *
     * <p>Returns {@code null} for anything unusable so a corrupt capture degrades to
     * "strike recorded without evidence" instead of failing the whole request. Kept
     * free of database calls: swallowing an exception thrown by a persistence
     * operation would leave the surrounding transaction marked rollback-only.</p>
     */
    private DecodedFrame decodeFrame(ExamSession session, ViolationRequestDTO request) {
        if (!request.hasEvidence()) {
            return null;
        }

        String raw = request.evidenceImage();
        String mediaType = DEFAULT_MEDIA_TYPE;

        Matcher matcher = DATA_URI_PREFIX.matcher(raw);
        if (matcher.find()) {
            if (matcher.group("mime") != null) {
                mediaType = matcher.group("mime").toLowerCase(Locale.ROOT);
            }
            raw = raw.substring(matcher.end());
        }

        // Strip whitespace/newlines that some canvas encoders emit.
        String payload = raw.replaceAll("\\s", "");
        if (payload.isEmpty()) {
            return null;
        }

        try {
            // Decoding validates the frame and yields its true byte size. Both forms
            // are kept: the raw bytes are what object storage takes, the base64 string
            // is what the inline column takes, and which one is used is not known
            // until the upload has been attempted.
            byte[] decoded = Base64.getDecoder().decode(payload);
            if (decoded.length == 0) {
                return null;
            }
            return new DecodedFrame(payload, decoded, mediaType);
        } catch (IllegalArgumentException ex) {
            log.warn("Discarding malformed evidence frame for sessionId={}: {}", session.getId(), ex.getMessage());
            return null;
        }
    }

    /** Validated frame ready for persistence, in both forms the two storage kinds need. */
    private record DecodedFrame(String base64Payload, byte[] bytes, String mediaType) {

        long byteLength() {
            return bytes.length;
        }
    }

    private String buildDescription(ViolationRequestDTO request) {
        String base = request.description() != null
                ? request.description()
                : request.violationType().name().replace('_', ' ').toLowerCase(Locale.ROOT);

        if (request.confidence() == null) {
            return base;
        }
        return "%s (confidence %.2f)".formatted(base, request.confidence());
    }
}
