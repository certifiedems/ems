-- src/main/resources/db/migration/V32__add_safety_question_category.sql
-- Allow 'Safety' as a question category (QuestionCategory.SAFETY).
--
-- V1 declared the category check inline, so PostgreSQL chose its name
-- (normally questions_question_category_check). The constraint is found by
-- what it checks rather than dropped by that name, so this still applies if
-- the name differs.
DO $$
DECLARE
    existing_constraint text;
BEGIN
    FOR existing_constraint IN
        SELECT con.conname
        FROM pg_constraint con
        WHERE con.conrelid = 'questions'::regclass
          AND con.contype = 'c'
          AND pg_get_constraintdef(con.oid) LIKE '%question_category%'
    LOOP
        EXECUTE format('ALTER TABLE questions DROP CONSTRAINT %I', existing_constraint);
    END LOOP;
END $$;

ALTER TABLE questions
    ADD CONSTRAINT chk_questions_category
    CHECK (question_category IN ('Technical', 'Functional', 'Compliance', 'General', 'Safety'));
