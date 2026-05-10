-- Telegram deep-link verification, WhatsApp OTP, notification delivery audit, broker lifecycle, OAuth CSRF state.

CREATE TABLE IF NOT EXISTS auth_telegram_verification_tokens (
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

CREATE INDEX IF NOT EXISTS idx_tg_verify_user ON auth_telegram_verification_tokens (user_id);
CREATE INDEX IF NOT EXISTS idx_tg_verify_expires ON auth_telegram_verification_tokens (expires_at);

CREATE TABLE IF NOT EXISTS auth_whatsapp_verification_tokens (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    user_id UUID NOT NULL REFERENCES auth_users (id),
    otp_hash VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed BOOLEAN NOT NULL DEFAULT FALSE,
    resend_count INTEGER NOT NULL DEFAULT 0,
    last_sent_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_wa_verify_user ON auth_whatsapp_verification_tokens (user_id);

CREATE TABLE IF NOT EXISTS notification_delivery_records (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    user_id UUID REFERENCES auth_users (id),
    channel VARCHAR(32) NOT NULL,
    template_key VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(500),
    payload_json TEXT,
    delivered_at TIMESTAMPTZ,
    correlation_id VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_notify_delivery_user ON notification_delivery_records (user_id);
CREATE INDEX IF NOT EXISTS idx_notify_delivery_status ON notification_delivery_records (status);

CREATE TABLE IF NOT EXISTS broker_oauth_states (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    user_id UUID NOT NULL REFERENCES auth_users (id),
    state_token VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_broker_oauth_state_token ON broker_oauth_states (state_token);

ALTER TABLE broker_accounts
    ADD COLUMN IF NOT EXISTS broker_user_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS access_token_enc TEXT,
    ADD COLUMN IF NOT EXISTS refresh_token_enc TEXT,
    ADD COLUMN IF NOT EXISTS token_expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_sync_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS margin_snapshot_json TEXT,
    ADD COLUMN IF NOT EXISTS health_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN';

COMMENT ON COLUMN broker_accounts.health_status IS 'UNKNOWN | HEALTHY | DEGRADED | DISCONNECTED';
