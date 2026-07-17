CREATE TABLE IF NOT EXISTS option_arb_opportunities (
    id BIGSERIAL PRIMARY KEY,
    scan_time TIMESTAMP NOT NULL DEFAULT NOW(),
    underlying VARCHAR(20) NOT NULL,
    opportunity_type VARCHAR(30) NOT NULL,
    strike INTEGER NOT NULL,
    action VARCHAR(30) NOT NULL,
    legs TEXT NOT NULL,
    description TEXT,
    spot_price NUMERIC(12,2) NOT NULL,
    futures_price NUMERIC(12,2) NOT NULL,
    ce_entry_price NUMERIC(12,2),
    pe_entry_price NUMERIC(12,2),
    ce_bid NUMERIC(12,2),
    ce_ask NUMERIC(12,2),
    pe_bid NUMERIC(12,2),
    pe_ask NUMERIC(12,2),
    edge_points NUMERIC(12,2) NOT NULL,
    edge_after_costs NUMERIC(12,2) NOT NULL,
    confidence NUMERIC(5,1),
    days_to_expiry NUMERIC(5,1),
    expiry_date DATE,
    -- P&L fields (filled after market close)
    status VARCHAR(20) DEFAULT 'OPEN',
    ce_exit_price NUMERIC(12,2),
    pe_exit_price NUMERIC(12,2),
    exit_spot_price NUMERIC(12,2),
    exit_time TIMESTAMP,
    pnl_points NUMERIC(12,2),
    pnl_amount NUMERIC(12,2),
    pnl_after_costs NUMERIC(12,2),
    notes TEXT,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_optionarb_scan_time ON option_arb_opportunities(scan_time DESC);
CREATE INDEX IF NOT EXISTS idx_optionarb_status ON option_arb_opportunities(status);
CREATE INDEX IF NOT EXISTS idx_optionarb_underlying ON option_arb_opportunities(underlying);
