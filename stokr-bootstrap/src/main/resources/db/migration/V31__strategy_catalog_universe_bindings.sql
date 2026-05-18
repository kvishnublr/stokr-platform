-- ============================================================
-- V31: Strategy catalog enhancement + multi-asset universe system
-- Adds admin-managed metadata to strategy_definitions,
-- and introduces universe groups, symbols, and runtime bindings
-- with full multi-asset support (EQUITY, COMMODITY, FUTURES,
-- INDEX DERIVATIVES, CURRENCY, OPTIONS).
-- ============================================================

-- -------------------------------------------------------
-- 1. Extend strategy_definitions with catalog metadata
-- -------------------------------------------------------
ALTER TABLE strategy_definitions
    ADD COLUMN IF NOT EXISTS strategy_type              VARCHAR(64)   DEFAULT 'INTRADAY',
    ADD COLUMN IF NOT EXISTS execution_mode             VARCHAR(64)   DEFAULT 'ALL',
    ADD COLUMN IF NOT EXISTS template_class_name        VARCHAR(256),
    ADD COLUMN IF NOT EXISTS generated_class_path       VARCHAR(512),
    ADD COLUMN IF NOT EXISTS default_timeframe          VARCHAR(16)   DEFAULT '1m',
    ADD COLUMN IF NOT EXISTS default_exchange           VARCHAR(16)   DEFAULT 'NSE',
    ADD COLUMN IF NOT EXISTS supports_backtest          BOOLEAN       NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS supports_live              BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS supports_paper             BOOLEAN       NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS catalog_version            VARCHAR(32)   DEFAULT '1.0',
    ADD COLUMN IF NOT EXISTS created_by                 UUID,
    ADD COLUMN IF NOT EXISTS template_generated         BOOLEAN       NOT NULL DEFAULT FALSE,
    -- multi-asset columns
    ADD COLUMN IF NOT EXISTS asset_class                VARCHAR(32)   DEFAULT 'EQUITY',
    ADD COLUMN IF NOT EXISTS segment                    VARCHAR(32)   DEFAULT 'NSE',
    ADD COLUMN IF NOT EXISTS derivative_enabled         BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS option_strategy_enabled    BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS futures_strategy_enabled   BOOLEAN       NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN strategy_definitions.strategy_type            IS 'INTRADAY | SWING | POSITIONAL | SCALPING';
COMMENT ON COLUMN strategy_definitions.execution_mode           IS 'ALL | LIVE_ONLY | PAPER_ONLY | BACKTEST_ONLY';
COMMENT ON COLUMN strategy_definitions.template_class_name      IS 'Short class name of the generated signal generator';
COMMENT ON COLUMN strategy_definitions.generated_class_path     IS 'Fully-qualified class path written to disk by template generator';
COMMENT ON COLUMN strategy_definitions.template_generated       IS 'True once admin triggered template generation';
COMMENT ON COLUMN strategy_definitions.asset_class              IS 'EQUITY | COMMODITY | FUTURES | OPTIONS | CURRENCY';
COMMENT ON COLUMN strategy_definitions.segment                  IS 'NSE | NFO | MCX | CDS | BSE';
COMMENT ON COLUMN strategy_definitions.derivative_enabled       IS 'Strategy supports derivative instruments';
COMMENT ON COLUMN strategy_definitions.futures_strategy_enabled IS 'Strategy specifically targets futures contracts';
COMMENT ON COLUMN strategy_definitions.option_strategy_enabled  IS 'Strategy specifically targets options contracts';

-- -------------------------------------------------------
-- 2. strategy_universe_groups
--    Reusable symbol universes an admin can define.
--    Multi-asset aware: equity, commodity, futures, options.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS strategy_universe_groups (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    group_key       VARCHAR(128) NOT NULL UNIQUE,
    display_name    VARCHAR(200) NOT NULL,
    description     VARCHAR(500),
    universe_type   VARCHAR(64)  NOT NULL DEFAULT 'INDEX_CONSTITUENTS',
    exchange        VARCHAR(16)  NOT NULL DEFAULT 'NSE',
    -- multi-asset
    asset_class     VARCHAR(32)  NOT NULL DEFAULT 'EQUITY',
    segment         VARCHAR(32)  NOT NULL DEFAULT 'NSE',
    instrument_type VARCHAR(32)  NOT NULL DEFAULT 'EQ',
    auto_managed    BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  strategy_universe_groups                  IS 'Admin-managed reusable symbol universes (multi-asset)';
COMMENT ON COLUMN strategy_universe_groups.universe_type    IS 'INDEX_CONSTITUENTS | CUSTOM | SECTOR | FUTURES | OPTIONS | COMMODITY';
COMMENT ON COLUMN strategy_universe_groups.asset_class      IS 'EQUITY | COMMODITY | FUTURES | OPTIONS | CURRENCY';
COMMENT ON COLUMN strategy_universe_groups.segment          IS 'NSE | NFO | MCX | CDS | BSE';
COMMENT ON COLUMN strategy_universe_groups.instrument_type  IS 'EQ | FUT | OPT | COM | CUR';
COMMENT ON COLUMN strategy_universe_groups.auto_managed     IS 'True when symbols are synced by UniverseSyncService';

CREATE INDEX IF NOT EXISTS idx_universe_groups_key          ON strategy_universe_groups (group_key);
CREATE INDEX IF NOT EXISTS idx_universe_groups_enabled      ON strategy_universe_groups (enabled);
CREATE INDEX IF NOT EXISTS idx_universe_groups_asset_class  ON strategy_universe_groups (asset_class, segment);

-- -------------------------------------------------------
-- 3. strategy_universe_symbols
--    Symbol membership within a universe group.
--    Supports equities, futures, options, commodities.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS strategy_universe_symbols (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id            UUID         NOT NULL REFERENCES strategy_universe_groups (id) ON DELETE CASCADE,
    symbol              VARCHAR(64)  NOT NULL,
    trading_symbol      VARCHAR(128),
    underlying_symbol   VARCHAR(64),
    exchange            VARCHAR(16)  NOT NULL DEFAULT 'NSE',
    instrument_token    BIGINT,
    -- multi-asset / derivatives
    instrument_type     VARCHAR(32)  NOT NULL DEFAULT 'EQ',
    lot_size            INT,
    tick_size           NUMERIC(12,4),
    expiry              DATE,
    strike              NUMERIC(12,2),
    option_type         VARCHAR(4),
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  strategy_universe_symbols                   IS 'Symbol members of a universe group (multi-asset)';
COMMENT ON COLUMN strategy_universe_symbols.instrument_token  IS 'Broker instrument token (optional, populated by sync)';
COMMENT ON COLUMN strategy_universe_symbols.trading_symbol    IS 'Broker-specific trading symbol (e.g. BANKNIFTY26MAYFUT)';
COMMENT ON COLUMN strategy_universe_symbols.underlying_symbol IS 'Underlying instrument (e.g. BANKNIFTY for its futures)';
COMMENT ON COLUMN strategy_universe_symbols.instrument_type   IS 'EQ | FUT | OPT | COM | CUR';
COMMENT ON COLUMN strategy_universe_symbols.expiry            IS 'Contract expiry date for futures/options';
COMMENT ON COLUMN strategy_universe_symbols.strike            IS 'Strike price for options contracts';
COMMENT ON COLUMN strategy_universe_symbols.option_type       IS 'CE or PE for options contracts';
COMMENT ON COLUMN strategy_universe_symbols.lot_size          IS 'Contract lot size (futures/options/commodities)';
COMMENT ON COLUMN strategy_universe_symbols.tick_size         IS 'Minimum price movement for this instrument';

CREATE INDEX IF NOT EXISTS idx_universe_symbols_group           ON strategy_universe_symbols (group_id);
CREATE INDEX IF NOT EXISTS idx_universe_symbols_symbol          ON strategy_universe_symbols (symbol);
CREATE INDEX IF NOT EXISTS idx_universe_symbols_enabled         ON strategy_universe_symbols (group_id, enabled);
CREATE INDEX IF NOT EXISTS idx_universe_symbols_instrument_type ON strategy_universe_symbols (instrument_type);
CREATE INDEX IF NOT EXISTS idx_universe_symbols_expiry          ON strategy_universe_symbols (expiry) WHERE expiry IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_universe_symbols           ON strategy_universe_symbols (group_id, symbol, exchange, COALESCE(expiry, '1970-01-01'::DATE), COALESCE(strike, 0), COALESCE(option_type, 'NA'));

-- -------------------------------------------------------
-- 4. strategy_runtime_bindings
--    Maps a strategy catalog entry to a universe group.
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS strategy_runtime_bindings (
    id                      UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_catalog_id     UUID         NOT NULL REFERENCES strategy_definitions (id) ON DELETE CASCADE,
    universe_group_id       UUID         NOT NULL REFERENCES strategy_universe_groups (id) ON DELETE CASCADE,
    runtime_enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    max_positions           INT          NOT NULL DEFAULT 5,
    capital_limit           NUMERIC(24,8),
    risk_profile            VARCHAR(32)  NOT NULL DEFAULT 'MEDIUM',
    scan_interval_seconds   INT          NOT NULL DEFAULT 60,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_strategy_universe_binding UNIQUE (strategy_catalog_id, universe_group_id)
);

COMMENT ON TABLE  strategy_runtime_bindings                       IS 'Binds a strategy to a symbol universe for runtime scanning';
COMMENT ON COLUMN strategy_runtime_bindings.runtime_enabled       IS 'Admin toggle: only true bindings are scanned by CatalogDrivenScanScheduler';
COMMENT ON COLUMN strategy_runtime_bindings.scan_interval_seconds IS 'Overrides global poll interval for this binding';

CREATE INDEX IF NOT EXISTS idx_runtime_bindings_strategy  ON strategy_runtime_bindings (strategy_catalog_id);
CREATE INDEX IF NOT EXISTS idx_runtime_bindings_universe  ON strategy_runtime_bindings (universe_group_id);
CREATE INDEX IF NOT EXISTS idx_runtime_bindings_enabled   ON strategy_runtime_bindings (runtime_enabled);

-- -------------------------------------------------------
-- 5. Seed standard universe groups (equity + derivatives)
-- -------------------------------------------------------
INSERT INTO strategy_universe_groups
    (id, group_key, display_name, description, universe_type, exchange, asset_class, segment, instrument_type, auto_managed, enabled)
VALUES
    -- Equity Index Constituents
    ('a0000001-0000-0000-0000-000000000001', 'NIFTY_50',     'NIFTY 50',     'NSE Nifty 50 index constituents',     'INDEX_CONSTITUENTS', 'NSE', 'EQUITY',    'NSE', 'EQ',  TRUE,  TRUE),
    ('a0000001-0000-0000-0000-000000000002', 'NIFTY_100',    'NIFTY 100',    'NSE Nifty 100 index constituents',    'INDEX_CONSTITUENTS', 'NSE', 'EQUITY',    'NSE', 'EQ',  TRUE,  TRUE),
    ('a0000001-0000-0000-0000-000000000003', 'NIFTY_200',    'NIFTY 200',    'NSE Nifty 200 index constituents',    'INDEX_CONSTITUENTS', 'NSE', 'EQUITY',    'NSE', 'EQ',  TRUE,  TRUE),
    ('a0000001-0000-0000-0000-000000000004', 'NIFTY_500',    'NIFTY 500',    'NSE Nifty 500 index constituents',    'INDEX_CONSTITUENTS', 'NSE', 'EQUITY',    'NSE', 'EQ',  TRUE,  TRUE),
    -- Index Futures
    ('a0000001-0000-0000-0000-000000000005', 'BANKNIFTY_FUTURES',  'BANK NIFTY Futures',  'NSE Bank Nifty F&O futures',  'FUTURES', 'NFO', 'FUTURES',  'NFO', 'FUT', FALSE, TRUE),
    ('a0000001-0000-0000-0000-000000000006', 'FINNIFTY_FUTURES',   'FIN NIFTY Futures',   'NSE Fin Nifty F&O futures',   'FUTURES', 'NFO', 'FUTURES',  'NFO', 'FUT', FALSE, TRUE),
    ('a0000001-0000-0000-0000-000000000007', 'NIFTY_FUTURES',      'NIFTY Futures',       'NSE Nifty 50 index futures',  'FUTURES', 'NFO', 'FUTURES',  'NFO', 'FUT', FALSE, TRUE),
    ('a0000001-0000-0000-0000-000000000008', 'STOCK_FUTURES',      'Stock Futures',       'NSE stock futures (F&O)',     'FUTURES', 'NFO', 'FUTURES',  'NFO', 'FUT', FALSE, TRUE),
    -- Options
    ('a0000001-0000-0000-0000-000000000009', 'NIFTY_WEEKLY_OPTIONS',      'NIFTY Weekly Options',      'Nifty weekly option contracts',      'OPTIONS', 'NFO', 'OPTIONS', 'NFO', 'OPT', FALSE, TRUE),
    ('a0000001-0000-0000-0000-000000000010', 'BANKNIFTY_WEEKLY_OPTIONS',  'BANKNIFTY Weekly Options',  'Bank Nifty weekly option contracts',  'OPTIONS', 'NFO', 'OPTIONS', 'NFO', 'OPT', FALSE, TRUE),
    -- Commodities (MCX)
    ('a0000001-0000-0000-0000-000000000011', 'MCX_BULLION',  'MCX Bullion',       'MCX Gold & Silver futures',              'COMMODITY', 'MCX', 'COMMODITY', 'MCX', 'COM', FALSE, TRUE),
    ('a0000001-0000-0000-0000-000000000012', 'MCX_ENERGY',   'MCX Energy',        'MCX Crude Oil & Natural Gas futures',    'COMMODITY', 'MCX', 'COMMODITY', 'MCX', 'COM', FALSE, TRUE),
    ('a0000001-0000-0000-0000-000000000013', 'MCX_METALS',   'MCX Base Metals',   'MCX Copper, Zinc, Aluminium etc.',       'COMMODITY', 'MCX', 'COMMODITY', 'MCX', 'COM', FALSE, TRUE),
    ('a0000001-0000-0000-0000-000000000014', 'MCX_AGRI',     'MCX Agricultural',  'MCX agricultural commodity futures',     'COMMODITY', 'MCX', 'COMMODITY', 'MCX', 'COM', FALSE, TRUE),
    -- Custom
    ('a0000001-0000-0000-0000-000000000015', 'CUSTOM_WATCHLIST', 'Custom Watchlist', 'Admin-curated custom symbols', 'CUSTOM', 'NSE', 'EQUITY', 'NSE', 'EQ', FALSE, TRUE)
ON CONFLICT (group_key) DO NOTHING;

-- -------------------------------------------------------
-- 6. Seed MCX commodity universe symbols (sample)
-- -------------------------------------------------------
INSERT INTO strategy_universe_symbols
    (group_id, symbol, trading_symbol, underlying_symbol, exchange, instrument_type, lot_size, enabled)
VALUES
    -- MCX Bullion
    ('a0000001-0000-0000-0000-000000000011', 'GOLD',       'GOLD',       'GOLD',       'MCX', 'COM', 1,  TRUE),
    ('a0000001-0000-0000-0000-000000000011', 'GOLDM',      'GOLDM',      'GOLD',       'MCX', 'COM', 10, TRUE),
    ('a0000001-0000-0000-0000-000000000011', 'SILVER',     'SILVER',     'SILVER',     'MCX', 'COM', 30, TRUE),
    ('a0000001-0000-0000-0000-000000000011', 'SILVERM',    'SILVERM',    'SILVER',     'MCX', 'COM', 5,  TRUE),
    -- MCX Energy
    ('a0000001-0000-0000-0000-000000000012', 'CRUDEOIL',   'CRUDEOIL',   'CRUDEOIL',   'MCX', 'COM', 100, TRUE),
    ('a0000001-0000-0000-0000-000000000012', 'CRUDEOILM',  'CRUDEOILM',  'CRUDEOIL',   'MCX', 'COM', 10,  TRUE),
    ('a0000001-0000-0000-0000-000000000012', 'NATURALGAS', 'NATURALGAS', 'NATURALGAS', 'MCX', 'COM', 1250, TRUE),
    -- MCX Base Metals
    ('a0000001-0000-0000-0000-000000000013', 'COPPER',     'COPPER',     'COPPER',     'MCX', 'COM', 2500, TRUE),
    ('a0000001-0000-0000-0000-000000000013', 'ZINC',       'ZINC',       'ZINC',       'MCX', 'COM', 5000, TRUE),
    ('a0000001-0000-0000-0000-000000000013', 'ALUMINIUM',  'ALUMINIUM',  'ALUMINIUM',  'MCX', 'COM', 5000, TRUE),
    ('a0000001-0000-0000-0000-000000000013', 'NICKEL',     'NICKEL',     'NICKEL',     'MCX', 'COM', 1500, TRUE),
    ('a0000001-0000-0000-0000-000000000013', 'LEAD',       'LEAD',       'LEAD',       'MCX', 'COM', 5000, TRUE)
ON CONFLICT DO NOTHING;

-- -------------------------------------------------------
-- 7. Seed existing strategy_definitions with catalog columns
-- -------------------------------------------------------
UPDATE strategy_definitions SET
    strategy_type        = 'INTRADAY',
    execution_mode       = 'ALL',
    default_timeframe    = '1m',
    default_exchange     = 'NSE',
    supports_backtest    = TRUE,
    supports_live        = FALSE,
    supports_paper       = TRUE,
    catalog_version      = '1.0',
    template_generated   = TRUE,
    asset_class          = 'EQUITY',
    segment              = 'NSE',
    derivative_enabled   = FALSE,
    futures_strategy_enabled = FALSE,
    option_strategy_enabled  = FALSE
WHERE deleted = FALSE
  AND strategy_type IS NULL;
