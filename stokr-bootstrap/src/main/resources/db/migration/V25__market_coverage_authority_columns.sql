ALTER TABLE market_data_coverage
    ADD COLUMN IF NOT EXISTS coverage_start TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS coverage_end TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS freshness_age_seconds BIGINT,
    ADD COLUMN IF NOT EXISTS validated_range_start TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS validated_range_end TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS replay_ready BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS scanner_ready BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS partial_coverage BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS stale_state VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN';

UPDATE market_data_coverage
SET coverage_start = COALESCE(coverage_start, covered_from),
    coverage_end = COALESCE(coverage_end, covered_to),
    freshness_age_seconds = COALESCE(freshness_age_seconds, 9223372036854775807),
    validated_range_start = COALESCE(validated_range_start, covered_from),
    validated_range_end = COALESCE(validated_range_end, covered_to),
    replay_ready = COALESCE(replay_ready, replay_readiness = 'READY_FOR_BACKTEST'),
    scanner_ready = COALESCE(scanner_ready, scanner_readiness = 'READY_FOR_SCANNERS'),
    partial_coverage = COALESCE(partial_coverage, completeness IN ('PARTIAL', 'GAPS_PRESENT')),
    stale_state = COALESCE(stale_state, freshness);

CREATE INDEX IF NOT EXISTS idx_market_data_coverage_authority
    ON market_data_coverage (symbol, timeframe, replay_ready, scanner_ready, stale_state, updated_at DESC)
    WHERE deleted = FALSE;
