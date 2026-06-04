-- Phase 1: Order Flow Analysis Foundation
-- Creates tables to store real-time order book snapshots and metrics

CREATE TABLE IF NOT EXISTS order_flow_snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    symbol VARCHAR(20) NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL,

    -- Bid-Ask Data
    bid_price NUMERIC(20, 4) NOT NULL,
    bid_volume BIGINT NOT NULL,
    bid_level_1 BIGINT,
    bid_level_2 BIGINT,
    bid_level_3 BIGINT,
    bid_level_4 BIGINT,
    bid_level_5 BIGINT,

    ask_price NUMERIC(20, 4) NOT NULL,
    ask_volume BIGINT NOT NULL,
    ask_level_1 BIGINT,
    ask_level_2 BIGINT,
    ask_level_3 BIGINT,
    ask_level_4 BIGINT,
    ask_level_5 BIGINT,

    -- Calculated Metrics
    spread NUMERIC(10, 4),
    spread_pct NUMERIC(10, 6),
    mid_price NUMERIC(20, 4),
    bid_ask_ratio NUMERIC(10, 4),
    imbalance BIGINT,

    -- Liquidity Metrics
    cumulative_bid_10_levels BIGINT,
    cumulative_ask_10_levels BIGINT,
    liquidity_score INTEGER CHECK (liquidity_score >= 0 AND liquidity_score <= 100),

    -- Pressure Indicators
    buyer_pressure_score INTEGER CHECK (buyer_pressure_score >= 0 AND buyer_pressure_score <= 100),
    seller_pressure_score INTEGER CHECK (seller_pressure_score >= 0 AND seller_pressure_score <= 100),

    -- Additional Analysis Fields
    bid_ask_momentum INTEGER,
    large_orders_on_bid BIGINT,
    large_orders_on_ask BIGINT,
    pressure_type VARCHAR(50),

    buyer_initiated_pct NUMERIC(10, 4),
    seller_initiated_pct NUMERIC(10, 4),

    -- Metadata
    exchange VARCHAR(10),
    is_valid BOOLEAN DEFAULT FALSE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes for performance
CREATE INDEX IF NOT EXISTS idx_order_flow_symbol_time
    ON order_flow_snapshots(symbol, timestamp DESC);

CREATE INDEX IF NOT EXISTS idx_order_flow_pressure
    ON order_flow_snapshots(symbol, buyer_pressure_score DESC);

CREATE INDEX IF NOT EXISTS idx_order_flow_liquidity
    ON order_flow_snapshots(symbol, liquidity_score DESC);

CREATE INDEX IF NOT EXISTS idx_order_flow_timestamp
    ON order_flow_snapshots(timestamp DESC);

CREATE INDEX IF NOT EXISTS idx_order_flow_valid
    ON order_flow_snapshots(symbol, is_valid, timestamp DESC);

-- Composite index for most common queries
CREATE INDEX IF NOT EXISTS idx_order_flow_recent
    ON order_flow_snapshots(symbol, timestamp DESC, is_valid);

-- Create volume_profile table for intraday analysis (Phase 1 extension)
CREATE TABLE IF NOT EXISTS volume_profile_intraday (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    symbol VARCHAR(20) NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL,

    -- Price Level Data
    price_level NUMERIC(20, 4) NOT NULL,
    volume_at_level BIGINT NOT NULL,
    concentration NUMERIC(5, 2),

    -- Profile Characteristics
    point_of_control NUMERIC(20, 4),
    value_area_high NUMERIC(20, 4),
    value_area_low NUMERIC(20, 4),
    total_volume BIGINT,

    -- Analysis
    profile_type VARCHAR(20),
    strength_score INTEGER CHECK (strength_score >= 0 AND strength_score <= 100),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_volume_profile_symbol
    ON volume_profile_intraday(symbol, timestamp DESC);

CREATE INDEX IF NOT EXISTS idx_volume_profile_poc
    ON volume_profile_intraday(point_of_control);

-- Create liquidity_depth table for target analysis (Phase 1 extension)
CREATE TABLE IF NOT EXISTS liquidity_depth_analysis (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    symbol VARCHAR(20) NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL,

    -- Target Analysis
    entry_price NUMERIC(20, 4),
    target_price NUMERIC(20, 4),
    stop_loss NUMERIC(20, 4),

    -- Volume to Target
    volume_between_entry_target BIGINT,
    volume_at_target_level BIGINT,
    avg_volume_last_20_bars BIGINT,

    -- Liquidity Assessment
    volume_adequate BOOLEAN,
    adequate_score INTEGER CHECK (adequate_score >= 0 AND adequate_score <= 100),
    slippage_estimated NUMERIC(10, 4),
    slippage_pct NUMERIC(10, 6),

    -- Risk Assessment
    liquidity_risk VARCHAR(20),
    recommendation VARCHAR(50),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_liquidity_symbol_time
    ON liquidity_depth_analysis(symbol, timestamp DESC);

-- Create table for order flow metrics history (for trend analysis)
CREATE TABLE IF NOT EXISTS order_flow_metrics_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    symbol VARCHAR(20) NOT NULL,
    metric_timestamp TIMESTAMPTZ NOT NULL,

    -- Window metrics (1-minute aggregates)
    period_seconds INTEGER,
    avg_buyer_pressure INTEGER,
    avg_seller_pressure INTEGER,
    avg_liquidity_score INTEGER,
    avg_spread_pct NUMERIC(10, 6),
    avg_bid_ask_ratio NUMERIC(10, 4),

    -- Trend indicators
    pressure_trend VARCHAR(20),  -- INCREASING, DECREASING, STABLE
    liquidity_trend VARCHAR(20),

    -- Volume metrics
    total_volume_period BIGINT,
    total_trades_period BIGINT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_metrics_history_symbol
    ON order_flow_metrics_history(symbol, metric_timestamp DESC);

-- Create validation log table for debugging
CREATE TABLE IF NOT EXISTS order_flow_validation_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    symbol VARCHAR(20) NOT NULL,
    snapshot_id UUID,

    -- Validation result
    is_valid BOOLEAN,
    validation_timestamp TIMESTAMPTZ NOT NULL,

    -- Error details if invalid
    error_codes TEXT,
    error_messages TEXT,

    -- Validation metrics
    snapshot_age_ms BIGINT,
    spread_pct NUMERIC(10, 6),
    bid_ask_ratio NUMERIC(10, 4),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_validation_log_symbol
    ON order_flow_validation_log(symbol, created_at DESC);

-- Add comments for documentation
COMMENT ON TABLE order_flow_snapshots IS
    'Real-time order book snapshots with calculated pressure and liquidity metrics';

COMMENT ON TABLE volume_profile_intraday IS
    'Volume profile analysis - price levels where volume concentrates';

COMMENT ON TABLE liquidity_depth_analysis IS
    'Liquidity analysis for entry/target/SL assessment';

COMMENT ON TABLE order_flow_metrics_history IS
    'Aggregated metrics over time windows for trend analysis';

COMMENT ON TABLE order_flow_validation_log IS
    'Validation results for data quality monitoring';

-- Retention policy comment
-- Order flow snapshots: Keep 30 days
-- After 30 days: Archive to order_flow_archive table or delete
-- This prevents table from growing unbounded

COMMENT ON COLUMN order_flow_snapshots.buyer_pressure_score IS
    'Score 0-100: Likelihood of upward price movement based on bid-ask pressure';

COMMENT ON COLUMN order_flow_snapshots.liquidity_score IS
    'Score 0-100: Liquidity quality (tight spread, good depth, balanced book)';

COMMENT ON COLUMN order_flow_snapshots.pressure_type IS
    'Classification: STRONG_BUY_PRESSURE, BUY_PRESSURE, NEUTRAL, SELL_PRESSURE, STRONG_SELL_PRESSURE, POOR_LIQUIDITY';
