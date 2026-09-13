-- src/main/resources/db/migration/V34__create_proctoring_policies.sql
-- Proctoring rules an administrator controls.
--
-- Which detections count, how many strikes end an attempt and how loud a sound
-- has to be were all constants in code, so changing any of them took a release.
-- They live here instead, edited from the admin console.
--
-- One row per scope. scope_key 'DEFAULT' is the platform-wide policy; 'EXAM:<id>'
-- is an exam's own, and replaces the default outright for attempts at that exam.
-- No row at all is not an error: the application falls back to the built-in
-- rules, which are exactly the ones it enforced before this table existed, so
-- this migration changes nothing until an administrator saves.
--
-- Uniqueness sits on scope_key rather than on exam_ref alone: a UNIQUE column
-- admits any number of NULLs, and the default policy is the row whose exam_ref
-- is NULL.
CREATE TABLE IF NOT EXISTS proctoring_policies (
    id                                    BIGSERIAL PRIMARY KEY,
    scope_key                             VARCHAR(40)  NOT NULL,
    exam_ref                              BIGINT,
    strike_limit                          INT          NOT NULL,
    unidentified_sound_grace              INT          NOT NULL,
    voice_min_db_above_floor              INT          NOT NULL,
    background_noise_min_db_above_floor   INT          NOT NULL,
    unidentified_sound_min_db_above_floor INT          NOT NULL,
    version                               BIGINT       NOT NULL DEFAULT 0,
    created_by                            VARCHAR(100) NOT NULL,
    created_date                          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by                            VARCHAR(100),
    updated_date                          TIMESTAMPTZ,
    CONSTRAINT uq_proctoring_policies_scope_key UNIQUE (scope_key),
    CONSTRAINT uq_proctoring_policies_exam_ref UNIQUE (exam_ref),
    -- An exam's rules go with the exam.
    CONSTRAINT fk_proctoring_policies_exam
        FOREIGN KEY (exam_ref) REFERENCES exams (id) ON DELETE CASCADE,
    CONSTRAINT chk_proctoring_policies_scope CHECK (
        (scope_key = 'DEFAULT' AND exam_ref IS NULL)
        OR (scope_key <> 'DEFAULT' AND exam_ref IS NOT NULL)),
    -- The same bounds ProctoringPolicyRequest validates.
    CONSTRAINT chk_proctoring_policies_strike_limit CHECK (strike_limit BETWEEN 1 AND 20),
    CONSTRAINT chk_proctoring_policies_sound_grace CHECK (unidentified_sound_grace BETWEEN 0 AND 20),
    CONSTRAINT chk_proctoring_policies_voice_db CHECK (voice_min_db_above_floor BETWEEN 0 AND 60),
    CONSTRAINT chk_proctoring_policies_noise_db CHECK (background_noise_min_db_above_floor BETWEEN 0 AND 60),
    CONSTRAINT chk_proctoring_policies_sound_db CHECK (unidentified_sound_min_db_above_floor BETWEEN 0 AND 60)
);

-- How each violation type is enforced under a policy. A type with no row keeps
-- its built-in enforcement.
CREATE TABLE IF NOT EXISTS proctoring_policy_rules (
    policy_ref     BIGINT      NOT NULL,
    violation_type VARCHAR(80) NOT NULL,
    enforcement    VARCHAR(20) NOT NULL,
    CONSTRAINT pk_proctoring_policy_rules PRIMARY KEY (policy_ref, violation_type),
    CONSTRAINT fk_proctoring_policy_rules_policy
        FOREIGN KEY (policy_ref) REFERENCES proctoring_policies (id) ON DELETE CASCADE,
    CONSTRAINT chk_proctoring_policy_rules_enforcement
        CHECK (enforcement IN ('DISABLED', 'RECORD_ONLY', 'STRIKE'))
);

COMMENT ON TABLE proctoring_policies IS
'Admin-controlled proctoring rules: the DEFAULT policy and per-exam overrides. No row means the built-in rules apply.';

-- The rules each attempt is judged by, captured as it starts. A change made while
-- candidates are sitting an exam reaches the attempts that start after it and
-- never one already under way. NULL on attempts begun before this column
-- existed; those follow the exam's current rules.
ALTER TABLE exam_sessions
    ADD COLUMN IF NOT EXISTS proctoring_policy_json TEXT;
