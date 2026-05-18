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

export function AdminExecutionPage() {
  const qc = useQueryClient();
  const [scopeType, setScopeType] = useState("STRATEGY");
  const [scopeKey, setScopeKey] = useState("OPENING_RANGE_BREAKOUT");
  const [guardMode, setGuardMode] = useState("ENTRY_STRICT");
  const [maxDriftPct, setMaxDriftPct] = useState("0.10");
  const [maxSignalAgeMs, setMaxSignalAgeMs] = useState("5000");

  const snapshot = useQuery({
    queryKey: ADMIN_OPS_SNAPSHOT_KEY,
    queryFn: fetchAdminOpsSnapshotMerged,
    staleTime: 60_000,
  });
  const policies = useQuery({
    queryKey: ["admin-execution-guard-policies"],
    queryFn: async () => (await api.get("/api/admin/execution-guard/policies?limit=200")).data?.data as Array<Record<string, unknown>>,
    staleTime: 15_000,
  });
  const audits = useQuery({
    queryKey: ["admin-execution-guard-audit"],
    queryFn: async () => (await api.get("/api/admin/execution-guard/policies/audit?limit=100")).data?.data as Array<Record<string, unknown>>,
    staleTime: 30_000,
  });
  const upsertPolicy = useMutation({
    mutationFn: async () =>
      (await api.post("/api/admin/execution-guard/policies", {
        scopeType,
        scopeKey,
        guardMode,
        patch: {
          maxDriftPct: Number(maxDriftPct),
          maxSignalAgeMs: Number(maxSignalAgeMs),
        },
      })).data?.data,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["admin-execution-guard-policies"] });
      qc.invalidateQueries({ queryKey: ["admin-execution-guard-audit"] });
    },
  });
  const reloadPolicies = useMutation({
    mutationFn: async () => (await api.post("/api/admin/execution-guard/policies/reload")).data?.data,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["admin-execution-guard-policies"] });
    },
  });

  return (
    <div className="space-y-3 text-foreground">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">OMS / Execution</h1>
          <p className="mt-1 text-xs text-muted-foreground">Throughput, rejects, latency, and per-order execution timeline.</p>
        </div>
        <Link
          to="/admin/oms"
          className="rounded-md border border-border bg-card px-3 py-1.5 font-mono text-[10px] font-semibold text-primary hover:bg-background/80"
        >
          Order grid →
        </Link>
      </div>
      <OMSLatencyMonitor snapshot={snapshot.data} />
      <div className="rounded-xl border border-border bg-card p-3">
        <div className="flex items-center justify-between gap-2">
          <h2 className="text-sm font-semibold">Execution Guard Policy Registry</h2>
          <button
            type="button"
            onClick={() => reloadPolicies.mutate()}
            className="rounded-md border border-border px-2 py-1 text-[11px] font-semibold"
          >
            Reload policy cache
          </button>
        </div>
        <div className="mt-3 grid gap-2 md:grid-cols-6">
          <select value={scopeType} onChange={(e) => setScopeType(e.target.value)} className="rounded-md border border-border bg-background px-2 py-1 text-xs">
            <option value="STRATEGY">STRATEGY</option>
            <option value="SYMBOL">SYMBOL</option>
            <option value="SESSION">SESSION</option>
            <option value="REGIME">REGIME</option>
          </select>
          <input value={scopeKey} onChange={(e) => setScopeKey(e.target.value)} className="rounded-md border border-border bg-background px-2 py-1 text-xs md:col-span-2" placeholder="scope key" />
          <select value={guardMode} onChange={(e) => setGuardMode(e.target.value)} className="rounded-md border border-border bg-background px-2 py-1 text-xs">
            <option value="ENTRY_STRICT">ENTRY_STRICT</option>
            <option value="EXIT_SAFE">EXIT_SAFE</option>
          </select>
          <input value={maxDriftPct} onChange={(e) => setMaxDriftPct(e.target.value)} className="rounded-md border border-border bg-background px-2 py-1 text-xs" placeholder="max drift %" />
          <input value={maxSignalAgeMs} onChange={(e) => setMaxSignalAgeMs(e.target.value)} className="rounded-md border border-border bg-background px-2 py-1 text-xs" placeholder="max signal age ms" />
        </div>
        <div className="mt-2">
          <button type="button" onClick={() => upsertPolicy.mutate()} className="rounded-md bg-primary px-3 py-1 text-xs font-semibold text-primary-foreground">
            Save override
          </button>
        </div>
        <div className="mt-3 overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead className="text-muted-foreground">
              <tr>{["scopeType", "scopeKey", "guardMode", "maxDriftPct", "maxSignalAgeMs", "updatedAt"].map((c) => <th key={c} className="pb-2 pr-3 uppercase tracking-wide">{c}</th>)}</tr>
            </thead>
            <tbody>
              {(policies.data ?? []).map((p, i) => (
                <tr key={String(p.id ?? i)} className="border-t border-border">
                  {["scopeType", "scopeKey", "guardMode", "maxDriftPct", "maxSignalAgeMs", "updatedAt"].map((c) => <td key={c} className="py-2 pr-3 font-mono">{String(p[c] ?? "-")}</td>)}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="mt-3 overflow-x-auto">
          <div className="mb-1 text-xs font-semibold">Policy Audit</div>
          <table className="w-full text-left text-xs">
            <thead className="text-muted-foreground">
              <tr>{["createdAt", "action", "scopeType", "scopeKey", "guardMode", "notes"].map((c) => <th key={c} className="pb-2 pr-3 uppercase tracking-wide">{c}</th>)}</tr>
            </thead>
            <tbody>
              {(audits.data ?? []).map((a, i) => (
                <tr key={String(a.id ?? i)} className="border-t border-border">
                  {["createdAt", "action", "scopeType", "scopeKey", "guardMode", "notes"].map((c) => <td key={c} className="py-2 pr-3 font-mono">{String(a[c] ?? "-")}</td>)}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <ExecutionTimelinePanel />
    </div>
  );
}
