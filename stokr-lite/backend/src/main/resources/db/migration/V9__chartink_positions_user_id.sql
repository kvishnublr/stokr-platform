ALTER TABLE chartink_positions ADD COLUMN IF NOT EXISTS user_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_chartink_positions_user_id ON chartink_positions(user_id);
