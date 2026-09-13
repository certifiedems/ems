-- src/main/resources/db/migration/V35__add_exam_attempt_allowance.sql
-- Let one payment cover more than one sitting of a certification exam.
--
-- A failed or terminated attempt closed its application, and the only way back
-- was a new application and a second fee. An administrator now decides, per
-- certification level, how many attempts one payment buys. The first attempt is
-- the application the fee was paid against; each further attempt is a retake
-- application that points back at it and is never charged.
--
-- No policy row means one attempt per payment, which is exactly what happened
-- before this table existed, so this migration changes nothing for candidates
-- until an administrator saves.
CREATE TABLE IF NOT EXISTS certification_attempt_policies (
    id                   BIGSERIAL PRIMARY KEY,
    certification_level  VARCHAR(10)  NOT NULL,
    attempts_per_payment INT          NOT NULL,
    version              BIGINT       NOT NULL DEFAULT 0,
    created_by           VARCHAR(100) NOT NULL,
    created_date         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by           VARCHAR(100),
    updated_date         TIMESTAMPTZ,
    CONSTRAINT uq_certification_attempt_policies_level UNIQUE (certification_level),
    CONSTRAINT chk_certification_attempt_policies_level CHECK (certification_level IN ('L1', 'L2', 'L3')),
    -- The same bounds ExamAttemptPolicyRequest validates.
    CONSTRAINT chk_certification_attempt_policies_attempts CHECK (attempts_per_payment BETWEEN 1 AND 10)
);

COMMENT ON TABLE certification_attempt_policies IS
'Attempts one payment buys, per certification level. No row means one attempt.';

-- Which sitting on its payment an application is: 1 for the application the fee
-- was paid against -- which every existing application is -- and 2 upwards for
-- the retakes that payment covers.
ALTER TABLE certification_applications
    ADD COLUMN IF NOT EXISTS attempt_number INT NOT NULL DEFAULT 1;

-- How many sittings the payment covers. Fixed when the payment succeeds, from the
-- level's policy at that moment, and copied onto every retake: a later change by
-- an administrator reaches payments made after it, never one already made. NULL
-- until the application is paid.
ALTER TABLE certification_applications
    ADD COLUMN IF NOT EXISTS attempts_allowed INT;

-- The application whose payment covers this one; NULL on that application itself.
ALTER TABLE certification_applications
    ADD COLUMN IF NOT EXISTS paid_application_ref BIGINT;

ALTER TABLE certification_applications
    ADD CONSTRAINT fk_certification_applications_paid_application
    FOREIGN KEY (paid_application_ref) REFERENCES certification_applications (id) ON DELETE CASCADE;

ALTER TABLE certification_applications
    ADD CONSTRAINT chk_certification_applications_attempt_number
    CHECK (attempt_number >= 1 AND (attempts_allowed IS NULL OR attempt_number <= attempts_allowed));

-- One application per sitting on a payment. Two clicks on "start next attempt"
-- racing each other would otherwise both succeed and hand out a sitting nobody
-- paid for. The paid application's own row has a NULL reference, which a unique
-- index does not constrain.
CREATE UNIQUE INDEX IF NOT EXISTS uq_certification_applications_paid_attempt
    ON certification_applications (paid_application_ref, attempt_number);

-- Payments taken before attempts were configurable bought a single sitting, and
-- still do. Recorded explicitly so NULL goes back to meaning only "not paid yet".
UPDATE certification_applications
SET attempts_allowed = 1
WHERE attempts_allowed IS NULL
  AND payment_status IN ('SUCCESS', 'REFUNDED');
