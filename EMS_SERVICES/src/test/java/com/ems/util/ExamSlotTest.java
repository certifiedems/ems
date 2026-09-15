package com.ems.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ems.util.ExamSlot.Span;

class ExamSlotTest {

	private static final Instant DAY = Instant.parse("2026-09-20T00:00:00Z");

	@Test
	void aSlotLastsTheExamAndTheBreak() {
		assertThat(ExamSlot.length(60)).isEqualTo(Duration.ofMinutes(75));
	}

	@Test
	void aSixtyMinuteExamHasASlotEverySeventyFiveMinutesFromMidnightUtc() {
		assertThat(ExamSlot.isSlotStart(DAY, 60)).isTrue();
		assertThat(ExamSlot.isSlotStart(at("01:15"), 60)).isTrue();
		assertThat(ExamSlot.isSlotStart(at("10:00"), 60)).isTrue();

		assertThat(ExamSlot.isSlotStart(at("10:15"), 60)).isFalse();
		assertThat(ExamSlot.isSlotStart(at("10:00").plusMillis(1), 60)).isFalse();
	}

	@Test
	void aLongerExamKeepsItsOwnTimetable() {
		// 90 minutes and the break: 00:00, 01:45, 03:30 ...
		assertThat(ExamSlot.isSlotStart(at("01:45"), 90)).isTrue();
		assertThat(ExamSlot.isSlotStart(at("01:15"), 90)).isFalse();
	}

	/** 23:45 is on the 75-minute beat, but its exam and break would run into tomorrow. */
	@Test
	void theLastSlotOfTheDayFinishesItsBreakByMidnight() {
		List<Instant> slots = ExamSlot.slotsBetween(DAY, DAY.plus(1, ChronoUnit.DAYS), 60);

		assertThat(slots).hasSize(19).startsWith(DAY);
		assertThat(slots.get(slots.size() - 1)).isEqualTo(at("22:30"));
		assertThat(ExamSlot.isSlotStart(at("23:45"), 60)).isFalse();
	}

	@Test
	void slotsBetweenTwoInstantsIncludeTheFirstAndExcludeTheLast() {
		assertThat(ExamSlot.slotsBetween(at("10:00"), at("12:30"), 60))
				.containsExactly(at("10:00"), at("11:15"));
	}

	@Test
	void everyBookingInTheSameSlotTakesASeat() {
		Span slot = Span.of(at("10:00"), 60);

		assertThat(ExamSlot.seatsTaken(slot, List.of(Span.of(at("10:00"), 60), Span.of(at("10:00"), 60))))
				.isEqualTo(2);
	}

	/** The break of the slot before ends exactly as this one starts, so neighbours never share a seat. */
	@Test
	void theSlotsEitherSideTakeNoSeat() {
		Span slot = Span.of(at("11:15"), 60);

		assertThat(ExamSlot.seatsTaken(slot, List.of(Span.of(at("10:00"), 60), Span.of(at("12:30"), 60))))
				.isZero();
	}

	/**
	 * A 90-minute slot at 10:30 runs to 12:15, across the 60-minute slots at 10:00
	 * and 11:15. Those two never run together, so the seats taken are the fuller
	 * of the two rather than their sum.
	 */
	@Test
	void aSlotOverlappingTwoOthersCountsItsBusiestMomentNotEveryBookingItTouches() {
		List<Span> bookings = new ArrayList<>();
		bookings.addAll(Collections.nCopies(30, Span.of(at("10:00"), 60)));
		bookings.addAll(Collections.nCopies(50, Span.of(at("11:15"), 60)));

		assertThat(ExamSlot.seatsTaken(Span.of(at("10:30"), 90), bookings)).isEqualTo(50);
	}

	private static Instant at(String time) {
		return Instant.parse("2026-09-20T" + time + ":00Z");
	}
}
