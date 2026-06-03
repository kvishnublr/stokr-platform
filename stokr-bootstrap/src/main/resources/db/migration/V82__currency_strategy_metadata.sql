-- Currency strategy metadata for runtime validation and UI launch forms.
-- Ensures the published USD/INR and EUR/INR strategies have valid parameter schemas.

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
  "executionCapabilities": {
    "backtest": true,
    "paper": true,
    "live": true
  },
  "parameters": [
    {"id": "symbol", "type": "string", "label": "Symbol", "required": true, "defaultValue": "USDINR", "group": "core", "validation": {"maxLength": 24, "pattern": "^[A-Z0-9][A-Z0-9 .\\-]{0,23}$"}},
    {"id": "timeframe", "type": "enum", "label": "Timeframe", "required": true, "defaultValue": "5m", "enumValues": ["1m", "5m", "15m"], "group": "core"},
    {"id": "riskPct", "type": "number", "label": "Risk % per trade", "required": true, "defaultValue": 0.40, "group": "risk", "validation": {"min": 0.01, "max": 5, "step": 0.01}},
    {"id": "stopLossPct", "type": "number", "label": "Stop loss %", "required": true, "defaultValue": 0.25, "group": "risk", "validation": {"min": 0.01, "max": 10, "step": 0.01}},
    {"id": "takeProfitPct", "type": "number", "label": "Take profit %", "required": true, "defaultValue": 0.50, "group": "risk", "validation": {"min": 0.01, "max": 20, "step": 0.01}},
    {"id": "cooldownBars", "type": "integer", "label": "Cooldown bars", "required": true, "defaultValue": 3, "group": "timing", "validation": {"min": 0, "max": 500}},
    {"id": "spreadToleranceBps", "type": "number", "label": "Spread tolerance (bps)", "required": true, "defaultValue": 4, "group": "execution", "validation": {"min": 0, "max": 250, "step": 0.5}}
  ],
  "allowedTimeframes": ["1m", "5m", "15m"],
  "allowedExecutionModes": ["BACKTEST", "PAPER", "LIVE"],
  "allowedFeeModels": ["NONE", "PERCENT_2_BPS", "PERCENT_5_BPS"],
  "allowedSlippageModels": ["NONE", "SPREAD_PROXY", "VOL_SCALED"],
  "allowedExecutionProfiles": ["SIMULATED_DEFAULT", "REPLAY_RAW", "CONSERVATIVE", "BALANCED", "AGGRESSIVE"],
  "deploymentDefaults": {
    "symbol": "USDINR",
    "timeframe": "5m",
    "executionProfile": "REPLAY_RAW",
    "feeModel": "PERCENT_2_BPS",
    "slippageModel": "SPREAD_PROXY"
  },
  "previewMetrics": {
    "avgMonthlyReturnPct": 2.1,
    "winRatePct": 57,
    "maxDrawdownPct": 6.5,
    "riskLevel": "Medium",
    "avgTradesPerDay": 8,
    "holdingHorizon": "Intraday (session)"
  }
}
$META$,
    updated_at = NOW()
WHERE strategy_key = 'USDINR_MOMENTUM'
  AND deleted = FALSE;

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
  "executionCapabilities": {
    "backtest": true,
    "paper": true,
    "live": true
  },
  "parameters": [
    {"id": "symbol", "type": "string", "label": "Symbol", "required": true, "defaultValue": "EURINR", "group": "core", "validation": {"maxLength": 24, "pattern": "^[A-Z0-9][A-Z0-9 .\\-]{0,23}$"}},
    {"id": "timeframe", "type": "enum", "label": "Timeframe", "required": true, "defaultValue": "5m", "enumValues": ["1m", "5m", "15m"], "group": "core"},
    {"id": "riskPct", "type": "number", "label": "Risk % per trade", "required": true, "defaultValue": 0.35, "group": "risk", "validation": {"min": 0.01, "max": 5, "step": 0.01}},
    {"id": "stopLossPct", "type": "number", "label": "Stop loss %", "required": true, "defaultValue": 0.30, "group": "risk", "validation": {"min": 0.01, "max": 10, "step": 0.01}},
    {"id": "takeProfitPct", "type": "number", "label": "Take profit %", "required": true, "defaultValue": 0.45, "group": "risk", "validation": {"min": 0.01, "max": 20, "step": 0.01}},
    {"id": "cooldownBars", "type": "integer", "label": "Cooldown bars", "required": true, "defaultValue": 3, "group": "timing", "validation": {"min": 0, "max": 500}},
    {"id": "spreadToleranceBps", "type": "number", "label": "Spread tolerance (bps)", "required": true, "defaultValue": 4, "group": "execution", "validation": {"min": 0, "max": 250, "step": 0.5}}
  ],
  "allowedTimeframes": ["1m", "5m", "15m"],
  "allowedExecutionModes": ["BACKTEST", "PAPER", "LIVE"],
  "allowedFeeModels": ["NONE", "PERCENT_2_BPS", "PERCENT_5_BPS"],
  "allowedSlippageModels": ["NONE", "SPREAD_PROXY", "VOL_SCALED"],
  "allowedExecutionProfiles": ["SIMULATED_DEFAULT", "REPLAY_RAW", "CONSERVATIVE", "BALANCED", "AGGRESSIVE"],
  "deploymentDefaults": {
    "symbol": "EURINR",
    "timeframe": "5m",
    "executionProfile": "REPLAY_RAW",
    "feeModel": "PERCENT_2_BPS",
    "slippageModel": "SPREAD_PROXY"
  },
  "previewMetrics": {
    "avgMonthlyReturnPct": 1.7,
    "winRatePct": 60,
    "maxDrawdownPct": 5.8,
    "riskLevel": "Medium",
    "avgTradesPerDay": 6,
    "holdingHorizon": "Intraday (session)"
  }
}
$META$,
    updated_at = NOW()
WHERE strategy_key = 'EURINR_MEAN_REVERSION'
  AND deleted = FALSE;
