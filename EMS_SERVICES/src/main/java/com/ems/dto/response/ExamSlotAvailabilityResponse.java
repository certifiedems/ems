package com.ems.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * The slots an application's exam can be booked into over a stretch of time,
 * with the seats left in each.
 *
 * <p>Only slots the booking itself would accept are listed — inside the exam's
 * booking window and not already past — so the picker never offers a time the
 * server refuses. A full slot is still listed, with no seats left, so a day does
 * not look emptier than it is.</p>
 *
 * @param examDurationMinutes how long the exam itself runs
 * @param breakMinutes        the gap after each slot's exam before the next slot
 * @param capacity            seats in a slot, shared by every exam
 * @param slots               earliest first
 */
public record ExamSlotAvailabilityResponse(
        Long examId,
        int examDurationMinutes,
        int breakMinutes,
        int capacity,
        List<Slot> slots) {

    /**
     * @param startsAt  the time to send when booking this slot
     * @param endsAt    when the exam ends if started on time; the break follows
     * @param seatsLeft seats still free at the busiest moment of the slot
     */
    public record Slot(Instant startsAt, Instant endsAt, int seatsLeft) {
    }
}
