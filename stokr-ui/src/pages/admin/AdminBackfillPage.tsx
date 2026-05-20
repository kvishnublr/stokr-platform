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

type StrategyAuditRow = {
  strategyKey: string;
  displayName: string | null;
  timeframe: string;
  useCase: string;
  symbolsTotal: number;
  symbolsReady: number;
  ready: boolean;
  status: string;
  blockers: Array<{ symbol: string; state: string; detail: string }>;
  symbols: string[];
};

type SymbolGroupPreview = {
  count: number;
  symbols: string[];
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

function extractAuthSource(message: string | null | undefined): string {
  const m = String(message ?? "");
  const match = /auth\s+([A-Z_]+)/i.exec(m);
  return match?.[1]?.toUpperCase() ?? "UNKNOWN";
}

function looksLikeInvalidBrokerDate(message: string): boolean {
  const m = String(message ?? "").toLowerCase();
  return m.includes("invalid from date") || m.includes("invalid to date") || m.includes("inputexception");
}

function parseCustomSymbols(input: string): string[] {
  return input
    .split(/[\s,]+/)
    .map((s) => s.trim().toUpperCase())
    .filter(Boolean);
}

function looksLikeZerodhaTokenAuthError(message: string): boolean {
  const m = (message || "").toLowerCase();
  return (
    m.includes("tokenexception") ||
    m.includes("incorrect `api_key`") ||
    m.includes("incorrect api_key") ||
    m.includes("access_token") ||
    m.includes("token invalid") ||
    m.includes("auth expired") ||
    m.includes("forbidden")
  );
}

function looksLikeLookbackLimit(message: string): boolean {
  const m = (message || "").toLowerCase();
  return m.includes("lookback") || m.includes("exceeds zerodha historical lookback") || m.includes("supports only recent windows");
}

function toDateInputString(d: Date): string {
  const istMs = d.getTime() + 5.5 * 60 * 60 * 1000;
  const ist = new Date(istMs);
  const yyyy = ist.getUTCFullYear();
  const mm = String(ist.getUTCMonth() + 1).padStart(2, "0");
  const dd = String(ist.getUTCDate()).padStart(2, "0");
  return `${yyyy}-${mm}-${dd}`;
}

function toNseSessionStartIso(dateYmd: string): string | null {
  const d = String(dateYmd ?? "").trim();
  if (!/^\d{4}-\d{2}-\d{2}$/.test(d)) return null;
  return `${d}T09:15:00+05:30`;
}

function toNseSessionEndIso(dateYmd: string): string | null {
  const d = String(dateYmd ?? "").trim();
  if (!/^\d{4}-\d{2}-\d{2}$/.test(d)) return null;
  return `${d}T15:30:00+05:30`;
}

function defaultBackfillRange(): { start: string; end: string } {
  const now = new Date();
  const end = new Date(now);
  end.setMinutes(0, 0, 0);
  const start = new Date(end.getTime() - 2 * 24 * 60 * 60 * 1000);
  return { start: toDateInputString(start), end: toDateInputString(end) };
}

export function AdminBackfillPage() {
  const qc = useQueryClient();
  const defaultRange = useMemo(() => defaultBackfillRange(), []);
  const [brokerSource, setBrokerSource] = useState("ZERODHA");
  const [symbolGroup, setSymbolGroup] = useState("NIFTY_50");
  const [timeframe, setTimeframe] = useState("1m");
  const [rangeStart, setRangeStart] = useState(defaultRange.start);
  const [rangeEnd, setRangeEnd] = useState(defaultRange.end);
  const [customSymbols, setCustomSymbols] = useState("NIFTY_FUT,BANKNIFTY_FUT");
  const [selectedJobId, setSelectedJobId] = useState<string | null>(null);
  const [failedOnly, setFailedOnly] = useState(false);
  const [auditFrom, setAuditFrom] = useState(defaultRange.start);
  const [auditTo, setAuditTo] = useState(defaultRange.end);
  const [importSymbol, setImportSymbol] = useState("SBIN");
  const [importTimeframe, setImportTimeframe] = useState("1m");
  const [importFile, setImportFile] = useState<File | null>(null);
  const completedNotifiedRef = useRef<Set<string>>(new Set());

  async function reconnectZerodhaNow() {
    try {
      const res = await api.post<{ data?: { authorizeUrl?: string } }>("/api/admin/broker-infrastructure/ZERODHA/connect");
      const url = res.data?.data?.authorizeUrl;
      if (url && typeof url === "string") {
        window.location.href = url;
        return;
      }
      toast.error("Could not start Zerodha OAuth. Open Broker Infrastructure and reconnect.");
    } catch (e) {
      toast.error(parseAxiosMessage(e));
    }
  }

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
    mutationFn: async () => {
      const startIso = toNseSessionStartIso(rangeStart);
      const endIso = toNseSessionEndIso(rangeEnd);
      if (!startIso || !endIso) {
        throw new Error("Invalid date. Use YYYY-MM-DD.");
      }
      if (new Date(startIso).getTime() >= new Date(endIso).getTime()) {
        throw new Error("Start date must be before end date.");
      }
      if (brokerSource.toUpperCase() === "ZERODHA" && timeframe !== "1d") {
        const cutoffMs = Date.now() - 60 * 24 * 60 * 60 * 1000;
        if (new Date(startIso).getTime() < cutoffMs) {
          throw new Error("Zerodha intraday history supports only recent windows (about 60 days). Use a newer start date or import historical candles.");
        }
      }
      const payload = {
        brokerSource,
        symbolGroup,
        timeframe,
        rangeStart: startIso,
        rangeEnd: endIso,
        customSymbols: symbolGroup === "CUSTOM" ? parseCustomSymbols(customSymbols) : [],
      };
      const pre = await api.post("/api/admin/market/backfill/preflight", payload);
      const preData = pre.data?.data as { verdict?: string; blockers?: Array<{ code?: string; message?: string; action?: string }> } | undefined;
      if ((preData?.verdict ?? "FAIL").toUpperCase() !== "PASS") {
        const top = (preData?.blockers ?? [])[0];
        const msg = top ? `${top.code ?? "BLOCKED"}: ${top.message ?? "Backfill preflight blocked"}` : "Backfill preflight blocked";
        const action = top?.action ? ` Next: ${top.action}` : "";
        throw new Error(`${msg}${action}`);
      }
      return api.post("/api/admin/market/backfill/jobs", payload);
    },
    onSuccess: async () => {
      toast.success("Backfill job queued");
      await qc.invalidateQueries({ queryKey: ["admin-market-backfill-jobs"] });
    },
    onError: (e) => {
      const msg = parseAxiosMessage(e);
      if (looksLikeInvalidBrokerDate(msg)) {
        toast.error(
          "Backfill date window is invalid for broker API. Use a weekday trading session in IST (09:15-15:30) and keep end time in the past.",
        );
        return;
      }
      if (looksLikeZerodhaTokenAuthError(msg)) {
        toast.error("Broker token invalid/expired. Re-authenticate Zerodha now?", {
          action: {
            label: "OK",
            onClick: () => {
              void reconnectZerodhaNow();
            },
          },
          cancel: {
            label: "Cancel",
            onClick: () => {},
          },
          duration: 12000,
        });
        return;
      }
      if (looksLikeLookbackLimit(msg)) {
        toast.error(
          "Zerodha intraday history is limited (typically 60 days for intraday intervals). Use a recent date range or import historical data."
        );
        return;
      }
      toast.error(msg);
    },
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

  const importCsv = useMutation({
    mutationFn: async () => {
      if (!importFile) {
        throw new Error("Select a CSV file first.");
      }
      const form = new FormData();
      form.append("file", importFile);
      form.append("timeframe", importTimeframe);
      const sym = importSymbol.trim().toUpperCase();
      if (sym) {
        form.append("symbol", sym);
      }
      return api.post("/api/admin/market/backfill/import-csv", form, {
        headers: { "Content-Type": "multipart/form-data" },
      });
    },
    onSuccess: async (res) => {
      const d = res.data?.data as { totalPersisted?: number; symbolsUpdated?: number } | undefined;
      toast.success(`Imported ${d?.totalPersisted ?? 0} candles across ${d?.symbolsUpdated ?? 0} symbol(s).`);
      await qc.invalidateQueries({ queryKey: ["admin-market-backfill-coverage"] });
      await qc.invalidateQueries({ queryKey: ["admin-backfill-strategy-audit"] });
      setImportFile(null);
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

  const groupPreview = useMemo(() => {
    if (symbolGroup === "CUSTOM") {
      const symbols = parseCustomSymbols(customSymbols);
      return { count: symbols.length, symbols };
    }
    const root = (caps.data?.symbolGroupPreview ?? {}) as Record<string, SymbolGroupPreview>;
    return root[symbolGroup] ?? { count: 0, symbols: [] };
  }, [caps.data, symbolGroup, customSymbols]);

  const strategyAudit = useQuery({
    queryKey: ["admin-backfill-strategy-audit", auditFrom, auditTo, timeframe],
    queryFn: async () => {
      const from = toNseSessionStartIso(auditFrom);
      const to = toNseSessionEndIso(auditTo);
      if (!from || !to) return [];
      return (await api.get(`/api/admin/market/backfill/strategy-readiness-audit?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&timeframe=${encodeURIComponent(timeframe)}&useCase=REPLAY`)).data?.data as StrategyAuditRow[];
    },
    enabled: Boolean(toNseSessionStartIso(auditFrom) && toNseSessionEndIso(auditTo)),
    refetchInterval: 30_000,
  });

  const failureCodeSet = useMemo(() => {
    const set = new Set<string>();
    for (const f of filteredFailures) {
      set.add(String(f.failureCode ?? "").toUpperCase());
    }
    return set;
  }, [filteredFailures]);

  const guidance = useMemo(() => {
    const hints: string[] = [];
    if (failureCodeSet.has("NO_PLATFORM_SESSION") || failureCodeSet.has("TOKEN_DECRYPT_FAILED") || failureCodeSet.has("BROKER_NOT_CONFIGURED")) {
      hints.push("Platform session issue: reconnect broker session, then Retry failed.");
    }
    if (failureCodeSet.has("SYMBOL_NOT_MAPPED")) {
      hints.push("Symbol mapping issue: use ALL_ACTIVE_STRATEGY_SYMBOLS or CUSTOM with exact symbols.");
    }
    if (failureCodeSet.has("RATE_LIMITED") || failureCodeSet.has("BROKER_FETCH_FAILED")) {
      hints.push("Transient broker issue: retry failed symbols after a short wait.");
    }
    if (failureCodeSet.has("PARTIAL_FETCH") || failureCodeSet.has("INCOMPLETE_RANGE") || failureCodeSet.has("NO_CANDLES_RETURNED")) {
      hints.push("Incomplete range: run Repair gaps or narrow range and retry.");
    }
    return hints;
  }, [failureCodeSet]);

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
          <input type="date" value={rangeStart} onChange={(e) => setRangeStart(e.target.value)} className="rounded border border-border bg-background px-2 py-1 text-sm" />
          <input type="date" value={rangeEnd} onChange={(e) => setRangeEnd(e.target.value)} className="rounded border border-border bg-background px-2 py-1 text-sm" />
          {symbolGroup === "CUSTOM" ? (
            <input value={customSymbols} onChange={(e) => setCustomSymbols(e.target.value)} className="rounded border border-border bg-background px-2 py-1 text-sm" placeholder="CSV symbols for CUSTOM" />
          ) : (
            <div className="rounded border border-border bg-muted/30 px-2 py-1 text-xs text-muted-foreground">
              Custom symbols are used only when group = CUSTOM.
            </div>
          )}
        </div>
        <div className="mt-2 flex items-center gap-2">
          <button type="button" onClick={() => createJob.mutate()} disabled={createJob.isPending} className="rounded border border-border bg-background px-3 py-1 text-sm font-semibold disabled:opacity-50">
            Start
          </button>
          <span className="text-xs text-muted-foreground">Date-only input. Launch window uses NSE session hours (09:15-15:30 IST).</span>
        </div>
        <div className="mt-2 rounded border border-border bg-muted/20 p-2">
          <div className="text-xs font-semibold">Symbols in this launch ({groupPreview.count})</div>
          <div className="mt-1 text-xs text-muted-foreground">
            {groupPreview.symbols.length > 0 ? groupPreview.symbols.join(", ") : symbolGroup === "CUSTOM" ? "Will use custom CSV symbols." : "No preview available."}
          </div>
        </div>
      </div>

      <div className="rounded-xl border border-border bg-card p-3">
        <div className="mb-2 text-sm font-semibold">Historical CSV import (fallback)</div>
        <div className="mb-2 text-xs text-muted-foreground">
          Use this when broker lookback is limited. CSV columns: <span className="font-mono">timestamp,open,high,low,close,volume</span> and optional <span className="font-mono">symbol</span>.
        </div>
        <div className="grid gap-2 md:grid-cols-3">
          <input
            value={importSymbol}
            onChange={(e) => setImportSymbol(e.target.value.toUpperCase())}
            placeholder="Default symbol (optional if CSV has symbol column)"
            className="rounded border border-border bg-background px-2 py-1 text-sm"
          />
          <select
            value={importTimeframe}
            onChange={(e) => setImportTimeframe(e.target.value)}
            className="rounded border border-border bg-background px-2 py-1 text-sm"
          >
            {["1m", "5m", "15m", "1h", "1d"].map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
          <input
            type="file"
            accept=".csv,text/csv"
            onChange={(e) => setImportFile(e.target.files?.[0] ?? null)}
            className="rounded border border-border bg-background px-2 py-1 text-sm"
          />
        </div>
        <div className="mt-2 flex items-center gap-2">
          <button
            type="button"
            onClick={() => importCsv.mutate()}
            disabled={importCsv.isPending || !importFile}
            className="rounded border border-border bg-background px-3 py-1 text-sm font-semibold disabled:opacity-50"
          >
            Import CSV
          </button>
          <span className="text-xs text-muted-foreground">
            Import updates central candle store and coverage readiness for replay/backtest.
          </span>
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
              {guidance.length > 0 ? (
                <div className="rounded border border-amber-300 bg-amber-50 p-2">
                  <div className="text-xs font-semibold text-amber-900">Operator guidance</div>
                  <div className="mt-1 space-y-1 text-xs text-amber-900">
                    {guidance.map((g) => (
                      <div key={g}>• {g}</div>
                    ))}
                    <div>• Replay/backtest only after candles &gt; 0 and coverage is READY.</div>
                  </div>
                </div>
              ) : null}
              <div>
                <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">Symbol outcomes</div>
                <div className="max-h-56 overflow-auto rounded border border-border">
                  <table className="w-full text-xs">
                    <thead className="bg-muted/40 text-left">
                      <tr>
                        <th className="px-2 py-1">Symbol</th>
                        <th className="px-2 py-1">Status</th>
                        <th className="px-2 py-1">Auth source</th>
                        <th className="px-2 py-1">Coverage</th>
                        <th className="px-2 py-1">Candles</th>
                        <th className="px-2 py-1">Failures</th>
                        <th className="px-2 py-1">Reason</th>
                      </tr>
                    </thead>
                    <tbody>
                      {filteredSymbols.map((s) => {
                        const cov = (coverage.data ?? []).find((c) => c.symbol === s.symbol && c.timeframe === timeframe);
                        const covBadge = cov ? `${cov.completeness}/${cov.replayReadiness}` : "NOT_BACKFILLED";
                        return (
                        <tr key={s.symbol} className="border-t border-border">
                          <td className="px-2 py-1 font-semibold">{s.symbol}</td>
                          <td className="px-2 py-1">{s.status}</td>
                          <td className="px-2 py-1">{extractAuthSource(s.message)}</td>
                          <td className="px-2 py-1">{covBadge}</td>
                          <td className="px-2 py-1">{s.candlesFetched}</td>
                          <td className="px-2 py-1">{s.failureCount}</td>
                          <td className="px-2 py-1 text-muted-foreground">{s.message}</td>
                        </tr>
                      )})}
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
                          <th className="px-2 py-1">Auth source</th>
                          <th className="px-2 py-1">Latest retry</th>
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
                            <td className="px-2 py-1">{extractAuthSource(f.message)}</td>
                            <td className="px-2 py-1">{f.lastOccurredAt ? new Date(f.lastOccurredAt).toLocaleString("en-IN", { timeZone: "Asia/Kolkata", hour12: false, day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" }) : "-"}</td>
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
        <div className="mb-2 flex flex-wrap items-end justify-between gap-2">
          <div className="text-sm font-semibold">Strategy readiness audit (backtest)</div>
          <div className="flex flex-wrap gap-2">
            <input type="date" value={auditFrom} onChange={(e) => setAuditFrom(e.target.value)} className="rounded border border-border bg-background px-2 py-1 text-xs" />
            <input type="date" value={auditTo} onChange={(e) => setAuditTo(e.target.value)} className="rounded border border-border bg-background px-2 py-1 text-xs" />
          </div>
        </div>
        {strategyAudit.isLoading ? <div className="text-xs text-muted-foreground">Loading strategy audit...</div> : null}
        {strategyAudit.data ? (
          <div className="max-h-72 overflow-auto rounded border border-border">
            <table className="w-full text-xs">
              <thead className="bg-muted/40 text-left">
                <tr>
                  <th className="px-2 py-1">Strategy</th>
                  <th className="px-2 py-1">Status</th>
                  <th className="px-2 py-1">Ready symbols</th>
                  <th className="px-2 py-1">Blockers</th>
                </tr>
              </thead>
              <tbody>
                {strategyAudit.data.map((r) => (
                  <tr key={r.strategyKey} className="border-t border-border align-top">
                    <td className="px-2 py-1">
                      <div className="font-semibold">{r.displayName ?? r.strategyKey}</div>
                      <div className="text-[11px] text-muted-foreground">{r.strategyKey}</div>
                    </td>
                    <td className="px-2 py-1">{r.status}</td>
                    <td className="px-2 py-1">{r.symbolsReady}/{r.symbolsTotal}</td>
                    <td className="px-2 py-1 text-muted-foreground">
                      {r.blockers.length === 0 ? "None" : r.blockers.slice(0, 4).map((b) => `${b.symbol}:${b.state}`).join(", ")}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
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
