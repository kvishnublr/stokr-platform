-- V007: Alter oms_executions - Add audit trail
-- Purpose: Mark synthetic executions and link to positions/signals

ALTER TABLE oms_executions ADD COLUMN (
    -- Causation links
    signal_id UUID,
    position_id UUID,

    -- Is this synthetic (created by reconciliation)?
    is_synthetic BOOLEAN DEFAULT FALSE,
    synthetic_reason VARCHAR(255),
        -- "External broker exit detected"

    -- Who created this execution
    execution_owner VARCHAR(32),
        -- BROKER, SYSTEM_SYNTHETIC, RECONCILIATION

    -- Validation status
    validation_status VARCHAR(32)
        -- VALIDATED, SYNTHETIC, PENDING_VALIDATION
);

-- Add foreign keys
ALTER TABLE oms_executions
ADD CONSTRAINT fk_oms_executions_signal_id
FOREIGN KEY (signal_id) REFERENCES strategy_signals(id) ON DELETE SET NULL;

ALTER TABLE oms_executions
ADD CONSTRAINT fk_oms_executions_position_id
FOREIGN KEY (position_id) REFERENCES portfolio_positions(id) ON DELETE SET NULL;

-- Indices
CREATE INDEX idx_executions_signal ON oms_executions(signal_id);
CREATE INDEX idx_executions_position ON oms_executions(position_id);
CREATE INDEX idx_executions_is_synthetic ON oms_executions(is_synthetic);
CREATE INDEX idx_executions_execution_owner ON oms_executions(execution_owner);
CREATE INDEX idx_executions_validation_status ON oms_executions(validation_status);

-- Comments
COMMENT ON COLUMN oms_executions.signal_id IS 'Links execution to triggering signal';
COMMENT ON COLUMN oms_executions.position_id IS 'Links execution to portfolio position';
COMMENT ON COLUMN oms_executions.is_synthetic IS 'If true, created by reconciliation engine (not from broker)';
COMMENT ON COLUMN oms_executions.synthetic_reason IS 'Why this synthetic execution was created';
COMMENT ON COLUMN oms_executions.execution_owner IS 'Who created it: broker, system, or reconciliation';
