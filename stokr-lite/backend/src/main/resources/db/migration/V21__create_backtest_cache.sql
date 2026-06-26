CREATE TABLE IF NOT EXISTS backtest_cache (
    id          BIGSERIAL PRIMARY KEY,
    cache_key   VARCHAR(64) NOT NULL UNIQUE,
    strategy_type VARCHAR(50) NOT NULL,
    universe    VARCHAR(50) NOT NULL,
    date_start  TIMESTAMP NOT NULL,
    date_end    TIMESTAMP NOT NULL,
    brokerage   INT NOT NULL DEFAULT 0,
    result_json TEXT NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_backtest_cache_key ON backtest_cache(cache_key);
