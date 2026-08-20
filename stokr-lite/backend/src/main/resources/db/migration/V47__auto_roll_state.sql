-- Tracks Butterfly positions under auto-roll monitoring: if spot sits outside the profit
-- zone continuously for a configurable window, the position is closed automatically and a
-- re-centered replacement is proposed here, awaiting a one-click confirm before entry.
CREATE TABLE IF NOT EXISTS auto_roll_state (
    id BIGSERIAL PRIMARY KEY,
    original_position_id BIGINT,
    current_position_id BIGINT,
    underlying VARCHAR(20),
    option_type VARCHAR(5),
    lots INTEGER DEFAULT 1,
    roll_count INTEGER DEFAULT 0,
    breach_started_at TIMESTAMP,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    pending_proposal_json TEXT,
    last_closed_pnl NUMERIC(12,2),
    created_at TIMESTAMP DEFAULT now(),
    updated_at TIMESTAMP DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_auto_roll_state_status ON auto_roll_state(status);
CREATE INDEX IF NOT EXISTS idx_auto_roll_state_current_position ON auto_roll_state(current_position_id);
