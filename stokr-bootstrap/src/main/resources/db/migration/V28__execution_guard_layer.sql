-- Execution guard persistence (entry/exit validation, rejections, post-fill quality).

CREATE TABLE IF NOT EXISTS execution_guard_events (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    user_id UUID NOT NULL,
    order_id UUID,
    signal_id UUID,
    strategy_key VARCHAR(128),
    symbol VARCHAR(64),
    side VARCHAR(8),
    guard_mode VARCHAR(32),
    outcome VARCHAR(16),
    primary_code VARCHAR(64),
    message VARCHAR(512),
    signal_generated_at TIMESTAMPTZ,
    validation_time TIMESTAMPTZ,
    signal_age_ms BIGINT,
    ttl_ms BIGINT,
    signal_price NUMERIC(24,8),
    current_ltp NUMERIC(24,8),
    drift_pct NUMERIC(12,6),
    allowed_drift_pct NUMERIC(12,6),
    last_tick_at TIMESTAMPTZ,
    last_heartbeat_at TIMESTAMPTZ,
    stale_duration_ms BIGINT,
    validation_latency_ms BIGINT
);

CREATE INDEX IF NOT EXISTS idx_execution_guard_events_user_created
    ON execution_guard_events (user_id, created_at DESC)
    WHERE deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_execution_guard_events_outcome
    ON execution_guard_events (outcome, created_at DESC)
    WHERE deleted = FALSE;

CREATE TABLE IF NOT EXISTS execution_quality_metrics (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    user_id UUID NOT NULL,
    order_id UUID NOT NULL,
    signal_id UUID,
    symbol VARCHAR(64),
    strategy_key VARCHAR(128),
    expected_price NUMERIC(24,8),
    actual_fill_price NUMERIC(24,8),
    realized_slippage_pct NUMERIC(12,6),
    fill_latency_ms BIGINT,
    spread_at_execution_pct NUMERIC(12,6),
    volatility_at_execution_pct NUMERIC(12,6),
    captured_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_execution_quality_metrics_order
    ON execution_quality_metrics (order_id)
    WHERE deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_execution_quality_metrics_user_created
    ON execution_quality_metrics (user_id, created_at DESC)
    WHERE deleted = FALSE;

