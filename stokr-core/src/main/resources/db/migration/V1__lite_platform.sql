-- ============================================================================
-- Stokr LITE Platform - Database Schema
-- Version: 7.0.0-LITE
-- ============================================================================

-- ============================================================================
-- ORGANIZATIONS
-- ============================================================================
CREATE TABLE organizations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(100) UNIQUE NOT NULL,
    plan_type VARCHAR(20) DEFAULT 'FREE' NOT NULL,
    max_users INTEGER DEFAULT 3,
    max_strategies INTEGER DEFAULT 10,
    billing_email VARCHAR(255),
    stripe_customer_id VARCHAR(255),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    deleted BOOLEAN DEFAULT FALSE
);

CREATE INDEX idx_organizations_slug ON organizations(slug);
CREATE INDEX idx_organizations_deleted ON organizations(deleted);

-- ============================================================================
-- USERS
-- ============================================================================
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID REFERENCES organizations(id),
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255),
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    role VARCHAR(20) DEFAULT 'TRADER' NOT NULL,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    email_verified BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    deleted BOOLEAN DEFAULT FALSE
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_org ON users(organization_id);
CREATE INDEX idx_users_deleted ON users(deleted);

-- ============================================================================
-- BROKER ACCOUNTS
-- ============================================================================
CREATE TABLE broker_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    organization_id UUID REFERENCES organizations(id),
    vendor_code VARCHAR(20) DEFAULT 'ZERODHA' NOT NULL,
    access_token_encrypted TEXT,
    api_key VARCHAR(100),
    api_secret_encrypted TEXT,
    request_token_encrypted TEXT,
    access_token_expiry TIMESTAMP,
    account_number VARCHAR(100),
    account_name VARCHAR(255),
    is_active BOOLEAN DEFAULT TRUE,
    last_sync_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    deleted BOOLEAN DEFAULT FALSE
);

CREATE INDEX idx_broker_user ON broker_accounts(user_id);
CREATE INDEX idx_broker_org ON broker_accounts(organization_id);
CREATE INDEX idx_broker_deleted ON broker_accounts(deleted);

-- ============================================================================
-- STRATEGIES
-- ============================================================================
CREATE TABLE strategies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID REFERENCES organizations(id),
    creator_id UUID REFERENCES users(id),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    code TEXT,
    parameters JSONB DEFAULT '{}',
    tags TEXT,
    is_active BOOLEAN DEFAULT TRUE,
    is_public BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    deleted BOOLEAN DEFAULT FALSE
);

CREATE INDEX idx_strategies_org ON strategies(organization_id);
CREATE INDEX idx_strategies_creator ON strategies(creator_id);
CREATE INDEX idx_strategies_deleted ON strategies(deleted);

-- ============================================================================
-- STRATEGY INSTANCES
-- ============================================================================
CREATE TABLE strategy_instances (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_id UUID NOT NULL REFERENCES strategies(id),
    user_id UUID NOT NULL REFERENCES users(id),
    organization_id UUID REFERENCES organizations(id),
    broker_account_id UUID REFERENCES broker_accounts(id),
    name VARCHAR(255),
    symbol VARCHAR(50) NOT NULL,
    enabled BOOLEAN DEFAULT TRUE,
    execution_mode VARCHAR(20) DEFAULT 'PAPER',
    allocation DECIMAL(18,2) DEFAULT 10000,
    max_position_size DECIMAL(18,2) DEFAULT 1000,
    risk_multiplier DECIMAL(5,2) DEFAULT 1.0,
    max_daily_loss DECIMAL(18,2) DEFAULT 500,
    status VARCHAR(20) DEFAULT 'STOPPED',
    started_at TIMESTAMP,
    stopped_at TIMESTAMP,
    last_signal_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    deleted BOOLEAN DEFAULT FALSE
);

CREATE INDEX idx_instances_strategy ON strategy_instances(strategy_id);
CREATE INDEX idx_instances_user ON strategy_instances(user_id);
CREATE INDEX idx_instances_org ON strategy_instances(organization_id);
CREATE INDEX idx_instances_status ON strategy_instances(status);
CREATE INDEX idx_instances_deleted ON strategy_instances(deleted);

-- ============================================================================
-- SIGNALS
-- ============================================================================
CREATE TABLE signals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    instance_id UUID NOT NULL REFERENCES strategy_instances(id),
    symbol VARCHAR(50) NOT NULL,
    signal_type VARCHAR(10) NOT NULL,
    side VARCHAR(10) NOT NULL,
    confidence DECIMAL(5,2),
    entry_price DECIMAL(18,4),
    target_price DECIMAL(18,4),
    stop_loss DECIMAL(18,4),
    quantity DECIMAL(18,4),
    status VARCHAR(20) DEFAULT 'PENDING',
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMP DEFAULT NOW(),
    executed_at TIMESTAMP,
    skipped_at TIMESTAMP,
    failed_at TIMESTAMP,
    failure_reason TEXT
);

CREATE INDEX idx_signals_instance ON signals(instance_id);
CREATE INDEX idx_signals_status ON signals(status);
CREATE INDEX idx_signals_created ON signals(created_at);

-- ============================================================================
-- ORDERS
-- ============================================================================
CREATE TABLE orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    instance_id UUID REFERENCES strategy_instances(id),
    signal_id UUID REFERENCES signals(id),
    user_id UUID NOT NULL REFERENCES users(id),
    symbol VARCHAR(50) NOT NULL,
    side VARCHAR(10) NOT NULL,
    order_type VARCHAR(20) DEFAULT 'MARKET',
    quantity DECIMAL(18,4),
    price DECIMAL(18,4),
    trigger_price DECIMAL(18,4),
    filled_quantity DECIMAL(18,4),
    average_price DECIMAL(18,4),
    status VARCHAR(20) DEFAULT 'PENDING',
    broker_order_id VARCHAR(100),
    exchange_order_id VARCHAR(100),
    exchange VARCHAR(20),
    product_type VARCHAR(20) DEFAULT 'MIS',
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMP DEFAULT NOW(),
    submitted_at TIMESTAMP,
    filled_at TIMESTAMP,
    cancelled_at TIMESTAMP,
    rejected_at TIMESTAMP,
    rejection_reason TEXT
);

CREATE INDEX idx_orders_user ON orders(user_id);
CREATE INDEX idx_orders_instance ON orders(instance_id);
CREATE INDEX idx_orders_signal ON orders(signal_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_created ON orders(created_at);

-- ============================================================================
-- POSITIONS
-- ============================================================================
CREATE TABLE positions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    instance_id UUID REFERENCES strategy_instances(id),
    user_id UUID NOT NULL REFERENCES users(id),
    symbol VARCHAR(50) NOT NULL,
    side VARCHAR(10),
    quantity DECIMAL(18,4),
    avg_price DECIMAL(18,4),
    current_price DECIMAL(18,4),
    pnl DECIMAL(18,2),
    unrealized_pnl DECIMAL(18,2),
    realized_pnl DECIMAL(18,2),
    exchange VARCHAR(20),
    product_type VARCHAR(20),
    status VARCHAR(20) DEFAULT 'OPEN',
    opened_at TIMESTAMP DEFAULT NOW(),
    closed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_positions_user ON positions(user_id);
CREATE INDEX idx_positions_instance ON positions(instance_id);
CREATE INDEX idx_positions_symbol ON positions(symbol);
CREATE INDEX idx_positions_status ON positions(status);

-- ============================================================================
-- DAILY PERFORMANCE
-- ============================================================================
CREATE TABLE daily_performance (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    instance_id UUID NOT NULL REFERENCES strategy_instances(id),
    date DATE NOT NULL,
    equity DECIMAL(18,2),
    capital DECIMAL(18,2),
    returns DECIMAL(10,4),
    benchmark_returns DECIMAL(10,4),
    max_drawdown DECIMAL(10,4),
    trades_count INTEGER DEFAULT 0,
    wins INTEGER DEFAULT 0,
    losses INTEGER DEFAULT 0,
    avg_win DECIMAL(18,2),
    avg_loss DECIMAL(18,2),
    win_rate DECIMAL(5,4),
    profit_factor DECIMAL(8,4),
    sharpe_ratio DECIMAL(8,4),
    pnl DECIMAL(18,2),
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(instance_id, date)
);

CREATE INDEX idx_performance_instance ON daily_performance(instance_id);
CREATE INDEX idx_performance_date ON daily_performance(date);

-- ============================================================================
-- SAMPLE DATA
-- ============================================================================

-- Insert a default organization
INSERT INTO organizations (id, name, slug, plan_type, max_users, max_strategies)
VALUES ('00000000-0000-0000-0000-000000000001', 'Default Organization', 'default', 'FREE', 3, 10);

-- Insert a demo admin user (password: admin123)
INSERT INTO users (id, organization_id, email, password_hash, first_name, last_name, role, status)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001',
    'admin@stokr.local',
    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
    'Admin',
    'User',
    'ADMIN',
    'ACTIVE'
);
