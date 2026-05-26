-- Keep only NSE_SPIKE_DETECTION active for catalog scan + toggles.
UPDATE strategy_definitions
SET enabled = false
WHERE deleted = false
  AND strategy_key <> 'NSE_SPIKE_DETECTION';

UPDATE strategy_runtime_bindings b
SET runtime_enabled = false, updated_at = NOW()
FROM strategy_definitions sc
WHERE b.strategy_catalog_id = sc.id
  AND sc.strategy_key <> 'NSE_SPIKE_DETECTION';

UPDATE strategy_runtime_bindings b
SET runtime_enabled = true, updated_at = NOW()
FROM strategy_definitions sc
WHERE b.strategy_catalog_id = sc.id
  AND sc.strategy_key = 'NSE_SPIKE_DETECTION';

UPDATE strategy_definitions
SET enabled = true
WHERE strategy_key = 'NSE_SPIKE_DETECTION' AND deleted = false;

SELECT strategy_key, enabled FROM strategy_definitions WHERE deleted = false ORDER BY 1;
SELECT sc.strategy_key, ug.group_key, b.runtime_enabled
FROM strategy_runtime_bindings b
JOIN strategy_definitions sc ON sc.id = b.strategy_catalog_id
JOIN strategy_universe_groups ug ON ug.id = b.universe_group_id
ORDER BY 1, 2;
