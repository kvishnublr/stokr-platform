-- =============================================================
-- Stokr Lite - Signals Table
-- =============================================================

CREATE TABLE IF NOT EXISTS strategy_signals (
    id              BIGSERIAL PRIMARY KEY,
    deployment_id   BIGINT       REFERENCES deployments(id) ON DELETE CASCADE,
    user_id         BIGINT       REFERENCES users(id) ON DELETE CASCADE,
    strategy_id     BIGINT       REFERENCES strategies(id),
    symbol          VARCHAR(50)  NOT NULL,
    side            VARCHAR(10)  NOT NULL,
    entry_price     DECIMAL(15,4),
    stop_loss       DECIMAL(15,4),
    target          DECIMAL(15,4),
    confidence      DECIMAL(5,2),
    reason          VARCHAR(500),
    status          VARCHAR(20)  NOT NULL DEFAULT 'GENERATED',
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_signals_user_id ON strategy_signals(user_id);
CREATE INDEX IF NOT EXISTS idx_signals_deployment_id ON strategy_signals(deployment_id);
CREATE INDEX IF NOT EXISTS idx_signals_status ON strategy_signals(status);
CREATE INDEX IF NOT EXISTS idx_signals_created_at ON strategy_signals(created_at);
CREATE INDEX IF NOT EXISTS idx_signals_user_created ON strategy_signals(user_id, created_at);
