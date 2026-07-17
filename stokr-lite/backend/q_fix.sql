UPDATE deployments SET status = 'ACTIVE' WHERE id = 11;
SELECT id, strategy_id, status, mode, capital FROM deployments ORDER BY id;
