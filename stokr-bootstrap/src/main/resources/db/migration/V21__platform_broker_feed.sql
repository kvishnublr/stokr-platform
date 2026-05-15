-- Platform-owned market feed sessions (separate from trader broker_accounts / execution OAuth).

CREATE TABLE IF NOT EXISTS platform_broker_feed_sessions (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    vendor_code VARCHAR(32) NOT NULL,
    connection_state VARCHAR(32) NOT NULL,
    websocket_state VARCHAR(32) NOT NULL DEFAULT 'CLOSED',
    access_token_enc TEXT,
    refresh_token_enc TEXT,
    token_expires_at TIMESTAMPTZ,
    last_tick_at TIMESTAMPTZ,
    last_sync_at TIMESTAMPTZ,
    reconnect_count INT NOT NULL DEFAULT 0,
    subscription_count INT NOT NULL DEFAULT 0,
    packets_per_sec DOUBLE PRECISION,
    ticks_per_sec DOUBLE PRECISION,
    feed_lag_ms INT,
    ingestion_paused BOOLEAN NOT NULL DEFAULT FALSE,
    instrument_sync_state VARCHAR(64),
    telemetry_json TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_platform_broker_vendor_live
    ON platform_broker_feed_sessions (lower(vendor_code))
    WHERE deleted = FALSE;

CREATE TABLE IF NOT EXISTS platform_broker_oauth_states (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    vendor_code VARCHAR(32) NOT NULL,
    state_token VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_platform_oauth_active
    ON platform_broker_oauth_states (state_token)
    WHERE deleted = FALSE AND consumed = FALSE;
