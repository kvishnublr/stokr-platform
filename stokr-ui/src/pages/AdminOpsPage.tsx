import { useQuery } from "@tanstack/react-query";
import { ChevronDown, ChevronRight, Zap, Activity, TrendingUp, Gauge } from "lucide-react";
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
    <div className="flex min-h-0 flex-1 flex-col gap-8 text-foreground overflow-y-auto bg-gradient-to-b from-slate-900 via-slate-900 to-slate-950 dark:from-neutral-950 dark:via-neutral-950 dark:to-neutral-900">
      {/* Premium Header */}
      <div className="sticky top-0 z-10 backdrop-blur-xl bg-gradient-to-b from-slate-900/80 to-slate-900/20 border-b border-blue-500/10 px-6 pt-6 pb-4 animate-fade-in">
        <div className="flex items-start justify-between gap-4 mb-4">
          <div className="flex-1">
            <div className="flex items-center gap-3 mb-3">
              <div className="relative">
                <div className="absolute inset-0 bg-gradient-to-r from-blue-500 to-purple-500 rounded-xl blur-lg opacity-50 animate-glow-pulse"></div>
                <div className="relative bg-gradient-to-br from-blue-600 to-purple-600 rounded-xl p-3">
                  <Zap className="w-6 h-6 text-white" />
                </div>
              </div>
              <div>
                <h1 className="text-4xl font-black bg-gradient-to-r from-blue-400 via-purple-400 to-pink-400 bg-clip-text text-transparent animate-neon-glow">
                  Operations Control
                </h1>
                <p className="text-xs text-blue-300/70 font-semibold uppercase tracking-widest mt-1">Unified Execution Framework</p>
              </div>
            </div>
            <p className="text-sm text-slate-400 leading-relaxed">
              Real-time mode switching · Historical replay controls · Market data monitoring · Execution analytics
            </p>
          </div>
        </div>
      </div>

      <div className="px-6 pb-6 space-y-8 min-h-0">
        {/* Quick Status Indicators */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          {[
            { icon: Zap, label: "System Mode", value: "LIVE", color: "from-blue-500 to-blue-600" },
            { icon: Activity, label: "Broker Status", value: "Connected", color: "from-green-500 to-green-600" },
            { icon: Gauge, label: "Market Time", value: "09:15 IST", color: "from-purple-500 to-purple-600" },
            { icon: TrendingUp, label: "Active Positions", value: "42", color: "from-orange-500 to-orange-600" },
          ].map((stat, i) => {
            const Icon = stat.icon;
            return (
              <div
                key={i}
                className="group relative overflow-hidden rounded-2xl border border-slate-700/50 backdrop-blur-sm hover-lift animate-slide-up"
                style={{ animationDelay: `${i * 50}ms` }}
              >
                <div className={`absolute inset-0 bg-gradient-to-br ${stat.color} opacity-5 group-hover:opacity-10 transition-opacity`}></div>
                <div className="relative p-4 space-y-2">
                  <div className="flex items-center justify-between">
                    <Icon className={`w-5 h-5 text-${stat.color.split('-')[1]}-400`} />
                    <div className="w-2 h-2 rounded-full bg-green-500 animate-pulse"></div>
                  </div>
                  <p className="text-xs font-semibold text-slate-400 uppercase tracking-wider">{stat.label}</p>
                  <p className="text-2xl font-bold text-white">{stat.value}</p>
                </div>
              </div>
            );
          })}
        </div>

        {/* Readiness Status */}
        <section
          className={cn(
            "overflow-hidden rounded-2xl border backdrop-blur-sm transition-all duration-300",
            r?.blocking
              ? "border-red-500/30 bg-red-500/5"
              : "border-slate-700/50 bg-slate-800/30 hover:border-blue-500/30 hover:bg-blue-500/5",
          )}
        >
          <button
            type="button"
            className="flex w-full items-center justify-between gap-2 px-6 py-4 text-left font-semibold hover:bg-white/5 transition-colors"
            onClick={() => setReadinessOpen((o) => !o)}
          >
            <span className="text-lg">Live-trading Readiness</span>
            <div className="transition-transform duration-300" style={{ transform: readinessOpen ? 'rotate(0)' : 'rotate(-90deg)' }}>
              <ChevronDown className="h-5 w-5" />
            </div>
          </button>
          {readinessOpen && (
            <div className="border-t border-slate-700/30 px-6 py-4 space-y-3">
              {readiness.isLoading ? (
                <div className="text-sm text-slate-400 animate-pulse">Loading readiness checks...</div>
              ) : readiness.isError ? (
                <div className="text-sm text-red-400">Could not load readiness (admin only).</div>
              ) : (
                <ul className="space-y-2">
                  {r
                    ? Object.entries(r.checks).map(([k, v]) => (
                        <li key={k} className="flex items-center justify-between gap-3 text-sm hover:bg-white/5 px-3 py-2 rounded-lg transition-colors">
                          <span className="font-mono text-xs text-slate-400">{k}</span>
                          <div className="flex items-center gap-2">
                            <div className={`w-2 h-2 rounded-full ${v.ok ? 'bg-green-500' : 'bg-amber-500'} animate-pulse`}></div>
                            <span className={v.ok ? "text-green-400 font-medium" : "text-amber-400 font-medium"}>
                              {v.detail}
                            </span>
                          </div>
                        </li>
                      ))
                    : null}
                </ul>
              )}
            </div>
          )}
        </section>

        {/* Main Operations Cockpit */}
        <div className="animate-slide-up" style={{ animationDelay: '150ms' }}>
          <AdminOperationsCockpit snapshot={snapshot.data} isFetching={snapshot.isFetching} />
        </div>

        {/* Execution Framework Section */}
        <div className="space-y-6">
          <div className="flex items-center gap-3">
            <div className="h-8 w-1 bg-gradient-to-b from-blue-500 to-purple-500 rounded-full"></div>
            <h2 className="text-3xl font-bold">Execution Framework</h2>
          </div>

          {/* Row 1: Execution Mode + Replay Controls */}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <div className="animate-slide-up hover-lift group" style={{ animationDelay: '200ms' }}>
              <div className="absolute -inset-0.5 bg-gradient-to-r from-blue-500 to-purple-500 rounded-2xl blur opacity-0 group-hover:opacity-20 transition duration-500"></div>
              <div className="relative">
                <ExecutionModeSelector />
              </div>
            </div>
            <div className="animate-slide-up hover-lift group" style={{ animationDelay: '250ms' }}>
              <div className="absolute -inset-0.5 bg-gradient-to-r from-purple-500 to-pink-500 rounded-2xl blur opacity-0 group-hover:opacity-20 transition duration-500"></div>
              <div className="relative">
                <ReplayControlsPanel />
              </div>
            </div>
          </div>

          {/* Row 2: Market Data Coverage + Execution Stats */}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <div className="animate-slide-up hover-lift group" style={{ animationDelay: '300ms' }}>
              <div className="absolute -inset-0.5 bg-gradient-to-r from-cyan-500 to-blue-500 rounded-2xl blur opacity-0 group-hover:opacity-20 transition duration-500"></div>
              <div className="relative">
                <MarketDataCoverageMonitor />
              </div>
            </div>
            <div className="animate-slide-up hover-lift group" style={{ animationDelay: '350ms' }}>
              <div className="absolute -inset-0.5 bg-gradient-to-r from-orange-500 to-red-500 rounded-2xl blur opacity-0 group-hover:opacity-20 transition duration-500"></div>
              <div className="relative">
                <ExecutionStatsPanel />
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
