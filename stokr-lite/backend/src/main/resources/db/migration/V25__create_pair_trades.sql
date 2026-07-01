CREATE TABLE IF NOT EXISTS pair_trades (
    id BIGSERIAL PRIMARY KEY,
    pair_key VARCHAR(50) NOT NULL,
    symbol_a VARCHAR(20) NOT NULL,
    symbol_b VARCHAR(20) NOT NULL,
    direction VARCHAR(20) NOT NULL,
    entry_time TIMESTAMP NOT NULL,
    exit_time TIMESTAMP,
    daily_mean DOUBLE PRECISION,
    daily_std DOUBLE PRECISION,
    entry_z DOUBLE PRECISION NOT NULL,
    exit_z DOUBLE PRECISION,
    entry_price_a DOUBLE PRECISION,
    entry_price_b DOUBLE PRECISION,
    exit_price_a DOUBLE PRECISION,
    exit_price_b DOUBLE PRECISION,
    qty_a INT DEFAULT 1,
    qty_b INT DEFAULT 1,
    leg_a_pnl DOUBLE PRECISION DEFAULT 0,
    leg_b_pnl DOUBLE PRECISION DEFAULT 0,
    net_pnl DOUBLE PRECISION DEFAULT 0,
    exit_reason VARCHAR(30),
    status VARCHAR(20) DEFAULT 'OPEN',
    mode VARCHAR(10) DEFAULT 'PAPER',
    order_id_a VARCHAR(50),
    order_id_b VARCHAR(50),
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pair_trades_status ON pair_trades(status);
CREATE INDEX IF NOT EXISTS idx_pair_trades_entry_time ON pair_trades(entry_time);
CREATE INDEX IF NOT EXISTS idx_pair_trades_pair_key ON pair_trades(pair_key);
