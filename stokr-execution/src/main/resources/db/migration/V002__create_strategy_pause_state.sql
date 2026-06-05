-- V002: Create strategy_pause_state table
-- Purpose: Track when strategies are paused (survives restart & deployment)
-- Critical: Enables EXIT_ALL durability

CREATE TABLE strategy_pause_state (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    strategy_name VARCHAR(128) NOT NULL,

    -- Pause state (CRITICAL FOR DURABILITY)
    current_state VARCHAR(32) NOT NULL,
        -- RUNNING, PAUSED, STOPPED, EXIT_ALL_PAUSED, KILLSWITCH_PAUSED

    -- Why paused
    pause_reason VARCHAR(255),

    -- Who triggered pause
    triggered_by VARCHAR(32),
        -- USER, SYSTEM, KILLSWITCH, MANUAL_EXIT

    triggered_by_id UUID,

    -- When to resume (if ever)
    resume_at TIMESTAMP WITH TIME ZONE,
    resume_condition VARCHAR(255),
        -- MANUAL = user must manually resume
        -- NEXT_SESSION = resume next trading session
        -- NULL = never resume in this session

    -- Timestamps
    paused_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    resumed_at TIMESTAMP WITH TIME ZONE,

    -- CRITICAL: Must survive restart & deployment
    survives_restart BOOLEAN DEFAULT TRUE,
    survives_deployment BOOLEAN DEFAULT TRUE,

    -- Unique: one pause state per strategy per user
    UNIQUE(user_id, strategy_name),

    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Indices for fast lookups
CREATE INDEX idx_pause_state_user ON strategy_pause_state(user_id);
CREATE INDEX idx_pause_state_strategy ON strategy_pause_state(strategy_name);
CREATE INDEX idx_pause_state_current ON strategy_pause_state(current_state);
CREATE INDEX idx_pause_state_paused_at ON strategy_pause_state(paused_at);

-- Comments
COMMENT ON TABLE strategy_pause_state IS 'Durable pause state that survives restart and deployment';
COMMENT ON COLUMN strategy_pause_state.current_state IS 'RUNNING=active, PAUSED=paused, EXIT_ALL_PAUSED=cannot resume, KILLSWITCH_PAUSED=risk triggered';
COMMENT ON COLUMN strategy_pause_state.survives_restart IS 'If true, pause state is restored on application startup';
COMMENT ON COLUMN strategy_pause_state.survives_deployment IS 'If true, pause state persists across code deployment';
