CREATE TABLE candle_data (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    timeframe VARCHAR(20) NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL,
    open DECIMAL(19, 4) NOT NULL,
    high DECIMAL(19, 4) NOT NULL,
    low DECIMAL(19, 4) NOT NULL,
    close DECIMAL(19, 4) NOT NULL,
    volume BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_candle_symbol_timeframe ON candle_data(symbol, timeframe);
CREATE INDEX idx_candle_timestamp ON candle_data(timestamp);
