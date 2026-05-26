import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../../api/client";
import {
  AlertTriangle, BarChart3, CheckCircle, Clock, Eraser, Play, RefreshCw, Target, Trash2,
} from "lucide-react";

type StrategyCatalogItem = { strategyKey: string; displayName: string };

type StrategyStatsRow = {
  strategyName: string;
  total: number;
  buyCount: number;
  sellCount: number;
  targetHit: number;
  slHit: number;
  running: number;
  expired: number;
  pending: number;
  winRate?: number;
};

type CleanupResult = {
  dryRun: boolean;
  fromDate: string;
  toDate: string;
  strategyKey: string;
  includeReplayAndLab: boolean;
  matchedCount: number;
  deletedCount: number;
};

type BenchmarkResult = {
  status: string;
  strategyKey: string;
  fromDate: string;
  toDate: string;
  purgedBeforeRerun: boolean;
  purgedCount: number;
  strategiesReplayed: number;
  totalSignalsGenerated: number;
  outcomesProcessed: number;
  strategyStats: StrategyStatsRow[];
  replayDetails?: Record<string, unknown>;
};

type AsyncStart = { status: string; message: string; strategyKey: string; from: string; to: string };

const today = () => new Date().toISOString().slice(0, 10);

const pct = (v: number | undefined) => (v == null || Number.isNaN(v) ? "—" : `${v.toFixed(1)}%`);

export function AdminSignalLabPage() {
  const queryClient = useQueryClient();
  const [strategyKey, setStrategyKey] = useState("ALL");
  const [from, setFrom] = useState(today());
  const [to, setTo] = useState(today());
  const [includeReplayLab, setIncludeReplayLab] = useState(true);
  const [purgeBeforeRerun, setPurgeBeforeRerun] = useState(true);
  const [cleanupPreview, setCleanupPreview] = useState<CleanupResult | null>(null);
  const [cleanupDone, setCleanupDone] = useState<CleanupResult | null>(null);
  const [benchmarkResult, setBenchmarkResult] = useState<BenchmarkResult | AsyncStart | null>(null);

  const catalogQ = useQuery<StrategyCatalogItem[]>({
    queryKey: ["strategy-catalog-keys"],
    queryFn: async () => {
      const r = await api.get("/api/admin/strategy-catalog");
      return (r.data?.data ?? []).map((c: { strategyKey: string; displayName?: string }) => ({
        strategyKey: c.strategyKey,
        displayName: c.displayName ?? c.strategyKey,
      }));
    },
    staleTime: 60_000,
  });

  const statsQ = useQuery<StrategyStatsRow[]>({
    queryKey: ["admin-signal-stats-detailed", from, to, strategyKey, includeReplayLab],
    queryFn: async () => {
      const p = new URLSearchParams();
      p.set("from", from);
      p.set("to", to);
      p.set("strategyKey", strategyKey);
      p.set("includeReplayAndLab", String(includeReplayLab));
      const r = await api.get(`/api/admin/signals/stats/detailed?${p.toString()}`);
      return (r.data?.data ?? []).map((row: StrategyStatsRow) => ({
        ...row,
        winRate: row.targetHit + row.slHit > 0
          ? (row.targetHit / (row.targetHit + row.slHit)) * 100
          : 0,
      }));
    },
    refetchInterval: 15_000,
  });

  const strategies = useMemo(() => {
    const rows = catalogQ.data ?? [];
    return [{ strategyKey: "ALL", displayName: "All strategies" }, ...rows];
  }, [catalogQ.data]);

  const totals = useMemo(() => {
    const rows = statsQ.data ?? [];
    return rows.reduce(
      (acc, r) => ({
        total: acc.total + (r.total ?? 0),
        targetHit: acc.targetHit + (r.targetHit ?? 0),
        slHit: acc.slHit + (r.slHit ?? 0),
        running: acc.running + (r.running ?? 0),
        expired: acc.expired + (r.expired ?? 0),
        pending: acc.pending + (r.pending ?? 0),
      }),
      { total: 0, targetHit: 0, slHit: 0, running: 0, expired: 0, pending: 0 },
    );
  }, [statsQ.data]);

  const totalWinRate = totals.targetHit + totals.slHit > 0
    ? (totals.targetHit / (totals.targetHit + totals.slHit)) * 100
    : 0;

  const cleanupParams = () => {
    const p = new URLSearchParams();
    p.set("from", from);
    p.set("to", to);
    p.set("strategyKey", strategyKey);
    p.set("includeReplayAndLab", String(includeReplayLab));
    return p;
  };

  const previewMut = useMutation({
    mutationFn: async () => {
      const p = cleanupParams();
      p.set("dryRun", "true");
      const r = await api.post(`/api/admin/signals/cleanup?${p.toString()}`);
      return r.data?.data as CleanupResult;
    },
    onSuccess: (data) => {
      setCleanupPreview(data);
      setCleanupDone(null);
    },
  });

  const cleanupMut = useMutation({
    mutationFn: async () => {
      const p = cleanupParams();
      p.set("dryRun", "false");
      const r = await api.post(`/api/admin/signals/cleanup?${p.toString()}`);
      return r.data?.data as CleanupResult;
    },
    onSuccess: (data) => {
      setCleanupDone(data);
      setCleanupPreview(null);
      void queryClient.invalidateQueries({ queryKey: ["admin-signal-stats-detailed"] });
      void queryClient.invalidateQueries({ queryKey: ["admin-signals"] });
      void queryClient.invalidateQueries({ queryKey: ["admin-signal-stats"] });
    },
  });

  const benchmarkMut = useMutation({
    mutationFn: async () => {
      const p = new URLSearchParams();
      p.set("from", from);
      p.set("to", to);
      p.set("strategyKey", strategyKey);
      p.set("purgeBeforeRerun", String(purgeBeforeRerun));
      p.set("includeReplayAndLab", String(includeReplayLab));
      const all = strategyKey === "ALL";
      p.set("async", String(all));
      const r = await api.post(`/api/admin/signals/benchmark/rerun?${p.toString()}`);
      return r.data?.data as BenchmarkResult | AsyncStart;
    },
    onSuccess: (data) => {
      setBenchmarkResult(data);
      void queryClient.invalidateQueries({ queryKey: ["admin-signal-stats-detailed"] });
      void queryClient.invalidateQueries({ queryKey: ["admin-signals"] });
      void queryClient.invalidateQueries({ queryKey: ["admin-signal-stats"] });
    },
  });

  const benchmarkAsync = benchmarkResult && "message" in benchmarkResult && benchmarkResult.status === "STARTED";

  return (
    <div className="mx-auto max-w-5xl space-y-6 p-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-slate-900">Signal Lab &amp; Benchmark</h1>
          <p className="mt-1 text-sm text-slate-500">
            Clean up signals for today or a range, rerun strategies with the latest committed logic, and review hit/SL stats per strategy.
          </p>
        </div>
        <div className="flex gap-2 text-sm">
          <Link to="/admin/signals" className="rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-slate-600 hover:bg-slate-50">
            Signal Monitor
          </Link>
          <Link to="/admin/signal-replay" className="rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-slate-600 hover:bg-slate-50">
            Signal Replay
          </Link>
        </div>
      </div>

      <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm space-y-4">
        <h2 className="text-sm font-semibold text-slate-700">Scope</h2>
        <div className="grid gap-4 md:grid-cols-3">
          <div>
            <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate-500">Strategy</label>
            <select
              value={strategyKey}
              onChange={(e) => setStrategyKey(e.target.value)}
              className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
            >
              {strategies.map((s) => (
                <option key={s.strategyKey} value={s.strategyKey}>{s.displayName}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate-500">From</label>
            <input type="date" value={from} max={today()} onChange={(e) => setFrom(e.target.value)} className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
          </div>
          <div>
            <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate-500">To</label>
            <input type="date" value={to} max={today()} min={from} onChange={(e) => setTo(e.target.value)} className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
          </div>
        </div>
        <label className="flex items-center gap-2 text-sm text-slate-600">
          <input type="checkbox" checked={includeReplayLab} onChange={(e) => setIncludeReplayLab(e.target.checked)} />
          Include replay &amp; lab signals (recommended when rerunning historical replay)
        </label>
        <div className="flex flex-wrap gap-2">
          <button type="button" onClick={() => { setFrom(today()); setTo(today()); }} className="rounded-lg border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-600 hover:bg-slate-50">
            Today only
          </button>
          <button type="button" onClick={() => statsQ.refetch()} className="inline-flex items-center gap-1 rounded-lg border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-600 hover:bg-slate-50">
            <RefreshCw className={`h-3.5 w-3.5 ${statsQ.isFetching ? "animate-spin" : ""}`} /> Refresh stats
          </button>
        </div>
      </div>

      <div className="grid gap-4 md:grid-cols-2">
        <div className="rounded-xl border border-amber-200 bg-amber-50/50 p-5 space-y-3">
          <div className="flex items-center gap-2 text-sm font-semibold text-amber-800">
            <Eraser className="h-4 w-4" /> Cleanup signals
          </div>
          <p className="text-xs text-amber-700">
            Soft-deletes matching signals for {from === to ? from : `${from} → ${to}`}
            {strategyKey === "ALL" ? " across all strategies" : ` for ${strategyKey}`}.
          </p>
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              onClick={() => previewMut.mutate()}
              disabled={previewMut.isPending}
              className="inline-flex items-center gap-2 rounded-lg border border-amber-300 bg-white px-3 py-2 text-sm font-medium text-amber-800 hover:bg-amber-50 disabled:opacity-50"
            >
              {previewMut.isPending ? <RefreshCw className="h-4 w-4 animate-spin" /> : <BarChart3 className="h-4 w-4" />}
              Preview count
            </button>
            <button
              type="button"
              onClick={() => cleanupMut.mutate()}
              disabled={cleanupMut.isPending}
              className="inline-flex items-center gap-2 rounded-lg bg-amber-600 px-3 py-2 text-sm font-semibold text-white hover:bg-amber-700 disabled:opacity-50"
            >
              {cleanupMut.isPending ? <RefreshCw className="h-4 w-4 animate-spin" /> : <Trash2 className="h-4 w-4" />}
              Delete matching
            </button>
          </div>
          {cleanupPreview && (
            <div className="rounded-lg border border-amber-200 bg-white p-3 text-sm text-amber-900">
              Preview: <strong>{cleanupPreview.matchedCount}</strong> signal(s) would be removed.
            </div>
          )}
          {cleanupDone && (
            <div className="flex items-start gap-2 rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-800">
              <CheckCircle className="mt-0.5 h-4 w-4 shrink-0" />
              Removed <strong>{cleanupDone.deletedCount}</strong> of {cleanupDone.matchedCount} matched signal(s).
            </div>
          )}
          {(previewMut.isError || cleanupMut.isError) && (
            <div className="flex items-start gap-2 rounded-lg border border-rose-200 bg-rose-50 p-3 text-sm text-rose-700">
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
              {String((previewMut.error ?? cleanupMut.error as Error)?.message ?? "Cleanup failed")}
            </div>
          )}
        </div>

        <div className="rounded-xl border border-indigo-200 bg-indigo-50/50 p-5 space-y-3">
          <div className="flex items-center gap-2 text-sm font-semibold text-indigo-800">
            <Play className="h-4 w-4" /> Rerun with current logic
          </div>
          <p className="text-xs text-indigo-700">
            After you commit strategy changes, rerun replay for this range using today&apos;s code path, then compute outcomes.
          </p>
          <label className="flex items-center gap-2 text-sm text-indigo-800">
            <input type="checkbox" checked={purgeBeforeRerun} onChange={(e) => setPurgeBeforeRerun(e.target.checked)} />
            Purge matching signals before rerun
          </label>
          <button
            type="button"
            onClick={() => benchmarkMut.mutate()}
            disabled={benchmarkMut.isPending}
            className="flex w-full items-center justify-center gap-2 rounded-lg bg-indigo-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-indigo-700 disabled:opacity-50"
          >
            {benchmarkMut.isPending
              ? <><RefreshCw className="h-4 w-4 animate-spin" /> Running benchmark…</>
              : <><Play className="h-4 w-4" /> Purge + replay + outcomes</>}
          </button>
          {benchmarkAsync && (
            <div className="rounded-lg border border-blue-200 bg-blue-50 p-3 text-sm text-blue-800">
              <div className="flex items-center gap-2 font-medium"><Clock className="h-4 w-4" /> Running in background</div>
              <div className="mt-1 text-xs">{benchmarkResult && "message" in benchmarkResult ? benchmarkResult.message : ""}</div>
            </div>
          )}
          {benchmarkResult && !benchmarkAsync && "totalSignalsGenerated" in benchmarkResult && (
            <div className="rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-800 space-y-1">
              <div className="font-medium">Benchmark complete</div>
              <div>Generated {benchmarkResult.totalSignalsGenerated} signal(s) · outcomes processed {benchmarkResult.outcomesProcessed}</div>
              {benchmarkResult.purgedBeforeRerun && <div>Purged {benchmarkResult.purgedCount} before rerun</div>}
            </div>
          )}
          {benchmarkMut.isError && (
            <div className="flex items-start gap-2 rounded-lg border border-rose-200 bg-rose-50 p-3 text-sm text-rose-700">
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
              {String((benchmarkMut.error as Error)?.message ?? "Benchmark failed")}
            </div>
          )}
        </div>
      </div>

      <div className="rounded-xl border border-slate-200 bg-white shadow-sm overflow-hidden">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 px-5 py-4">
          <div>
            <h2 className="text-sm font-semibold text-slate-800">Per-strategy results</h2>
            <p className="text-xs text-slate-500">{from === to ? from : `${from} → ${to}`} · IST day boundaries</p>
          </div>
          <div className="flex flex-wrap gap-3 text-xs">
            <span className="rounded-full bg-slate-100 px-2.5 py-1 text-slate-700">Signals {totals.total}</span>
            <span className="rounded-full bg-emerald-100 px-2.5 py-1 text-emerald-800"><Target className="mr-1 inline h-3 w-3" />Target {totals.targetHit}</span>
            <span className="rounded-full bg-rose-100 px-2.5 py-1 text-rose-800">SL {totals.slHit}</span>
            <span className="rounded-full bg-indigo-100 px-2.5 py-1 text-indigo-800">Win {pct(totalWinRate)}</span>
          </div>
        </div>
        <div className="overflow-x-auto">
          <table className="min-w-full text-sm">
            <thead className="bg-slate-50 text-left text-xs uppercase tracking-wide text-slate-500">
              <tr>
                <th className="px-4 py-3">Strategy</th>
                <th className="px-4 py-3 text-right">Signals</th>
                <th className="px-4 py-3 text-right">Buy</th>
                <th className="px-4 py-3 text-right">Sell</th>
                <th className="px-4 py-3 text-right">Target</th>
                <th className="px-4 py-3 text-right">SL</th>
                <th className="px-4 py-3 text-right">Running</th>
                <th className="px-4 py-3 text-right">Expired</th>
                <th className="px-4 py-3 text-right">Pending</th>
                <th className="px-4 py-3 text-right">Win %</th>
              </tr>
            </thead>
            <tbody>
              {(statsQ.data ?? []).length === 0 ? (
                <tr>
                  <td colSpan={10} className="px-4 py-8 text-center text-slate-400">
                    {statsQ.isLoading ? "Loading…" : "No signals in this range."}
                  </td>
                </tr>
              ) : (
                (statsQ.data ?? []).map((row) => (
                  <tr key={row.strategyName} className="border-t border-slate-100 hover:bg-slate-50/80">
                    <td className="px-4 py-3 font-medium text-slate-800">{row.strategyName}</td>
                    <td className="px-4 py-3 text-right tabular-nums">{row.total}</td>
                    <td className="px-4 py-3 text-right tabular-nums text-emerald-700">{row.buyCount}</td>
                    <td className="px-4 py-3 text-right tabular-nums text-rose-700">{row.sellCount}</td>
                    <td className="px-4 py-3 text-right tabular-nums font-medium text-emerald-700">{row.targetHit}</td>
                    <td className="px-4 py-3 text-right tabular-nums font-medium text-rose-700">{row.slHit}</td>
                    <td className="px-4 py-3 text-right tabular-nums">{row.running}</td>
                    <td className="px-4 py-3 text-right tabular-nums">{row.expired}</td>
                    <td className="px-4 py-3 text-right tabular-nums">{row.pending}</td>
                    <td className="px-4 py-3 text-right tabular-nums">{pct(row.winRate)}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      <div className="rounded-lg border border-slate-100 bg-slate-50 p-4 text-xs text-slate-500 space-y-1">
        <div className="font-semibold text-slate-600">Workflow after changing strategy logic</div>
        <div>1. Commit &amp; deploy your strategy code changes</div>
        <div>2. Open this page, set date range + strategy, preview cleanup if needed</div>
        <div>3. Run &quot;Purge + replay + outcomes&quot; — uses current registered strategy logic</div>
        <div>4. Review per-strategy target / SL / win rate in the table above</div>
      </div>
    </div>
  );
}
