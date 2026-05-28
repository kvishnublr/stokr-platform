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
