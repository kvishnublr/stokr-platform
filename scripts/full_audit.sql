SELECT s.id, s.name, s.strategy_type, s.timeframe, s.enabled,
  (SELECT count(*) FROM deployments d WHERE d.strategy_id = s.id) as dep_count,
  (SELECT count(*) FROM strategy_universe_mappings m WHERE m.strategy_id = s.id) as universe_count,
  (SELECT count(*) FROM strategy_configs c WHERE c.strategy_id = s.id) as config_count
FROM strategies s ORDER BY s.id;
