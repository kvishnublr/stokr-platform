-- V005: Alter portfolio_positions - Add ownership tracking
-- Purpose: Track who closed each position and how (STRATEGY, USER, BROKER, RISK, KILLSWITCH)

ALTER TABLE portfolio_positions ADD COLUMN (
    -- Position state machine
    position_state VARCHAR(32) DEFAULT 'OPEN',
        -- OPEN, CLOSING, CLOSED, ZOMBIE, GHOST

    -- Ownership
    owner_type VARCHAR(32),
        -- STRATEGY, MANUAL, BROKER, RISK, KILLSWITCH, NULL

    -- Exit source
    exit_source VARCHAR(32),
        -- STRATEGY_SIGNAL, MANUAL_BROKER, MANUAL_TERMINAL, BROKER_LIQUIDATION, RISK_CIRCUIT, KILL_SWITCH

    -- Causation linkage
    entry_signal_id UUID,
    exit_signal_id UUID,
    entry_order_id UUID,
    exit_order_id UUID,
    entry_execution_id UUID,
    exit_execution_id UUID,

    -- Manual suppression
    manual_suppression_active BOOLEAN DEFAULT FALSE,
    suppression_reason VARCHAR(255),
    suppressed_until TIMESTAMP WITH TIME ZONE,

    -- Lifecycle timestamps
    position_opened_at TIMESTAMP WITH TIME ZONE,
    position_closed_at TIMESTAMP WITH TIME ZONE,
    position_state_updated_at TIMESTAMP WITH TIME ZONE,

    -- Reconciliation state
    last_reconciliation_at TIMESTAMP WITH TIME ZONE,
    reconciliation_status VARCHAR(32),
        -- SYNCED, PENDING, DIVERGED, GHOST, ORPHAN

    -- Broker linkage
    broker_position_id VARCHAR(255),
    broker_order_id VARCHAR(255)
);

-- Indices for fast lookups
CREATE INDEX idx_portfolio_position_state ON portfolio_positions(position_state);
CREATE INDEX idx_portfolio_owner_type ON portfolio_positions(owner_type);
CREATE INDEX idx_portfolio_exit_source ON portfolio_positions(exit_source);
CREATE INDEX idx_portfolio_manual_suppression ON portfolio_positions(manual_suppression_active);
CREATE INDEX idx_portfolio_entry_signal ON portfolio_positions(entry_signal_id);
CREATE INDEX idx_portfolio_exit_signal ON portfolio_positions(exit_signal_id);
CREATE INDEX idx_portfolio_reconciliation_status ON portfolio_positions(reconciliation_status);

-- Comments
COMMENT ON COLUMN portfolio_positions.position_state IS 'Current state: OPEN, CLOSING, CLOSED, ZOMBIE, GHOST';
COMMENT ON COLUMN portfolio_positions.owner_type IS 'Who owns/closed it: STRATEGY, MANUAL, BROKER, RISK, KILLSWITCH';
COMMENT ON COLUMN portfolio_positions.exit_source IS 'How it was closed: signal, manual, broker liquidation, risk circuit';
COMMENT ON COLUMN portfolio_positions.manual_suppression_active IS 'If true, no more exit attempts allowed';
COMMENT ON COLUMN portfolio_positions.reconciliation_status IS 'Broker sync state: SYNCED, PENDING, DIVERGED, GHOST, ORPHAN';
