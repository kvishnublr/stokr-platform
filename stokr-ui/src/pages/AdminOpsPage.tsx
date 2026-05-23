import { useQuery } from "@tanstack/react-query";
import { ChevronDown, ChevronRight } from "lucide-react";
import { useState } from "react";
import { api } from "../api/client";
import { AdminOperationsCockpit } from "../components/admin/cockpit/AdminOperationsCockpit";
import { ExecutionModeSelector } from "../components/admin/ExecutionModeSelector";
import { ReplayControlsPanel } from "../components/admin/ReplayControlsPanel";
import { MarketDataCoverageMonitor } from "../components/admin/MarketDataCoverageMonitor";
import { ExecutionStatsPanel } from "../components/admin/ExecutionStatsPanel";
import { ADMIN_OPS_SNAPSHOT_KEY } from "../lib/adminQueryKeys";
import { fetchAdminOpsSnapshotMerged } from "../lib/fetchAdminOpsSnapshotMerged";
import { cn } from "../lib/utils";

type ReadinessSnapshot = {
  checks: Record<string, { ok: boolean; detail: string }>;
  blocking: boolean;
};

export function AdminOpsPage() {
  const [readinessOpen, setReadinessOpen] = useState(true);

  const snapshot = useQuery({
    queryKey: ADMIN_OPS_SNAPSHOT_KEY,
    queryFn: fetchAdminOpsSnapshotMerged,
    staleTime: 1500,
    retry: 2,
  });

  const readiness = useQuery({
    queryKey: ["admin-readiness"],
    queryFn: async () => {
      const res = await api.get("/api/admin/readiness");
      return res.data?.data as ReadinessSnapshot;
    },
    refetchInterval: 30_000,
    retry: 1,
  });

  const r = readiness.data;

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-6 text-foreground overflow-y-auto">
      <div>
        <h1 className="text-3xl font-bold bg-gradient-to-r from-blue-600 to-blue-800 dark:from-blue-400 dark:to-blue-600 bg-clip-text text-transparent mb-2">
          Operations Control Center
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Unified execution framework management · Mode switching · Replay controls · Real-time monitoring
        </p>
      </div>

      {/* Readiness Status */}
      <section
        className={cn(
          "overflow-hidden rounded-xl border bg-card text-card-foreground shadow-sm",
          r?.blocking ? "border-red-500/50" : "border-border",
        )}
      >
        <button
          type="button"
          className="flex w-full items-center justify-between gap-2 border-b border-border px-4 py-2.5 text-left text-sm font-medium hover:bg-background/50"
          onClick={() => setReadinessOpen((o) => !o)}
        >
          <span>Live-trading readiness</span>
          {readinessOpen ? <ChevronDown className="h-4 w-4 shrink-0" /> : <ChevronRight className="h-4 w-4 shrink-0" />}
        </button>
        {readinessOpen ? (
          <div className="px-4 py-3">
            {readiness.isLoading ? (
              <div className="text-sm text-muted-foreground">Loading readiness...</div>
            ) : readiness.isError ? (
              <div className="text-sm text-red-600 dark:text-red-400">Could not load readiness (admin only).</div>
            ) : (
              <ul className="space-y-2 text-sm">
                {r
                  ? Object.entries(r.checks).map(([k, v]) => (
                      <li key={k} className="flex flex-wrap justify-between gap-2 border-b border-border pb-2 last:border-0">
                        <span className="font-mono text-xs text-muted-foreground">{k}</span>
                        <span className={v.ok ? "text-emerald-600 dark:text-emerald-400" : "text-amber-700 dark:text-amber-300"}>
                          {v.detail}
                        </span>
                      </li>
                    ))
                  : null}
              </ul>
            )}
          </div>
        ) : null}
      </section>

      {/* Main Operations Cockpit */}
      <AdminOperationsCockpit snapshot={snapshot.data} isFetching={snapshot.isFetching} />

      {/* Execution Framework Components */}
      <div className="space-y-6">
        <h2 className="text-2xl font-bold text-foreground mt-4">Execution Framework</h2>

        {/* Row 1: Execution Mode + Replay Controls */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <div className="animate-slide-up" style={{ animationDelay: '0ms' }}>
            <ExecutionModeSelector />
          </div>
          <div className="animate-slide-up" style={{ animationDelay: '100ms' }}>
            <ReplayControlsPanel />
          </div>
        </div>

        {/* Row 2: Market Data Coverage + Execution Stats */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <div className="animate-slide-up" style={{ animationDelay: '200ms' }}>
            <MarketDataCoverageMonitor />
          </div>
          <div className="animate-slide-up" style={{ animationDelay: '300ms' }}>
            <ExecutionStatsPanel />
          </div>
        </div>
      </div>
    </div>
  );
}
