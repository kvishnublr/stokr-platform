-- MCX end-to-end test strategy (Test Signal Lab + optional scanner probe)
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
    '22222222-2222-2222-2222-222222222274',
    NOW(), NOW(), 0, FALSE,
    'COMMODITIES_E2E_TEST',
    'MCX E2E Test (manual/scanner)',
    'Simple MCX probe strategy for data-flow and broker path validation. Disable when not testing.',
    '{"intendedUse":"MCX_E2E_TEST","defaultSymbol":"CRUDEOIL","exchange":"MCX"}',
    'INTRADAY', 'MEDIUM', TRUE, TRUE,
    'INTRADAY', 'ALL', '1m', 'MCX',
    FALSE, TRUE, TRUE, '1.0',
    TRUE, 'COMMODITY', 'MCX',
    FALSE, TRUE, FALSE
WHERE NOT EXISTS (
    SELECT 1 FROM strategy_definitions WHERE strategy_key = 'COMMODITIES_E2E_TEST'
);

INSERT INTO strategy_runtime_bindings (
    id, created_at, updated_at,
    strategy_catalog_id, universe_group_id,
    runtime_enabled, max_positions, risk_profile, scan_interval_seconds
)
SELECT
    gen_random_uuid(), NOW(), NOW(),
    (SELECT id FROM strategy_definitions WHERE strategy_key = 'COMMODITIES_E2E_TEST'),
    'a0000001-0000-0000-0000-000000000012',
    TRUE, 1, 'MEDIUM', 120
WHERE NOT EXISTS (
    SELECT 1 FROM strategy_runtime_bindings srb
    JOIN strategy_definitions sd ON srb.strategy_catalog_id = sd.id
    WHERE sd.strategy_key = 'COMMODITIES_E2E_TEST'
      AND srb.universe_group_id = 'a0000001-0000-0000-0000-000000000012'
);
