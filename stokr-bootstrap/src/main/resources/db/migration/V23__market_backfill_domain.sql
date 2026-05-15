CREATE TABLE market_backfill_jobs (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    requested_by_user_id UUID,
    broker_source VARCHAR(32) NOT NULL,
    symbol_group VARCHAR(32) NOT NULL,
    timeframe VARCHAR(16) NOT NULL,
    range_start TIMESTAMPTZ NOT NULL,
    range_end TIMESTAMPTZ NOT NULL,
    status VARCHAR(24) NOT NULL,
    total_symbols INTEGER NOT NULL DEFAULT 0,
    processed_symbols INTEGER NOT NULL DEFAULT 0,
    total_candles_fetched BIGINT NOT NULL DEFAULT 0,
    total_gaps INTEGER NOT NULL DEFAULT 0,
    failure_count INTEGER NOT NULL DEFAULT 0,
    latest_candle_at TIMESTAMPTZ,
    throughput_candles_per_sec NUMERIC(24, 6),
    repair_state VARCHAR(24),
    cancelled BOOLEAN NOT NULL DEFAULT FALSE,
    message VARCHAR(500)
);

CREATE INDEX idx_market_backfill_jobs_created ON market_backfill_jobs (created_at DESC);
CREATE INDEX idx_market_backfill_jobs_status ON market_backfill_jobs (status, updated_at DESC);

CREATE TABLE market_backfill_job_symbols (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    job_id UUID NOT NULL REFERENCES market_backfill_jobs (id),
    symbol VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    candles_fetched BIGINT NOT NULL DEFAULT 0,
    latest_candle_at TIMESTAMPTZ,
    gap_count INTEGER NOT NULL DEFAULT 0,
    failure_count INTEGER NOT NULL DEFAULT 0,
    message VARCHAR(500)
);

CREATE INDEX idx_market_backfill_job_symbols_job ON market_backfill_job_symbols (job_id, updated_at DESC);
CREATE UNIQUE INDEX ux_market_backfill_job_symbol_active
    ON market_backfill_job_symbols (job_id, symbol)
    WHERE deleted = FALSE;

CREATE TABLE market_backfill_gaps (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    job_id UUID NOT NULL REFERENCES market_backfill_jobs (id),
    symbol VARCHAR(64) NOT NULL,
    timeframe VARCHAR(16) NOT NULL,
    gap_start TIMESTAMPTZ NOT NULL,
    gap_end TIMESTAMPTZ NOT NULL,
    missing_bars INTEGER NOT NULL DEFAULT 0,
    repaired BOOLEAN NOT NULL DEFAULT FALSE,
    message VARCHAR(500)
);

CREATE INDEX idx_market_backfill_gaps_job ON market_backfill_gaps (job_id, repaired, updated_at DESC);

CREATE TABLE market_backfill_failures (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    job_id UUID NOT NULL REFERENCES market_backfill_jobs (id),
    symbol VARCHAR(64),
    failure_code VARCHAR(64) NOT NULL,
    message VARCHAR(500),
    retryable BOOLEAN NOT NULL DEFAULT TRUE,
    attempt_count INTEGER NOT NULL DEFAULT 1,
    last_occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_market_backfill_failures_job ON market_backfill_failures (job_id, updated_at DESC);
