import { api } from "./client";

export type StrategyRiskStateDto = {
  strategyKey: string;
  enabled: boolean;
  liveEnabled: boolean;
  emergencyStopEnabled: boolean;
  autoDisableOnLoss: boolean;
  dailyLossLimit: number | null;
  todayPnl: number | null;
  maxPositions: number;
  openPositions: number;
  allocatedCapital: number | null;
  utilizedCapital: number | null;
  executionMode: "PAPER" | "LIVE" | "BOTH";
};

export type AdminRiskDashboardDto = {
  collectedAt: string;
  killSwitchActive: boolean;
  brokerHalt: boolean;
  liveTradingArmed: boolean;
  activeStrategies: number;
  liveEnabledStrategies: number;
  emergencyStoppedStrategies: number;
  todayOrders: number;
  todayFills: number;
  todayRejects: number;
  openReconciliationAlerts: number;
  strategyRiskStates: StrategyRiskStateDto[];
};

export type StrategyCapitalSummary = {
  strategyKey: string;
  allocatedCapital: number;
  utilizedCapital: number;
  availableCapital: number;
  realizedPnl: number;
  unrealizedPnl: number;
  maxPositions: number;
  openPositions: number;
  liveEnabled: boolean;
  emergencyStopEnabled: boolean;
  enabled: boolean;
};

export type GlobalCapitalSummary = {
  totalAllocatedCapital: number;
  totalUtilizedCapital: number;
  totalAvailableCapital: number;
  totalRealizedPnl: number;
  totalUnrealizedPnl: number;
  activeStrategies: number;
  liveEnabledStrategies: number;
  emergencyStoppedStrategies: number;
  strategies: StrategyCapitalSummary[];
};

export async function fetchRiskDashboard(): Promise<AdminRiskDashboardDto> {
  const res = await api.get("/api/admin/risk-dashboard");
  return res.data?.data as AdminRiskDashboardDto;
}

export async function fetchGlobalCapital(): Promise<GlobalCapitalSummary> {
  const res = await api.get("/api/admin/capital");
  return res.data?.data as GlobalCapitalSummary;
}

export async function fetchStrategyCapital(strategyKey: string): Promise<StrategyCapitalSummary> {
  const res = await api.get(`/api/admin/capital/${strategyKey}`);
  return res.data?.data as StrategyCapitalSummary;
}
