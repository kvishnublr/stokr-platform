\echo '=== OMS orders today vishnualgo ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
),
u AS (SELECT id FROM auth_users WHERE username = 'vishnualgo')
SELECT o.strategy_key, o.execution_mode, o.state, COUNT(*)
FROM oms_orders o, bounds, u
WHERE o.user_id = u.id AND o.deleted = FALSE AND o.created_at >= bounds.start_utc
GROUP BY 1, 2, 3 ORDER BY 4 DESC;

\echo '=== OMS orders today total ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
)
SELECT COUNT(*) FROM oms_orders, bounds WHERE deleted = FALSE AND created_at >= bounds.start_utc;

\echo '=== findRecentForTrader count vishnualgo (all sources) ==='
WITH u AS (SELECT id FROM auth_users WHERE username = 'vishnualgo')
SELECT ss.signal_source, COUNT(*)
FROM strategy_signals ss
LEFT JOIN strategy_instances si ON si.id = ss.instance_id AND si.deleted = FALSE
, u
WHERE ss.deleted = FALSE
  AND ((si.id IS NOT NULL AND si.user_id = u.id) OR ss.user_id = u.id)
GROUP BY 1;

\echo '=== system user id signals ==='
SELECT COUNT(*) FROM strategy_signals ss
WHERE ss.user_id = '33333333-3333-3333-3333-333333333333'::uuid
  AND ss.created_at >= (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata');

\echo '=== BUY/SELL ratio today by source ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
)
SELECT signal_source,
  COUNT(*) FILTER (WHERE signal_type = 'BUY') AS buy,
  COUNT(*) FILTER (WHERE signal_type = 'SELL') AS sell,
  COUNT(*) FILTER (WHERE signal_type = 'EXIT') AS exit
FROM strategy_signals, bounds
WHERE deleted = FALSE AND created_at >= bounds.start_utc
GROUP BY 1 ORDER BY 2 DESC;
