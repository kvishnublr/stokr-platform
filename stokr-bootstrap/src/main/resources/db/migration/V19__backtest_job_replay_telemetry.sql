-- Replay operational telemetry + explicit terminal diagnosis (no silent COMPLETED + zero metrics).

ALTER TABLE backtest_jobs ADD COLUMN IF NOT EXISTS replay_diagnosis VARCHAR(32);
ALTER TABLE backtest_jobs ADD COLUMN IF NOT EXISTS replay_candles_expected INTEGER NOT NULL DEFAULT 0;
ALTER TABLE backtest_jobs ADD COLUMN IF NOT EXISTS replay_candles_processed INTEGER NOT NULL DEFAULT 0;
ALTER TABLE backtest_jobs ADD COLUMN IF NOT EXISTS replay_signals_emitted INTEGER NOT NULL DEFAULT 0;
ALTER TABLE backtest_jobs ADD COLUMN IF NOT EXISTS replay_execution_events INTEGER NOT NULL DEFAULT 0;
ALTER TABLE backtest_jobs ADD COLUMN IF NOT EXISTS replay_duration_ms BIGINT;
