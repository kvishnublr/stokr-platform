-- V001: Create position_lifecycle_audit table
-- Purpose: Track every position state change (black box flight recorder)
-- Tracks: who changed it, why, when, what changed, links to orders/executions

CREATE TABLE position_lifecycle_audit (
    id UUID PRIMARY KEY,
    position_id UUID NOT NULL,
    user_id UUID NOT NULL,
    symbol VARCHAR(64) NOT NULL,

    -- State change
    old_state VARCHAR(32),
    new_state VARCHAR(32),

    -- Ownership tracking
    owner_type VARCHAR(32),
        -- STRATEGY, MANUAL, BROKER, RISK, KILLSWITCH, SYSTEM

    -- Exit source
    exit_source VARCHAR(32),
        -- STRATEGY_SIGNAL, MANUAL_BROKER, MANUAL_TERMINAL, BROKER_LIQUIDATION, RISK_CIRCUIT, KILL_SWITCH

    -- Quantity tracking
    old_quantity NUMERIC(24, 8),
    new_quantity NUMERIC(24, 8),
    change_amount NUMERIC(24, 8),

    -- Links to related records (causation)
    entry_signal_id UUID,
    exit_signal_id UUID,
    entry_order_id UUID,
    exit_order_id UUID,
    entry_execution_id UUID,
    exit_execution_id UUID,

    -- Who triggered this change
    triggered_by VARCHAR(32),
        -- USER, SYSTEM, BROKER, STRATEGY

    -- Why it changed
    reason TEXT,

    -- Timestamps
    occurred_at TIMESTAMP WITH TIME ZONE,
    recorded_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

    -- Foreign keys
    FOREIGN KEY (position_id) REFERENCES portfolio_positions(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Indices for fast lookups
CREATE INDEX idx_position_lifecycle_position ON position_lifecycle_audit(position_id);
CREATE INDEX idx_position_lifecycle_user ON position_lifecycle_audit(user_id);
CREATE INDEX idx_position_lifecycle_symbol ON position_lifecycle_audit(symbol);
CREATE INDEX idx_position_lifecycle_owner ON position_lifecycle_audit(owner_type);
CREATE INDEX idx_position_lifecycle_exit_source ON position_lifecycle_audit(exit_source);
CREATE INDEX idx_position_lifecycle_occurred_at ON position_lifecycle_audit(occurred_at);
CREATE INDEX idx_position_lifecycle_recorded_at ON position_lifecycle_audit(recorded_at);
CREATE INDEX idx_position_lifecycle_triggered_by ON position_lifecycle_audit(triggered_by);

-- Partition by month for performance on large tables
CREATE TABLE position_lifecycle_audit_202606 PARTITION OF position_lifecycle_audit
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');

-- Comments for documentation
COMMENT ON TABLE position_lifecycle_audit IS 'Complete audit trail of all position state changes';
COMMENT ON COLUMN position_lifecycle_audit.owner_type IS 'Who owns/closed the position: STRATEGY, MANUAL, BROKER, RISK, KILLSWITCH';
COMMENT ON COLUMN position_lifecycle_audit.exit_source IS 'How position was exited: signal, manual, broker liquidation, risk circuit';
COMMENT ON COLUMN position_lifecycle_audit.triggered_by IS 'Who triggered the change: USER, SYSTEM, BROKER, STRATEGY';
