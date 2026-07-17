SELECT d.id as depl_id, s.name as strategy, s.strategy_type, s.timeframe, d.mode, d.capital, d.status, d.broker_account_id
FROM deployments d JOIN strategies s ON d.strategy_id = s.id
ORDER BY d.id;
