package com.ems.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The single row every exam slot booking locks before it counts seats.
 *
 * <p>Seats are counted and then taken in two statements. Without a lock, two
 * candidates confirming the last seat at the same moment would both count 99 and
 * both be booked. Locking one shared row queues bookings behind each other, which
 * is cheap — it is held for one count and one update — and works on PostgreSQL
 * and H2 alike, which an advisory lock would not.</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "exam_slot_booking_locks")
public class ExamSlotBookingLock {

    /** The id of the one row the migration creates. */
    public static final int BOOKING_LOCK_ID = 1;

    @Id
    private Integer id;
}
