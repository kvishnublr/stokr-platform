-- V104: Increase INDEX_HUNT per-strategy max_positions from default 2 to 5.
-- The code default was too restrictive for a multi-symbol strategy.

UPDATE strategy_execution_configs SET
    max_positions = 5,
    updated_at = now()
WHERE strategy_key = 'INDEX_HUNT'
  AND user_id IS NULL
  AND deleted = FALSE;
