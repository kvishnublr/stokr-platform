-- V58: Publish parameter metadata for NSE_SPIKE_DETECTION (Historical replay launcher).
-- V36 inserted the strategy without parameter_metadata_json; V55 removed the MEAN_REVERSION fallback source.

UPDATE strategy_definitions
SET parameter_metadata_json = $META$
{
  "schemaVersion": 2,
  "strategyKey": "NSE_SPIKE_DETECTION",
  "displayName": "NSE Spike Detection (1m)",
  "description": "1m momentum spike strategy for NSE equities using velocity, volume burst, and bar quality.",
  "category": "INTRADAY",
  "supportedMarkets": ["NSE"],
  "requiredIndicators": ["PRICE", "VOLUME"],
  "executionCapabilities": {"backtest": true, "paper": true, "live": true},
  "allowedTimeframes": ["1m"],
  "allowedExecutionModes": ["BACKTEST", "PAPER", "LIVE"],
  "allowedFeeModels": ["NONE", "PERCENT_2_BPS", "PERCENT_5_BPS"],
  "allowedSlippageModels": ["NONE", "SPREAD_PROXY", "VOL_SCALED"],
  "allowedExecutionProfiles": ["SIMULATED_DEFAULT", "REPLAY_RAW", "CONSERVATIVE", "BALANCED", "AGGRESSIVE"],
  "parameters": [
    {"id": "minCompositeScore", "type": "number", "label": "Composite score threshold", "required": false, "defaultValue": 65, "group": "signals", "validation": {"min": 50, "max": 95, "step": 1}, "precision": 1},
    {"id": "minVelocityPct", "type": "number", "label": "Min velocity % / minute", "required": false, "defaultValue": 0.12, "group": "signals", "validation": {"min": 0.05, "max": 1.0, "step": 0.01}, "precision": 2},
    {"id": "minVolumeMultiple", "type": "number", "label": "Min volume burst multiple", "required": false, "defaultValue": 1.2, "group": "filters", "validation": {"min": 1.0, "max": 5.0, "step": 0.01}, "precision": 2},
    {"id": "minBarQualityThreshold", "type": "number", "label": "Min bar quality score", "required": false, "defaultValue": 60, "group": "filters", "validation": {"min": 40, "max": 90, "step": 1}, "precision": 0},
    {"id": "maxWickPctBeforeReject", "type": "number", "label": "Max wick % before reject", "required": false, "defaultValue": 0.70, "group": "filters", "validation": {"min": 0.2, "max": 0.95, "step": 0.01}, "precision": 2},
    {"id": "requireContinuationCandle", "type": "boolean", "label": "Require continuation candle", "required": false, "defaultValue": true, "group": "signals"},
    {"id": "cooldownSeconds", "type": "integer", "label": "Cooldown seconds", "required": false, "defaultValue": 300, "group": "timing", "validation": {"min": 30, "max": 3600}},
    {"id": "slOffsetPct", "type": "number", "label": "Stop-loss offset %", "required": false, "defaultValue": 0.50, "group": "risk", "validation": {"min": 0.1, "max": 2.0, "step": 0.01}, "precision": 2},
    {"id": "targetTightRangeExtension", "type": "number", "label": "Tight-range target extension %", "required": false, "defaultValue": 0.50, "group": "risk", "validation": {"min": 0.1, "max": 3.0, "step": 0.01}, "precision": 2},
    {"id": "targetWideRangeExtension", "type": "number", "label": "Wide-range target extension %", "required": false, "defaultValue": 1.50, "group": "risk", "validation": {"min": 0.5, "max": 5.0, "step": 0.01}, "precision": 2}
  ],
  "deploymentDefaults": {
    "symbol": "RELIANCE",
    "timeframe": "1m",
    "executionProfile": "REPLAY_RAW",
    "feeModel": "PERCENT_2_BPS",
    "slippageModel": "SPREAD_PROXY"
  },
  "previewMetrics": {
    "avgMonthlyReturnPct": 2.8,
    "winRatePct": 58,
    "maxDrawdownPct": 8.5,
    "riskLevel": "High",
    "avgTradesPerDay": 12,
    "holdingHorizon": "Intraday (session)"
  }
}
$META$,
    updated_at = NOW()
WHERE strategy_key = 'NSE_SPIKE_DETECTION'
  AND deleted = FALSE
  AND (parameter_metadata_json IS NULL OR TRIM(parameter_metadata_json) = '');
