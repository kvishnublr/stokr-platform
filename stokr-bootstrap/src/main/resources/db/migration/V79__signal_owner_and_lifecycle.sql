ALTER TABLE strategy_signals
    ADD COLUMN IF NOT EXISTS owner_type VARCHAR(32),
    ADD COLUMN IF NOT EXISTS lifecycle_status VARCHAR(32);

CREATE INDEX IF NOT EXISTS idx_strategy_signals_owner_type
    ON strategy_signals (owner_type);

CREATE INDEX IF NOT EXISTS idx_strategy_signals_lifecycle_status
    ON strategy_signals (lifecycle_status);
