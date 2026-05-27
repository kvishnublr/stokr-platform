import { useEffect, useMemo, useState, type ComponentType, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { motion, AnimatePresence } from "framer-motion";
import { Link } from "react-router-dom";
import { api, parseAxiosMessage } from "../../api/client";
import {
  bareSymbol,
  formatConfidencePct,
  mismatchHint,
  mismatchLabel,
  normalizeSignalRow,
  signalDirection,
  signalStrategyKey,
} from "../../lib/intradaySignals";
import {
  brokerPositionPollMs,
  formatBrokerSyncAge,
  isBrokerSyncPulseLive,
  useBrokerPositionSync,
} from "../../lib/hooks/useBrokerPositionSync";
import { formatInr, formatPnlDisplay, parseMoney, resolveAccountPnl } from "../../lib/moneyUtils";
import { useSessionStore } from "../../state/session";
import { useUiThemeStore } from "../../state/uiTheme";
import { cn } from "../../lib/utils";
import {
  Activity,
  AlertTriangle,
  ArrowRight,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
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
  executionGuardEvents?: Array<Record<string, unknown>>;
  executionQualityScore?: Record<string, unknown>;
  brokerTruth?: BrokerTruth;
  riskControls: { parityState: string; brokerHealth: string };
};

type WatchRow = { symbol?: string; price?: unknown; ltp?: unknown; changePct?: unknown };

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

function SyncBadge({ state, isLight = true }: { state: string; isLight?: boolean }) {
  const tone = syncTone(state);
  const cls =
    tone === "ok"
      ? isLight
        ? "border-emerald-200/80 bg-emerald-50 text-emerald-800"
        : "border-emerald-500/40 bg-emerald-500/15 text-emerald-200"
      : tone === "warn"
        ? isLight
          ? "border-amber-200/80 bg-amber-50 text-amber-900"
          : "border-amber-500/40 bg-amber-500/15 text-amber-200"
        : isLight
          ? "border-rose-200/80 bg-rose-50 text-rose-800"
          : "border-rose-500/40 bg-rose-500/15 text-rose-200";
  return (
    <motion.span layout className={cn("inline-flex items-center rounded-full border px-2.5 py-0.5 text-[10px] font-semibold uppercase tracking-wider", cls)}>
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

function pnlClass(v: unknown, isLight = true) {
  const n = typeof v === "number" ? v : parseFloat(String(v ?? ""));
  if (Number.isNaN(n) || n === 0) return isLight ? "text-slate-700" : "text-neutral-200";
  return n > 0 ? "text-emerald-600" : "text-rose-600";
}

function Skeleton({ className }: { className?: string }) {
  return <div className={cn("animate-pulse rounded-lg bg-gradient-to-r from-slate-200/80 via-slate-100 to-slate-200/80 bg-[length:200%_100%]", className)} />;
}

function QueryShell({
  loading,
  error,
  onRetry,
  children,
  empty,
  emptyMessage = "No data yet — check broker connection and strategy runtime.",
  isLight = true,
}: {
  loading: boolean;
  error: string | null;
  onRetry: () => void;
  children: ReactNode;
  empty?: boolean;
  emptyMessage?: string;
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
        <button type="button" onClick={onRetry} className="inline-flex items-center gap-2 rounded-full border border-slate-200 bg-white px-4 py-2 text-sm font-medium text-slate-800 shadow-sm transition hover:bg-slate-50">
          <RefreshCw className="h-4 w-4" />
          Retry
        </button>
      </div>
    );
  }
  if (empty) {
    return <p className={cn("p-8 text-center text-sm", isLight ? "text-slate-500" : "text-neutral-400")}>{emptyMessage}</p>;
  }
  return <>{children}</>;
}

function ZoneHeader({
  index,
  title,
  subtitle,
  icon: Icon,
  isLight,
  action,
}: {
  index: string;
  title: string;
  subtitle: string;
  icon: ComponentType<{ className?: string }>;
  isLight: boolean;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-2">
      <div className="flex items-center gap-3">
        <div
          className={cn(
            "flex h-10 w-10 flex-col items-center justify-center rounded-xl border text-[10px] font-bold",
            isLight ? "border-indigo-200 bg-gradient-to-br from-indigo-50 to-sky-50 text-indigo-700" : "border-indigo-500/30 bg-indigo-500/10 text-indigo-200",
          )}
        >
          <Icon className="h-4 w-4" />
          <span className="mt-0.5 opacity-70">{index}</span>
        </div>
        <div>
          <h2 className={cn("text-sm font-semibold tracking-tight", isLight ? "text-slate-900" : "text-neutral-100")}>{title}</h2>
          <p className={cn("text-[11px]", isLight ? "text-slate-500" : "text-neutral-400")}>{subtitle}</p>
        </div>
      </div>
      {action}
    </div>
  );
}

export function IntradayCockpitPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const accessToken = useSessionStore((s) => s.accessToken);
  const userId = useSessionStore((s) => s.userId);
  const [selectedStrategy, setSelectedStrategy] = useState<string | null>(null);
  const [guardEvents, setGuardEvents] = useState<Array<Record<string, unknown>>>([]);
  const [integrityOpen, setIntegrityOpen] = useState(false);
  const [integrityTab, setIntegrityTab] = useState<"reconcile" | "guards">("reconcile");

  useBrokerPositionSync(accessToken, userId, !!accessToken);

  const readinessQ = useQuery({
    queryKey: ["intraday-readiness"],
    queryFn: async () => (await api.get("/api/trader/intraday/readiness")).data?.data as Readiness,
    refetchInterval: 10_000,
    staleTime: 8_000,
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
  const brokerTruthPreview = brokerTruthQ.data;
  const brokerConnected = brokerTruthPreview?.brokerConnected ?? readiness?.broker?.tokenValid ?? false;
  const positionPollMs = brokerPositionPollMs(brokerConnected);

  const workstationQ = useQuery({
    queryKey: ["intraday-workstation"],
    queryFn: async () => (await api.get("/api/trader/terminal/workstation")).data?.data as Workstation,
    refetchInterval: positionPollMs,
    staleTime: 3_000,
    retry: 1,
  });

  const watchQ = useQuery({
    queryKey: ["intraday-market-watch"],
    queryFn: async () => {
      const res = await api.get("/api/trader/terminal/market/watch");
      return (Array.isArray(res.data?.data) ? res.data.data : []) as WatchRow[];
    },
    refetchInterval: positionPollMs,
    staleTime: 1_500,
    enabled: !!accessToken,
  });

  const ws = workstationQ.data;
  const brokerTruth = brokerTruthPreview ?? ws?.brokerTruth;
  const syncState = brokerTruth?.syncState ?? "PENDING_SYNC";
  const mismatchCount = brokerTruth?.mismatches?.length ?? 0;
  const syncPulseLive = isBrokerSyncPulseLive(brokerTruth?.lastSyncAt);
  const syncAge = formatBrokerSyncAge(brokerTruth?.lastSyncAt);

  useEffect(() => {
    if (mismatchCount > 0) setIntegrityOpen(true);
  }, [mismatchCount]);

  const readinessErr = readinessQ.isError ? parseAxiosMessage(readinessQ.error) : null;
  const wsErr = workstationQ.isError ? parseAxiosMessage(workstationQ.error) : null;
  const brokerErr = brokerTruthQ.isError ? parseAxiosMessage(brokerTruthQ.error) : null;

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

  const ltpMap = useMemo(() => {
    const map = new Map<string, number>();
    for (const row of watchQ.data ?? []) {
      const sym = bareSymbol(row.symbol).toUpperCase();
      const price = parseMoney(row.price ?? row.ltp);
      if (sym && price != null) map.set(sym, price);
    }
    for (const p of ws?.openPositions ?? []) {
      const sym = bareSymbol(p.symbol).toUpperCase();
      const ltp = parseMoney(p.ltp);
      if (sym && ltp != null) map.set(sym, ltp);
    }
    return map;
  }, [watchQ.data, ws?.openPositions]);

  const resolveLtp = (symbol: unknown) => ltpMap.get(bareSymbol(symbol).toUpperCase()) ?? null;

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
    readiness?.overallStatus === "READY" ? "ok" : readiness?.overallStatus === "WARNING" ? "warn" : "bad";

  const openPositions = ws?.openPositions ?? [];

  return (
    <div className={cn("relative flex min-h-0 flex-1 flex-col pb-28 font-sans", isLight ? "text-slate-900" : "text-neutral-100")}>
      <div
        className={cn(
          "pointer-events-none absolute inset-0",
          isLight
            ? "bg-[radial-gradient(ellipse_80%_50%_at_0%_0%,rgba(99,102,241,0.1),transparent),radial-gradient(ellipse_60%_40%_at_100%_0%,rgba(14,165,233,0.08),transparent)]"
            : "bg-[radial-gradient(ellipse_80%_50%_at_0%_0%,rgba(99,102,241,0.14),transparent),radial-gradient(ellipse_60%_40%_at_100%_0%,rgba(14,165,233,0.1),transparent)]",
        )}
      />

      {/* Command deck */}
      <motion.header
        initial={{ opacity: 0, y: -12 }}
        animate={{ opacity: 1, y: 0 }}
        className={cn(
          "sticky top-0 z-30 border-b backdrop-blur-xl",
          isLight ? "border-white/60 bg-white/75 shadow-[0_8px_32px_rgba(15,23,42,0.06)]" : "border-neutral-800/80 bg-neutral-950/85",
        )}
      >
        <div className="mx-auto max-w-[1680px] px-4 py-4 lg:px-8">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div className="flex min-w-0 items-start gap-3">
              <div className="flex h-11 w-11 items-center justify-center rounded-2xl bg-gradient-to-br from-indigo-600 via-violet-600 to-sky-500 text-white shadow-lg shadow-indigo-500/30">
                <Sparkles className="h-5 w-5" />
              </div>
              <div>
                <div className="flex flex-wrap items-center gap-2">
                  <h1 className={cn("text-xl font-semibold tracking-tight", isLight ? "text-slate-900" : "text-white")}>Intraday Terminal</h1>
                  <SyncBadge state={syncState} isLight={isLight} />
                  <span className={cn("inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-[10px] font-medium", syncPulseLive ? "bg-emerald-500/15 text-emerald-700" : isLight ? "bg-slate-100 text-slate-600" : "bg-neutral-800 text-neutral-400")}>
                    <PulseDot ok={syncPulseLive} />
                    {syncPulseLive ? "Live sync" : syncAge ?? "Awaiting sync"}
                    {brokerTruth?.syncLatencyMs != null ? ` · ${brokerTruth.syncLatencyMs}ms` : ""}
                  </span>
                </div>
                <p className={cn("mt-0.5 text-xs", isLight ? "text-slate-500" : "text-neutral-400")}>
                  Live signals · open book · broker-verified execution
                </p>
              </div>
            </div>

            <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-6">
              <MetricPill label="Session" value={readiness?.session?.sessionState ?? "—"} isLight={isLight} />
              <MetricPill label="Feed" value={`${readiness?.feed?.feedLagMs ?? 0}ms`} isLight={isLight} />
              <MetricPill label="Mode" value={ws?.accountSummary?.executionMode ?? "—"} isLight={isLight} />
              <MetricPill label="Day P&L" value={formatPnlDisplay(resolvedPnl.mtm)} highlight pnl={resolvedPnl.mtm} isLight={isLight} />
              <MetricPill label="Open" value={String(openPositions.length)} isLight={isLight} />
              <MetricPill label="Quality" value={String(ws?.executionQualityScore?.grade ?? ws?.executionQualityScore?.score ?? "—")} isLight={isLight} />
            </div>
          </div>

          <AnimatePresence>
            {!brokerConnected && !readinessQ.isLoading && (
              <motion.div initial={{ opacity: 0, height: 0 }} animate={{ opacity: 1, height: "auto" }} exit={{ opacity: 0, height: 0 }} className="mt-4 overflow-hidden rounded-xl border border-amber-200/80 bg-gradient-to-r from-amber-50 to-orange-50 px-4 py-3">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <div className="flex items-start gap-3">
                    <Landmark className="mt-0.5 h-5 w-5 shrink-0 text-amber-700" />
                    <div>
                      <p className="text-sm font-medium text-amber-950">Broker session required for live LTP and position sync</p>
                      <p className="mt-0.5 text-xs text-amber-900/80">Connect Zerodha to refresh prices and reconcile the open book.</p>
                    </div>
                  </div>
                  <Link to="/brokers" className="inline-flex items-center gap-2 rounded-full bg-amber-900 px-4 py-2 text-sm font-semibold text-amber-50 shadow-md transition hover:bg-amber-800">
                    Connect broker
                    <ArrowRight className="h-4 w-4" />
                  </Link>
                </div>
              </motion.div>
            )}
          </AnimatePresence>

          <motion.div
            layout
            className={cn(
              "mt-4 flex flex-wrap items-center gap-3 rounded-xl border px-4 py-2.5 text-sm",
              overallTone === "ok" && (isLight ? "border-emerald-200/80 bg-emerald-50/70" : "border-emerald-500/30 bg-emerald-500/10"),
              overallTone === "warn" && (isLight ? "border-amber-200/80 bg-amber-50/70" : "border-amber-500/30 bg-amber-500/10"),
              overallTone === "bad" && (isLight ? "border-rose-200/80 bg-rose-50/70" : "border-rose-500/30 bg-rose-500/10"),
            )}
          >
            {readinessQ.isLoading ? (
              <Skeleton className="h-5 flex-1" />
            ) : readinessErr ? (
              <>
                <AlertTriangle className="h-4 w-4 text-amber-700" />
                <span>{readinessErr}</span>
                <button type="button" onClick={() => void readinessQ.refetch()} className="ml-auto inline-flex items-center gap-1 rounded-full border px-3 py-1 text-xs font-medium">
                  <RefreshCw className="h-3 w-3" />
                  Retry
                </button>
              </>
            ) : (
              <>
                {overallTone === "ok" ? <CheckCircle2 className="h-4 w-4 text-emerald-600" /> : <AlertTriangle className="h-4 w-4 text-amber-600" />}
                <span className={cn("font-medium", isLight ? "text-slate-800" : "text-neutral-100")}>
                  {readiness?.overallStatus ?? "CHECKING"} — {readiness?.session?.detail ?? "Validating market session"}
                </span>
                <span className={cn("text-xs", isLight ? "text-slate-500" : "text-neutral-400")}>
                  {readiness?.runtime?.runningStrategies ?? 0}/{readiness?.runtime?.totalStrategies ?? 0} strategies · WS {readiness?.feed?.websocketState ?? "—"}
                </span>
              </>
            )}
          </motion.div>
        </div>
      </motion.header>

      {/* Main workspace */}
      <div className="relative mx-auto grid max-w-[1680px] grid-cols-12 gap-5 p-4 lg:gap-6 lg:p-8">
        {/* Zone 01 — Strategy scanner */}
        <aside className="col-span-12 space-y-3 xl:col-span-3">
          <ZoneHeader index="01" title="Strategy scanner" subtitle="Tap to filter the signal board" icon={Zap} isLight={isLight} />
          <GlassPanel isLight={isLight}>
            <QueryShell loading={readinessQ.isLoading} error={readinessErr} onRetry={() => void readinessQ.refetch()} empty={!readinessQ.isLoading && !readinessErr && (readiness?.strategies?.length ?? 0) === 0} isLight={isLight}>
              <motion.div variants={stagger} initial="hidden" animate="show" className="max-h-[min(72vh,720px)] space-y-2 overflow-y-auto p-3">
                <button
                  type="button"
                  onClick={() => setSelectedStrategy(null)}
                  className={cn(
                    "w-full rounded-xl border px-3 py-2.5 text-left text-xs font-semibold transition",
                    !selectedStrategy
                      ? isLight
                        ? "border-indigo-300 bg-indigo-50 text-indigo-900"
                        : "border-indigo-500/40 bg-indigo-500/15 text-indigo-100"
                      : isLight
                        ? "border-slate-200 bg-white/80 text-slate-600 hover:border-slate-300"
                        : "border-neutral-800 bg-neutral-900/60 text-neutral-400 hover:border-neutral-700",
                  )}
                >
                  All strategies
                </button>
                {(readiness?.strategies ?? []).map((st) => {
                  const active = selectedStrategy === st.strategyKey;
                  const running = st.runtime?.includes("RUN");
                  return (
                    <motion.button
                      key={st.strategyKey}
                      type="button"
                      variants={rowIn}
                      whileHover={{ y: -1 }}
                      whileTap={{ scale: 0.995 }}
                      onClick={() => setSelectedStrategy(st.strategyKey)}
                      className={cn(
                        "w-full rounded-xl border p-3 text-left transition",
                        active
                          ? isLight
                            ? "border-indigo-300 bg-white shadow-md ring-1 ring-indigo-200"
                            : "border-indigo-500/50 bg-neutral-900 ring-1 ring-indigo-500/30"
                          : isLight
                            ? "border-slate-200/80 bg-white/80 hover:border-slate-300"
                            : "border-neutral-800/80 bg-neutral-900/60 hover:border-neutral-700",
                      )}
                    >
                      <div className="flex items-center justify-between gap-2">
                        <span className={cn("truncate text-sm font-semibold", isLight ? "text-slate-900" : "text-neutral-100")}>{st.strategy}</span>
                        <SyncBadge state={running ? "RUNNING" : st.status} isLight={isLight} />
                      </div>
                      <p className={cn("mt-1 font-mono text-[10px]", isLight ? "text-slate-500" : "text-neutral-400")}>{st.strategyKey}</p>
                      <div className={cn("mt-2 h-1 overflow-hidden rounded-full", isLight ? "bg-slate-100" : "bg-neutral-800")}>
                        <motion.div className={cn("h-full rounded-full", running ? "bg-gradient-to-r from-emerald-500 to-sky-500" : "bg-slate-300")} animate={{ width: running ? "85%" : "30%" }} transition={{ duration: 0.5 }} />
                      </div>
                      <p className={cn("mt-1.5 text-[10px]", isLight ? "text-slate-500" : "text-neutral-400")}>
                        Last signal {st.lastSignalTime ?? "—"}
                      </p>
                    </motion.button>
                  );
                })}
              </motion.div>
            </QueryShell>
          </GlassPanel>
        </aside>

        {/* Zone 02 — Signal board */}
        <section className="col-span-12 space-y-3 xl:col-span-5">
          <ZoneHeader
            index="02"
            title="Signal board"
            subtitle={selectedStrategy ? `${filteredSignals.length} signals · ${selectedStrategy}` : `${filteredSignals.length} live opportunities`}
            icon={Activity}
            isLight={isLight}
            action={
              selectedStrategy ? (
                <button type="button" onClick={() => setSelectedStrategy(null)} className={cn("rounded-full border px-3 py-1 text-[11px] font-medium", isLight ? "border-slate-200 bg-white text-slate-700" : "border-neutral-700 bg-neutral-900 text-neutral-200")}>
                  Clear filter
                </button>
              ) : null
            }
          />
          <GlassPanel className="overflow-hidden p-0" isLight={isLight}>
            <QueryShell loading={workstationQ.isLoading} error={wsErr} onRetry={() => void workstationQ.refetch()} empty={!workstationQ.isLoading && !wsErr && filteredSignals.length === 0} emptyMessage="No signals in this window — strategies may still be scanning." isLight={isLight}>
              <div className="max-h-[min(72vh,720px)] overflow-auto">
                <table className="w-full min-w-[680px] border-collapse text-xs">
                  <thead className={cn("sticky top-0 z-10 border-b text-[10px] font-semibold uppercase tracking-wider", isLight ? "border-slate-200 bg-slate-50/95 text-slate-500" : "border-neutral-800 bg-neutral-900/95 text-neutral-400")}>
                    <tr>
                      <th className="px-3 py-2.5 text-left">Symbol</th>
                      <th className="px-2 py-2.5 text-left">Side</th>
                      <th className="px-2 py-2.5 text-right">LTP</th>
                      <th className="px-2 py-2.5 text-right">Entry</th>
                      <th className="px-2 py-2.5 text-right">SL</th>
                      <th className="px-2 py-2.5 text-right">Target</th>
                      <th className="px-2 py-2.5 text-right">RR</th>
                      <th className="px-3 py-2.5 text-right">Conf</th>
                    </tr>
                  </thead>
                  <tbody>
                    {filteredSignals.map((row, i) => {
                      const dir = signalDirection(row);
                      const ltp = resolveLtp(row.symbol);
                      const entry = parseMoney(row.entryReferencePrice);
                      const ltpVsEntry =
                        ltp != null && entry != null && entry !== 0 ? ((ltp - entry) / entry) * 100 : null;
                      return (
                        <motion.tr
                          key={String(row.id ?? row.signalId ?? i)}
                          variants={rowIn}
                          initial="hidden"
                          animate="show"
                          className={cn("border-b transition-colors", isLight ? "border-slate-100 hover:bg-indigo-50/40" : "border-neutral-800 hover:bg-indigo-500/10")}
                        >
                          <td className="px-3 py-2.5">
                            <div className="font-mono font-semibold text-indigo-600 dark:text-indigo-300">{bareSymbol(row.symbol)}</div>
                            <div className={cn("truncate text-[10px]", isLight ? "text-slate-400" : "text-neutral-500")}>{signalStrategyKey(row)}</div>
                          </td>
                          <td className={cn("px-2 py-2.5 font-bold", dir === "BUY" ? "text-emerald-600" : dir === "SELL" ? "text-rose-600" : "text-slate-400")}>{dir}</td>
                          <td className="px-2 py-2.5 text-right">
                            <div className="font-mono font-semibold tabular-nums">{formatInr(ltp)}</div>
                            {ltpVsEntry != null ? (
                              <div className={cn("text-[10px] tabular-nums", ltpVsEntry >= 0 ? "text-emerald-600" : "text-rose-600")}>
                                {ltpVsEntry >= 0 ? "+" : ""}
                                {ltpVsEntry.toFixed(2)}%
                              </div>
                            ) : null}
                          </td>
                          <td className="px-2 py-2.5 text-right font-mono tabular-nums">{fmtNum(row.entryReferencePrice)}</td>
                          <td className="px-2 py-2.5 text-right font-mono tabular-nums text-rose-600">{fmtNum(row.stopPrice)}</td>
                          <td className="px-2 py-2.5 text-right font-mono tabular-nums text-emerald-600">{fmtNum(row.targetPrice)}</td>
                          <td className="px-2 py-2.5 text-right font-mono tabular-nums">{fmtNum(row.riskReward)}</td>
                          <td className="px-3 py-2.5 text-right">
                            <span className={cn("inline-block rounded-md px-1.5 py-0.5 font-medium", isLight ? "bg-slate-100 text-slate-700" : "bg-neutral-800 text-neutral-200")}>
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

        {/* Zone 03 — Live book */}
        <section className="col-span-12 space-y-3 xl:col-span-4">
          <ZoneHeader index="03" title="Live book" subtitle="Open positions · LTP refreshed every 2s" icon={TrendingUp} isLight={isLight} />
          <GlassPanel className="overflow-hidden p-0" isLight={isLight}>
            <QueryShell
              loading={workstationQ.isLoading}
              error={wsErr}
              onRetry={() => void workstationQ.refetch()}
              empty={!workstationQ.isLoading && !wsErr && openPositions.length === 0}
              emptyMessage="No open positions — signals and fills will appear here."
              isLight={isLight}
            >
              <div className="max-h-[min(72vh,720px)] overflow-auto">
                <table className="w-full min-w-[420px] border-collapse text-xs">
                  <thead className={cn("sticky top-0 z-10 border-b text-[10px] font-semibold uppercase tracking-wider", isLight ? "border-slate-200 bg-slate-50/95 text-slate-500" : "border-neutral-800 bg-neutral-900/95 text-neutral-400")}>
                    <tr>
                      <th className="px-3 py-2.5 text-left">Symbol</th>
                      <th className="px-2 py-2.5 text-right">Qty</th>
                      <th className="px-2 py-2.5 text-right">Avg</th>
                      <th className="px-2 py-2.5 text-right">LTP</th>
                      <th className="px-3 py-2.5 text-right">MTM</th>
                    </tr>
                  </thead>
                  <tbody>
                    {openPositions.map((p, i) => {
                      const sym = String(p.symbol ?? "");
                      const ltp = resolveLtp(sym) ?? parseMoney(p.ltp);
                      const mtm = parseMoney(p.mtmPnl ?? p.unrealizedPnl);
                      const sync = String(p.brokerSyncState ?? syncState);
                      return (
                        <motion.tr
                          key={sym || i}
                          variants={rowIn}
                          initial="hidden"
                          animate="show"
                          className={cn("border-b", isLight ? "border-slate-100 hover:bg-emerald-50/30" : "border-neutral-800 hover:bg-emerald-500/10")}
                        >
                          <td className="px-3 py-2.5">
                            <div className="flex items-center gap-2">
                              <span className="font-mono font-semibold">{bareSymbol(sym)}</span>
                              <SyncBadge state={sync} isLight={isLight} />
                            </div>
                            <div className={cn("text-[10px] uppercase", isLight ? "text-slate-400" : "text-neutral-500")}>{String(p.side ?? "")}</div>
                          </td>
                          <td className="px-2 py-2.5 text-right font-mono tabular-nums">
                            {fmtNum(p.qty, 0)}
                            <div className={cn("text-[10px]", isLight ? "text-slate-400" : "text-neutral-500")}>brk {fmtNum(p.brokerQty ?? p.qty, 0)}</div>
                          </td>
                          <td className="px-2 py-2.5 text-right font-mono tabular-nums">{formatInr(parseMoney(p.avgPrice))}</td>
                          <td className="px-2 py-2.5 text-right">
                            <div className="font-mono font-semibold tabular-nums text-sky-700 dark:text-sky-300">{formatInr(ltp)}</div>
                            {watchQ.isFetching ? <div className="text-[9px] text-emerald-600">updating…</div> : null}
                          </td>
                          <td className={cn("px-3 py-2.5 text-right font-mono font-semibold tabular-nums", pnlClass(mtm, isLight))}>{formatPnlDisplay(mtm)}</td>
                        </motion.tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </QueryShell>
          </GlassPanel>
          <Link
            to="/positions"
            className={cn(
              "inline-flex items-center gap-2 text-xs font-semibold transition hover:underline",
              isLight ? "text-indigo-700" : "text-indigo-300",
            )}
          >
            Full positions & exit controls
            <ArrowRight className="h-3.5 w-3.5" />
          </Link>
        </section>
      </div>

      {/* Integrity dock — broker reconciliation & guard stream (diagnostic, not primary trading view) */}
      <div className="fixed inset-x-0 bottom-0 z-40 px-4 pb-4 lg:px-8">
        <div className="mx-auto max-w-[1680px]">
          <motion.div
            layout
            className={cn(
              "overflow-hidden rounded-2xl border backdrop-blur-xl shadow-2xl",
              isLight ? "border-slate-200/90 bg-white/90" : "border-neutral-800/90 bg-neutral-950/90",
              mismatchCount > 0 ? "ring-2 ring-amber-400/50" : "",
            )}
          >
            <button
              type="button"
              onClick={() => setIntegrityOpen((v) => !v)}
              className={cn("flex w-full items-center gap-3 px-4 py-3 text-left", isLight ? "hover:bg-slate-50" : "hover:bg-neutral-900/80")}
            >
              <div className={cn("flex h-9 w-9 items-center justify-center rounded-xl", mismatchCount > 0 ? "bg-amber-100 text-amber-800" : "bg-emerald-100 text-emerald-800")}>
                <Shield className="h-4 w-4" />
              </div>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className={cn("text-sm font-semibold", isLight ? "text-slate-900" : "text-neutral-100")}>Integrity & guards</span>
                  <SyncBadge state={syncState} isLight={isLight} />
                  {mismatchCount > 0 ? (
                    <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-bold text-amber-900">{mismatchCount} reconcile</span>
                  ) : (
                    <span className="rounded-full bg-emerald-100 px-2 py-0.5 text-[10px] font-bold text-emerald-800">Ledger matched</span>
                  )}
                  {(brokerTruth?.pendingBrokerOrders ?? 0) > 0 ? (
                    <span className={cn("text-[10px]", isLight ? "text-slate-500" : "text-neutral-400")}>{brokerTruth?.pendingBrokerOrders} pending orders</span>
                  ) : null}
                </div>
                <p className={cn("truncate text-[11px]", isLight ? "text-slate-500" : "text-neutral-400")}>
                  {mismatchCount > 0
                    ? "Platform and broker disagree on open quantity — review before sizing up."
                    : brokerTruth?.message || "Broker ledger matches platform positions."}
                </p>
              </div>
              {integrityOpen ? <ChevronDown className="h-4 w-4 shrink-0 opacity-50" /> : <ChevronUp className="h-4 w-4 shrink-0 opacity-50" />}
            </button>

            <AnimatePresence>
              {integrityOpen ? (
                <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: "auto", opacity: 1 }} exit={{ height: 0, opacity: 0 }} className="border-t">
                  <div className={cn("flex gap-2 border-b px-4 py-2", isLight ? "border-slate-100 bg-slate-50/80" : "border-neutral-800 bg-neutral-900/50")}>
                    {(["reconcile", "guards"] as const).map((tab) => (
                      <button
                        key={tab}
                        type="button"
                        onClick={() => setIntegrityTab(tab)}
                        className={cn(
                          "rounded-lg px-3 py-1.5 text-[11px] font-semibold capitalize transition",
                          integrityTab === tab
                            ? isLight
                              ? "bg-white text-slate-900 shadow-sm"
                              : "bg-neutral-800 text-white"
                            : isLight
                              ? "text-slate-500 hover:text-slate-800"
                              : "text-neutral-400 hover:text-neutral-200",
                        )}
                      >
                        {tab === "reconcile" ? "Reconciliation" : `Guard stream${recentGuardEvents.length ? ` (${recentGuardEvents.length})` : ""}`}
                      </button>
                    ))}
                    <Link to="/positions" className={cn("ml-auto inline-flex items-center gap-1 self-center text-[11px] font-semibold", isLight ? "text-indigo-700" : "text-indigo-300")}>
                      Open positions
                      <ArrowRight className="h-3 w-3" />
                    </Link>
                  </div>

                  <div className="max-h-[220px] overflow-auto p-4">
                    {integrityTab === "reconcile" ? (
                      <QueryShell loading={brokerTruthQ.isLoading && !brokerTruth} error={brokerErr} onRetry={() => void brokerTruthQ.refetch()} isLight={isLight}>
                        {mismatchCount === 0 ? (
                          <div className={cn("flex items-center gap-2 rounded-xl border px-4 py-3 text-sm", isLight ? "border-emerald-200 bg-emerald-50 text-emerald-800" : "border-emerald-500/30 bg-emerald-500/10 text-emerald-200")}>
                            <CheckCircle2 className="h-4 w-4 shrink-0" />
                            Positions match the broker ledger. No ghost rows or quantity drift detected.
                          </div>
                        ) : (
                          <table className="w-full text-xs">
                            <thead>
                              <tr className={cn("text-[10px] uppercase tracking-wide", isLight ? "text-slate-500" : "text-neutral-400")}>
                                <th className="pb-2 text-left">Symbol</th>
                                <th className="pb-2 text-left">Issue</th>
                                <th className="pb-2 text-right">Broker</th>
                                <th className="pb-2 text-right">Platform</th>
                              </tr>
                            </thead>
                            <tbody>
                              {(brokerTruth?.mismatches ?? []).map((m) => (
                                <tr key={`${m.kind}-${m.symbol}`} className={cn("border-t", isLight ? "border-slate-100" : "border-neutral-800")}>
                                  <td className="py-2 font-mono font-semibold">{bareSymbol(m.symbol)}</td>
                                  <td className="py-2">
                                    <div className="font-medium text-amber-800 dark:text-amber-200">{mismatchLabel(m.kind)}</div>
                                    <div className={cn("text-[10px]", isLight ? "text-slate-500" : "text-neutral-400")}>{mismatchHint(m.kind)}</div>
                                  </td>
                                  <td className="py-2 text-right font-mono tabular-nums">{m.brokerQty}</td>
                                  <td className="py-2 text-right font-mono tabular-nums">{m.internalQty}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        )}
                      </QueryShell>
                    ) : recentGuardEvents.length > 0 ? (
                      <ul className="space-y-2 text-[11px]">
                        {recentGuardEvents.map((ev, idx) => (
                          <li key={String(ev.id ?? ev.eventId ?? idx)} className={cn("rounded-lg border px-3 py-2", isLight ? "border-indigo-100 bg-indigo-50/50 text-indigo-950" : "border-indigo-500/30 bg-indigo-500/10 text-indigo-100")}>
                            <span className="font-semibold">{String(ev.title ?? ev.code ?? ev.kind ?? "Guard")}</span>
                            <span className={cn("mx-1 opacity-40")}>·</span>
                            <span className="opacity-90">{String(ev.message ?? ev.detail ?? "—")}</span>
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <p className={cn("text-center text-sm", isLight ? "text-slate-500" : "text-neutral-400")}>No execution guard events in the current session.</p>
                    )}
                  </div>
                </motion.div>
              ) : null}
            </AnimatePresence>
          </motion.div>
        </div>
      </div>
    </div>
  );
}

function GlassPanel({ children, className, isLight = true }: { children: ReactNode; className?: string; isLight?: boolean }) {
  return (
    <div
      className={cn(
        "rounded-2xl border backdrop-blur-xl",
        isLight ? "border-white/80 bg-white/70 shadow-[0_8px_40px_rgba(15,23,42,0.06)]" : "border-neutral-800/80 bg-neutral-900/55 shadow-[0_8px_40px_rgba(0,0,0,0.35)]",
        className,
      )}
    >
      {children}
    </div>
  );
}

function MetricPill({ label, value, highlight, pnl, isLight = true }: { label: string; value: string; highlight?: boolean; pnl?: unknown; isLight?: boolean }) {
  return (
    <div className={cn("rounded-xl border px-3 py-2 shadow-sm", isLight ? "border-white/90 bg-white/85" : "border-neutral-800 bg-neutral-900/80")}>
      <span className={cn("text-[10px] font-medium uppercase tracking-wider", isLight ? "text-slate-500" : "text-neutral-400")}>{label}</span>
      <span className={cn("mt-0.5 block font-mono text-sm font-semibold tabular-nums", highlight ? pnlClass(pnl, isLight) : isLight ? "text-slate-800" : "text-neutral-100")}>{value}</span>
    </div>
  );
}
