SELECT strategy_name, symbol, created_at, outcome_status, lifecycle_status, pipeline
FROM strategy_signals
WHERE strategy_name IN ('ADV_CASH', 'GAP_FILL')
  AND created_at >= CURRENT_DATE
ORDER BY created_at DESC
LIMIT 15;

SELECT strategy_key, execution_mode, enabled
FROM strategy_execution_config
WHERE strategy_key IN ('ADV_CASH', 'GAP_FILL');

SELECT block_code, COUNT(*)
FROM oms_safety_blocked_orders
WHERE created_at >= CURRENT_DATE
GROUP BY block_code
ORDER BY COUNT(*) DESC;
