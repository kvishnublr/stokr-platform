UPDATE deployments SET status = 'PAUSED', mode = 'PAPER', updated_at = NOW() WHERE id IN (11, 12, 13, 15);
SELECT id, strategy_id, name, status, mode, capital FROM deployments d JOIN strategies s ON s.id = d.strategy_id ORDER BY d.id;
