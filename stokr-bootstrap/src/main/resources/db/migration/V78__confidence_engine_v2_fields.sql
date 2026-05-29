ALTER TABLE strategy_signals
    ADD COLUMN IF NOT EXISTS probability NUMERIC(10, 6),
    ADD COLUMN IF NOT EXISTS trade_quality VARCHAR(32),
    ADD COLUMN IF NOT EXISTS confidence_version VARCHAR(32),
    ADD COLUMN IF NOT EXISTS confidence_breakdown_json TEXT;

CREATE INDEX IF NOT EXISTS idx_strategy_signals_confidence_version
    ON strategy_signals (confidence_version);
