UPDATE deployments SET status = 'STOPPED' WHERE id IN (6, 14);
SELECT id, strategy_id, capital, mode, status FROM deployments ORDER BY id;
