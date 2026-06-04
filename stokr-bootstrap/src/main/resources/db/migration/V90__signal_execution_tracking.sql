-- Signal Execution Tracking Table
-- Tracks complete execution lifecycle from signal generation through broker execution
-- Enables real-time visibility, auto-retry, and compliance audit trail

CREATE TABLE IF NOT EXISTS signal_execution_tracks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    signal_id UUID NOT NULL UNIQUE,
    user_id UUID NOT NULL,
    strategy_key VARCHAR(128) NOT NULL,
    symbol VARCHAR(64) NOT NULL,
    order_id UUID,
    broker_order_id VARCHAR(256),
    status VARCHAR(32) NOT NULL DEFAULT 'GENERATED',
    execution_mode VARCHAR(32),
    broker_vendor VARCHAR(64),
    side VARCHAR(16),
    quantity NUMERIC(24, 8),
    entry_price NUMERIC(24, 8),
    current_step TEXT,
    last_error TEXT,
    failure_reason VARCHAR(512),
    retry_count INTEGER DEFAULT 0,
    last_retry_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL,
    executed_at TIMESTAMPTZ,
    filled_at TIMESTAMPTZ,
    execution_time_ms BIGINT,
    metadata JSONB,
    deleted BOOLEAN DEFAULT FALSE,

    CONSTRAINT fk_set_signal_id
        FOREIGN KEY (signal_id)
        REFERENCES strategy_signals(id) ON DELETE CASCADE
);

-- Indexes for performance
CREATE INDEX idx_set_signal_id ON signal_execution_tracks(signal_id);
CREATE INDEX idx_set_user_id ON signal_execution_tracks(user_id);
CREATE INDEX idx_set_status ON signal_execution_tracks(status);
CREATE INDEX idx_set_created_at ON signal_execution_tracks(created_at DESC);
CREATE INDEX idx_set_strategy_key ON signal_execution_tracks(strategy_key);
CREATE INDEX idx_set_symbol ON signal_execution_tracks(symbol);
CREATE INDEX idx_set_status_retry ON signal_execution_tracks(status, retry_count) WHERE status IN ('VALIDATION_FAILED', 'SIZING_FAILED', 'RISK_FAILED', 'EXPOSURE_FAILED', 'BROKER_TRUTH_FAILED');
CREATE INDEX idx_set_user_created ON signal_execution_tracks(user_id, created_at DESC);
CREATE INDEX idx_set_retry_at ON signal_execution_tracks(last_retry_at) WHERE retry_count < 3 AND status IN ('VALIDATION_FAILED', 'SIZING_FAILED', 'RISK_FAILED');
CREATE INDEX idx_set_deleted ON signal_execution_tracks(deleted) WHERE deleted = FALSE;
CREATE INDEX idx_set_filled_at ON signal_execution_tracks(filled_at) WHERE filled_at IS NOT NULL;
CREATE INDEX idx_set_execution_time ON signal_execution_tracks(execution_time_ms) WHERE execution_time_ms IS NOT NULL;

-- Grant permissions to stokr_user
GRANT SELECT, INSERT, UPDATE ON signal_execution_tracks TO stokr_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO stokr_user;

-- Add comment for documentation
COMMENT ON TABLE signal_execution_tracks IS
'Tracks the complete execution lifecycle of every signal from generation through broker execution.
Enables real-time UI visibility, automatic retry with fallback modes, and audit trail for compliance.
Status flow: GENERATED → DISPATCHED → ORDER_CREATED → SUBMITTED → ACCEPTED → FILLED/REJECTED';
