-- Trade Watches - user-initiated trade monitoring
-- When a user accepts a Trade Plan recommendation, the system
-- creates a row here. The TradeWatchScheduler polls these rows
-- every few seconds and:
--   1) Updates current_ltp / current_pnl
--   2) Trails the SL upward (Chandelier method) as price moves favorably
--   3) Checks for reversal triggers (volume vacuum, OBI flip, candle
--      reversal, strategy gate failure)
--   4) Closes the watch (EXITED) with exit_reason when any trigger fires

CREATE TABLE IF NOT EXISTS trade_watches (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version              BIGINT NOT NULL DEFAULT 0,
    deleted              BOOLEAN NOT NULL DEFAULT FALSE,

    -- Identity
    user_id              UUID NOT NULL,
    signal_id            UUID,
    strategy_key         VARCHAR(64) NOT NULL,
    symbol               VARCHAR(64) NOT NULL,
    side                 VARCHAR(8) NOT NULL,
    execution_mode       VARCHAR(16) NOT NULL DEFAULT 'PAPER',
    oms_order_id         UUID,

    -- Trade plan (snapshot at entry)
    entry_price          NUMERIC(18,4) NOT NULL,
    stop_loss            NUMERIC(18,4) NOT NULL,
    target_1             NUMERIC(18,4),
    target_2             NUMERIC(18,4),
    quantity             NUMERIC(18,4) NOT NULL,
    risk_per_share       NUMERIC(18,4),
    risk_amount          NUMERIC(18,4),
    atr_at_entry         NUMERIC(18,4),

    -- Live tracking
    current_ltp          NUMERIC(18,4),
    high_water_mark      NUMERIC(18,4),
    trailing_stop        NUMERIC(18,4),
    current_pnl          NUMERIC(18,4) DEFAULT 0,
    current_pnl_pct      NUMERIC(8,4) DEFAULT 0,
    target_1_hit         BOOLEAN NOT NULL DEFAULT FALSE,

    -- State machine
    status               VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    exit_reason          VARCHAR(64),
    exit_price           NUMERIC(18,4),
    exit_at              TIMESTAMPTZ,
    final_pnl            NUMERIC(18,4),

    -- Telemetry
    last_check_at        TIMESTAMPTZ,
    check_count          INT NOT NULL DEFAULT 0,
    notes                JSONB
);

CREATE INDEX IF NOT EXISTS idx_trade_watches_user_active
    ON trade_watches (user_id, status)
    WHERE status = 'ACTIVE' AND deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_trade_watches_symbol_active
    ON trade_watches (symbol, status)
    WHERE status = 'ACTIVE' AND deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_trade_watches_strategy
    ON trade_watches (strategy_key, created_at DESC)
    WHERE deleted = FALSE;
