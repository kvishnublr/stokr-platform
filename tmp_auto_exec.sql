CREATE TABLE IF NOT EXISTS option_arb_auto_exec_settings (
    id BIGSERIAL PRIMARY KEY,
    setting_key VARCHAR(100) UNIQUE NOT NULL,
    setting_value TEXT NOT NULL,
    description VARCHAR(500),
    updated_at TIMESTAMP DEFAULT NOW()
);

INSERT INTO option_arb_auto_exec_settings (setting_key, setting_value, description) VALUES
    ('auto_execute_enabled', 'false', 'Enable/disable auto-execution of option arb trades'),
    ('min_edge_after_costs', '500', 'Minimum edge after costs (in INR) to auto-execute'),
    ('max_positions_per_underlying', '2', 'Maximum open positions per underlying'),
    ('max_total_positions', '8', 'Maximum total open positions across all underlyings'),
    ('smart_rollover', 'true', 'When true: if same futures direction, only roll options legs (save brokerage)'),
    ('scan_interval_seconds', '300', 'How often to scan for auto-execution opportunities (seconds)'),
    ('execute_on_startup', 'false', 'When true: scan and execute on server startup (only if auto_execute_enabled=true)')
ON CONFLICT (setting_key) DO NOTHING;

CREATE TABLE IF NOT EXISTS option_arb_executed_trades (
    id BIGSERIAL PRIMARY KEY,
    opportunity_id BIGINT,
    underlying VARCHAR(50) NOT NULL,
    strike INTEGER NOT NULL,
    expiry_date DATE NOT NULL,
    action VARCHAR(50) NOT NULL,
    ce_symbol VARCHAR(50),
    pe_symbol VARCHAR(50),
    fut_symbol VARCHAR(50),
    ce_order_id VARCHAR(50),
    pe_order_id VARCHAR(50),
    fut_order_id VARCHAR(50),
    ce_entry_price DOUBLE PRECISION,
    pe_entry_price DOUBLE PRECISION,
    fut_entry_price DOUBLE PRECISION,
    lot_size INTEGER,
    status VARCHAR(30) DEFAULT 'OPEN',
    closed_at TIMESTAMP,
    close_ce_order_id VARCHAR(50),
    close_pe_order_id VARCHAR(50),
    close_fut_order_id VARCHAR(50),
    close_ce_price DOUBLE PRECISION,
    close_pe_price DOUBLE PRECISION,
    close_fut_price DOUBLE PRECISION,
    pnl_points DOUBLE PRECISION,
    pnl_amount DOUBLE PRECISION,
    rollover_from_id BIGINT,
    notes TEXT,
    executed_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_exec_trades_status ON option_arb_executed_trades(status);
CREATE INDEX IF NOT EXISTS idx_exec_trades_underlying ON option_arb_executed_trades(underlying);
CREATE INDEX IF NOT EXISTS idx_exec_trades_underlying_status ON option_arb_executed_trades(underlying, status);
