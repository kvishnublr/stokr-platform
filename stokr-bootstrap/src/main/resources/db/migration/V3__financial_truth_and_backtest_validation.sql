-- Financial truth: execution ledger integrity, portfolio accounting, deterministic backtest persistence.

ALTER TABLE oms_executions ADD COLUMN IF NOT EXISTS execution_sequence BIGINT;
ALTER TABLE oms_executions ADD COLUMN IF NOT EXISTS execution_hash VARCHAR(128);
ALTER TABLE oms_executions ADD COLUMN IF NOT EXISTS execution_timestamp TIMESTAMPTZ;
ALTER TABLE oms_executions ADD COLUMN IF NOT EXISTS replay_source VARCHAR(32);
ALTER TABLE oms_executions ADD COLUMN IF NOT EXISTS replay_run_id UUID;

CREATE UNIQUE INDEX IF NOT EXISTS ux_oms_executions_broker_execution_id
    ON oms_executions (broker_execution_id)
    WHERE broker_execution_id IS NOT NULL AND deleted = FALSE;

CREATE UNIQUE INDEX IF NOT EXISTS ux_oms_executions_execution_hash
    ON oms_executions (execution_hash)
    WHERE execution_hash IS NOT NULL AND deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_oms_executions_order_sequence
    ON oms_executions (order_id, execution_sequence);

CREATE INDEX IF NOT EXISTS idx_oms_executions_replay_run
    ON oms_executions (replay_run_id, execution_timestamp);

CREATE TABLE IF NOT EXISTS portfolio_daily_summary (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    user_id UUID NOT NULL,
    business_date DATE NOT NULL,
    realized_pnl NUMERIC(24, 8) NOT NULL DEFAULT 0,
    unrealized_pnl NUMERIC(24, 8) NOT NULL DEFAULT 0,
    mtm_pnl NUMERIC(24, 8) NOT NULL DEFAULT 0,
    cumulative_pnl NUMERIC(24, 8) NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_portfolio_daily_summary_user_date
    ON portfolio_daily_summary (user_id, business_date)
    WHERE deleted = FALSE;

CREATE TABLE IF NOT EXISTS backtest_equity_curve (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    run_id UUID NOT NULL REFERENCES backtest_runs (id),
    point_time TIMESTAMPTZ NOT NULL,
    cumulative_pnl NUMERIC(24, 8) NOT NULL,
    drawdown NUMERIC(24, 8) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_backtest_equity_curve_run_time
    ON backtest_equity_curve (run_id, point_time);

ALTER TABLE backtest_trades ADD COLUMN IF NOT EXISTS side VARCHAR(8);
ALTER TABLE backtest_trades ADD COLUMN IF NOT EXISTS pnl NUMERIC(24, 8);
ALTER TABLE backtest_trades ADD COLUMN IF NOT EXISTS opened_at TIMESTAMPTZ;
ALTER TABLE backtest_trades ADD COLUMN IF NOT EXISTS closed_at TIMESTAMPTZ;
ALTER TABLE backtest_trades ADD COLUMN IF NOT EXISTS holding_seconds BIGINT;

ALTER TABLE backtest_metrics ADD COLUMN IF NOT EXISTS win_rate NUMERIC(24, 8);
ALTER TABLE backtest_metrics ADD COLUMN IF NOT EXISTS total_trades INTEGER;
ALTER TABLE backtest_metrics ADD COLUMN IF NOT EXISTS profit_factor NUMERIC(24, 8);
ALTER TABLE backtest_metrics ADD COLUMN IF NOT EXISTS sharpe_ratio NUMERIC(24, 8);
ALTER TABLE backtest_metrics ADD COLUMN IF NOT EXISTS max_drawdown NUMERIC(24, 8);
ALTER TABLE backtest_metrics ADD COLUMN IF NOT EXISTS avg_rr NUMERIC(24, 8);
ALTER TABLE backtest_metrics ADD COLUMN IF NOT EXISTS expectancy NUMERIC(24, 8);
ALTER TABLE backtest_metrics ADD COLUMN IF NOT EXISTS total_pnl NUMERIC(24, 8);
ALTER TABLE backtest_metrics ADD COLUMN IF NOT EXISTS avg_holding_time_seconds BIGINT;
ALTER TABLE backtest_metrics ADD COLUMN IF NOT EXISTS replay_hash VARCHAR(128);
