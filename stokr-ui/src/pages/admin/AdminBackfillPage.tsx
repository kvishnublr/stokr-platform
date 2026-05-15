import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useRef, useState } from "react";
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

type JobSymbolDetail = {
  symbol: string;
  status: string;
  candlesFetched: number;
  gapCount: number;
  failureCount: number;
  latestCandleAt: string | null;
  message: string;
};

type JobFailureDetail = {
  symbol: string;
  failureCode: string;
  message: string;
  retryable: boolean;
  attemptCount: number;
  lastOccurredAt: string | null;
};

type JobDetail = {
  id: string;
  symbols: JobSymbolDetail[];
  failures: JobFailureDetail[];
};

function pct(p: number, t: number): number {
  if (!t) return 0;
  return Math.max(0, Math.min(100, Math.round((p / t) * 100)));
}

function jobReason(j: BackfillJob): string {
  if (j.message && j.message.trim().length > 0) return j.message;
  if (j.status === "COMPLETED") return "Completed with no reported errors.";
  if (j.status === "PARTIAL") return "Completed with partial symbol/range failures.";
  if (j.status === "FAILED") return "Job failed before full completion.";
  if (j.status === "RUNNING") return "Backfill is in progress.";
  return "No additional reason from backend.";
}

function coverageReason(c: CoverageRow): string {
  if (c.gapsPresent || c.completeness === "GAPS_PRESENT") return "GAPS_PRESENT";
  if (c.completeness === "NOT_BACKFILLED") return "NOT_BACKFILLED";
  if (c.completeness === "PARTIAL") return "INCOMPLETE_RANGE";
  if (c.freshness === "STALE") return "STALE_CANDLES";
  if (c.replayReadiness === "NO_DATA" || c.scannerReadiness === "NO_DATA") return "NO_DATA";
  return "READY";
}

export function AdminBackfillPage() {
  const qc = useQueryClient();
  const [brokerSource, setBrokerSource] = useState("ZERODHA");
  const [symbolGroup, setSymbolGroup] = useState("NIFTY_50");
  const [timeframe, setTimeframe] = useState("1m");
  const [rangeStart, setRangeStart] = useState("2026-01-01T09:15:00Z");
  const [rangeEnd, setRangeEnd] = useState("2026-01-05T15:30:00Z");
  const [customSymbols, setCustomSymbols] = useState("NIFTY_FUT,BANKNIFTY_FUT");
  const [selectedJobId, setSelectedJobId] = useState<string | null>(null);
  const [failedOnly, setFailedOnly] = useState(false);
  const completedNotifiedRef = useRef<Set<string>>(new Set());

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

  const jobDetail = useQuery({
    queryKey: ["admin-market-backfill-job-detail", selectedJobId],
    queryFn: async () => (await api.get(`/api/admin/market/backfill/jobs/${selectedJobId}`)).data?.data as JobDetail,
    enabled: Boolean(selectedJobId),
    refetchInterval: selectedJobId ? 10_000 : false,
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

  const filteredSymbols = useMemo(() => {
    const rows = jobDetail.data?.symbols ?? [];
    if (!failedOnly) return rows;
    return rows.filter((s) => s.failureCount > 0 || s.status === "FAILED" || s.status === "GAP_DETECTED");
  }, [jobDetail.data?.symbols, failedOnly]);

  const filteredFailures = useMemo(() => {
    const rows = jobDetail.data?.failures ?? [];
    if (!failedOnly) return rows;
    const failedSet = new Set(filteredSymbols.map((s) => s.symbol));
    return rows.filter((f) => failedSet.has(f.symbol));
  }, [jobDetail.data?.failures, failedOnly, filteredSymbols]);

  function failureCodeClass(code: string): string {
    const c = String(code ?? "").toUpperCase();
    if (c.includes("RATE") || c.includes("TIMEOUT") || c.includes("5XX") || c.includes("TEMP")) {
      return "border border-amber-300 bg-amber-50 text-amber-800";
    }
    if (c.includes("AUTH") || c.includes("TOKEN") || c.includes("PERMISSION")) {
      return "border border-red-300 bg-red-50 text-red-800";
    }
    if (c.includes("NO_DATA") || c.includes("INCOMPLETE") || c.includes("EMPTY")) {
      return "border border-blue-300 bg-blue-50 text-blue-800";
    }
    return "border border-border bg-muted/40 text-foreground";
  }

  const coverageStats = useMemo(() => {
    const rows = coverage.data ?? [];
    return {
      ready: rows.filter((r) => r.completeness === "READY").length,
      partial: rows.filter((r) => r.completeness === "PARTIAL").length,
      stale: rows.filter((r) => r.freshness === "STALE").length,
      gaps: rows.filter((r) => r.gapsPresent || r.completeness === "GAPS_PRESENT").length,
    };
  }, [coverage.data]);

  useEffect(() => {
    const rows = jobs.data ?? [];
    for (const j of rows) {
      if (j.status === "COMPLETED" || j.status === "FAILED" || j.status === "PARTIAL") {
        if (!completedNotifiedRef.current.has(j.id)) {
          completedNotifiedRef.current.add(j.id);
          const suffix = j.status === "COMPLETED" ? "finished successfully." : `finished with status ${j.status}.`;
          toast.info(`Backfill ${j.id.slice(0, 8)} ${suffix}`);
        }
      }
    }
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

      {stats.running > 0 ? (
        <div className="rounded-xl border border-blue-300 bg-blue-50 p-3 text-sm text-blue-900">
          Backfill is running ({stats.running} active). Please wait until it finishes for final readiness state.
        </div>
      ) : null}

      <div className="grid gap-3 md:grid-cols-4">
        <div className="rounded-xl border border-border bg-card p-3">
          <div className="text-xs text-muted-foreground">Coverage READY</div>
          <div className="text-2xl font-semibold">{coverageStats.ready}</div>
        </div>
        <div className="rounded-xl border border-border bg-card p-3">
          <div className="text-xs text-muted-foreground">Coverage PARTIAL</div>
          <div className="text-2xl font-semibold">{coverageStats.partial}</div>
        </div>
        <div className="rounded-xl border border-border bg-card p-3">
          <div className="text-xs text-muted-foreground">Coverage STALE</div>
          <div className="text-2xl font-semibold">{coverageStats.stale}</div>
        </div>
        <div className="rounded-xl border border-border bg-card p-3">
          <div className="text-xs text-muted-foreground">Coverage GAPS</div>
          <div className="text-2xl font-semibold">{coverageStats.gaps}</div>
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
            const running = j.status === "RUNNING";
            return (
              <div key={j.id} className="rounded-lg border border-border p-2">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="text-xs">
                    <span className="font-mono">{j.id.slice(0, 8)}</span> · <span className="font-semibold">{j.status}</span> · {j.brokerSource} · {j.timeframe}
                  </div>
                  <div className="flex gap-1">
                    <button type="button" onClick={() => setSelectedJobId(j.id)} className="rounded border border-border px-2 py-0.5 text-xs">Details</button>
                    <button type="button" onClick={() => action.mutate({ jobId: j.id, action: "cancel" })} className="rounded border border-border px-2 py-0.5 text-xs">Cancel</button>
                    <button type="button" onClick={() => action.mutate({ jobId: j.id, action: "retry-failures" })} className="rounded border border-border px-2 py-0.5 text-xs">Retry failed</button>
                    <button type="button" onClick={() => action.mutate({ jobId: j.id, action: "repair-gaps" })} className="rounded border border-border px-2 py-0.5 text-xs">Repair gaps</button>
                  </div>
                </div>
                <div className="mt-1 h-2 rounded bg-muted">
                  <div
                    className={`h-2 rounded bg-blue-500 transition-all duration-700 ${running ? "animate-pulse" : ""}`}
                    style={{ width: `${progress}%` }}
                  />
                </div>
                <div className="mt-1 text-xs text-muted-foreground">
                  symbols {j.processedSymbols}/{j.totalSymbols} · candles {j.totalCandlesFetched} · gaps {j.totalGaps} · failures {j.failureCount} · cps {j.throughputCps ?? "-"} · latest {j.latestCandleAt ?? "-"}
                </div>
                {running ? <div className="mt-1 text-xs text-blue-700">In progress. Please wait for completion notification.</div> : null}
                <div className="mt-1 text-xs">
                  <span className="font-semibold">Reason:</span> <span className="text-muted-foreground">{jobReason(j)}</span>
                </div>
              </div>
            );
          })}
          {jobs.data && jobs.data.length === 0 ? <div className="text-sm text-muted-foreground">No backfill jobs.</div> : null}
        </div>
      </div>

      {selectedJobId ? (
        <div className="rounded-xl border border-border bg-card p-3">
          <div className="mb-2 flex items-center justify-between">
            <div className="text-sm font-semibold">Job details · <span className="font-mono">{selectedJobId.slice(0, 8)}</span></div>
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => setFailedOnly((v) => !v)}
                className="rounded border border-border px-2 py-0.5 text-xs"
              >
                {failedOnly ? "Show all" : "Failed only"}
              </button>
              <button type="button" onClick={() => setSelectedJobId(null)} className="rounded border border-border px-2 py-0.5 text-xs">Close</button>
            </div>
          </div>
          {jobDetail.isLoading ? <div className="text-sm text-muted-foreground">Loading details...</div> : null}
          {jobDetail.isError ? <div className="text-sm text-red-600">Failed to load details.</div> : null}
          {jobDetail.data ? (
            <div className="space-y-3">
              <div>
                <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">Symbol outcomes</div>
                <div className="max-h-56 overflow-auto rounded border border-border">
                  <table className="w-full text-xs">
                    <thead className="bg-muted/40 text-left">
                      <tr>
                        <th className="px-2 py-1">Symbol</th>
                        <th className="px-2 py-1">Status</th>
                        <th className="px-2 py-1">Candles</th>
                        <th className="px-2 py-1">Failures</th>
                        <th className="px-2 py-1">Reason</th>
                      </tr>
                    </thead>
                    <tbody>
                      {filteredSymbols.map((s) => (
                        <tr key={s.symbol} className="border-t border-border">
                          <td className="px-2 py-1 font-semibold">{s.symbol}</td>
                          <td className="px-2 py-1">{s.status}</td>
                          <td className="px-2 py-1">{s.candlesFetched}</td>
                          <td className="px-2 py-1">{s.failureCount}</td>
                          <td className="px-2 py-1 text-muted-foreground">{s.message}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
              <div>
                <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">Failure log</div>
                {filteredFailures.length === 0 ? (
                  <div className="text-xs text-muted-foreground">No failures logged.</div>
                ) : (
                  <div className="max-h-48 overflow-auto rounded border border-border">
                    <table className="w-full text-xs">
                      <thead className="bg-muted/40 text-left">
                        <tr>
                          <th className="px-2 py-1">Symbol</th>
                          <th className="px-2 py-1">Code</th>
                          <th className="px-2 py-1">Retryable</th>
                          <th className="px-2 py-1">Message</th>
                        </tr>
                      </thead>
                      <tbody>
                        {filteredFailures.map((f, idx) => (
                          <tr key={`${f.symbol}-${f.failureCode}-${idx}`} className="border-t border-border">
                            <td className="px-2 py-1 font-semibold">{f.symbol}</td>
                            <td className="px-2 py-1">
                              <span className={`inline-flex rounded px-1.5 py-0.5 text-[11px] font-semibold ${failureCodeClass(f.failureCode)}`}>
                                {f.failureCode}
                              </span>
                            </td>
                            <td className="px-2 py-1">{f.retryable ? "Yes" : "No"}</td>
                            <td className="px-2 py-1 text-muted-foreground">{f.message}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            </div>
          ) : null}
        </div>
      ) : null}

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
              <div className="mt-1">
                <span className="font-semibold">Reason:</span> <span className="text-muted-foreground">{coverageReason(c)}</span>
              </div>
            </div>
          ))}
          {coverage.data && coverage.data.length === 0 ? <div className="text-sm text-muted-foreground">No coverage rows yet.</div> : null}
        </div>
      </div>
    </div>
  );
}
