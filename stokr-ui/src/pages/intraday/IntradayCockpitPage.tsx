import { useEffect, useMemo, useState, type ComponentType, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { motion, AnimatePresence } from "framer-motion";
import { Link } from "react-router-dom";
import { api, parseAxiosMessage } from "../../api/client";
import {
  bareSymbol,
  formatConfidencePct,
  mismatchLabel,
  normalizeSignalRow,
  signalDirection,
  signalStrategyKey,
} from "../../lib/intradaySignals";
import { formatPnlDisplay, resolveAccountPnl } from "../../lib/moneyUtils";
import { useUiThemeStore } from "../../state/uiTheme";
import { cn } from "../../lib/utils";
import {
  Activity,
  AlertTriangle,
  ArrowRight,
  CheckCircle2,
  Landmark,
  Radio,
  RefreshCw,
  Shield,
  Sparkles,
  TrendingUp,
  Zap,
} from "lucide-react";

type Readiness = {
  overallStatus: string;
  lastValidatedAt: string;
  feed: {
    status: string;
    severity: string;
    feedLagMs: number;
    websocketState: string;
    detail: string;
  };
  broker: { status: string; tokenValid: boolean; health: string; lastSyncAt: string };
  runtime: { totalStrategies: number; runningStrategies: number; staleStrategies: number };
  session: { sessionState: string; detail: string };
  strategies: Array<{
    strategy: string;
    strategyKey: string;
    runtime: string;
    status: string;
    lastSignalTime: string;
    historicalCoverage?: { state: string; detail: string };
  }>;
  warnings: Array<{ code: string; message: string; title?: string; detail?: string }>;
  blockers: Array<{ code: string; message: string; title?: string; detail?: string }>;
  severityCounters: Record<string, number>;
};

type BrokerTruth = {
  syncState: string;
  lastSyncAt: string | null;
  syncLatencyMs: number;
  brokerConnected: boolean;
  pendingBrokerOrders: number;
  message: string;
  mismatches: Array<{ symbol: string; kind: string; brokerQty: number; internalQty: number }>;
  brokerClosedSymbols: string[];
  blockedSymbols: string[];
};

type Workstation = {
  accountSummary: {
    totalPnl: string;
    unrealizedPnl: string;
    openPositions: number;
    activeStrategies: number;
    brokerConnectionState: string;
    executionMode: string;
  };
  openPositions: Array<Record<string, unknown>>;
  latestSignals: Array<Record<string, unknown>>;
  strategyAllocations: Array<Record<string, unknown>>;
  executionGuardEvents?: Array<Record<string, unknown>>;
  executionQualityScore?: Record<string, unknown>;
  brokerTruth?: BrokerTruth;
  riskControls: { parityState: string; brokerHealth: string };
};

const stagger = {
  hidden: { opacity: 0 },
  show: { opacity: 1, transition: { staggerChildren: 0.04 } },
};

const rowIn = {
  hidden: { opacity: 0, y: 8 },
  show: { opacity: 1, y: 0, transition: { duration: 0.28, ease: [0.22, 1, 0.36, 1] } },
};

function syncTone(state: string) {
  const s = (state || "").toUpperCase();
  if (s === "VERIFIED" || s === "SYNCED" || s === "READY") return "ok";
  if (s === "RECONCILING" || s === "PENDING_SYNC" || s === "WARNING" || s === "DEGRADED") return "warn";
  return "bad";
}

function SyncBadge({ state }: { state: string }) {
  const tone = syncTone(state);
  const cls =
    tone === "ok"
      ? "border-emerald-200/80 bg-emerald-50 text-emerald-800 shadow-[inset_0_1px_0_rgba(255,255,255,0.8)]"
      : tone === "warn"
        ? "border-amber-200/80 bg-amber-50 text-amber-900 shadow-[inset_0_1px_0_rgba(255,255,255,0.8)]"
        : "border-rose-200/80 bg-rose-50 text-rose-800 shadow-[inset_0_1px_0_rgba(255,255,255,0.8)]";
  return (
    <motion.span
      layout
      className={cn(
        "inline-flex items-center rounded-full border px-2.5 py-0.5 text-[10px] font-semibold uppercase tracking-wider",
        cls,
      )}
    >
      {state || "UNKNOWN"}
    </motion.span>
  );
}

function PulseDot({ ok }: { ok: boolean }) {
  return (
    <motion.span
      animate={{ opacity: ok ? [0.45, 1, 0.45] : 1, scale: ok ? [1, 1.15, 1] : 1 }}
      transition={{ duration: 2, repeat: Infinity }}
      className={cn("inline-block h-2 w-2 rounded-full ring-2 ring-white/80", ok ? "bg-emerald-500" : "bg-rose-500")}
    />
  );
}

function fmtNum(v: unknown, digits = 2) {
  if (v == null) return "—";
  const n = typeof v === "number" ? v : parseFloat(String(v));
  if (Number.isNaN(n)) return String(v);
  return n.toFixed(digits);
}

function pnlClass(v: unknown) {
  const n = typeof v === "number" ? v : parseFloat(String(v ?? ""));
  if (Number.isNaN(n) || n === 0) return "text-slate-700";
  return n > 0 ? "text-emerald-700" : "text-rose-700";
}

function Skeleton({ className }: { className?: string }) {
  return (
    <div
      className={cn(
        "animate-pulse rounded-lg bg-gradient-to-r from-slate-200/80 via-slate-100 to-slate-200/80 bg-[length:200%_100%]",
        className,
      )}
    />
  );
}

function QueryShell({
  loading,
  error,
  onRetry,
  children,
  empty,
  isLight = true,
}: {
  loading: boolean;
  error: string | null;
  onRetry: () => void;
  children: ReactNode;
  empty?: boolean;
  isLight?: boolean;
}) {
  if (loading) {
    return (
      <div className="space-y-2 p-3">
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-3/4" />
      </div>
    );
  }
  if (error) {
    return (
      <div className="flex flex-col items-center gap-3 p-6 text-center">
        <AlertTriangle className="h-8 w-8 text-amber-600" />
        <p className="max-w-sm text-sm text-slate-600">{error}</p>
        <button
          type="button"
          onClick={onRetry}
          className="inline-flex items-center gap-2 rounded-full border border-slate-200 bg-white px-4 py-2 text-sm font-medium text-slate-800 shadow-sm transition hover:bg-slate-50"
        >
          <RefreshCw className="h-4 w-4" />
          Retry
        </button>
      </div>
    );
  }
  if (empty) {
    return (
      <p className={cn("p-8 text-center text-sm", isLight ? "text-slate-500" : "text-neutral-400")}>
        No data yet — check broker connection and strategy runtime.
      </p>
    );
  }
  return <>{children}</>;
}

export function IntradayCockpitPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const [selectedStrategy, setSelectedStrategy] = useState<string | null>(null);
  const [guardEvents, setGuardEvents] = useState<Array<Record<string, unknown>>>([]);

  const readinessQ = useQuery({
    queryKey: ["intraday-readiness"],
    queryFn: async () => (await api.get("/api/trader/intraday/readiness")).data?.data as Readiness,
    refetchInterval: 10_000,
    staleTime: 8_000,
    retry: 1,
  });

  const workstationQ = useQuery({
    queryKey: ["intraday-workstation"],
    queryFn: async () => (await api.get("/api/trader/terminal/workstation")).data?.data as Workstation,
    refetchInterval: 8_000,
    staleTime: 5_000,
    retry: 1,
  });

  const brokerTruthQ = useQuery({
    queryKey: ["intraday-broker-truth"],
    queryFn: async () => (await api.get("/api/trader/terminal/broker-truth")).data?.data as BrokerTruth,
    refetchInterval: 10_000,
    staleTime: 8_000,
    retry: 1,
  });

  const readiness = readinessQ.data;
  const ws = workstationQ.data;
  const brokerTruth = brokerTruthQ.data ?? ws?.brokerTruth;

  const readinessErr = readinessQ.isError ? parseAxiosMessage(readinessQ.error) : null;
  const wsErr = workstationQ.isError ? parseAxiosMessage(workstationQ.error) : null;
  const brokerErr = brokerTruthQ.isError ? parseAxiosMessage(brokerTruthQ.error) : null;

  const brokerConnected = brokerTruth?.brokerConnected ?? readiness?.broker?.tokenValid ?? false;
  const syncState = brokerTruth?.syncState ?? "PENDING_SYNC";

  useEffect(() => {
    const token = localStorage.getItem("accessToken");
    if (!token) return;
    const ctrl = new AbortController();
    let cancelled = false;
    const run = async () => {
      try {
        const res = await fetch("/api/trader/terminal/execution-guard/stream", {
          headers: { Authorization: `Bearer ${token}`, Accept: "text/event-stream" },
          signal: ctrl.signal,
        });
        if (!res.ok || !res.body) return;
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        while (!cancelled) {
          const chunk = await reader.read();
          if (chunk.done) break;
          buffer += decoder.decode(chunk.value, { stream: true });
          let idx;
          while ((idx = buffer.indexOf("\n\n")) >= 0) {
            const frame = buffer.slice(0, idx).trim();
            buffer = buffer.slice(idx + 2);
            if (!frame) continue;
            const data = frame.split("\n").find((l) => l.startsWith("data:"));
            if (!data) continue;
            try {
              const parsed = JSON.parse(data.slice(5).trim()) as Record<string, unknown>;
              setGuardEvents((prev) => [parsed, ...prev].slice(0, 50));
            } catch {
              /* ignore malformed frame */
            }
          }
        }
      } catch {
        /* stream closed */
      }
    };
    void run();
    return () => {
      cancelled = true;
      ctrl.abort();
    };
  }, []);

  const resolvedPnl = useMemo(
    () =>
      resolveAccountPnl({
        brokerTruth: brokerTruth as Record<string, unknown> | undefined,
        accountSummary: ws?.accountSummary,
        openPositions: ws?.openPositions,
      }),
    [brokerTruth, ws?.accountSummary, ws?.openPositions],
  );

  const filteredSignals = useMemo(() => {
    const signals = (ws?.latestSignals ?? []).map(normalizeSignalRow);
    const scoped = selectedStrategy
      ? signals.filter((s) => signalStrategyKey(s).toUpperCase().includes(selectedStrategy.toUpperCase()))
      : signals;
    return scoped.slice(0, 24);
  }, [ws?.latestSignals, selectedStrategy]);

  const recentGuardEvents = useMemo(() => {
    const persisted = ws?.executionGuardEvents ?? [];
    return [...guardEvents, ...persisted].slice(0, 12);
  }, [guardEvents, ws?.executionGuardEvents]);

  const overallTone =
    readiness?.overallStatus === "READY"
      ? "ok"
      : readiness?.overallStatus === "WARNING"
        ? "warn"
        : "bad";

  const guardCount = (ws?.executionGuardEvents?.length ?? 0) + guardEvents.length;

  return (
    <div
      className={cn(
        "relative flex min-h-0 flex-1 flex-col pb-8 font-sans",
        isLight ? "text-slate-900" : "text-neutral-100",
      )}
    >
      {!isLight ? (
        <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_80%_50%_at_10%_-10%,rgba(99,102,241,0.14),transparent),radial-gradient(ellipse_60%_40%_at_90%_0%,rgba(14,165,233,0.12),transparent)]" />
      ) : (
        <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_80%_50%_at_10%_-10%,rgba(99,102,241,0.12),transparent),radial-gradient(ellipse_60%_40%_at_90%_0%,rgba(14,165,233,0.1),transparent),radial-gradient(ellipse_50%_50%_at_50%_100%,rgba(16,185,129,0.08),transparent)]" />
      )}
      <motion.div
        aria-hidden
        className="pointer-events-none absolute -left-24 top-32 h-72 w-72 rounded-full bg-indigo-200/30 blur-3xl"
        animate={{ x: [0, 40, 0], y: [0, 20, 0] }}
        transition={{ duration: 18, repeat: Infinity, ease: "easeInOut" }}
      />
      <motion.div
        aria-hidden
        className="pointer-events-none absolute -right-16 bottom-24 h-64 w-64 rounded-full bg-sky-200/35 blur-3xl"
        animate={{ x: [0, -30, 0], y: [0, -25, 0] }}
        transition={{ duration: 22, repeat: Infinity, ease: "easeInOut" }}
      />

      <motion.header
        initial={{ opacity: 0, y: -12 }}
        animate={{ opacity: 1, y: 0 }}
        className={cn(
          "sticky top-0 z-30 border-b backdrop-blur-xl",
          isLight
            ? "border-white/60 bg-white/70 shadow-[0_8px_32px_rgba(15,23,42,0.06)]"
            : "border-neutral-800/80 bg-neutral-950/80 shadow-[0_8px_32px_rgba(0,0,0,0.35)]",
        )}
      >
        <div className="mx-auto max-w-[1600px] px-4 py-4 lg:px-8">
          <div className="flex flex-wrap items-center justify-between gap-4">
            <div className="flex items-center gap-3">
              <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-indigo-500 to-sky-500 text-white shadow-lg shadow-indigo-500/25">
                <Sparkles className="h-5 w-5" />
              </div>
              <div>
                <h1 className="text-lg font-semibold tracking-tight text-slate-900">Intraday Cockpit</h1>
                <p className="text-xs text-slate-500">Institutional execution workspace</p>
              </div>
              <SyncBadge state={syncState} />
              <span className="hidden items-center gap-1.5 text-xs text-slate-500 sm:flex">
                <PulseDot ok={brokerConnected} />
                Sync {brokerTruth?.syncLatencyMs ?? 0}ms
              </span>
            </div>
            <div className="flex flex-wrap items-stretch gap-3">
              <MetricPill label="Session" value={readiness?.session?.sessionState ?? (readinessQ.isLoading ? "…" : "—")} isLight={isLight} />
              <MetricPill label="Feed lag" value={`${readiness?.feed?.feedLagMs ?? 0}ms`} isLight={isLight} />
              <MetricPill label="Regime" value={readiness?.feed?.status ?? "—"} isLight={isLight} />
              <MetricPill
                label="Day PnL"
                value={formatPnlDisplay(resolvedPnl.mtm)}
                highlight
                pnl={resolvedPnl.mtm}
                isLight={isLight}
              />
              <MetricPill label="Risk" value={ws?.riskControls?.parityState ?? "—"} isLight={isLight} />
              <MetricPill
                label="Exec quality"
                value={String(ws?.executionQualityScore?.grade ?? ws?.executionQualityScore?.score ?? "—")}
                isLight={isLight}
              />
            </div>
          </div>

          <AnimatePresence>
            {!brokerConnected && !readinessQ.isLoading && (
              <motion.div
                initial={{ opacity: 0, height: 0 }}
                animate={{ opacity: 1, height: "auto" }}
                exit={{ opacity: 0, height: 0 }}
                className="mt-4 overflow-hidden rounded-xl border border-amber-200/80 bg-gradient-to-r from-amber-50 to-orange-50 px-4 py-3 shadow-sm"
              >
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <div className="flex items-start gap-3">
                    <Landmark className="mt-0.5 h-5 w-5 shrink-0 text-amber-700" />
                    <div>
                      <p className="text-sm font-medium text-amber-950">Broker not connected or session expired</p>
                      <p className="mt-0.5 text-xs text-amber-900/80">
                        Connect Zerodha via Kite OAuth to sync positions and clear PENDING_SYNC.
                      </p>
                    </div>
                  </div>
                  <Link
                    to="/brokers"
                    className="inline-flex items-center gap-2 rounded-full bg-amber-900 px-4 py-2 text-sm font-semibold text-amber-50 shadow-md transition hover:bg-amber-800"
                  >
                    Connect at Brokers
                    <ArrowRight className="h-4 w-4" />
                  </Link>
                </div>
              </motion.div>
            )}
          </AnimatePresence>

          <motion.div
            layout
            className={cn(
              "mt-4 flex flex-wrap items-center gap-3 rounded-xl border px-4 py-3 text-sm backdrop-blur-sm",
              overallTone === "ok" && "border-emerald-200/80 bg-emerald-50/80",
              overallTone === "warn" && "border-amber-200/80 bg-amber-50/80",
              overallTone === "bad" && "border-rose-200/80 bg-rose-50/80",
              readinessQ.isLoading && "border-slate-200/80 bg-white/60",
            )}
          >
            {readinessQ.isLoading ? (
              <Skeleton className="h-5 flex-1" />
            ) : readinessErr ? (
              <>
                <AlertTriangle className="h-4 w-4 text-amber-700" />
                <span className="text-slate-700">{readinessErr}</span>
                <button
                  type="button"
                  onClick={() => void readinessQ.refetch()}
                  className="ml-auto inline-flex items-center gap-1 rounded-full border border-slate-200 bg-white px-3 py-1 text-xs font-medium"
                >
                  <RefreshCw className="h-3 w-3" />
                  Retry
                </button>
              </>
            ) : (
              <>
                {overallTone === "ok" ? (
                  <CheckCircle2 className="h-4 w-4 text-emerald-600" />
                ) : (
                  <AlertTriangle className="h-4 w-4 text-amber-600" />
                )}
                <span className="font-medium text-slate-800">
                  {readiness?.overallStatus ?? "CHECKING"} · {readiness?.session?.detail ?? "Validating…"}
                </span>
                <span className="text-slate-500">
                  {readiness?.warnings?.length ?? 0} warn · {readiness?.blockers?.length ?? 0} block
                </span>
                <span className="ml-auto flex flex-wrap gap-3 text-[10px] font-medium uppercase tracking-wide text-slate-500">
                  <span className="flex items-center gap-1">
                    <Radio className="h-3 w-3" /> WS {readiness?.feed?.websocketState ?? "—"}
                  </span>
                  <span>
                    Runtime {readiness?.runtime?.runningStrategies ?? 0}/{readiness?.runtime?.totalStrategies ?? 0}
                  </span>
                </span>
              </>
            )}
          </motion.div>
        </div>
      </motion.header>

      <div className="relative mx-auto grid max-w-[1600px] grid-cols-12 gap-5 p-4 lg:gap-6 lg:p-8">
        <aside className="col-span-12 space-y-3 xl:col-span-3">
          <PanelTitle icon={Zap} title="Strategy rail" subtitle="Live runtime · ranked setups" isLight={isLight} />
          <GlassPanel isLight={isLight}>
            <QueryShell
              loading={readinessQ.isLoading}
              error={readinessErr}
              onRetry={() => void readinessQ.refetch()}
              empty={!readinessQ.isLoading && !readinessErr && (readiness?.strategies?.length ?? 0) === 0}
              isLight={isLight}
            >
              <motion.div variants={stagger} initial="hidden" animate="show" className="max-h-[70vh] space-y-2 overflow-y-auto p-3 pr-1">
                {(readiness?.strategies ?? []).map((st) => (
                  <motion.button
                    key={st.strategyKey}
                    type="button"
                    variants={rowIn}
                    whileHover={{ y: -2, scale: 1.01 }}
                    whileTap={{ scale: 0.99 }}
                    onClick={() => setSelectedStrategy(st.strategyKey)}
                    className={cn(
                      "w-full rounded-xl border p-3 text-left transition-shadow",
                      selectedStrategy === st.strategyKey
                        ? isLight
                          ? "border-indigo-300 bg-white shadow-lg shadow-indigo-500/10 ring-1 ring-indigo-200"
                          : "border-indigo-500/50 bg-neutral-900 shadow-lg shadow-indigo-500/20 ring-1 ring-indigo-500/30"
                        : isLight
                          ? "border-slate-200/80 bg-white/80 hover:border-slate-300 hover:shadow-md"
                          : "border-neutral-800/80 bg-neutral-900/60 hover:border-neutral-700 hover:shadow-md",
                    )}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className={cn("text-sm font-semibold", isLight ? "text-slate-900" : "text-neutral-100")}>{st.strategy}</span>
                      <SyncBadge state={st.runtime?.includes("RUN") ? "VERIFIED" : st.status} />
                    </div>
                    <p className={cn("mt-1 font-mono text-[10px]", isLight ? "text-slate-500" : "text-neutral-400")}>{st.strategyKey}</p>
                    <div className={cn("mt-2 h-1.5 overflow-hidden rounded-full", isLight ? "bg-slate-100" : "bg-neutral-800")}>
                      <motion.div
                        className="h-full rounded-full bg-gradient-to-r from-indigo-500 via-sky-500 to-emerald-400"
                        initial={{ width: 0 }}
                        animate={{ width: st.runtime?.includes("RUN") ? "78%" : "28%" }}
                        transition={{ duration: 0.6 }}
                      />
                    </div>
                    <p className={cn("mt-1.5 text-[10px]", isLight ? "text-slate-500" : "text-neutral-400")}>
                      Last signal {st.lastSignalTime ?? "—"}
                      {st.historicalCoverage?.state ? ` · ${st.historicalCoverage.state}` : ""}
                    </p>
                  </motion.button>
                ))}
              </motion.div>
            </QueryShell>
          </GlassPanel>
        </aside>

        <section className="col-span-12 space-y-3 xl:col-span-6">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <PanelTitle
              icon={Activity}
              title="Opportunity matrix"
              subtitle={
                selectedStrategy
                  ? `${filteredSignals.length} signals · ${selectedStrategy}`
                  : `${filteredSignals.length} ranked · all strategies`
              }
              isLight={isLight}
            />
            {selectedStrategy ? (
              <button
                type="button"
                onClick={() => setSelectedStrategy(null)}
                className={cn(
                  "rounded-full border px-3 py-1 text-[11px] font-medium",
                  isLight
                    ? "border-slate-200 bg-white text-slate-700 hover:bg-slate-50"
                    : "border-neutral-700 bg-neutral-900 text-neutral-200 hover:bg-neutral-800",
                )}
              >
                Clear filter
              </button>
            ) : null}
          </div>
          <GlassPanel className="overflow-hidden p-0" isLight={isLight}>
            <QueryShell
              loading={workstationQ.isLoading}
              error={wsErr}
              onRetry={() => void workstationQ.refetch()}
              empty={!workstationQ.isLoading && !wsErr && filteredSignals.length === 0}
              isLight={isLight}
            >
              <div className="max-h-[min(70vh,640px)] overflow-y-auto overflow-x-auto">
                <table className="w-full min-w-[720px] table-fixed border-collapse text-xs">
                  <thead
                    className={cn(
                      "sticky top-0 z-10 border-b text-[10px] font-semibold uppercase tracking-wider",
                      isLight
                        ? "border-slate-200/80 bg-slate-50/95 text-slate-500"
                        : "border-neutral-800 bg-neutral-900/95 text-neutral-400",
                    )}
                  >
                    <tr>
                      <th className="w-[14%] px-3 py-2.5 text-left">Symbol</th>
                      <th className="w-[8%] px-2 py-2.5 text-left">Dir</th>
                      <th className="w-[22%] px-2 py-2.5 text-left">Strategy</th>
                      <th className="w-[12%] px-2 py-2.5 text-right">Entry</th>
                      <th className="w-[11%] px-2 py-2.5 text-right">SL</th>
                      <th className="w-[11%] px-2 py-2.5 text-right">Target</th>
                      <th className="w-[8%] px-2 py-2.5 text-right">RR</th>
                      <th className="w-[14%] px-3 py-2.5 text-right">Conf</th>
                    </tr>
                  </thead>
                  <tbody>
                    {filteredSignals.map((row, i) => {
                      const dir = signalDirection(row);
                      const dirTone =
                        dir === "BUY" ? "text-emerald-600 dark:text-emerald-400" : dir === "SELL" ? "text-rose-600 dark:text-rose-400" : "text-slate-500";
                      return (
                        <motion.tr
                          key={String(row.id ?? row.signalId ?? i)}
                          variants={rowIn}
                          initial="hidden"
                          animate="show"
                          className={cn(
                            "border-b transition-colors",
                            isLight
                              ? "border-slate-100 hover:bg-indigo-50/50"
                              : "border-neutral-800/80 hover:bg-indigo-500/10",
                          )}
                        >
                          <td className="truncate px-3 py-2.5 font-mono font-medium text-indigo-600 dark:text-indigo-300">
                            {bareSymbol(row.symbol)}
                          </td>
                          <td className={cn("px-2 py-2.5 font-semibold", dirTone)}>{dir}</td>
                          <td className="truncate px-2 py-2.5 text-slate-500 dark:text-neutral-400" title={signalStrategyKey(row)}>
                            {signalStrategyKey(row)}
                          </td>
                          <td className="px-2 py-2.5 text-right font-mono">{fmtNum(row.entryReferencePrice)}</td>
                          <td className="px-2 py-2.5 text-right font-mono">{fmtNum(row.stopPrice)}</td>
                          <td className="px-2 py-2.5 text-right font-mono">{fmtNum(row.targetPrice)}</td>
                          <td className="px-2 py-2.5 text-right font-mono">{fmtNum(row.riskReward)}</td>
                          <td className="px-3 py-2.5 text-right">
                            <span
                              className={cn(
                                "inline-block rounded-md px-1.5 py-0.5 font-medium",
                                isLight ? "bg-slate-100 text-slate-700" : "bg-neutral-800 text-neutral-200",
                              )}
                            >
                              {formatConfidencePct(row.confidenceScore ?? row.confidence)}
                            </span>
                          </td>
                        </motion.tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </QueryShell>
          </GlassPanel>
        </section>

        <aside className="col-span-12 space-y-4 xl:col-span-3">
          <PanelTitle icon={Shield} title="Execution intel" subtitle="Broker truth · guard stream" isLight={isLight} />
          <GlassPanel className="space-y-3 p-4" isLight={isLight}>
            <QueryShell
              loading={brokerTruthQ.isLoading && !brokerTruth}
              error={brokerErr}
              onRetry={() => void brokerTruthQ.refetch()}
              isLight={isLight}
            >
              <div className="flex items-center justify-between text-xs">
                <span className={isLight ? "text-slate-500" : "text-neutral-400"}>Sync state</span>
                <SyncBadge state={syncState} />
              </div>
              <p className={cn("text-[11px] leading-relaxed", isLight ? "text-slate-600" : "text-neutral-300")}>
                {brokerTruth?.message || "Awaiting broker reconciliation…"}
              </p>
              {brokerTruth?.mismatches?.length ? (
                <div className="space-y-2">
                  <p className={cn("text-[10px] font-semibold uppercase tracking-wide", isLight ? "text-amber-800" : "text-amber-300")}>
                    {brokerTruth.mismatches.length} reconciliation item(s)
                  </p>
                  <ul
                    className={cn(
                      "max-h-40 space-y-1.5 overflow-y-auto rounded-lg border p-2.5 text-[11px]",
                      isLight ? "border-amber-200/60 bg-amber-50/80 text-amber-950" : "border-amber-500/30 bg-amber-500/10 text-amber-100",
                    )}
                  >
                    {brokerTruth.mismatches.slice(0, 8).map((m) => (
                      <li key={`${m.kind}-${m.symbol}`} className="flex flex-col gap-0.5">
                        <span className="font-medium">{bareSymbol(m.symbol)}</span>
                        <span className={isLight ? "text-amber-900/80" : "text-amber-100/80"}>
                          {mismatchLabel(m.kind)} · broker {m.brokerQty} vs internal {m.internalQty}
                        </span>
                      </li>
                    ))}
                  </ul>
                </div>
              ) : (
                <p className={cn("rounded-lg border px-3 py-2 text-[11px]", isLight ? "border-emerald-200 bg-emerald-50 text-emerald-800" : "border-emerald-500/30 bg-emerald-500/10 text-emerald-200")}>
                  Positions match broker ledger.
                </p>
              )}
              <div className={cn("text-xs", isLight ? "text-slate-500" : "text-neutral-400")}>
                Pending broker orders:{" "}
                <span className={cn("font-semibold", isLight ? "text-slate-800" : "text-neutral-100")}>
                  {brokerTruth?.pendingBrokerOrders ?? 0}
                </span>
              </div>
              <div
                className={cn(
                  "rounded-lg border p-2.5",
                  isLight ? "border-indigo-100 bg-indigo-50/50 text-indigo-900" : "border-indigo-500/30 bg-indigo-500/10 text-indigo-100",
                )}
              >
                <p className="text-[11px] font-medium">
                  Guard events · <span className="font-semibold">{guardCount}</span>
                </p>
                {recentGuardEvents.length > 0 ? (
                  <ul className="mt-2 max-h-36 space-y-1 overflow-y-auto text-[10px] opacity-90">
                    {recentGuardEvents.map((ev, idx) => (
                      <li key={String(ev.id ?? ev.eventId ?? idx)} className="truncate">
                        {String(ev.title ?? ev.code ?? ev.kind ?? "Guard")} · {String(ev.message ?? ev.detail ?? "—")}
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="mt-1 text-[10px] opacity-80">No guard events in the last stream window.</p>
                )}
              </div>
            </QueryShell>
          </GlassPanel>

          <PanelTitle icon={TrendingUp} title="Live positions" subtitle="Broker-verified quantity" isLight={isLight} />
          <GlassPanel isLight={isLight}>
            <QueryShell
              loading={workstationQ.isLoading}
              error={wsErr}
              onRetry={() => void workstationQ.refetch()}
              empty={!workstationQ.isLoading && !wsErr && (ws?.openPositions?.length ?? 0) === 0}
              isLight={isLight}
            >
              <motion.div variants={stagger} initial="hidden" animate="show" className="max-h-[40vh] space-y-2 overflow-y-auto p-3">
                {(ws?.openPositions ?? []).map((p) => {
                  const sym = String(p.symbol ?? "");
                  const sync = String(p.brokerSyncState ?? syncState);
                  return (
                    <motion.div
                      key={sym}
                      variants={rowIn}
                      layout
                      className={cn(
                        "rounded-xl border p-3 shadow-sm",
                        isLight ? "border-slate-200/80 bg-white/90" : "border-neutral-800/80 bg-neutral-900/70",
                      )}
                    >
                      <div className="flex items-center justify-between">
                        <span className={cn("font-mono text-sm font-medium", isLight ? "text-slate-900" : "text-neutral-100")}>
                          {bareSymbol(sym)}
                        </span>
                        <SyncBadge state={sync} />
                      </div>
                      <div className={cn("mt-2 grid grid-cols-2 gap-1 text-[11px]", isLight ? "text-slate-500" : "text-neutral-400")}>
                        <span>Qty {fmtNum(p.qty, 0)}</span>
                        <span>Broker {fmtNum(p.brokerQty ?? p.qty, 0)}</span>
                        <span className={pnlClass(p.mtmPnl)}>MTM {fmtNum(p.mtmPnl)}</span>
                        <span>{String(p.side ?? "")}</span>
                      </div>
                    </motion.div>
                  );
                })}
              </motion.div>
            </QueryShell>
          </GlassPanel>
        </aside>
      </div>
    </div>
  );
}

function GlassPanel({ children, className, isLight = true }: { children: ReactNode; className?: string; isLight?: boolean }) {
  return (
    <div
      className={cn(
        "rounded-2xl border backdrop-blur-xl",
        isLight
          ? "border-white/80 bg-white/65 shadow-[0_8px_40px_rgba(15,23,42,0.06)]"
          : "border-neutral-800/80 bg-neutral-900/55 shadow-[0_8px_40px_rgba(0,0,0,0.35)]",
        className,
      )}
    >
      {children}
    </div>
  );
}

function PanelTitle({
  icon: Icon,
  title,
  subtitle,
  isLight = true,
}: {
  icon: ComponentType<{ className?: string }>;
  title: string;
  subtitle: string;
  isLight?: boolean;
}) {
  return (
    <div className="flex items-center gap-2 px-0.5">
      <div
        className={cn(
          "flex h-8 w-8 items-center justify-center rounded-lg shadow-sm ring-1",
          isLight ? "bg-white/80 text-indigo-600 ring-slate-200/80" : "bg-neutral-900/80 text-indigo-300 ring-neutral-700/80",
        )}
      >
        <Icon className="h-4 w-4" />
      </div>
      <div>
        <h2 className={cn("text-sm font-semibold", isLight ? "text-slate-900" : "text-neutral-100")}>{title}</h2>
        <p className={cn("text-[11px]", isLight ? "text-slate-500" : "text-neutral-400")}>{subtitle}</p>
      </div>
    </div>
  );
}

function MetricPill({
  label,
  value,
  highlight,
  pnl,
  isLight = true,
}: {
  label: string;
  value: string;
  highlight?: boolean;
  pnl?: unknown;
  isLight?: boolean;
}) {
  return (
    <div
      className={cn(
        "min-w-[72px] rounded-xl border px-3 py-2 shadow-sm backdrop-blur-sm",
        isLight ? "border-white/90 bg-white/80" : "border-neutral-800 bg-neutral-900/80",
      )}
    >
      <span className={cn("text-[10px] font-medium uppercase tracking-wider", isLight ? "text-slate-500" : "text-neutral-400")}>
        {label}
      </span>
      <span
        className={cn(
          "mt-0.5 block font-mono text-sm font-semibold",
          highlight ? pnlClass(pnl) : isLight ? "text-slate-800" : "text-neutral-100",
        )}
      >
        {value}
      </span>
    </div>
  );
}
