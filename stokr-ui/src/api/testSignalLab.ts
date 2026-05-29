import { api } from "./client";

export type TestSignalLabRequest = {
  traderUserId: string;
  brokerAccountId?: string;
  strategyKey: string;
  strategyTemplate?: string;
  symbol: string;
  side: "BUY" | "SELL";
  quantity?: number;
  productType?: string;
  orderType?: string;
  executionMode: "SIMULATED" | "PAPER" | "LIVE" | "BOTH";
  exchange?: string;
  price?: number;
  triggerType?: "INSTANT" | "DELAYED" | "MARKET_OPEN_SIMULATION";
  forceQuantityOne: boolean;
  dryRunOnly: boolean;
  skipActualBrokerExecution: boolean;
  simulateRejection: boolean;
  simulateTimeout: boolean;
  simulateStaleWebsocket: boolean;
  simulateMarginFailure: boolean;
  simulateBrokerDisconnect: boolean;
  autoSquareOffMinutes?: number;
};

export type TestSignalPreflightReport = {
  canSubmit: boolean;
  effectiveExecutionMode: string;
  blockers: string[];
  checks: Array<{
    key: string;
    label: string;
    status: string;
    message: string;
    suggestedAction?: string | null;
    actionCode?: string | null;
  }>;
};

export async function fetchTestSignalLabOptions() {
  const res = await api.get("/api/admin/test-signal-lab/options");
  return res.data?.data as {
    traders: Array<{ userId: string; username: string; displayName: string; liveTradingApproved: boolean }>;
    brokerAccounts: Array<{ id: string; userId: string; vendorCode: string; status: string }>;
    strategies: Array<{
      id: string;
      strategyKey: string;
      displayName: string;
      segment?: string;
      defaultExchange?: string;
    }>;
    executionModes: string[];
    triggerTypes: string[];
  };
}

export async function runTestSignalLab(request: TestSignalLabRequest) {
  const res = await api.post("/api/admin/test-signal-lab/run", request);
  return res.data?.data as any;
}

export async function runTestSignalPreflight(request: TestSignalLabRequest) {
  const res = await api.post("/api/admin/test-signal-lab/preflight", request);
  return res.data?.data as TestSignalPreflightReport;
}

export async function fetchTestSignalRuns(page = 0, size = 30) {
  const res = await api.get("/api/admin/test-signal-lab/runs", { params: { page, size } });
  return res.data?.data as { content: any[]; page: number; size: number; totalElements: number; totalPages: number };
}

export async function fetchTestSignalRunReport(id: string) {
  const res = await api.get(`/api/admin/test-signal-lab/runs/${id}`);
  return res.data?.data as any;
}

export async function remediateTestIssue(payload: { actionCode: string; traderUserId?: string; vendor?: string }) {
  const res = await api.post("/api/admin/test-signal-lab/remediate", payload);
  return res.data?.data as any;
}

export async function queueCommoditiesScannerFire(symbol = "CRUDEOIL") {
  const res = await api.post("/api/admin/test-signal-lab/queue-scanner-fire", { symbol });
  return res.data?.data as { queued: boolean; symbol: string; strategyKey: string; message: string };
}

export async function fetchTestSignalTelemetry() {
  const res = await api.get("/api/admin/test-signal-lab/telemetry");
  return res.data?.data as any;
}
