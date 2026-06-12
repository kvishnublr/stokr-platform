-- V112: Drop the stale global unique index ux_sec_strategy_key.
--
-- Background: V40 created ux_sec_strategy_key as a UNIQUE INDEX on (strategy_key).
-- V45 introduced per-user override rows (user_id != NULL) and tried to drop the old
-- rule with `ALTER TABLE ... DROP CONSTRAINT IF EXISTS ux_sec_strategy_key`. That is a
-- no-op for an INDEX (it only drops table CONSTRAINTs), so the index survived and kept
-- enforcing global uniqueness on strategy_key alone — rejecting any trader-specific row
-- whenever a global (user_id IS NULL) row for the same strategy_key already existed.
-- This silently broke ALL trader per-strategy overrides (capital allocation, settings).
--
-- The correct uniqueness is already enforced by the partial indexes added in V45:
--   ux_sec_global_strategy  -> one global config per strategy_key (user_id IS NULL)
--   ux_sec_user_strategy    -> one override per (user_id, strategy_key) (user_id NOT NULL)
-- so removing ux_sec_strategy_key does not weaken data integrity.

DROP INDEX IF EXISTS ux_sec_strategy_key;
