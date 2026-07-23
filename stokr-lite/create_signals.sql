CREATE TABLE IF NOT EXISTS signals (
    id BIGSERIAL PRIMARY KEY,
    scan_time TIMESTAMP NOT NULL,
    underlying VARCHAR(20) NOT NULL,
    strike DOUBLE PRECISION NOT NULL,
    action VARCHAR(20) NOT NULL,
    strategy_type VARCHAR(30) NOT NULL DEFAULT 'NORMAL_PARITY',
    spot_price DOUBLE PRECISION,
    futures_price DOUBLE PRECISION,
    ce_price DOUBLE PRECISION,
    pe_price DOUBLE PRECISION,
    ce_bid DOUBLE PRECISION,
    ce_ask DOUBLE PRECISION,
    pe_bid DOUBLE PRECISION,
    pe_ask DOUBLE PRECISION,
    edge_points DOUBLE PRECISION,
    edge_after_costs DOUBLE PRECISION,
    days_to_expiry DOUBLE PRECISION,
    expiry_date DATE,
    ce_symbol VARCHAR(50),
    pe_symbol VARCHAR(50),
    fut_symbol VARCHAR(50),
    status VARCHAR(20) DEFAULT 'ACTIVE',
    notes TEXT,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_signals_scan_time ON signals(scan_time);
CREATE INDEX IF NOT EXISTS idx_signals_underlying ON signals(underlying);
CREATE INDEX IF NOT EXISTS idx_signals_status ON signals(status);
CREATE INDEX IF NOT EXISTS idx_signals_edge ON signals(edge_after_costs DESC);
