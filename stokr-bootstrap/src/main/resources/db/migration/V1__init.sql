-- stokr_platform database: single schema (public), prefixed table names

CREATE TABLE auth_roles (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    name VARCHAR(64) NOT NULL UNIQUE
);

CREATE TABLE auth_users (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(200) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE UNIQUE INDEX ux_auth_users_email_active ON auth_users (lower(email)) WHERE deleted = FALSE;

CREATE TABLE auth_user_roles (
    user_id UUID NOT NULL REFERENCES auth_users (id),
    role_id UUID NOT NULL REFERENCES auth_roles (id),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE auth_refresh_tokens (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    user_id UUID NOT NULL REFERENCES auth_users (id),
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_auth_refresh_tokens_user ON auth_refresh_tokens (user_id);

CREATE TABLE user_profiles (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    user_id UUID NOT NULL UNIQUE,
    display_name VARCHAR(200),
    timezone VARCHAR(64),
    preferences_json TEXT,
    subscription_plan VARCHAR(32) NOT NULL,
    risk_profile VARCHAR(32) NOT NULL,
    account_status VARCHAR(32) NOT NULL,
    broker_account_placeholder VARCHAR(500),
    trading_preferences_json TEXT
);

CREATE TABLE broker_accounts (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    user_id UUID NOT NULL,
    vendor_code VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    metadata_json TEXT
);

CREATE INDEX idx_broker_accounts_user ON broker_accounts (user_id);

CREATE TABLE strategy_definitions (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    strategy_key VARCHAR(128) NOT NULL UNIQUE,
    description VARCHAR(500),
    config_json TEXT
);

CREATE TABLE strategy_instances (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    definition_id UUID NOT NULL REFERENCES strategy_definitions (id),
    user_id UUID NOT NULL,
    symbol VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_strategy_instances_user ON strategy_instances (user_id);

CREATE TABLE strategy_signals (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    instance_id UUID NOT NULL REFERENCES strategy_instances (id),
    signal_type VARCHAR(16) NOT NULL,
    reason VARCHAR(500),
    suggested_qty NUMERIC(24, 8)
);

CREATE INDEX idx_strategy_signals_instance ON strategy_signals (instance_id);

CREATE TABLE oms_orders (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    user_id UUID NOT NULL,
    correlation_id VARCHAR(64),
    idempotency_key VARCHAR(128),
    symbol VARCHAR(64) NOT NULL,
    side VARCHAR(8) NOT NULL,
    order_type VARCHAR(32) NOT NULL,
    quantity NUMERIC(24, 8) NOT NULL,
    limit_price NUMERIC(24, 8),
    state VARCHAR(32) NOT NULL,
    broker_vendor VARCHAR(32),
    broker_order_id UUID,
    reject_reason VARCHAR(500)
);

CREATE INDEX idx_oms_orders_user ON oms_orders (user_id);
CREATE UNIQUE INDEX ux_oms_orders_user_idempotency
    ON oms_orders (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL AND deleted = FALSE;

CREATE TABLE oms_executions (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    order_id UUID NOT NULL REFERENCES oms_orders (id),
    broker_execution_id VARCHAR(128),
    filled_qty NUMERIC(24, 8) NOT NULL,
    avg_price NUMERIC(24, 8)
);

CREATE INDEX idx_oms_executions_order ON oms_executions (order_id);

CREATE TABLE oms_trades (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    order_id UUID NOT NULL REFERENCES oms_orders (id),
    execution_id UUID NOT NULL REFERENCES oms_executions (id),
    quantity NUMERIC(24, 8) NOT NULL,
    price NUMERIC(24, 8) NOT NULL
);

CREATE INDEX idx_oms_trades_order ON oms_trades (order_id);

CREATE TABLE risk_rules (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    user_id UUID,
    code VARCHAR(64) NOT NULL,
    config_json TEXT,
    max_daily_loss NUMERIC(24, 8),
    max_open_positions INTEGER,
    max_capital_allocation NUMERIC(24, 8),
    cooldown_seconds INTEGER
);

CREATE INDEX idx_risk_rules_user ON risk_rules (user_id);

CREATE TABLE risk_events (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    user_id UUID NOT NULL,
    order_id UUID,
    rule_code VARCHAR(64),
    result VARCHAR(16) NOT NULL,
    message VARCHAR(500)
);

CREATE INDEX idx_risk_events_user ON risk_events (user_id);

CREATE TABLE portfolio_positions (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    user_id UUID NOT NULL,
    symbol VARCHAR(64) NOT NULL,
    quantity NUMERIC(24, 8) NOT NULL,
    avg_price NUMERIC(24, 8)
);

CREATE INDEX idx_portfolio_positions_user ON portfolio_positions (user_id);

CREATE TABLE portfolio_pnl_snapshots (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    user_id UUID NOT NULL,
    as_of TIMESTAMPTZ NOT NULL,
    pnl NUMERIC(24, 8) NOT NULL
);

CREATE INDEX idx_portfolio_pnl_user ON portfolio_pnl_snapshots (user_id);

CREATE TABLE marketdata_candles (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    symbol VARCHAR(64) NOT NULL,
    timeframe VARCHAR(16) NOT NULL,
    open_time TIMESTAMPTZ NOT NULL,
    open_price NUMERIC(24, 8) NOT NULL,
    high_price NUMERIC(24, 8) NOT NULL,
    low_price NUMERIC(24, 8) NOT NULL,
    close_price NUMERIC(24, 8) NOT NULL,
    volume NUMERIC(24, 8)
);

CREATE INDEX idx_marketdata_candles_symbol_tf_time ON marketdata_candles (symbol, timeframe, open_time);

CREATE TABLE backtest_runs (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    strategy_key VARCHAR(128) NOT NULL,
    symbol VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    seed BIGINT NOT NULL
);

CREATE TABLE backtest_results (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    run_id UUID NOT NULL UNIQUE REFERENCES backtest_runs (id),
    roi NUMERIC(24, 8),
    win_rate NUMERIC(24, 8),
    sharpe_ratio NUMERIC(24, 8),
    max_drawdown NUMERIC(24, 8),
    pnl NUMERIC(24, 8),
    profit_factor NUMERIC(24, 8)
);

CREATE TABLE backtest_trades (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    run_id UUID NOT NULL REFERENCES backtest_runs (id),
    symbol VARCHAR(64) NOT NULL,
    quantity NUMERIC(24, 8) NOT NULL,
    price NUMERIC(24, 8) NOT NULL
);

CREATE INDEX idx_backtest_trades_run ON backtest_trades (run_id);

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    actor_user_id UUID,
    action VARCHAR(128) NOT NULL,
    resource_type VARCHAR(128),
    resource_id VARCHAR(128),
    payload_json TEXT,
    correlation_id VARCHAR(64)
);

CREATE INDEX idx_audit_logs_created ON audit_logs (created_at DESC);

INSERT INTO auth_roles (id, created_at, updated_at, version, deleted, name)
VALUES ('11111111-1111-1111-1111-111111111101', NOW(), NOW(), 0, FALSE, 'ROLE_ADMIN');

INSERT INTO auth_roles (id, created_at, updated_at, version, deleted, name)
VALUES ('11111111-1111-1111-1111-111111111102', NOW(), NOW(), 0, FALSE, 'ROLE_TRADER');
