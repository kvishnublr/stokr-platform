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
  displayStatus?: string;
  signalAgeSec?: number;
  tradeQuality?: string;
  omsEligible?: boolean;
  outcomeStatus?: string;
  reason?: string;
  createdAt?: string;
  buyPct?: number;
  sellPct?: number;
  volumeMultiple?: string;
  winPct?: string | number;
  source?: string;
  changePct?: number;
  momentumPct?: number;
  activityScore?: number;
};

export type AdvSectorStock = {
  symbol: string;
  aiScore?: number;
  executionStatus?: string;
  side?: string;
};

export type AdvSector = {
  name: string;
  count: number;
  stocks: AdvSectorStock[] | string[];
  advancers?: number;
  decliners?: number;
  avgScore?: number;
};

export type AdvLiveControl = {
  liveEnabled?: boolean;
  platformLiveFlag?: boolean;
  liveGateOpen?: boolean;
  feedEquityStale?: boolean;
  feedIndexStale?: boolean;
  feedOperational?: boolean;
  feedWarmup?: boolean;
  feedStatus?: "OPERATIONAL" | "WARMUP" | "RECOVERING" | "STALE" | string;
  websocketConnected?: boolean;
  tickGapSeconds?: number;
  safeStartupReady?: boolean;
  marketOpen?: boolean;
  scanIntervalSec?: number;
};

export type AdvTerminalSnapshot = {
  marketRegime: string;
  regimeNarrative: string;
  marketOpen: boolean;
  sessionState?: string;
  istTime: string;
  scanIntervalSec?: number;
  truthSource?: string;
  metrics: Record<string, unknown>;
  scannerRows: AdvScannerRow[];
  liveCards: AdvScannerRow[];
  engine?: Record<string, unknown>;
  orderFlow?: {
    symbol: string;
    buyPct?: number;
    sellPct?: number;
    executionStatus?: string;
    rejectionReason?: string;
    obi?: string;
    trend?: string;
  }[];
  orderFlowSummary?: Record<string, unknown>;
  decisions?: {
    time: string;
    symbol: string;
    action: string;
    strategy: string;
    aiScore: number;
    executionStatus?: string;
    rejectionReason?: string;
    lifecycle?: string[];
    decisionFactors?: string[];
    result: string;
  }[];
  rejectedSetups?: {
    symbol: string;
    strategy?: string;
    aiScore?: number;
    executionStatus?: string;
    rejectionReason?: string;
    rejectionCode?: string;
  }[];
  sectors?: AdvSector[];
  risk?: Record<string, unknown>;
  performance?: Record<string, unknown> & {
    bySetupType?: { setup: string; count: number; executable: number; avgScore: number }[];
  };
  systemHealth?: Record<string, unknown>;
  liveControl?: AdvLiveControl;
};

const ADV_HTTP_TIMEOUT_MS = 25_000;

export async function fetchAdvTerminal(): Promise<AdvTerminalSnapshot> {
  const res = await api.get("/api/v1/adv-dashboard/terminal", { timeout: ADV_HTTP_TIMEOUT_MS });
  return (res.data?.data ?? res.data) as AdvTerminalSnapshot;
}

export async function fetchAdvMovers(): Promise<{ symbol: string; price?: string; changePct?: string; source?: string; aiScore?: number }[]> {
  const res = await api.get("/api/v1/adv-dashboard/movers", { timeout: ADV_HTTP_TIMEOUT_MS });
  return Array.isArray(res.data?.data) ? res.data.data : [];
}

export async function fetchAdvWatch(): Promise<{ symbol: string; price?: string; changePct?: string; volume?: number | string }[]> {
  const res = await api.get("/api/trader/terminal/market/watch");
  return Array.isArray(res.data?.data) ? res.data.data : [];
}

export async function fetchAdvExecutionSummary(): Promise<Record<string, unknown>> {
  const res = await api.get("/api/trader/execution-summary");
  return (res.data?.data ?? {}) as Record<string, unknown>;
}

export async function fetchAdvWorkstation(): Promise<Record<string, unknown>> {
  const res = await api.get("/api/trader/terminal/workstation", { timeout: ADV_HTTP_TIMEOUT_MS });
  return (res.data?.data ?? {}) as Record<string, unknown>;
}
