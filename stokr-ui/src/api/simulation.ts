import { api } from "./client";

export type SimulationRuntimeStatus = {
  enabled: boolean;
  enabledAt?: string;
  enabledBy?: string;
};

export type SimulationHarnessReport = {
  simulationRunId: string;
  scenario: string;
  strategyKey: string;
  symbol: string;
  signalId: string | null;
  success: boolean;
  validation: Record<string, unknown>;
};

export type SimulationDashboard = {
  runs: Array<Record<string, unknown>>;
  signals: Array<Record<string, unknown>>;
  aggregates: Record<string, unknown>;
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
  return res.data.data;
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
