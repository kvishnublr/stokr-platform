-- PR-2: persisted execution envelope + Mean Reversion metadata v2 (strategy-only parameters).

ALTER TABLE backtest_runs ADD COLUMN IF NOT EXISTS execution_request_json TEXT;
ALTER TABLE backtest_runs ADD COLUMN IF NOT EXISTS metadata_schema_version INTEGER;
ALTER TABLE backtest_runs ADD COLUMN IF NOT EXISTS strategy_definition_version BIGINT;
ALTER TABLE backtest_runs ADD COLUMN IF NOT EXISTS execution_mode VARCHAR(32);
ALTER TABLE backtest_runs ADD COLUMN IF NOT EXISTS fee_model VARCHAR(64);
ALTER TABLE backtest_runs ADD COLUMN IF NOT EXISTS slippage_model VARCHAR(64);

UPDATE strategy_definitions
SET parameter_metadata_json = $META$
{
  "schemaVersion": 2,
  "strategyKey": "MEAN_REVERSION_RANGE_FADE",
  "displayName": "Mean reversion (range fade)",
  "description": "Metadata v2: global execution envelope is separate; parameters here are strategy-only.",
  "category": "MEAN_REVERSION",
  "supportedMarkets": ["NSE", "BSE", "US_EQUITY"],
  "requiredIndicators": ["ATR", "VOLUME"],
  "executionCapabilities": {"backtest": true, "paper": true, "live": true},
  "allowedTimeframes": ["1m", "5m", "15m", "1h"],
  "allowedExecutionModes": ["BACKTEST", "PAPER", "LIVE"],
  "allowedFeeModels": ["NONE", "PERCENT_2_BPS", "PERCENT_5_BPS"],
  "allowedSlippageModels": ["NONE", "SPREAD_PROXY", "VOL_SCALED"],
  "allowedExecutionProfiles": ["SIMULATED_DEFAULT", "SIMULATED_HIGH_LATENCY", "REPLAY_RAW", "CONSERVATIVE", "BALANCED", "AGGRESSIVE"],
  "parameters": [
    {"id": "entryThreshold", "type": "number", "label": "Entry threshold", "description": "Band stretch required to enter (normalized)", "required": true, "defaultValue": 0.35, "group": "signals", "validation": {"min": 0.05, "max": 5, "step": 0.01}, "precision": 2},
    {"id": "exitThreshold", "type": "number", "label": "Exit threshold", "description": "Mean reversion target toward mid", "required": true, "defaultValue": 0.2, "group": "signals", "validation": {"min": 0.01, "max": 5, "step": 0.01}, "precision": 2},
    {"id": "stopLossType", "type": "enum", "label": "Stop loss type", "required": true, "defaultValue": "FIXED_PCT", "enumValues": ["FIXED_PCT", "ATR_MULTIPLE"], "group": "risk"},
    {"id": "stopLossPercent", "type": "number", "label": "Stop loss percent", "required": true, "defaultValue": 0.35, "group": "risk", "validation": {"min": 0.01, "max": 30, "step": 0.01}, "precision": 2},
    {"id": "takeProfitPercent", "type": "number", "label": "Take profit percent", "required": true, "defaultValue": 0.55, "group": "risk", "validation": {"min": 0.01, "max": 50, "step": 0.01}, "precision": 2},
    {"id": "atrFilter", "type": "enum", "label": "ATR filter", "required": true, "defaultValue": "STANDARD", "enumValues": ["OFF", "STANDARD", "STRICT"], "group": "filters"},
    {"id": "volumeFilter", "type": "enum", "label": "Volume filter", "required": true, "defaultValue": "RELAXED", "enumValues": ["OFF", "RELAXED", "STRICT"], "group": "filters"},
    {"id": "cooldownCandles", "type": "integer", "label": "Cooldown candles", "required": true, "defaultValue": 3, "group": "timing", "validation": {"min": 0, "max": 500}},
    {"id": "spreadTolerance", "type": "number", "label": "Spread tolerance (bps)", "required": true, "defaultValue": 5, "group": "execution", "validation": {"min": 0, "max": 500, "step": 0.5}, "precision": 1},
    {"id": "maxConcurrentPositions", "type": "integer", "label": "Max concurrent positions", "required": true, "defaultValue": 1, "group": "risk", "validation": {"min": 1, "max": 20}},
    {"id": "confirmationCandles", "type": "integer", "label": "Confirmation candles", "required": true, "defaultValue": 1, "group": "timing", "validation": {"min": 0, "max": 20}},
    {"id": "sessionFilter", "type": "enum", "label": "Session filter", "required": true, "defaultValue": "ALL", "enumValues": ["ALL", "RTH_ONLY", "INTRADAY_SESSION"], "group": "timing"},
    {"id": "maxHoldingCandles", "type": "integer", "label": "Max holding candles", "required": true, "defaultValue": 80, "group": "timing", "validation": {"min": 1, "max": 10000}},
    {"id": "volatilityFilter", "type": "enum", "label": "Volatility filter", "required": true, "defaultValue": "LOW", "enumValues": ["OFF", "LOW", "MEDIUM", "HIGH"], "group": "filters"},
    {"id": "executionAggression", "type": "enum", "label": "Execution aggression", "required": true, "defaultValue": "BALANCED", "enumValues": ["CONSERVATIVE", "BALANCED", "AGGRESSIVE"], "group": "execution"},
    {"id": "partialExitEnabled", "type": "boolean", "label": "Partial exit enabled", "required": true, "defaultValue": false, "group": "execution"},
    {"id": "trailingStopEnabled", "type": "boolean", "label": "Trailing stop enabled", "required": true, "defaultValue": false, "group": "risk"}
  ]
}
$META$
WHERE strategy_key = 'MEAN_REVERSION_RANGE_FADE' AND deleted = FALSE;
