package com.ems.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * Catalogue of proctoring violations recognised by the platform.
 *
 * <p>The first block is the original browser-security set. The second block is
 * emitted by the client-side AI proctoring pipeline (TensorFlow.js running in a
 * background Web Worker) and by the hardened browser-security listeners.</p>
 *
 * <p>Persisted as {@code VARCHAR(80)} in {@code violations.violation_type};
 * adding constants here requires no DDL change.</p>
 */
public enum ViolationType {

    // --- Browser security / session integrity -------------------------------
    TAB_SWITCH,
    /** Not detected: the exam client reports a minimised window as {@link #WINDOW_FOCUS_LOST}. */
    WINDOW_MINIMIZED,
    WINDOW_FOCUS_LOST,
    /**
     * The proctoring camera stopped delivering video: disconnected, switched off,
     * taken by another application, or its permission revoked.
     *
     * <p>Judged from the camera track rather than from what the models see. A
     * camera that stops leaves the video element holding its last frame, and a
     * frozen frame of a seated candidate passes every visual check.</p>
     */
    WEBCAM_OFF,
    /**
     * The exam page itself was interfered with: the proctoring watermark removed
     * or hidden, or developer tools open during the attempt.
     */
    SESSION_TAMPERING,
    BROWSER_MONITORING,
    /**
     * The same attempt open in two places at once — two browsers, tabs or devices
     * checking in together. Detected by the server from heartbeats rather than
     * reported by a client; see {@code ExamPresenceTracker}.
     */
    MULTIPLE_LOGIN,

    // --- AI computer-vision detections (Web Worker) -------------------------
    /** COCO-SSD recognised a mobile phone inside the webcam frame. */
    PHONE_DETECTED,
    /** More than one person present in front of the candidate camera. */
    MULTIPLE_FACES,
    /** No person detected in the frame for a sustained window. */
    FACE_NOT_VISIBLE,
    /** Head yaw indicates the candidate turned away from the screen. */
    FACE_TURNED_AWAY,
    /** Vertical gaze/head pitch indicates the candidate is looking down. */
    EYES_OFF_SCREEN,

    // --- Environment / peripheral --------------------------------------------
    /**
     * A sustained, unvarying sound source in the room — a fan, a television, road
     * noise. Emitted by the sound-event engine for events it classified
     * {@code SUSTAINED_NOISE}.
     */
    BACKGROUND_NOISE,

    /**
     * A human voice was heard near the candidate.
     *
     * <p>Classified from periodicity, pitch range and voice-band energy share
     * rather than loudness, so a whisper across the desk counts and a fan at the
     * same level does not. This is a discrete, checkable event — someone was
     * speaking in the room during the attempt — which is what makes an automatic
     * strike defensible where a bare loudness reading would not be.</p>
     */
    VOICE_DETECTED,

    /**
     * A sound occurred near the candidate that the engine could not identify.
     *
     * <p>A cough, a chair, a dropped pen, a door, a notification chime. This
     * counts as a strike, but not immediately. It was review-only until the
     * platform's reviewing invigilator turned out not to exist: a flag nobody
     * reads is not a lighter sanction than a strike, it is no sanction at all.
     * Making every sound a strike was the other extreme, and would have ended
     * attempts over a candidate's throat.</p>
     *
     * <p>What survives both is a grace: the first sounds of an attempt are
     * recorded and forgiven, and the ones after them count. The judgement being
     * made is not "was that noise innocent", which a microphone cannot answer,
     * but "is this still happening" — a cough does not repeat on a schedule and
     * a conversation with someone out of frame does. How many are forgiven, and
     * how loud one has to be before the client raises it at all, are set per
     * policy — see {@link com.ems.service.EffectiveProctoringPolicy} — and the
     * client's per-type cooldown decides what else reaches the server.</p>
     */
    SOUND_DETECTED,

    // --- Enforced browser state ----------------------------------------------
    /** Candidate left enforced full-screen mode. */
    FULLSCREEN_EXIT,
    /** Mandatory display capture was revoked by the candidate. */
    SCREEN_SHARE_STOPPED,
    /** Candidate refused the mandatory display-capture prompt. */
    SCREEN_SHARE_DENIED,
    /** Heartbeat failed or the browser reported a loss of connectivity. */
    NETWORK_LOSS,
    /** A concurrent MediaRecorder / capture surface was observed. */
    SCREEN_RECORDING_SUSPECTED,

    // --- Environment geometry -------------------------------------------------
    /**
     * The candidate's camera geometry stopped meeting exam requirements — most
     * often a laptop moved off the desk and into the candidate's lap, which points
     * the camera up past their face and collapses the angle between the exam
     * screen and anything held beside it.
     */
    PROCTOR_SETUP_INVALID;

    /**
     * Types nothing detects.
     *
     * <p>Still accepted by the legacy reporting endpoint, but not offered for an
     * administrator to configure: a switch no detection ever reaches would read
     * as a rule being enforced when nothing is enforcing it.</p>
     */
    private static final Set<ViolationType> NOT_DETECTED = EnumSet.of(WINDOW_MINIMIZED);

    /**
     * Detections recorded without a strike unless an administrator says otherwise.
     *
     * <p>These are the two measurements the client cannot make confidently enough
     * to end an attempt on. Gaze direction comes from an iris displacement of a
     * couple of pixels on a webcam frame, and camera geometry is a heuristic over
     * face proportions; both drift with lighting, spectacles and face shape, so a
     * candidate sitting honestly can produce a steady stream of either. Everything
     * else — a phone in frame, a second face, a voice, an unidentified noise,
     * full-screen abandoned — is a discrete event that either happened or did
     * not, where an automatic strike is defensible.
     *
     * <p>There is no invigilator queue behind "record only". It means logged as
     * evidence with no sanction, so choosing it for a type is a decision to let
     * that behaviour go unpunished, not a decision to escalate it elsewhere.</p>
     */
    private static final Set<ViolationType> RECORD_ONLY_BY_DEFAULT =
            EnumSet.of(EYES_OFF_SCREEN, PROCTOR_SETUP_INVALID);

    /** Whether an administrator's proctoring policy can change how this type is enforced. */
    public boolean isAdminConfigurable() {
        return !NOT_DETECTED.contains(this);
    }

    /**
     * How this type is enforced when no administrator has said otherwise — which
     * is exactly how it was enforced before policies existed.
     */
    public ViolationEnforcement defaultEnforcement() {
        return RECORD_ONLY_BY_DEFAULT.contains(this) ? ViolationEnforcement.RECORD_ONLY : ViolationEnforcement.STRIKE;
    }
}
