-- Backtest run provenance + UX metadata (nullable for legacy rows).
ALTER TABLE backtest_runs ADD COLUMN IF NOT EXISTS user_id UUID;
ALTER TABLE backtest_runs ADD COLUMN IF NOT EXISTS timeframe VARCHAR(16);
ALTER TABLE backtest_runs ADD COLUMN IF NOT EXISTS range_start TIMESTAMPTZ;
ALTER TABLE backtest_runs ADD COLUMN IF NOT EXISTS range_end TIMESTAMPTZ;
ALTER TABLE backtest_runs ADD COLUMN IF NOT EXISTS execution_profile VARCHAR(32);

CREATE INDEX IF NOT EXISTS idx_backtest_runs_user_created ON backtest_runs (user_id, created_at DESC);

INSERT INTO strategy_definitions (
    id, created_at, updated_at, version, deleted,
    strategy_key, display_name, description, config_json, category, risk_level, enabled, visible_to_users
)
SELECT '22222222-2222-2222-2222-222222222202', NOW(), NOW(), 0, FALSE,
       'OPENING_RANGE_BREAKOUT',
       'Opening range breakout',
       'Opening range breakout after session opening window',
       '{"timeframes":["1m"],"rangeMinutes":15}',
       'INTRADAY',
       'HIGH',
       TRUE,
       TRUE
WHERE NOT EXISTS (SELECT 1 FROM strategy_definitions WHERE strategy_key = 'OPENING_RANGE_BREAKOUT');

INSERT INTO strategy_definitions (
    id, created_at, updated_at, version, deleted,
    strategy_key, display_name, description, config_json, category, risk_level, enabled, visible_to_users
)
SELECT '22222222-2222-2222-2222-222222222203', NOW(), NOW(), 0, FALSE,
       'VWAP_MEAN_REVERSION',
       'VWAP mean reversion',
       'Fade extreme deviations from session VWAP',
       '{"timeframes":["1m"],"session":{"start":"09:25","end":"14:45"}}',
       'MEAN_REVERSION',
       'MEDIUM',
       TRUE,
       TRUE
WHERE NOT EXISTS (SELECT 1 FROM strategy_definitions WHERE strategy_key = 'VWAP_MEAN_REVERSION');

INSERT INTO strategy_definitions (
    id, created_at, updated_at, version, deleted,
    strategy_key, display_name, description, config_json, category, risk_level, enabled, visible_to_users
)
SELECT '22222222-2222-2222-2222-222222222204', NOW(), NOW(), 0, FALSE,
       'MOMENTUM_BREAKOUT',
       'Momentum breakout',
       'Donchian-style momentum breakout',
       '{"timeframes":["1m"],"lookback":20}',
       'TREND',
       'HIGH',
       TRUE,
       TRUE
WHERE NOT EXISTS (SELECT 1 FROM strategy_definitions WHERE strategy_key = 'MOMENTUM_BREAKOUT');

INSERT INTO strategy_definitions (
    id, created_at, updated_at, version, deleted,
    strategy_key, display_name, description, config_json, category, risk_level, enabled, visible_to_users
)
SELECT '22222222-2222-2222-2222-222222222205', NOW(), NOW(), 0, FALSE,
       'EMA_TREND_FOLLOW',
       'EMA trend follow',
       'Trend alignment via fast/slow EMA with pullback entry',
       '{"timeframes":["1m"],"fast":9,"slow":21}',
       'TREND',
       'MEDIUM',
       TRUE,
       TRUE
WHERE NOT EXISTS (SELECT 1 FROM strategy_definitions WHERE strategy_key = 'EMA_TREND_FOLLOW');
