-- V64: Optimize tick cleanup retention policy to 7 days for anomalies to prevent disk bloat
CREATE OR REPLACE FUNCTION cleanup_tick_tables() RETURNS void AS $$
BEGIN
    DELETE FROM tick_data WHERE created_at < NOW() - INTERVAL '7 days';
    DELETE FROM tick_candle_data WHERE timestamp < NOW() - INTERVAL '30 days';
    DELETE FROM tick_anomalies WHERE created_at < NOW() - INTERVAL '7 days';
END;
$$ LANGUAGE plpgsql;