INSERT INTO strategy_definitions (
    id, created_at, updated_at, version, deleted,
    strategy_key, display_name, description, config_json, category, risk_level, enabled, visible_to_users
)
SELECT '22222222-2222-2222-2222-222222222230', NOW(), NOW(), 0, FALSE,
       'CASH_15M_BREAKOUT_TEST',
       'Cash 15m breakout (test)',
       'Simple 15m breakout test strategy for E2E signal/paper pipeline validation',
       '{"timeframes":["15m"],"lookbackBars":12,"minBreakoutPct":0.0015,"intendedUse":"E2E_TEST"}',
       'INTRADAY',
       'MEDIUM',
       TRUE,
       TRUE
WHERE NOT EXISTS (SELECT 1 FROM strategy_definitions WHERE strategy_key = 'CASH_15M_BREAKOUT_TEST');

