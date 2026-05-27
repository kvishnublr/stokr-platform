-- P1 strategy exit lifecycle telemetry — structured exit audit trail

CREATE TABLE IF NOT EXISTS strategy_exit_telemetry (
    id                      BIGSERIAL PRIMARY KEY,
    signal_id               UUID,
    strategy_name           VARCHAR(128) NOT NULL,
    symbol                  VARCHAR(64) NOT NULL,
    entry_time              TIMESTAMPTZ NOT NULL,
    exit_time               TIMESTAMPTZ NOT NULL,
    hold_seconds            BIGINT NOT NULL,
    exit_category           VARCHAR(32) NOT NULL,
    exit_reason             VARCHAR(512) NOT NULL,
    unrealized_pnl_peak     NUMERIC(24, 8),
    unrealized_pnl_trough   NUMERIC(24, 8),
    pressure_score_at_exit  NUMERIC(12, 6),
    min_hold_bypassed       BOOLEAN NOT NULL DEFAULT FALSE,
    pressure_trigger        VARCHAR(64),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_strategy_exit_telemetry_session
    ON strategy_exit_telemetry (exit_time DESC, strategy_name);

CREATE INDEX IF NOT EXISTS idx_strategy_exit_telemetry_signal
    ON strategy_exit_telemetry (signal_id);
