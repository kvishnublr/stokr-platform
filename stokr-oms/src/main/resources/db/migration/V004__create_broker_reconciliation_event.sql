-- V004: Create broker_reconciliation_event table
-- Purpose: Track reconciliation actions (detections and resolutions)
-- Used for: Complete visibility into broker-OMS sync state

CREATE TABLE broker_reconciliation_event (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    reconciliation_cycle_id UUID,

    -- What happened
    event_type VARCHAR(32) NOT NULL,
        -- BROKER_POSITION_CLOSED, BROKER_POSITION_OPENED, QUANTITY_MISMATCH, ORPHAN_DETECTED, GHOST_DETECTED

    symbol VARCHAR(64) NOT NULL,

    -- The mismatch
    broker_quantity NUMERIC(24, 8),
    oms_quantity NUMERIC(24, 8),
    quantity_mismatch NUMERIC(24, 8),

    -- Links
    broker_position_id VARCHAR(255),
    oms_position_id UUID,

    -- How we fixed it
    resolution_action VARCHAR(32),
        -- SYNTHETIC_EXIT_CREATED, POSITION_UPDATED, LIQUIDATION_INITIATED, GHOST_REMOVED

    resolution_status VARCHAR(32),
        -- PENDING, RESOLVED, FAILED

    -- Timestamps
    detected_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    resolved_at TIMESTAMP WITH TIME ZONE,

    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (oms_position_id) REFERENCES portfolio_positions(id) ON DELETE SET NULL
);

-- Indices
CREATE INDEX idx_reconciliation_event_user ON broker_reconciliation_event(user_id);
CREATE INDEX idx_reconciliation_event_type ON broker_reconciliation_event(event_type);
CREATE INDEX idx_reconciliation_event_symbol ON broker_reconciliation_event(symbol);
CREATE INDEX idx_reconciliation_event_status ON broker_reconciliation_event(resolution_status);
CREATE INDEX idx_reconciliation_event_detected_at ON broker_reconciliation_event(detected_at);

-- Comments
COMMENT ON TABLE broker_reconciliation_event IS 'Audit trail of broker-OMS reconciliation events';
COMMENT ON COLUMN broker_reconciliation_event.event_type IS 'Type of mismatch detected: closure, opening, qty mismatch, orphan, ghost';
COMMENT ON COLUMN broker_reconciliation_event.resolution_action IS 'How the mismatch was resolved';
