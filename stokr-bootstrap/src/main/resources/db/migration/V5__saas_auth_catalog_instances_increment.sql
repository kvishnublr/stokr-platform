-- Incremental SaaS hardening: auth lockout + password reset tokens,
-- strategy catalog metadata, strategy instance runtime configuration.
-- Extends existing tables only; adds one ancillary token table.

-- ----- Auth users -----
ALTER TABLE auth_users
    ADD COLUMN IF NOT EXISTS failed_login_attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE auth_users
    ADD COLUMN IF NOT EXISTS locked_until TIMESTAMPTZ;

ALTER TABLE auth_users
    ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT FALSE;

-- ----- Password reset (single-table token store; rotate by INSERT, invalidate by used=TRUE) -----
CREATE TABLE IF NOT EXISTS auth_password_reset_tokens (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    user_id UUID NOT NULL REFERENCES auth_users (id),
    token_hash VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_auth_pw_reset_user ON auth_password_reset_tokens (user_id);
CREATE INDEX IF NOT EXISTS idx_auth_pw_reset_expires ON auth_password_reset_tokens (expires_at);

-- ----- Strategy catalog metadata -----
ALTER TABLE strategy_definitions
    ADD COLUMN IF NOT EXISTS category VARCHAR(64);

ALTER TABLE strategy_definitions
    ADD COLUMN IF NOT EXISTS tags_json TEXT;

ALTER TABLE strategy_definitions
    ADD COLUMN IF NOT EXISTS icon_key VARCHAR(64);

ALTER TABLE strategy_definitions
    ADD COLUMN IF NOT EXISTS min_capital NUMERIC(24, 8);

ALTER TABLE strategy_definitions
    ADD COLUMN IF NOT EXISTS popularity_score NUMERIC(12, 4);

ALTER TABLE strategy_definitions
    ADD COLUMN IF NOT EXISTS win_rate NUMERIC(12, 6);

ALTER TABLE strategy_definitions
    ADD COLUMN IF NOT EXISTS avg_monthly_return NUMERIC(12, 6);

ALTER TABLE strategy_definitions
    ADD COLUMN IF NOT EXISTS announcement_banner VARCHAR(500);

UPDATE strategy_definitions
SET category = COALESCE(category, 'MEAN_REVERSION'),
    popularity_score = COALESCE(popularity_score, 72.5),
    win_rate = COALESCE(win_rate, 58.25),
    avg_monthly_return = COALESCE(avg_monthly_return, 2.15),
    tags_json = COALESCE(tags_json, '["indices","futures","intraday"]'),
    icon_key = COALESCE(icon_key, 'sparkles')
WHERE strategy_key = 'MEAN_REVERSION_RANGE_FADE';

-- ----- Strategy instances runtime -----
ALTER TABLE strategy_instances
    ADD COLUMN IF NOT EXISTS execution_mode VARCHAR(16) NOT NULL DEFAULT 'SIMULATED';

ALTER TABLE strategy_instances
    ADD COLUMN IF NOT EXISTS allocation_amount NUMERIC(24, 8);

ALTER TABLE strategy_instances
    ADD COLUMN IF NOT EXISTS risk_multiplier NUMERIC(24, 8) DEFAULT 1;

ALTER TABLE strategy_instances
    ADD COLUMN IF NOT EXISTS max_daily_loss NUMERIC(24, 8);

ALTER TABLE strategy_instances
    ADD COLUMN IF NOT EXISTS started_at TIMESTAMPTZ;

ALTER TABLE strategy_instances
    ADD COLUMN IF NOT EXISTS stopped_at TIMESTAMPTZ;

ALTER TABLE strategy_instances
    ADD COLUMN IF NOT EXISTS runtime_state VARCHAR(32) NOT NULL DEFAULT 'STOPPED';
