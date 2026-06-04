SELECT strategy_key, user_id, status, execution_mode, last_heartbeat_at
FROM strategy_instances
WHERE strategy_key IN ('ADV_CASH', 'GAP_FILL')
  AND status = 'RUNNING';

SELECT reason_code, COUNT(*)
FROM risk_events
WHERE created_at >= CURRENT_DATE
GROUP BY reason_code
ORDER BY COUNT(*) DESC
LIMIT 20;
