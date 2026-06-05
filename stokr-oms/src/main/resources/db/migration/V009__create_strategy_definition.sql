-- V009: Create strategy_definition table
-- Purpose: Enforce documented entry/exit criteria for all strategies (WS 12)
-- Requirement: EVERY strategy MUST have entry_criteria + exit_criteria defined

CREATE TABLE strategy_definition (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    strategy_name VARCHAR(128) NOT NULL,
    strategy_type VARCHAR(64),
        -- TREND_FOLLOWING, MEAN_REVERSION, RANGE_BOUND, NEWS_EVENT, etc.

    -- CRITICAL: Entry criteria (must be documented)
    entry_trigger VARCHAR(32) NOT NULL,
        -- SIGNAL, PRICE_LEVEL, VOLUME_SURGE, VOLATILITY, FEED, TECHNICAL
    entry_criteria_description TEXT NOT NULL,
    entry_validation_rule TEXT NOT NULL,
        -- JSON with {condition, min_threshold, max_threshold}

    -- CRITICAL: Exit criteria (must be documented)
    exit_trigger VARCHAR(32) NOT NULL,
        -- TARGET, STOPLOSS, TIME_BASED, VOLATILITY, FEED, PRESSURE
    exit_criteria_description TEXT NOT NULL,
    exit_validation_rule TEXT NOT NULL,
        -- JSON with {condition, min_threshold, max_threshold}

    -- Exit substrategy (must document ALL exit paths)
    has_target_exit BOOLEAN,
    target_exit_rule TEXT,
    has_stoploss_exit BOOLEAN,
    stoploss_exit_rule TEXT,
    has_trailing_exit BOOLEAN,
    trailing_exit_rule TEXT,
    has_time_exit BOOLEAN,
    time_exit_rule TEXT,
    has_pressure_exit BOOLEAN,
    pressure_exit_rule TEXT,

    -- Tuning parameters
    suggested_tuning_parameters TEXT,
        -- JSON of {param_name, current_value, suggested_range, reason}

    -- Risk parameters
    max_position_size NUMERIC(24, 8),
    max_daily_loss NUMERIC(20, 2),
    risk_per_trade_percent NUMERIC(5, 2),

    -- Status
    is_documented BOOLEAN DEFAULT FALSE,
    is_validated BOOLEAN DEFAULT FALSE,
    compliance_review_status VARCHAR(32),
        -- PENDING, APPROVED, FLAGGED, REJECTED

    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_modified_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    compliance_reviewed_at TIMESTAMP WITH TIME ZONE,
    reviewed_by_user_id UUID,

    UNIQUE(user_id, strategy_name),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (reviewed_by_user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX idx_strategy_def_user ON strategy_definition(user_id);
CREATE INDEX idx_strategy_def_name ON strategy_definition(strategy_name);
CREATE INDEX idx_strategy_def_documented ON strategy_definition(is_documented);
CREATE INDEX idx_strategy_def_validated ON strategy_definition(is_validated);
CREATE INDEX idx_strategy_def_compliance ON strategy_definition(compliance_review_status);

COMMENT ON TABLE strategy_definition IS 'Enforces entry/exit documentation for every strategy (WS12)';
COMMENT ON COLUMN strategy_definition.entry_criteria_description IS 'REQUIRED: Plain English description of when/why we enter';
COMMENT ON COLUMN strategy_definition.exit_criteria_description IS 'REQUIRED: Plain English description of when/why we exit';
COMMENT ON COLUMN strategy_definition.is_documented IS 'MUST be true before strategy can run';
COMMENT ON COLUMN strategy_definition.compliance_review_status IS 'Compliance sign-off required before LIVE';
