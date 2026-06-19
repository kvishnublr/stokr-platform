-- Add exit tracking columns to strategy_signals table
ALTER TABLE strategy_signals ADD COLUMN IF NOT EXISTS entry_time TIMESTAMP NULL;
ALTER TABLE strategy_signals ADD COLUMN IF NOT EXISTS exit_time TIMESTAMP NULL;
ALTER TABLE strategy_signals ADD COLUMN IF NOT EXISTS exit_type VARCHAR(20) NULL;

-- Create index for efficient exit type queries
CREATE INDEX IF NOT EXISTS idx_strategy_signals_exit_type ON strategy_signals(exit_type);
CREATE INDEX IF NOT EXISTS idx_strategy_signals_exit_time ON strategy_signals(exit_time);
