-- Futures Signals Table (S3 & S7)
CREATE TABLE IF NOT EXISTS futures_signals (
    signal_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    strategy_name VARCHAR(10) NOT NULL,           -- "S3", "S7"
    symbol_name VARCHAR(20) NOT NULL,             -- "NIFTY", "BANKNIFTY"
    direction VARCHAR(10) NOT NULL,               -- "LONG", "SHORT"
    current_price DECIMAL(12, 2),
    entry_level DECIMAL(12, 2) NOT NULL,
    stop_loss_level DECIMAL(12, 2) NOT NULL,
    target_level1 DECIMAL(12, 2) NOT NULL,
    target_level2 DECIMAL(12, 2),

    -- Gate analysis
    vwap_level DECIMAL(12, 2),
    sma20 DECIMAL(12, 2),
    sma50 DECIMAL(12, 2),
    range5m DECIMAL(10, 6),
    momentum5m DECIMAL(10, 6),
    volume5m BIGINT,

    -- Quality metrics
    quality_score DECIMAL(5, 2) NOT NULL,
    signal_strength VARCHAR(20),                  -- "hi", "medium"

    -- Execution status
    execution_status VARCHAR(20) NOT NULL,        -- "PENDING", "FILLED", "CLOSED", "CANCELLED"
    actual_entry_price DECIMAL(12, 2),
    time_filled_at TIMESTAMP,
    lot_size INT,

    -- Exit execution
    actual_exit_price DECIMAL(12, 2),
    time_closed_at TIMESTAMP,
    outcome_type VARCHAR(20),                     -- "WIN", "LOSS", "EXPIRED"

    -- P&L
    profit_loss DECIMAL(12, 2),
    slippage_entry DECIMAL(10, 6),
    slippage_exit DECIMAL(10, 6),

    -- Timestamps
    time_detected TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- Flags
    is_active BOOLEAN DEFAULT TRUE,
    notes TEXT,

    -- Indexes
    INDEX idx_strategy_active (strategy_name, is_active, time_detected),
    INDEX idx_quality_time (quality_score, time_detected),
    INDEX idx_symbol_direction (symbol_name, direction, is_active),
    INDEX idx_created (created_at),
    INDEX idx_outcome (outcome_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- View: Daily S3 Stats
CREATE OR REPLACE VIEW v_futures_s3_daily_summary AS
SELECT
    DATE(created_at) AS trade_date,
    COUNT(*) AS total_trades,
    SUM(CASE WHEN outcome_type = 'WIN' THEN 1 ELSE 0 END) AS wins,
    SUM(CASE WHEN outcome_type = 'LOSS' THEN 1 ELSE 0 END) AS losses,
    ROUND(100.0 * SUM(CASE WHEN outcome_type = 'WIN' THEN 1 ELSE 0 END) / COUNT(*), 2) AS win_rate,
    SUM(COALESCE(profit_loss, 0)) AS total_pnl
FROM futures_signals
WHERE strategy_name = 'S3' AND outcome_type IS NOT NULL
GROUP BY DATE(created_at)
ORDER BY trade_date DESC;

-- View: Daily S7 Stats
CREATE OR REPLACE VIEW v_futures_s7_daily_summary AS
SELECT
    DATE(created_at) AS trade_date,
    COUNT(*) AS total_trades,
    SUM(CASE WHEN outcome_type = 'WIN' THEN 1 ELSE 0 END) AS wins,
    SUM(CASE WHEN outcome_type = 'LOSS' THEN 1 ELSE 0 END) AS losses,
    ROUND(100.0 * SUM(CASE WHEN outcome_type = 'WIN' THEN 1 ELSE 0 END) / COUNT(*), 2) AS win_rate,
    SUM(COALESCE(profit_loss, 0)) AS total_pnl
FROM futures_signals
WHERE strategy_name = 'S7' AND outcome_type IS NOT NULL
GROUP BY DATE(created_at)
ORDER BY trade_date DESC;

-- View: Overall Performance by Symbol
CREATE OR REPLACE VIEW v_futures_symbol_performance AS
SELECT
    strategy_name,
    symbol_name,
    COUNT(*) AS total_trades,
    SUM(CASE WHEN outcome_type = 'WIN' THEN 1 ELSE 0 END) AS wins,
    SUM(CASE WHEN outcome_type = 'LOSS' THEN 1 ELSE 0 END) AS losses,
    ROUND(100.0 * SUM(CASE WHEN outcome_type = 'WIN' THEN 1 ELSE 0 END) / COUNT(*), 2) AS win_rate,
    SUM(COALESCE(profit_loss, 0)) AS total_pnl
FROM futures_signals
WHERE outcome_type IS NOT NULL
GROUP BY strategy_name, symbol_name
ORDER BY total_pnl DESC;

-- View: Recent Trades (last 20)
CREATE OR REPLACE VIEW v_futures_recent_performance AS
SELECT
    signal_id,
    strategy_name,
    symbol_name,
    direction,
    execution_status,
    quality_score,
    entry_level,
    stop_loss_level,
    target_level1,
    actual_entry_price,
    actual_exit_price,
    profit_loss,
    outcome_type,
    created_at,
    time_closed_at
FROM futures_signals
ORDER BY created_at DESC
LIMIT 20;
