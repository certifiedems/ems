-- Reopen the booking windows that the seed data let run out.
--
-- The three certification exams were seeded with a window of NOW() to
-- NOW() + 30 days — relative to whenever the database happened to be created,
-- not to any decision anyone made about how long the exam should be bookable.
-- Thirty days on, scheduled_end_time is in the past, and scheduleExam refuses
-- every slot a candidate picks because no instant can be both in the future and
-- inside a window that has already closed. Candidates with a paid application
-- and a missed slot were left cycling through the picker, refused on every date
-- they tried, with nothing telling them the exam itself had shut.
--
-- Scoped deliberately to the three seeded codes whose window has already
-- expired. An exam window a person actually chose — through
-- POST /api/exams/{examId}/schedule — is a decision, and a migration must not
-- overrule one; these were never chosen by anybody.
--
-- Two years rather than another thirty days, so this does not come back round
-- as the same outage. Moving a window on purpose is the admin endpoint's job.

UPDATE exams
SET scheduled_end_time = CURRENT_TIMESTAMP + INTERVAL '2 years',
    updated_by = 'SYSTEM',
    updated_date = CURRENT_TIMESTAMP
WHERE exam_code IN ('L1-FOUND-001', 'L2-ADV-001', 'L3-EXPERT-001')
  AND scheduled_end_time IS NOT NULL
  AND scheduled_end_time < CURRENT_TIMESTAMP;
