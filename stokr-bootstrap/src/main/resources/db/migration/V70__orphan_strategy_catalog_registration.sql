-- V70: Register orphan strategies in catalog with bindings (DRY_RUN only, no LIVE)

INSERT INTO strategy_definitions (
    id, strategy_key, display_name, description, asset_class, segment, default_exchange,
    default_timeframe, enabled, deleted, version, validation_status, live_shadow_enabled,
    created_at, updated_at
)
VALUES
    (gen_random_uuid(), 'INDEX_HUNT', 'Index Hunt',
     'NIFTY/BANKNIFTY index momentum options direction with multi-gate validation.',
     'OPTIONS', 'NSE', 'NSE', '1m', TRUE, FALSE, 1, 'DRY_RUN', FALSE, NOW(), NOW()),
    (gen_random_uuid(), 'ADV_CASH', 'Advanced Cash Equity',
     'Top-25 liquid equity OBI consensus with pattern memory.',
     'EQUITY', 'NSE', 'NSE', '1m', TRUE, FALSE, 1, 'DRY_RUN', FALSE, NOW(), NOW()),
    (gen_random_uuid(), 'S3_VWAP_RETEST', 'S3 VWAP Retest',
     'Futures VWAP retest continuation in TREND regime.',
     'FUTURES', 'NSE', 'NSE', '1m', TRUE, FALSE, 1, 'DRY_RUN', FALSE, NOW(), NOW()),
    (gen_random_uuid(), 'S7_RANGE_FADE', 'S7 Range Fade',
     'Chop regime buy-only fade below VWAP on index futures.',
     'FUTURES', 'NSE', 'NSE', '1m', TRUE, FALSE, 1, 'DRY_RUN', FALSE, NOW(), NOW())
ON CONFLICT (strategy_key) DO UPDATE SET
    enabled = TRUE,
    deleted = FALSE,
    validation_status = 'DRY_RUN',
    live_shadow_enabled = FALSE,
    updated_at = NOW();

-- Runtime bindings for orphan strategies (disabled scan by default via execution mode)
INSERT INTO strategy_runtime_bindings (
    id, strategy_catalog_id, universe_group_id, runtime_enabled, scan_interval_seconds,
    max_positions, risk_profile, created_at, updated_at
)
SELECT gen_random_uuid(), sd.id, ug.id, FALSE, 60, 2, 'MEDIUM', NOW(), NOW()
FROM strategy_definitions sd
CROSS JOIN strategy_universe_groups ug
WHERE sd.strategy_key = 'INDEX_HUNT'
  AND ug.group_key = 'NIFTY_50'
  AND sd.deleted = FALSE
ON CONFLICT DO NOTHING;

INSERT INTO strategy_runtime_bindings (
    id, strategy_catalog_id, universe_group_id, runtime_enabled, scan_interval_seconds,
    max_positions, risk_profile, created_at, updated_at
)
SELECT gen_random_uuid(), sd.id, ug.id, FALSE, 60, 3, 'MEDIUM', NOW(), NOW()
FROM strategy_definitions sd
CROSS JOIN strategy_universe_groups ug
WHERE sd.strategy_key = 'ADV_CASH'
  AND ug.group_key = 'NIFTY_50'
  AND sd.deleted = FALSE
ON CONFLICT DO NOTHING;

INSERT INTO strategy_runtime_bindings (
    id, strategy_catalog_id, universe_group_id, runtime_enabled, scan_interval_seconds,
    max_positions, risk_profile, created_at, updated_at
)
SELECT gen_random_uuid(), sd.id, ug.id, FALSE, 60, 2, 'HIGH', NOW(), NOW()
FROM strategy_definitions sd
CROSS JOIN strategy_universe_groups ug
WHERE sd.strategy_key IN ('S3_VWAP_RETEST', 'S7_RANGE_FADE')
  AND ug.group_key = 'BANKNIFTY_FUTURES'
  AND sd.deleted = FALSE
ON CONFLICT DO NOTHING;

-- Execution configs for orphan strategies (DRY_RUN / PAPER only, fixed qty=1)
INSERT INTO strategy_execution_configs (
    id, strategy_key, enabled, execution_mode, live_enabled, paper_enabled,
    sizing_mode, force_fixed_qty, fixed_qty, max_positions, deleted
)
SELECT gen_random_uuid(), sk, TRUE, 'PAPER', FALSE, TRUE,
       'FIXED_QUANTITY', TRUE, 1, 2, FALSE
FROM (VALUES ('INDEX_HUNT'), ('ADV_CASH'), ('S3_VWAP_RETEST'), ('S7_RANGE_FADE')) AS t(sk)
WHERE NOT EXISTS (
    SELECT 1 FROM strategy_execution_configs c
    WHERE c.strategy_key = t.sk AND c.user_id IS NULL AND c.deleted = FALSE
);
