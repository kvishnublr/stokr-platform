-- Count signals per deployment per day
SELECT DATE(created_at AT TIME ZONE 'Asia/Kolkata') as day,
       deployment_id,
       strategy_id,
       COUNT(*),
       status
FROM strategy_signals
WHERE created_at >= '2026-07-14'
GROUP BY day, deployment_id, strategy_id, status
ORDER BY day DESC, deployment_id;
