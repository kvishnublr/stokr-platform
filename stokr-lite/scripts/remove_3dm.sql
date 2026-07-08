SELECT id, name, description FROM strategies WHERE id = 17;
DELETE FROM strategy_configs WHERE strategy_id = 17;
DELETE FROM strategy_universe_mappings WHERE strategy_id = 17;
DELETE FROM strategies WHERE id = 17;
SELECT id, name FROM strategies ORDER BY id;
