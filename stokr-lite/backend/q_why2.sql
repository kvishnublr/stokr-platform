SELECT * FROM deployments ORDER BY id;

SELECT column_name, data_type FROM information_schema.columns WHERE table_name = 'deployments' ORDER BY ordinal_position;

SELECT id, name, timeframe, enabled FROM strategies ORDER BY id;
