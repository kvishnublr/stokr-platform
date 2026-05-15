CREATE TABLE market_data_coverage (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    symbol VARCHAR(64) NOT NULL,
    timeframe VARCHAR(16) NOT NULL,
    covered_from TIMESTAMPTZ NOT NULL,
    covered_to TIMESTAMPTZ NOT NULL,
    latest_candle_at TIMESTAMPTZ,
    completeness VARCHAR(24) NOT NULL,
    freshness VARCHAR(24) NOT NULL,
    gaps_present BOOLEAN NOT NULL DEFAULT FALSE,
    gap_count INTEGER NOT NULL DEFAULT 0,
    replay_readiness VARCHAR(32) NOT NULL,
    scanner_readiness VARCHAR(32) NOT NULL,
    completion_pct NUMERIC(12,4),
    last_validation_at TIMESTAMPTZ NOT NULL,
    note VARCHAR(500)
);

CREATE UNIQUE INDEX ux_market_data_coverage_symbol_tf_active
    ON market_data_coverage (symbol, timeframe)
    WHERE deleted = FALSE;

CREATE INDEX idx_market_data_coverage_readiness
    ON market_data_coverage (replay_readiness, scanner_readiness, updated_at DESC);
