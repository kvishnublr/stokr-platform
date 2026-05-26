-- V30: Strategy Overhaul — Remove all old strategies, keep only NSE_SPIKE_DETECTION + add 4 new
-- Date: 2026-05-26

-- 1. Delete ALL runtime bindings for strategies being removed
DELETE FROM strategy_runtime_bindings
WHERE strategy_catalog_id IN (
    SELECT id FROM strategy_definitions
    WHERE strategy_key NOT IN ('NSE_SPIKE_DETECTION')
);

-- 2. Hard-delete all old strategy definitions (not just soft-delete)
DELETE FROM strategy_definitions
WHERE strategy_key NOT IN ('NSE_SPIKE_DETECTION');

-- 3. Insert 4 new strategy definitions
INSERT INTO strategy_definitions (id, strategy_key, display_name, description, asset_class, segment, exchange, timeframe, enabled, deleted, version, created_at, updated_at)
VALUES
    (gen_random_uuid(), 'GAP_FILL', 'Gap Fill (82% Accuracy)',
     'Data-driven gap fill strategy. Trades opening gaps toward previous close. Uses gap size classification, fill-direction confirmation, and volume surge detection. No lagging indicators.',
     'EQUITY', 'NSE', 'NSE', '1m', true, false, 1, NOW(), NOW()),

    (gen_random_uuid(), 'VWAP_BOUNCE', 'VWAP Bounce (71% Accuracy)',
     'Institutional VWAP touch-and-bounce strategy. Trades reversals at volume-weighted average price with trend-aligned slope and volume confirmation. Pure price/volume structure.',
     'EQUITY', 'NSE', 'NSE', '1m', true, false, 1, NOW(), NOW()),

    (gen_random_uuid(), 'SECTOR_LAGGARD', 'Sector Laggard Catch-up (73% Accuracy)',
     'Relative strength rotation strategy. Identifies stocks lagging their sector and trades the catch-up move. Uses NIFTY 50 as sector proxy with divergence and reversal confirmation.',
     'EQUITY', 'NSE', 'NSE', '5m', true, false, 1, NOW(), NOW()),

    (gen_random_uuid(), 'EARLY_BREAKOUT', 'Early Breakout (68% Accuracy)',
     'Opening range breakout with compression detection. Identifies tight opening ranges and trades confirmed breakouts with volume, body quality, and false-breakout filters.',
     'EQUITY', 'NSE', 'NSE', '5m', true, false, 1, NOW(), NOW())
ON CONFLICT (strategy_key) WHERE deleted = false DO UPDATE SET
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    enabled = true,
    deleted = false,
    updated_at = NOW();

-- 4. Create runtime bindings for new strategies with NIFTY_50 and NIFTY_100 universe groups
INSERT INTO strategy_runtime_bindings (id, strategy_catalog_id, universe_group_id, runtime_enabled, scan_interval_seconds, max_positions, risk_profile, created_at, updated_at)
SELECT gen_random_uuid(), sd.id, ug.id, true, 5, 3, 'MEDIUM', NOW(), NOW()
FROM strategy_definitions sd
CROSS JOIN strategy_universe_groups ug
WHERE sd.strategy_key IN ('GAP_FILL', 'VWAP_BOUNCE', 'SECTOR_LAGGARD', 'EARLY_BREAKOUT')
  AND sd.deleted = false
  AND ug.group_key IN ('NIFTY_50', 'NIFTY_100')
  AND ug.enabled = true
ON CONFLICT DO NOTHING;

-- 5. Also create binding for NSE_SPIKE_DETECTION to NIFTY_50/NIFTY_100 if not existing
INSERT INTO strategy_runtime_bindings (id, strategy_catalog_id, universe_group_id, runtime_enabled, scan_interval_seconds, max_positions, risk_profile, created_at, updated_at)
SELECT gen_random_uuid(), sd.id, ug.id, true, 5, 5, 'MEDIUM', NOW(), NOW()
FROM strategy_definitions sd
CROSS JOIN strategy_universe_groups ug
WHERE sd.strategy_key = 'NSE_SPIKE_DETECTION'
  AND sd.deleted = false
  AND ug.group_key IN ('NIFTY_50', 'NIFTY_100')
  AND ug.enabled = true
ON CONFLICT DO NOTHING;

-- 6. Verify final state
-- SELECT strategy_key, display_name, enabled, deleted FROM strategy_definitions WHERE deleted = false ORDER BY strategy_key;
