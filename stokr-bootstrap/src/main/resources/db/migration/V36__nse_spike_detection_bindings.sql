-- ============================================================
-- V36: NSE_SPIKE_DETECTION strategy definition + bindings
-- Registers the 1m momentum spike strategy for NIFTY_50 and
-- NIFTY_100 universes (liquid stocks only).
-- ============================================================

-- 1. Insert NSE_SPIKE_DETECTION into strategy_definitions
INSERT INTO strategy_definitions (
    id, created_at, updated_at, version, deleted,
    strategy_key, display_name, description, config_json,
    category, risk_level, enabled, visible_to_users,
    strategy_type, execution_mode, default_timeframe, default_exchange,
    supports_backtest, supports_live, supports_paper, catalog_version,
    template_generated, asset_class, segment,
    derivative_enabled, futures_strategy_enabled, option_strategy_enabled
)
SELECT
    gen_random_uuid(), NOW(), NOW(), 0, FALSE,
    'NSE_SPIKE_DETECTION',
    'NSE Spike Detection (1m)',
    '1m momentum spike strategy — detects sudden price+volume bursts on NSE equities '
    'using a 6-component composite score (velocity, volume, bar quality, NIFTY alignment, '
    'sector alignment, rejection wick). Fires BUY/SELL when score >= 75.',
    '{"timeframe":"1m","scoreThreshold":75,"volAvgPeriod":20,"niftiBars":7,"sessionStart":"09:15","sessionEnd":"15:20"}',
    'INTRADAY', 'HIGH', TRUE, TRUE,
    'INTRADAY', 'ALL', '1m', 'NSE',
    TRUE, TRUE, TRUE, '1.0',
    TRUE, 'EQUITY', 'NSE',
    FALSE, FALSE, FALSE
WHERE NOT EXISTS (
    SELECT 1 FROM strategy_definitions WHERE strategy_key = 'NSE_SPIKE_DETECTION'
);

-- 2. Runtime binding: NIFTY_50 (top 50 liquid stocks — best for spike detection)
INSERT INTO strategy_runtime_bindings (
    id, created_at, updated_at,
    strategy_catalog_id, universe_group_id,
    runtime_enabled, max_positions, risk_profile, scan_interval_seconds
)
SELECT
    gen_random_uuid(), NOW(), NOW(),
    (SELECT id FROM strategy_definitions WHERE strategy_key = 'NSE_SPIKE_DETECTION'),
    'a0000001-0000-0000-0000-000000000001',
    TRUE, 5, 'HIGH', 60
WHERE NOT EXISTS (
    SELECT 1 FROM strategy_runtime_bindings srb
    JOIN strategy_definitions sd ON srb.strategy_catalog_id = sd.id
    WHERE sd.strategy_key = 'NSE_SPIKE_DETECTION'
      AND srb.universe_group_id = 'a0000001-0000-0000-0000-000000000001'
);

-- 3. Runtime binding: NIFTY_100 (expanded universe, still liquid)
INSERT INTO strategy_runtime_bindings (
    id, created_at, updated_at,
    strategy_catalog_id, universe_group_id,
    runtime_enabled, max_positions, risk_profile, scan_interval_seconds
)
SELECT
    gen_random_uuid(), NOW(), NOW(),
    (SELECT id FROM strategy_definitions WHERE strategy_key = 'NSE_SPIKE_DETECTION'),
    'a0000001-0000-0000-0000-000000000002',
    TRUE, 5, 'HIGH', 60
WHERE NOT EXISTS (
    SELECT 1 FROM strategy_runtime_bindings srb
    JOIN strategy_definitions sd ON srb.strategy_catalog_id = sd.id
    WHERE sd.strategy_key = 'NSE_SPIKE_DETECTION'
      AND srb.universe_group_id = 'a0000001-0000-0000-0000-000000000002'
);
