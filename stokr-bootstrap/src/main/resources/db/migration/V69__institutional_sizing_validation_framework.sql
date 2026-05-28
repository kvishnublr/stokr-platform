-- V69: Institutional sizing, capital reservation, validation metrics, telemetry

-- ── Strategy validation status on catalog ─────────────────────────────────────
ALTER TABLE strategy_definitions
    ADD COLUMN IF NOT EXISTS validation_status VARCHAR(32) NOT NULL DEFAULT 'DRY_RUN',
    ADD COLUMN IF NOT EXISTS live_shadow_enabled BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE strategy_definitions SET validation_status = 'PAPER_VALIDATING'
WHERE strategy_key = 'GAP_FILL' AND deleted = FALSE;

UPDATE strategy_definitions SET validation_status = 'PAPER_VALIDATING'
WHERE strategy_key IN ('NSE_SPIKE_DETECTION', 'VWAP_BOUNCE') AND deleted = FALSE;

UPDATE strategy_definitions SET validation_status = 'DRY_RUN'
WHERE strategy_key IN ('EARLY_BREAKOUT', 'SECTOR_LAGGARD') AND deleted = FALSE;

-- Remove unsupported accuracy marketing from display names
UPDATE strategy_definitions SET display_name = 'Gap Fill'
WHERE strategy_key = 'GAP_FILL' AND display_name LIKE '%Accuracy%';

UPDATE strategy_definitions SET display_name = 'VWAP Bounce'
WHERE strategy_key = 'VWAP_BOUNCE' AND display_name LIKE '%Accuracy%';

UPDATE strategy_definitions SET display_name = 'Sector Laggard Catch-up'
WHERE strategy_key = 'SECTOR_LAGGARD' AND display_name LIKE '%Accuracy%';

UPDATE strategy_definitions SET display_name = 'Early Breakout'
WHERE strategy_key = 'EARLY_BREAKOUT' AND display_name LIKE '%Accuracy%';

-- ── Extended execution config (sizing modes) ──────────────────────────────────
ALTER TABLE strategy_execution_configs
    ADD COLUMN IF NOT EXISTS sizing_mode VARCHAR(32) NOT NULL DEFAULT 'FIXED_QUANTITY',
    ADD COLUMN IF NOT EXISTS max_capital_per_trade NUMERIC(24,8),
    ADD COLUMN IF NOT EXISTS max_risk_per_trade_pct NUMERIC(8,4),
    ADD COLUMN IF NOT EXISTS max_total_exposure NUMERIC(24,8),
    ADD COLUMN IF NOT EXISTS allow_fractional_capital_usage BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS capital_utilization_mode VARCHAR(32) NOT NULL DEFAULT 'FULLY_ALLOCATED';

-- GAP_FILL: CAPITAL_BUCKET pilot defaults (₹20k / 5 positions → ₹4k/trade)
UPDATE strategy_execution_configs SET
    sizing_mode = 'CAPITAL_BUCKET',
    force_fixed_qty = FALSE,
    allocated_capital = 20000,
    max_positions = 5,
    max_capital_per_trade = 4000,
    max_total_exposure = 20000,
    execution_mode = 'BOTH',
    live_enabled = TRUE,
    paper_enabled = TRUE,
    capital_utilization_mode = 'RESERVED_BUFFER'
WHERE strategy_key = 'GAP_FILL' AND user_id IS NULL AND deleted = FALSE;

INSERT INTO strategy_execution_configs (
    id, strategy_key, enabled, execution_mode, live_enabled, paper_enabled,
    sizing_mode, force_fixed_qty, fixed_qty, allocated_capital, max_positions,
    max_capital_per_trade, max_total_exposure, capital_utilization_mode, deleted
)
SELECT gen_random_uuid(), 'GAP_FILL', TRUE, 'BOTH', TRUE, TRUE,
       'CAPITAL_BUCKET', FALSE, 1, 20000, 5, 4000, 20000, 'RESERVED_BUFFER', FALSE
WHERE NOT EXISTS (
    SELECT 1 FROM strategy_execution_configs
    WHERE strategy_key = 'GAP_FILL' AND user_id IS NULL AND deleted = FALSE
);

-- NSE_SPIKE / VWAP_BOUNCE: PAPER validating only
-- Default execution configs for remaining catalog strategies
INSERT INTO strategy_execution_configs (
    id, strategy_key, enabled, execution_mode, live_enabled, paper_enabled,
    sizing_mode, force_fixed_qty, fixed_qty, max_positions, deleted
)
SELECT gen_random_uuid(), sk, TRUE, em, FALSE, TRUE,
       'FIXED_QUANTITY', TRUE, 1, mp, FALSE
FROM (VALUES
    ('NSE_SPIKE_DETECTION', 'PAPER', 5),
    ('EARLY_BREAKOUT', 'PAPER', 3),
    ('SECTOR_LAGGARD', 'PAPER', 3),
    ('VWAP_BOUNCE', 'PAPER', 3)
) AS t(sk, em, mp)
WHERE NOT EXISTS (
    SELECT 1 FROM strategy_execution_configs c
    WHERE c.strategy_key = t.sk AND c.user_id IS NULL AND c.deleted = FALSE
);

-- ── Capital reservations (locked at signal acceptance) ───────────────────────
CREATE TABLE IF NOT EXISTS strategy_capital_reservations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,

    strategy_key VARCHAR(128) NOT NULL,
    user_id UUID NOT NULL,
    signal_id UUID,
    order_id UUID,
    symbol VARCHAR(64),
    reserved_amount NUMERIC(24,8) NOT NULL,
    reserved_quantity NUMERIC(24,8) NOT NULL,
    entry_price NUMERIC(24,8),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    release_reason VARCHAR(256),
    sizing_snapshot_hash VARCHAR(64)
);

CREATE INDEX idx_scr_strategy_status ON strategy_capital_reservations (strategy_key, status)
    WHERE deleted = FALSE;
CREATE INDEX idx_scr_signal ON strategy_capital_reservations (signal_id)
    WHERE deleted = FALSE;
CREATE INDEX idx_scr_order ON strategy_capital_reservations (order_id)
    WHERE deleted = FALSE;

-- ── Position sizing telemetry ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS strategy_position_sizing_telemetry (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    strategy_name VARCHAR(128) NOT NULL,
    signal_id UUID,
    order_id UUID,
    symbol VARCHAR(64),
    sizing_mode VARCHAR(32),
    capital_allocated NUMERIC(24,8),
    capital_used NUMERIC(24,8),
    reserved_capital NUMERIC(24,8),
    quantity NUMERIC(24,8),
    normalized_quantity NUMERIC(24,8),
    entry_price NUMERIC(24,8),
    exposure_value NUMERIC(24,8),
    available_capital_before NUMERIC(24,8),
    available_capital_after NUMERIC(24,8),
    utilization_pct NUMERIC(8,4),
    rejected BOOLEAN NOT NULL DEFAULT FALSE,
    rejected_reason VARCHAR(512),
    sizing_snapshot_hash VARCHAR(64),
    execution_mode VARCHAR(16),
    broker_normalization_note VARCHAR(256)
);

CREATE INDEX idx_spst_strategy_created ON strategy_position_sizing_telemetry (strategy_name, created_at DESC);
CREATE INDEX idx_spst_signal ON strategy_position_sizing_telemetry (signal_id);

-- ── Validation metrics (per strategy per session) ───────────────────────────
CREATE TABLE IF NOT EXISTS strategy_validation_metrics (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    strategy_name VARCHAR(128) NOT NULL,
    session_date DATE NOT NULL,
    validation_status VARCHAR(32) NOT NULL,
    signals_generated BIGINT NOT NULL DEFAULT 0,
    targets_hit BIGINT NOT NULL DEFAULT 0,
    stop_losses_hit BIGINT NOT NULL DEFAULT 0,
    pressure_exits BIGINT NOT NULL DEFAULT 0,
    avg_hold_minutes NUMERIC(12,4),
    expectancy NUMERIC(12,6),
    avg_r_multiple NUMERIC(12,6),
    max_drawdown NUMERIC(24,8),
    paper_pnl NUMERIC(24,8),
    live_pnl NUMERIC(24,8),
    paper_live_drift NUMERIC(12,6),
    sample_size BIGINT NOT NULL DEFAULT 0,
    win_rate NUMERIC(8,4),
    stale_signal_rejections BIGINT NOT NULL DEFAULT 0,
    oms_reject_rate NUMERIC(8,4),
    sizing_rejections BIGINT NOT NULL DEFAULT 0,
    UNIQUE (strategy_name, session_date)
);

-- ── Extend execution comparison for full paper/live analytics ───────────────
ALTER TABLE execution_comparison_metrics
    ADD COLUMN IF NOT EXISTS direction VARCHAR(8),
    ADD COLUMN IF NOT EXISTS paper_entry_price NUMERIC(24,8),
    ADD COLUMN IF NOT EXISTS live_entry_price NUMERIC(24,8),
    ADD COLUMN IF NOT EXISTS paper_exit_price NUMERIC(24,8),
    ADD COLUMN IF NOT EXISTS live_exit_price NUMERIC(24,8),
    ADD COLUMN IF NOT EXISTS paper_pnl NUMERIC(24,8),
    ADD COLUMN IF NOT EXISTS live_pnl NUMERIC(24,8),
    ADD COLUMN IF NOT EXISTS slippage_entry NUMERIC(12,6),
    ADD COLUMN IF NOT EXISTS slippage_exit NUMERIC(12,6),
    ADD COLUMN IF NOT EXISTS hold_time_diff_seconds BIGINT,
    ADD COLUMN IF NOT EXISTS paper_exit_reason VARCHAR(128),
    ADD COLUMN IF NOT EXISTS live_exit_reason VARCHAR(128),
    ADD COLUMN IF NOT EXISTS live_quantity NUMERIC(24,8),
    ADD COLUMN IF NOT EXISTS paper_quantity NUMERIC(24,8),
    ADD COLUMN IF NOT EXISTS broker_ack_latency_ms BIGINT,
    ADD COLUMN IF NOT EXISTS live_order_latency_ms BIGINT,
    ADD COLUMN IF NOT EXISTS quantity_drift NUMERIC(24,8);
