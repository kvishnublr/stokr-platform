-- V006: Alter oms_orders - Add signal linkage
-- Purpose: Link every LIVE order to its triggering signal (no orphans)
-- Requirement: signal_id is MANDATORY for LIVE fills

ALTER TABLE oms_orders ADD COLUMN (
    -- CRITICAL: Every LIVE order must have signal_id
    signal_id UUID,
        -- REQUIRED for LIVE execution mode (NOT unique - retries allowed)

    execution_mode_confirmed VARCHAR(32),
        -- Must match execution_mode: LIVE, PAPER, SIMULATION

    -- Broker reconciliation
    broker_order_id VARCHAR(255) UNIQUE,
    broker_order_status VARCHAR(32),
    broker_rejection_reason TEXT,

    -- Duplicate detection
    duplicate_of_order_id UUID,
    is_duplicate BOOLEAN DEFAULT FALSE
);

-- Add foreign key for signal linkage
ALTER TABLE oms_orders
ADD CONSTRAINT fk_oms_orders_signal_id
FOREIGN KEY (signal_id) REFERENCES strategy_signals(id) ON DELETE SET NULL;

-- Indices
CREATE INDEX idx_orders_signal ON oms_orders(signal_id);
CREATE INDEX idx_orders_broker_id ON oms_orders(broker_order_id);
CREATE INDEX idx_orders_is_duplicate ON oms_orders(is_duplicate);
CREATE INDEX idx_orders_execution_mode_confirmed ON oms_orders(execution_mode_confirmed);

-- Comments
COMMENT ON COLUMN oms_orders.signal_id IS 'REQUIRED for LIVE mode - links order to triggering signal';
COMMENT ON COLUMN oms_orders.broker_order_id IS 'Broker assigned order ID for reconciliation';
COMMENT ON COLUMN oms_orders.is_duplicate IS 'If true, this order is a duplicate of duplicate_of_order_id';
