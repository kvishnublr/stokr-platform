CREATE TABLE trader_configs (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    mode VARCHAR(10) NOT NULL DEFAULT 'PAPER',
    capital NUMERIC(15,2) NOT NULL DEFAULT 15000,
    max_positions INT NOT NULL DEFAULT 3,
    min_share_price NUMERIC(10,2) NOT NULL DEFAULT 200,
    max_share_price NUMERIC(10,2) NOT NULL DEFAULT 3000,
    stop_loss_pct NUMERIC(5,2) NOT NULL DEFAULT 0.2,
    target_pct NUMERIC(5,2) NOT NULL DEFAULT 0.6,
    max_daily_loss NUMERIC(15,2) NOT NULL DEFAULT 225,
    min_trade_gap_minutes INT NOT NULL DEFAULT 2,
    max_consecutive_losses INT NOT NULL DEFAULT 3,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_trader_configs_user_id ON trader_configs(user_id);
