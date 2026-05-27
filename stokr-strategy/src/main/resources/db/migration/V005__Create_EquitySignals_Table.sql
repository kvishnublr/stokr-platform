CREATE TABLE equity_signals (
    signal_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol_name VARCHAR(20) NOT NULL,
    sector VARCHAR(50),
    tier VARCHAR(10),
    direction VARCHAR(10),
    current_price DECIMAL(20,4),
    entry_level DECIMAL(20,4),
    stop_loss_level DECIMAL(20,4),
    target_level1 DECIMAL(20,4),
    target_level2 DECIMAL(20,4),
    obi_score DECIMAL(10,4),
    obi_slope DECIMAL(10,4),
    volume_level DECIMAL(20,4),
    bid_ask_spread DECIMAL(10,4),
    vix_level DECIMAL(10,4),
    timeframe_alignment INT,
    confidence_level INT,
    quality_score DECIMAL(10,2),
    execution_status VARCHAR(20),
    actual_entry_price DECIMAL(20,4),
    actual_exit_price DECIMAL(20,4),
    quantity INT,
    outcome_type VARCHAR(20),
    profit_loss DECIMAL(20,4),
    slippage_entry DECIMAL(10,4),
    slippage_exit DECIMAL(10,4),
    time_detected TIMESTAMP,
    time_filled_at TIMESTAMP,
    time_closed_at TIMESTAMP,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_symbol_active (symbol_name, is_active, time_detected),
    INDEX idx_confidence (confidence_level, time_detected),
    INDEX idx_sector_direction (sector, direction, is_active),
    INDEX idx_created_at (created_at),
    INDEX idx_outcome (outcome_type)
);

CREATE VIEW vw_equity_signals_daily_summary AS
SELECT
    DATE(created_at) as trading_date,
    sector,
    COUNT(*) as total_signals,
    SUM(CASE WHEN outcome_type = 'WIN' THEN 1 ELSE 0 END) as wins,
    SUM(CASE WHEN outcome_type = 'LOSS' THEN 1 ELSE 0 END) as losses,
    ROUND(100.0 * SUM(CASE WHEN outcome_type = 'WIN' THEN 1 ELSE 0 END) / NULLIF(COUNT(CASE WHEN outcome_type IN ('WIN', 'LOSS') THEN 1 END), 0), 2) as win_rate,
    SUM(COALESCE(profit_loss, 0)) as total_pnl
FROM equity_signals
WHERE outcome_type IN ('WIN', 'LOSS')
GROUP BY DATE(created_at), sector
ORDER BY trading_date DESC, total_pnl DESC;

CREATE VIEW vw_equity_signals_tier_performance AS
SELECT
    tier,
    COUNT(*) as total_trades,
    SUM(CASE WHEN outcome_type = 'WIN' THEN 1 ELSE 0 END) as wins,
    SUM(CASE WHEN outcome_type = 'LOSS' THEN 1 ELSE 0 END) as losses,
    ROUND(100.0 * SUM(CASE WHEN outcome_type = 'WIN' THEN 1 ELSE 0 END) / NULLIF(COUNT(CASE WHEN outcome_type IN ('WIN', 'LOSS') THEN 1 END), 0), 2) as win_rate,
    SUM(COALESCE(profit_loss, 0)) as total_pnl,
    AVG(COALESCE(profit_loss, 0)) as avg_pnl
FROM equity_signals
WHERE outcome_type IN ('WIN', 'LOSS')
GROUP BY tier
ORDER BY win_rate DESC;

CREATE VIEW vw_equity_signals_recent_performance AS
SELECT
    signal_id,
    symbol_name,
    sector,
    direction,
    entry_level,
    actual_entry_price,
    actual_exit_price,
    target_level1,
    quality_score,
    confidence_level,
    outcome_type,
    profit_loss,
    time_detected,
    time_closed_at
FROM equity_signals
WHERE outcome_type IN ('WIN', 'LOSS')
ORDER BY time_closed_at DESC
LIMIT 20;
