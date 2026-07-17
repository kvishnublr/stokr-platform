-- Any signals from deployment 11 (OB)?
SELECT count(*) FROM strategy_signals WHERE deployment_id = 11;

-- Check all deployments
SELECT d.id, d.strategy_id, d.status, d.mode, s.name as strategy_name
FROM deployments d
JOIN strategies s ON d.strategy_id = s.id
ORDER BY d.id;
