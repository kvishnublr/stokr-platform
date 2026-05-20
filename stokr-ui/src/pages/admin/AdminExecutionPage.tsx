import { useQuery } from "@tanstack/react-query";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router-dom";
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

  return (
    <div className="space-y-4 text-foreground">
      {/* Header */}
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">OMS / Execution</h1>
          <p className="mt-1 text-xs text-muted-foreground">
            Throughput, rejects, latency, execution guard policies, and per-order timelines.
          </p>
        </div>
        <Link
          to="/admin/oms"
          className="rounded-md border border-border bg-card px-3 py-1.5 font-mono text-[10px] font-semibold text-primary hover:bg-background/80"
        >
          Order grid →
        </Link>
      </div>

      {/* Broker auth banner */}
      {(tokenExpired || notConnected) && (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-amber-500/40 bg-amber-500/10 px-4 py-3">
          <div className="text-sm text-amber-200/95">
            {tokenExpired
              ? "Zerodha token expired — all live orders will be REJECTED until you reconnect."
              : "No broker session linked — live execution is offline."}
            {" "}Reconnect before market open (09:15 IST).
          </div>
          <Link
            to="/brokers"
            className="shrink-0 rounded-lg bg-amber-500/90 px-3 py-1.5 text-xs font-semibold text-neutral-950 hover:bg-amber-400"
          >
            {tokenExpired ? "Reconnect Zerodha" : "Connect Zerodha"}
          </Link>
        </div>
      )}

      {/* OMS latency monitor */}
      <OMSLatencyMonitor snapshot={snapshot.data} />

      {/* Execution Guard Policy Registry */}
      <div className="rounded-xl border border-border bg-card p-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 className="text-sm font-semibold">Execution Guard Policy Registry</h2>
            <p className="mt-0.5 text-xs text-muted-foreground">
              Per-scope rules that block or allow order routing. Guards run before every order is dispatched to the broker rail.
            </p>
          </div>
          <button
            type="button"
            onClick={() => reloadPolicies.mutate()}
            disabled={reloadPolicies.isPending}
            className="rounded-md border border-border px-2.5 py-1 text-[11px] font-semibold hover:bg-background/80 disabled:opacity-50"
          >
            {reloadPolicies.isPending ? "Reloading..." : "Reload policy cache"}
          </button>
        </div>

        {/* Policy form */}
        <div className="mt-4 rounded-lg border border-border bg-background/40 p-3">
          <div className="mb-2 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
            Add / update policy
          </div>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            <div>
              <label className="block text-[10px] font-medium text-muted-foreground">Scope type</label>
              <select
                value={scopeType}
                onChange={(e) => setScopeType(e.target.value)}
                className="mt-1 w-full rounded-md border border-border bg-background px-2 py-1.5 text-xs"
              >
                {SCOPE_TYPES.map((s) => (
                  <option key={s} value={s}>{s}</option>
                ))}
              </select>
              <p className="mt-1 text-[10px] text-muted-foreground">{SCOPE_HINTS[scopeType]}</p>
            </div>
            <div>
              <label className="block text-[10px] font-medium text-muted-foreground">Scope key</label>
              <input
                value={scopeKey}
                onChange={(e) => setScopeKey(e.target.value)}
                placeholder="e.g. STOKR_BREAKOUT_COMMODITIES_RSI"
                className="mt-1 w-full rounded-md border border-border bg-background px-2 py-1.5 text-xs"
              />
              <p className="mt-1 text-[10px] text-muted-foreground">Exact strategy key, symbol, session, or regime name</p>
            </div>
            <div>
              <label className="block text-[10px] font-medium text-muted-foreground">Guard mode</label>
              <select
                value={guardMode}
                onChange={(e) => setGuardMode(e.target.value)}
                className="mt-1 w-full rounded-md border border-border bg-background px-2 py-1.5 text-xs"
              >
                {GUARD_MODES.map((g) => (
                  <option key={g} value={g}>{g}</option>
                ))}
              </select>
              <p className="mt-1 text-[10px] text-muted-foreground">{GUARD_MODE_HINTS[guardMode]}</p>
            </div>
            <div>
              <label className="block text-[10px] font-medium text-muted-foreground">Max drift %</label>
              <input
                value={maxDriftPct}
                onChange={(e) => setMaxDriftPct(e.target.value)}
                placeholder="0.10"
                className="mt-1 w-full rounded-md border border-border bg-background px-2 py-1.5 text-xs font-mono"
              />
              <p className="mt-1 text-[10px] text-muted-foreground">
                Order blocked if market price drifted more than this % from signal price (0.10 = 0.10%)
              </p>
            </div>
            <div>
              <label className="block text-[10px] font-medium text-muted-foreground">Max signal age (ms)</label>
              <input
                value={maxSignalAgeMs}
                onChange={(e) => setMaxSignalAgeMs(e.target.value)}
                placeholder="5000"
                className="mt-1 w-full rounded-md border border-border bg-background px-2 py-1.5 text-xs font-mono"
              />
              <p className="mt-1 text-[10px] text-muted-foreground">
                Order blocked if signal is older than this (ms). 5000 = 5 sec. Set high for catalog-driven strategies.
              </p>
            </div>
          </div>
          <div className="mt-3 flex items-center gap-2">
            <button
              type="button"
              onClick={() => upsertPolicy.mutate()}
              disabled={upsertPolicy.isPending}
              className="rounded-md bg-primary px-4 py-1.5 text-xs font-semibold text-primary-foreground disabled:opacity-50"
            >
              {upsertPolicy.isPending ? "Saving..." : "Save override"}
            </button>
            {upsertPolicy.isSuccess && (
              <span className="text-[11px] text-emerald-400">Saved</span>
            )}
            {upsertPolicy.isError && (
              <span className="text-[11px] text-red-400">Save failed</span>
            )}
          </div>
        </div>

        {/* Active policies table */}
        <div className="mt-4">
          <div className="mb-1 flex items-center justify-between gap-2">
            <span className="text-xs font-semibold">Active policies ({(policies.data ?? []).length})</span>
            {policies.isFetching && <span className="text-[10px] text-muted-foreground">refreshing...</span>}
          </div>
          <div className="overflow-x-auto rounded-lg border border-border">
            <table className="w-full border-collapse text-left text-[11px]">
              <thead className="bg-card text-muted-foreground">
                <tr>
                  {["Scope type", "Scope key", "Guard mode", "Max drift %", "Max age ms", "Updated (IST)"].map((c) => (
                    <th key={c} className="border-b border-border px-3 py-2 font-semibold uppercase tracking-wide text-[10px]">
                      {c}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {(policies.data ?? []).length === 0 ? (
                  <tr>
                    <td colSpan={6} className="px-3 py-3 text-muted-foreground">
                      No guard policies configured. Add one above.
                    </td>
                  </tr>
                ) : (
                  (policies.data ?? []).map((p, i) => (
                    <tr key={String(p.id ?? i)} className="border-b border-border/60 hover:bg-background/40">
                      <td className="px-3 py-2 font-mono text-foreground">{String(p.scopeType ?? "-")}</td>
                      <td className="px-3 py-2 font-mono text-muted-foreground">{String(p.scopeKey ?? "-")}</td>
                      <td className="px-3 py-2 font-mono">
                        <span
                          className={`rounded px-1.5 py-0.5 text-[10px] font-semibold ${
                            String(p.guardMode) === "ENTRY_STRICT"
                              ? "bg-amber-500/15 text-amber-200"
                              : "bg-sky-500/15 text-sky-200"
                          }`}
                        >
                          {String(p.guardMode ?? "-")}
                        </span>
                      </td>
                      <td className="px-3 py-2 font-mono text-muted-foreground">{String(p.maxDriftPct ?? "-")}</td>
                      <td className="px-3 py-2 font-mono text-muted-foreground">{String(p.maxSignalAgeMs ?? "-")}</td>
                      <td className="px-3 py-2 font-mono text-muted-foreground text-[10px]">
                        {p.updatedAt ? fmtDateTime(String(p.updatedAt)) : "-"}
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>

        {/* Policy audit log */}
        <div className="mt-4">
          <div className="mb-1 flex items-center justify-between gap-2">
            <span className="text-xs font-semibold">Policy audit log</span>
            {audits.isFetching && <span className="text-[10px] text-muted-foreground">refreshing...</span>}
          </div>
          <div className="overflow-x-auto rounded-lg border border-border">
            <table className="w-full border-collapse text-left text-[11px]">
              <thead className="bg-card text-muted-foreground">
                <tr>
                  {["Time (IST)", "Action", "Scope type", "Scope key", "Guard mode", "Notes"].map((c) => (
                    <th key={c} className="border-b border-border px-3 py-2 font-semibold uppercase tracking-wide text-[10px]">
                      {c}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {(audits.data ?? []).length === 0 ? (
                  <tr>
                    <td colSpan={6} className="px-3 py-3 text-muted-foreground">
                      No policy changes recorded yet.
                    </td>
                  </tr>
                ) : (
                  (audits.data ?? []).map((a, i) => (
                    <tr key={String(a.id ?? i)} className="border-b border-border/60 hover:bg-background/40">
                      <td className="px-3 py-2 font-mono text-[10px] text-muted-foreground">
                        {a.createdAt ? fmtDateTime(String(a.createdAt)) : "-"}
                      </td>
                      <td className="px-3 py-2 font-mono font-semibold text-foreground">{String(a.action ?? "-")}</td>
                      <td className="px-3 py-2 font-mono text-muted-foreground">{String(a.scopeType ?? "-")}</td>
                      <td className="px-3 py-2 font-mono text-muted-foreground">{String(a.scopeKey ?? "-")}</td>
                      <td className="px-3 py-2 font-mono text-muted-foreground">{String(a.guardMode ?? "-")}</td>
                      <td className="px-3 py-2 font-mono text-muted-foreground">{String(a.notes ?? "-")}</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>
      </div>

      {/* Execution timeline */}
      <ExecutionTimelinePanel />
    </div>
  );
}
