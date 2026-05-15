/** Mirrors `StrategyMetadataResponseDto` + nested DTOs from the API (PR-1 contract). */

export type StrategyExecutionCapabilities = {
  backtest: boolean;
  paper: boolean;
  live: boolean;
};

export type StrategyParameterField = {
  id: string;
  type: string;
  label: string;
  description?: string | null;
  required: boolean;
  defaultValue?: unknown;
  validation?: Record<string, unknown> | null;
  enumValues?: string[] | null;
  group?: string | null;
  precision?: number | null;
  visibleWhen?: Record<string, unknown> | null;
};

export type StrategyDeploymentDefaults = {
  symbol: string;
  timeframe: string;
  executionProfile: string;
  feeModel: string;
  slippageModel: string;
};

export type StrategyPreviewMetrics = {
  avgMonthlyReturnPct: number;
  winRatePct: number;
  maxDrawdownPct: number;
  riskLevel: string;
  avgTradesPerDay: number;
  tradeFrequency?: string | null;
};

export type StrategyMetadataResponse = {
  schemaVersion: number;
  strategyKey: string;
  displayName: string;
  description?: string | null;
  category?: string | null;
  supportedMarkets: string[];
  requiredIndicators: string[];
  executionCapabilities: StrategyExecutionCapabilities;
  parameters: StrategyParameterField[];
  allowedTimeframes?: string[] | null;
  allowedExecutionModes?: string[] | null;
  allowedFeeModels?: string[] | null;
  allowedSlippageModels?: string[] | null;
  allowedExecutionProfiles?: string[] | null;
  /** Strategy-owned replay defaults (symbol, timeframe, cost models). */
  deploymentDefaults?: StrategyDeploymentDefaults | null;
  /** Read-only confidence metrics for the launcher card. */
  previewMetrics?: StrategyPreviewMetrics | null;
};
