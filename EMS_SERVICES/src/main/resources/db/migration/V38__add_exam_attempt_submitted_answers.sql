-- src/main/resources/db/migration/V38__add_exam_attempt_submitted_answers.sql
-- The answers a candidate submitted, kept with the result they earned.
--
-- Scoring used to keep only the totals (attempted, correct, wrong), so once an
-- attempt was marked nobody could say which questions it got wrong. The admin
-- exam tracker needs exactly that. Held as {"<questionId>": ["option", ...]},
-- the shape exam_sessions.answers_draft_json already uses.
--
-- NULL on attempts submitted before this column existed: their answers were
-- never stored, and the tracker falls back to the session's last autosave.
ALTER TABLE exam_attempts
    ADD COLUMN IF NOT EXISTS submitted_answers_json TEXT;
