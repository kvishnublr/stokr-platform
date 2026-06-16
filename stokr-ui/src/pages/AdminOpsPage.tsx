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
    <div className="flex min-h-0 flex-1 flex-col gap-8 text-foreground overflow-y-auto bg-white">
      {/* Professional Header */}
      <div className="sticky top-0 z-20 bg-white border-b border-blue-100 px-6 pt-6 pb-4 animate-fade-in">
        <div className="flex items-center gap-3 mb-2">
          <div className="bg-blue-50 rounded-xl p-3 border border-blue-200">
            <Zap className="w-6 h-6 text-blue-600" />
          </div>
          <h1 className="text-4xl font-bold text-blue-900">
            Operations Control
          </h1>
        </div>
        <p className="text-sm text-slate-600 ml-12">Unified execution framework management · Mode switching · Real-time monitoring</p>
      </div>

      <div className="px-6 pb-6 space-y-6 min-h-0">
        {/* Quick Status Indicators */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          {[
            { icon: Zap, label: "Mode", value: "LIVE", color: "blue" },
            { icon: Activity, label: "Broker", value: "Connected", color: "green" },
            { icon: Gauge, label: "Market", value: "09:15 IST", color: "purple" },
            { icon: TrendingUp, label: "Positions", value: "42", color: "orange" },
          ].map((stat, i) => {
            const Icon = stat.icon;
            const colorClasses = {
              blue: { bg: 'bg-blue-50', border: 'border-blue-200', icon: 'text-blue-600', text: 'text-blue-900', pulse: 'bg-blue-400' },
              green: { bg: 'bg-green-50', border: 'border-green-200', icon: 'text-green-600', text: 'text-green-900', pulse: 'bg-green-400' },
              purple: { bg: 'bg-purple-50', border: 'border-purple-200', icon: 'text-purple-600', text: 'text-purple-900', pulse: 'bg-purple-400' },
              orange: { bg: 'bg-orange-50', border: 'border-orange-200', icon: 'text-orange-600', text: 'text-orange-900', pulse: 'bg-orange-400' },
            };
            const colors = colorClasses[stat.color as keyof typeof colorClasses];
            return (
              <div
                key={i}
                className={`${colors.bg} border ${colors.border} rounded-xl p-4 space-y-3 hover:shadow-md transition-all duration-300 animate-slide-up`}
                style={{ animationDelay: `${i * 50}ms` }}
              >
                <div className="flex items-center justify-between">
                  <Icon className={`w-5 h-5 ${colors.icon}`} />
                  <div className={`w-2 h-2 rounded-full ${colors.pulse} animate-pulse`}></div>
                </div>
                <div>
                  <p className="text-xs font-semibold text-slate-600 uppercase tracking-wider">{stat.label}</p>
                  <p className={`text-2xl font-bold ${colors.text} mt-1`}>{stat.value}</p>
                </div>
              </div>
            );
          })}
        </div>

        {/* Tabbed Interface */}
        <div className="rounded-2xl border border-slate-200 bg-white overflow-hidden animate-slide-up shadow-sm" style={{ animationDelay: '100ms' }}>
          {/* Tab Headers */}
          <div className="flex border-b border-slate-200 bg-slate-50">
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
                    'flex-1 px-6 py-4 font-semibold text-sm flex items-center justify-center gap-2 transition-all duration-300 border-b-2',
                    isActive
                      ? 'bg-blue-50 text-blue-900 border-b-blue-600'
                      : 'text-slate-600 border-b-transparent hover:bg-slate-100 hover:text-slate-800'
                  )}
                >
                  <Icon className="w-4 h-4" />
                  {tab.label}
                  {tab.count && <span className="ml-1 text-xs bg-slate-200 text-slate-700 px-2 py-0.5 rounded-full">{tab.count}</span>}
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
                  <div className="text-center py-8 text-slate-500 animate-pulse">Loading checks...</div>
                ) : readiness.isError ? (
                  <div className="text-center py-8 text-red-600">Could not load readiness</div>
                ) : (
                  <div className="grid gap-3">
                    {readinessChecks.map(([k, v]) => (
                      <div
                        key={k}
                        className={cn(
                          'flex items-center justify-between p-4 rounded-xl border transition-all duration-300 hover:shadow-md',
                          v.ok
                            ? 'border-green-200 bg-green-50 hover:border-green-300'
                            : 'border-amber-200 bg-amber-50 hover:border-amber-300'
                        )}
                      >
                        <div className="flex items-center gap-3 flex-1">
                          {v.ok ? (
                            <CheckCircle2 className="w-5 h-5 text-green-600 flex-shrink-0" />
                          ) : (
                            <AlertCircle className="w-5 h-5 text-amber-600 flex-shrink-0" />
                          )}
                          <span className={cn('text-sm font-medium', v.ok ? 'text-green-900' : 'text-amber-900')}>{k.replace(/_/g, ' ')}</span>
                        </div>
                        <span className={cn('text-xs font-semibold whitespace-nowrap', v.ok ? 'text-green-700' : 'text-amber-700')}>
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
