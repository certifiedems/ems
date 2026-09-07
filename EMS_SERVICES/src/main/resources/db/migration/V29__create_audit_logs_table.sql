-- src/main/resources/db/migration/V29__create_audit_logs_table.sql
-- Admin-facing audit trail: who did what, to whom, and whether it succeeded.
-- Purpose: give support/admin a record to answer "who reset this password" /
-- "who locked this account" / "did this candidate's exam actually get submitted".

-- V1 already created an audit_logs table with a completely different shape
-- (event_timestamp/performed_by/created_by) that no entity or query ever used.
-- CREATE TABLE IF NOT EXISTS would silently keep that table and the
-- occurred_at index below would then fail on a column that does not exist, so
-- the name has to be cleared first. A legacy table that somehow holds rows is
-- set aside rather than dropped; its indexes and identity sequence are renamed
-- with it so the names below are free.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'audit_logs'
          AND column_name = 'event_timestamp'
    ) THEN
        IF EXISTS (SELECT 1 FROM audit_logs) THEN
            ALTER TABLE audit_logs RENAME TO audit_logs_v1_legacy;
            ALTER INDEX IF EXISTS audit_logs_pkey RENAME TO audit_logs_v1_legacy_pkey;
            ALTER INDEX IF EXISTS idx_audit_logs_event_time
                RENAME TO idx_audit_logs_v1_legacy_event_time;
            ALTER SEQUENCE IF EXISTS audit_logs_id_seq
                RENAME TO audit_logs_v1_legacy_id_seq;
        ELSE
            DROP TABLE audit_logs;
        END IF;
    END IF;
END $$;

-- Deliberately has no foreign keys to users: a failed login names an email
-- that may never have belonged to an account, and a row must outlive the
-- user it is about if that user is ever deleted.
CREATE TABLE IF NOT EXISTS audit_logs (
    id              BIGSERIAL PRIMARY KEY,
    event_type      VARCHAR(40)   NOT NULL,
    outcome         VARCHAR(20)   NOT NULL,
    actor_email     VARCHAR(255),
    actor_user_id   VARCHAR(50),
    target_user_id  VARCHAR(50),
    target_type     VARCHAR(40),
    target_id       VARCHAR(100),
    description     VARCHAR(1000),
    ip_address      VARCHAR(64),
    correlation_id  VARCHAR(64),
    occurred_at     TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- The console's default view: everything, newest first.
CREATE INDEX IF NOT EXISTS idx_audit_logs_occurred_at ON audit_logs(occurred_at DESC);

-- Filtering by event type ("show me every LOGIN_FAILURE") and by whose
-- record an event is about ("everything that happened to user X").
CREATE INDEX IF NOT EXISTS idx_audit_logs_event_type ON audit_logs(event_type);
CREATE INDEX IF NOT EXISTS idx_audit_logs_target_user_id ON audit_logs(target_user_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_actor_email ON audit_logs(actor_email);

COMMENT ON TABLE audit_logs IS
'Admin-facing audit trail of security- and admin-relevant events (auth, payments, exam lifecycle, admin actions on users/certificates).';
