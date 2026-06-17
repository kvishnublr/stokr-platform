-- =============================================================
-- Stokr Lite - Chartink Position Tracking
-- =============================================================

CREATE TABLE chartink_positions (
    id              BIGSERIAL PRIMARY KEY,
    signal_id       BIGINT       NOT NULL UNIQUE REFERENCES strategy_signals(id),
    symbol          VARCHAR(50)  NOT NULL,
    side            VARCHAR(10)  NOT NULL,
    quantity        INT          NOT NULL DEFAULT 0,
    entry_price     DECIMAL(15,4),
    avg_price       DECIMAL(15,4),
    stop_loss       DECIMAL(15,4),
    target          DECIMAL(15,4),
    trailing_stop   DECIMAL(15,4),
    highest_price   DECIMAL(15,4),
    lowest_price    DECIMAL(15,4),
    unrealized_pnl  DECIMAL(15,4) DEFAULT 0,
    realized_pnl    DECIMAL(15,4) DEFAULT 0,
    status          VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    exit_reason     VARCHAR(100),
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    closed_at       TIMESTAMP
);

CREATE INDEX idx_chartink_pos_status ON chartink_positions(status);
CREATE INDEX idx_chartink_pos_symbol ON chartink_positions(symbol);
CREATE INDEX idx_chartink_pos_open ON chartink_positions(symbol, status);
