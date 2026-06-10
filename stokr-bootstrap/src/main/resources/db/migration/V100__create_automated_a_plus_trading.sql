-- Automated A+ Setup Trading Strategy
-- Tracks automated entry/exit for A+ setups (aiScore >= 85)

CREATE TABLE IF NOT EXISTS automated_a_plus_trades (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(50) NOT NULL,
    entry_price NUMERIC(18, 4) NOT NULL,
    entry_time TIMESTAMP NOT NULL,
    entry_ai_score INTEGER NOT NULL,
    current_ai_score INTEGER,
    side VARCHAR(10) NOT NULL CHECK (side IN ('BUY', 'SELL')),
    quantity INTEGER NOT NULL,
    status VARCHAR(50) NOT NULL CHECK (status IN ('ACTIVE', 'EXIT_PENDING', 'EXITED', 'CANCELLED')),
    exit_trigger VARCHAR(100),
    exit_price NUMERIC(18, 4),
    exit_time TIMESTAMP,
    pnl NUMERIC(18, 4),
    pnl_pct NUMERIC(10, 4),

    -- Exit criteria tracking
    ai_score_drop_reason BOOLEAN DEFAULT FALSE,
    opposite_signal_triggered BOOLEAN DEFAULT FALSE,
    hard_sl_hit BOOLEAN DEFAULT FALSE,
    hard_tp_hit BOOLEAN DEFAULT FALSE,
    market_close_exit BOOLEAN DEFAULT FALSE,

    -- OMS integration
    entry_order_id VARCHAR(100),
    exit_order_id VARCHAR(100),
    entry_execution_status VARCHAR(50),
    exit_execution_status VARCHAR(50),

    trader_id UUID,
    strategy_name VARCHAR(100) DEFAULT 'AI_PLUS_SETUP_AUTO',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_trader FOREIGN KEY (trader_id) REFERENCES traders(id) ON DELETE SET NULL
);

CREATE INDEX idx_automated_trades_symbol_status ON automated_a_plus_trades(symbol, status);
CREATE INDEX idx_automated_trades_entry_time ON automated_a_plus_trades(entry_time DESC);
CREATE INDEX idx_automated_trades_status ON automated_a_plus_trades(status);

-- Strategy configuration
CREATE TABLE IF NOT EXISTS a_plus_strategy_config (
    id BIGSERIAL PRIMARY KEY,
    enabled BOOLEAN DEFAULT TRUE,
    entry_ai_score_min INTEGER DEFAULT 85,
    exit_ai_score_threshold INTEGER DEFAULT 70,
    hard_sl_pct NUMERIC(10, 4) DEFAULT 1.50,
    hard_tp_pct NUMERIC(10, 4) DEFAULT 3.00,
    position_size_qty INTEGER DEFAULT 1,
    universe_group VARCHAR(100) DEFAULT 'NIFTY_100',
    scan_interval_sec INTEGER DEFAULT 30,
    max_concurrent_positions INTEGER DEFAULT 5,
    auto_close_market_end BOOLEAN DEFAULT TRUE,
    market_close_hour INTEGER DEFAULT 15,
    market_close_minute INTEGER DEFAULT 30,
    trader_id UUID,
    execution_mode VARCHAR(50) DEFAULT 'BOTH',

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Insert default configuration for VISHNUALGO (BOTH mode)
INSERT INTO a_plus_strategy_config (id, enabled, entry_ai_score_min, exit_ai_score_threshold,
    hard_sl_pct, hard_tp_pct, position_size_qty, universe_group, scan_interval_sec,
    execution_mode)
VALUES (1, TRUE, 85, 70, 1.50, 3.00, 1, 'NIFTY_100', 30, 'BOTH')
ON CONFLICT (id) DO NOTHING;

-- Trade audit log
CREATE TABLE IF NOT EXISTS automated_trade_audit_log (
    id BIGSERIAL PRIMARY KEY,
    trade_id BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    event_message TEXT,
    ai_score INTEGER,
    price NUMERIC(18, 4),
    pnl NUMERIC(18, 4),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_trade_audit FOREIGN KEY (trade_id) REFERENCES automated_a_plus_trades(id) ON DELETE CASCADE
);

CREATE INDEX idx_trade_audit_trade_id ON automated_trade_audit_log(trade_id);
