UPDATE deployments SET capital = 25000.00 WHERE id IN (11, 12, 13);
SELECT id, strategy_id, capital, mode, status FROM deployments ORDER BY id;
