-- Equity cash signals (ADV_CASH) — PostgreSQL

CREATE TABLE IF NOT EXISTS equity_signals (
    signal_id             BIGSERIAL PRIMARY KEY,
    symbol_name           VARCHAR(20)  NOT NULL,
    sector                VARCHAR(50)  NOT NULL,
    tier                  VARCHAR(10)  NOT NULL,
    direction             VARCHAR(10)  NOT NULL,
    current_price         NUMERIC(20, 4) NOT NULL,
    entry_level           NUMERIC(20, 4) NOT NULL,
    stop_loss_level       NUMERIC(20, 4) NOT NULL,
    target_level1         NUMERIC(20, 4) NOT NULL,
    target_level2         NUMERIC(20, 4),
    obi_score             NUMERIC(10, 4),
    obi_slope             NUMERIC(10, 4),
    volume_level          NUMERIC(20, 4),
    bid_ask_spread        NUMERIC(10, 4),
    vix_level             NUMERIC(10, 4),
    timeframe_alignment   INTEGER,
    confidence_level      INTEGER NOT NULL,
    quality_score         NUMERIC(10, 2) NOT NULL,
    execution_status      VARCHAR(20) NOT NULL,
    actual_entry_price    NUMERIC(20, 4),
    actual_exit_price     NUMERIC(20, 4),
    quantity              INTEGER,
    outcome_type          VARCHAR(20),
    profit_loss           NUMERIC(20, 4),
    slippage_entry        NUMERIC(10, 4),
    slippage_exit         NUMERIC(10, 4),
    time_detected         TIMESTAMPTZ NOT NULL,
    time_filled_at        TIMESTAMPTZ,
    time_closed_at        TIMESTAMPTZ,
    is_active             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMPTZ,
    notes                 TEXT
);

CREATE INDEX IF NOT EXISTS idx_symbol_active ON equity_signals (symbol_name, is_active, time_detected);
CREATE INDEX IF NOT EXISTS idx_confidence_time ON equity_signals (confidence_level, time_detected);
CREATE INDEX IF NOT EXISTS idx_sector_direction ON equity_signals (sector, direction, is_active);
CREATE INDEX IF NOT EXISTS idx_created_at ON equity_signals (created_at);
CREATE INDEX IF NOT EXISTS idx_outcome ON equity_signals (outcome_type);
