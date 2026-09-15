package com.ems.util;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The timetable exams are booked against, and how many seats a slot has taken.
 *
 * <p>A slot is one sitting of an exam and the break after it: a 60-minute exam's
 * slot lasts 75 minutes, and its next slot starts as that break ends. Slots of
 * one exam therefore never overlap, and at most {@link #CAPACITY} candidates are
 * booked into any stretch of time. The timetable restarts at 00:00 UTC each day
 * and leaves out a slot whose break would run past midnight, so every day offers
 * the same times and no slot straddles two days.</p>
 *
 * <p>The capacity is shared by every exam, but exams of different lengths keep
 * different timetables — a 90-minute exam's slots are 105 minutes apart — so one
 * slot can overlap two of another exam's. Seats are counted at the busiest moment
 * inside a slot, not as the bookings that happen to start with it.</p>
 *
 * <p>Kept here rather than in the service for the reason {@link ExamStartWindow}
 * is: booking enforces it and the availability list shows it, and if the two
 * disagreed the picker would offer a slot the server then refused.</p>
 */
public final class ExamSlot {

    /** Most candidates booked into the same stretch of time, across every exam. */
    public static final int CAPACITY = 100;

    /** The gap after each slot's exam before that exam's next slot starts. */
    public static final Duration BREAK = Duration.ofMinutes(15);

    /**
     * How long before a slot a booking can start and still be running inside it.
     * No exam lasts a day, so this reaches every overlapping booking — including
     * one made before this timetable existed, which need not have kept to midnight.
     */
    public static final Duration LOOKBACK = Duration.ofDays(1);

    private static final Duration DAY = Duration.ofDays(1);

    private ExamSlot() {
    }

    /** The stretch of time a booking occupies: its exam and the break after it. */
    public record Span(Instant start, Instant end) {

        public static Span of(Instant start, int durationMinutes) {
            return new Span(start, start.plus(length(durationMinutes)));
        }

        boolean overlaps(Span other) {
            return start.isBefore(other.end) && other.start.isBefore(end);
        }

        boolean covers(Instant moment) {
            return !moment.isBefore(start) && moment.isBefore(end);
        }
    }

    /** How long one slot of an exam this many minutes long lasts. */
    public static Duration length(int durationMinutes) {
        return Duration.ofMinutes(durationMinutes).plus(BREAK);
    }

    /** Whether a slot of this exam starts exactly at {@code at}. */
    public static boolean isSlotStart(Instant at, int durationMinutes) {
        Duration length = length(durationMinutes);
        Instant dayStart = at.truncatedTo(ChronoUnit.DAYS);
        return Duration.between(dayStart, at).toNanos() % length.toNanos() == 0
                && !at.plus(length).isAfter(dayStart.plus(DAY));
    }

    /** Every slot of this exam starting at or after {@code from} and before {@code to}, earliest first. */
    public static List<Instant> slotsBetween(Instant from, Instant to, int durationMinutes) {
        Duration length = length(durationMinutes);
        List<Instant> slots = new ArrayList<>();
        for (Instant dayStart = from.truncatedTo(ChronoUnit.DAYS); dayStart.isBefore(to); dayStart = dayStart.plus(DAY)) {
            Instant dayEnd = dayStart.plus(DAY);
            for (Instant start = dayStart; !start.plus(length).isAfter(dayEnd); start = start.plus(length)) {
                if (!start.isBefore(from) && start.isBefore(to)) {
                    slots.add(start);
                }
            }
        }
        return slots;
    }

    /**
     * Seats taken in {@code slot}: the most {@code bookings} running at any one
     * moment inside it.
     *
     * <p>The count only rises where a booking starts, so the busiest moment is
     * the slot's own start or the start of a booking inside it, and those are the
     * only moments checked.</p>
     */
    public static int seatsTaken(Span slot, List<Span> bookings) {
        List<Span> overlapping = bookings.stream().filter(slot::overlaps).toList();

        Set<Instant> moments = new TreeSet<>();
        moments.add(slot.start());
        overlapping.stream()
                .map(Span::start)
                .filter(start -> start.isAfter(slot.start()))
                .forEach(moments::add);

        int busiest = 0;
        for (Instant moment : moments) {
            int running = (int) overlapping.stream().filter(booking -> booking.covers(moment)).count();
            busiest = Math.max(busiest, running);
        }
        return busiest;
    }
}
