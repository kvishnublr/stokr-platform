import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { toast } from "sonner";
import { api, parseAxiosMessage } from "../../api/client";

type BackfillJob = {
  id: string;
  status: string;
  brokerSource: string;
  symbolGroup: string;
  timeframe: string;
  rangeStart: string;
  rangeEnd: string;
  processedSymbols: number;
  totalSymbols: number;
  totalCandlesFetched: number;
  totalGaps: number;
  failureCount: number;
  latestCandleAt: string | null;
  throughputCps: string | null;
  message: string;
  updatedAt: string | null;
};
type CoverageRow = {
  symbol: string;
  timeframe: string;
  coveredFrom: string | null;
  coveredTo: string | null;
  latestCandleAt: string | null;
  completeness: string;
  freshness: string;
  replayReadiness: string;
  scannerReadiness: string;
  gapsPresent: boolean;
  gapCount: number;
  completionPct: string | number | null;
};

function pct(p: number, t: number): number {
  if (!t) return 0;
  return Math.max(0, Math.min(100, Math.round((p / t) * 100)));
}

export function AdminBackfillPage() {
  const qc = useQueryClient();
  const [brokerSource, setBrokerSource] = useState("ZERODHA");
  const [symbolGroup, setSymbolGroup] = useState("NIFTY_50");
  const [timeframe, setTimeframe] = useState("1m");
  const [rangeStart, setRangeStart] = useState("2026-01-01T09:15:00Z");
  const [rangeEnd, setRangeEnd] = useState("2026-01-05T15:30:00Z");
  const [customSymbols, setCustomSymbols] = useState("NIFTY_FUT,BANKNIFTY_FUT");

  const caps = useQuery({
    queryKey: ["admin-market-backfill-caps"],
    queryFn: async () => (await api.get("/api/admin/market/backfill/capabilities")).data?.data as Record<string, any>,
    staleTime: 60_000,
  });
  const jobs = useQuery({
    queryKey: ["admin-market-backfill-jobs"],
    queryFn: async () => (await api.get("/api/admin/market/backfill/jobs?limit=30")).data?.data as BackfillJob[],
    refetchInterval: 10_000,
  });
  const coverage = useQuery({
    queryKey: ["admin-market-backfill-coverage"],
    queryFn: async () => (await api.get("/api/admin/market/backfill/coverage")).data?.data as CoverageRow[],
    refetchInterval: 15_000,
  });

  const createJob = useMutation({
    mutationFn: async () =>
      api.post("/api/admin/market/backfill/jobs", {
        brokerSource,
        symbolGroup,
        timeframe,
        rangeStart,
        rangeEnd,
        customSymbols:
          symbolGroup === "CUSTOM"
            ? customSymbols
                .split(",")
                .map((s) => s.trim())
                .filter(Boolean)
            : [],
      }),
    onSuccess: async () => {
      toast.success("Backfill job queued");
      await qc.invalidateQueries({ queryKey: ["admin-market-backfill-jobs"] });
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const action = useMutation({
    mutationFn: async (payload: { jobId: string; action: "cancel" | "retry-failures" | "repair-gaps" }) =>
      api.post(`/api/admin/market/backfill/jobs/${payload.jobId}/${payload.action}`),
    onSuccess: async () => {
      toast.success("Action submitted");
      await qc.invalidateQueries({ queryKey: ["admin-market-backfill-jobs"] });
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const stats = useMemo(() => {
    const rows = jobs.data ?? [];
    return {
      running: rows.filter((r) => r.status === "RUNNING").length,
      failed: rows.filter((r) => r.status === "FAILED" || r.status === "PARTIAL").length,
      completed: rows.filter((r) => r.status === "COMPLETED").length,
    };
  }, [jobs.data]);

  return (
    <div className="space-y-4 text-foreground">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Backfill</h1>
        <p className="text-xs text-muted-foreground">Historical market ingestion, gap detection, retries, and repair.</p>
      </div>

      <div className="grid gap-3 md:grid-cols-3">
        <div className="rounded-xl border border-border bg-card p-3">
          <div className="text-xs text-muted-foreground">Running jobs</div>
          <div className="text-2xl font-semibold">{stats.running}</div>
        </div>
        <div className="rounded-xl border border-border bg-card p-3">
          <div className="text-xs text-muted-foreground">Completed</div>
          <div className="text-2xl font-semibold">{stats.completed}</div>
        </div>
        <div className="rounded-xl border border-border bg-card p-3">
          <div className="text-xs text-muted-foreground">Failed/partial</div>
          <div className="text-2xl font-semibold">{stats.failed}</div>
        </div>
      </div>

      <div className="rounded-xl border border-border bg-card p-3">
        <div className="mb-2 text-sm font-semibold">Launch backfill</div>
        <div className="grid gap-2 md:grid-cols-3">
          <select value={brokerSource} onChange={(e) => setBrokerSource(e.target.value)} className="rounded border border-border bg-background px-2 py-1 text-sm">
            {Object.keys(caps.data?.brokers ?? { ZERODHA: {} }).map((b) => (
              <option key={b} value={b}>
                {b}
              </option>
            ))}
          </select>
          <select value={symbolGroup} onChange={(e) => setSymbolGroup(e.target.value)} className="rounded border border-border bg-background px-2 py-1 text-sm">
            {["NIFTY_50", "NIFTY_100", "NIFTY_200", "BANKNIFTY", "FINNIFTY", "ALL_ACTIVE_STRATEGY_SYMBOLS", "ALL_EQUITY", "CUSTOM"].map((g) => (
              <option key={g} value={g}>
                {g}
              </option>
            ))}
          </select>
          <select value={timeframe} onChange={(e) => setTimeframe(e.target.value)} className="rounded border border-border bg-background px-2 py-1 text-sm">
            {["1m", "5m", "15m", "1h", "1d"].map((tf) => (
              <option key={tf} value={tf}>
                {tf}
              </option>
            ))}
          </select>
          <input value={rangeStart} onChange={(e) => setRangeStart(e.target.value)} className="rounded border border-border bg-background px-2 py-1 text-sm" placeholder="rangeStart ISO" />
          <input value={rangeEnd} onChange={(e) => setRangeEnd(e.target.value)} className="rounded border border-border bg-background px-2 py-1 text-sm" placeholder="rangeEnd ISO" />
          <input value={customSymbols} onChange={(e) => setCustomSymbols(e.target.value)} className="rounded border border-border bg-background px-2 py-1 text-sm" placeholder="CSV symbols for CUSTOM" disabled={symbolGroup !== "CUSTOM"} />
        </div>
        <div className="mt-2 flex items-center gap-2">
          <button type="button" onClick={() => createJob.mutate()} disabled={createJob.isPending} className="rounded border border-border bg-background px-3 py-1 text-sm font-semibold disabled:opacity-50">
            Start
          </button>
          <span className="text-xs text-muted-foreground">Source of truth: 1m candles. Higher TF derived from 1m.</span>
        </div>
      </div>

      <div className="rounded-xl border border-border bg-card p-3">
        <div className="mb-2 text-sm font-semibold">Recent jobs</div>
        <div className="space-y-2">
          {(jobs.data ?? []).map((j) => {
            const progress = pct(j.processedSymbols, j.totalSymbols);
            return (
              <div key={j.id} className="rounded-lg border border-border p-2">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="text-xs">
                    <span className="font-mono">{j.id.slice(0, 8)}</span> · <span className="font-semibold">{j.status}</span> · {j.brokerSource} · {j.timeframe}
                  </div>
                  <div className="flex gap-1">
                    <button type="button" onClick={() => action.mutate({ jobId: j.id, action: "cancel" })} className="rounded border border-border px-2 py-0.5 text-xs">Cancel</button>
                    <button type="button" onClick={() => action.mutate({ jobId: j.id, action: "retry-failures" })} className="rounded border border-border px-2 py-0.5 text-xs">Retry failed</button>
                    <button type="button" onClick={() => action.mutate({ jobId: j.id, action: "repair-gaps" })} className="rounded border border-border px-2 py-0.5 text-xs">Repair gaps</button>
                  </div>
                </div>
                <div className="mt-1 h-2 rounded bg-muted">
                  <div className="h-2 rounded bg-blue-500" style={{ width: `${progress}%` }} />
                </div>
                <div className="mt-1 text-xs text-muted-foreground">
                  symbols {j.processedSymbols}/{j.totalSymbols} · candles {j.totalCandlesFetched} · gaps {j.totalGaps} · failures {j.failureCount} · cps {j.throughputCps ?? "-"} · latest {j.latestCandleAt ?? "-"}
                </div>
              </div>
            );
          })}
          {jobs.data && jobs.data.length === 0 ? <div className="text-sm text-muted-foreground">No backfill jobs.</div> : null}
        </div>
      </div>

      <div className="rounded-xl border border-border bg-card p-3">
        <div className="mb-2 text-sm font-semibold">Coverage state</div>
        <div className="space-y-2">
          {(coverage.data ?? []).slice(0, 24).map((c) => (
            <div key={`${c.symbol}-${c.timeframe}`} className="rounded-lg border border-border p-2 text-xs">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="font-semibold">{c.symbol} · {c.timeframe}</div>
                <div className="text-muted-foreground">{c.completeness} · {c.freshness}</div>
              </div>
              <div className="mt-1 text-muted-foreground">
                replay {c.replayReadiness} · scanners {c.scannerReadiness} · gaps {c.gapCount} · completion {c.completionPct ?? "-"}%
              </div>
            </div>
          ))}
          {coverage.data && coverage.data.length === 0 ? <div className="text-sm text-muted-foreground">No coverage rows yet.</div> : null}
        </div>
      </div>
    </div>
  );
}
