-- PR-5: PAPER execution mode width, async backtest jobs, throttle index.

ALTER TABLE oms_orders ALTER COLUMN execution_mode TYPE VARCHAR(32);

CREATE TABLE IF NOT EXISTS backtest_jobs (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    user_id UUID NOT NULL,
    run_id UUID REFERENCES backtest_runs (id),
    status VARCHAR(32) NOT NULL,
    progress INTEGER NOT NULL DEFAULT 0,
    total_bars INTEGER NOT NULL DEFAULT 0,
    message TEXT,
    correlation_id VARCHAR(64),
    request_json TEXT NOT NULL,
    metadata_schema_version INTEGER,
    strategy_definition_version BIGINT,
    cancelled BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_backtest_jobs_user ON backtest_jobs (user_id) WHERE deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_backtest_jobs_status ON backtest_jobs (status) WHERE deleted = FALSE;
