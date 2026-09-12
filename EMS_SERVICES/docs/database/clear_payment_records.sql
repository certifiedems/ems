-- =============================================================================
-- Clear specific payment records -- PostgreSQL
-- =============================================================================
--
-- Run by hand (psql / pgAdmin) against the EMS database. This file lives under
-- docs/, not db/migration, so Flyway never runs it. Needs migration V33 (the
-- gateway_mode and payment_method columns).
--
-- HOW TO USE
--   1. In STEP 1 keep exactly ONE "WHERE" option and fill in its values.
--   2. Run the whole script. It ends in ROLLBACK, so nothing is saved.
--   3. Read the STEP 3 preview and the row counts psql prints for each step.
--   4. Change the last line from ROLLBACK to COMMIT and run it again.
--
-- WHAT STEP 2 PROTECTS YOU FROM
--   * PENDING / FAILED attempts younger than 2 days are skipped. The candidate
--     may still be in Razorpay checkout, and Razorpay retries webhooks for about
--     a day. Delete that row and a payment captured afterwards has nowhere to
--     land: money taken, nothing recorded, candidate locked out.
--   * LIVE payments that SUCCEEDED or were REFUNDED are skipped. They are real
--     revenue and real refunds -- what Razorpay settlements and tax records are
--     reconciled against. Rows whose live/test mode is still unknown (NULL) are
--     treated as live; use "Check with gateway" on them in the admin Payments
--     screen first if you believe they were test payments.
--   Only remove a STEP 2 statement if you are certain.
--
-- WHAT ELSE HAPPENS
--   * Deleting a SUCCESS payment does not un-pay its application by itself:
--     certification_applications.payment_status would still read SUCCESS and
--     the candidate could still book the exam. STEP 4 puts such applications
--     back to "applied, awaiting payment" -- but only if no exam session was
--     ever started on them and no other successful payment covers them.
--     Applications that were already sat keep their history untouched.
--   * No table has a foreign key to payments, so nothing else is deleted.
--   * audit_logs rows about these payments (target_type = 'PAYMENT',
--     target_id = transaction_id) are kept on purpose: they are the record that
--     the payment existed.
-- =============================================================================

BEGIN;

-- -----------------------------------------------------------------------------
-- STEP 1: choose the payments. Keep ONE option below; leave the rest commented.
-- -----------------------------------------------------------------------------
CREATE TEMP TABLE payments_to_clear ON COMMIT DROP AS
SELECT p.id
FROM payments p
JOIN users u ON u.id = p.user_ref
WHERE
    -- Option A: specific transactions (the "Transaction" column in the admin screen)
    p.transaction_id IN ('REPLACE_WITH_TRANSACTION_ID_1', 'REPLACE_WITH_TRANSACTION_ID_2')

    -- Option B: every test and simulated payment
    -- p.gateway_mode IN ('TEST', 'SIMULATED')

    -- Option C: abandoned attempts (STEP 2 keeps anything newer than 2 days)
    -- p.payment_status IN ('PENDING', 'FAILED')

    -- Option D: every payment of one candidate, by user ID or email
    -- (u.user_id = 'REPLACE_WITH_USER_ID' OR lower(u.email) = lower('REPLACE_WITH_EMAIL'))

    -- Option E: payments opened in a date range (end is exclusive)
    -- p.created_date >= TIMESTAMP '2026-09-01 00:00:00' AND p.created_date < TIMESTAMP '2026-09-08 00:00:00'
;

-- -----------------------------------------------------------------------------
-- STEP 2: safety nets
-- -----------------------------------------------------------------------------

-- Attempts that may still be in progress.
DELETE FROM payments_to_clear c
USING payments p
WHERE p.id = c.id
  AND p.payment_status IN ('PENDING', 'FAILED')
  AND p.created_date > CURRENT_TIMESTAMP - INTERVAL '2 days';

-- Real money, settled or refunded. Unknown mode counts as live.
DELETE FROM payments_to_clear c
USING payments p
WHERE p.id = c.id
  AND p.payment_status IN ('SUCCESS', 'REFUNDED')
  AND (p.gateway_mode = 'LIVE' OR p.gateway_mode IS NULL);

-- -----------------------------------------------------------------------------
-- STEP 3: preview -- exactly the rows that will be deleted
-- -----------------------------------------------------------------------------
SELECT
    p.transaction_id,
    p.payment_status,
    p.gateway_mode,
    p.payment_method,
    p.amount,
    p.currency,
    p.provider_order_id,
    p.provider_reference,
    p.created_date,
    p.payment_date,
    u.user_id,
    u.email,
    ca.id                  AS application_id,
    ca.application_status,
    ca.payment_status      AS application_payment_status,
    EXISTS (SELECT 1 FROM exam_sessions es WHERE es.application_ref = ca.id) AS exam_started
FROM payments_to_clear c
JOIN payments p ON p.id = c.id
JOIN users u ON u.id = p.user_ref
LEFT JOIN certification_applications ca ON ca.id = p.certification_application_ref
ORDER BY p.created_date DESC;

-- -----------------------------------------------------------------------------
-- STEP 4: reopen applications paid only by deleted payments and never used
-- -----------------------------------------------------------------------------
UPDATE certification_applications ca
SET payment_status      = 'PENDING',
    application_status  = 'APPLIED',
    scheduled_exam_time = NULL,
    updated_by          = 'MANUAL_PAYMENT_CLEANUP',
    updated_date        = CURRENT_TIMESTAMP
WHERE ca.id IN (
        SELECT p.certification_application_ref
        FROM payments_to_clear c
        JOIN payments p ON p.id = c.id
        WHERE p.payment_status = 'SUCCESS'
    )
  AND ca.payment_status = 'SUCCESS'
  AND ca.application_status IN ('APPLIED', 'IN_PROGRESS')
  AND NOT EXISTS (SELECT 1 FROM exam_sessions es WHERE es.application_ref = ca.id)
  AND NOT EXISTS (
        SELECT 1
        FROM payments other
        WHERE other.certification_application_ref = ca.id
          AND other.payment_status = 'SUCCESS'
          AND other.id NOT IN (SELECT id FROM payments_to_clear)
    );

-- -----------------------------------------------------------------------------
-- STEP 5: delete
-- -----------------------------------------------------------------------------
DELETE FROM payments p
USING payments_to_clear c
WHERE p.id = c.id;

ROLLBACK;   -- <-- change to COMMIT once the preview and row counts look right
