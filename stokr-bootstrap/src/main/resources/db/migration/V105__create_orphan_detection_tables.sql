-- V105: Create Orphan Detection Tables
-- Creates broker position observations and orphan detection infrastructure

CREATE TABLE IF NOT EXISTS broker_position_observations (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN DEFAULT FALSE,

    user_id UUID NOT NULL,
    symbol VARCHAR(64) NOT NULL,
    broker_quantity NUMERIC(24,8) NOT NULL,
    broker_entry_price NUMERIC(24,8),
    broker_entry_time TIMESTAMPTZ,
    observation_time TIMESTAMPTZ NOT NULL,

    broker_order_id VARCHAR(64),
    broker_position_type VARCHAR(32),
    broker_entry_source VARCHAR(128),
    broker_tags TEXT,

    is_orphaned BOOLEAN,
    matched_oms_order_id UUID,

    notes TEXT,

    CONSTRAINT fk_obs_user FOREIGN KEY (user_id) REFERENCES auth_users(id)
);

CREATE INDEX idx_obs_user_symbol_time ON broker_position_observations(user_id, symbol, observation_time DESC);
CREATE INDEX idx_obs_is_orphaned ON broker_position_observations(is_orphaned, observation_time DESC);
CREATE INDEX idx_obs_time ON broker_position_observations(observation_time DESC);

-- Partition broker_position_observations by month for performance
CREATE TABLE broker_position_observations_2026_06 PARTITION OF broker_position_observations
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');

CREATE TABLE IF NOT EXISTS orphan_detected_positions (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN DEFAULT FALSE,

    user_id UUID NOT NULL,
    symbol VARCHAR(64) NOT NULL,
    quantity NUMERIC(24,8) NOT NULL,
    broker_entry_time TIMESTAMPTZ NOT NULL,
    broker_entry_price NUMERIC(24,8),
    current_value NUMERIC(24,8),

    detection_timestamp TIMESTAMPTZ NOT NULL,
    detection_source VARCHAR(64),

    status VARCHAR(32) NOT NULL,
    notes TEXT,

    CONSTRAINT fk_orphan_user FOREIGN KEY (user_id) REFERENCES auth_users(id)
);

CREATE INDEX idx_orphan_user_symbol ON orphan_detected_positions(user_id, symbol);
CREATE INDEX idx_orphan_detected_time ON orphan_detected_positions(detection_timestamp DESC);
CREATE INDEX idx_orphan_status ON orphan_detected_positions(status);
CREATE INDEX idx_orphan_do_not_touch ON orphan_detected_positions(status) WHERE status = 'DO_NOT_TOUCH';

CREATE TABLE IF NOT EXISTS orphan_classification_results (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,

    orphan_id UUID NOT NULL,
    classification_type VARCHAR(32) NOT NULL,
    confidence_level VARCHAR(32) NOT NULL,
    evidence_score INT NOT NULL,

    supporting_evidence TEXT,
    blocking_issues TEXT,

    matched_signal_id UUID,
    signal_confidence INT,

    broker_entry_source_found BOOLEAN,
    broker_entry_source_value VARCHAR(128),

    evidence_breakdown TEXT,
    classification_reason TEXT,

    status VARCHAR(32) NOT NULL,

    CONSTRAINT fk_classification_orphan FOREIGN KEY (orphan_id) REFERENCES orphan_detected_positions(id),
    CONSTRAINT fk_classification_signal FOREIGN KEY (matched_signal_id) REFERENCES strategy_signal(id)
);

CREATE INDEX idx_classification_orphan ON orphan_classification_results(orphan_id);
CREATE INDEX idx_classification_type ON orphan_classification_results(classification_type);
CREATE INDEX idx_classification_score ON orphan_classification_results(evidence_score DESC);
CREATE INDEX idx_classification_status ON orphan_classification_results(status);
CREATE INDEX idx_classification_do_not_touch ON orphan_classification_results(status) WHERE status = 'DO_NOT_TOUCH';
