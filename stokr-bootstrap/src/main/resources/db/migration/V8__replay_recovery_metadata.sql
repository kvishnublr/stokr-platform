-- Checkpoint recovery metadata for deterministic resume (cursor within logical replay range).
ALTER TABLE replay_checkpoints ADD COLUMN IF NOT EXISTS recovery_metadata TEXT;
