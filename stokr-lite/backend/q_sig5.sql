SELECT deployment_id, count(*) as cnt, min(created_at), max(created_at)
FROM strategy_signals 
GROUP BY deployment_id 
ORDER BY deployment_id;
