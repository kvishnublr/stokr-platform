-- HYBRID EXIT ENGINE - DATABASE SCHEMA
-- Stores technical indicators and exit signals for real-time decision making

-- Table 1: Indicator History (for charting and analysis)
CREATE TABLE IF NOT EXISTS indicator_history (
    id SERIAL PRIMARY KEY,
    symbol VARCHAR(20),
    timestamp TIMESTAMP DEFAULT NOW(),
    rsi DECIMAL(10, 2),
    macd_line DECIMAL(15, 4),
    macd_signal DECIMAL(15, 4),
    macd_histogram DECIMAL(15, 4),
    volume_rsi DECIMAL(10, 2),
    atr DECIMAL(15, 4),
    bb_upper DECIMAL(15, 4),
    bb_middle DECIMAL(15, 4),
    bb_lower DECIMAL(15, 4),
    INDEX idx_symbol_time (symbol, timestamp DESC)
);

-- Table 2: Exit Signals (strategy + indicator-based)
CREATE TABLE IF NOT EXISTS exit_signals (
    id SERIAL PRIMARY KEY,
    position_id INTEGER REFERENCES positions(id),
    symbol VARCHAR(20),
    signal_type VARCHAR(50),  -- RSI_OVERBOUGHT, MACD_BEARISH_CROSSOVER, BB_UPPER_TOUCH, etc.
    signal_strength DECIMAL(5, 2),  -- 0.0 to 1.0 confidence
    recommendation VARCHAR(50),  -- HOLD, WEAK_EXIT, EXIT, STRONG_EXIT
    overall_confidence DECIMAL(5, 2),
    generated_at TIMESTAMP DEFAULT NOW(),
    INDEX idx_position_time (position_id, generated_at DESC)
);

-- Table 3: Dynamic Target History (for optimization tracking)
CREATE TABLE IF NOT EXISTS dynamic_targets (
    id SERIAL PRIMARY KEY,
    position_id INTEGER REFERENCES positions(id),
    symbol VARCHAR(20),
    original_target DECIMAL(15, 4),
    dynamic_target DECIMAL(15, 4),
    dynamic_stop DECIMAL(15, 4),
    volatility_factor DECIMAL(5, 3),
    momentum_factor DECIMAL(5, 3),
    rsi_factor DECIMAL(5, 3),
    profit_potential DECIMAL(10, 2),
    risk_level DECIMAL(10, 2),
    calculated_at TIMESTAMP DEFAULT NOW(),
    INDEX idx_position (position_id),
    INDEX idx_symbol (symbol)
);

-- Table 4: Exit Events (audit trail of actual exits)
CREATE TABLE IF NOT EXISTS exit_events (
    id SERIAL PRIMARY KEY,
    position_id INTEGER REFERENCES positions(id),
    symbol VARCHAR(20),
    exit_type VARCHAR(50),  -- STRATEGY_EXIT, INDICATOR_EXIT, AI_EXIT, HYBRID_EXIT
    signal_confidence DECIMAL(5, 2),
    entry_price DECIMAL(15, 4),
    exit_price DECIMAL(15, 4),
    profit_loss DECIMAL(15, 2),
    pnl_percent DECIMAL(10, 2),
    exit_reason TEXT,
    exit_timestamp TIMESTAMP DEFAULT NOW(),
    INDEX idx_symbol_time (symbol, exit_timestamp DESC)
);

-- Table 5: Hybrid Configuration (feature toggles)
CREATE TABLE IF NOT EXISTS hybrid_exit_config (
    id SERIAL PRIMARY KEY,
    feature_name VARCHAR(100) UNIQUE,
    enabled BOOLEAN DEFAULT TRUE,
    parameters JSON,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- Insert default configuration
INSERT INTO hybrid_exit_config (feature_name, enabled, parameters) VALUES
('strategy_exit', TRUE, '{"confidence_threshold": 0.8, "required": true}'),
('indicator_rsi', TRUE, '{"overbought": 70, "oversold": 30, "weight": 0.3}'),
('indicator_macd', TRUE, '{"weight": 0.3, "crossover_required": true}'),
('indicator_bollinger', TRUE, '{"periods": 20, "std_dev": 2, "weight": 0.2}'),
('indicator_volume', TRUE, '{"threshold_multiplier": 1.5, "weight": 0.2}'),
('dynamic_targets', TRUE, '{"enabled": true, "update_frequency": 10}'),
('ai_optimization', FALSE, '{"enabled": false, "model": "ensemble", "confidence_threshold": 0.7}');

-- Table 6: Strategy Exit Signals (defines what each strategy signals for exit)
CREATE TABLE IF NOT EXISTS strategy_exit_definitions (
    id SERIAL PRIMARY KEY,
    strategy_name VARCHAR(50),
    exit_condition_type VARCHAR(100),  -- PROFIT_TARGET, STOP_LOSS, SIGNAL_REVERSAL, TIME_BASED
    parameters JSON,
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT NOW()
);

-- Insert default strategy exit definitions
INSERT INTO strategy_exit_definitions (strategy_name, exit_condition_type, parameters) VALUES
('indexhunt', 'PROFIT_TARGET', '{"target_percent": 2.0, "confidence": 0.8}'),
('indexhunt', 'STOP_LOSS', '{"stop_percent": 2.0, "confidence": 0.8}'),
('adv_cash', 'PROFIT_TARGET', '{"target_percent": 1.5, "confidence": 0.75}'),
('adv_cash', 'STOP_LOSS', '{"stop_percent": 1.5, "confidence": 0.75}'),
('commodities', 'PROFIT_TARGET', '{"target_percent": 3.0, "confidence": 0.85}'),
('commodities', 'STOP_LOSS', '{"stop_percent": 2.5, "confidence": 0.85}'),
('early_breakout', 'PROFIT_TARGET', '{"target_percent": 2.5, "confidence": 0.8}'),
('early_breakout', 'STOP_LOSS', '{"stop_percent": 1.5, "confidence": 0.8}');

-- Table 7: Exit Performance Metrics (for continuous improvement)
CREATE TABLE IF NOT EXISTS exit_performance (
    id SERIAL PRIMARY KEY,
    symbol VARCHAR(20),
    exit_type VARCHAR(50),
    total_exits INTEGER,
    successful_exits INTEGER,
    avg_profit_loss DECIMAL(15, 2),
    win_rate DECIMAL(5, 2),
    last_updated TIMESTAMP DEFAULT NOW(),
    UNIQUE KEY unique_symbol_type (symbol, exit_type)
);

-- Add column to positions table for hybrid exit tracking (if not exists)
ALTER TABLE positions ADD COLUMN IF NOT EXISTS hybrid_exit_enabled BOOLEAN DEFAULT TRUE;
ALTER TABLE positions ADD COLUMN IF NOT EXISTS last_signal_check TIMESTAMP;
ALTER TABLE positions ADD COLUMN IF NOT EXISTS last_dynamic_target DECIMAL(15, 4);
ALTER TABLE positions ADD COLUMN IF NOT EXISTS signal_confidence DECIMAL(5, 2);

-- Stored procedure: Update indicator history
DELIMITER //
CREATE PROCEDURE update_indicators(
    p_symbol VARCHAR(20),
    p_rsi DECIMAL(10, 2),
    p_macd_line DECIMAL(15, 4),
    p_macd_signal DECIMAL(15, 4),
    p_macd_histogram DECIMAL(15, 4),
    p_volume_rsi DECIMAL(10, 2),
    p_atr DECIMAL(15, 4),
    p_bb_upper DECIMAL(15, 4),
    p_bb_middle DECIMAL(15, 4),
    p_bb_lower DECIMAL(15, 4)
)
BEGIN
    INSERT INTO indicator_history (
        symbol, rsi, macd_line, macd_signal, macd_histogram,
        volume_rsi, atr, bb_upper, bb_middle, bb_lower
    ) VALUES (
        p_symbol, p_rsi, p_macd_line, p_macd_signal, p_macd_histogram,
        p_volume_rsi, p_atr, p_bb_upper, p_bb_middle, p_bb_lower
    );
END //
DELIMITER ;

-- Stored procedure: Record exit event
DELIMITER //
CREATE PROCEDURE record_exit_event(
    p_position_id INTEGER,
    p_symbol VARCHAR(20),
    p_exit_type VARCHAR(50),
    p_signal_confidence DECIMAL(5, 2),
    p_entry_price DECIMAL(15, 4),
    p_exit_price DECIMAL(15, 4),
    p_exit_reason TEXT
)
BEGIN
    DECLARE v_profit_loss DECIMAL(15, 2);
    DECLARE v_pnl_percent DECIMAL(10, 2);

    SET v_profit_loss = (p_exit_price - p_entry_price);
    SET v_pnl_percent = (v_profit_loss / p_entry_price) * 100;

    INSERT INTO exit_events (
        position_id, symbol, exit_type, signal_confidence,
        entry_price, exit_price, profit_loss, pnl_percent, exit_reason
    ) VALUES (
        p_position_id, p_symbol, p_exit_type, p_signal_confidence,
        p_entry_price, p_exit_price, v_profit_loss, v_pnl_percent, p_exit_reason
    );

    -- Update performance metrics
    INSERT INTO exit_performance (symbol, exit_type, total_exits, successful_exits, avg_profit_loss, win_rate)
    VALUES (p_symbol, p_exit_type, 1, IF(v_profit_loss > 0, 1, 0), v_profit_loss, IF(v_profit_loss > 0, 100, 0))
    ON DUPLICATE KEY UPDATE
        total_exits = total_exits + 1,
        successful_exits = successful_exits + IF(v_profit_loss > 0, 1, 0),
        avg_profit_loss = (avg_profit_loss + v_profit_loss) / 2,
        win_rate = (successful_exits + IF(v_profit_loss > 0, 1, 0)) / (total_exits + 1) * 100;
END //
DELIMITER ;

-- Create index for fast signal lookup
CREATE INDEX IF NOT EXISTS idx_exit_signals_position ON exit_signals(position_id);
CREATE INDEX IF NOT EXISTS idx_exit_signals_symbol ON exit_signals(symbol);
CREATE INDEX IF NOT EXISTS idx_dynamic_targets_position ON dynamic_targets(position_id);
