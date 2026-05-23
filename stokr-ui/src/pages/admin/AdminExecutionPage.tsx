import { useQuery } from "@tanstack/react-query";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router-dom";
import { Shield, AlertCircle, CheckCircle2, Clock, TrendingUp, Zap } from "lucide-react";
import {
  ExecutionTimelinePanel,
  OMSLatencyMonitor,
} from "../../components/admin/cockpit/AdminCockpitPanels";
import { api } from "../../api/client";
import { ADMIN_OPS_SNAPSHOT_KEY } from "../../lib/adminQueryKeys";
import { fetchAdminOpsSnapshotMerged } from "../../lib/fetchAdminOpsSnapshotMerged";
import { BROKER_STATUS_QUERY_KEY, fetchBrokerStatus } from "../../api/broker";
import { fmtDateTime } from "../../lib/dateUtils";

const SCOPE_TYPES = ["STRATEGY", "SYMBOL", "SESSION", "REGIME"] as const;
const GUARD_MODES = ["ENTRY_STRICT", "EXIT_SAFE"] as const;

const SCOPE_HINTS: Record<string, string> = {
  STRATEGY: "Apply rule to all orders from one strategy (e.g. STOKR_BREAKOUT_COMMODITIES_RSI)",
  SYMBOL: "Apply rule to a specific instrument (e.g. GOLD26MAYFUT)",
  SESSION: "Apply rule to a trading session window (e.g. MCX_MORNING)",
  REGIME: "Apply rule when a market regime is active (e.g. HIGH_VOLATILITY)",
};

const GUARD_MODE_HINTS: Record<string, string> = {
  ENTRY_STRICT: "Block new entries if drift or age threshold exceeded",
  EXIT_SAFE: "Allow exits even when entry guard is triggered — prevents stuck positions",
};

export function AdminExecutionPage() {
  const qc = useQueryClient();
  const [scopeType, setScopeType] = useState<string>("STRATEGY");
  const [scopeKey, setScopeKey] = useState("STOKR_BREAKOUT_COMMODITIES_RSI");
  const [guardMode, setGuardMode] = useState<string>("ENTRY_STRICT");
  const [maxDriftPct, setMaxDriftPct] = useState("0.10");
  const [maxSignalAgeMs, setMaxSignalAgeMs] = useState("5000");

  const snapshot = useQuery({
    queryKey: ADMIN_OPS_SNAPSHOT_KEY,
    queryFn: fetchAdminOpsSnapshotMerged,
    staleTime: 60_000,
  });

  const brokerStatus = useQuery({
    queryKey: BROKER_STATUS_QUERY_KEY,
    queryFn: fetchBrokerStatus,
    staleTime: 30_000,
    refetchInterval: 60_000,
    retry: 1,
  });

  const policies = useQuery({
    queryKey: ["admin-execution-guard-policies"],
    queryFn: async () =>
      (await api.get("/api/admin/execution-guard/policies?limit=200")).data?.data as Array<Record<string, unknown>>,
    staleTime: 15_000,
    refetchInterval: 30_000,
  });

  const audits = useQuery({
    queryKey: ["admin-execution-guard-audit"],
    queryFn: async () =>
      (await api.get("/api/admin/execution-guard/policies/audit?limit=50")).data?.data as Array<Record<string, unknown>>,
    staleTime: 30_000,
    refetchInterval: 60_000,
  });

  const upsertPolicy = useMutation({
    mutationFn: async () =>
      (
        await api.post("/api/admin/execution-guard/policies", {
          scopeType,
          scopeKey,
          guardMode,
          patch: {
            maxDriftPct: Number(maxDriftPct),
            maxSignalAgeMs: Number(maxSignalAgeMs),
          },
        })
      ).data?.data,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["admin-execution-guard-policies"] });
      void qc.invalidateQueries({ queryKey: ["admin-execution-guard-audit"] });
    },
  });

  const reloadPolicies = useMutation({
    mutationFn: async () =>
      (await api.post("/api/admin/execution-guard/policies/reload")).data?.data,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["admin-execution-guard-policies"] });
    },
  });

  const bs = brokerStatus.data;
  const tokenExpired = bs?.connected && !bs?.tokenValid;
  const notConnected = bs && !bs.connected;
  const activePolicies = policies.data?.length ?? 0;
  const recentAudits = audits.data?.length ?? 0;

  return (
    <div className="space-y-6 text-foreground overflow-y-auto bg-white">
      {/* Header */}
      <div className="sticky top-0 z-20 bg-white border-b border-blue-100 px-6 pt-6 pb-4 animate-fade-in">
        <div className="flex items-center gap-3 mb-2">
          <div className="bg-blue-50 rounded-xl p-3 border border-blue-200">
            <Shield className="w-6 h-6 text-blue-600" />
          </div>
          <h1 className="text-4xl font-bold text-blue-900">Execution Guard</h1>
        </div>
        <p className="text-sm text-slate-600 ml-12">Policy management · Guard modes · Safety controls · Audit trails</p>
      </div>

      <div className="px-6 pb-6 space-y-6 min-h-0">
        {/* Status Cards Grid */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 animate-slide-up">
          <div className="bg-blue-50 border border-blue-200 rounded-xl p-4 space-y-3 hover:shadow-md transition-all duration-300">
            <div className="flex items-center justify-between">
              <Shield className="w-5 h-5 text-blue-600" />
              <div className="w-2 h-2 rounded-full bg-blue-400 animate-pulse"></div>
            </div>
            <div>
              <p className="text-xs font-semibold text-slate-600 uppercase tracking-wider">Active Policies</p>
              <p className="text-2xl font-bold text-blue-900 mt-1">{activePolicies}</p>
            </div>
          </div>

          <div className="bg-green-50 border border-green-200 rounded-xl p-4 space-y-3 hover:shadow-md transition-all duration-300">
            <div className="flex items-center justify-between">
              <CheckCircle2 className="w-5 h-5 text-green-600" />
              <div className="w-2 h-2 rounded-full bg-green-400 animate-pulse"></div>
            </div>
            <div>
              <p className="text-xs font-semibold text-slate-600 uppercase tracking-wider">Broker Status</p>
              <p className={`text-2xl font-bold mt-1 ${bs?.connected ? 'text-green-900' : 'text-red-900'}`}>
                {bs?.connected ? "Connected" : "Offline"}
              </p>
            </div>
          </div>

          <div className="bg-purple-50 border border-purple-200 rounded-xl p-4 space-y-3 hover:shadow-md transition-all duration-300">
            <div className="flex items-center justify-between">
              <Clock className="w-5 h-5 text-purple-600" />
              <div className="w-2 h-2 rounded-full bg-purple-400 animate-pulse"></div>
            </div>
            <div>
              <p className="text-xs font-semibold text-slate-600 uppercase tracking-wider">Token Valid</p>
              <p className={`text-2xl font-bold mt-1 ${bs?.tokenValid ? 'text-purple-900' : 'text-amber-900'}`}>
                {bs?.tokenValid ? "Valid" : "Expired"}
              </p>
            </div>
          </div>

          <div className="bg-orange-50 border border-orange-200 rounded-xl p-4 space-y-3 hover:shadow-md transition-all duration-300">
            <div className="flex items-center justify-between">
              <TrendingUp className="w-5 h-5 text-orange-600" />
              <div className="w-2 h-2 rounded-full bg-orange-400 animate-pulse"></div>
            </div>
            <div>
              <p className="text-xs font-semibold text-slate-600 uppercase tracking-wider">Audit Events</p>
              <p className="text-2xl font-bold text-orange-900 mt-1">{recentAudits}</p>
            </div>
          </div>
        </div>

        {/* Broker Alert Banner */}
        {(tokenExpired || notConnected) && (
          <div className="animate-scale-in rounded-xl border-l-4 border-l-red-500 bg-red-50 p-4 flex items-start gap-4">
            <AlertCircle className="w-5 h-5 text-red-600 flex-shrink-0 mt-0.5" />
            <div className="flex-1">
              <p className="font-semibold text-red-900 mb-1">
                {tokenExpired ? "⚠️ Zerodha Token Expired" : "⚠️ Broker Disconnected"}
              </p>
              <p className="text-sm text-red-700 mb-3">
                {tokenExpired
                  ? "All live orders will be REJECTED until you reconnect. Reconnect before market open (09:15 IST)."
                  : "No broker session linked — live execution is offline. Connect Zerodha to enable trading."}
              </p>
              <Link
                to="/brokers"
                className="inline-flex px-4 py-2 bg-red-600 hover:bg-red-700 text-white text-sm font-semibold rounded-lg transition-colors"
              >
                {tokenExpired ? "Reconnect Zerodha" : "Connect Zerodha"} →
              </Link>
            </div>
          </div>
        )}

        {/* OMS Latency Monitor */}
        <OMSLatencyMonitor snapshot={snapshot.data} />

        {/* Policy Management Section */}
        <div className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm animate-slide-up">
          <div className="flex items-center gap-3 mb-4">
            <div className="h-8 w-1 bg-gradient-to-b from-blue-500 to-purple-500 rounded-full"></div>
            <h2 className="text-2xl font-bold">Guard Policy Manager</h2>
          </div>

          {/* Policy Form */}
          <div className="bg-gradient-to-r from-blue-50 to-purple-50 rounded-xl p-4 mb-6 border border-blue-200">
            <h3 className="text-sm font-bold text-slate-900 mb-4 flex items-center gap-2">
              <Zap className="w-4 h-4 text-blue-600" />
              Create New Policy
            </h3>
            <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
              <div className="space-y-2">
                <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider">Scope Type</label>
                <select
                  value={scopeType}
                  onChange={(e) => setScopeType(e.target.value)}
                  className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2.5 text-sm text-slate-900 hover:border-blue-300 focus:outline-none focus:ring-2 focus:ring-blue-500/30"
                >
                  {SCOPE_TYPES.map((s) => (
                    <option key={s} value={s}>{s}</option>
                  ))}
                </select>
                <p className="text-xs text-slate-500">{SCOPE_HINTS[scopeType]}</p>
              </div>

              <div className="space-y-2">
                <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider">Scope Key</label>
                <input
                  value={scopeKey}
                  onChange={(e) => setScopeKey(e.target.value)}
                  placeholder="e.g. STOKR_BREAKOUT_COMMODITIES_RSI"
                  className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2.5 text-sm text-slate-900 placeholder-slate-400 hover:border-blue-300 focus:outline-none focus:ring-2 focus:ring-blue-500/30"
                />
                <p className="text-xs text-slate-500">Exact strategy key, symbol, or session name</p>
              </div>

              <div className="space-y-2">
                <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider">Guard Mode</label>
                <select
                  value={guardMode}
                  onChange={(e) => setGuardMode(e.target.value)}
                  className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2.5 text-sm text-slate-900 hover:border-blue-300 focus:outline-none focus:ring-2 focus:ring-blue-500/30"
                >
                  {GUARD_MODES.map((g) => (
                    <option key={g} value={g}>{g}</option>
                  ))}
                </select>
                <p className="text-xs text-slate-500">{GUARD_MODE_HINTS[guardMode]}</p>
              </div>

              <div className="space-y-2">
                <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider">Max Drift %</label>
                <input
                  value={maxDriftPct}
                  onChange={(e) => setMaxDriftPct(e.target.value)}
                  placeholder="0.10"
                  className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 font-mono placeholder-slate-400 hover:border-blue-300 focus:outline-none focus:ring-2 focus:ring-blue-500/30"
                />
                <p className="text-xs text-slate-500">Block if drift &gt; this % from signal price</p>
              </div>

              <div className="space-y-2">
                <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider">Max Signal Age (ms)</label>
                <input
                  value={maxSignalAgeMs}
                  onChange={(e) => setMaxSignalAgeMs(e.target.value)}
                  placeholder="5000"
                  className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 font-mono placeholder-slate-400 hover:border-blue-300 focus:outline-none focus:ring-2 focus:ring-blue-500/30"
                />
                <p className="text-xs text-slate-500">Block if signal older than this (ms)</p>
              </div>

              <div className="flex items-end gap-2">
                <button
                  type="button"
                  onClick={() => upsertPolicy.mutate()}
                  disabled={upsertPolicy.isPending}
                  className="flex-1 rounded-lg bg-blue-600 hover:bg-blue-700 px-4 py-2.5 text-sm font-semibold text-white transition-colors disabled:opacity-50"
                >
                  {upsertPolicy.isPending ? "Saving..." : "Save Policy"}
                </button>
                <button
                  type="button"
                  onClick={() => reloadPolicies.mutate()}
                  disabled={reloadPolicies.isPending}
                  className="rounded-lg border border-slate-300 bg-white hover:bg-slate-50 px-4 py-2.5 text-sm font-semibold text-slate-900 transition-colors disabled:opacity-50"
                >
                  {reloadPolicies.isPending ? "Reloading..." : "Reload Cache"}
                </button>
              </div>
            </div>
            {upsertPolicy.isSuccess && (
              <p className="mt-3 text-sm text-green-700 flex items-center gap-2">
                <CheckCircle2 className="w-4 h-4" /> Policy saved successfully
              </p>
            )}
            {upsertPolicy.isError && (
              <p className="mt-3 text-sm text-red-700 flex items-center gap-2">
                <AlertCircle className="w-4 h-4" /> Failed to save policy
              </p>
            )}
          </div>

          {/* Active Policies Table */}
          <div className="space-y-3 mb-6">
            <div className="flex items-center justify-between">
              <h3 className="font-semibold text-slate-900">Active Policies ({activePolicies})</h3>
              {policies.isFetching && <span className="text-xs text-slate-500 animate-pulse">Updating...</span>}
            </div>
            <div className="overflow-x-auto rounded-lg border border-slate-200">
              <table className="w-full text-sm">
                <thead className="bg-slate-50 border-b border-slate-200">
                  <tr>
                    <th className="px-4 py-3 text-left text-xs font-semibold text-slate-700">Scope Type</th>
                    <th className="px-4 py-3 text-left text-xs font-semibold text-slate-700">Scope Key</th>
                    <th className="px-4 py-3 text-left text-xs font-semibold text-slate-700">Guard Mode</th>
                    <th className="px-4 py-3 text-left text-xs font-semibold text-slate-700">Max Drift</th>
                    <th className="px-4 py-3 text-left text-xs font-semibold text-slate-700">Max Age (ms)</th>
                    <th className="px-4 py-3 text-left text-xs font-semibold text-slate-700">Updated</th>
                  </tr>
                </thead>
                <tbody>
                  {(policies.data ?? []).length === 0 ? (
                    <tr>
                      <td colSpan={6} className="px-4 py-6 text-center text-slate-500">
                        No policies configured. Create one above.
                      </td>
                    </tr>
                  ) : (
                    (policies.data ?? []).map((p, i) => (
                      <tr key={String(p.id ?? i)} className="border-b border-slate-200 hover:bg-blue-50 transition-colors">
                        <td className="px-4 py-3 font-mono text-slate-900 text-xs">{String(p.scopeType ?? "-")}</td>
                        <td className="px-4 py-3 font-mono text-slate-600 text-xs">{String(p.scopeKey ?? "-")}</td>
                        <td className="px-4 py-3">
                          <span
                            className={`inline-flex rounded-full px-2 py-1 text-xs font-semibold ${
                              String(p.guardMode) === "ENTRY_STRICT"
                                ? "bg-amber-100 text-amber-800"
                                : "bg-blue-100 text-blue-800"
                            }`}
                          >
                            {String(p.guardMode ?? "-")}
                          </span>
                        </td>
                        <td className="px-4 py-3 font-mono text-slate-600 text-xs">{String(p.maxDriftPct ?? "-")}%</td>
                        <td className="px-4 py-3 font-mono text-slate-600 text-xs">{String(p.maxSignalAgeMs ?? "-")}</td>
                        <td className="px-4 py-3 font-mono text-slate-500 text-xs">
                          {p.updatedAt ? fmtDateTime(String(p.updatedAt)) : "-"}
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
          </div>

          {/* Audit Log */}
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <h3 className="font-semibold text-slate-900">Audit Log ({recentAudits})</h3>
              {audits.isFetching && <span className="text-xs text-slate-500 animate-pulse">Updating...</span>}
            </div>
            <div className="overflow-x-auto rounded-lg border border-slate-200">
              <table className="w-full text-sm">
                <thead className="bg-slate-50 border-b border-slate-200">
                  <tr>
                    <th className="px-4 py-3 text-left text-xs font-semibold text-slate-700">Time (IST)</th>
                    <th className="px-4 py-3 text-left text-xs font-semibold text-slate-700">Action</th>
                    <th className="px-4 py-3 text-left text-xs font-semibold text-slate-700">Scope Type</th>
                    <th className="px-4 py-3 text-left text-xs font-semibold text-slate-700">Scope Key</th>
                    <th className="px-4 py-3 text-left text-xs font-semibold text-slate-700">Guard Mode</th>
                    <th className="px-4 py-3 text-left text-xs font-semibold text-slate-700">Notes</th>
                  </tr>
                </thead>
                <tbody>
                  {(audits.data ?? []).length === 0 ? (
                    <tr>
                      <td colSpan={6} className="px-4 py-6 text-center text-slate-500">
                        No audit events recorded yet.
                      </td>
                    </tr>
                  ) : (
                    (audits.data ?? []).map((a, i) => (
                      <tr key={String(a.id ?? i)} className="border-b border-slate-200 hover:bg-green-50 transition-colors">
                        <td className="px-4 py-3 font-mono text-slate-600 text-xs">
                          {a.createdAt ? fmtDateTime(String(a.createdAt)) : "-"}
                        </td>
                        <td className="px-4 py-3 font-semibold text-slate-900 text-xs">{String(a.action ?? "-")}</td>
                        <td className="px-4 py-3 font-mono text-slate-600 text-xs">{String(a.scopeType ?? "-")}</td>
                        <td className="px-4 py-3 font-mono text-slate-600 text-xs">{String(a.scopeKey ?? "-")}</td>
                        <td className="px-4 py-3 font-mono text-slate-600 text-xs">{String(a.guardMode ?? "-")}</td>
                        <td className="px-4 py-3 text-slate-600 text-xs">{String(a.notes ?? "-")}</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </div>

        {/* Execution Timeline */}
        <ExecutionTimelinePanel />
      </div>
    </div>
  );
}
