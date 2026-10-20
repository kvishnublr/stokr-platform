-- LivePosition.holdType (INTRADAY / OVERNIGHT_SWING) was added in code without a column,
-- which made every live_positions read fail with 'column hold_type does not exist'.
ALTER TABLE live_positions ADD COLUMN IF NOT EXISTS hold_type VARCHAR(50);
