-- V57: Reduce duplicate universe scans and align scan cadence with catalog poll (60s).

-- Prefer NIFTY_100 only when both NIFTY_50 and NIFTY_100 bindings exist (avoids ~2x symbol scans).
UPDATE strategy_runtime_bindings b
SET runtime_enabled = false,
    updated_at      = NOW()
FROM strategy_definitions sd,
     strategy_universe_groups ug
WHERE b.strategy_catalog_id = sd.id
  AND b.universe_group_id = ug.id
  AND ug.group_key = 'NIFTY_50'
  AND sd.strategy_key IN (
      'NSE_SPIKE_DETECTION', 'EARLY_BREAKOUT', 'VWAP_BOUNCE', 'GAP_FILL', 'SECTOR_LAGGARD'
  )
  AND EXISTS (
      SELECT 1
      FROM strategy_runtime_bindings b2
      JOIN strategy_universe_groups ug2 ON ug2.id = b2.universe_group_id
      WHERE b2.strategy_catalog_id = sd.id
        AND ug2.group_key = 'NIFTY_100'
        AND b2.runtime_enabled = true
  );

-- Catalog poll defaults to 60s; per-binding throttle should match (not 5s).
UPDATE strategy_runtime_bindings b
SET scan_interval_seconds = 60,
    updated_at            = NOW()
FROM strategy_definitions sd
WHERE b.strategy_catalog_id = sd.id
  AND sd.strategy_key IN (
      'NSE_SPIKE_DETECTION', 'EARLY_BREAKOUT', 'VWAP_BOUNCE', 'GAP_FILL', 'SECTOR_LAGGARD'
  )
  AND b.runtime_enabled = true
  AND b.scan_interval_seconds < 60;
