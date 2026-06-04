SELECT count(*) AS adv_cash_orders_today
FROM oms_orders
WHERE strategy_key = 'ADV_CASH' AND created_at >= CURRENT_DATE;

SELECT count(*) AS gap_fill_orders_today
FROM oms_orders
WHERE strategy_key = 'GAP_FILL' AND created_at >= CURRENT_DATE;

SELECT strategy_key, execution_mode, state, symbol, created_at
FROM oms_orders
WHERE strategy_key IN ('ADV_CASH', 'GAP_FILL')
  AND created_at >= CURRENT_DATE
ORDER BY created_at DESC
LIMIT 15;

SELECT block_code, effective_mode, COUNT(*)
FROM oms_safety_blocked_orders
WHERE created_at >= CURRENT_DATE
GROUP BY block_code, effective_mode;
