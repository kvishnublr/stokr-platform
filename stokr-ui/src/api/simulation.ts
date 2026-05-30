import { api } from "./client";

export type SimulationRuntimeStatus = {
  enabled: boolean;
  enabledAt?: string;
  enabledBy?: string;
};

export type HarnessValidation = {
  signalGenerated?: boolean;
  confidencePersisted?: boolean;
  confidenceV2?: boolean;
  omsExecuted?: boolean;
  orderState?: string | null;
  outcomeStatus?: string | null;
  entryPrice?: number | string | null;
  targetPrice?: number | string | null;
  stopPrice?: number | string | null;
  realizedPnl?: number | string | null;
  protectionTriggered?: boolean;
  simulationRunId?: string | null;
  pipelineSteps?: string[];
  error?: string | null;
};

export type SimulationHarnessReport = {
  simulationRunId: string;
  scenario: string;
  strategyKey: string;
  symbol: string;
  signalId: string | null;
  success: boolean;
  validation: HarnessValidation;
};

export type SimulationRunRow = {
  runId: string;
  scenario: string;
  status: string;
  success: boolean;
  startedAt: string;
  completedAt?: string | null;
  signalCount: number;
  orderCount: number;
};

export type SimulationSignalRow = {
  signalId: string;
  strategy: string;
  symbol: string;
  confidence: number | null;
  confidenceVersion: string | null;
  outcomeStatus: string | null;
  targetPrice?: number | string | null;
  stopPrice?: number | string | null;
  realizedPnl?: number | string | null;
  scenario?: string | null;
  orderState: string | null;
};

export type SimulationAggregates = {
  signals?: number;
  targetHits?: number;
  stopLosses?: number;
  protectionExits?: number;
  confidencePopulated?: number;
};

export type SimulationDashboard = {
  runs: SimulationRunRow[];
  signals: SimulationSignalRow[];
  aggregates: SimulationAggregates;
};

export type ScenarioValidationResult = {
  scenario: string;
  passed: boolean;
  validation: HarnessValidation;
};

export type ValidationPackReport = {
  allPassed: boolean;
  scenarios: ScenarioValidationResult[];
  analyticsIsolation: Record<string, unknown>;
};

export async function fetchSimulationStatus() {
  const res = await api.get("/api/admin/simulation/runtime/status");
  return res.data.data as SimulationRuntimeStatus;
}

export async function enableSimulationRuntime() {
  const res = await api.post("/api/admin/simulation/runtime/enable");
  return res.data.data as SimulationRuntimeStatus;
}

export async function disableSimulationRuntime() {
  const res = await api.post("/api/admin/simulation/runtime/disable");
  return res.data.data as SimulationRuntimeStatus;
}

export async function listScenarios() {
  const res = await api.get("/api/admin/simulation/scenarios");
  return res.data.data as string[];
}

export async function runSimulationScenario(body: Record<string, unknown>) {
  const res = await api.post("/api/admin/simulation/run", body);
  return res.data.data as SimulationHarnessReport;
}

export async function fetchSimulationDashboard(runId?: string) {
  const res = await api.get("/api/admin/simulation/dashboard", {
    params: runId ? { runId } : {},
  });
  return res.data.data as SimulationDashboard;
}

export async function runValidationPack() {
  const res = await api.post("/api/admin/simulation/validate-release");
  return res.data.data as ValidationPackReport;
}

export async function cleanupSimulation(body: {
  runId?: string;
  scenario?: string;
  from?: string;
  toExclusive?: string;
}) {
  const res = await api.delete("/api/admin/simulation/cleanup", { data: body });
  return res.data.data;
}
