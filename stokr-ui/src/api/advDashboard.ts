import { api } from "./client";

export type ExecutionStatus =
  | "EXECUTABLE"
  | "WATCHLIST"
  | "INTELLIGENCE_ONLY"
  | "BLOCKED"
  | "REJECTED"
  | "COOLDOWN"
  | "OMS_REJECTED"
  | "QUALITY_REJECTED"
  | "EXECUTED";

export type AdvSetupCard = {
  symbol: string;
  setupType: string;
  confidenceScore: number;
  qualityTier: string;
  badges: string[];
  entryPrice: number | null;
  targetPrice: number | null;
  stopLoss: number | null;
  riskRewardRatio: number | null;
  whyThisTrade: string;
  riskNote: string;
};

export type AdvScannerRow = {
  rank: number;
  signalId?: string | null;
  symbol: string;
  strategy?: string;
  side?: string;
  ltp?: number | string | null;
  aiScore: number;
  probability?: number;
  executionStatus: ExecutionStatus | string;
  pipelineStage?: string;
  rejectionReason?: string | null;
  rejectionCode?: string | null;
  requestedMode?: string;
  effectiveMode?: string;
  qualityGate?: string;
  riskGate?: string;
  cooldownSecRemaining?: number;
  lifecycle?: string[];
  setupType?: string;
  status?: string;
  signalAgeSec?: number;
  tradeQuality?: string;
  omsEligible?: boolean;
  outcomeStatus?: string;
  reason?: string;
  createdAt?: string;
};

export type AdvLiveControl = {
  liveEnabled?: boolean;
  platformLiveFlag?: boolean;
  liveGateOpen?: boolean;
  feedEquityStale?: boolean;
  feedIndexStale?: boolean;
  feedOperational?: boolean;
  safeStartupReady?: boolean;
  marketOpen?: boolean;
  scanIntervalSec?: number;
};

export type AdvTerminalSnapshot = {
  marketRegime: string;
  regimeNarrative: string;
  marketOpen: boolean;
  istTime: string;
  scanIntervalSec?: number;
  truthSource?: string;
  metrics: Record<string, unknown>;
  scannerRows: AdvScannerRow[];
  liveCards: AdvScannerRow[];
  engine?: Record<string, unknown>;
  orderFlow?: { symbol: string; executionStatus?: string; rejectionReason?: string; obi?: string; trend?: string }[];
  decisions?: {
    time: string;
    symbol: string;
    action: string;
    strategy: string;
    aiScore: number;
    executionStatus?: string;
    rejectionReason?: string;
    lifecycle?: string[];
    result: string;
  }[];
  sectors?: { name: string; count: number; stocks: string[] }[];
  risk?: Record<string, unknown>;
  performance?: Record<string, unknown>;
  liveControl?: AdvLiveControl;
};

export type AdvDashboardSnapshot = {
  marketRegime: string;
  regimeNarrative: string;
  metrics: Record<string, unknown>;
  setups: AdvSetupCard[];
  principles: string[];
};

export async function fetchAdvDashboardSnapshot(): Promise<AdvDashboardSnapshot> {
  const res = await api.get("/api/v1/adv-dashboard/snapshot");
  return res.data as AdvDashboardSnapshot;
}

export async function fetchAdvTerminal(): Promise<AdvTerminalSnapshot> {
  const res = await api.get("/api/v1/adv-dashboard/terminal");
  return (res.data?.data ?? res.data) as AdvTerminalSnapshot;
}
