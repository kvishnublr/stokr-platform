-- Trade lifecycle reconciliation: full paper/live lifecycle fields and validation rollup extensions

ALTER TABLE execution_comparison_metrics
    ADD COLUMN IF NOT EXISTS paper_realized_pnl NUMERIC(24,8),
    ADD COLUMN IF NOT EXISTS live_realized_pnl NUMERIC(24,8),
    ADD COLUMN IF NOT EXISTS pnl_drift NUMERIC(24,8),
    ADD COLUMN IF NOT EXISTS paper_hold_seconds BIGINT,
    ADD COLUMN IF NOT EXISTS live_hold_seconds BIGINT,
    ADD COLUMN IF NOT EXISTS hold_time_drift BIGINT,
    ADD COLUMN IF NOT EXISTS paper_exit_category VARCHAR(32),
    ADD COLUMN IF NOT EXISTS live_exit_category VARCHAR(32),
    ADD COLUMN IF NOT EXISTS paper_max_drawdown NUMERIC(24,8),
    ADD COLUMN IF NOT EXISTS live_max_drawdown NUMERIC(24,8),
    ADD COLUMN IF NOT EXISTS paper_max_profit NUMERIC(24,8),
    ADD COLUMN IF NOT EXISTS live_max_profit NUMERIC(24,8),
    ADD COLUMN IF NOT EXISTS fill_count_difference BIGINT,
    ADD COLUMN IF NOT EXISTS partial_fill_difference BIGINT,
    ADD COLUMN IF NOT EXISTS paper_fill_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS live_fill_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS reconciliation_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS reconciled_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS paper_closed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS live_closed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS reconciliation_failure_reason VARCHAR(512),
    ADD COLUMN IF NOT EXISTS paper_entry_filled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS live_entry_filled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS paper_position_closed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS live_position_closed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS paper_entry_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS live_entry_at TIMESTAMPTZ;

UPDATE execution_comparison_metrics
SET paper_realized_pnl = paper_pnl
WHERE paper_realized_pnl IS NULL AND paper_pnl IS NOT NULL;

UPDATE execution_comparison_metrics
SET live_realized_pnl = live_pnl
WHERE live_realized_pnl IS NULL AND live_pnl IS NOT NULL;

UPDATE execution_comparison_metrics
SET pnl_drift = pnl_divergence
WHERE pnl_drift IS NULL AND pnl_divergence IS NOT NULL;

UPDATE execution_comparison_metrics
SET hold_time_drift = hold_time_diff_seconds
WHERE hold_time_drift IS NULL AND hold_time_diff_seconds IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_ecm_reconciliation_status
    ON execution_comparison_metrics (reconciliation_status, updated_at DESC)
    WHERE deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_ecm_strategy_reconciled
    ON execution_comparison_metrics (strategy_key, reconciled_at DESC)
    WHERE deleted = FALSE;

ALTER TABLE strategy_validation_metrics
    ADD COLUMN IF NOT EXISTS fills BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS avg_slippage_bps NUMERIC(12,6),
    ADD COLUMN IF NOT EXISTS win_rate_delta NUMERIC(8,4),
    ADD COLUMN IF NOT EXISTS paper_win_rate NUMERIC(8,4),
    ADD COLUMN IF NOT EXISTS live_win_rate NUMERIC(8,4),
    ADD COLUMN IF NOT EXISTS integrity_rejection_pct NUMERIC(8,4),
    ADD COLUMN IF NOT EXISTS avg_execution_latency_ms BIGINT,
    ADD COLUMN IF NOT EXISTS strategy_degradation_score NUMERIC(12,6),
    ADD COLUMN IF NOT EXISTS live_underperformance_pct NUMERIC(12,6),
    ADD COLUMN IF NOT EXISTS expectancy_drift NUMERIC(12,6),
    ADD COLUMN IF NOT EXISTS exit_timing_drift_seconds BIGINT,
    ADD COLUMN IF NOT EXISTS slippage_p50_bps NUMERIC(12,6),
    ADD COLUMN IF NOT EXISTS slippage_p95_bps NUMERIC(12,6),
    ADD COLUMN IF NOT EXISTS latency_p50_ms BIGINT,
    ADD COLUMN IF NOT EXISTS latency_p95_ms BIGINT,
    ADD COLUMN IF NOT EXISTS unreconciled_trades BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS reconciliation_failures BIGINT NOT NULL DEFAULT 0;
