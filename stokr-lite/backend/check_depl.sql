SELECT s.id, s.strategy_type FROM strategies s JOIN deployments d ON d.strategy_id = s.id WHERE d.id = 11;
