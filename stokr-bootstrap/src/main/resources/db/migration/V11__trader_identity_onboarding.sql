-- SaaS trader lifecycle: canonical ROLE_TRADER, contact channels, verification flags, live-trading gate.

-- Align legacy ROLE_USER memberships → ROLE_TRADER for non-admin users (ROLE_ADMIN unchanged).
UPDATE auth_user_roles
SET role_id = '11111111-1111-1111-1111-111111111102'
WHERE role_id = '11111111-1111-1111-1111-111111111103'
  AND user_id NOT IN (
      SELECT ur.user_id
      FROM auth_user_roles ur
      WHERE ur.role_id = '11111111-1111-1111-1111-111111111101'
  );

ALTER TABLE auth_users
    ADD COLUMN IF NOT EXISTS mobile_phone VARCHAR(32);

ALTER TABLE auth_users
    ADD COLUMN IF NOT EXISTS telegram_username VARCHAR(64);

ALTER TABLE auth_users
    ADD COLUMN IF NOT EXISTS telegram_chat_id VARCHAR(64);

ALTER TABLE auth_users
    ADD COLUMN IF NOT EXISTS whatsapp_e164 VARCHAR(32);

ALTER TABLE auth_users
    ADD COLUMN IF NOT EXISTS telegram_verified BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE auth_users
    ADD COLUMN IF NOT EXISTS whatsapp_verified BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE auth_users
    ADD COLUMN IF NOT EXISTS onboarding_complete BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE auth_users
    ADD COLUMN IF NOT EXISTS live_trading_approved BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN auth_users.telegram_chat_id IS 'Set after user proves control via Telegram bot /deep-link binding.';
COMMENT ON COLUMN auth_users.onboarding_complete IS 'Email + required channels verified; unlocks full trading UX.';
COMMENT ON COLUMN auth_users.live_trading_approved IS 'Admin-approved live broker routing; paper/sim still allowed when false.';
