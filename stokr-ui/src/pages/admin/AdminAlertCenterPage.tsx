import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { api, parseAxiosMessage } from "../../api/client";
import { IncidentFeed } from "../../components/admin/cockpit/AdminCockpitPanels";
import { asRecord, type OpsSnapshot } from "../../components/admin/cockpit/opsTypes";
import { AdminPageShell, AdminPanel, AdminSection } from "../../components/admin/institutional/AdminDesignSystem";
import { EmptyState } from "../../components/ds/EmptyState";
import { PageSkeleton } from "../../components/ds/SkeletonLoader";
import { useUiThemeStore } from "../../state/uiTheme";
import { cn } from "../../lib/utils";

const LS_RESOLVED = "stokr-ops-incidents-resolved";
const LS_MUTED = "stokr-ops-incidents-muted";
const SS_ACK = "stokr-ops-incidents-ack";

function loadIds(key: string): Set<string> {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return new Set();
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return new Set();
    return new Set(parsed.map(String));
  } catch {
    return new Set();
  }
}

function loadAckIds(): Set<string> {
  try {
    const raw = sessionStorage.getItem(SS_ACK);
    if (!raw) return new Set();
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return new Set();
    return new Set(parsed.map(String));
  } catch {
    return new Set();
  }
}

function incidentId(row: Record<string, unknown>): string {
  return `${String(row.code ?? "")}:${String(row.detectedAt ?? "")}`;
}

type IncidentSummary = {
  total: number;
  active: number;
  acknowledged: number;
  critical: number;
  warn: number;
  info: number;
};

function summarizeIncidents(incidents: unknown[]): IncidentSummary {
  const resolved = loadIds(LS_RESOLVED);
  const muted = loadIds(LS_MUTED);
  const acked = loadAckIds();

  let total = 0;
  let active = 0;
  let acknowledged = 0;
  let critical = 0;
  let warn = 0;
  let info = 0;

  for (const raw of incidents) {
    const row = asRecord(raw);
    if (!row) continue;
    total += 1;

    const id = incidentId(row);
    const level = String(row.level ?? "info").toLowerCase();
    const isVisible = !muted.has(id) && !resolved.has(id);

    if (isVisible) {
      active += 1;
      if (level === "critical") critical += 1;
      else if (level === "warn" || level === "warning") warn += 1;
      else info += 1;
    }

    if (acked.has(id)) acknowledged += 1;
  }

  return { total, active, acknowledged, critical, warn, info };
}

type ExecutionAlertRow = {
  id: string;
  createdAt: string;
  alertType: string;
  strategyKey: string | null;
  symbol: string | null;
  payloadJson: string | null;
};

type ExecutionSummary = {
  killSwitchActive?: boolean;
  recentLiveFailures15m?: number;
  lastLiveFillAt?: string | null;
  lastLiveFailureAt?: string | null;
  todayOrdersByModeState?: Array<{ mode: string; state: string; cnt: number }>;
};

function alertText(row: ExecutionAlertRow): string {
  try {
    const parsed = JSON.parse(row.payloadJson ?? "{}") as { text?: string };
    if (parsed.text) return parsed.text;
  } catch {
    /* fall through */
  }
  return `${row.alertType} ${row.strategyKey ?? ""} ${row.symbol ?? ""}`.trim();
}

function fmtAgo(iso: string | null | undefined): string {
  if (!iso) return "never";
  const ms = Date.now() - new Date(iso).getTime();
  if (ms < 60_000) return `${Math.max(1, Math.round(ms / 1000))}s ago`;
  if (ms < 3_600_000) return `${Math.round(ms / 60_000)}m ago`;
  return `${Math.round(ms / 3_600_000)}h ago`;
}

export function AdminAlertCenterPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const query = useQuery({
    queryKey: ["admin-alert-center"],
    queryFn: async () => {
      const res = await api.get("/api/admin/alerts");
      return Array.isArray(res.data?.data) ? res.data.data : [];
    },
    staleTime: 10_000,
    refetchInterval: 30_000,
  });

  const execSummaryQ = useQuery({
    queryKey: ["admin-execution-alert-summary"],
    queryFn: async () => (await api.get("/api/admin/execution-alerts/summary")).data?.data as ExecutionSummary,
    staleTime: 10_000,
    refetchInterval: 15_000,
  });

  const execAlertsQ = useQuery({
    queryKey: ["admin-execution-alerts"],
    queryFn: async () => {
      const res = await api.get("/api/admin/execution-alerts", { params: { failuresOnly: false } });
      return Array.isArray(res.data?.data) ? (res.data.data as ExecutionAlertRow[]) : [];
    },
    staleTime: 10_000,
    refetchInterval: 30_000,
  });

  const incidents = query.data ?? [];
  const summary = useMemo(() => summarizeIncidents(incidents), [incidents]);
  const snapshot = useMemo<OpsSnapshot>(
    () => ({
      collectedAt: new Date().toISOString(),
      marketInfra: {},
      replayInfra: {},
      oms: {},
      system: {},
      incidents: incidents as Array<Record<string, unknown>>,
    }),
    [incidents],
  );

  const metrics = [
    { label: "Active", value: summary.active, warn: summary.active > 0 },
    { label: "Acknowledged", value: summary.acknowledged },
    { label: "Critical", value: summary.critical, warn: summary.critical > 0 },
    { label: "Warning", value: summary.warn, warn: summary.warn > 0 },
    { label: "Info", value: summary.info },
    { label: "Total signals", value: summary.total },
  ];

  return (
    <AdminPageShell
      isLight={isLight}
      eyebrow="Institutional console"
      title="Alert Center"
      subtitle="Active incidents and acknowledgement workflow."
    >
      {query.isError ? (
        <AdminPanel isLight={isLight} title="Load failed">
          <p className={cn("text-sm", isLight ? "text-rose-700" : "text-rose-300")}>
            Failed to load alerts: {parseAxiosMessage(query.error)}
          </p>
          <button
            type="button"
            className={cn("mt-3 text-sm font-semibold underline", isLight ? "text-blue-700" : "text-blue-300")}
            onClick={() => void query.refetch()}
          >
            Retry
          </button>
        </AdminPanel>
      ) : null}

      {query.isLoading ? <PageSkeleton cards={3} /> : null}

      {!query.isLoading && !query.isError ? (
        <>
          <AdminSection
            isLight={isLight}
            title="Live execution health"
            subtitle="Broker-level order outcomes — the first place a live failure shows up"
          >
            <AdminPanel isLight={isLight}>
              {(execSummaryQ.data?.recentLiveFailures15m ?? 0) > 0 ? (
                <div className="mb-3 animate-pulse rounded-lg border border-rose-500/60 bg-rose-500/15 px-4 py-3">
                  <p className={cn("text-sm font-bold", isLight ? "text-rose-700" : "text-rose-300")}>
                    ⚠ {execSummaryQ.data?.recentLiveFailures15m} LIVE order failure(s) in the last 15 minutes
                  </p>
                </div>
              ) : null}
              <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
                <div className={cn(
                  "rounded-lg border px-3 py-2",
                  execSummaryQ.data?.killSwitchActive
                    ? "border-amber-500/50 bg-amber-500/10"
                    : isLight ? "border-neutral-200" : "border-neutral-800",
                )}>
                  <p className="text-[10px] uppercase text-neutral-500">Kill switch</p>
                  <p className="font-mono text-sm font-semibold">
                    {execSummaryQ.data?.killSwitchActive ? "ENGAGED" : "OFF"}
                  </p>
                </div>
                <div className={cn("rounded-lg border px-3 py-2", isLight ? "border-neutral-200" : "border-neutral-800")}>
                  <p className="text-[10px] uppercase text-neutral-500">Last LIVE fill</p>
                  <p className="font-mono text-sm font-semibold">{fmtAgo(execSummaryQ.data?.lastLiveFillAt)}</p>
                </div>
                <div className={cn(
                  "rounded-lg border px-3 py-2",
                  execSummaryQ.data?.lastLiveFailureAt ? "border-rose-500/40 bg-rose-500/5" : isLight ? "border-neutral-200" : "border-neutral-800",
                )}>
                  <p className="text-[10px] uppercase text-neutral-500">Last LIVE failure</p>
                  <p className="font-mono text-sm font-semibold">{fmtAgo(execSummaryQ.data?.lastLiveFailureAt)}</p>
                </div>
                <div className={cn("rounded-lg border px-3 py-2", isLight ? "border-neutral-200" : "border-neutral-800")}>
                  <p className="text-[10px] uppercase text-neutral-500">Today's orders</p>
                  <p className="font-mono text-xs">
                    {(execSummaryQ.data?.todayOrdersByModeState ?? []).length === 0
                      ? "none"
                      : (execSummaryQ.data?.todayOrdersByModeState ?? [])
                          .map((r) => `${r.mode} ${r.state}: ${r.cnt}`)
                          .join(" · ")}
                  </p>
                </div>
              </div>
              {(execAlertsQ.data ?? []).length > 0 ? (
                <div className="mt-3 max-h-64 overflow-y-auto rounded-lg border border-neutral-700/40">
                  <table className="w-full text-left text-xs">
                    <thead className={cn("sticky top-0", isLight ? "bg-neutral-100" : "bg-neutral-900")}>
                      <tr>
                        <th className="px-3 py-2 font-semibold uppercase text-neutral-500">Time</th>
                        <th className="px-3 py-2 font-semibold uppercase text-neutral-500">Type</th>
                        <th className="px-3 py-2 font-semibold uppercase text-neutral-500">Detail</th>
                      </tr>
                    </thead>
                    <tbody>
                      {(execAlertsQ.data ?? []).slice(0, 50).map((row) => (
                        <tr key={row.id} className={cn("border-t", isLight ? "border-neutral-200" : "border-neutral-800")}>
                          <td className="whitespace-nowrap px-3 py-1.5 font-mono text-neutral-500">
                            {new Date(row.createdAt).toLocaleTimeString()}
                          </td>
                          <td className="whitespace-nowrap px-3 py-1.5">
                            <span className={cn(
                              "rounded px-1.5 py-0.5 font-mono text-[10px] font-bold",
                              row.alertType.includes("REJECT")
                                ? "bg-rose-500/15 text-rose-500"
                                : row.alertType === "LIVE_FILL"
                                  ? "bg-emerald-500/15 text-emerald-500"
                                  : "bg-neutral-500/15 text-neutral-400",
                            )}>
                              {row.alertType}
                            </span>
                          </td>
                          <td className="px-3 py-1.5">{alertText(row)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <p className={cn("mt-3 text-xs", isLight ? "text-neutral-500" : "text-neutral-500")}>
                  No execution alerts recorded yet — alerts appear here on every LIVE fill or rejection.
                </p>
              )}
            </AdminPanel>
          </AdminSection>

          <AdminSection isLight={isLight} title="Summary">
            <AdminPanel isLight={isLight}>
              {summary.active === 0 ? (
                <p className={cn("text-sm", isLight ? "text-neutral-600" : "text-neutral-400")}>No active alerts</p>
              ) : null}
              <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6">
                {metrics.map((m) => (
                  <div
                    key={m.label}
                    className={cn(
                      "rounded-lg border px-3 py-2",
                      m.warn ? "border-rose-500/40 bg-rose-500/10" : isLight ? "border-neutral-200" : "border-neutral-800",
                    )}
                  >
                    <p className="text-[10px] uppercase text-neutral-500">{m.label}</p>
                    <p className="font-mono text-sm font-semibold">{m.value}</p>
                  </div>
                ))}
              </div>
            </AdminPanel>
          </AdminSection>

          <AdminSection isLight={isLight} title="Incidents" subtitle="Rule-derived signals from live platform telemetry">
            {incidents.length === 0 ? (
              <EmptyState
                variant={isLight ? "light" : "dark"}
                title="No incidents"
                description="Platform telemetry shows no open operational incidents."
              />
            ) : (
              <IncidentFeed snapshot={snapshot} />
            )}
          </AdminSection>
        </>
      ) : null}
    </AdminPageShell>
  );
}
