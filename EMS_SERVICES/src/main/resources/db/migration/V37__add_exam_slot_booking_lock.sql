-- src/main/resources/db/migration/V37__add_exam_slot_booking_lock.sql
-- Exam slots: at most 100 candidates booked into the same stretch of time across
-- every exam, and a 15-minute break after each slot's exam before the next slot.
-- The timetable itself is computed, not stored; see com.ems.util.ExamSlot.
--
-- Seats are counted and then taken in two statements, so every booking first
-- locks this table's single row. Without it, two candidates confirming the last
-- seat together would both count 99 and both be booked.
CREATE TABLE IF NOT EXISTS exam_slot_booking_locks (
    id INT PRIMARY KEY
);

INSERT INTO exam_slot_booking_locks (id) VALUES (1) ON CONFLICT (id) DO NOTHING;

-- Seats are counted from the bookings starting in and just before a slot.
CREATE INDEX IF NOT EXISTS idx_certification_applications_scheduled_exam_time
    ON certification_applications (scheduled_exam_time);
