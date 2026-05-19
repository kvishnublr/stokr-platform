-- ============================================================
-- V35: Missing indexes on strategy_definitions
-- Speeds up the admin catalog page query:
--   SELECT * FROM strategy_definitions WHERE deleted = FALSE ORDER BY strategy_key
-- ============================================================

CREATE INDEX IF NOT EXISTS idx_strategy_definitions_deleted
    ON strategy_definitions (deleted);

CREATE INDEX IF NOT EXISTS idx_strategy_definitions_key_active
    ON strategy_definitions (strategy_key) WHERE deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_strategy_definitions_asset_class
    ON strategy_definitions (asset_class, segment) WHERE deleted = FALSE;
