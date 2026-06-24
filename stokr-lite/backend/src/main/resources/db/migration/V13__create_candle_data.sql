CREATE TABLE IF NOT EXISTS candle_data (
    id              BIGSERIAL PRIMARY KEY,
    symbol          VARCHAR(50)  NOT NULL,
    timeframe       VARCHAR(20)  NOT NULL,
    timestamp       TIMESTAMP    NOT NULL,
    open            DECIMAL(15,4) NOT NULL,
    high            DECIMAL(15,4) NOT NULL,
    low             DECIMAL(15,4) NOT NULL,
    close           DECIMAL(15,4) NOT NULL,
    volume          BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_candle_symbol_timeframe ON candle_data(symbol, timeframe);
CREATE INDEX IF NOT EXISTS idx_candle_timestamp ON candle_data(timestamp);
