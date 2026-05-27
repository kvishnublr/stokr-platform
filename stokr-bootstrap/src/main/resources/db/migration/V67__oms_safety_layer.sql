-- P3 OMS safety layer: dedupe keys, broker telemetry, kill-switch audit, blocked orders

CREATE TABLE IF NOT EXISTS oms_execution_dedupe_keys (
    id              BIGSERIAL PRIMARY KEY,
    execution_key   VARCHAR(512) NOT NULL,
    strategy_name   VARCHAR(128) NOT NULL,
    symbol          VARCHAR(64) NOT NULL,
    direction       VARCHAR(8) NOT NULL,
    session_date    DATE NOT NULL,
    order_id        UUID,
    user_id         UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_oms_execution_dedupe_key UNIQUE (execution_key)
);

CREATE INDEX IF NOT EXISTS idx_oms_execution_dedupe_session
    ON oms_execution_dedupe_keys (session_date DESC, strategy_name);

CREATE TABLE IF NOT EXISTS broker_execution_telemetry (
    id                  BIGSERIAL PRIMARY KEY,
    order_id            UUID NOT NULL,
    user_id             UUID,
    strategy_name       VARCHAR(128),
    symbol              VARCHAR(64),
    execution_mode      VARCHAR(16),
    submit_time         TIMESTAMPTZ,
    ack_time            TIMESTAMPTZ,
    ack_latency_ms      BIGINT,
    fill_time           TIMESTAMPTZ,
    fill_latency_ms     BIGINT,
    cancel_time         TIMESTAMPTZ,
    cancel_latency_ms   BIGINT,
    rejection_reason    VARCHAR(512),
    partial_fill_count  INT NOT NULL DEFAULT 0,
    broker_order_id     VARCHAR(128),
    broker_vendor       VARCHAR(32),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_broker_execution_telemetry_order
    ON broker_execution_telemetry (order_id);

CREATE TABLE IF NOT EXISTS trading_kill_switch_events (
    id              BIGSERIAL PRIMARY KEY,
    active          BOOLEAN NOT NULL,
    trigger_source  VARCHAR(64) NOT NULL,
    reason          VARCHAR(512),
    flatten_requested BOOLEAN NOT NULL DEFAULT FALSE,
    actor           VARCHAR(128),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_trading_kill_switch_events_created
    ON trading_kill_switch_events (created_at DESC);

CREATE TABLE IF NOT EXISTS oms_safety_blocked_orders (
    id              BIGSERIAL PRIMARY KEY,
    user_id         UUID,
    signal_id       UUID,
    strategy_name   VARCHAR(128),
    symbol          VARCHAR(64),
    requested_mode  VARCHAR(16),
    effective_mode  VARCHAR(16),
    block_code      VARCHAR(64) NOT NULL,
    block_message   VARCHAR(512),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_oms_safety_blocked_orders_created
    ON oms_safety_blocked_orders (created_at DESC);
