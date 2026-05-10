-- Email verification tokens (single-use, hashed), aligned with password reset pattern.

CREATE TABLE IF NOT EXISTS auth_email_verification_tokens (
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

CREATE INDEX IF NOT EXISTS idx_auth_email_verify_user ON auth_email_verification_tokens (user_id);
CREATE INDEX IF NOT EXISTS idx_auth_email_verify_expires ON auth_email_verification_tokens (expires_at);

-- Dev admin: fully onboarded for operational testing
UPDATE auth_users
SET email_verified = TRUE,
    telegram_verified = TRUE,
    whatsapp_verified = TRUE,
    onboarding_complete = TRUE,
    live_trading_approved = COALESCE(live_trading_approved, FALSE)
WHERE id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
