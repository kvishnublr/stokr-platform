import { useEffect, useMemo, useState, type ComponentType, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { motion, AnimatePresence } from "framer-motion";
import { Link } from "react-router-dom";
import { api, parseAxiosMessage } from "../../api/client";
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
}: {
  loading: boolean;
  error: string | null;
  onRetry: () => void;
  children: ReactNode;
  empty?: boolean;
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
      <p className="p-8 text-center text-sm text-slate-500">
        No data yet — check broker connection and strategy runtime.
      </p>
    );
  }
  return <>{children}</>;
}

export function IntradayCockpitPage() {
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

  const filteredSignals = useMemo(() => {
    const signals = ws?.latestSignals ?? [];
    if (!selectedStrategy) return signals.slice(0, 24);
    return signals
      .filter((s) =>
        String(s.strategyKey ?? s.strategy ?? "")
          .toUpperCase()
          .includes(selectedStrategy.toUpperCase()),
      )
      .slice(0, 24);
  }, [ws?.latestSignals, selectedStrategy]);

  const overallTone =
    readiness?.overallStatus === "READY"
      ? "ok"
      : readiness?.overallStatus === "WARNING"
        ? "warn"
        : "bad";

  const guardCount = (ws?.executionGuardEvents?.length ?? 0) + guardEvents.length;

  return (
    <div className="relative min-h-[calc(100vh-3rem)] overflow-hidden bg-[#f4f6fb] font-sans text-slate-900">
      <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_80%_50%_at_10%_-10%,rgba(99,102,241,0.12),transparent),radial-gradient(ellipse_60%_40%_at_90%_0%,rgba(14,165,233,0.1),transparent),radial-gradient(ellipse_50%_50%_at_50%_100%,rgba(16,185,129,0.08),transparent)]" />
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
        className="sticky top-0 z-30 border-b border-white/60 bg-white/70 shadow-[0_8px_32px_rgba(15,23,42,0.06)] backdrop-blur-xl"
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
              <MetricPill label="Session" value={readiness?.session?.sessionState ?? (readinessQ.isLoading ? "…" : "—")} />
              <MetricPill label="Feed lag" value={`${readiness?.feed?.feedLagMs ?? 0}ms`} />
              <MetricPill label="Regime" value={readiness?.feed?.status ?? "—"} />
              <MetricPill
                label="Day PnL"
                value={fmtNum(ws?.accountSummary?.totalPnl)}
                highlight
                pnl={ws?.accountSummary?.totalPnl}
              />
              <MetricPill label="Risk" value={ws?.riskControls?.parityState ?? "—"} />
              <MetricPill
                label="Exec quality"
                value={String(ws?.executionQualityScore?.grade ?? ws?.executionQualityScore?.score ?? "—")}
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
          <PanelTitle icon={Zap} title="Strategy rail" subtitle="Live runtime · ranked setups" />
          <GlassPanel>
            <QueryShell
              loading={readinessQ.isLoading}
              error={readinessErr}
              onRetry={() => void readinessQ.refetch()}
              empty={!readinessQ.isLoading && !readinessErr && (readiness?.strategies?.length ?? 0) === 0}
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
                        ? "border-indigo-300 bg-white shadow-lg shadow-indigo-500/10 ring-1 ring-indigo-200"
                        : "border-slate-200/80 bg-white/80 hover:border-slate-300 hover:shadow-md",
                    )}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-sm font-semibold text-slate-900">{st.strategy}</span>
                      <SyncBadge state={st.runtime?.includes("RUN") ? "VERIFIED" : st.status} />
                    </div>
                    <p className="mt-1 font-mono text-[10px] text-slate-500">{st.strategyKey}</p>
                    <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-slate-100">
                      <motion.div
                        className="h-full rounded-full bg-gradient-to-r from-indigo-500 via-sky-500 to-emerald-400"
                        initial={{ width: 0 }}
                        animate={{ width: st.runtime?.includes("RUN") ? "78%" : "28%" }}
                        transition={{ duration: 0.6 }}
                      />
                    </div>
                    <p className="mt-1.5 text-[10px] text-slate-500">
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
          <PanelTitle
            icon={Activity}
            title="Opportunity matrix"
            subtitle={
              selectedStrategy
                ? `${filteredSignals.length} signals · ${selectedStrategy}`
                : `${filteredSignals.length} ranked · all strategies`
            }
          />
          <GlassPanel className="overflow-hidden p-0">
            <QueryShell
              loading={workstationQ.isLoading}
              error={wsErr}
              onRetry={() => void workstationQ.refetch()}
              empty={!workstationQ.isLoading && !wsErr && filteredSignals.length === 0}
            >
              <div className="grid grid-cols-8 gap-2 border-b border-slate-200/80 bg-slate-50/80 px-3 py-2.5 text-[10px] font-semibold uppercase tracking-wider text-slate-500">
                <span>Symbol</span>
                <span>Dir</span>
                <span>Strategy</span>
                <span>Entry</span>
                <span>SL</span>
                <span>Target</span>
                <span>RR</span>
                <span>Conf</span>
              </div>
              <AnimatePresence mode="popLayout">
                <motion.div variants={stagger} initial="hidden" animate="show">
                  {filteredSignals.map((row, i) => (
                    <motion.div
                      key={String(row.id ?? row.signalId ?? i)}
                      variants={rowIn}
                      layout
                      className="grid grid-cols-8 gap-2 border-b border-slate-100 px-3 py-2.5 text-xs transition-colors hover:bg-indigo-50/50"
                    >
                      <span className="font-mono font-medium text-indigo-700">{String(row.symbol ?? "—")}</span>
                      <span className="text-slate-700">{String(row.signalType ?? row.side ?? "—")}</span>
                      <span className="truncate text-slate-500">{String(row.strategyKey ?? row.strategy ?? "—")}</span>
                      <span>{fmtNum(row.entryReferencePrice ?? row.entry)}</span>
                      <span>{fmtNum(row.stopPrice)}</span>
                      <span>{fmtNum(row.targetPrice)}</span>
                      <span>{fmtNum(row.riskReward)}</span>
                      <span>
                        <span className="rounded-md bg-slate-100 px-1.5 py-0.5 font-medium text-slate-700">
                          {fmtNum(row.confidenceScore ?? row.confidence, 0)}%
                        </span>
                      </span>
                    </motion.div>
                  ))}
                </motion.div>
              </AnimatePresence>
            </QueryShell>
          </GlassPanel>
        </section>

        <aside className="col-span-12 space-y-4 xl:col-span-3">
          <PanelTitle icon={Shield} title="Execution intel" subtitle="Broker truth · guard stream" />
          <GlassPanel className="space-y-3 p-4">
            <QueryShell
              loading={brokerTruthQ.isLoading && !brokerTruth}
              error={brokerErr}
              onRetry={() => void brokerTruthQ.refetch()}
            >
              <div className="flex items-center justify-between text-xs">
                <span className="text-slate-500">Sync state</span>
                <SyncBadge state={syncState} />
              </div>
              <p className="text-[11px] leading-relaxed text-slate-600">{brokerTruth?.message || "Awaiting broker reconciliation…"}</p>
              {brokerTruth?.mismatches?.length ? (
                <ul className="space-y-1 rounded-lg border border-amber-200/60 bg-amber-50/80 p-2 text-[11px] text-amber-900">
                  {brokerTruth.mismatches.slice(0, 5).map((m) => (
                    <li key={m.symbol}>
                      {m.kind}: {m.symbol} (broker {m.brokerQty} vs internal {m.internalQty})
                    </li>
                  ))}
                </ul>
              ) : null}
              <div className="text-xs text-slate-500">
                Pending broker orders:{" "}
                <span className="font-semibold text-slate-800">{brokerTruth?.pendingBrokerOrders ?? 0}</span>
              </div>
              <motion.div
                animate={guardEvents.length > 0 ? { boxShadow: ["0 0 0 rgba(99,102,241,0)", "0 0 20px rgba(99,102,241,0.15)", "0 0 0 rgba(99,102,241,0)"] } : {}}
                transition={{ duration: 1.2 }}
                className="rounded-lg border border-indigo-100 bg-indigo-50/50 p-2.5 text-[11px] text-indigo-900"
              >
                Live guard events: <span className="font-semibold">{guardCount}</span>
              </motion.div>
            </QueryShell>
          </GlassPanel>

          <PanelTitle icon={TrendingUp} title="Live positions" subtitle="Broker-verified quantity" />
          <GlassPanel>
            <QueryShell
              loading={workstationQ.isLoading}
              error={wsErr}
              onRetry={() => void workstationQ.refetch()}
              empty={!workstationQ.isLoading && !wsErr && (ws?.openPositions?.length ?? 0) === 0}
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
                      className="rounded-xl border border-slate-200/80 bg-white/90 p-3 shadow-sm"
                    >
                      <div className="flex items-center justify-between">
                        <span className="font-mono text-sm font-medium text-slate-900">{sym}</span>
                        <SyncBadge state={sync} />
                      </div>
                      <div className="mt-2 grid grid-cols-2 gap-1 text-[11px] text-slate-500">
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

function GlassPanel({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div
      className={cn(
        "rounded-2xl border border-white/80 bg-white/65 shadow-[0_8px_40px_rgba(15,23,42,0.06)] backdrop-blur-xl",
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
}: {
  icon: ComponentType<{ className?: string }>;
  title: string;
  subtitle: string;
}) {
  return (
    <div className="flex items-center gap-2 px-0.5">
      <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-white/80 text-indigo-600 shadow-sm ring-1 ring-slate-200/80">
        <Icon className="h-4 w-4" />
      </div>
      <div>
        <h2 className="text-sm font-semibold text-slate-900">{title}</h2>
        <p className="text-[11px] text-slate-500">{subtitle}</p>
      </div>
    </div>
  );
}

function MetricPill({
  label,
  value,
  highlight,
  pnl,
}: {
  label: string;
  value: string;
  highlight?: boolean;
  pnl?: unknown;
}) {
  return (
    <div className="min-w-[72px] rounded-xl border border-white/90 bg-white/80 px-3 py-2 shadow-sm backdrop-blur-sm">
      <span className="text-[10px] font-medium uppercase tracking-wider text-slate-500">{label}</span>
      <span
        className={cn(
          "mt-0.5 block font-mono text-sm font-semibold",
          highlight ? pnlClass(pnl) : "text-slate-800",
        )}
      >
        {value}
      </span>
    </div>
  );
}
