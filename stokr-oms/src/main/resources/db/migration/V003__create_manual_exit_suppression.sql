-- V003: Create manual_exit_suppression table
-- Purpose: Track manual exits and suppress future exits for that position
-- Prevents: Duplicate exits after user manually closes from Zerodha

CREATE TABLE manual_exit_suppression (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    position_id UUID NOT NULL,
    symbol VARCHAR(64) NOT NULL,

    -- What to suppress
    suppress_sl_exit BOOLEAN DEFAULT TRUE,
    suppress_target_exit BOOLEAN DEFAULT TRUE,
    suppress_pressure_exit BOOLEAN DEFAULT TRUE,
    suppress_feed_protection_exit BOOLEAN DEFAULT TRUE,
    suppress_auto_exit BOOLEAN DEFAULT TRUE,
    suppress_all_exits BOOLEAN DEFAULT TRUE,

    -- Why suppressed
    suppression_reason VARCHAR(255),

    -- Where user exited from
    manual_exit_source VARCHAR(32),
        -- ZERODHA_APP, KITE_WEB, BROKER_API, TRADER_TERMINAL, EXTERNAL_BROKER

    -- Exit details
    manual_exit_time TIMESTAMP WITH TIME ZONE,
    manual_exit_quantity NUMERIC(24, 8),
    manual_exit_price NUMERIC(24, 2),

    -- When suppression is active
    suppression_starts_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    suppression_expires_at TIMESTAMP WITH TIME ZONE,
        -- NULL = never expires in this session

    suppression_active BOOLEAN DEFAULT TRUE,

    -- Unique per position (one suppression record per position)
    UNIQUE(position_id),

    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (position_id) REFERENCES portfolio_positions(id) ON DELETE CASCADE
);

-- Indices
CREATE INDEX idx_suppression_user ON manual_exit_suppression(user_id);
CREATE INDEX idx_suppression_symbol ON manual_exit_suppression(symbol);
CREATE INDEX idx_suppression_active ON manual_exit_suppression(suppression_active);
CREATE INDEX idx_suppression_position ON manual_exit_suppression(position_id);

-- Comments
COMMENT ON TABLE manual_exit_suppression IS 'Tracks manual exits and prevents duplicate exit attempts';
COMMENT ON COLUMN manual_exit_suppression.manual_exit_source IS 'Where user exited: broker app, web, API, or Stokr terminal';
COMMENT ON COLUMN manual_exit_suppression.suppression_active IS 'If true, all exit signals for this position are suppressed';
