-- Add exit tracking columns to strategy_signals table
ALTER TABLE strategy_signals ADD COLUMN entry_time TIMESTAMP NULL;
ALTER TABLE strategy_signals ADD COLUMN exit_time TIMESTAMP NULL;
ALTER TABLE strategy_signals ADD COLUMN exit_type VARCHAR(20) NULL;

-- Create index for efficient exit type queries
CREATE INDEX idx_strategy_signals_exit_type ON strategy_signals(exit_type);
CREATE INDEX idx_strategy_signals_exit_time ON strategy_signals(exit_time);
