SELECT d.id, d.strategy_id, s.name, d.status, d.mode, d.capital FROM deployments d JOIN strategies s ON s.id = d.strategy_id ORDER BY d.id;
