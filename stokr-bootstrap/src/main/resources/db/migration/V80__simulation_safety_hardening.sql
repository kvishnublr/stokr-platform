-- Simulation safety: runtime control, run registry, artifact tagging.

CREATE TABLE IF NOT EXISTS simulation_runtime_control (
    id              SMALLINT PRIMARY KEY DEFAULT 1,
    runtime_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    enabled_at      TIMESTAMPTZ,
    enabled_by      UUID,
    CONSTRAINT simulation_runtime_control_single_row CHECK (id = 1)
);

INSERT INTO simulation_runtime_control (id, runtime_enabled)
VALUES (1, FALSE)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS simulation_runs (
    id              UUID PRIMARY KEY,
    scenario        VARCHAR(64) NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    success         BOOLEAN,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ,
    started_by      UUID,
    report_json     TEXT,
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_simulation_runs_started_at ON simulation_runs (started_at DESC);
CREATE INDEX IF NOT EXISTS idx_simulation_runs_scenario ON simulation_runs (scenario);

ALTER TABLE strategy_signals
    ADD COLUMN IF NOT EXISTS is_simulation BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS simulation_run_id UUID,
    ADD COLUMN IF NOT EXISTS simulation_scenario VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_strategy_signals_simulation
    ON strategy_signals (is_simulation, simulation_run_id)
    WHERE deleted = FALSE;

ALTER TABLE oms_orders
    ADD COLUMN IF NOT EXISTS is_simulation BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS simulation_run_id UUID,
    ADD COLUMN IF NOT EXISTS simulation_scenario VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_oms_orders_simulation
    ON oms_orders (is_simulation, simulation_run_id)
    WHERE deleted = FALSE;

ALTER TABLE oms_executions
    ADD COLUMN IF NOT EXISTS is_simulation BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS simulation_run_id UUID,
    ADD COLUMN IF NOT EXISTS simulation_scenario VARCHAR(64);

ALTER TABLE portfolio_positions
    ADD COLUMN IF NOT EXISTS is_simulation BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS simulation_run_id UUID,
    ADD COLUMN IF NOT EXISTS simulation_scenario VARCHAR(64);

ALTER TABLE operational_audit_events
    ADD COLUMN IF NOT EXISTS is_simulation BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS simulation_run_id UUID,
    ADD COLUMN IF NOT EXISTS simulation_scenario VARCHAR(64);
