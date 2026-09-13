package com.ems.service;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import com.ems.enums.ViolationEnforcement;
import com.ems.enums.ViolationType;

/**
 * The rules one attempt is judged by, fully resolved.
 *
 * <p>Always complete: every {@link ViolationType} has an enforcement, taken from
 * {@link ViolationType#defaultEnforcement()} wherever the saved policy says
 * nothing — a type added after the policy was saved, or one an admin cannot
 * configure. Callers never handle a missing rule.</p>
 *
 * <p>The sound thresholds are dB above the room's own noise floor, which the
 * client's sound engine tracks continuously; an absolute level would mean nothing
 * without knowing the microphone. Only the exam client hears the sound, so it is
 * the one that applies them.</p>
 */
public record EffectiveProctoringPolicy(
        int strikeLimit,
        int unidentifiedSoundGrace,
        int voiceMinDbAboveFloor,
        int backgroundNoiseMinDbAboveFloor,
        int unidentifiedSoundMinDbAboveFloor,
        Map<ViolationType, ViolationEnforcement> rules) {

    /** Strikes tolerated before an attempt is invalidated. */
    public static final int DEFAULT_STRIKE_LIMIT = 3;

    /**
     * Unidentified sounds forgiven per attempt before they start costing strikes.
     *
     * <p>A cough, a sneeze, a chair — once or twice across an hour is a person
     * sitting in a room, and an exam that ends on it is measuring the candidate's
     * throat. What is not innocent is the same thing happening again and again:
     * repetition is the only part of this a microphone can actually establish.
     * Counted per attempt rather than over a rolling window, because that is the
     * version a candidate can be told in one sentence.</p>
     */
    public static final int DEFAULT_UNIDENTIFIED_SOUND_GRACE = 2;

    /** No loudness bar for a voice: a quiet one across the desk is the case that matters most. */
    public static final int DEFAULT_VOICE_MIN_DB_ABOVE_FLOOR = 0;

    public static final int DEFAULT_BACKGROUND_NOISE_MIN_DB_ABOVE_FLOOR = 0;

    /**
     * How far above the room an unidentified sound must peak to be raised at all.
     *
     * <p>Exists for the candidate with a mechanical keyboard: every keystroke is a
     * genuine impulse, and without a bar their grace would be spent on typing and
     * the exam ended on it.</p>
     */
    public static final int DEFAULT_UNIDENTIFIED_SOUND_MIN_DB_ABOVE_FLOOR = 15;

    public static final int MIN_STRIKE_LIMIT = 1;
    public static final int MAX_STRIKE_LIMIT = 20;
    public static final int MAX_UNIDENTIFIED_SOUND_GRACE = 20;
    public static final int MAX_DB_ABOVE_FLOOR = 60;

    public EffectiveProctoringPolicy {
        Map<ViolationType, ViolationEnforcement> complete = new EnumMap<>(ViolationType.class);
        for (ViolationType type : ViolationType.values()) {
            ViolationEnforcement configured = rules != null && type.isAdminConfigurable() ? rules.get(type) : null;
            complete.put(type, configured != null ? configured : type.defaultEnforcement());
        }
        rules = Collections.unmodifiableMap(complete);
    }

    /** The rules the platform enforced before administrators could change them. */
    public static EffectiveProctoringPolicy builtIn() {
        return new EffectiveProctoringPolicy(
                DEFAULT_STRIKE_LIMIT,
                DEFAULT_UNIDENTIFIED_SOUND_GRACE,
                DEFAULT_VOICE_MIN_DB_ABOVE_FLOOR,
                DEFAULT_BACKGROUND_NOISE_MIN_DB_ABOVE_FLOOR,
                DEFAULT_UNIDENTIFIED_SOUND_MIN_DB_ABOVE_FLOOR,
                null);
    }

    public ViolationEnforcement enforcementFor(ViolationType type) {
        return rules.get(type);
    }

    /** The rules an administrator can change, in declaration order. */
    public Map<ViolationType, ViolationEnforcement> configurableRules() {
        Map<ViolationType, ViolationEnforcement> configurable = new EnumMap<>(ViolationType.class);
        rules.forEach((type, enforcement) -> {
            if (type.isAdminConfigurable()) {
                configurable.put(type, enforcement);
            }
        });
        return configurable;
    }
}
