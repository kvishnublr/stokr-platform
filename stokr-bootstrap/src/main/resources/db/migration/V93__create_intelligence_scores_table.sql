-- V93: Create Intelligence Scores Table for Batch Updates
-- Purpose: Store pre-calculated intelligence scores for all symbols
-- Triggers: OrderFlowBatchUpdateService updates this every 1 minute

CREATE TABLE IF NOT EXISTS intelligence_scores (
    id SERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL UNIQUE,
    confidence INTEGER CHECK (confidence >= 0 AND confidence <= 100),
    recommendation VARCHAR(30),
    buyer_pressure_score INTEGER CHECK (buyer_pressure_score >= 0 AND buyer_pressure_score <= 100),
    seller_pressure_score INTEGER CHECK (seller_pressure_score >= 0 AND seller_pressure_score <= 100),
    liquidity_score INTEGER CHECK (liquidity_score >= 0 AND liquidity_score <= 100),
    spread_score INTEGER CHECK (spread_score >= 0 AND spread_score <= 100),
    confidence_multiplier DECIMAL(4, 2),
    signal_strength VARCHAR(30),
    should_enhance_confidence BOOLEAN DEFAULT false,
    should_reduce_confidence BOOLEAN DEFAULT false,
    should_skip BOOLEAN DEFAULT false,
    last_updated TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Performance indexes for quick lookups
CREATE INDEX IF NOT EXISTS idx_intelligence_symbol ON intelligence_scores(symbol);
CREATE INDEX IF NOT EXISTS idx_intelligence_confidence ON intelligence_scores(confidence DESC);
CREATE INDEX IF NOT EXISTS idx_intelligence_recommendation ON intelligence_scores(recommendation);
CREATE INDEX IF NOT EXISTS idx_intelligence_updated ON intelligence_scores(last_updated DESC);
CREATE INDEX IF NOT EXISTS idx_intelligence_pressure ON intelligence_scores(buyer_pressure_score DESC);
CREATE INDEX IF NOT EXISTS idx_intelligence_liquidity ON intelligence_scores(liquidity_score DESC);

-- Comment for documentation
COMMENT ON TABLE intelligence_scores IS 'Pre-calculated intelligence scores for all symbols, updated every 1 minute by OrderFlowBatchUpdateService. Used for fast dashboard lookups.';
COMMENT ON COLUMN intelligence_scores.confidence IS 'Overall confidence score (0-100) based on order flow analysis';
COMMENT ON COLUMN intelligence_scores.recommendation IS 'Signal recommendation: STRONG_BUY_PRESSURE, BUY_PRESSURE, NEUTRAL, SELL_PRESSURE, POOR_LIQUIDITY_SKIP';
COMMENT ON COLUMN intelligence_scores.confidence_multiplier IS 'Multiplier to apply to pattern-based signal (0.5x to 1.5x)';
COMMENT ON COLUMN intelligence_scores.last_updated IS 'Timestamp of last batch update (every 1 minute)';
