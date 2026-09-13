package com.ems.dto.response;

import java.util.Map;

import com.ems.enums.ViolationEnforcement;
import com.ems.enums.ViolationType;

/**
 * The proctoring rules the exam client runs under.
 *
 * <p>Narrower than the admin view on purpose: no audit fields, and no sound
 * grace, which only the server applies. The server stays the judge of every
 * strike; this tells the client which detections to raise at all, how loud a
 * sound must be before it is worth raising, and what limit to show the
 * candidate.</p>
 */
public record CandidateProctoringPolicyResponse(
        Long examId,
        int strikeLimit,
        int voiceMinDbAboveFloor,
        int backgroundNoiseMinDbAboveFloor,
        int unidentifiedSoundMinDbAboveFloor,
        Map<ViolationType, ViolationEnforcement> rules) {
}
