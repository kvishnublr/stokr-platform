/** Shape of `GET /api/admin/operations/snapshot` — sections are intentionally loose (maps). */

export type OpsSnapshot = {
  collectedAt: string;
  marketInfra: Record<string, unknown>;
  replayInfra: Record<string, unknown>;
  oms: Record<string, unknown>;
  system: Record<string, unknown>;
  brokerSessions?: Record<string, unknown>;
  marketFreshness?: Record<string, unknown>;
  scannerTelemetry?: Record<string, unknown>;
  signalDistribution?: Record<string, unknown>;
  traderExecutionHealth?: Record<string, unknown>;
  incidents?: Array<Record<string, unknown>>;
  marketPlane?: Record<string, unknown>;
  operationalHistory?: Record<string, unknown>;
};

export function asRecord(v: unknown): Record<string, unknown> | undefined {
  return v != null && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined;
}

export function asArray(v: unknown): unknown[] | undefined {
  return Array.isArray(v) ? v : undefined;
}

export function fmtNum(v: unknown, digits = 2): string {
  if (typeof v === "number" && Number.isFinite(v)) return v.toFixed(digits);
  if (typeof v === "string" && v.trim() !== "" && !Number.isNaN(Number(v))) return Number(v).toFixed(digits);
  return "—";
}

export function fmtInt(v: unknown): string {
  if (typeof v === "number" && Number.isFinite(v)) return String(Math.round(v));
  if (typeof v === "string" && v.trim() !== "" && !Number.isNaN(Number(v))) return String(Math.round(Number(v)));
  return "—";
}

export function badgeClassForStatus(status: string): string {
  const s = status.toUpperCase();
  if (s === "ON") return "border-red-600/60 bg-red-600/15 text-red-100";
  if (s === "OFF") return "border-emerald-500/50 bg-emerald-500/10 text-emerald-200";
  if (s === "ARMED") return "border-amber-500/50 bg-amber-500/15 text-amber-100";
  if (s === "CONNECTED" || s === "OK" || s === "COMPLETED" || s === "DISARMED")
    return "border-emerald-500/50 bg-emerald-500/10 text-emerald-200";
  if (s === "STALE" || s === "DEGRADED" || s === "BACKFILLING" || s === "RECONNECTING")
    return "border-amber-500/50 bg-amber-500/10 text-amber-200";
  if (s === "SATURATED") return "border-orange-600/50 bg-orange-600/15 text-orange-100";
  if (s === "DISCONNECTED" || s === "FAILED") return "border-red-500/50 bg-red-500/10 text-red-200";
  if (s === "UNKNOWN" || s === "NOT_INSTRUMENTED") return "border-dashed border-border text-muted-foreground";
  return "border-border bg-card text-foreground";
}
