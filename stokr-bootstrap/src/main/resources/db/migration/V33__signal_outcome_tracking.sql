-- Signal outcome lifecycle tracking
-- Adds execution quality and outcome analytics columns to strategy_signals

ALTER TABLE strategy_signals
    ADD COLUMN IF NOT EXISTS outcome_status          VARCHAR(32),
    ADD COLUMN IF NOT EXISTS outcome_time            TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS entry_price             NUMERIC(24, 8),
    ADD COLUMN IF NOT EXISTS exit_price              NUMERIC(24, 8),
    ADD COLUMN IF NOT EXISTS realized_pnl            NUMERIC(24, 8),
    ADD COLUMN IF NOT EXISTS unrealized_pnl          NUMERIC(24, 8),
    ADD COLUMN IF NOT EXISTS max_favorable_excursion NUMERIC(24, 8),
    ADD COLUMN IF NOT EXISTS max_adverse_excursion   NUMERIC(24, 8),
    ADD COLUMN IF NOT EXISTS hit_target              BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS hit_stoploss            BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS risk_reward_achieved    NUMERIC(10, 4),
    ADD COLUMN IF NOT EXISTS execution_latency_ms    BIGINT,
    ADD COLUMN IF NOT EXISTS broker_latency_ms       BIGINT,
    ADD COLUMN IF NOT EXISTS signal_validity_seconds INT,
    ADD COLUMN IF NOT EXISTS expired                 BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS expiry_reason           VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_signals_outcome_live
    ON strategy_signals (outcome_status)
    WHERE deleted = FALSE AND backtest_run_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_signals_symbol_live
    ON strategy_signals (symbol)
    WHERE deleted = FALSE AND backtest_run_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_signals_strategy_live
    ON strategy_signals (strategy_name)
    WHERE deleted = FALSE AND backtest_run_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_signals_created_live
    ON strategy_signals (created_at DESC)
    WHERE deleted = FALSE AND backtest_run_id IS NULL;
