-- INDEX HUNT signals (PostgreSQL)

CREATE TABLE IF NOT EXISTS index_signals (
    signal_id              BIGSERIAL PRIMARY KEY,
    index_name             VARCHAR(20)  NOT NULL,
    direction              VARCHAR(5)   NOT NULL,
    time_detected          TIMESTAMPTZ  NOT NULL,
    index_price            NUMERIC(10, 2) NOT NULL,
    option_entry_premium   NUMERIC(10, 2),
    option_sl              NUMERIC(10, 2),
    option_t1              NUMERIC(10, 2),
    option_t2              NUMERIC(10, 2),
    momentum_5m            NUMERIC(5, 4) NOT NULL,
    trend_30m              NUMERIC(5, 4),
    pcr_ratio              NUMERIC(5, 2),
    vix_level              NUMERIC(5, 2),
    session_open_price     NUMERIC(5, 4),
    recent_3min_low        NUMERIC(5, 4),
    recent_3min_high       NUMERIC(5, 4),
    quality_score          NUMERIC(5, 2),
    signal_strength        VARCHAR(20),
    gate_status            VARCHAR(30),
    all_gates_passed       BOOLEAN NOT NULL DEFAULT TRUE,
    execution_status       VARCHAR(20),
    is_active              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    filled_at              TIMESTAMPTZ,
    closed_at              TIMESTAMPTZ,
    actual_entry_premium   NUMERIC(10, 2),
    actual_exit_premium    NUMERIC(10, 2),
    pnl_per_contract       NUMERIC(10, 2),
    total_pnl              NUMERIC(10, 2),
    outcome_type           VARCHAR(20),
    notes                  VARCHAR(200)
);

CREATE INDEX IF NOT EXISTS idx_index_signals_active ON index_signals (is_active, time_detected);
CREATE INDEX IF NOT EXISTS idx_index_signals_quality ON index_signals (quality_score, time_detected);
CREATE INDEX IF NOT EXISTS idx_index_signals_index ON index_signals (index_name, direction, is_active);
