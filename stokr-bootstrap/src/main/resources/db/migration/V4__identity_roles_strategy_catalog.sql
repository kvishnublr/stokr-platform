-- Identity: ROLE_USER, username/display_name/last_login, seeded admin, strategy catalog flags
-- Seeded admin password (change immediately in production): "Temp@12345678"
-- BCrypt strength 10, compatible with Spring BCryptPasswordEncoder.

-- Role: standard app user (self-registration only gets this role)
INSERT INTO auth_roles (id, created_at, updated_at, version, deleted, name)
SELECT '11111111-1111-1111-1111-111111111103', NOW(), NOW(), 0, FALSE, 'ROLE_USER'
WHERE NOT EXISTS (SELECT 1 FROM auth_roles WHERE name = 'ROLE_USER');

ALTER TABLE auth_users
    ADD COLUMN IF NOT EXISTS username VARCHAR(64),
    ADD COLUMN IF NOT EXISTS display_name VARCHAR(200),
    ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMPTZ;

-- Backfill username from email local-part for any existing rows
UPDATE auth_users
SET username = lower(regexp_replace(split_part(lower(email), '@', 1), '[^a-z0-9_]', '_', 'g'))
    || '_' || substring(replace(id::text, '-', '') from 1 for 8)
WHERE username IS NULL OR username = '';

CREATE UNIQUE INDEX IF NOT EXISTS ux_auth_users_username_active
    ON auth_users (lower(username))
    WHERE deleted = FALSE;

ALTER TABLE auth_users
    ALTER COLUMN username SET NOT NULL;

ALTER TABLE strategy_definitions
    ADD COLUMN IF NOT EXISTS display_name VARCHAR(200),
    ADD COLUMN IF NOT EXISTS enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS visible_to_users BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS risk_level VARCHAR(32) NOT NULL DEFAULT 'MEDIUM';

UPDATE strategy_definitions
SET display_name = COALESCE(display_name, initcap(replace(strategy_key, '_', ' '))),
    risk_level = COALESCE(NULLIF(risk_level, ''), 'MEDIUM')
WHERE strategy_key IS NOT NULL;

-- Seed admin user — default password documented in README (dev only).
INSERT INTO auth_users (id, created_at, updated_at, version, deleted, email, username, display_name,
                          password_hash, enabled, last_login_at)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
       NOW(),
       NOW(),
       0,
       FALSE,
       'admin@stokr.local',
       'admin',
       'Platform Admin',
       '$2a$10$iiZzRZfiKKxVsntI0OxZUuq7QSNRBkKg3G/Jjq9nAqc1HDy9dP/jm',
       TRUE,
       NULL
WHERE NOT EXISTS (SELECT 1 FROM auth_users WHERE lower(email) = lower('admin@stokr.local'));

INSERT INTO auth_user_roles (user_id, role_id)
SELECT 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', id
FROM auth_roles
WHERE name = 'ROLE_ADMIN'
  AND NOT EXISTS (
    SELECT 1 FROM auth_user_roles
    WHERE user_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
      AND role_id = auth_roles.id
);
