\echo '=== REPLAY PAPER FILLED by user ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
)
SELECT u.username, COUNT(*)
FROM oms_orders o
JOIN strategy_signals ss ON ss.id = o.signal_id
LEFT JOIN auth_users u ON u.id = o.user_id
, bounds
WHERE o.deleted = FALSE AND o.created_at >= bounds.start_utc
  AND ss.signal_source = 'REPLAY' AND o.state = 'FILLED'
GROUP BY 1 ORDER BY 2 DESC LIMIT 10;

\echo '=== hourly REPLAY signal inserts ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
)
SELECT date_trunc('hour', created_at AT TIME ZONE 'Asia/Kolkata') AS h, COUNT(*)
FROM strategy_signals, bounds
WHERE deleted = FALSE AND signal_source = 'REPLAY' AND created_at >= bounds.start_utc
GROUP BY 1 ORDER BY 1;

\echo '=== active bindings (correct table) ==='
SELECT sd.strategy_key, ug.group_key, srb.scan_interval_seconds
FROM strategy_runtime_bindings srb
JOIN strategy_definitions sd ON sd.id = srb.strategy_catalog_id
JOIN strategy_universe_groups ug ON ug.id = srb.universe_group_id
WHERE srb.runtime_enabled = TRUE ORDER BY 1, 2;
