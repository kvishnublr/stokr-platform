-- Signal Execution Tracking Table
-- Tracks every signal from generation through broker execution

CREATE TABLE signal_execution_tracks (
    id UUID PRIMARY KEY,
    signal_id UUID NOT NULL,
    user_id UUID NOT NULL,
    strategy_key VARCHAR(255) NOT NULL,
    symbol VARCHAR(50) NOT NULL,
    signal_type VARCHAR(50),
    order_id UUID,
    broker_order_id VARCHAR(255),
    status VARCHAR(50) NOT NULL,
    execution_mode VARCHAR(50),
    broker_vendor VARCHAR(100),
    side VARCHAR(10),
    quantity DECIMAL(19, 8),
    entry_price DECIMAL(19, 8),
    current_step VARCHAR(500),
    last_error VARCHAR(2000),
    failure_reason VARCHAR(500),
    retry_count INTEGER DEFAULT 0,
    last_retry_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    executed_at TIMESTAMP WITH TIME ZONE,
    filled_at TIMESTAMP WITH TIME ZONE,
    execution_time_ms BIGINT,
    metadata JSONB,
    deleted BOOLEAN DEFAULT FALSE
);

-- Indexes for fast lookups
CREATE INDEX idx_set_signal_id ON signal_execution_tracks(signal_id);
CREATE INDEX idx_set_user_id ON signal_execution_tracks(user_id);
CREATE INDEX idx_set_status ON signal_execution_tracks(status);
CREATE INDEX idx_set_created_at ON signal_execution_tracks(created_at);
CREATE INDEX idx_set_strategy_key ON signal_execution_tracks(strategy_key);
CREATE INDEX idx_set_symbol ON signal_execution_tracks(symbol);
CREATE INDEX idx_set_status_retry ON signal_execution_tracks(status, retry_count);
CREATE INDEX idx_set_user_created ON signal_execution_tracks(user_id, created_at DESC);

-- Foreign key constraints
ALTER TABLE signal_execution_tracks
ADD CONSTRAINT fk_set_signal_id
FOREIGN KEY (signal_id)
REFERENCES strategy_signals(id) ON DELETE CASCADE;

ALTER TABLE signal_execution_tracks
ADD CONSTRAINT fk_set_user_id
FOREIGN KEY (user_id)
REFERENCES users(id) ON DELETE CASCADE;

-- Grant permissions to stokr_user
GRANT SELECT, INSERT, UPDATE ON signal_execution_tracks TO stokr_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO stokr_user;

-- Add comment for documentation
COMMENT ON TABLE signal_execution_tracks IS
'Tracks the complete execution lifecycle of every signal from generation through broker execution.
Enables real-time UI visibility, automatic retry with fallback modes, and audit trail for compliance.
Status flow: GENERATED → DISPATCHED → ORDER_CREATED → SUBMITTED → ACCEPTED → FILLED/REJECTED';

COMMENT ON COLUMN signal_execution_tracks.status IS
'Execution status: GENERATED, DISPATCHED, VALIDATION_FAILED, SIZING_FAILED, RISK_FAILED, EXPOSURE_FAILED,
BROKER_TRUTH_FAILED, ORDER_CREATED, SUBMITTED, ACCEPTED, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED,
TIMEOUT, RETRYING, COMPLETED';

COMMENT ON COLUMN signal_execution_tracks.execution_mode IS
'Execution mode: LIVE (real broker), PAPER (simulated), BOTH (dual execution), DRY_RUN (test)';

COMMENT ON COLUMN signal_execution_tracks.broker_vendor IS
'Broker API vendor: ZERODHA (primary), SIM (paper trading simulator)';
