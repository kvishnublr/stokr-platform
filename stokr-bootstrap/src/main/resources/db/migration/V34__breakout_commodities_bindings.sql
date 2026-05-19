-- ============================================================
-- V34: BREAKOUT_COMMODITIES strategy definition + MCX bindings
-- Inserts the MCX 10m breakout strategy into the catalog and
-- creates runtime bindings for all four MCX universe groups.
-- ============================================================

-- 1. Insert BREAKOUT_COMMODITIES strategy definition
INSERT INTO strategy_definitions (
    id, created_at, updated_at, version, deleted,
    strategy_key, display_name, description, config_json,
    category, risk_level, enabled, visible_to_users,
    -- catalog columns (V31)
    strategy_type, execution_mode, default_timeframe, default_exchange,
    supports_backtest, supports_live, supports_paper, catalog_version,
    template_generated, asset_class, segment,
    derivative_enabled, futures_strategy_enabled, option_strategy_enabled
)
SELECT
    gen_random_uuid(), NOW(), NOW(), 0, FALSE,
    'BREAKOUT_COMMODITIES',
    'MCX Commodities Breakout (10m)',
    '10m breakout strategy for MCX Gold, Silver, Crude Oil, Natural Gas and Base Metals',
    '{"timeframe":"10m","lookbackBars":20,"minBreakoutPct":0.002,"minAtrRatio":0.0015,"rsiThresholdBuy":55,"rsiThresholdSell":45,"atrPeriod":14}',
    'INTRADAY', 'HIGH', TRUE, TRUE,
    'INTRADAY', 'ALL', '10m', 'MCX',
    TRUE, TRUE, TRUE, '1.0',
    TRUE, 'COMMODITY', 'MCX',
    FALSE, TRUE, FALSE
WHERE NOT EXISTS (
    SELECT 1 FROM strategy_definitions WHERE strategy_key = 'BREAKOUT_COMMODITIES'
);

-- 2. Runtime binding: MCX_BULLION (Gold, Silver)
-- NOTE: strategy_runtime_bindings has no version/deleted columns (created in V31)
INSERT INTO strategy_runtime_bindings (
    id, created_at, updated_at,
    strategy_catalog_id, universe_group_id,
    runtime_enabled, max_positions, risk_profile, scan_interval_seconds
)
SELECT
    gen_random_uuid(), NOW(), NOW(),
    (SELECT id FROM strategy_definitions WHERE strategy_key = 'BREAKOUT_COMMODITIES'),
    'a0000001-0000-0000-0000-000000000011',
    TRUE, 3, 'HIGH', 600
WHERE NOT EXISTS (
    SELECT 1 FROM strategy_runtime_bindings srb
    JOIN strategy_definitions sd ON srb.strategy_catalog_id = sd.id
    WHERE sd.strategy_key = 'BREAKOUT_COMMODITIES'
      AND srb.universe_group_id = 'a0000001-0000-0000-0000-000000000011'
);

-- 3. Runtime binding: MCX_ENERGY (Crude Oil, Natural Gas)
INSERT INTO strategy_runtime_bindings (
    id, created_at, updated_at,
    strategy_catalog_id, universe_group_id,
    runtime_enabled, max_positions, risk_profile, scan_interval_seconds
)
SELECT
    gen_random_uuid(), NOW(), NOW(),
    (SELECT id FROM strategy_definitions WHERE strategy_key = 'BREAKOUT_COMMODITIES'),
    'a0000001-0000-0000-0000-000000000012',
    TRUE, 3, 'HIGH', 600
WHERE NOT EXISTS (
    SELECT 1 FROM strategy_runtime_bindings srb
    JOIN strategy_definitions sd ON srb.strategy_catalog_id = sd.id
    WHERE sd.strategy_key = 'BREAKOUT_COMMODITIES'
      AND srb.universe_group_id = 'a0000001-0000-0000-0000-000000000012'
);

-- 4. Runtime binding: MCX_METALS (Copper, Zinc, Aluminium, etc.)
INSERT INTO strategy_runtime_bindings (
    id, created_at, updated_at,
    strategy_catalog_id, universe_group_id,
    runtime_enabled, max_positions, risk_profile, scan_interval_seconds
)
SELECT
    gen_random_uuid(), NOW(), NOW(),
    (SELECT id FROM strategy_definitions WHERE strategy_key = 'BREAKOUT_COMMODITIES'),
    'a0000001-0000-0000-0000-000000000013',
    TRUE, 3, 'MEDIUM', 600
WHERE NOT EXISTS (
    SELECT 1 FROM strategy_runtime_bindings srb
    JOIN strategy_definitions sd ON srb.strategy_catalog_id = sd.id
    WHERE sd.strategy_key = 'BREAKOUT_COMMODITIES'
      AND srb.universe_group_id = 'a0000001-0000-0000-0000-000000000013'
);

-- 5. Runtime binding: MCX_AGRI (agricultural futures)
INSERT INTO strategy_runtime_bindings (
    id, created_at, updated_at,
    strategy_catalog_id, universe_group_id,
    runtime_enabled, max_positions, risk_profile, scan_interval_seconds
)
SELECT
    gen_random_uuid(), NOW(), NOW(),
    (SELECT id FROM strategy_definitions WHERE strategy_key = 'BREAKOUT_COMMODITIES'),
    'a0000001-0000-0000-0000-000000000014',
    TRUE, 2, 'MEDIUM', 600
WHERE NOT EXISTS (
    SELECT 1 FROM strategy_runtime_bindings srb
    JOIN strategy_definitions sd ON srb.strategy_catalog_id = sd.id
    WHERE sd.strategy_key = 'BREAKOUT_COMMODITIES'
      AND srb.universe_group_id = 'a0000001-0000-0000-0000-000000000014'
);
