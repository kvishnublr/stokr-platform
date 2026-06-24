-- Ensure NIFTY_100 universe group exists (safe if already created by StaticUniverseSyncService)
INSERT INTO universe_groups (group_key, display_name, universe_type, exchange, asset_class, segment, instrument_type, auto_managed, enabled)
SELECT 'NIFTY_100', 'NIFTY 100', 'INDEX_CONSTITUENTS', 'NSE', 'EQUITY', 'NSE', 'EQ', true, true
WHERE NOT EXISTS (SELECT 1 FROM universe_groups WHERE group_key = 'NIFTY_100');

-- Map all 5 strategies to NIFTY_100 universe group
INSERT INTO strategy_universe_mappings (strategy_id, universe_group_id, runtime_enabled, max_positions)
SELECT s.id, g.id, true, 2
FROM strategies s, universe_groups g
WHERE g.group_key = 'NIFTY_100'
  AND s.id IN (1, 2, 3, 4, 5)
  AND NOT EXISTS (
    SELECT 1 FROM strategy_universe_mappings m
    WHERE m.strategy_id = s.id AND m.universe_group_id = g.id
  );
