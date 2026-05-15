/** Shape of `GET /api/admin/operations/snapshot` â€” sections are intentionally loose (maps). */

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
  /** Cascade: platform tape â†’ scanner â†’ signals â†’ OMS (backend-computed). */
  operationalLifecycle?: Record<string, unknown>;
  /** Admin-owned platform market feed (per vendor), from platform_broker_feed_sessions. */
  platformMarketFeed?: Record<string, unknown>;
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
  return "â€”";
}

export function fmtInt(v: unknown): string {
  if (typeof v === "number" && Number.isFinite(v)) return String(Math.round(v));
  if (typeof v === "string" && v.trim() !== "" && !Number.isNaN(Number(v))) return String(Math.round(Number(v)));
  return "â€”";
}

export function badgeClassForStatus(status: string): string {
  const s = status.toUpperCase().replace(/\s+/g, "_");
  if (s === "ON") return "border-red-600/60 bg-red-600/15 text-red-800 dark:text-red-100";
  if (s === "OFF") return "border-emerald-600/50 bg-emerald-600/10 text-emerald-800 dark:text-emerald-100";
  if (s === "ARMED") return "border-amber-600/50 bg-amber-600/15 text-amber-900 dark:text-amber-100";
  if (s === "CONNECTED" || s === "OK" || s === "COMPLETED" || s === "DISARMED" || s === "READY" || s === "RUNNING" || s === "OPERATIONAL")
    return "border-emerald-600/50 bg-emerald-600/10 text-emerald-900 dark:text-emerald-100";
  if (s === "STALE" || s === "DEGRADED" || s === "BACKFILLING" || s === "RECONNECTING")
    return "border-amber-600/50 bg-amber-600/12 text-amber-950 dark:text-amber-100";
  if (s === "UNAVAILABLE")
    return "border-red-600/50 bg-red-600/12 text-red-900 dark:text-red-100";
  if (s === "SATURATED") return "border-orange-600/50 bg-orange-600/15 text-orange-950 dark:text-orange-100";
  if (s === "DISCONNECTED" || s === "FAILED" || s === "OFFLINE")
    return "border-red-600/55 bg-red-600/12 text-red-900 dark:text-red-100";
  if (s === "AUTH_EXPIRED" || s === "RATE_LIMITED" || s === "LIMITED")
    return "border-amber-700/55 bg-amber-600/15 text-amber-950 dark:text-amber-50";
  if (s === "PAUSED" || s === "WAITING_FOR_MARKET_DATA" || s === "IDLE")
    return "border-orange-600/50 bg-orange-500/12 text-orange-950 dark:text-orange-100";
  if (s === "UNKNOWN" || s === "NOT_INSTRUMENTED")
    return "border-dashed border-border bg-muted/40 text-foreground/80 dark:text-muted-foreground";
  return "border-border bg-card text-foreground";
}

