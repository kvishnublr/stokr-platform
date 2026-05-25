import { useEffect, useMemo, useRef, useState, type ComponentType } from "react";
import { useQuery } from "@tanstack/react-query";
import { motion, AnimatePresence } from "framer-motion";
import { api } from "../../api/client";
import { cn } from "../../lib/utils";
import {
  Activity,
  AlertTriangle,
  CheckCircle2,
  Radio,
  Shield,
  Zap,
} from "lucide-react";

type Readiness = {
  overallStatus: string;
  lastValidatedAt: string;
  feed: { status: string; severity: string; feedLagMs: number; websocketState: string; detail: string };
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
  warnings: Array<{ code: string; message: string }>;
  blockers: Array<{ code: string; message: string }>;
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

function syncTone(state: string) {
  const s = (state || "").toUpperCase();
  if (s === "VERIFIED" || s === "SYNCED") return "ok";
  if (s === "RECONCILING" || s === "PENDING_SYNC") return "warn";
  return "bad";
}

function SyncBadge({ state }: { state: string }) {
  const tone = syncTone(state);
  const cls =
    tone === "ok"
      ? "border-emerald-500/40 bg-emerald-500/10 text-emerald-300"
      : tone === "warn"
        ? "border-amber-500/40 bg-amber-500/10 text-amber-300"
        : "border-rose-500/40 bg-rose-500/10 text-rose-300";
  return (
    <span className={cn("rounded px-2 py-0.5 text-[10px] font-bold uppercase tracking-wider border", cls)}>
      {state || "UNKNOWN"}
    </span>
  );
}

function PulseDot({ ok }: { ok: boolean }) {
  return (
    <motion.span
      animate={{ opacity: ok ? [0.5, 1, 0.5] : 1, scale: ok ? [1, 1.2, 1] : 1 }}
      transition={{ duration: 1.8, repeat: Infinity }}
      className={cn("inline-block h-2 w-2 rounded-full", ok ? "bg-emerald-400" : "bg-rose-400")}
    />
  );
}

function fmtNum(v: unknown, digits = 2) {
  if (v == null) return "—";
  const n = typeof v === "number" ? v : parseFloat(String(v));
  if (Number.isNaN(n)) return String(v);
  return n.toFixed(digits);
}

export function IntradayCockpitPage() {
  const [selectedStrategy, setSelectedStrategy] = useState<string | null>(null);
  const [guardFlash, setGuardFlash] = useState(0);
  const guardRef = useRef<EventSource | null>(null);

  const readinessQ = useQuery({
    queryKey: ["intraday-readiness"],
    queryFn: async () => (await api.get("/api/trader/intraday/readiness")).data?.data as Readiness,
    refetchInterval: 5000,
  });

  const workstationQ = useQuery({
    queryKey: ["intraday-workstation"],
    queryFn: async () => (await api.get("/api/trader/terminal/workstation")).data?.data as Workstation,
    refetchInterval: 3000,
  });

  const brokerTruthQ = useQuery({
    queryKey: ["intraday-broker-truth"],
    queryFn: async () => (await api.get("/api/trader/terminal/broker-truth")).data?.data as BrokerTruth,
    refetchInterval: 5000,
  });

  const readiness = readinessQ.data;
  const ws = workstationQ.data;
  const brokerTruth = brokerTruthQ.data ?? ws?.brokerTruth;

  useEffect(() => {
    const es = new EventSource("/api/trader/terminal/execution-guard/stream");
    guardRef.current = es;
    es.onmessage = () => setGuardFlash((n) => n + 1);
    return () => {
      es.close();
      guardRef.current = null;
    };
  }, []);

  const filteredSignals = useMemo(() => {
    const signals = ws?.latestSignals ?? [];
    if (!selectedStrategy) return signals.slice(0, 24);
    return signals
      .filter((s) => String(s.strategyKey ?? s.strategy ?? "").toUpperCase().includes(selectedStrategy.toUpperCase()))
      .slice(0, 24);
  }, [ws?.latestSignals, selectedStrategy]);

  const overallTone =
    readiness?.overallStatus === "READY"
      ? "ok"
      : readiness?.overallStatus === "WARNING"
        ? "warn"
        : "bad";

  return (
    <div className="min-h-[calc(100vh-3rem)] bg-[#070b12] text-slate-100">
      <div className="pointer-events-none fixed inset-0 bg-[radial-gradient(ellipse_at_20%_0%,rgba(0,180,255,0.08),transparent_50%),radial-gradient(ellipse_at_80%_100%,rgba(0,255,140,0.06),transparent_45%)]" />

      {/* Live trading strip */}
      <motion.header
        initial={{ opacity: 0, y: -8 }}
        animate={{ opacity: 1, y: 0 }}
        className="sticky top-0 z-30 border-b border-cyan-500/15 bg-[#070b12]/90 backdrop-blur-xl"
      >
        <div className="px-4 py-3 lg:px-6">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-3">
              <h1 className="text-lg font-semibold tracking-tight text-white">Intraday Cockpit</h1>
              <SyncBadge state={brokerTruth?.syncState ?? "PENDING_SYNC"} />
              <span className="flex items-center gap-1.5 text-xs text-slate-400">
                <PulseDot ok={brokerTruth?.brokerConnected ?? false} />
                Broker sync {brokerTruth?.syncLatencyMs ?? 0}ms
              </span>
            </div>
            <div className="flex flex-wrap items-center gap-4 text-xs">
              <MetricPill label="Session" value={readiness?.session?.sessionState ?? "—"} />
              <MetricPill label="Feed lag" value={`${readiness?.feed?.feedLagMs ?? 0}ms`} />
              <MetricPill label="VIX / regime" value={readiness?.feed?.status ?? "—"} />
              <MetricPill
                label="Day PnL"
                value={fmtNum(ws?.accountSummary?.totalPnl)}
                highlight
              />
              <MetricPill label="Risk" value={ws?.riskControls?.parityState ?? "—"} />
              <MetricPill
                label="Exec quality"
                value={String(ws?.executionQualityScore?.grade ?? ws?.executionQualityScore?.score ?? "—")}
              />
            </div>
          </div>

          {/* Readiness command bar */}
          <div
            className={cn(
              "mt-3 flex flex-wrap items-center gap-3 rounded-lg border px-3 py-2 text-sm",
              overallTone === "ok" && "border-emerald-500/25 bg-emerald-500/5",
              overallTone === "warn" && "border-amber-500/25 bg-amber-500/5",
              overallTone === "bad" && "border-rose-500/25 bg-rose-500/5"
            )}
          >
            {overallTone === "ok" ? (
              <CheckCircle2 className="h-4 w-4 text-emerald-400" />
            ) : (
              <AlertTriangle className="h-4 w-4 text-amber-400" />
            )}
            <span className="font-medium">
              {readiness?.overallStatus ?? "CHECKING"} · {readiness?.session?.detail ?? "Validating…"}
            </span>
            <span className="text-slate-400">
              {readiness?.warnings?.length ?? 0} warn · {readiness?.blockers?.length ?? 0} block
            </span>
            <span className="ml-auto flex gap-2 text-[10px] uppercase tracking-wide text-slate-400">
              <span className="flex items-center gap-1">
                <Radio className="h-3 w-3" /> WS {readiness?.feed?.websocketState ?? "—"}
              </span>
              <span>Runtime {readiness?.runtime?.runningStrategies ?? 0}/{readiness?.runtime?.totalStrategies ?? 0}</span>
            </span>
          </div>
        </div>
      </motion.header>

      <div className="relative grid grid-cols-12 gap-4 p-4 lg:p-6">
        {/* Strategy rail */}
        <aside className="col-span-12 xl:col-span-3 space-y-3">
          <PanelTitle icon={Zap} title="Strategy rail" subtitle="Live runtime · auto-ranked" />
          <div className="space-y-2 max-h-[70vh] overflow-y-auto pr-1">
            {(readiness?.strategies ?? []).map((st) => (
              <motion.button
                key={st.strategyKey}
                type="button"
                layout
                whileHover={{ scale: 1.01, y: -2 }}
                onClick={() => setSelectedStrategy(st.strategyKey)}
                className={cn(
                  "w-full text-left rounded-xl border p-3 transition-shadow",
                  selectedStrategy === st.strategyKey
                    ? "border-cyan-400/50 bg-cyan-500/10 shadow-[0_0_24px_rgba(0,200,255,0.15)]"
                    : "border-slate-700/80 bg-slate-900/60 hover:border-slate-600"
                )}
              >
                <div className="flex items-center justify-between">
                  <span className="font-semibold text-sm">{st.strategy}</span>
                  <SyncBadge state={st.runtime?.includes("RUN") ? "VERIFIED" : st.status} />
                </div>
                <p className="mt-1 text-[11px] text-slate-400">{st.strategyKey}</p>
                <div className="mt-2 h-1.5 rounded-full bg-slate-800 overflow-hidden">
                  <motion.div
                    className="h-full bg-gradient-to-r from-cyan-500 to-emerald-400"
                    initial={{ width: 0 }}
                    animate={{ width: st.runtime?.includes("RUN") ? "72%" : "24%" }}
                  />
                </div>
                <p className="mt-1 text-[10px] text-slate-500">Last signal {st.lastSignalTime ?? "—"}</p>
              </motion.button>
            ))}
          </div>
        </aside>

        {/* Opportunity matrix */}
        <section className="col-span-12 xl:col-span-6 space-y-3">
          <PanelTitle
            icon={Activity}
            title="AI Opportunity Matrix"
            subtitle={
              selectedStrategy
                ? `${filteredSignals.length} signals · ${selectedStrategy}`
                : `${filteredSignals.length} ranked · all strategies`
            }
          />
          <div className="rounded-xl border border-slate-700/80 bg-slate-900/40 overflow-hidden">
            <div className="grid grid-cols-8 gap-2 px-3 py-2 text-[10px] uppercase tracking-wider text-slate-500 border-b border-slate-800">
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
              {filteredSignals.length === 0 ? (
                <p className="p-8 text-center text-slate-500 text-sm">No ranked opportunities yet</p>
              ) : (
                filteredSignals.map((row, i) => (
                  <motion.div
                    key={String(row.id ?? row.signalId ?? i)}
                    initial={{ opacity: 0, x: -8 }}
                    animate={{ opacity: 1, x: 0 }}
                    exit={{ opacity: 0 }}
                    className="grid grid-cols-8 gap-2 px-3 py-2 text-xs border-b border-slate-800/80 hover:bg-cyan-500/5"
                  >
                    <span className="font-mono text-cyan-200">{String(row.symbol ?? "—")}</span>
                    <span>{String(row.signalType ?? row.side ?? "—")}</span>
                    <span className="truncate text-slate-400">{String(row.strategyKey ?? row.strategy ?? "—")}</span>
                    <span>{fmtNum(row.entryReferencePrice ?? row.entry)}</span>
                    <span>{fmtNum(row.stopPrice)}</span>
                    <span>{fmtNum(row.targetPrice)}</span>
                    <span>{fmtNum(row.riskReward)}</span>
                    <span>
                      <span className="rounded bg-slate-800 px-1.5 py-0.5">
                        {fmtNum(row.confidenceScore ?? row.confidence, 0)}%
                      </span>
                    </span>
                  </motion.div>
                ))
              )}
            </AnimatePresence>
          </div>
        </section>

        {/* Execution intel + safety */}
        <aside className="col-span-12 xl:col-span-3 space-y-3">
          <PanelTitle icon={Shield} title="Execution Intel" subtitle="Broker truth · guard stream" />
          <div className="rounded-xl border border-slate-700/80 bg-slate-900/60 p-3 space-y-3">
            <div className="flex justify-between text-xs">
              <span className="text-slate-400">Sync state</span>
              <SyncBadge state={brokerTruth?.syncState ?? "STALE"} />
            </div>
            <p className="text-[11px] text-slate-500">{brokerTruth?.message}</p>
            {brokerTruth?.mismatches?.length ? (
              <ul className="text-[11px] space-y-1 text-amber-300">
                {brokerTruth.mismatches.slice(0, 5).map((m) => (
                  <li key={m.symbol}>
                    {m.kind}: {m.symbol} (broker {m.brokerQty} vs internal {m.internalQty})
                  </li>
                ))}
              </ul>
            ) : null}
            <div className="text-xs text-slate-400">
              Pending broker orders: <span className="text-white">{brokerTruth?.pendingBrokerOrders ?? 0}</span>
            </div>
            <div
              className={cn(
                "rounded-lg border p-2 text-[11px]",
                guardFlash > 0 ? "border-cyan-500/30 bg-cyan-500/5" : "border-slate-700"
              )}
            >
              Live guard events: {(ws?.executionGuardEvents?.length ?? 0) + (guardFlash > 0 ? 1 : 0)}
            </div>
          </div>

          <PanelTitle icon={Shield} title="Live positions" subtitle="Broker-verified qty" />
          <div className="space-y-2 max-h-[40vh] overflow-y-auto">
            {(ws?.openPositions ?? []).length === 0 ? (
              <p className="text-sm text-slate-500 p-4 text-center border border-dashed border-slate-700 rounded-xl">
                No open positions
              </p>
            ) : (
              (ws?.openPositions ?? []).map((p) => {
                const sym = String(p.symbol ?? "");
                const sync = String(p.brokerSyncState ?? brokerTruth?.syncState ?? "PENDING_SYNC");
                return (
                  <motion.div
                    key={sym}
                    layout
                    initial={{ opacity: 0 }}
                    animate={{ opacity: 1 }}
                    className="rounded-lg border border-slate-700/80 bg-slate-900/80 p-2.5"
                  >
                    <div className="flex items-center justify-between">
                      <span className="font-mono text-sm text-cyan-200">{sym}</span>
                      <SyncBadge state={sync} />
                    </div>
                    <div className="mt-1 grid grid-cols-2 gap-1 text-[11px] text-slate-400">
                      <span>Qty {fmtNum(p.qty, 0)}</span>
                      <span>Broker {fmtNum(p.brokerQty ?? p.qty, 0)}</span>
                      <span>MTM {fmtNum(p.mtmPnl)}</span>
                      <span>{String(p.side ?? "")}</span>
                    </div>
                  </motion.div>
                );
              })
            )}
          </div>
        </aside>
      </div>
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
    <div className="flex items-center gap-2">
      <Icon className="h-4 w-4 text-cyan-400" />
      <div>
        <h2 className="text-sm font-semibold text-white">{title}</h2>
        <p className="text-[11px] text-slate-500">{subtitle}</p>
      </div>
    </div>
  );
}

function MetricPill({
  label,
  value,
  highlight,
}: {
  label: string;
  value: string;
  highlight?: boolean;
}) {
  return (
    <div className="flex flex-col">
      <span className="text-[10px] uppercase tracking-wider text-slate-500">{label}</span>
      <span className={cn("font-mono text-sm", highlight ? "text-emerald-300" : "text-slate-200")}>{value}</span>
    </div>
  );
}
