import { useQuery } from "@tanstack/react-query";
import { fmtDateTime } from "../../lib/dateUtils";
import { useState, useCallback } from "react";
import { api } from "../../api/client";
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

// ─── Helpers ─────────────────────────────────────────────────────────────────

const fmt = (v: number | null | undefined, dec = 2) =>
  v == null ? "—" : Number(v).toFixed(dec);

const fmtPnl = (v: number | null | undefined) => {
  if (v == null) return "—";
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
  const [page, setPage] = useState(0);
  const [symbol, setSymbol] = useState("");
  const [strategyName, setStrategyName] = useState("");
  const [signalType, setSignalType] = useState("ALL");
  const [pipeline, setPipeline] = useState("ALL");
  const [outcomeStatus, setOutcomeStatus] = useState("ALL");
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const statsQ = useQuery<StatsResp>({
    queryKey: ["admin-signal-stats"],
    queryFn: async () => (await api.get("/api/admin/signals/stats")).data?.data,
    refetchInterval: 30_000,
    staleTime: 15_000,
  });

  const q = useQuery<PageResp>({
    queryKey: ["admin-signals", page, symbol, strategyName, signalType, pipeline, outcomeStatus],
    queryFn: async () => {
      const p = new URLSearchParams();
      p.set("page", String(page));
      p.set("size", "100");
      p.set("sort", "createdAt,desc");
      if (symbol.trim()) p.set("symbol", symbol.trim());
      if (strategyName.trim()) p.set("strategyName", strategyName.trim());
      if (signalType !== "ALL") p.set("signalType", signalType);
      if (pipeline !== "ALL") p.set("pipeline", pipeline);
      if (outcomeStatus !== "ALL") p.set("outcomeStatus", outcomeStatus);
      const res = await api.get(`/api/admin/signals?${p.toString()}`);
      return res.data?.data as PageResp;
    },
    refetchInterval: 15_000,
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
    setSymbol(""); setStrategyName(""); setSignalType("ALL");
    setPipeline("ALL"); setOutcomeStatus("ALL"); setPage(0);
  };

  const hasFilters = !!(symbol || strategyName || signalType !== "ALL" || pipeline !== "ALL" || outcomeStatus !== "ALL");

  const rows = q.data?.content ?? [];
  const total = q.data?.totalElements ?? 0;
  const totalPages = q.data?.totalPages ?? 1;
  const stats = statsQ.data;

  const rowOutcomeBg = (outcome: string | null) => {
    if (outcome === "TARGET_HIT") return "bg-emerald-50/50";
    if (outcome === "STOPLOSS_HIT") return "bg-rose-50/50";
    if (outcome === "RUNNING") return "bg-sky-50/40";
    return "";
  };

  const inputCls = "h-8 rounded-lg border border-slate-300 bg-white px-3 text-xs text-slate-900 placeholder-slate-400 outline-none focus:border-blue-400 focus:ring-1 focus:ring-blue-100 transition-colors";
  const selectCls = "h-8 rounded-lg border border-slate-300 bg-white px-2 text-xs text-slate-800 outline-none focus:border-blue-400 transition-colors cursor-pointer";

  return (
    <div className="flex h-full flex-col bg-slate-50">

      {/* ── Page Header + KPI strip ─────────────────────────────────────────── */}
      <div className="shrink-0 border-b border-slate-200 bg-white px-6 py-2.5">
        <div className="flex items-center justify-between gap-4 flex-wrap">
          <div className="flex items-center gap-4 flex-wrap">
            <h1 className="text-base font-bold tracking-tight text-slate-900">Signal Intelligence</h1>
            {/* Inline KPI pills */}
            <div className="flex items-center gap-2 flex-wrap text-[11px]">
              {[
                { label: "Today", value: stats?.totalToday ?? 0, cls: "text-blue-700 bg-blue-50 border-blue-200" },
                { label: "BUY", value: stats?.buyToday ?? 0, cls: "text-emerald-700 bg-emerald-50 border-emerald-200" },
                { label: "SELL", value: stats?.sellToday ?? 0, cls: "text-rose-700 bg-rose-50 border-rose-200" },
                { label: "LIVE", value: stats?.liveToday ?? 0, cls: "text-orange-700 bg-orange-50 border-orange-200" },
                { label: "PAPER", value: stats?.paperToday ?? 0, cls: "text-slate-600 bg-slate-50 border-slate-200" },
                { label: "Conf", value: stats?.avgConfidence != null ? `${(stats.avgConfidence * 100).toFixed(0)}%` : "—", cls: "text-violet-700 bg-violet-50 border-violet-200" },
                { label: "Win%",
                  value: stats && (stats.targetHit + stats.slHit) > 0
                    ? `${Math.round(stats.targetHit / (stats.targetHit + stats.slHit) * 100)}%` : "—",
                  cls: "text-teal-700 bg-teal-50 border-teal-200" },
                { label: "All-time", value: (stats?.totalAllTime ?? 0).toLocaleString(), cls: "text-slate-500 bg-white border-slate-200" },
              ].map(({ label, value, cls }) => (
                <span key={label} className={`inline-flex items-center gap-1 rounded-full border px-2 py-0.5 font-semibold ${cls}`}>
                  <span className="text-slate-400 font-normal">{label}</span>
                  <span className="tabular-nums">{value}</span>
                </span>
              ))}
            </div>
          </div>
          <div className="flex items-center gap-2">
            <button type="button" onClick={() => void q.refetch()}
              className="inline-flex items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs font-semibold text-slate-600 hover:bg-slate-50 transition-colors shadow-sm">
              <RefreshCw className={`h-3.5 w-3.5 ${q.isFetching ? "animate-spin" : ""}`} /> Refresh
            </button>
            <button type="button" onClick={handleExport}
              className="inline-flex items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs font-semibold text-slate-600 hover:bg-slate-50 transition-colors shadow-sm">
              <Download className="h-3.5 w-3.5" /> CSV
            </button>
          </div>
        </div>
      </div>

      {/* ── Filter Bar ───────────────────────────────────────────────────────── */}
      <div className="shrink-0 border-b border-slate-200 bg-white px-6 py-2">
        <div className="flex flex-wrap items-center gap-2">
          <input className={inputCls} placeholder="Symbol…" value={symbol}
            onChange={e => { setSymbol(e.target.value); setPage(0); }} />
          <input className={inputCls} placeholder="Strategy…" value={strategyName}
            onChange={e => { setStrategyName(e.target.value); setPage(0); }} />
          <select className={selectCls} value={signalType} onChange={e => { setSignalType(e.target.value); setPage(0); }}>
            <option value="ALL">All sides</option>
            <option value="BUY">BUY</option>
            <option value="SELL">SELL</option>
            <option value="HOLD">HOLD</option>
          </select>
          <select className={selectCls} value={pipeline} onChange={e => { setPipeline(e.target.value); setPage(0); }}>
            <option value="ALL">All modes</option>
            <option value="LIVE">LIVE</option>
            <option value="PAPER">PAPER</option>
            <option value="SIMULATED">SIMULATED</option>
          </select>
          <select className={selectCls} value={outcomeStatus} onChange={e => { setOutcomeStatus(e.target.value); setPage(0); }}>
            <option value="ALL">All outcomes</option>
            <option value="TARGET_HIT">Target Hit</option>
            <option value="STOPLOSS_HIT">SL Hit</option>
            <option value="RUNNING">Running</option>
            <option value="PARTIAL_TARGET">Partial Target</option>
            <option value="BREAKEVEN_EXIT">Breakeven</option>
            <option value="EXPIRED">Expired</option>
            <option value="MISSED">Missed</option>
            <option value="REJECTED">Rejected</option>
            <option value="CANCELLED">Cancelled</option>
            <option value="NO_EXECUTION">No Execution</option>
          </select>

          {/* Quick filters */}
          <div className="flex items-center gap-1 ml-1 border-l border-slate-200 pl-3">
            {QUICK_FILTERS.map(qf => (
              <button key={qf.label} type="button"
                onClick={() => setQuickFilter(qf)}
                className="rounded-full border border-slate-200 bg-slate-50 px-2.5 py-0.5 text-[10px] font-semibold text-slate-600 hover:bg-slate-100 hover:border-slate-300 transition-colors whitespace-nowrap">
                {qf.label}
              </button>
            ))}
          </div>

          {hasFilters && (
            <button type="button" onClick={clearFilters}
              className="ml-auto flex items-center gap-1 text-xs text-slate-400 hover:text-rose-500 transition-colors">
              <X className="h-3 w-3" /> Clear
            </button>
          )}
          {!hasFilters && (
            <span className="ml-auto text-[11px] text-slate-400">auto-refresh 15s</span>
          )}
        </div>
      </div>

      {/* ── Data Grid ────────────────────────────────────────────────────────── */}
      <div className="flex-1 overflow-auto">
        <table className="w-full min-w-[1100px] text-xs border-collapse">
          <thead className="sticky top-0 z-10 border-b border-slate-200 bg-slate-50">
            <tr>
              {["Time", "Strategy", "Symbol", "Side", "Confidence", "Entry", "SL", "Target", "Qty", "Outcome", "PnL", "Mode"].map(h => (
                <th key={h} className="whitespace-nowrap px-4 py-2 text-left text-[10px] font-bold uppercase tracking-widest text-slate-400 border-r border-slate-100 last:border-r-0">
                  {h}
                </th>
              ))}
              <th className="w-8 px-2" />
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {q.isLoading && (
              <tr><td colSpan={13} className="px-4 py-16 text-center text-slate-400">
                <RefreshCw className="mx-auto mb-2 h-5 w-5 animate-spin opacity-50" />
                Loading signals…
              </td></tr>
            )}
            {!q.isLoading && rows.length === 0 && (
              <tr><td colSpan={13} className="px-4 py-16 text-center">
                <AlertTriangle className="mx-auto mb-2 h-6 w-6 text-amber-400" />
                <div className="text-sm font-medium text-slate-600">No signals found</div>
                <div className="text-xs text-slate-400 mt-1">Try adjusting the filters above</div>
              </td></tr>
            )}
            {rows.map((r) => (
              <tr key={r.id}
                onClick={() => setSelectedId(r.id)}
                className={`group cursor-pointer transition-colors hover:bg-blue-50/60 ${rowOutcomeBg(r.outcomeStatus)} ${selectedId === r.id ? "bg-blue-50 ring-1 ring-inset ring-blue-200" : ""}`}>
                <td className="whitespace-nowrap px-3 py-1.5 font-mono text-slate-400 text-[11px]">{fmtTime(r.createdAt)}</td>
                <td className="max-w-[150px] px-3 py-1.5 font-medium text-slate-700 truncate" title={r.strategyName ?? ""}>{r.strategyName ?? "—"}</td>
                <td className="px-3 py-1.5 font-mono font-bold text-slate-900">{r.symbol ?? "—"}</td>
                <td className="px-3 py-1.5"><SideChip type={r.signalType} /></td>
                <td className="px-3 py-1.5"><ConfidenceBar score={r.confidenceScore} /></td>
                <td className="px-3 py-1.5 font-mono text-slate-700">{fmt(r.entryReferencePrice)}</td>
                <td className="px-3 py-1.5 font-mono text-rose-600">{fmt(r.stopPrice)}</td>
                <td className="px-3 py-1.5 font-mono text-emerald-600">{fmt(r.targetPrice)}</td>
                <td className="px-3 py-1.5 font-mono text-slate-600">{fmt(r.suggestedQty, 0)}</td>
                <td className="px-3 py-1.5"><OutcomeChip status={r.outcomeStatus} /></td>
                <td className={`px-3 py-1.5 font-mono text-xs ${pnlClass(r.realizedPnl ?? r.unrealizedPnl)}`}>
                  {fmtPnl(r.realizedPnl ?? r.unrealizedPnl)}
                </td>
                <td className="px-3 py-1.5"><ModeChip mode={r.pipeline} /></td>
                <td className="px-2 py-1.5 text-slate-300 group-hover:text-slate-500 transition-colors">
                  <ChevronRight className="h-3.5 w-3.5" />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* ── Pagination ───────────────────────────────────────────────────────── */}
      <div className="shrink-0 border-t border-slate-200 bg-white px-6 py-3 flex items-center justify-between">
        <span className="text-xs text-slate-500">
          {total > 0 ? `${page * 100 + 1}–${Math.min((page + 1) * 100, total)} of ${total.toLocaleString()} signals` : "0 results"}
        </span>
        <div className="flex items-center gap-2">
          <button type="button" disabled={page <= 0} onClick={() => setPage(p => Math.max(0, p - 1))}
            className="rounded-lg border border-slate-300 bg-white px-3 py-1 text-xs font-medium text-slate-600 hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors shadow-sm">
            ← Prev
          </button>
          <span className="text-xs font-medium text-slate-500 tabular-nums px-2">Page {page + 1} / {totalPages}</span>
          <button type="button" disabled={page >= totalPages - 1} onClick={() => setPage(p => p + 1)}
            className="rounded-lg border border-slate-300 bg-white px-3 py-1 text-xs font-medium text-slate-600 hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed transition-colors shadow-sm">
            Next →
          </button>
        </div>
      </div>

      {selectedId && <SignalDetailDrawer id={selectedId} onClose={() => setSelectedId(null)} />}
    </div>
  );
}
