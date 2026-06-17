-- Universe Groups table
CREATE TABLE IF NOT EXISTS universe_groups (
    id BIGSERIAL PRIMARY KEY,
    group_key VARCHAR(50) NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    universe_type VARCHAR(30) NOT NULL DEFAULT 'INDEX_CONSTITUENTS',
    exchange VARCHAR(20) NOT NULL DEFAULT 'NSE',
    asset_class VARCHAR(20) NOT NULL DEFAULT 'EQUITY',
    segment VARCHAR(20) NOT NULL DEFAULT 'NSE',
    instrument_type VARCHAR(10) DEFAULT 'EQ',
    auto_managed BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Universe Symbols table
CREATE TABLE IF NOT EXISTS universe_symbols (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES universe_groups(id) ON DELETE CASCADE,
    symbol VARCHAR(30) NOT NULL,
    trading_symbol VARCHAR(50),
    underlying_symbol VARCHAR(30),
    exchange VARCHAR(20) NOT NULL DEFAULT 'NSE',
    instrument_token VARCHAR(50),
    instrument_type VARCHAR(10) DEFAULT 'EQ',
    lot_size INT DEFAULT 1,
    tick_size NUMERIC(10,4) DEFAULT 0.05,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_universe_symbols_group ON universe_symbols(group_id);
CREATE INDEX IF NOT EXISTS idx_universe_symbols_symbol ON universe_symbols(symbol);

-- Strategy Configs table
CREATE TABLE IF NOT EXISTS strategy_configs (
    id BIGSERIAL PRIMARY KEY,
    strategy_id BIGINT NOT NULL UNIQUE REFERENCES strategies(id) ON DELETE CASCADE,
    allocated_capital NUMERIC(18,2) DEFAULT 100000,
    max_positions INT DEFAULT 2,
    max_trade_quantity INT DEFAULT 1,
    force_fixed_qty BOOLEAN NOT NULL DEFAULT TRUE,
    fixed_qty INT DEFAULT 1,
    sizing_mode VARCHAR(30) DEFAULT 'FIXED_QUANTITY',
    max_capital_per_trade NUMERIC(18,2) DEFAULT 50000,
    max_risk_per_trade_pct NUMERIC(5,2) DEFAULT 1.0,
    daily_loss_limit NUMERIC(18,2) DEFAULT 5000,
    cooldown_minutes INT DEFAULT 15,
    live_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    paper_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_strategy_configs_strategy ON strategy_configs(strategy_id);

-- Strategy Universe Mappings table
CREATE TABLE IF NOT EXISTS strategy_universe_mappings (
    id BIGSERIAL PRIMARY KEY,
    strategy_id BIGINT NOT NULL REFERENCES strategies(id) ON DELETE CASCADE,
    universe_group_id BIGINT NOT NULL REFERENCES universe_groups(id) ON DELETE CASCADE,
    runtime_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    max_positions INT DEFAULT 2,
    capital_limit NUMERIC(18,2) DEFAULT 100000,
    risk_profile VARCHAR(20) DEFAULT 'MEDIUM',
    scan_interval_seconds INT DEFAULT 60,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(strategy_id, universe_group_id)
);

CREATE INDEX IF NOT EXISTS idx_strategy_mappings_strategy ON strategy_universe_mappings(strategy_id);
CREATE INDEX IF NOT EXISTS idx_strategy_mappings_group ON strategy_universe_mappings(universe_group_id);
CREATE INDEX IF NOT EXISTS idx_strategy_mappings_runtime ON strategy_universe_mappings(runtime_enabled);
