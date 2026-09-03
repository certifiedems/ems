package com.ems.dto.response;

import java.time.Instant;

import com.ems.enums.CertificationLevel;
import com.ems.enums.ExamStatus;

/**
 * One exam's booking window, with the number of people it is holding up.
 *
 * <p>Kept apart from {@link ExamResponse} rather than bolted onto it. That
 * record describes what an exam is and is read on every admin and candidate
 * screen; this one exists for a single operational question — which windows
 * have closed and who is stuck behind them — and answering it costs an
 * aggregate over every application, which no other caller of {@code
 * ExamResponse} should be made to pay for.</p>
 *
 * @param waitingApplications paid applications with no attempt started that
 *                            still need a slot, either never booked or booked
 *                            and missed. These are exactly the candidates a
 *                            closed window refuses.
 * @param bookedApplications  paid applications holding a slot that is still
 *                            ahead of them. Reported so a closed window is not
 *                            read as having stranded them; their booking stands
 *                            and will still start.
 */
public record ExamBookingWindowResponse(
        Long examId,
        String examCode,
        String examName,
        CertificationLevel certificationLevel,
        ExamStatus examStatus,
        boolean published,
        Instant bookingOpensAt,
        Instant bookingClosesAt,
        long waitingApplications,
        long bookedApplications) {
}
