import type { QueryClient } from "@tanstack/react-query";
import { api } from "../api/client";

export const TRADER_EXECUTION_MODE_QUERY_KEY = ["trader-execution-mode-pref"] as const;

export type TraderExecutionMode = "PAPER" | "LIVE";

export function normalizeTraderExecutionMode(raw: unknown): TraderExecutionMode {
  return String(raw ?? "PAPER").toUpperCase() === "LIVE" ? "LIVE" : "PAPER";
}

export async function fetchTraderExecutionMode(): Promise<TraderExecutionMode> {
  const res = await api.get("/api/trader/me/execution-mode");
  return normalizeTraderExecutionMode(res.data?.data?.executionMode);
}

/** Strategy feed rows use `pipeline` (PAPER | LIVE | SIMULATED). */
export function signalMatchesExecutionMode(
  row: { pipeline?: string | null; executionMode?: string | null },
  mode: TraderExecutionMode,
): boolean {
  const pipeline = String(row.pipeline ?? row.executionMode ?? "").toUpperCase();
  if (!pipeline) return true;
  if (mode === "LIVE") return pipeline === "LIVE";
  return pipeline === "PAPER" || pipeline === "SIMULATED";
}

/** Refetch trader views that depend on workspace execution mode. */
export function invalidateTraderExecutionModeQueries(qc: QueryClient) {
  void qc.invalidateQueries({ queryKey: [...TRADER_EXECUTION_MODE_QUERY_KEY] });
  void qc.invalidateQueries({ queryKey: ["trader-exec-mode"] });
  void qc.invalidateQueries({ queryKey: ["trader-workstation"] });
  void qc.invalidateQueries({ queryKey: ["trader-dashboard-workstation"] });
  void qc.invalidateQueries({ queryKey: ["intraday-workstation"] });
  void qc.invalidateQueries({ queryKey: ["trader-signals-feed"] });
  void qc.invalidateQueries({ queryKey: ["trader-dashboard-signals-feed"] });
  void qc.invalidateQueries({ queryKey: ["trader-strategy-feed"] });
  void qc.invalidateQueries({ queryKey: ["oms-orders"] });
  void qc.invalidateQueries({ queryKey: ["trader-dashboard-orders"] });
  void qc.invalidateQueries({ queryKey: ["oms-execs"] });
  void qc.invalidateQueries({ queryKey: ["trader-dashboard-exec-summary"] });
  void qc.invalidateQueries({ queryKey: ["trader-dashboard-portfolio-overview"] });
  void qc.invalidateQueries({ queryKey: ["sidebar-portfolio-snapshot"] });
}
