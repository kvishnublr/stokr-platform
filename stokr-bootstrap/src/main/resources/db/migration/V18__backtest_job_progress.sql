-- Async backtest job progress + ETA support.

ALTER TABLE backtest_jobs ADD COLUMN IF NOT EXISTS processed_bars INTEGER NOT NULL DEFAULT 0;
ALTER TABLE backtest_jobs ADD COLUMN IF NOT EXISTS started_at TIMESTAMPTZ;
