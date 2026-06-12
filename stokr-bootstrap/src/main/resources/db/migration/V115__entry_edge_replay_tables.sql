-- V115: Entry-edge replay telemetry + automatic live demotion gate.
--
-- strategy_entry_edge_daily: per-session counterfactual replay of every emitted signal
-- against stored 1m candles — which level was touched first from entry, target or stop,
-- ignoring all exit-engine interference. This is the only metric that separates "bad
-- entries" from "bad exits".
--
-- strategy_edge_demotions: strategies whose rolling entry edge is below the breakeven
-- target-first rate implied by their own risk:reward get force-demoted to PAPER until
-- the edge recovers.

CREATE TABLE IF NOT EXISTS strategy_entry_edge_daily (
    id             BIGSERIAL PRIMARY KEY,
    session_date   DATE NOT NULL,
    strategy_name  VARCHAR(128) NOT NULL,
    signals        INT NOT NULL DEFAULT 0,
    target_first   INT NOT NULL DEFAULT 0,
    sl_first       INT NOT NULL DEFAULT 0,
    unresolved     INT NOT NULL DEFAULT 0,
    avg_rr         NUMERIC(10,4),
    target_first_pct NUMERIC(8,4),
    breakeven_pct  NUMERIC(8,4),
    replay_pnl     NUMERIC(18,4),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_entry_edge_session UNIQUE (strategy_name, session_date)
);

CREATE INDEX IF NOT EXISTS idx_entry_edge_session_date
    ON strategy_entry_edge_daily (session_date DESC, strategy_name);

CREATE TABLE IF NOT EXISTS strategy_edge_demotions (
    id             BIGSERIAL PRIMARY KEY,
    strategy_key   VARCHAR(128) NOT NULL,
    active         BOOLEAN NOT NULL DEFAULT true,
    reason         VARCHAR(512),
    demoted_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    lifted_at      TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_edge_demotion_active
    ON strategy_edge_demotions (strategy_key) WHERE active = true;
