CREATE TABLE IF NOT EXISTS live_positions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    opportunity_id BIGINT,
    underlying VARCHAR(20),
    strike INTEGER,
    action VARCHAR(50),
    strategy_type VARCHAR(50),
    ce_symbol VARCHAR(30),
    pe_symbol VARCHAR(30),
    fut_symbol VARCHAR(30),
    lots INTEGER DEFAULT 1,
    lot_size INTEGER DEFAULT 50,
    ce_entry_price NUMERIC(12,2),
    pe_entry_price NUMERIC(12,2),
    fut_entry_price NUMERIC(12,2),
    ce_exit_price NUMERIC(12,2),
    pe_exit_price NUMERIC(12,2),
    fut_exit_price NUMERIC(12,2),
    entry_cost NUMERIC(12,2),
    current_pnl NUMERIC(12,2),
    target_edge NUMERIC(12,2),
    status VARCHAR(20) DEFAULT 'OPEN',
    ce_order_id VARCHAR(30),
    pe_order_id VARCHAR(30),
    fut_order_id VARCHAR(30),
    error_message TEXT,
    entered_at TIMESTAMP,
    exited_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_live_positions_status ON live_positions(status);
CREATE INDEX IF NOT EXISTS idx_live_positions_user ON live_positions(user_id, status);
