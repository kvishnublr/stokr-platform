import { api } from "./client";

export type AssetClass = "CASH" | "F&O" | "CURRENCY" | "MCX";

export type AssetClassAllocationDto = {
  assetClass: AssetClass;
  allocatedCapital: number;
  maxConcurrentPositions: number;
  perTradeLimitAmount: number;
  enabled: boolean;
  dailyLossLimit: number | null;
  dailyLossLimitEnabled: boolean;
  /** Read-only: member strategy keys this bucket fans out to. Ignored on write. */
  memberStrategyKeys: string[];
};

export type TraderCapitalAllocationView = {
  totalCapital: number;
  allocations: AssetClassAllocationDto[];
};

const BASE = "/api/trader/capital-allocation";

export async function fetchCapitalAllocation(): Promise<TraderCapitalAllocationView> {
  const res = await api.get(BASE);
  return res.data?.data as TraderCapitalAllocationView;
}

export async function saveCapitalAllocation(
  body: TraderCapitalAllocationView,
): Promise<TraderCapitalAllocationView> {
  const res = await api.put(BASE, body);
  return res.data?.data as TraderCapitalAllocationView;
}
