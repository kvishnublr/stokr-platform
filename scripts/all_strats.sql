-- All strategies with their types
SELECT id, name, strategy_type, timeframe, enabled FROM strategies WHERE name IS NOT NULL ORDER BY id;

-- Check what strategy plugin map exists in BacktestController
