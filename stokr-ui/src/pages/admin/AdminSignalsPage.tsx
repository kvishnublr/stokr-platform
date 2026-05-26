import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router-dom";
import { fmtDateTime } from "../../lib/dateUtils";
import { useState, useCallback, useEffect, useMemo } from "react";
import { api } from "../../api/client";
import { useUiThemeStore } from "../../state/uiTheme";
import { cn } from "../../lib/utils";
import { AdminPageShell, AdminPanel, AdminSection } from "../../components/admin/institutional/AdminDesignSystem";
import { LiveSignalCard } from "../../components/admin/institutional/experience/LiveSignalCard";
import { SignalQualityEngine } from "../../components/admin/institutional/experience/SignalQualityEngine";
import {
  SignalClusterStormPanel,
  SignalConfidenceEnginePanel,
  SignalReplayIntelPanel,
} from "../../components/admin/institutional/experience/SignalIntelligenceOS";
import { SignalLifecycleTimeline, StrategyPerformanceHeatmap } from "../../components/admin/institutional/experience/SignalLifecycleTimeline";
import { resolveProvenance } from "../../components/admin/institutional/experience/provenanceTheme";
import {
  TrendingUp, TrendingDown, Minus, RefreshCw, Download, X,
  ChevronRight, AlertTriangle, Target,
  Activity, BarChart3, Zap, Timer,
} from "lucide-react";

// ─── Types ───────────────────────────────────────────────────────────────────

type SignalRow = {
  id: string;
  strategyName: string | null;
  symbol: string | null;
  signalType: string | null;
  pipeline: string | null;
  signalSource: string | null;
  confidenceScore: number | null;
  entryReferencePrice: number | null;
  stopPrice: number | null;
  targetPrice: number | null;
  suggestedQty: number | null;
  reason: string | null;
  marketRegime: string | null;
  userId: string | null;
  createdAt: string | null;
  outcomeStatus: string | null;
  realizedPnl: number | null;
  unrealizedPnl: number | null;
  maxFavorableExcursion: number | null;
  maxAdverseExcursion: number | null;
  hitTarget: boolean | null;
  hitStoploss: boolean | null;
  riskRewardAchieved: number | null;
  executionLatencyMs: number | null;
  entryPrice: number | null;
  exitPrice: number | null;
};

type OrderSummary = {
  id: string; symbol: string; side: string; state: string;
  quantity: number; executionMode: string; rejectReason: string | null; createdAt: string;
};

type TimelineEvent = {
  eventType: string; streamSequence: number | null; createdAt: string;
  payload: Record<string, unknown>;
};

type SignalDetail = {
  signal: SignalRow;
  linkedOrders: OrderSummary[];
  executionTimeline: TimelineEvent[];
};

type PageResp = { content: SignalRow[]; totalPages: number; totalElements: number; page: number };

type StatsResp = {
  totalToday: number; buyToday: number; sellToday: number;
  liveToday: number; paperToday: number; avgConfidence: number | null;
  targetHit: number; slHit: number; running: number; expired: number; totalAllTime: number;
};

type CatalogSignalStats = { strategyKey: string; signalsToday: number; lastSignalAt: string | null };

// ─── Helpers ─────────────────────────────────────────────────────────────────

const fmt = (v: number | null | undefined, dec = 2) =>
  v == null ? "—" : Number(v).toFixed(dec);

const fmtPnl = (v: number | null | undefined) => {
  if (v == null || Number.isNaN(Number(v))) return "—";
  const n = Number(v);
  const s = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", minimumFractionDigits: 0, maximumFractionDigits: 0 }).format(Math.abs(n));
  return n >= 0 ? `+${s}` : `-${s}`;
};

const fmtTime = fmtDateTime;

const pnlClass = (v: number | null | undefined) =>
  v == null ? "text-slate-400" : Number(v) >= 0 ? "text-emerald-600 font-semibold" : "text-rose-600 font-semibold";

// ─── Chips ────────────────────────────────────────────────────────────────────

function SideChip({ type }: { type: string | null }) {
  const t = (type ?? "").toUpperCase();
  if (t === "BUY") return (
    <span className="inline-flex items-center gap-1 rounded-md border border-emerald-200 bg-emerald-50 px-2 py-0.5 text-[11px] font-semibold text-emerald-700">
      <TrendingUp className="h-3 w-3" /> BUY
    </span>
  );
  if (t === "SELL") return (
    <span className="inline-flex items-center gap-1 rounded-md border border-rose-200 bg-rose-50 px-2 py-0.5 text-[11px] font-semibold text-rose-700">
      <TrendingDown className="h-3 w-3" /> SELL
    </span>
  );
  return (
    <span className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-slate-100 px-2 py-0.5 text-[11px] font-semibold text-slate-500">
      <Minus className="h-3 w-3" /> {t || "—"}
    </span>
  );
}

function ModeChip({ mode }: { mode: string | null }) {
  const v = (mode ?? "").toUpperCase();
  if (v === "LIVE") return (
    <span className="inline-flex items-center gap-1 rounded-md border border-rose-200 bg-rose-50 px-1.5 py-0.5 text-[10px] font-bold text-rose-600">
      <span className="h-1.5 w-1.5 rounded-full bg-rose-500 animate-pulse" />LIVE
    </span>
  );
  if (v === "PAPER") return (
    <span className="rounded-md border border-amber-200 bg-amber-50 px-1.5 py-0.5 text-[10px] font-bold text-amber-700">PAPER</span>
  );
  return <span className="rounded-md border border-slate-200 bg-slate-50 px-1.5 py-0.5 text-[10px] font-semibold text-slate-500">{v || "—"}</span>;
}

const OUTCOME_CFG: Record<string, { label: string; cls: string; dot: string }> = {
  TARGET_HIT:     { label: "Target Hit",     cls: "border-emerald-200 bg-emerald-50 text-emerald-700", dot: "bg-emerald-500" },
  STOPLOSS_HIT:   { label: "SL Hit",         cls: "border-rose-200 bg-rose-50 text-rose-700",         dot: "bg-rose-500" },
  RUNNING:        { label: "Running",         cls: "border-sky-200 bg-sky-50 text-sky-700",            dot: "bg-sky-500" },
  PARTIAL_TARGET: { label: "Partial",         cls: "border-orange-200 bg-orange-50 text-orange-700",   dot: "bg-orange-400" },
  BREAKEVEN_EXIT: { label: "Breakeven",       cls: "border-blue-200 bg-blue-50 text-blue-700",         dot: "bg-blue-400" },
  EXPIRED:        { label: "Expired",         cls: "border-slate-200 bg-slate-100 text-slate-500",     dot: "bg-slate-400" },
  MISSED:         { label: "Missed",          cls: "border-amber-200 bg-amber-50 text-amber-700",      dot: "bg-amber-400" },
  CANCELLED:      { label: "Cancelled",       cls: "border-slate-200 bg-slate-100 text-slate-500",     dot: "bg-slate-300" },
  REJECTED:       { label: "Rejected",        cls: "border-red-200 bg-red-50 text-red-700",            dot: "bg-red-500" },
  NO_EXECUTION:   { label: "No Execution",    cls: "border-slate-200 bg-slate-50 text-slate-400",      dot: "bg-slate-300" },
};

function OutcomeChip({ status }: { status: string | null }) {
  if (status === "PENDING") {
    return <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-semibold text-slate-600">Pending</span>;
  }
  if (!status) return <span className="text-slate-300 text-[11px]">—</span>;
  const cfg = OUTCOME_CFG[status] ?? { label: status, cls: "border-slate-200 bg-slate-50 text-slate-500", dot: "bg-slate-400" };
  return (
    <span className={`inline-flex items-center gap-1 rounded-md border px-2 py-0.5 text-[10px] font-semibold ${cfg.cls}`}>
      <span className={`h-1.5 w-1.5 rounded-full ${cfg.dot}`} />{cfg.label}
    </span>
  );
}

function ConfidenceBar({ score }: { score: number | null }) {
  if (score == null) return <span className="text-slate-300">—</span>;
  const pct = Math.round(Number(score) * 100);
  const color = pct >= 75 ? "bg-emerald-400" : pct >= 55 ? "bg-amber-400" : "bg-rose-400";
  return (
    <div className="flex items-center gap-2 min-w-[80px]">
      <div className="flex-1 h-1.5 rounded-full bg-slate-200 overflow-hidden">
        <div className={`h-full rounded-full ${color}`} style={{ width: `${pct}%` }} />
      </div>
      <span className="text-[11px] font-mono text-slate-600 tabular-nums w-9 text-right">{pct}%</span>
    </div>
  );
}


// ─── Timeline event dot color ─────────────────────────────────────────────────

const evDot = (type: string) => {
  if (type.includes("FILLED") || type.includes("FILL")) return "bg-emerald-500";
  if (type.includes("REJECT")) return "bg-rose-500";
  if (type.includes("RISK") || type.includes("PASS")) return "bg-sky-400";
  if (type.includes("DLQ") || type.includes("RETRY")) return "bg-amber-400";
  return "bg-slate-400";
};

// ─── Detail Drawer ────────────────────────────────────────────────────────────

function SignalDetailDrawer({ id, onClose }: { id: string; onClose: () => void }) {
  const q = useQuery<SignalDetail>({
    queryKey: ["admin-signal-detail", id],
    queryFn: async () => (await api.get(`/api/admin/signals/${id}`)).data?.data,
    staleTime: 30_000,
  });

  const d = q.data;
  const sig = d?.signal;

  const label = "text-[10px] font-semibold uppercase tracking-widest text-slate-400";
  const val = "mt-0.5 text-sm font-mono text-slate-900";

  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      <div className="absolute inset-0 bg-slate-900/30 backdrop-blur-[2px]" onClick={onClose} />
      <div className="relative z-10 flex h-full w-full max-w-2xl flex-col bg-white shadow-2xl ring-1 ring-slate-200 overflow-hidden">

        {/* Header */}
        <div className="flex items-center justify-between border-b border-slate-200 bg-slate-50 px-5 py-4">
          <div>
            <div className="flex items-center gap-2 flex-wrap">
              <span className="text-sm font-bold text-slate-900">{sig?.strategyName ?? "Signal"}</span>
              {sig && <SideChip type={sig.signalType} />}
              {sig && <ModeChip mode={sig.pipeline} />}
              {sig && <OutcomeChip status={sig.outcomeStatus} />}
            </div>
            <div className="mt-1 font-mono text-[11px] text-slate-400">{id.slice(0, 20)}…</div>
          </div>
          <button type="button" onClick={onClose}
            className="rounded-lg p-2 text-slate-400 hover:bg-slate-100 hover:text-slate-600 transition-colors">
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto">
          {q.isLoading && (
            <div className="flex h-32 items-center justify-center text-slate-400 text-sm">Loading signal detail…</div>
          )}

          {sig && (
            <>
              {/* Signal Summary */}
              <div className="border-b border-slate-100 px-5 py-4">
                <div className="mb-3 flex items-center gap-2">
                  <Target className="h-3.5 w-3.5 text-slate-400" />
                  <span className="text-[11px] font-bold uppercase tracking-widest text-slate-400">Signal Parameters</span>
                </div>
                <div className="grid grid-cols-3 gap-3">
                  {[
                    { label: "Symbol", value: sig.symbol ?? "—" },
                    { label: "Entry Ref", value: fmt(sig.entryReferencePrice) },
                    { label: "Confidence", value: <ConfidenceBar score={sig.confidenceScore} /> },
                    { label: "Stop Loss", value: <span className="text-rose-600 font-mono text-sm font-semibold">{fmt(sig.stopPrice)}</span> },
                    { label: "Target", value: <span className="text-emerald-600 font-mono text-sm font-semibold">{fmt(sig.targetPrice)}</span> },
                    { label: "Quantity", value: fmt(sig.suggestedQty, 0) },
                    { label: "Regime", value: sig.marketRegime ?? "—" },
                    { label: "Generated", value: fmtTime(sig.createdAt) },
                    { label: "User", value: sig.userId ? sig.userId.slice(0, 8) + "…" : "—" },
                  ].map(({ label: lbl, value }) => (
                    <div key={lbl} className="rounded-lg bg-slate-50 border border-slate-100 px-3 py-2.5">
                      <div className={label}>{lbl}</div>
                      <div className={val}>{value}</div>
                    </div>
                  ))}
                </div>
              </div>

              {/* Outcome Analytics */}
              {(sig.outcomeStatus || sig.realizedPnl != null || sig.executionLatencyMs != null) && (
                <div className="border-b border-slate-100 px-5 py-4">
                  <div className="mb-3 flex items-center gap-2">
                    <BarChart3 className="h-3.5 w-3.5 text-slate-400" />
                    <span className="text-[11px] font-bold uppercase tracking-widest text-slate-400">Execution Analytics</span>
                  </div>
                  <div className="grid grid-cols-3 gap-3">
                    {[
                      { label: "Outcome", value: <OutcomeChip status={sig.outcomeStatus} /> },
                      { label: "Entry Price", value: fmt(sig.entryPrice) },
                      { label: "Exit Price", value: fmt(sig.exitPrice) },
                      { label: "Realized PnL", value: <span className={pnlClass(sig.realizedPnl)}>{fmtPnl(sig.realizedPnl)}</span> },
                      { label: "Unrealized PnL", value: <span className={pnlClass(sig.unrealizedPnl)}>{fmtPnl(sig.unrealizedPnl)}</span> },
                      { label: "RR Achieved", value: sig.riskRewardAchieved != null ? `${Number(sig.riskRewardAchieved).toFixed(2)}x` : "—" },
                      { label: "MFE", value: <span className="text-emerald-600 font-mono">{fmt(sig.maxFavorableExcursion)}</span> },
                      { label: "MAE", value: <span className="text-rose-600 font-mono">{fmt(sig.maxAdverseExcursion)}</span> },
                      { label: "Exec Latency", value: sig.executionLatencyMs != null ? `${sig.executionLatencyMs}ms` : "—" },
                    ].map(({ label: lbl, value }) => (
                      <div key={lbl} className="rounded-lg bg-slate-50 border border-slate-100 px-3 py-2.5">
                        <div className={label}>{lbl}</div>
                        <div className={val}>{value}</div>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Rationale */}
              {sig.reason && (
                <div className="border-b border-slate-100 px-5 py-4">
                  <div className="mb-2 flex items-center gap-2">
                    <Activity className="h-3.5 w-3.5 text-slate-400" />
                    <span className="text-[11px] font-bold uppercase tracking-widest text-slate-400">Signal Rationale</span>
                  </div>
                  <p className="text-sm text-slate-600 leading-relaxed bg-slate-50 rounded-lg px-4 py-3 border border-slate-100">{sig.reason}</p>
                </div>
              )}

              {/* Linked Orders */}
              {(d?.linkedOrders?.length ?? 0) > 0 && (
                <div className="border-b border-slate-100 px-5 py-4">
                  <div className="mb-3 flex items-center gap-2">
                    <Zap className="h-3.5 w-3.5 text-slate-400" />
                    <span className="text-[11px] font-bold uppercase tracking-widest text-slate-400">Linked Orders ({d!.linkedOrders.length})</span>
                  </div>
                  <div className="rounded-xl border border-slate-200 overflow-hidden">
                    <table className="w-full text-xs">
                      <thead>
                        <tr className="border-b border-slate-200 bg-slate-50">
                          {["Symbol", "Side", "Qty", "Mode", "State", "Reject Reason", "Time"].map(h => (
                            <th key={h} className="px-3 py-2 text-left text-[10px] font-semibold uppercase tracking-wide text-slate-400">{h}</th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {d!.linkedOrders.map((o) => (
                          <tr key={o.id} className="border-b border-slate-100 hover:bg-slate-50">
                            <td className="px-3 py-2 font-mono font-semibold text-slate-900">{o.symbol}</td>
                            <td className="px-3 py-2"><SideChip type={o.side} /></td>
                            <td className="px-3 py-2 font-mono text-slate-700">{o.quantity}</td>
                            <td className="px-3 py-2"><ModeChip mode={o.executionMode} /></td>
                            <td className="px-3 py-2">
                              <span className={`rounded-md border px-1.5 py-0.5 text-[10px] font-semibold uppercase ${
                                o.state === "FILLED" ? "border-emerald-200 bg-emerald-50 text-emerald-700" :
                                o.state === "REJECTED" ? "border-rose-200 bg-rose-50 text-rose-700" :
                                "border-slate-200 bg-slate-50 text-slate-500"}`}>{o.state}</span>
                            </td>
                            <td className="px-3 py-2 text-rose-600 max-w-[120px] truncate text-[11px]" title={o.rejectReason ?? ""}>{o.rejectReason ?? "—"}</td>
                            <td className="px-3 py-2 text-slate-400 text-[11px]">{fmtTime(o.createdAt)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              )}

              {/* Execution Timeline */}
              {(d?.executionTimeline?.length ?? 0) > 0 && (
                <div className="px-5 py-4">
                  <div className="mb-3 flex items-center gap-2">
                    <Timer className="h-3.5 w-3.5 text-slate-400" />
                    <span className="text-[11px] font-bold uppercase tracking-widest text-slate-400">Execution Timeline</span>
                  </div>
                  <div className="space-y-0">
                    {d!.executionTimeline.map((ev, i) => (
                      <div key={i} className="flex gap-4">
                        <div className="flex flex-col items-center pt-1 w-4 shrink-0">
                          <div className={`h-2.5 w-2.5 rounded-full ring-2 ring-white ${evDot(ev.eventType)}`} />
                          {i < d!.executionTimeline.length - 1 && (
                            <div className="flex-1 w-px bg-slate-200 mt-1 mb-0" style={{ minHeight: 20 }} />
                          )}
                        </div>
                        <div className="pb-4 flex-1 min-w-0">
                          <div className="flex items-center justify-between gap-2">
                            <span className="text-xs font-semibold text-slate-700">{ev.eventType}</span>
                            <span className="text-[10px] text-slate-400 shrink-0">{fmtTime(ev.createdAt)}</span>
                          </div>
                          {ev.payload && Object.keys(ev.payload).length > 0 && (
                            <div className="mt-1 rounded-lg bg-slate-50 border border-slate-100 px-2.5 py-1.5 font-mono text-[10px] text-slate-500 break-all">
                              {JSON.stringify(ev.payload)}
                            </div>
                          )}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}

// ─── Main Page ────────────────────────────────────────────────────────────────

const QUICK_FILTERS = [
  { label: "Winners",       outcome: "TARGET_HIT",   side: "",     color: "emerald" },
  { label: "SL Hit",        outcome: "STOPLOSS_HIT", side: "",     color: "rose" },
  { label: "Running",       outcome: "RUNNING",      side: "",     color: "sky" },
  { label: "All BUY",       outcome: "",             side: "BUY",  color: "green" },
  { label: "All SELL",      outcome: "",             side: "SELL", color: "red" },
  { label: "Expired",       outcome: "EXPIRED",      side: "",     color: "slate" },
] as const;

export function AdminSignalsPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const [searchParams] = useSearchParams();
  const provenanceFromUrl = searchParams.get("provenance");
  const [page, setPage] = useState(0);
  const [symbol, setSymbol] = useState("");
  const [strategyFilter, setStrategyFilter] = useState("");
  const [signalType, setSignalType] = useState("ALL");
  const [pipeline, setPipeline] = useState("ALL");
  const [outcomeStatus, setOutcomeStatus] = useState("ALL");
  const [includeReplayLab, setIncludeReplayLab] = useState(provenanceFromUrl === "replay");
  const [provenanceTab, setProvenanceTab] = useState<"PROD" | "REPLAY_LAB">(
    provenanceFromUrl === "replay" ? "REPLAY_LAB" : "PROD",
  );
  const [viewMode, setViewMode] = useState<"stream" | "grid">("stream");
  const [showDetailGrid, setShowDetailGrid] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const queryClient = useQueryClient();

  const activatePipeline = useMutation({
    mutationFn: async () => {
      const res = await api.post("/api/admin/signals/activate-pipeline?syncUniverses=true&runImmediatePoll=true");
      try {
        await api.post("/api/admin/signals/seed-replay-candles");
      } catch {
        /* seed endpoint optional on older API builds */
      }
      return res.data?.data;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["admin-signals"] });
      void queryClient.invalidateQueries({ queryKey: ["admin-signal-stats"] });
      void queryClient.invalidateQueries({ queryKey: ["strategy-catalog-signal-stats"] });
    },
  });

  const trackOutcomes = useMutation({
    mutationFn: async () => {
      const res = await api.post("/api/admin/signals/track-outcomes");
      return res.data?.data as { processed?: number };
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["admin-signals"] });
      void queryClient.invalidateQueries({ queryKey: ["admin-signal-stats"] });
      void queryClient.invalidateQueries({ queryKey: ["strategy-catalog-signal-stats"] });
    },
  });

  // Subscribe to admin SSE stream; invalidate signal queries immediately on signal/outcome events
  useEffect(() => {
    const controller = new AbortController();
    const token = localStorage.getItem("accessToken");
    if (!token) return;

    let buffer = "";

    fetch("/api/admin/operations/stream", {
      headers: { Authorization: `Bearer ${token}`, Accept: "text/event-stream" },
      signal: controller.signal,
    })
      .then(async (res) => {
        if (!res.ok || !res.body) return;
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        while (true) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          let sep: number;
          while ((sep = buffer.indexOf("\n\n")) >= 0) {
            const frame = buffer.slice(0, sep).trim();
            buffer = buffer.slice(sep + 2);
            if (frame) {
              const isSignalEvent = frame.includes("event: signal") || frame.includes("event:signal")
                || frame.includes("event: signal_outcome") || frame.includes("event:signal_outcome")
                || frame.includes("event: ops_realtime") || frame.includes("event:ops_realtime");
              if (isSignalEvent) {
                void queryClient.invalidateQueries({ queryKey: ["admin-signals"] });
                void queryClient.invalidateQueries({ queryKey: ["admin-signal-stats"] });
                void queryClient.invalidateQueries({ queryKey: ["strategy-catalog-signal-stats"] });
              }
            }
          }
        }
      })
      .catch(() => {});

    return () => controller.abort();
  }, [queryClient]);

  const statsQ = useQuery<StatsResp>({
    queryKey: ["admin-signal-stats"],
    queryFn: async () => (await api.get("/api/admin/signals/stats")).data?.data,
    refetchInterval: 20_000,
    staleTime: 10_000,
  });

  const strategyStatsQ = useQuery<CatalogSignalStats[]>({
    queryKey: ["strategy-catalog-signal-stats"],
    queryFn: async () => (await api.get("/api/strategies/catalog/signal-stats")).data?.data ?? [],
    refetchInterval: 20_000,
    staleTime: 10_000,
  });

  const strategyOptions = useMemo(() => {
    const rows = [...(strategyStatsQ.data ?? [])].sort(
      (a, b) => (b.signalsToday ?? 0) - (a.signalsToday ?? 0),
    );
    return rows.map((r) => ({
      key: String(r.strategyKey ?? "").trim().toUpperCase(),
      count: r.signalsToday ?? 0,
    })).filter((r) => r.key.length > 0);
  }, [strategyStatsQ.data]);

  const q = useQuery<PageResp>({
    queryKey: ["admin-signals", page, symbol, strategyFilter, signalType, pipeline, outcomeStatus, includeReplayLab],
    queryFn: async () => {
      const p = new URLSearchParams();
      p.set("page", String(page));
      p.set("size", "100");
      p.set("sort", "createdAt,desc");
      if (includeReplayLab) p.set("includeReplayAndLab", "true");
      if (symbol.trim()) p.set("symbol", symbol.trim());
      if (strategyFilter.trim()) p.set("strategyName", strategyFilter.trim());
      if (signalType !== "ALL") p.set("signalType", signalType);
      if (pipeline !== "ALL") p.set("pipeline", pipeline);
      if (outcomeStatus !== "ALL") p.set("outcomeStatus", outcomeStatus);
      const res = await api.get(`/api/admin/signals?${p.toString()}`);
      return res.data?.data as PageResp;
    },
    refetchInterval: 10_000,
  });

  const handleExport = useCallback(() => {
    const rows = q.data?.content ?? [];
    const header = ["time", "strategy", "symbol", "side", "mode", "confidence", "entry", "sl", "target", "qty", "outcome", "pnl", "reason"];
    const csv = [
      header.join(","),
      ...rows.map(r => [
        r.createdAt, r.strategyName, r.symbol, r.signalType, r.pipeline,
        r.confidenceScore, r.entryReferencePrice, r.stopPrice, r.targetPrice,
        r.suggestedQty, r.outcomeStatus, r.realizedPnl,
        `"${(r.reason ?? "").replace(/"/g, "'")}"`
      ].join(","))
    ].join("\n");
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([csv], { type: "text/csv" }));
    a.download = `signals_${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
  }, [q.data]);

  const setQuickFilter = (qf: typeof QUICK_FILTERS[number]) => {
    setOutcomeStatus(qf.outcome || "ALL");
    setSignalType(qf.side || "ALL");
    setPage(0);
  };

  const clearFilters = () => {
    setSymbol(""); setStrategyFilter(""); setSignalType("ALL");
    setPipeline("ALL"); setOutcomeStatus("ALL"); setIncludeReplayLab(false); setPage(0);
  };

  const hasFilters = !!(symbol || strategyFilter || signalType !== "ALL" || pipeline !== "ALL"
    || outcomeStatus !== "ALL" || includeReplayLab);

  const rows = q.data?.content ?? [];
  const filteredRows = useMemo(() => {
    if (provenanceTab === "REPLAY_LAB") {
      return rows.filter((r) => {
        const p = resolveProvenance(r.pipeline, r.signalSource);
        return p === "REPLAY" || p === "LAB";
      });
    }
    return rows.filter((r) => {
      const p = resolveProvenance(r.pipeline, r.signalSource);
      return p === "LIVE" || p === "PAPER" || p === "UNKNOWN";
    });
  }, [rows, provenanceTab]);

  const selectedRow = filteredRows.find((r) => r.id === selectedId) ?? rows.find((r) => r.id === selectedId);
  const total = q.data?.totalElements ?? 0;
  const totalPages = q.data?.totalPages ?? 1;
  const stats = statsQ.data;

  const heatmapRows = useMemo(
    () =>
      [...(strategyStatsQ.data ?? [])]
        .sort((a, b) => (b.signalsToday ?? 0) - (a.signalsToday ?? 0))
        .map((r) => ({ strategyKey: r.strategyKey, signalsToday: r.signalsToday ?? 0 })),
    [strategyStatsQ.data],
  );

  const rowOutcomeBg = (outcome: string | null) => {
    if (outcome === "TARGET_HIT") return "bg-emerald-50/50";
    if (outcome === "STOPLOSS_HIT") return "bg-rose-50/50";
    if (outcome === "RUNNING") return "bg-sky-50/40";
    return "";
  };

  const inputCls = cn(
    "h-9 rounded-xl border px-3 text-xs outline-none transition focus:ring-2 focus:ring-blue-500/30",
    isLight
      ? "border-neutral-300 bg-white text-neutral-900 placeholder-neutral-400"
      : "border-neutral-700 bg-neutral-900/60 text-neutral-100 placeholder-neutral-500",
  );
  const selectCls = inputCls;

  return (
    <AdminPageShell
      isLight={isLight}
      eyebrow="Strategies & signals"
      title="Live Signal Operating System"
      subtitle="Predictive signal intelligence — confidence engine, false breakout detection, cluster storms, and live replay trust surfaces."
      actions={
        <div className="flex flex-wrap items-center gap-2">
          <Link
            to="/admin/signal-lab"
            className={cn(
              "inline-flex items-center gap-1.5 rounded-xl border px-3 py-2 text-xs font-semibold",
              isLight ? "border-indigo-300 bg-indigo-50 text-indigo-800" : "border-indigo-500/40 bg-indigo-500/10 text-indigo-200",
            )}
          >
            <BarChart3 className="h-3.5 w-3.5" /> Lab
          </Link>
          <button
            type="button"
            disabled={activatePipeline.isPending}
            onClick={() => activatePipeline.mutate()}
            className={cn(
              "inline-flex items-center gap-1.5 rounded-xl border px-3 py-2 text-xs font-semibold disabled:opacity-60",
              isLight ? "border-emerald-300 bg-emerald-50 text-emerald-800" : "border-emerald-500/40 bg-emerald-500/10 text-emerald-200",
            )}
          >
            <Zap className={cn("h-3.5 w-3.5", activatePipeline.isPending && "animate-pulse")} />
            {activatePipeline.isPending ? "Activating…" : "Start flow"}
          </button>
          <button type="button" onClick={() => void q.refetch()} className={cn("rounded-xl border px-3 py-2 text-xs font-semibold", isLight ? "border-neutral-300 bg-white" : "border-neutral-700 bg-neutral-900 text-neutral-200")}>
            <RefreshCw className={cn("inline h-3.5 w-3.5", q.isFetching && "animate-spin")} /> Refresh
          </button>
        </div>
      }
    >
      <div className="space-y-6">
        <SignalQualityEngine stats={stats} isLight={isLight} />

        <AdminSection isLight={isLight} title="Signal cluster detection" subtitle="Storms, heat waves, and correlated firing">
          <SignalClusterStormPanel signals={filteredRows} isLight={isLight} />
        </AdminSection>

        <div className="grid gap-4 xl:grid-cols-2">
          <SignalConfidenceEnginePanel signal={selectedRow} isLight={isLight} />
          <SignalReplayIntelPanel signal={selectedRow} isLight={isLight} />
        </div>

        <div className="flex flex-wrap gap-2">
          {(["PROD", "REPLAY_LAB"] as const).map((tab) => (
            <button
              key={tab}
              type="button"
              onClick={() => setProvenanceTab(tab)}
              className={cn(
                "rounded-full border px-4 py-1.5 text-[11px] font-bold uppercase tracking-wide transition",
                provenanceTab === tab
                  ? tab === "PROD"
                    ? isLight
                      ? "border-rose-300 bg-rose-50 text-rose-800"
                      : "border-rose-500/40 bg-rose-500/15 text-rose-100"
                    : isLight
                      ? "border-violet-300 bg-violet-50 text-violet-800"
                      : "border-violet-500/40 bg-violet-500/15 text-violet-100"
                  : isLight
                    ? "border-neutral-200 bg-white text-neutral-600"
                    : "border-neutral-800 bg-neutral-900/50 text-neutral-400",
              )}
            >
              {tab === "PROD" ? "Live + Paper" : "Replay + Lab"}
            </button>
          ))}
          <button
            type="button"
            onClick={() => {
              setIncludeReplayLab(provenanceTab === "REPLAY_LAB");
              setPage(0);
              void q.refetch();
            }}
            className={cn("ml-auto text-[11px] underline-offset-2 hover:underline", isLight ? "text-neutral-500" : "text-neutral-400")}
          >
            Sync API filter
          </button>
        </div>

        {selectedRow ? (
          <AdminPanel isLight={isLight} title="Signal lifecycle" subtitle={`${selectedRow.symbol} · ${selectedRow.strategyName}`}>
            <SignalLifecycleTimeline outcome={selectedRow.outcomeStatus} isLight={isLight} />
          </AdminPanel>
        ) : null}

        <AdminSection isLight={isLight} title="Strategy performance clusters" subtitle="Heat intensity by signals today">
          <StrategyPerformanceHeatmap rows={heatmapRows} isLight={isLight} />
        </AdminSection>

        {/* Filters */}
        <AdminPanel isLight={isLight} title="Stream filters" noPadding>
          <div className="flex flex-wrap items-center gap-2 px-5 py-4">
            <input className={inputCls} placeholder="Symbol…" value={symbol} onChange={(e) => { setSymbol(e.target.value); setPage(0); }} />
            <select className={cn(selectCls, "min-w-[180px]")} value={strategyFilter} onChange={(e) => { setStrategyFilter(e.target.value); setPage(0); }}>
              <option value="">All strategies</option>
              {strategyOptions.map(({ key, count }) => (
                <option key={key} value={key}>{key} ({count})</option>
              ))}
            </select>
            <select className={selectCls} value={signalType} onChange={(e) => { setSignalType(e.target.value); setPage(0); }}>
              <option value="ALL">All sides</option>
              <option value="BUY">BUY</option>
              <option value="SELL">SELL</option>
            </select>
            <select className={selectCls} value={outcomeStatus} onChange={(e) => { setOutcomeStatus(e.target.value); setPage(0); }}>
              <option value="ALL">All outcomes</option>
              <option value="TARGET_HIT">Target hit</option>
              <option value="STOPLOSS_HIT">SL hit</option>
              <option value="RUNNING">Running</option>
              <option value="EXPIRED">Expired</option>
            </select>
            <div className="ml-auto flex gap-2">
              <button type="button" onClick={() => setViewMode("stream")} className={cn("rounded-lg px-3 py-1.5 text-xs font-semibold", viewMode === "stream" ? "bg-blue-600 text-white" : isLight ? "bg-neutral-100" : "bg-neutral-800")}>Stream</button>
              <button type="button" onClick={() => setViewMode("grid")} className={cn("rounded-lg px-3 py-1.5 text-xs font-semibold", viewMode === "grid" ? "bg-blue-600 text-white" : isLight ? "bg-neutral-100" : "bg-neutral-800")}>Grid</button>
              <button type="button" onClick={() => setShowDetailGrid((v) => !v)} className={cn("rounded-lg border px-3 py-1.5 text-xs font-semibold", isLight ? "border-neutral-300" : "border-neutral-700")}>
                {showDetailGrid ? "Hide" : "Show"} detail grid
              </button>
            </div>
          </div>
        </AdminPanel>

        {/* Live signal stream */}
        <AdminSection isLight={isLight} title="Live signal stream" subtitle={`${filteredRows.length} visible · ${total.toLocaleString()} total matched`}>
          {q.isLoading ? (
            <div className={cn("py-16 text-center text-sm", isLight ? "text-neutral-500" : "text-neutral-400")}>
              <RefreshCw className="mx-auto mb-2 h-6 w-6 animate-spin opacity-50" /> Loading signals…
            </div>
          ) : filteredRows.length === 0 ? (
            <div className={cn("rounded-2xl border border-dashed py-16 text-center", isLight ? "border-neutral-300" : "border-neutral-800")}>
              <AlertTriangle className="mx-auto mb-2 h-6 w-6 text-amber-400" />
              <p className="text-sm font-medium">No signals in this provenance lane</p>
            </div>
          ) : (
            <div className={cn(viewMode === "stream" ? "flex flex-col gap-3" : "grid gap-3 sm:grid-cols-2 xl:grid-cols-3")}>
              {filteredRows.map((r, i) => (
                <LiveSignalCard
                  key={r.id}
                  signal={r}
                  isLight={isLight}
                  selected={selectedId === r.id}
                  index={i}
                  onSelect={() => setSelectedId(r.id)}
                />
              ))}
            </div>
          )}
        </AdminSection>

        {showDetailGrid ? (
          <AdminPanel isLight={isLight} title="Detail grid" subtitle="Secondary drill-down surface" noPadding accent>
            <div className="max-h-[420px] overflow-auto">
              <table className="w-full min-w-[900px] border-collapse text-xs">
                <thead className={cn("sticky top-0 z-10 border-b", isLight ? "bg-neutral-50" : "bg-neutral-900")}>
                  <tr>
                    {["Time", "Strategy", "Symbol", "Side", "Conf", "Outcome", "PnL", "Mode"].map((h) => (
                      <th key={h} className="px-3 py-2 text-left text-[10px] font-bold uppercase tracking-wider opacity-60">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {filteredRows.map((r) => (
                    <tr key={r.id} onClick={() => setSelectedId(r.id)} className={cn("cursor-pointer border-b transition hover:bg-blue-500/5", isLight ? "border-neutral-100" : "border-neutral-800", selectedId === r.id && "bg-blue-500/10")}>
                      <td className="px-3 py-2 font-mono opacity-70">{fmtTime(r.createdAt)}</td>
                      <td className="max-w-[140px] truncate px-3 py-2">{r.strategyName}</td>
                      <td className="px-3 py-2 font-mono font-bold">{r.symbol}</td>
                      <td className="px-3 py-2"><SideChip type={r.signalType} /></td>
                      <td className="px-3 py-2"><ConfidenceBar score={r.confidenceScore} /></td>
                      <td className="px-3 py-2"><OutcomeChip status={r.outcomeStatus} /></td>
                      <td className={cn("px-3 py-2 font-mono", pnlClass(r.realizedPnl ?? r.unrealizedPnl))}>{fmtPnl(r.realizedPnl ?? r.unrealizedPnl)}</td>
                      <td className="px-3 py-2"><ModeChip mode={r.pipeline} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </AdminPanel>
        ) : null}

        <div className="flex flex-wrap items-center justify-between gap-3">
          <span className={cn("text-xs", isLight ? "text-neutral-500" : "text-neutral-400")}>
            {total > 0 ? `Page ${page + 1} / ${totalPages} · ${total.toLocaleString()} signals` : "0 results"}
          </span>
          <div className="flex gap-2">
            <button type="button" disabled={page <= 0} onClick={() => setPage((p) => Math.max(0, p - 1))} className={cn("rounded-lg border px-3 py-1.5 text-xs disabled:opacity-40", isLight ? "border-neutral-300 bg-white" : "border-neutral-700 bg-neutral-900")}>← Prev</button>
            <button type="button" disabled={page >= totalPages - 1} onClick={() => setPage((p) => p + 1)} className={cn("rounded-lg border px-3 py-1.5 text-xs disabled:opacity-40", isLight ? "border-neutral-300 bg-white" : "border-neutral-700 bg-neutral-900")}>Next →</button>
            <button type="button" onClick={handleExport} className={cn("rounded-lg border px-3 py-1.5 text-xs", isLight ? "border-neutral-300 bg-white" : "border-neutral-700 bg-neutral-900 text-neutral-200")}>
              <Download className="inline h-3.5 w-3.5" /> CSV
            </button>
          </div>
        </div>
      </div>

      {selectedId && <SignalDetailDrawer id={selectedId} onClose={() => setSelectedId(null)} />}
    </AdminPageShell>
  );
}
