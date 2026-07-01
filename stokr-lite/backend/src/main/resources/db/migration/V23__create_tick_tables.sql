-- Tick-level data for sub-minute anomaly detection
CREATE TABLE IF NOT EXISTS tick_data (
    id              BIGSERIAL PRIMARY KEY,
    symbol          VARCHAR(20) NOT NULL,
    exchange_ts     TIMESTAMP NOT NULL,        -- timestamp from exchange
    received_ts     TIMESTAMP NOT NULL,        -- when our server received it
    ltp             NUMERIC(12,2) NOT NULL,
    volume          BIGINT NOT NULL DEFAULT 0,  -- total volume at this tick
    minute_volume   BIGINT NOT NULL DEFAULT 0,  -- volume since last minute boundary (accumulated in current candle)
    buy_quantity    BIGINT DEFAULT 0,
    sell_quantity   BIGINT DEFAULT 0,
    change_pct      NUMERIC(8,4) DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tick_symbol_ts ON tick_data (symbol, exchange_ts DESC);
CREATE INDEX IF NOT EXISTS idx_tick_created_at ON tick_data (created_at);

-- Tick-based 1-min candles (true high/low from tick aggregation)
CREATE TABLE IF NOT EXISTS tick_candle_data (
    id              BIGSERIAL PRIMARY KEY,
    symbol          VARCHAR(20) NOT NULL,
    timeframe       VARCHAR(10) NOT NULL DEFAULT '1min',
    timestamp       TIMESTAMP NOT NULL,
    open            NUMERIC(12,2) NOT NULL,
    high            NUMERIC(12,2) NOT NULL,
    low             NUMERIC(12,2) NOT NULL,
    close           NUMERIC(12,2) NOT NULL,
    volume          BIGINT NOT NULL DEFAULT 0,
    trade_count     INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (symbol, timeframe, timestamp)
);

-- Detected anomalies (sudden moves)
CREATE TABLE IF NOT EXISTS tick_anomalies (
    id              BIGSERIAL PRIMARY KEY,
    symbol          VARCHAR(20) NOT NULL,
    anomaly_type    VARCHAR(30) NOT NULL,       -- VOLUME_SURGE, PRICE_ACCEL, VWAP_DEVIATION, NARROW_BREAKOUT
    detected_ts     TIMESTAMP NOT NULL,
    price_at_event  NUMERIC(12,2) NOT NULL,
    magnitude       NUMERIC(12,4) NOT NULL,     -- how strong the anomaly was (z-score, multiple, etc.)
    volume_at_event BIGINT NOT NULL DEFAULT 0,
    vwap_deviation  NUMERIC(12,4) DEFAULT 0,
    direction       VARCHAR(10) DEFAULT 'UP',   -- UP / DOWN
    confirmed       BOOLEAN DEFAULT FALSE,      -- was followed by continuation?
    resolved        BOOLEAN DEFAULT FALSE,      -- has the anomaly been acted upon?
    signal_id       BIGINT DEFAULT NULL,        -- if a strategy signal was generated
    metadata        JSONB DEFAULT '{}',
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_anomaly_type_ts ON tick_anomalies (symbol, anomaly_type, detected_ts DESC);
CREATE INDEX IF NOT EXISTS idx_anomaly_unresolved ON tick_anomalies (resolved, detected_ts) WHERE resolved = FALSE;

-- Keep tick data for 7 days only, tick candles for 90 days, anomalies for 90 days
CREATE OR REPLACE FUNCTION cleanup_tick_tables() RETURNS void AS $$
BEGIN
    DELETE FROM tick_data WHERE created_at < NOW() - INTERVAL '7 days';
    DELETE FROM tick_candle_data WHERE timestamp < NOW() - INTERVAL '90 days';
    DELETE FROM tick_anomalies WHERE created_at < NOW() - INTERVAL '90 days';
END;
$$ LANGUAGE plpgsql;
