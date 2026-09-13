package com.ems.dto.request;

import java.util.Map;

import com.ems.enums.ViolationEnforcement;
import com.ems.enums.ViolationType;
import com.ems.service.EffectiveProctoringPolicy;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * A complete set of proctoring rules, as saved from the admin console.
 *
 * <p>{@code rules} may leave a configurable type out, which then keeps its
 * built-in enforcement; naming a type an administrator cannot configure is
 * rejected.</p>
 *
 * @param strikeLimit                      strikes at which an attempt is terminated
 * @param unidentifiedSoundGrace           unidentified sounds forgiven per attempt before they count
 * @param voiceMinDbAboveFloor             dB above the room's noise floor a voice must peak at to be raised
 * @param backgroundNoiseMinDbAboveFloor   the same bar for sustained background noise
 * @param unidentifiedSoundMinDbAboveFloor the same bar for any other sound
 * @param rules                            enforcement per violation type
 * @param version                          the version the admin loaded; a stale one is refused so a
 *                                         second admin's save is not silently overwritten. Null skips
 *                                         the check.
 */
public record ProctoringPolicyRequest(

        @NotNull(message = "strikeLimit is required")
        @Min(value = EffectiveProctoringPolicy.MIN_STRIKE_LIMIT, message = "strikeLimit must be at least {value}")
        @Max(value = EffectiveProctoringPolicy.MAX_STRIKE_LIMIT, message = "strikeLimit must not exceed {value}")
        Integer strikeLimit,

        @NotNull(message = "unidentifiedSoundGrace is required")
        @Min(value = 0, message = "unidentifiedSoundGrace must not be negative")
        @Max(value = EffectiveProctoringPolicy.MAX_UNIDENTIFIED_SOUND_GRACE,
                message = "unidentifiedSoundGrace must not exceed {value}")
        Integer unidentifiedSoundGrace,

        @NotNull(message = "voiceMinDbAboveFloor is required")
        @Min(value = 0, message = "voiceMinDbAboveFloor must not be negative")
        @Max(value = EffectiveProctoringPolicy.MAX_DB_ABOVE_FLOOR,
                message = "voiceMinDbAboveFloor must not exceed {value} dB")
        Integer voiceMinDbAboveFloor,

        @NotNull(message = "backgroundNoiseMinDbAboveFloor is required")
        @Min(value = 0, message = "backgroundNoiseMinDbAboveFloor must not be negative")
        @Max(value = EffectiveProctoringPolicy.MAX_DB_ABOVE_FLOOR,
                message = "backgroundNoiseMinDbAboveFloor must not exceed {value} dB")
        Integer backgroundNoiseMinDbAboveFloor,

        @NotNull(message = "unidentifiedSoundMinDbAboveFloor is required")
        @Min(value = 0, message = "unidentifiedSoundMinDbAboveFloor must not be negative")
        @Max(value = EffectiveProctoringPolicy.MAX_DB_ABOVE_FLOOR,
                message = "unidentifiedSoundMinDbAboveFloor must not exceed {value} dB")
        Integer unidentifiedSoundMinDbAboveFloor,

        @NotNull(message = "rules are required")
        Map<ViolationType, ViolationEnforcement> rules,

        Long version) {
}
