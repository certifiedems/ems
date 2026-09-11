-- ems_backend/src/main/resources/db/migration/V31__ensure_seeded_user_roles.sql
--
-- V1 seeds the roles table, but the user_roles join rows only ever came from
-- db/postgres/data-postgresql.sql, which no profile wires into Spring
-- (dev/prod run Flyway on classpath:db/migration only). A database whose users
-- were created without that script - or with only part of it applied - ends up
-- with a seeded admin that authenticates but carries no authorities, so every
-- @PreAuthorize check and the roles claim in the access token come back empty.
--
-- Re-assert the two seeded accounts' roles here so they travel with the
-- migrations. Both statements are guarded by user_id and idempotent: they touch
-- nothing on a database that already has the rows, and nothing at all on one
-- where the seeded accounts were never created.

INSERT INTO user_roles (user_id, role_id, created_by, created_date, updated_by, updated_date)
SELECT u.id, r.id, 'SYSTEM', CURRENT_TIMESTAMP, 'SYSTEM', CURRENT_TIMESTAMP
FROM users u
JOIN roles r ON r.name = 'USER'
WHERE u.user_id IN ('admin-001', 'user-001')
ON CONFLICT (user_id, role_id) DO NOTHING;

INSERT INTO user_roles (user_id, role_id, created_by, created_date, updated_by, updated_date)
SELECT u.id, r.id, 'SYSTEM', CURRENT_TIMESTAMP, 'SYSTEM', CURRENT_TIMESTAMP
FROM users u
JOIN roles r ON r.name = 'ADMIN'
WHERE u.user_id = 'admin-001'
ON CONFLICT (user_id, role_id) DO NOTHING;
