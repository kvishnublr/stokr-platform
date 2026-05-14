-- Strategy launch / backtest metadata (JSON contract for UI + future validation).
-- Only MEAN_REVERSION_RANGE_FADE is seeded in this migration; other strategies get metadata in later migrations.

ALTER TABLE strategy_definitions
    ADD COLUMN IF NOT EXISTS parameter_metadata_json TEXT;

UPDATE strategy_definitions
SET parameter_metadata_json = $META$
{
  "schemaVersion": 1,
  "strategyKey": "MEAN_REVERSION_RANGE_FADE",
  "displayName": "Mean reversion (range fade)",
  "description": "Mean reversion range fade — parameter schema v1 (execution wiring lands in later PRs).",
  "category": "MEAN_REVERSION",
  "supportedMarkets": ["NSE", "BSE", "US_EQUITY"],
  "requiredIndicators": ["ATR", "VOLUME"],
  "executionCapabilities": {
    "backtest": true,
    "paper": true,
    "live": true
  },
  "parameters": [
    {"id": "symbol", "type": "string", "label": "Symbol", "description": "Underlying or continuous symbol", "required": true, "defaultValue": "NIFTY 50", "group": "core", "validation": {"maxLength": 48, "pattern": "^[A-Z0-9][A-Z0-9 .\\-]{0,47}$"}},
    {"id": "timeframe", "type": "enum", "label": "Timeframe", "required": true, "defaultValue": "5m", "enumValues": ["1m", "5m", "15m", "1h"], "group": "core"},
    {"id": "capital", "type": "number", "label": "Capital (notional)", "required": true, "defaultValue": 100000, "group": "risk", "validation": {"min": 1000, "max": 100000000, "step": 100}},
    {"id": "riskPct", "type": "number", "label": "Risk % per trade", "required": true, "defaultValue": 0.5, "group": "risk", "validation": {"min": 0.01, "max": 5, "step": 0.01}},
    {"id": "stopLossType", "type": "enum", "label": "Stop loss type", "required": true, "defaultValue": "FIXED_PCT", "enumValues": ["FIXED_PCT", "ATR_MULTIPLE"], "group": "risk"},
    {"id": "stopLossPct", "type": "number", "label": "Stop loss %", "required": true, "defaultValue": 0.35, "group": "risk", "validation": {"min": 0.01, "max": 20, "step": 0.01}},
    {"id": "takeProfitPct", "type": "number", "label": "Take profit %", "required": true, "defaultValue": 0.55, "group": "risk", "validation": {"min": 0.01, "max": 50, "step": 0.01}},
    {"id": "atrFilterEnabled", "type": "boolean", "label": "ATR filter enabled", "required": false, "defaultValue": true, "group": "filters"},
    {"id": "atrPeriod", "type": "integer", "label": "ATR period", "required": false, "defaultValue": 14, "group": "filters", "validation": {"min": 2, "max": 100}},
    {"id": "volumeFilterEnabled", "type": "boolean", "label": "Volume filter enabled", "required": false, "defaultValue": false, "group": "filters"},
    {"id": "volumeMinRatio", "type": "number", "label": "Min volume vs 20-bar MA", "required": false, "defaultValue": 1.0, "group": "filters", "validation": {"min": 0, "max": 10, "step": 0.05}},
    {"id": "cooldownBars", "type": "integer", "label": "Cooldown (bars)", "required": true, "defaultValue": 3, "group": "timing", "validation": {"min": 0, "max": 500}},
    {"id": "spreadToleranceBps", "type": "number", "label": "Spread tolerance (bps)", "required": true, "defaultValue": 5, "group": "execution", "validation": {"min": 0, "max": 500, "step": 0.5}},
    {"id": "maxConcurrentPositions", "type": "integer", "label": "Max concurrent positions", "required": true, "defaultValue": 1, "group": "risk", "validation": {"min": 1, "max": 20}},
    {"id": "confirmationCandles", "type": "integer", "label": "Confirmation candles", "required": true, "defaultValue": 1, "group": "timing", "validation": {"min": 0, "max": 20}},
    {"id": "sessionFilter", "type": "enum", "label": "Session filter", "required": true, "defaultValue": "ALL", "enumValues": ["ALL", "RTH_ONLY", "INTRADAY_SESSION"], "group": "timing"},
    {"id": "maxHoldingBars", "type": "integer", "label": "Max holding duration (bars)", "required": false, "defaultValue": 80, "group": "timing", "validation": {"min": 1, "max": 10000}},
    {"id": "slippageModel", "type": "enum", "label": "Slippage model", "required": true, "defaultValue": "SPREAD_PROXY", "enumValues": ["NONE", "SPREAD_PROXY", "VOL_SCALED"], "group": "execution"},
    {"id": "feeModel", "type": "enum", "label": "Fee model", "required": true, "defaultValue": "PERCENT_2_BPS", "enumValues": ["NONE", "PERCENT_2_BPS", "PERCENT_5_BPS"], "group": "execution"},
    {"id": "executionProfile", "type": "enum", "label": "Execution profile", "required": true, "defaultValue": "SIMULATED_DEFAULT", "enumValues": ["SIMULATED_DEFAULT", "SIMULATED_HIGH_LATENCY", "REPLAY_RAW", "CONSERVATIVE", "BALANCED", "AGGRESSIVE"], "group": "execution"}
  ]
}
$META$
WHERE strategy_key = 'MEAN_REVERSION_RANGE_FADE' AND deleted = FALSE;
