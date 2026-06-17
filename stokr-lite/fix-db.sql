DELETE FROM flyway_schema_history WHERE version = '3';
DROP TABLE IF EXISTS strategy_universe_mappings CASCADE;
DROP TABLE IF EXISTS strategy_configs CASCADE;
DROP TABLE IF EXISTS universe_symbols CASCADE;
DROP TABLE IF EXISTS universe_groups CASCADE;
