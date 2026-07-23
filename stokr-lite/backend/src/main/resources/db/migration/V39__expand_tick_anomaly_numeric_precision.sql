ALTER TABLE tick_anomalies
    ALTER COLUMN magnitude TYPE NUMERIC(18,4),
    ALTER COLUMN vwap_deviation TYPE NUMERIC(18,4);
