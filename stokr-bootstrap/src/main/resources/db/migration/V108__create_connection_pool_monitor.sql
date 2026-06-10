-- V108: Create connection_pool_monitor table
-- Purpose: Monitor and log connection pool state (WS 11 - Redis Resilience)

CREATE TABLE IF NOT EXISTS connection_pool_monitor (
    id UUID PRIMARY KEY,
    monitor_time TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

    pool_name VARCHAR(128) NOT NULL,
        -- REDIS_LETTUCE, POSTGRES_HIKARI, BROKER_API

    -- Pool state
    pool_state VARCHAR(32),
        -- STARTED, STOPPED, STOPPING
    is_operational BOOLEAN,
    is_accepting_connections BOOLEAN,

    -- Connection stats
    total_connections INTEGER,
    active_connections INTEGER,
    idle_connections INTEGER,
    pending_requests INTEGER,

    -- Rejection stats
    connections_rejected INTEGER,
    rejection_reason VARCHAR(255),

    -- Recent errors
    last_error_message TEXT,
    last_error_time TIMESTAMP WITH TIME ZONE,
    consecutive_errors INTEGER,

    -- Recovery attempts
    recovery_attempted BOOLEAN DEFAULT FALSE,
    recovery_successful BOOLEAN,
    recovery_attempt_time TIMESTAMP WITH TIME ZONE,

    -- Severity and actions
    severity VARCHAR(32),
        -- NORMAL, WARNING, CRITICAL
    auto_action_taken VARCHAR(255)
        -- RESTART_REQUESTED, FALLBACK_ENABLED, NONE
);

CREATE INDEX IF NOT EXISTS idx_pool_monitor_pool_name ON connection_pool_monitor(pool_name);
CREATE INDEX IF NOT EXISTS idx_pool_monitor_time ON connection_pool_monitor(monitor_time DESC);
CREATE INDEX IF NOT EXISTS idx_pool_monitor_state ON connection_pool_monitor(pool_state);
CREATE INDEX IF NOT EXISTS idx_pool_monitor_operational ON connection_pool_monitor(is_operational);
CREATE INDEX IF NOT EXISTS idx_pool_monitor_severity ON connection_pool_monitor(severity);

COMMENT ON TABLE connection_pool_monitor IS 'Monitor all database and broker connection pools (WS11)';
COMMENT ON COLUMN connection_pool_monitor.pool_name IS 'Which pool: REDIS_LETTUCE, POSTGRES_HIKARI, BROKER_API';
COMMENT ON COLUMN connection_pool_monitor.recovery_successful IS 'Whether auto-recovery fixed the issue';
