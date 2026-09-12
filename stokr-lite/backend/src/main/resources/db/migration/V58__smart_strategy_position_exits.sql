-- Per-position exit rules for Smart Strategy trades
ALTER TABLE live_positions ADD COLUMN IF NOT EXISTS sl_pct DOUBLE PRECISION;
ALTER TABLE live_positions ADD COLUMN IF NOT EXISTS target_pct DOUBLE PRECISION;
ALTER TABLE live_positions ADD COLUMN IF NOT EXISTS time_exit_minutes INTEGER;
ALTER TABLE live_positions ADD COLUMN IF NOT EXISTS max_loss_amount DOUBLE PRECISION;
ALTER TABLE live_positions ADD COLUMN IF NOT EXISTS max_profit_amount DOUBLE PRECISION;
ALTER TABLE live_positions ADD COLUMN IF NOT EXISTS exit_reason VARCHAR(50);
