SELECT strategy_key, enabled FROM strategy_definitions WHERE deleted = false ORDER BY 1;
SELECT sc.strategy_key, b.group_key, b.runtime_enabled
FROM strategy_runtime_bindings b
JOIN strategy_definitions sc ON sc.id = b.strategy_catalog_id
WHERE b.deleted = false
ORDER BY 1, 2;
