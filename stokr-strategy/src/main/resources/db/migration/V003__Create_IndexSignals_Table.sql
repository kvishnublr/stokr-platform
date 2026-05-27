-- Flyway Migration: Create INDEX_SIGNALS table for INDEX HUNT strategy
-- Author: INDEX HUNT Implementation
-- Date: 2026-05-27

CREATE TABLE IF NOT EXISTS index_signals (
    signal_id BIGINT PRIMARY KEY AUTO_INCREMENT,

    -- Core Identity
    index_name VARCHAR(20) NOT NULL COMMENT 'NIFTY or BANKNIFTY',
    direction VARCHAR(5) NOT NULL COMMENT 'CE (call/bullish) or PE (put/bearish)',
    time_detected TIMESTAMP NOT NULL COMMENT 'When signal was detected',

    -- Price & Entry Details
    index_price DECIMAL(10, 2) NOT NULL COMMENT 'Spot price at signal time',
    option_entry_premium DECIMAL(10, 2) COMMENT 'Option LTP at signal time',
    option_sl DECIMAL(10, 2) COMMENT 'Stop loss level = entry × 0.80',
    option_t1 DECIMAL(10, 2) COMMENT 'Target 1 = entry × 1.28 (70% hit rate)',
    option_t2 DECIMAL(10, 2) COMMENT 'Target 2 = entry × 1.65 (rare)',

    -- Gate Analysis (5 Gates)
    momentum_5m DECIMAL(5, 4) COMMENT '5-minute price change %',
    trend_30m DECIMAL(5, 4) COMMENT '30-minute trend direction (+ve=up, -ve=down)',
    pcr_ratio DECIMAL(5, 2) COMMENT 'Put/Call OI ratio (smart money)',
    vix_level DECIMAL(5, 2) COMMENT 'India VIX at signal time',
    session_open_price DECIMAL(5, 4) COMMENT 'Day open (for session lock)',
    recent_3min_low DECIMAL(5, 4) COMMENT 'Anti-chase: recent 3m low',
    recent_3min_high DECIMAL(5, 4) COMMENT 'Anti-chase: recent 3m high',

    -- Quality Metrics
    quality_score DECIMAL(5, 2) COMMENT '0-100: composite quality',
    signal_strength VARCHAR(20) COMMENT 'md (medium) or hi (high)',
    gate_status VARCHAR(30) COMMENT 'Which gates passed',
    all_gates_passed BOOLEAN DEFAULT TRUE COMMENT 'All 5 gates green',

    -- Execution Status
    execution_status VARCHAR(20) COMMENT 'PENDING, FILLED, T1_HIT, SL_HIT, EXPIRED, CLOSED',
    is_active BOOLEAN DEFAULT TRUE COMMENT 'Waiting for execution',

    -- Timestamps
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    filled_at TIMESTAMP COMMENT 'When order executed',
    closed_at TIMESTAMP COMMENT 'When T1/SL hit',

    -- Outcome (after execution)
    actual_entry_premium DECIMAL(10, 2) COMMENT 'What we actually paid',
    actual_exit_premium DECIMAL(10, 2) COMMENT 'What we actually received',
    pnl_per_contract DECIMAL(10, 2) COMMENT 'Exit - Entry per contract',
    total_pnl DECIMAL(10, 2) COMMENT 'P&L × lot size',
    outcome_type VARCHAR(20) COMMENT 'WIN (T1), LOSS (SL), EXPIRED, PARTIAL',

    -- Metadata
    notes VARCHAR(200) COMMENT 'Trade notes',

    -- Indexes for performance
    INDEX idx_index_signals_active (is_active, time_detected),
    INDEX idx_index_signals_quality (quality_score, time_detected),
    INDEX idx_index_signals_index (index_name, direction, is_active),
    INDEX idx_index_signals_expiry (created_at),
    INDEX idx_index_signals_outcome (outcome_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='INDEX HUNT signals (NIFTY/BANKNIFTY options momentum trading)';

-- View: Today's closed signals summary
CREATE OR REPLACE VIEW v_index_hunt_today_summary AS
SELECT
    index_name,
    direction,
    outcome_type,
    COUNT(*) as trade_count,
    ROUND(SUM(total_pnl), 2) as total_pnl,
    ROUND(AVG(quality_score), 2) as avg_quality,
    ROUND(100.0 * SUM(CASE WHEN outcome_type = 'WIN' THEN 1 ELSE 0 END) / COUNT(*), 1) as win_rate_pct
FROM index_signals
WHERE DATE(closed_at) = CURDATE()
GROUP BY index_name, direction, outcome_type;

-- View: Recent performance (last 20 trades)
CREATE OR REPLACE VIEW v_index_hunt_recent_performance AS
SELECT
    signal_id,
    index_name,
    direction,
    quality_score,
    outcome_type,
    total_pnl,
    actual_entry_premium,
    actual_exit_premium,
    TIMESTAMPDIFF(MINUTE, filled_at, closed_at) as hold_minutes,
    closed_at
FROM index_signals
WHERE outcome_type IN ('WIN', 'LOSS')
ORDER BY closed_at DESC
LIMIT 20;

-- View: Win rate by index
CREATE OR REPLACE VIEW v_index_hunt_win_rate_by_index AS
SELECT
    index_name,
    COUNT(*) as total_trades,
    SUM(CASE WHEN outcome_type = 'WIN' THEN 1 ELSE 0 END) as wins,
    SUM(CASE WHEN outcome_type = 'LOSS' THEN 1 ELSE 0 END) as losses,
    ROUND(100.0 * SUM(CASE WHEN outcome_type = 'WIN' THEN 1 ELSE 0 END) /
          COUNT(*), 1) as win_rate_pct,
    ROUND(SUM(CASE WHEN outcome_type = 'WIN' THEN total_pnl ELSE 0 END), 2) as total_wins,
    ROUND(SUM(CASE WHEN outcome_type = 'LOSS' THEN total_pnl ELSE 0 END), 2) as total_losses,
    ROUND(SUM(total_pnl), 2) as net_pnl
FROM index_signals
WHERE outcome_type IN ('WIN', 'LOSS')
GROUP BY index_name;
