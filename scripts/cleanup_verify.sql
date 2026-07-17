-- Step 1: Find all strategy IDs to remove
-- KEEP: THREE_RED_DAYS, OVERSOLD_BOUNCE, MORNING_SURGE_REVERSAL, RSI_OVERSOLD, EMA50_DISTANCE
-- REMOVE: everything else

-- List all strategies to verify
SELECT id, name, strategy_type FROM strategies ORDER BY id;

-- Count what will be deleted
SELECT 
  (SELECT count(*) FROM strategy_signals WHERE strategy_id IN (4,16,18,20,24,25,31)) as signals_to_delete,
  (SELECT count(*) FROM deployments WHERE strategy_id IN (4,16,18,20,24,25)) as deployments_to_delete,
  (SELECT count(*) FROM strategy_universe_mappings WHERE strategy_id IN (4,16,18,20,24,25)) as universe_maps_to_delete,
  (SELECT count(*) FROM strategy_configs WHERE strategy_id IN (4,16,18,20,24,25)) as configs_to_delete;
