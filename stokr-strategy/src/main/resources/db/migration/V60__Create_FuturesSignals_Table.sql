-- Futures signals (S3 / S7) ??? PostgreSQL

CREATE TABLE IF NOT EXISTS futures_signals (
    signal_id           BIGSERIAL PRIMARY KEY,
    strategy_name       VARCHAR(10)  NOT NULL,
    symbol_name         VARCHAR(20)  NOT NULL,
    direction           VARCHAR(10)  NOT NULL,
    current_price       NUMERIC(12, 2),
    entry_level         NUMERIC(12, 2) NOT NULL,
    stop_loss_level     NUMERIC(12, 2) NOT NULL,
    target_level1       NUMERIC(12, 2) NOT NULL,
    target_level2       NUMERIC(12, 2),
    vwap_level          NUMERIC(12, 2),
    sma20               NUMERIC(12, 2),
    sma50               NUMERIC(12, 2),
    range5m             NUMERIC(10, 6),
    momentum5m          NUMERIC(10, 6),
    volume5m            NUMERIC(20, 4),
    quality_score       NUMERIC(5, 2) NOT NULL,
    signal_strength     VARCHAR(20),
    execution_status    VARCHAR(20) NOT NULL,
    actual_entry_price  NUMERIC(12, 2),
    time_filled_at      TIMESTAMPTZ,
    lot_size            INTEGER,
    actual_exit_price   NUMERIC(12, 2),
    time_closed_at      TIMESTAMPTZ,
    outcome_type        VARCHAR(20),
    profit_loss         NUMERIC(12, 2),
    slippage_entry      NUMERIC(10, 6),
    slippage_exit       NUMERIC(10, 6),
    time_detected       TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    notes               TEXT
);

CREATE INDEX IF NOT EXISTS idx_strategy_active ON futures_signals (strategy_name, is_active, time_detected);
CREATE INDEX IF NOT EXISTS idx_quality_time ON futures_signals (quality_score, time_detected);
CREATE INDEX IF NOT EXISTS idx_symbol_direction ON futures_signals (symbol_name, direction, is_active);
CREATE INDEX IF NOT EXISTS idx_created ON futures_signals (created_at);
CREATE INDEX IF NOT EXISTS idx_outcome ON futures_signals (outcome_type);
