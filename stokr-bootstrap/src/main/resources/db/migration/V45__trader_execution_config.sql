-- Phase: Trader self-service execution config
-- Adds user_id to strategy_execution_configs so traders can have per-user overrides.
-- NULL user_id = global admin config. Non-NULL user_id = trader-specific override.

ALTER TABLE strategy_execution_configs ADD COLUMN IF NOT EXISTS user_id UUID;

-- Drop old global unique constraint (was strategy_key only)
ALTER TABLE strategy_execution_configs DROP CONSTRAINT IF EXISTS ux_sec_strategy_key;

-- Partial unique index: only one global (admin) config per strategy key
CREATE UNIQUE INDEX IF NOT EXISTS ux_sec_global_strategy
    ON strategy_execution_configs (strategy_key)
    WHERE user_id IS NULL AND deleted = FALSE;

-- Partial unique index: only one trader override per (user, strategy)
CREATE UNIQUE INDEX IF NOT EXISTS ux_sec_user_strategy
    ON strategy_execution_configs (user_id, strategy_key)
    WHERE user_id IS NOT NULL AND deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_sec_user_key
    ON strategy_execution_configs (user_id, strategy_key)
    WHERE deleted = FALSE;
