-- Pipeline extensions: market ticks, enriched signals, expanded OMS states support, execution audit fields,
-- backtest metrics. Single schema (public).

CREATE TABLE marketdata_ticks (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    symbol VARCHAR(64) NOT NULL,
    tick_time TIMESTAMPTZ NOT NULL,
    price NUMERIC(24, 8) NOT NULL,
    quantity NUMERIC(24, 8),
    trade_side VARCHAR(8),
    source VARCHAR(64)
);

CREATE INDEX idx_marketdata_ticks_symbol_time ON marketdata_ticks (symbol, tick_time);

CREATE UNIQUE INDEX ux_marketdata_candles_symbol_tf_open
    ON marketdata_candles (symbol, timeframe, open_time)
    WHERE deleted = FALSE;

ALTER TABLE strategy_signals DROP CONSTRAINT IF EXISTS strategy_signals_instance_id_fkey;
ALTER TABLE strategy_signals ALTER COLUMN instance_id DROP NOT NULL;

ALTER TABLE strategy_signals ADD COLUMN IF NOT EXISTS strategy_name VARCHAR(128);
ALTER TABLE strategy_signals ADD COLUMN IF NOT EXISTS strategy_version VARCHAR(32);
ALTER TABLE strategy_signals ADD COLUMN IF NOT EXISTS symbol VARCHAR(64);
ALTER TABLE strategy_signals ADD COLUMN IF NOT EXISTS candle_timestamp TIMESTAMPTZ;
ALTER TABLE strategy_signals ADD COLUMN IF NOT EXISTS confidence_score NUMERIC(10, 6);
ALTER TABLE strategy_signals ADD COLUMN IF NOT EXISTS rsi_value NUMERIC(24, 8);
ALTER TABLE strategy_signals ADD COLUMN IF NOT EXISTS vwap_distance NUMERIC(24, 8);
ALTER TABLE strategy_signals ADD COLUMN IF NOT EXISTS atr_value NUMERIC(24, 8);
ALTER TABLE strategy_signals ADD COLUMN IF NOT EXISTS range_high NUMERIC(24, 8);
ALTER TABLE strategy_signals ADD COLUMN IF NOT EXISTS range_low NUMERIC(24, 8);
ALTER TABLE strategy_signals ADD COLUMN IF NOT EXISTS market_regime VARCHAR(32);
ALTER TABLE strategy_signals ADD COLUMN IF NOT EXISTS rejection_pattern VARCHAR(64);
ALTER TABLE strategy_signals ADD COLUMN IF NOT EXISTS reason_text VARCHAR(1000);
ALTER TABLE strategy_signals ADD COLUMN IF NOT EXISTS user_id UUID;
ALTER TABLE strategy_signals ADD COLUMN IF NOT EXISTS backtest_run_id UUID;
ALTER TABLE strategy_signals ADD COLUMN IF NOT EXISTS pipeline VARCHAR(16);
ALTER TABLE strategy_signals ADD COLUMN IF NOT EXISTS stop_price NUMERIC(24, 8);
ALTER TABLE strategy_signals ADD COLUMN IF NOT EXISTS target_price NUMERIC(24, 8);
ALTER TABLE strategy_signals ADD COLUMN IF NOT EXISTS entry_reference_price NUMERIC(24, 8);

CREATE INDEX IF NOT EXISTS idx_strategy_signals_symbol_time ON strategy_signals (symbol, candle_timestamp);
CREATE INDEX IF NOT EXISTS idx_strategy_signals_user ON strategy_signals (user_id);

ALTER TABLE oms_orders ALTER COLUMN state TYPE VARCHAR(64);

ALTER TABLE oms_orders ADD COLUMN IF NOT EXISTS signal_id UUID;
ALTER TABLE oms_orders ADD COLUMN IF NOT EXISTS strategy_key VARCHAR(128);
ALTER TABLE oms_orders ADD COLUMN IF NOT EXISTS execution_mode VARCHAR(16);
ALTER TABLE oms_orders ADD COLUMN IF NOT EXISTS stop_price NUMERIC(24, 8);
ALTER TABLE oms_orders ADD COLUMN IF NOT EXISTS target_price NUMERIC(24, 8);
ALTER TABLE oms_orders ADD COLUMN IF NOT EXISTS entry_reference_price NUMERIC(24, 8);
ALTER TABLE oms_orders ADD COLUMN IF NOT EXISTS backtest_run_id UUID;

CREATE INDEX IF NOT EXISTS idx_oms_orders_signal ON oms_orders (signal_id);

ALTER TABLE oms_executions ADD COLUMN IF NOT EXISTS execution_kind VARCHAR(16);
ALTER TABLE oms_executions ADD COLUMN IF NOT EXISTS fill_time TIMESTAMPTZ;
ALTER TABLE oms_executions ADD COLUMN IF NOT EXISTS latency_ms BIGINT;
ALTER TABLE oms_executions ADD COLUMN IF NOT EXISTS slippage_bps NUMERIC(24, 8);
ALTER TABLE oms_executions ADD COLUMN IF NOT EXISTS spread_bps NUMERIC(24, 8);
ALTER TABLE oms_executions ADD COLUMN IF NOT EXISTS reference_price NUMERIC(24, 8);

ALTER TABLE portfolio_positions ADD COLUMN IF NOT EXISTS realized_pnl NUMERIC(24, 8);
ALTER TABLE portfolio_positions ADD COLUMN IF NOT EXISTS unrealized_pnl NUMERIC(24, 8);
ALTER TABLE portfolio_positions ADD COLUMN IF NOT EXISTS mtm_price NUMERIC(24, 8);

ALTER TABLE portfolio_pnl_snapshots ADD COLUMN IF NOT EXISTS realized_pnl NUMERIC(24, 8);
ALTER TABLE portfolio_pnl_snapshots ADD COLUMN IF NOT EXISTS unrealized_pnl NUMERIC(24, 8);
ALTER TABLE portfolio_pnl_snapshots ADD COLUMN IF NOT EXISTS mtm_pnl NUMERIC(24, 8);

CREATE TABLE backtest_metrics (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    run_id UUID NOT NULL UNIQUE REFERENCES backtest_runs (id),
    metrics_json TEXT NOT NULL,
    rr_analysis_json TEXT,
    streak_analysis_json TEXT
);

INSERT INTO strategy_definitions (id, created_at, updated_at, version, deleted, strategy_key, description, config_json)
SELECT '22222222-2222-2222-2222-222222222201',
       NOW(),
       NOW(),
       0,
       FALSE,
       'MEAN_REVERSION_RANGE_FADE',
       'Mean reversion range fade on index futures (NIFTY/BANKNIFTY)',
       '{"timeframes":["1m","5m"],"session":{"start":"09:25","end":"14:45"},"symbols":["NIFTY_FUT","BANKNIFTY_FUT"]}'
WHERE NOT EXISTS (
    SELECT 1 FROM strategy_definitions WHERE strategy_key = 'MEAN_REVERSION_RANGE_FADE'
);
