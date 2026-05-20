CREATE TABLE strategy_daily_pnl_snapshots (
    id                UUID         PRIMARY KEY,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version           BIGINT       NOT NULL DEFAULT 0,
    deleted           BOOLEAN      NOT NULL DEFAULT FALSE,
    strategy_key      VARCHAR(128) NOT NULL,
    business_date     DATE         NOT NULL,
    realized_pnl      NUMERIC(24,8) NOT NULL DEFAULT 0,
    unrealized_pnl    NUMERIC(24,8) NOT NULL DEFAULT 0,
    trade_count       INT          NOT NULL DEFAULT 0,
    CONSTRAINT ux_sdps UNIQUE (strategy_key, business_date)
);

CREATE INDEX idx_sdps_key_date ON strategy_daily_pnl_snapshots (strategy_key, business_date DESC) WHERE deleted = FALSE;

ALTER TABLE portfolio_positions ADD COLUMN IF NOT EXISTS strategy_key VARCHAR(128);

CREATE INDEX idx_pp_strategy ON portfolio_positions (strategy_key) WHERE deleted = FALSE AND strategy_key IS NOT NULL;
