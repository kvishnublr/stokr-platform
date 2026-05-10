-- Institutional-grade replay journal, checkpoints, risk traces, strategy persistence & orchestration hooks.
-- Append-only semantics enforced in application layer (no UPDATE/DELETE on event_store).

-- Per-stream monotonic sequence allocation (transactionally incremented).
CREATE TABLE IF NOT EXISTS event_stream_counters (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    stream_type VARCHAR(32) NOT NULL,
    stream_key VARCHAR(256) NOT NULL,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ux_event_stream_counters UNIQUE (stream_type, stream_key)
);

CREATE INDEX IF NOT EXISTS idx_event_stream_counters_lookup ON event_stream_counters (stream_type, stream_key);

CREATE TABLE IF NOT EXISTS event_store (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    stream_type VARCHAR(32) NOT NULL,
    stream_key VARCHAR(256) NOT NULL,
    sequence_num BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    payload_hash VARCHAR(128) NOT NULL,
    chain_hash VARCHAR(128) NOT NULL,
    prev_chain_hash VARCHAR(128),
    correlation_id VARCHAR(64),
    user_id UUID,
    symbol VARCHAR(64),
    strategy_key VARCHAR(128),
    backtest_run_id UUID,
    CONSTRAINT ux_event_store_stream_seq UNIQUE (stream_type, stream_key, sequence_num)
);

CREATE INDEX IF NOT EXISTS idx_event_store_run ON event_store (backtest_run_id, sequence_num);
CREATE INDEX IF NOT EXISTS idx_event_store_user ON event_store (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_event_store_symbol ON event_store (symbol, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_event_store_type ON event_store (event_type, created_at DESC);

CREATE TABLE IF NOT EXISTS replay_checkpoints (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    stream_type VARCHAR(32) NOT NULL,
    stream_key VARCHAR(256) NOT NULL,
    last_sequence BIGINT NOT NULL,
    checkpoint_hash VARCHAR(128) NOT NULL,
    backtest_run_id UUID,
    user_id UUID,
    CONSTRAINT ux_replay_checkpoint_stream UNIQUE (stream_type, stream_key)
);

CREATE INDEX IF NOT EXISTS idx_replay_checkpoint_run ON replay_checkpoints (backtest_run_id);

-- Full synchronous risk evaluations (allows + rejects) for audit / replay debugging.
CREATE TABLE IF NOT EXISTS risk_evaluation_traces (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    user_id UUID NOT NULL,
    order_id UUID,
    allowed BOOLEAN NOT NULL,
    reason_code VARCHAR(64),
    message VARCHAR(1000),
    trace_json TEXT NOT NULL,
    correlation_id VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_risk_trace_user ON risk_evaluation_traces (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_risk_trace_order ON risk_evaluation_traces (order_id);

-- Strategy recoverable state (indicator memory, checkpoints — opaque JSON).
CREATE TABLE IF NOT EXISTS strategy_state_snapshots (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    instance_id UUID NOT NULL REFERENCES strategy_instances (id),
    sequence_num BIGINT NOT NULL,
    state_json TEXT NOT NULL,
    indicator_json TEXT,
    replay_checkpoint VARCHAR(128),
    CONSTRAINT ux_strategy_snapshot_instance_seq UNIQUE (instance_id, sequence_num)
);

CREATE INDEX IF NOT EXISTS idx_strategy_snapshot_instance ON strategy_state_snapshots (instance_id, sequence_num DESC);

ALTER TABLE strategy_instances
    ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMPTZ;

ALTER TABLE strategy_instances
    ADD COLUMN IF NOT EXISTS orchestration_state VARCHAR(32) NOT NULL DEFAULT 'MANAGED';

-- Strong idempotency: one OMS order per signal per user (nullable signal_id excluded).
CREATE UNIQUE INDEX IF NOT EXISTS ux_oms_orders_user_signal_live
    ON oms_orders (user_id, signal_id)
    WHERE signal_id IS NOT NULL AND deleted = FALSE;
