-- src/main/resources/db/migration/V36__add_user_last_login.sql
-- When each user last signed in, shown on the admin user list.
--
-- Stamped on a successful password login only; refreshing an access token is
-- not a sign-in. NULL means no login has been recorded for the account.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMPTZ;

-- Every successful login since V29 is already in the audit trail, so existing
-- accounts start from their latest one rather than from nothing.
UPDATE users u
SET last_login_at = logins.last_login_at
FROM (
    SELECT target_user_id, MAX(occurred_at) AS last_login_at
    FROM audit_logs
    WHERE event_type = 'LOGIN_SUCCESS'
    GROUP BY target_user_id
) logins
WHERE logins.target_user_id = u.user_id
  AND u.last_login_at IS NULL;
