-- V83: Register CDS currency strategies (USDINR momentum, EURINR mean reversion) for paper trading.

-- CDS universe group
INSERT INTO strategy_universe_groups
    (id, group_key, display_name, description, universe_type, exchange, asset_class, segment, instrument_type, auto_managed, enabled)
VALUES
    ('a0000001-0000-0000-0000-000000000016', 'CDS_MAJOR_PAIRS', 'CDS Major Pairs',
     'USDINR and EURINR currency derivatives', 'CURRENCY', 'CDS', 'CURRENCY', 'CDS', 'CUR', FALSE, TRUE)
ON CONFLICT (group_key) DO UPDATE SET
    enabled = TRUE,
    segment = 'CDS',
    asset_class = 'CURRENCY',
    updated_at = NOW();

INSERT INTO strategy_universe_symbols
    (group_id, symbol, trading_symbol, underlying_symbol, exchange, instrument_type, lot_size, enabled)
VALUES
    ('a0000001-0000-0000-0000-000000000016', 'USDINR', 'USDINR', 'USDINR', 'CDS', 'CUR', 1, TRUE),
    ('a0000001-0000-0000-0000-000000000016', 'EURINR', 'EURINR', 'EURINR', 'CDS', 'CUR', 1, TRUE)
ON CONFLICT DO NOTHING;

-- Strategy definitions
INSERT INTO strategy_definitions (
    id, created_at, updated_at, version, deleted,
    strategy_key, display_name, description, config_json,
    category, risk_level, enabled, visible_to_users,
    strategy_type, execution_mode, default_timeframe, default_exchange,
    supports_backtest, supports_live, supports_paper, catalog_version,
    template_generated, asset_class, segment,
    derivative_enabled, futures_strategy_enabled, option_strategy_enabled,
    validation_status, live_shadow_enabled
)
SELECT
    '22222222-2222-2222-2222-222222222283',
    NOW(), NOW(), 0, FALSE,
    'USDINR_MOMENTUM',
    'USD/INR Momentum',
    'CDS momentum strategy for USDINR with trend continuation and volatility gating.',
    '{"symbol":"USDINR","timeframe":"5m","exchange":"CDS"}',
    'INTRADAY', 'MEDIUM', TRUE, TRUE,
    'INTRADAY', 'PAPER', '5m', 'CDS',
    TRUE, FALSE, TRUE, '1.0',
    TRUE, 'CURRENCY', 'CDS',
    FALSE, FALSE, FALSE,
    'PAPER_VALIDATING', FALSE
WHERE NOT EXISTS (SELECT 1 FROM strategy_definitions WHERE strategy_key = 'USDINR_MOMENTUM');

INSERT INTO strategy_definitions (
    id, created_at, updated_at, version, deleted,
    strategy_key, display_name, description, config_json,
    category, risk_level, enabled, visible_to_users,
    strategy_type, execution_mode, default_timeframe, default_exchange,
    supports_backtest, supports_live, supports_paper, catalog_version,
    template_generated, asset_class, segment,
    derivative_enabled, futures_strategy_enabled, option_strategy_enabled,
    validation_status, live_shadow_enabled
)
SELECT
    '22222222-2222-2222-2222-222222222284',
    NOW(), NOW(), 0, FALSE,
    'EURINR_MEAN_REVERSION',
    'EUR/INR Mean Reversion',
    'CDS mean reversion strategy for EURINR with band re-entry and intraday exit.',
    '{"symbol":"EURINR","timeframe":"5m","exchange":"CDS"}',
    'INTRADAY', 'MEDIUM', TRUE, TRUE,
    'INTRADAY', 'PAPER', '5m', 'CDS',
    TRUE, FALSE, TRUE, '1.0',
    TRUE, 'CURRENCY', 'CDS',
    FALSE, FALSE, FALSE,
    'PAPER_VALIDATING', FALSE
WHERE NOT EXISTS (SELECT 1 FROM strategy_definitions WHERE strategy_key = 'EURINR_MEAN_REVERSION');

-- Runtime bindings (paper scan enabled)
INSERT INTO strategy_runtime_bindings (
    id, created_at, updated_at,
    strategy_catalog_id, universe_group_id,
    runtime_enabled, max_positions, risk_profile, scan_interval_seconds
)
SELECT gen_random_uuid(), NOW(), NOW(),
       sd.id, 'a0000001-0000-0000-0000-000000000016',
       TRUE, 2, 'MEDIUM', 300
FROM strategy_definitions sd
WHERE sd.strategy_key = 'USDINR_MOMENTUM'
  AND NOT EXISTS (
      SELECT 1 FROM strategy_runtime_bindings b
      JOIN strategy_definitions d ON d.id = b.strategy_catalog_id
      WHERE d.strategy_key = 'USDINR_MOMENTUM'
        AND b.universe_group_id = 'a0000001-0000-0000-0000-000000000016'
  );

INSERT INTO strategy_runtime_bindings (
    id, created_at, updated_at,
    strategy_catalog_id, universe_group_id,
    runtime_enabled, max_positions, risk_profile, scan_interval_seconds
)
SELECT gen_random_uuid(), NOW(), NOW(),
       sd.id, 'a0000001-0000-0000-0000-000000000016',
       TRUE, 2, 'MEDIUM', 300
FROM strategy_definitions sd
WHERE sd.strategy_key = 'EURINR_MEAN_REVERSION'
  AND NOT EXISTS (
      SELECT 1 FROM strategy_runtime_bindings b
      JOIN strategy_definitions d ON d.id = b.strategy_catalog_id
      WHERE d.strategy_key = 'EURINR_MEAN_REVERSION'
        AND b.universe_group_id = 'a0000001-0000-0000-0000-000000000016'
  );

-- Paper-only execution configs
INSERT INTO strategy_execution_configs (
    id, strategy_key, enabled, execution_mode, live_enabled, paper_enabled,
    sizing_mode, force_fixed_qty, fixed_qty, max_positions, capital_utilization_mode, deleted
)
SELECT gen_random_uuid(), sk, TRUE, 'PAPER', FALSE, TRUE,
       'FIXED_QUANTITY', TRUE, 1, 2, 'FULLY_ALLOCATED', FALSE
FROM (VALUES ('USDINR_MOMENTUM'), ('EURINR_MEAN_REVERSION')) AS t(sk)
WHERE NOT EXISTS (
    SELECT 1 FROM strategy_execution_configs c
    WHERE c.strategy_key = t.sk AND c.user_id IS NULL AND c.deleted = FALSE
);

UPDATE strategy_execution_configs SET
    execution_mode = 'PAPER',
    live_enabled = FALSE,
    paper_enabled = TRUE,
    sizing_mode = 'FIXED_QUANTITY',
    force_fixed_qty = TRUE,
    fixed_qty = 1,
    max_positions = 2
WHERE strategy_key IN ('USDINR_MOMENTUM', 'EURINR_MEAN_REVERSION')
  AND user_id IS NULL
  AND deleted = FALSE;

-- Apply parameter metadata (V82 ran before definitions existed)
UPDATE strategy_definitions
SET parameter_metadata_json = $META$
{
  "schemaVersion": 2,
  "strategyKey": "USDINR_MOMENTUM",
  "displayName": "USD/INR Momentum",
  "description": "CDS momentum strategy for USDINR with trend continuation and volatility gating.",
  "category": "INTRADAY",
  "supportedMarkets": ["CDS"],
  "requiredIndicators": ["PRICE", "VOLUME", "ATR"],
  "executionCapabilities": {"backtest": true, "paper": true, "live": false},
  "parameters": [
    {"id": "symbol", "type": "string", "label": "Symbol", "required": true, "defaultValue": "USDINR", "group": "core"},
    {"id": "timeframe", "type": "enum", "label": "Timeframe", "required": true, "defaultValue": "5m", "enumValues": ["1m", "5m", "15m"], "group": "core"},
    {"id": "riskPct", "type": "number", "label": "Risk % per trade", "required": true, "defaultValue": 0.40, "group": "risk"},
    {"id": "stopLossPct", "type": "number", "label": "Stop loss %", "required": true, "defaultValue": 0.25, "group": "risk"},
    {"id": "takeProfitPct", "type": "number", "label": "Take profit %", "required": true, "defaultValue": 0.50, "group": "risk"},
    {"id": "cooldownBars", "type": "integer", "label": "Cooldown bars", "required": true, "defaultValue": 3, "group": "timing"},
    {"id": "spreadToleranceBps", "type": "number", "label": "Spread tolerance (bps)", "required": true, "defaultValue": 4, "group": "execution"}
  ],
  "allowedTimeframes": ["1m", "5m", "15m"],
  "allowedExecutionModes": ["BACKTEST", "PAPER"],
  "deploymentDefaults": {"symbol": "USDINR", "timeframe": "5m", "executionProfile": "REPLAY_RAW"}
}
$META$,
    updated_at = NOW()
WHERE strategy_key = 'USDINR_MOMENTUM' AND deleted = FALSE;

UPDATE strategy_definitions
SET parameter_metadata_json = $META$
{
  "schemaVersion": 2,
  "strategyKey": "EURINR_MEAN_REVERSION",
  "displayName": "EUR/INR Mean Reversion",
  "description": "CDS mean reversion strategy for EURINR with band re-entry and intraday exit.",
  "category": "INTRADAY",
  "supportedMarkets": ["CDS"],
  "requiredIndicators": ["PRICE", "ATR", "VOLUME"],
  "executionCapabilities": {"backtest": true, "paper": true, "live": false},
  "parameters": [
    {"id": "symbol", "type": "string", "label": "Symbol", "required": true, "defaultValue": "EURINR", "group": "core"},
    {"id": "timeframe", "type": "enum", "label": "Timeframe", "required": true, "defaultValue": "5m", "enumValues": ["1m", "5m", "15m"], "group": "core"},
    {"id": "riskPct", "type": "number", "label": "Risk % per trade", "required": true, "defaultValue": 0.35, "group": "risk"},
    {"id": "stopLossPct", "type": "number", "label": "Stop loss %", "required": true, "defaultValue": 0.30, "group": "risk"},
    {"id": "takeProfitPct", "type": "number", "label": "Take profit %", "required": true, "defaultValue": 0.45, "group": "risk"},
    {"id": "cooldownBars", "type": "integer", "label": "Cooldown bars", "required": true, "defaultValue": 3, "group": "timing"},
    {"id": "spreadToleranceBps", "type": "number", "label": "Spread tolerance (bps)", "required": true, "defaultValue": 4, "group": "execution"}
  ],
  "allowedTimeframes": ["1m", "5m", "15m"],
  "allowedExecutionModes": ["BACKTEST", "PAPER"],
  "deploymentDefaults": {"symbol": "EURINR", "timeframe": "5m", "executionProfile": "REPLAY_RAW"}
}
$META$,
    updated_at = NOW()
WHERE strategy_key = 'EURINR_MEAN_REVERSION' AND deleted = FALSE;
