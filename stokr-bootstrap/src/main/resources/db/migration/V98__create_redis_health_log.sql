-- V008: Create redis_health_log table
-- Purpose: Track Redis connection health for monitoring and debugging
-- Used by: RedisConnectionMonitor (WS 11)

CREATE TABLE redis_health_log (
    id UUID PRIMARY KEY,
    check_time TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

    -- Health status
    is_healthy BOOLEAN,
    connection_active BOOLEAN,
    connection_factory_state VARCHAR(32),
        -- STARTED, STOPPED, STOPPING

    -- Pool stats
    connections_received INTEGER,
    connections_rejected INTEGER,
    current_connections INTEGER,

    -- Memory stats
    memory_used_mb NUMERIC(10, 2),
    memory_peak_mb NUMERIC(10, 2),
    cache_hits BIGINT,
    cache_misses BIGINT,
    miss_rate_percent NUMERIC(5, 2),

    -- Performance
    ops_per_second INTEGER,
    avg_latency_ms NUMERIC(10, 2),

    -- Issues
    has_issues BOOLEAN DEFAULT FALSE,
    issues_json TEXT,  -- JSON array of issues

    -- Action taken
    auto_recovery_attempted BOOLEAN DEFAULT FALSE,
    recovery_successful BOOLEAN
);

CREATE INDEX idx_redis_health_check_time ON redis_health_log(check_time DESC);
CREATE INDEX idx_redis_health_is_healthy ON redis_health_log(is_healthy);
CREATE INDEX idx_redis_health_has_issues ON redis_health_log(has_issues);

COMMENT ON TABLE redis_health_log IS 'Health history of Redis connection for monitoring and diagnostics';
