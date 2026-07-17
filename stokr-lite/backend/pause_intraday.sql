UPDATE deployments SET status = 'PAUSED' WHERE id IN (6, 7, 8);
SELECT id, strategy_id, status FROM deployments WHERE id IN (6, 7, 8);
