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

    -- Links to related records (causation)
    signal_id UUID,
    order_id UUID,
    execution_id UUID,
    broker_position_id VARCHAR(255),
    reconciliation_id UUID,

    -- Who triggered this change
    triggered_by VARCHAR(32),
        -- USER, SYSTEM, BROKER, STRATEGY

    -- Why it changed
    reason TEXT,
    source_system VARCHAR(32),
        -- OMS, BROKER, TERMINAL, RISK, RECONCILIATION, STRATEGY_RUNTIME

    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    event_time TIMESTAMP WITH TIME ZONE,

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
CREATE INDEX idx_position_lifecycle_time ON position_lifecycle_audit(event_time);
CREATE INDEX idx_position_lifecycle_source_system ON position_lifecycle_audit(source_system);

-- Partition by month for performance on large tables
CREATE TABLE position_lifecycle_audit_202606 PARTITION OF position_lifecycle_audit
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');

-- Comments for documentation
COMMENT ON TABLE position_lifecycle_audit IS 'Complete audit trail of all position state changes';
COMMENT ON COLUMN position_lifecycle_audit.owner_type IS 'Who owns/closed the position: STRATEGY, MANUAL, BROKER, RISK, KILLSWITCH';
COMMENT ON COLUMN position_lifecycle_audit.exit_source IS 'How position was exited: signal, manual, broker liquidation, risk circuit';
COMMENT ON COLUMN position_lifecycle_audit.triggered_by IS 'Who triggered the change: USER, SYSTEM, BROKER, STRATEGY';
