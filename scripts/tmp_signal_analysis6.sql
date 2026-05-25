\echo '=== REPLAY FILLED orders execution_mode ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
)
SELECT o.execution_mode, COUNT(*)
FROM oms_orders o
JOIN strategy_signals ss ON ss.id = o.signal_id
, bounds
WHERE o.deleted = FALSE AND o.created_at >= bounds.start_utc
  AND ss.signal_source = 'REPLAY' AND o.state = 'FILLED'
GROUP BY 1;

\echo '=== vishnualgo orders by signal source ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
),
u AS (SELECT id FROM auth_users WHERE username = 'vishnualgo')
SELECT ss.signal_source, o.state, COUNT(*)
FROM oms_orders o
JOIN strategy_signals ss ON ss.id = o.signal_id
, bounds, u
WHERE o.user_id = u.id AND o.deleted = FALSE AND o.created_at >= bounds.start_utc
GROUP BY 1, 2 ORDER BY 3 DESC;
