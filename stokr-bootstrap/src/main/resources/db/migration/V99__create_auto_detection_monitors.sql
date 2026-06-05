-- V010: Create auto-detection system tables
-- Purpose: Monitor market data, strategy drift, position orphans (WS 13)
-- Auto-detection finds and resolves issues autonomously

CREATE TABLE market_data_staleness_log (
    id UUID PRIMARY KEY,
    check_time TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

    symbol VARCHAR(64),
    feed_source VARCHAR(32),

    -- Staleness metrics
    last_price_age_seconds INTEGER,
    last_volume_age_seconds INTEGER,
    last_open_interest_age_seconds INTEGER,

    is_stale BOOLEAN,
    stale_threshold_seconds INTEGER,

    -- Action taken
    alert_sent BOOLEAN DEFAULT FALSE,
    feed_restored_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE strategy_drift_detection_log (
    id UUID PRIMARY KEY,
    check_time TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

    user_id UUID NOT NULL,
    strategy_name VARCHAR(128),

    -- Drift metrics
    expected_entry_conditions_met BOOLEAN,
    actual_entry_conditions_met BOOLEAN,
    entry_drift_detected BOOLEAN,

    expected_exit_conditions_met BOOLEAN,
    actual_exit_conditions_met BOOLEAN,
    exit_drift_detected BOOLEAN,

    -- Position delta
    expected_open_positions INTEGER,
    actual_open_positions INTEGER,
    position_delta INTEGER,

    -- Action taken
    drift_severity VARCHAR(32),
        -- LOW, MEDIUM, HIGH
    alert_sent BOOLEAN DEFAULT FALSE,
    strategy_paused BOOLEAN DEFAULT FALSE,
    pause_reason VARCHAR(255),

    FOREIGN KEY (user_id) REFERENCES auth_users(id) ON DELETE CASCADE
);

CREATE TABLE position_orphan_detection_log (
    id UUID PRIMARY KEY,
    check_time TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

    user_id UUID NOT NULL,
    position_id UUID,
    symbol VARCHAR(64),

    -- Orphan indicators
    broker_has_position BOOLEAN,
    oms_has_position BOOLEAN,
    no_entry_signal_found BOOLEAN,
    no_entry_order_found BOOLEAN,

    -- Ownership
    owner_type_recorded VARCHAR(32),
    is_orphan BOOLEAN,

    -- Action taken
    synthetic_exit_created BOOLEAN DEFAULT FALSE,
    synthetic_exit_id UUID,
    ghost_removed BOOLEAN DEFAULT FALSE,

    FOREIGN KEY (user_id) REFERENCES auth_users(id) ON DELETE CASCADE,
    FOREIGN KEY (position_id) REFERENCES portfolio_positions(id) ON DELETE SET NULL,
    FOREIGN KEY (synthetic_exit_id) REFERENCES oms_executions(id) ON DELETE SET NULL
);

-- Indices for fast lookups
CREATE INDEX idx_staleness_symbol ON market_data_staleness_log(symbol);
CREATE INDEX idx_staleness_check_time ON market_data_staleness_log(check_time DESC);
CREATE INDEX idx_staleness_is_stale ON market_data_staleness_log(is_stale);

CREATE INDEX idx_drift_user_strategy ON strategy_drift_detection_log(user_id, strategy_name);
CREATE INDEX idx_drift_check_time ON strategy_drift_detection_log(check_time DESC);
CREATE INDEX idx_drift_detected ON strategy_drift_detection_log(entry_drift_detected, exit_drift_detected);

CREATE INDEX idx_orphan_user ON position_orphan_detection_log(user_id);
CREATE INDEX idx_orphan_position ON position_orphan_detection_log(position_id);
CREATE INDEX idx_orphan_check_time ON position_orphan_detection_log(check_time DESC);
CREATE INDEX idx_orphan_is_orphan ON position_orphan_detection_log(is_orphan);

COMMENT ON TABLE market_data_staleness_log IS 'Auto-detection of market data feed failures (WS13)';
COMMENT ON TABLE strategy_drift_detection_log IS 'Auto-detection of strategy behavior drift (WS13)';
COMMENT ON TABLE position_orphan_detection_log IS 'Auto-detection of orphaned/ghost positions (WS13)';
