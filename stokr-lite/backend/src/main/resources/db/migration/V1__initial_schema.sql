-- =============================================================
-- Stokr Lite - Initial Schema Migration
-- =============================================================

-- Auth
CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    role            VARCHAR(20)  NOT NULL DEFAULT 'TRADER',
    enabled         BOOLEAN      NOT NULL DEFAULT true,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_email ON users(email);

-- User Profiles
CREATE TABLE user_profiles (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            VARCHAR(255),
    phone           VARCHAR(20),
    total_capital   DECIMAL(15,2) NOT NULL DEFAULT 0,
    risk_profile    VARCHAR(20)   NOT NULL DEFAULT 'MODERATE',
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_user_profiles_user_id ON user_profiles(user_id);

-- Broker Accounts
CREATE TABLE broker_accounts (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    broker_name     VARCHAR(50)  NOT NULL,
    client_id       VARCHAR(100),
    access_token    TEXT,
    refresh_token   TEXT,
    token_expiry    TIMESTAMP,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_broker_accounts_user_id ON broker_accounts(user_id);
CREATE INDEX idx_broker_accounts_status ON broker_accounts(status);

-- Strategies
CREATE TABLE strategies (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(100) NOT NULL UNIQUE,
    description     TEXT,
    strategy_type   VARCHAR(50)  NOT NULL,
    params_schema   JSONB,
    asset_class     VARCHAR(20)  NOT NULL DEFAULT 'EQUITY',
    enabled         BOOLEAN      NOT NULL DEFAULT true,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Deployments
CREATE TABLE deployments (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    strategy_id         BIGINT       NOT NULL REFERENCES strategies(id),
    broker_account_id   BIGINT       NOT NULL REFERENCES broker_accounts(id),
    mode                VARCHAR(10)  NOT NULL DEFAULT 'PAPER',
    capital             DECIMAL(15,2) NOT NULL DEFAULT 100000,
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_deployments_user_id ON deployments(user_id);
CREATE INDEX idx_deployments_status ON deployments(status);
CREATE INDEX idx_deployments_strategy_id ON deployments(strategy_id);

-- Orders
CREATE TABLE orders (
    id                  BIGSERIAL PRIMARY KEY,
    deployment_id       BIGINT       NOT NULL REFERENCES deployments(id) ON DELETE CASCADE,
    symbol              VARCHAR(50)  NOT NULL,
    side                VARCHAR(10)  NOT NULL,
    quantity            INTEGER      NOT NULL,
    price               DECIMAL(15,4),
    order_type          VARCHAR(20)  NOT NULL DEFAULT 'MARKET',
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    broker_order_id     VARCHAR(100),
    idempotency_key     VARCHAR(64)  UNIQUE,
    error_message       TEXT,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_orders_deployment_id ON orders(deployment_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_created_at ON orders(created_at);

-- Trades
CREATE TABLE trades (
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT       NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    fill_price      DECIMAL(15,4) NOT NULL,
    fill_quantity   INTEGER      NOT NULL,
    fill_time       TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_trades_order_id ON trades(order_id);

-- Positions
CREATE TABLE positions (
    id              BIGSERIAL PRIMARY KEY,
    deployment_id   BIGINT       NOT NULL REFERENCES deployments(id) ON DELETE CASCADE,
    symbol          VARCHAR(50)  NOT NULL,
    quantity        INTEGER      NOT NULL DEFAULT 0,
    avg_price       DECIMAL(15,4) NOT NULL DEFAULT 0,
    realized_pnl    DECIMAL(15,2) NOT NULL DEFAULT 0,
    unrealized_pnl  DECIMAL(15,2) NOT NULL DEFAULT 0,
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_positions_deployment_symbol ON positions(deployment_id, symbol);
CREATE INDEX idx_positions_deployment_id ON positions(deployment_id);

-- Daily PnL
CREATE TABLE daily_pnl (
    id              BIGSERIAL PRIMARY KEY,
    deployment_id   BIGINT       NOT NULL REFERENCES deployments(id) ON DELETE CASCADE,
    trade_date      DATE         NOT NULL,
    gross_pnl       DECIMAL(15,2) NOT NULL DEFAULT 0,
    net_pnl         DECIMAL(15,2) NOT NULL DEFAULT 0,
    trade_count     INTEGER      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX idx_daily_pnl_deployment_date ON daily_pnl(deployment_id, trade_date);

-- Risk Configs
CREATE TABLE risk_configs (
    id                  BIGSERIAL PRIMARY KEY,
    deployment_id       BIGINT       NOT NULL REFERENCES deployments(id) ON DELETE CASCADE,
    max_daily_loss      DECIMAL(15,2) NOT NULL DEFAULT 5000,
    max_positions       INTEGER      NOT NULL DEFAULT 3,
    max_qty_per_trade   INTEGER      NOT NULL DEFAULT 100
);

CREATE UNIQUE INDEX idx_risk_configs_deployment_id ON risk_configs(deployment_id);

-- Kill Switch State
CREATE TABLE kill_switch_state (
    id              BIGSERIAL PRIMARY KEY,
    is_active       BOOLEAN      NOT NULL DEFAULT false,
    activated_by    BIGINT       REFERENCES users(id),
    activated_at    TIMESTAMP,
    reason          TEXT,
    deactivated_at  TIMESTAMP
);

-- Insert initial kill switch row
INSERT INTO kill_switch_state (is_active) VALUES (false);

-- Error Logs
CREATE TABLE error_logs (
    id              BIGSERIAL PRIMARY KEY,
    deployment_id   BIGINT       REFERENCES deployments(id) ON DELETE SET NULL,
    error_type      VARCHAR(50)  NOT NULL,
    message         TEXT         NOT NULL,
    stack_trace     TEXT,
    severity        VARCHAR(20)  NOT NULL DEFAULT 'ERROR',
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_error_logs_created_at ON error_logs(created_at);
CREATE INDEX idx_error_logs_severity ON error_logs(severity);

-- Idempotency Keys
CREATE TABLE idempotency_keys (
    id              BIGSERIAL PRIMARY KEY,
    key_hash        VARCHAR(64)  NOT NULL UNIQUE,
    order_id        BIGINT       REFERENCES orders(id),
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP    NOT NULL
);

CREATE INDEX idx_idempotency_keys_expires ON idempotency_keys(expires_at);

-- Broker Token Refresh Log
CREATE TABLE broker_token_refresh_log (
    id                  BIGSERIAL PRIMARY KEY,
    broker_account_id   BIGINT       NOT NULL REFERENCES broker_accounts(id) ON DELETE CASCADE,
    status              VARCHAR(20)  NOT NULL,
    error_message       TEXT,
    refreshed_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    next_refresh_at     TIMESTAMP
);

CREATE INDEX idx_token_refresh_broker_id ON broker_token_refresh_log(broker_account_id);
