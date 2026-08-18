-- Periodic snapshots of the "Candidates" (not-arbitrage) discovery scan for
-- Vertical/Butterfly/Condor spreads. One row per underlying per strategy per snapshot --
-- a count + the top (highest-POP) candidate's numbers, not every candidate, to stay light.
CREATE TABLE IF NOT EXISTS candidate_snapshots (
    id BIGSERIAL PRIMARY KEY,
    strategy_type VARCHAR(30),
    underlying VARCHAR(20),
    candidate_count INTEGER,
    avg_pop DOUBLE PRECISION,
    top_option_type VARCHAR(5),
    top_strikes VARCHAR(60),
    top_pop DOUBLE PRECISION,
    top_cost_per_lot DOUBLE PRECISION,
    top_max_loss DOUBLE PRECISION,
    top_max_profit DOUBLE PRECISION,
    top_margin_estimate DOUBLE PRECISION,
    snapshot_time TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_candidate_snapshots_lookup
    ON candidate_snapshots(strategy_type, underlying, snapshot_time);
