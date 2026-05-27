-- P2 strategy runtime health — continuous session observability

CREATE TABLE IF NOT EXISTS strategy_runtime_health (
    id                      BIGSERIAL PRIMARY KEY,
    strategy_name           VARCHAR(128) NOT NULL,
    session_date            DATE NOT NULL,
    execution_mode          VARCHAR(16) NOT NULL,
    scans_attempted         BIGINT NOT NULL DEFAULT 0,
    scans_blocked_integrity BIGINT NOT NULL DEFAULT 0,
    scans_blocked_feed      BIGINT NOT NULL DEFAULT 0,
    signals_generated       BIGINT NOT NULL DEFAULT 0,
    trades_opened           BIGINT NOT NULL DEFAULT 0,
    trades_closed           BIGINT NOT NULL DEFAULT 0,
    rejection_rate          NUMERIC(8, 4),
    avg_hold_seconds        BIGINT,
    last_scan_time          TIMESTAMPTZ,
    last_signal_time        TIMESTAMPTZ,
    last_rejection_reason   VARCHAR(256),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_strategy_runtime_health_session UNIQUE (strategy_name, session_date)
);

CREATE INDEX IF NOT EXISTS idx_strategy_runtime_health_session
    ON strategy_runtime_health (session_date DESC, strategy_name);
