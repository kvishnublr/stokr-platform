-- Cash equity positions for Cash Surge / Cash Swing (directional NSE cash-market buys,
-- not options -- separate structure from live_positions which is options-only).
CREATE TABLE cash_positions (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(64) NOT NULL,
    strategy_type VARCHAR(32) NOT NULL,
    side VARCHAR(8) NOT NULL DEFAULT 'BUY',
    quantity INTEGER NOT NULL,
    entry_price NUMERIC(12,2),
    exit_price NUMERIC(12,2),
    target_price NUMERIC(12,2),
    stop_loss_price NUMERIC(12,2),
    current_pnl NUMERIC(12,2),
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    broker VARCHAR(32),
    order_id VARCHAR(64),
    error_message TEXT,
    entered_at TIMESTAMP,
    exited_at TIMESTAMP,
    created_at TIMESTAMP
);

CREATE INDEX idx_cash_positions_status ON cash_positions(status);
CREATE INDEX idx_cash_positions_symbol ON cash_positions(symbol);
