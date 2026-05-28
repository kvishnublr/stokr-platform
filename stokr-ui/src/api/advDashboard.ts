import { api } from "./client";

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
  symbol: string;
  ltp?: number | string;
  changePct?: number | string;
  aiScore: number;
  status: string;
  side?: string;
  setupType?: string;
  buyPct?: number;
  volumeMultiple?: string;
  winPct?: number;
  regimeFit?: boolean;
  badges?: string[];
};

export type AdvTerminalSnapshot = {
  marketRegime: string;
  regimeNarrative: string;
  marketOpen: boolean;
  istTime: string;
  metrics: Record<string, unknown>;
  scannerRows: AdvScannerRow[];
  liveCards: Record<string, unknown>[];
  engine?: Record<string, unknown>;
  orderFlow?: { symbol: string; buyPct: number; sellPct: number; obi: number | string; trend: string }[];
  decisions?: { time: string; symbol: string; action: string; strategy: string; aiScore: number; result: string }[];
  sectors?: { name: string; count: number; stocks: string[] }[];
  risk?: Record<string, unknown>;
  performance?: Record<string, unknown>;
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
