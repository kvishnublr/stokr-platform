-- Phase 1: Per-strategy execution configuration table.
-- Provides typed runtime config for every strategy: sizing mode, capital allocation,
-- risk limits, live/paper toggles. Production-safe defaults: force_fixed_qty=true, fixed_qty=1,
-- live_enabled=false, max_positions=2.

CREATE TABLE strategy_execution_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,

    strategy_id  UUID REFERENCES strategy_definitions(id),
    strategy_key VARCHAR(128) NOT NULL,

    -- Execution routing
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    execution_mode  VARCHAR(16) NOT NULL DEFAULT 'PAPER',  -- PAPER | LIVE | BOTH
    live_enabled    BOOLEAN NOT NULL DEFAULT FALSE,
    paper_enabled   BOOLEAN NOT NULL DEFAULT TRUE,
    telegram_enabled BOOLEAN NOT NULL DEFAULT TRUE,

    -- Capital & sizing  (force_fixed_qty=TRUE → always use fixed_qty=1)
    allocated_capital   NUMERIC(24,8),
    max_positions       INT NOT NULL DEFAULT 2,
    max_trade_quantity  NUMERIC(24,8),
    force_fixed_qty     BOOLEAN NOT NULL DEFAULT TRUE,
    fixed_qty           NUMERIC(24,8) NOT NULL DEFAULT 1,

    -- Risk gates
    daily_loss_limit        NUMERIC(24,8),
    cooldown_minutes        INT NOT NULL DEFAULT 0,
    allow_pyramiding        BOOLEAN NOT NULL DEFAULT FALSE,
    emergency_stop_enabled  BOOLEAN NOT NULL DEFAULT FALSE,
    auto_disable_on_loss    BOOLEAN NOT NULL DEFAULT FALSE,
    live_confirmation_required BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX ux_sec_strategy_key
    ON strategy_execution_configs (strategy_key)
    WHERE deleted = FALSE;

CREATE INDEX idx_sec_key ON strategy_execution_configs (strategy_key)
    WHERE deleted = FALSE;

CREATE INDEX idx_sec_strategy_id ON strategy_execution_configs (strategy_id)
    WHERE deleted = FALSE;
