import { useQuery } from "@tanstack/react-query";
import { ChevronDown, Zap, Activity, TrendingUp, Gauge, CheckCircle2, AlertCircle, Database, Radio } from "lucide-react";
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
  const [activeTab, setActiveTab] = useState<'readiness' | 'market' | 'cockpit'>('readiness');

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

  const readinessChecks = r ? Object.entries(r.checks) : [];
  const okCount = readinessChecks.filter(([, v]) => v.ok).length;
  const totalCount = readinessChecks.length;

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-8 text-foreground overflow-y-auto bg-gradient-to-b from-slate-900 via-slate-900 to-slate-950">
      {/* Premium Header */}
      <div className="sticky top-0 z-20 backdrop-blur-xl bg-gradient-to-b from-slate-900/80 to-slate-900/20 border-b border-blue-500/10 px-6 pt-6 pb-4 animate-fade-in">
        <div className="flex items-center gap-3 mb-2">
          <div className="relative">
            <div className="absolute inset-0 bg-gradient-to-r from-blue-500 to-purple-500 rounded-xl blur-lg opacity-50 animate-glow-pulse"></div>
            <div className="relative bg-gradient-to-br from-blue-600 to-purple-600 rounded-xl p-3">
              <Zap className="w-6 h-6 text-white" />
            </div>
          </div>
          <h1 className="text-4xl font-black bg-gradient-to-r from-blue-400 via-purple-400 to-pink-400 bg-clip-text text-transparent animate-neon-glow">
            Operations Control
          </h1>
        </div>
      </div>

      <div className="px-6 pb-6 space-y-6 min-h-0">
        {/* Quick Status Indicators */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          {[
            { icon: Zap, label: "Mode", value: "LIVE", color: "from-blue-500 to-blue-600" },
            { icon: Activity, label: "Broker", value: "Connected", color: "from-green-500 to-green-600" },
            { icon: Gauge, label: "Market", value: "09:15 IST", color: "from-purple-500 to-purple-600" },
            { icon: TrendingUp, label: "Positions", value: "42", color: "from-orange-500 to-orange-600" },
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
                    <Icon className="w-5 h-5 text-blue-400" />
                    <div className="w-2 h-2 rounded-full bg-green-500 animate-pulse"></div>
                  </div>
                  <p className="text-xs font-semibold text-slate-400 uppercase tracking-wider">{stat.label}</p>
                  <p className="text-2xl font-bold text-white">{stat.value}</p>
                </div>
              </div>
            );
          })}
        </div>

        {/* Tabbed Interface */}
        <div className="rounded-2xl border border-slate-700/50 bg-slate-800/30 backdrop-blur-sm overflow-hidden animate-slide-up" style={{ animationDelay: '100ms' }}>
          {/* Tab Headers */}
          <div className="flex border-b border-slate-700/50 bg-slate-900/50">
            {[
              { id: 'readiness', label: 'Readiness', icon: CheckCircle2, count: `${okCount}/${totalCount}` },
              { id: 'cockpit', label: 'Cockpit', icon: Activity },
              { id: 'market', label: 'Market Data', icon: Database },
            ].map((tab) => {
              const Icon = tab.icon;
              const isActive = activeTab === tab.id;
              return (
                <button
                  key={tab.id}
                  onClick={() => setActiveTab(tab.id as any)}
                  className={cn(
                    'flex-1 px-6 py-4 font-semibold text-sm flex items-center justify-center gap-2 transition-all duration-300',
                    isActive
                      ? 'bg-gradient-to-r from-blue-500/20 to-purple-500/20 text-white border-b-2 border-blue-500'
                      : 'text-slate-400 hover:text-slate-300 hover:bg-white/5'
                  )}
                >
                  <Icon className="w-4 h-4" />
                  {tab.label}
                  {tab.count && <span className="ml-1 text-xs bg-slate-700/50 px-2 py-0.5 rounded-full">{tab.count}</span>}
                </button>
              );
            })}
          </div>

          {/* Tab Content */}
          <div className="p-6 space-y-4 min-h-64">
            {/* Readiness Tab */}
            {activeTab === 'readiness' && (
              <div className="space-y-3 animate-fade-in">
                {readiness.isLoading ? (
                  <div className="text-center py-8 text-slate-400 animate-pulse">Loading checks...</div>
                ) : readiness.isError ? (
                  <div className="text-center py-8 text-red-400">Could not load readiness</div>
                ) : (
                  <div className="grid gap-3">
                    {readinessChecks.map(([k, v]) => (
                      <div
                        key={k}
                        className={cn(
                          'flex items-center justify-between p-3 rounded-xl border transition-all duration-300 hover:shadow-lg',
                          v.ok
                            ? 'border-green-500/30 bg-green-500/10 hover:border-green-500/50'
                            : 'border-amber-500/30 bg-amber-500/10 hover:border-amber-500/50'
                        )}
                      >
                        <div className="flex items-center gap-3 flex-1">
                          {v.ok ? (
                            <CheckCircle2 className="w-5 h-5 text-green-400 flex-shrink-0" />
                          ) : (
                            <AlertCircle className="w-5 h-5 text-amber-400 flex-shrink-0" />
                          )}
                          <span className="text-sm font-medium text-slate-300 font-mono">{k.replace(/_/g, ' ')}</span>
                        </div>
                        <span className={cn('text-xs font-semibold whitespace-nowrap', v.ok ? 'text-green-300' : 'text-amber-300')}>
                          {v.detail}
                        </span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}

            {/* Cockpit Tab */}
            {activeTab === 'cockpit' && (
              <div className="animate-fade-in">
                <AdminOperationsCockpit snapshot={snapshot.data} isFetching={snapshot.isFetching} />
              </div>
            )}

            {/* Market Data Tab */}
            {activeTab === 'market' && (
              <div className="text-center py-8 text-slate-400 animate-fade-in">
                Market data monitoring coming soon
              </div>
            )}
          </div>
        </div>

        {/* Execution Framework Section */}
        <div className="space-y-6 pt-4">
          <div className="flex items-center gap-3">
            <div className="h-8 w-1 bg-gradient-to-b from-blue-500 to-purple-500 rounded-full"></div>
            <h2 className="text-3xl font-bold">Execution Framework</h2>
          </div>

          {/* Row 1 */}
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

          {/* Row 2 */}
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
