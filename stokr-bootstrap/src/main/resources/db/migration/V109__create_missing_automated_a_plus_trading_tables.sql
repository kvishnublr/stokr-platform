-- V109: Create missing automated A+ trading tables
-- Prod previously consumed V100 for a different migration, so replay the A+ DDL here.

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

    ai_score_drop_reason BOOLEAN DEFAULT FALSE,
    opposite_signal_triggered BOOLEAN DEFAULT FALSE,
    hard_sl_hit BOOLEAN DEFAULT FALSE,
    hard_tp_hit BOOLEAN DEFAULT FALSE,
    market_close_exit BOOLEAN DEFAULT FALSE,

    entry_order_id VARCHAR(100),
    exit_order_id VARCHAR(100),
    entry_execution_status VARCHAR(50),
    exit_execution_status VARCHAR(50),

    trader_id UUID,
    strategy_name VARCHAR(100) DEFAULT 'AI_PLUS_SETUP_AUTO',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
    -- trader_id is a soft reference (UUID); no FK: there is no `traders` table.
    -- AutomatedAPlusTrade.traderId maps as a plain uuid column, not a JPA relationship.
);

CREATE INDEX IF NOT EXISTS idx_automated_trades_symbol_status ON automated_a_plus_trades(symbol, status);
CREATE INDEX IF NOT EXISTS idx_automated_trades_entry_time ON automated_a_plus_trades(entry_time DESC);
CREATE INDEX IF NOT EXISTS idx_automated_trades_status ON automated_a_plus_trades(status);

CREATE TABLE IF NOT EXISTS a_plus_strategy_config (
    id BIGSERIAL PRIMARY KEY,
    enabled BOOLEAN DEFAULT TRUE,
    entry_ai_score_min INTEGER DEFAULT 85 NOT NULL,
    exit_ai_score_threshold INTEGER DEFAULT 70 NOT NULL,
    hard_sl_pct NUMERIC(10, 4) DEFAULT 1.50 NOT NULL,
    hard_tp_pct NUMERIC(10, 4) DEFAULT 3.00 NOT NULL,
    position_size_qty INTEGER DEFAULT 1 NOT NULL,
    universe_group VARCHAR(100) DEFAULT 'NIFTY_100',
    scan_interval_sec INTEGER DEFAULT 30 NOT NULL,
    max_concurrent_positions INTEGER DEFAULT 5 NOT NULL,
    auto_close_market_end BOOLEAN DEFAULT TRUE,
    market_close_hour INTEGER DEFAULT 15 NOT NULL,
    market_close_minute INTEGER DEFAULT 30 NOT NULL,
    trader_id UUID,
    execution_mode VARCHAR(50) DEFAULT 'BOTH',

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

INSERT INTO a_plus_strategy_config (id, enabled, entry_ai_score_min, exit_ai_score_threshold,
    hard_sl_pct, hard_tp_pct, position_size_qty, universe_group, scan_interval_sec,
    max_concurrent_positions, auto_close_market_end, market_close_hour, market_close_minute,
    execution_mode)
VALUES (1, TRUE, 85, 70, 1.50, 3.00, 1, 'NIFTY_100', 30, 5, TRUE, 15, 30, 'BOTH')
ON CONFLICT (id) DO NOTHING;

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

CREATE INDEX IF NOT EXISTS idx_trade_audit_trade_id ON automated_trade_audit_log(trade_id);
