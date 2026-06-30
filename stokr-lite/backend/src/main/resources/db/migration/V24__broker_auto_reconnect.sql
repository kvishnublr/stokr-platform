-- Auto-reconnect credentials for Zerodha daily token refresh
ALTER TABLE broker_accounts
    ADD COLUMN IF NOT EXISTS zerodha_password    TEXT,
    ADD COLUMN IF NOT EXISTS zerodha_totp_secret TEXT,
    ADD COLUMN IF NOT EXISTS auto_reconnect      BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS last_auto_reconnect TIMESTAMPTZ;
