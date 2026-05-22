ALTER TABLE strategy_signals
    ADD COLUMN IF NOT EXISTS is_test_trade BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS test_run_id UUID,
    ADD COLUMN IF NOT EXISTS test_scenario VARCHAR(64);

ALTER TABLE oms_orders
    ADD COLUMN IF NOT EXISTS is_test_trade BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS test_run_id UUID;

CREATE INDEX IF NOT EXISTS idx_strategy_signals_test
    ON strategy_signals (is_test_trade, created_at DESC)
    WHERE deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_oms_orders_test
    ON oms_orders (is_test_trade, created_at DESC)
    WHERE deleted = FALSE;

CREATE TABLE IF NOT EXISTS admin_test_signal_runs (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    requested_by UUID NOT NULL,
    trader_user_id UUID NOT NULL,
    broker_account_id UUID,
    broker_vendor VARCHAR(32),
    strategy_key VARCHAR(128) NOT NULL,
    strategy_template VARCHAR(128),
    symbol VARCHAR(64) NOT NULL,
    side VARCHAR(8) NOT NULL,
    quantity NUMERIC(24,8) NOT NULL,
    product_type VARCHAR(32),
    order_type VARCHAR(32),
    exchange VARCHAR(16),
    requested_price NUMERIC(24,8),
    trigger_type VARCHAR(32),
    execution_mode VARCHAR(16) NOT NULL,
    force_quantity_one BOOLEAN NOT NULL DEFAULT TRUE,
    dry_run_only BOOLEAN NOT NULL DEFAULT TRUE,
    skip_actual_broker_execution BOOLEAN NOT NULL DEFAULT TRUE,
    simulate_rejection BOOLEAN NOT NULL DEFAULT FALSE,
    simulate_timeout BOOLEAN NOT NULL DEFAULT FALSE,
    simulate_stale_websocket BOOLEAN NOT NULL DEFAULT FALSE,
    simulate_margin_failure BOOLEAN NOT NULL DEFAULT FALSE,
    simulate_broker_disconnect BOOLEAN NOT NULL DEFAULT FALSE,
    auto_square_off_minutes INTEGER,
    status VARCHAR(32) NOT NULL,
    final_status VARCHAR(16),
    total_latency_ms BIGINT,
    signal_id UUID,
    order_id UUID,
    report_json TEXT,
    diagnostics_json TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_admin_test_signal_runs_created
    ON admin_test_signal_runs (created_at DESC)
    WHERE deleted = FALSE;
