-- V32: Virtual wallets for paper trading + marketplace infrastructure

CREATE TABLE IF NOT EXISTS virtual_wallets (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL UNIQUE REFERENCES users(id),
    initial_balance DECIMAL(15,2) NOT NULL DEFAULT 20000,
    current_balance DECIMAL(15,2) NOT NULL DEFAULT 20000,
    total_pnl       DECIMAL(15,2) NOT NULL DEFAULT 0,
    total_trades    INT          NOT NULL DEFAULT 0,
    winning_trades  INT          NOT NULL DEFAULT 0,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Give existing users a virtual wallet
INSERT INTO virtual_wallets (user_id, initial_balance, current_balance)
SELECT id, 20000, 20000 FROM users
ON CONFLICT (user_id) DO NOTHING;

-- Add profit_share tracking to signals
ALTER TABLE strategy_signals
    ADD COLUMN IF NOT EXISTS profit_share_pct DECIMAL(5,2) DEFAULT 25.00,
    ADD COLUMN IF NOT EXISTS admin_commission DECIMAL(15,2) DEFAULT 0;

-- Index for marketplace queries
CREATE INDEX IF NOT EXISTS idx_signals_user_exit ON strategy_signals(user_id, exit_time) WHERE exit_type IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_signals_strategy_exit ON strategy_signals(strategy_id, exit_time) WHERE exit_type IS NOT NULL;
