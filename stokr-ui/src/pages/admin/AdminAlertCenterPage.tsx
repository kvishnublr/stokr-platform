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
