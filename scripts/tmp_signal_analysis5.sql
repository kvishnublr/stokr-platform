\echo '=== top reject reasons vishnualgo today ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
),
u AS (SELECT id FROM auth_users WHERE username = 'vishnualgo')
SELECT LEFT(o.reject_reason, 80) AS reason, COUNT(*)
FROM oms_orders o, bounds, u
WHERE o.user_id = u.id AND o.deleted = FALSE AND o.state = 'REJECTED'
  AND o.created_at >= bounds.start_utc
GROUP BY 1 ORDER BY 2 DESC LIMIT 15;

\echo '=== orders by signal provenance (join) ==='
WITH bounds AS (
  SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata') AS start_utc
)
SELECT ss.signal_source, o.state, COUNT(*)
FROM oms_orders o
JOIN strategy_signals ss ON ss.id = o.signal_id
, bounds
WHERE o.deleted = FALSE AND o.created_at >= bounds.start_utc
GROUP BY 1, 2 ORDER BY 3 DESC LIMIT 20;
