-- OMS prod audit Jun 3 2026
SELECT count(*) AS orders_today FROM oms_orders WHERE deleted=false AND created_at >= CURRENT_DATE;
SELECT count(*) AS orders_last_7d FROM oms_orders WHERE deleted=false AND created_at >= CURRENT_DATE - INTERVAL '7 days';

SELECT date(created_at AT TIME ZONE 'Asia/Kolkata') AS ist_day, execution_mode, count(*)
FROM oms_orders WHERE deleted=false AND created_at >= CURRENT_DATE - INTERVAL '7 days'
GROUP BY 1,2 ORDER BY 1 DESC, 2;

SELECT created_at AT TIME ZONE 'Asia/Kolkata' AS ist_created, execution_mode, state, symbol, strategy_key, reject_reason
FROM oms_orders WHERE deleted=false ORDER BY created_at DESC LIMIT 20;

SELECT block_code, effective_mode, count(*) FROM oms_safety_blocked_orders
WHERE created_at >= CURRENT_DATE GROUP BY 1,2 ORDER BY 3 DESC;

SELECT date(created_at AT TIME ZONE 'Asia/Kolkata') AS ist_day, block_code, count(*)
FROM oms_safety_blocked_orders WHERE created_at >= CURRENT_DATE - INTERVAL '7 days'
GROUP BY 1,2 ORDER BY 1 DESC, 3 DESC;

SELECT user_id, symbol, quantity, updated_at AT TIME ZONE 'Asia/Kolkata' AS ist_updated
FROM portfolio_positions WHERE deleted=false AND quantity <> 0 ORDER BY updated_at DESC;

SELECT count(*) AS live_signals_today FROM strategy_signals WHERE created_at >= CURRENT_DATE AND pipeline = 'LIVE';

SELECT s.id, s.symbol, s.strategy_name, s.created_at AT TIME ZONE 'Asia/Kolkata' AS ist_created
FROM strategy_signals s
LEFT JOIN oms_orders o ON o.signal_id = s.id AND o.deleted=false
WHERE s.created_at >= CURRENT_DATE AND s.pipeline = 'LIVE' AND o.id IS NULL
ORDER BY s.created_at DESC LIMIT 20;
