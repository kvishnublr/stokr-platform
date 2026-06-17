-- =============================================================
-- Stokr Lite - Signal Enhancements for Chartink Integration
-- =============================================================

ALTER TABLE strategy_signals
    ADD COLUMN IF NOT EXISTS movement_score DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS signal_source VARCHAR(20) DEFAULT 'INTERNAL',
    ADD COLUMN IF NOT EXISTS scanner_name VARCHAR(100),
    ADD COLUMN IF NOT EXISTS metadata_json TEXT,
    ADD COLUMN IF NOT EXISTS failed_filters VARCHAR(200);

CREATE INDEX IF NOT EXISTS idx_signals_source ON strategy_signals(signal_source);
CREATE INDEX IF NOT EXISTS idx_signals_scanner ON strategy_signals(scanner_name);
CREATE INDEX IF NOT EXISTS idx_signals_movement_score ON strategy_signals(movement_score);
