-- Cash/equity intraday strategies: ensure runtime bindings on NIFTY_100 (and refresh NIFTY_50).

UPDATE strategy_definitions
SET enabled = TRUE, updated_at = NOW()
WHERE deleted = FALSE;

UPDATE strategy_runtime_bindings
SET runtime_enabled = TRUE,
    scan_interval_seconds = LEAST(scan_interval_seconds, 5),
    updated_at = NOW()
WHERE runtime_enabled = FALSE;

INSERT INTO strategy_runtime_bindings (
    id, created_at, updated_at,
    strategy_catalog_id, universe_group_id,
    runtime_enabled, max_positions, risk_profile, scan_interval_seconds
)
SELECT
    gen_random_uuid(), NOW(), NOW(),
    sd.id,
    ug.id,
    TRUE, 5, 'MEDIUM', 5
FROM strategy_definitions sd
CROSS JOIN strategy_universe_groups ug
WHERE sd.deleted = FALSE
  AND ug.group_key IN ('NIFTY_50', 'NIFTY_100')
  AND sd.strategy_key IN (
      'MEAN_REVERSION_RANGE_FADE',
      'VWAP_MEAN_REVERSION',
      'OPENING_RANGE_BREAKOUT',
      'EMA_TREND_FOLLOW',
      'MOMENTUM_BREAKOUT',
      'NSE_SPIKE_DETECTION',
      'CASH_15M_BREAKOUT_TEST',
      'MEAN_REVERSION_V2'
  )
  AND NOT EXISTS (
      SELECT 1 FROM strategy_runtime_bindings rb
      WHERE rb.strategy_catalog_id = sd.id
        AND rb.universe_group_id = ug.id
  );
