-- V54__nse_intraday_platform_schema.sql
-- NSE Intraday Platform - Core tables for setup detection, ranking, and personalization
-- Adds support for 4 trading setups: Gap Fill, VWAP Bounce, Sector Laggard, Early Breakout

-- Table: nse_stocks
-- Reference data for NSE listed stocks
CREATE TABLE IF NOT EXISTS nse_stocks (
    stock_id VARCHAR(10) PRIMARY KEY,
    stock_name VARCHAR(100) NOT NULL,
    sector VARCHAR(50) NOT NULL,
    market_cap_millions BIGINT,
    average_volume_daily BIGINT,
    lot_size INT,
    price_precision DECIMAL(10,2),
    prev_close DECIMAL(10,2),
    prev_high DECIMAL(10,2),
    prev_low DECIMAL(10,2),
    week_52_high DECIMAL(10,2),
    week_52_low DECIMAL(10,2),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_nse_stocks_sector ON nse_stocks(sector);
CREATE INDEX IF NOT EXISTS idx_nse_stocks_market_cap ON nse_stocks(market_cap_millions);

-- Table: historical_win_rates
-- Backtested win rates for each setup type under different market conditions
-- Used to calculate base probabilities for new signals
CREATE TABLE IF NOT EXISTS historical_win_rates (
    win_rate_id BIGSERIAL PRIMARY KEY,
    setup_type VARCHAR(50) NOT NULL,                -- gap_fill, vwap_bounce, sector_laggard, early_breakout
    gap_direction VARCHAR(10),                      -- UP, DOWN (for gap_fill only)
    gap_size_min DECIMAL(5,2),
    gap_size_max DECIMAL(5,2),
    market_regime VARCHAR(20),                      -- TRENDING_UP, TRENDING_DOWN, CHOPPY, VOLATILE, QUIET
    volume_ratio_min DECIMAL(5,2),
    volume_ratio_max DECIMAL(5,2),
    touches_min INT,
    touches_max INT,
    hour_of_day INT,
    sector VARCHAR(50),
    win_count INT DEFAULT 0,
    loss_count INT DEFAULT 0,
    win_rate DECIMAL(5,4),
    avg_win_percent DECIMAL(5,4),
    avg_loss_percent DECIMAL(5,4),
    sample_size INT,
    confidence_level VARCHAR(20),                   -- HIGH (n>100), MEDIUM (n>50), LOW (n<50)
    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(setup_type, gap_direction, market_regime, hour_of_day, sector)
);

CREATE INDEX IF NOT EXISTS idx_historical_win_rates_lookup
    ON historical_win_rates(setup_type, market_regime, hour_of_day);
CREATE INDEX IF NOT EXISTS idx_historical_win_rates_sector
    ON historical_win_rates(sector, setup_type);

-- Table: current_setups
-- Real-time ranking board of top setups detected
-- Updates every 1-5 minutes, expires after 30 minutes of inactivity
CREATE TABLE IF NOT EXISTS current_setups (
    setup_id BIGSERIAL PRIMARY KEY,
    time_detected TIMESTAMP NOT NULL,
    stock_id VARCHAR(10) NOT NULL,
    setup_type VARCHAR(50) NOT NULL,               -- gap_fill, vwap_bounce, sector_laggard, early_breakout

    -- Setup Details
    entry_price DECIMAL(10,2) NOT NULL,
    target_price DECIMAL(10,2),
    stop_loss DECIMAL(10,2),
    risk_amount DECIMAL(10,2),
    reward_amount DECIMAL(10,2),
    risk_reward_ratio DECIMAL(5,2),

    -- Probabilities & Adjustments
    base_probability DECIMAL(5,4),                  -- From historical_win_rates
    market_regime VARCHAR(20),                      -- Current market regime
    regime_adjustment DECIMAL(5,2),                 -- % adjustment based on regime
    adjusted_probability DECIMAL(5,4),              -- base_probability * (1 + regime_adjustment)

    -- Quality Metrics
    confidence_level VARCHAR(20),
    expected_value DECIMAL(10,4),
    quality_score DECIMAL(5,2),                     -- 0-100: composite score for ranking
    time_to_expiry_minutes INT,

    -- Status & Metadata
    is_active BOOLEAN DEFAULT TRUE,
    is_recommended_for_user BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP,

    FOREIGN KEY (stock_id) REFERENCES nse_stocks(stock_id)
);

CREATE INDEX IF NOT EXISTS idx_current_setups_active ON current_setups(is_active, time_detected DESC);
CREATE INDEX IF NOT EXISTS idx_current_setups_quality ON current_setups(quality_score DESC, time_detected DESC);
CREATE INDEX IF NOT EXISTS idx_current_setups_stock ON current_setups(stock_id, is_active);
CREATE INDEX IF NOT EXISTS idx_current_setups_expiry ON current_setups(expires_at);

-- Table: sector_tracking
-- Real-time sector momentum and performance
-- Used for sector laggard detection and sector rotation analysis
CREATE TABLE IF NOT EXISTS sector_tracking (
    sector_id BIGSERIAL PRIMARY KEY,
    sector_name VARCHAR(50) NOT NULL,
    time_tracked TIMESTAMP NOT NULL,

    sector_open DECIMAL(10,2),
    sector_current DECIMAL(10,2),
    sector_high DECIMAL(10,2),
    sector_low DECIMAL(10,2),

    sector_return_percent DECIMAL(6,4),
    sector_momentum DECIMAL(6,4),
    sector_volatility DECIMAL(6,4),

    top_performer VARCHAR(10),
    worst_performer VARCHAR(10),

    stock_count INT,
    avg_stock_return DECIMAL(6,4),

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(sector_name, time_tracked)
);

CREATE INDEX IF NOT EXISTS idx_sector_tracking_time ON sector_tracking(time_tracked DESC);
CREATE INDEX IF NOT EXISTS idx_sector_tracking_sector ON sector_tracking(sector_name, time_tracked DESC);

-- Table: market_regime_log
-- Historical record of market regime changes
-- Used for backtesting and regime-aware probability adjustments
CREATE TABLE IF NOT EXISTS market_regime_log (
    regime_id BIGSERIAL PRIMARY KEY,
    time_detected TIMESTAMP NOT NULL,
    regime VARCHAR(20) NOT NULL,                    -- TRENDING_UP, TRENDING_DOWN, CHOPPY, VOLATILE, QUIET
    nifty50_price DECIMAL(10,2),
    nifty50_change_percent DECIMAL(6,4),
    trend_score DECIMAL(5,4),
    momentum DECIMAL(6,4),
    volatility_ratio DECIMAL(5,4),
    volume_ratio DECIMAL(5,4),

    best_setup_type VARCHAR(50),
    expected_best_setup_prob DECIMAL(5,4),

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(time_detected)
);

CREATE INDEX IF NOT EXISTS idx_market_regime_log_time ON market_regime_log(time_detected DESC);
CREATE INDEX IF NOT EXISTS idx_market_regime_log_regime ON market_regime_log(regime, time_detected DESC);

-- Table: user_trades (extends existing OMS order/trade model)
-- Tracks intraday trades with setup type, entry/exit details, and outcome
CREATE TABLE IF NOT EXISTS user_trades (
    trade_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,                        -- References trader_identity table
    stock_id VARCHAR(10) NOT NULL,
    setup_type VARCHAR(50) NOT NULL,

    -- Entry Details
    entry_time TIMESTAMP NOT NULL,
    entry_price DECIMAL(10,2) NOT NULL,
    quantity INT NOT NULL,

    -- Target & Stop Loss
    target_price DECIMAL(10,2),
    stop_loss_price DECIMAL(10,2),

    -- Exit Details
    exit_time TIMESTAMP,
    exit_price DECIMAL(10,2),

    -- Status & Results
    status VARCHAR(20),                             -- OPEN, CLOSED, STOPPED_OUT
    profit_loss DECIMAL(12,2),
    profit_loss_percent DECIMAL(6,4),
    holding_time_minutes INT,
    result VARCHAR(10),                             -- WIN, LOSS, BREAK_EVEN

    -- Context
    market_regime_at_entry VARCHAR(20),
    entry_setup_quality_score DECIMAL(5,2),
    entry_probability DECIMAL(5,4),

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_user_trades_user_entry ON user_trades(user_id, entry_time DESC);
CREATE INDEX IF NOT EXISTS idx_user_trades_stock_entry ON user_trades(stock_id, entry_time DESC);
CREATE INDEX IF NOT EXISTS idx_user_trades_setup_type ON user_trades(setup_type, entry_time DESC);
CREATE INDEX IF NOT EXISTS idx_user_trades_status ON user_trades(user_id, status);

-- Table: user_statistics
-- Aggregated performance metrics per user
-- Updated after each trade and nightly aggregation
CREATE TABLE IF NOT EXISTS user_statistics (
    stat_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,

    -- Overall Stats
    total_trades INT DEFAULT 0,
    winning_trades INT DEFAULT 0,
    losing_trades INT DEFAULT 0,
    win_rate DECIMAL(5,4),
    avg_win_percent DECIMAL(6,4),
    avg_loss_percent DECIMAL(6,4),
    total_profit DECIMAL(12,2),

    -- By Setup Type (JSON structure)
    -- {gap_fill: {trades: 25, wins: 19, win_rate: 0.76}, ...}
    setup_type_stats JSONB,

    -- By Time of Day (JSON structure)
    -- {9: {trades: 5, wins: 4}, 10: {trades: 8, wins: 6}, ...}
    hourly_stats JSONB,

    -- By Sector (JSON structure)
    -- {Banking: {trades: 12, wins: 9}, IT: {trades: 8, wins: 5}, ...}
    sector_stats JSONB,

    -- Streaks
    current_winning_streak INT DEFAULT 0,
    current_losing_streak INT DEFAULT 0,
    best_winning_streak INT DEFAULT 0,
    best_losing_streak INT DEFAULT 0,

    -- Daily Stats
    today_trades INT DEFAULT 0,
    today_profit DECIMAL(12,2),
    today_win_rate DECIMAL(5,4),

    -- Monthly Stats
    month_trades INT DEFAULT 0,
    month_profit DECIMAL(12,2),
    month_win_rate DECIMAL(5,4),

    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_user_statistics_user ON user_statistics(user_id);

-- Table: user_preferences_intraday
-- User preferences specific to intraday platform (alerts, setup filters, risk settings)
CREATE TABLE IF NOT EXISTS user_preferences_intraday (
    pref_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,

    -- Alerts
    alert_enabled BOOLEAN DEFAULT TRUE,
    push_notification_enabled BOOLEAN DEFAULT TRUE,
    email_alert_enabled BOOLEAN DEFAULT FALSE,
    alert_frequency VARCHAR(20),                    -- ONLY_HIGH_PROB, ALL, MODERATE

    -- Setup Preferences (JSON)
    -- {gap_fill: true, vwap_bounce: true, sector_laggard: false, early_breakout: true}
    setup_preferences JSONB,

    -- Alert Time Windows (JSON)
    -- {gap_fill: [9, 10], vwap_bounce: [10, 11], ...}
    alert_time_windows JSONB,

    -- Thresholds
    min_win_probability_threshold DECIMAL(5,4),    -- Only alert if > this value (default 0.60)
    min_risk_reward_threshold DECIMAL(5,2),        -- Only alert if > this value (default 1.0)

    -- Risk Management
    risk_per_trade_percent DECIMAL(5,2),            -- 1%, 2%, 3% of account
    max_daily_trades INT,

    -- Sector Preferences (JSON)
    preferred_sectors JSONB,
    avoid_sectors JSONB,

    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_user_preferences_intraday_user ON user_preferences_intraday(user_id);

-- Table: setup_detection_cache
-- Temporary cache of detected setups during market hours
-- Used to prevent duplicate alerts and track which setups were shown to which users
CREATE TABLE IF NOT EXISTS setup_detection_cache (
    cache_id BIGSERIAL PRIMARY KEY,
    setup_id BIGINT NOT NULL,
    user_id BIGINT,                                 -- NULL if not user-specific
    alert_sent BOOLEAN DEFAULT FALSE,
    alert_sent_at TIMESTAMP,
    user_action VARCHAR(20),                        -- VIEWED, TRADED, IGNORED, DISMISSED
    user_action_time TIMESTAMP,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP                            -- TTL for cleanup
);

CREATE INDEX IF NOT EXISTS idx_setup_detection_cache_setup ON setup_detection_cache(setup_id);
CREATE INDEX IF NOT EXISTS idx_setup_detection_cache_user ON setup_detection_cache(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_setup_detection_cache_expires ON setup_detection_cache(expires_at);

-- Grant permissions (if needed)
-- These tables will be used by application code, so ensure proper access

COMMIT;
