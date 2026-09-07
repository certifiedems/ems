-- src/main/resources/db/migration/V30__align_question_json_columns_with_entity.sql
-- Bring questions.options_json / correct_options_json in line with the entity.
--
-- Question maps both as plain String, but V1 declared them JSONB. That costs
-- two separate failures:
--
--   1. Startup. Hibernate's schema validation resolves jsonb to Types#JSON via
--      PostgreSQLDialect while a String field expects Types#VARCHAR, so the
--      EntityManagerFactory cannot be built and the application never boots
--      against a migrated database.
--   2. Writes. questionRepository.save() binds the field with setString, and
--      PostgreSQL will not coerce character varying to jsonb in an INSERT, so
--      admin question create/update would fail even if startup were forced
--      through with validation disabled.
--
-- text is what the code has always treated these as: nothing reads them with
-- JSON operators, the payload is parsed in Java, and no view or query casts
-- them. The USING clause renders the existing jsonb to its text form, which is
-- valid JSON, so stored data survives the change intact.
ALTER TABLE questions
    ALTER COLUMN options_json TYPE TEXT USING options_json::text,
    ALTER COLUMN correct_options_json TYPE TEXT USING correct_options_json::text;
