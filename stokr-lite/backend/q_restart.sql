UPDATE deployments SET status = 'ACTIVE' WHERE status = 'STOPPED';
SELECT id, strategy_id, status, mode, capital FROM deployments ORDER BY id;
