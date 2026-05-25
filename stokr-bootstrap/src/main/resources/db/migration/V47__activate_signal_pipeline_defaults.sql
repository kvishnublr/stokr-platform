-- Enable signal generation path: catalog ON, runtime bindings ON, faster scan interval.

UPDATE strategy_definitions
SET enabled = TRUE,
    updated_at = NOW()
WHERE deleted = FALSE
  AND enabled = FALSE;

UPDATE strategy_runtime_bindings
SET runtime_enabled = TRUE,
    scan_interval_seconds = LEAST(scan_interval_seconds, 5),
    updated_at = NOW()
WHERE runtime_enabled = FALSE;

-- Ensure core intraday strategies are bound to NIFTY_50 when missing.
INSERT INTO strategy_runtime_bindings (
    id, created_at, updated_at,
    strategy_catalog_id, universe_group_id,
    runtime_enabled, max_positions, risk_profile, scan_interval_seconds
)
SELECT
    gen_random_uuid(), NOW(), NOW(),
    sd.id,
    'a0000001-0000-0000-0000-000000000001'::uuid,
    TRUE, 5, 'MEDIUM', 5
FROM strategy_definitions sd
WHERE sd.deleted = FALSE
  AND sd.strategy_key IN (
      'MEAN_REVERSION_RANGE_FADE',
      'VWAP_MEAN_REVERSION',
      'OPENING_RANGE_BREAKOUT',
      'EMA_TREND_FOLLOW',
      'MOMENTUM_BREAKOUT'
  )
  AND NOT EXISTS (
      SELECT 1 FROM strategy_runtime_bindings rb
      WHERE rb.strategy_catalog_id = sd.id
        AND rb.universe_group_id = 'a0000001-0000-0000-0000-000000000001'::uuid
  );
