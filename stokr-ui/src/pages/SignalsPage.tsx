import { useQuery } from "@tanstack/react-query";
import { AnimatePresence, motion } from "framer-motion";
import {
  TRADER_EXECUTION_MODE_QUERY_KEY,
  fetchTraderExecutionMode,
  signalMatchesExecutionMode,
} from "../lib/traderExecutionMode";
import { fmtDateTime } from "../lib/dateUtils";
import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../api/client";
import { cn } from "../lib/utils";
import { useUiThemeStore } from "../state/uiTheme";
import { normalizeSignalRow } from "../lib/intradaySignals";
import {
  passesHighConvictionFilter,
  resolveConfirmation,
  sortSignals,
  lookupAdvScore,
  type SignalSortMode,
} from "../lib/confirmationRank";
import { useAdvAiScoreMap } from "../hooks/useAdvAiScoreMap";
import { ConfirmationTierChip } from "../components/confirmation/ConfirmationTopPick";
import {
  TrendingUp, TrendingDown, Minus, RefreshCw, X, ChevronRight, AlertTriangle,
  Target, ShieldAlert, Zap, Activity, DollarSign, BarChart3, CheckCircle2, XCircle, Clock
} from "lucide-react";

type SignalRow = {
  id: string;
  createdAt: string | null;
  symbol: string | null;
  signalType: string | null;
  strategyName: string | null;
  reason: string | null;
  suggestedQty: string | null;
  confidenceScore: string | null;
  entryReferencePrice?: string | null;
  stopPrice?: string | null;
  targetPrice?: string | null;
  pipeline?: string | null;
  marketRegime?: string | null;
  // Enriched fields
  outcomeStatus?: string | null;
  exitPrice?: string | null;
  realizedPnl?: string | null;
  unrealizedPnl?: string | null;
  ltp?: string | null;
  pnl?: string | null;
};

const fmtTime = (ts: string | null | undefined): string => {
  if (!ts) return "—";
  try {
    const d = new Date(ts);
    return d.toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit", hour12: true });
  } catch {
    return "—";
  }
};

const fmt = (v: string | number | null | undefined, dec = 2) =>
  v == null || v === "" ? "—" : Number(v).toFixed(dec);

const fmtPnl = (v: string | number | null | undefined) => {
  if (v == null || v === "") return null;
  const n = Number(v);
  if (isNaN(n)) return null;
  return n;
};

function SideChip({ type, light }: { type: string | null; light: boolean }) {
  const t = (type ?? "").toUpperCase();
  if (t === "BUY") return (
    <span className={cn("inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-[11px] font-bold",
      light ? "bg-emerald-100 text-emerald-700" : "bg-emerald-500/15 text-emerald-400")}>
      <TrendingUp className="h-3 w-3" /> BUY
    </span>
  );
  if (t === "SELL") return (
    <span className={cn("inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-[11px] font-bold",
      light ? "bg-rose-100 text-rose-700" : "bg-rose-500/15 text-rose-400")}>
      <TrendingDown className="h-3 w-3" /> SELL
    </span>
  );
  return (
    <span className={cn("inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-[11px] font-bold",
      light ? "bg-neutral-100 text-neutral-500" : "bg-neutral-700/60 text-neutral-400")}>
      <Minus className="h-3 w-3" /> {t || "—"}
    </span>
  );
}

function OutcomeBadge({ status, light }: { status: string | null | undefined; light: boolean }) {
  if (!status) return (
    <span className={cn("inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[10px] font-semibold",
      light ? "bg-blue-50 text-blue-600" : "bg-blue-500/10 text-blue-400")}>
      <Activity className="h-2.5 w-2.5" /> RUNNING
    </span>
  );
  const s = status.toUpperCase();
  if (s === "TARGET_HIT") return (
    <span className={cn("inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[10px] font-semibold",
      light ? "bg-emerald-50 text-emerald-700" : "bg-emerald-500/10 text-emerald-400")}>
      <Target className="h-2.5 w-2.5" /> TARGET
    </span>
  );
  if (s === "SL_HIT") return (
    <span className={cn("inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[10px] font-semibold",
      light ? "bg-rose-50 text-rose-700" : "bg-rose-500/10 text-rose-400")}>
      <ShieldAlert className="h-2.5 w-2.5" /> SL HIT
    </span>
  );
  if (s === "PRESSURE_EXIT") return (
    <span className={cn("inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[10px] font-semibold",
      light ? "bg-amber-50 text-amber-700" : "bg-amber-500/10 text-amber-400")}>
      <Zap className="h-2.5 w-2.5" /> PRESSURE
    </span>
  );
  if (s === "CLOSED" || s === "EXPIRED") return (
    <span className={cn("inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[10px] font-semibold",
      light ? "bg-neutral-100 text-neutral-600" : "bg-neutral-700/40 text-neutral-400")}>
      <XCircle className="h-2.5 w-2.5" /> {s}
    </span>
  );
  return (
    <span className={cn("inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[10px] font-semibold",
      light ? "bg-neutral-100 text-neutral-500" : "bg-neutral-700/40 text-neutral-400")}>
      {status}
    </span>
  );
}

function PnlCell({ value, light }: { value: string | number | null | undefined; light: boolean }) {
  const n = fmtPnl(value);
  if (n == null) return <span className={light ? "text-neutral-400" : "text-neutral-500"}>—</span>;
  const positive = n > 0;
  const negative = n < 0;
  return (
    <span className={cn("font-mono font-semibold",
      positive ? (light ? "text-emerald-600" : "text-emerald-400") :
      negative ? (light ? "text-rose-600" : "text-rose-400") :
      (light ? "text-neutral-500" : "text-neutral-400"))}>
      {positive ? "+" : ""}{n.toFixed(2)}
    </span>
  );
}

function SummaryBanner({ rows, light }: { rows: SignalRow[]; light: boolean }) {
  const total = rows.length;
  const running = rows.filter(r => !r.outcomeStatus).length;
  const targetHit = rows.filter(r => (r.outcomeStatus ?? "").toUpperCase() === "TARGET_HIT").length;
  const slHit = rows.filter(r => (r.outcomeStatus ?? "").toUpperCase() === "SL_HIT").length;
  const pressureExit = rows.filter(r => (r.outcomeStatus ?? "").toUpperCase() === "PRESSURE_EXIT").length;
  const closed = rows.filter(r => {
    const s = (r.outcomeStatus ?? "").toUpperCase();
    return s === "CLOSED" || s === "EXPIRED";
  }).length;

  let totalPnl = 0;
  let winners = 0;
  let losers = 0;
  for (const r of rows) {
    const pnl = fmtPnl(r.pnl);
    if (pnl != null) {
      totalPnl += pnl;
      if (pnl > 0) winners++;
      else if (pnl < 0) losers++;
    }
  }

  const winRate = (winners + losers) > 0 ? ((winners / (winners + losers)) * 100).toFixed(1) : "—";

  const cardCls = cn(
    "rounded-xl border px-4 py-3 flex flex-col items-center min-w-[110px]",
    light ? "border-neutral-200 bg-white" : "border-white/[0.07] bg-white/[0.03]"
  );
  const labelCls = cn("text-[10px] font-semibold uppercase tracking-widest",
    light ? "text-neutral-400" : "text-neutral-500");
  const valCls = cn("text-lg font-bold font-mono mt-0.5",
    light ? "text-neutral-900" : "text-white");

  return (
    <div className={cn("rounded-xl border p-4", light ? "border-neutral-200 bg-gradient-to-r from-neutral-50 to-white" : "border-white/[0.07] bg-gradient-to-r from-neutral-900/60 to-neutral-950")}>
      <div className="flex items-center gap-2 mb-3">
        <BarChart3 className={cn("h-4 w-4", light ? "text-neutral-500" : "text-neutral-400")} />
        <span className={cn("text-xs font-bold uppercase tracking-widest", light ? "text-neutral-600" : "text-neutral-300")}>
          Signal Performance Summary
        </span>
      </div>
      <div className="flex flex-wrap gap-3">
        <div className={cardCls}>
          <div className={labelCls}>Total</div>
          <div className={valCls}>{total}</div>
        </div>
        <div className={cardCls}>
          <div className={cn(labelCls, light ? "text-blue-500" : "text-blue-400")}>
            <span className="flex items-center gap-1"><Clock className="h-2.5 w-2.5" /> Running</span>
          </div>
          <div className={cn(valCls, light ? "text-blue-600" : "text-blue-400")}>{running}</div>
        </div>
        <div className={cardCls}>
          <div className={cn(labelCls, light ? "text-emerald-500" : "text-emerald-400")}>
            <span className="flex items-center gap-1"><Target className="h-2.5 w-2.5" /> Target Hit</span>
          </div>
          <div className={cn(valCls, light ? "text-emerald-600" : "text-emerald-400")}>{targetHit}</div>
        </div>
        <div className={cardCls}>
          <div className={cn(labelCls, light ? "text-rose-500" : "text-rose-400")}>
            <span className="flex items-center gap-1"><ShieldAlert className="h-2.5 w-2.5" /> SL Hit</span>
          </div>
          <div className={cn(valCls, light ? "text-rose-600" : "text-rose-400")}>{slHit}</div>
        </div>
        <div className={cardCls}>
          <div className={cn(labelCls, light ? "text-amber-500" : "text-amber-400")}>
            <span className="flex items-center gap-1"><Zap className="h-2.5 w-2.5" /> Pressure Exit</span>
          </div>
          <div className={cn(valCls, light ? "text-amber-600" : "text-amber-400")}>{pressureExit}</div>
        </div>
        {closed > 0 && (
          <div className={cardCls}>
            <div className={labelCls}>Closed</div>
            <div className={valCls}>{closed}</div>
          </div>
        )}
        <div className={cn(cardCls, "border-l-2", totalPnl >= 0 ? (light ? "border-l-emerald-400" : "border-l-emerald-500") : (light ? "border-l-rose-400" : "border-l-rose-500"))}>
          <div className={cn(labelCls)}>
            <span className="flex items-center gap-1"><DollarSign className="h-2.5 w-2.5" /> Total P&L</span>
          </div>
          <div className={cn(valCls,
            totalPnl > 0 ? (light ? "text-emerald-600" : "text-emerald-400") :
            totalPnl < 0 ? (light ? "text-rose-600" : "text-rose-400") :
            (light ? "text-neutral-600" : "text-neutral-300"))}>
            {totalPnl > 0 ? "+" : ""}{totalPnl.toFixed(2)}
          </div>
        </div>
        <div className={cardCls}>
          <div className={cn(labelCls)}>
            <span className="flex items-center gap-1"><CheckCircle2 className="h-2.5 w-2.5" /> Win Rate</span>
          </div>
          <div className={valCls}>{winRate}{winRate !== "—" ? "%" : ""}</div>
        </div>
        <div className={cardCls}>
          <div className={cn(labelCls, light ? "text-emerald-500" : "text-emerald-400")}>Winners</div>
          <div className={cn(valCls, light ? "text-emerald-600" : "text-emerald-400")}>{winners}</div>
        </div>
        <div className={cardCls}>
          <div className={cn(labelCls, light ? "text-rose-500" : "text-rose-400")}>Losers</div>
          <div className={cn(valCls, light ? "text-rose-600" : "text-rose-400")}>{losers}</div>
        </div>
      </div>
    </div>
  );
}

function SignalDetailDrawer({
  signal,
  onClose,
  light,
  advMap,
}: {
  signal: SignalRow;
  onClose: () => void;
  light: boolean;
  advMap?: Map<string, number>;
}) {
  const bg = light ? "bg-white" : "bg-neutral-950";
  const border = light ? "border-neutral-200" : "border-white/[0.07]";
  const cardBg = light ? "bg-neutral-50" : "bg-white/[0.04]";
  const labelCls = "text-[10px] font-semibold uppercase tracking-widest " + (light ? "text-neutral-400" : "text-neutral-500");
  const valueCls = "mt-1 font-mono text-sm " + (light ? "text-neutral-900" : "text-white");

  const pnlVal = fmtPnl(signal.pnl);
  const normalized = normalizeSignalRow(signal as Record<string, unknown>);
  const confirmation = resolveConfirmation(normalized, lookupAdvScore(normalized, advMap));

  return (
    <>
      <motion.button
        type="button"
        aria-label="Close signal details"
        className="fixed inset-0 z-40 bg-black/50 backdrop-blur-sm"
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        onClick={onClose}
      />
      <motion.aside
        role="dialog"
        aria-labelledby="signal-drawer-title"
        initial={{ x: "100%" }}
        animate={{ x: 0 }}
        exit={{ x: "100%" }}
        transition={{ type: "spring", damping: 28, stiffness: 320 }}
        className={cn(
          "fixed right-0 top-0 z-50 flex h-full w-full max-w-xl flex-col overflow-hidden shadow-2xl ring-1",
          bg,
          border,
        )}
      >
          <div className={cn("flex items-center justify-between border-b px-5 py-3.5", border, light ? "bg-neutral-50" : "bg-neutral-900/80")}>
            <div className="flex items-center gap-2">
              <span id="signal-drawer-title" className={cn("text-sm font-bold", light ? "text-neutral-900" : "text-white")}>
                {signal.strategyName ?? "Signal"}
              </span>
              <SideChip type={signal.signalType} light={light} />
              <OutcomeBadge status={signal.outcomeStatus} light={light} />
            </div>
            <button type="button" onClick={onClose} className={cn("rounded-lg p-1.5", light ? "text-neutral-400 hover:bg-neutral-100" : "text-neutral-400 hover:bg-white/[0.07]")}>
              <X className="h-4 w-4" />
            </button>
          </div>
          <motion.div
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.06 }}
            className="flex-1 space-y-4 overflow-y-auto p-5"
          >
            {/* P&L Hero Card */}
            {pnlVal != null && (
              <div className={cn("rounded-xl border px-5 py-4 text-center", border,
                pnlVal > 0 ? (light ? "bg-emerald-50 border-emerald-200" : "bg-emerald-500/[0.07] border-emerald-500/20") :
                pnlVal < 0 ? (light ? "bg-rose-50 border-rose-200" : "bg-rose-500/[0.07] border-rose-500/20") :
                cardBg)}>
                <div className={cn("text-[10px] font-semibold uppercase tracking-widest mb-1",
                  light ? "text-neutral-400" : "text-neutral-500")}>P&L</div>
                <div className={cn("text-2xl font-bold font-mono",
                  pnlVal > 0 ? (light ? "text-emerald-600" : "text-emerald-400") :
                  pnlVal < 0 ? (light ? "text-rose-600" : "text-rose-400") :
                  (light ? "text-neutral-600" : "text-neutral-300"))}>
                  {pnlVal > 0 ? "+" : ""}{pnlVal.toFixed(2)}
                </div>
              </div>
            )}

            <div className="grid grid-cols-2 gap-3">
              {[
                { label: "Symbol", value: signal.symbol ?? "—" },
                { label: "Time", value: fmtTime(signal.createdAt) },
                { label: "Entry Price", value: fmt(signal.entryReferencePrice) },
                { label: "LTP", value: <span className={cn("font-bold", light ? "text-sky-600" : "text-sky-400")}>{fmt(signal.ltp)}</span> },
                { label: "Stop Loss", value: <span className={light ? "text-rose-600" : "text-rose-400"}>{fmt(signal.stopPrice)}</span> },
                { label: "Target", value: <span className={light ? "text-emerald-600" : "text-emerald-400"}>{fmt(signal.targetPrice)}</span> },
                { label: "Exit Price", value: signal.exitPrice ? fmt(signal.exitPrice) : "—" },
                { label: "Qty", value: fmt(signal.suggestedQty) },
                { label: "Setup rank", value: <ConfirmationTierChip rank={confirmation} isLight={light} /> },
                { label: "Confidence", value: signal.confidenceScore != null ? `${(Number(signal.confidenceScore) * 100).toFixed(1)}%` : "—" },
                {
                  label: "Risk : Reward",
                  value:
                    confirmation.riskReward != null ? `${confirmation.riskReward.toFixed(2)}×` : "—",
                },
                { label: "Outcome", value: <OutcomeBadge status={signal.outcomeStatus} light={light} /> },
              ].map(({ label, value }) => (
                <motion.div
                  key={label}
                  whileHover={{ y: -1 }}
                  className={cn("rounded-xl px-3 py-2.5 transition-shadow hover:shadow-sm", cardBg)}
                >
                  <div className={labelCls}>{label}</div>
                  <div className={valueCls}>{value}</div>
                </motion.div>
              ))}
            </div>
            {signal.reason && (
              <div className={cn("rounded-xl border px-4 py-3", border, cardBg)}>
                <div className={cn("mb-1", labelCls)}>Rationale</div>
                <div className={cn("text-xs leading-relaxed", light ? "text-neutral-700" : "text-neutral-300")}>{signal.reason}</div>
              </div>
            )}
          </motion.div>
        </motion.aside>
    </>
  );
}

export function SignalsPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const navigate = useNavigate();
  const [symbol, setSymbol] = useState("");
  const [signalType, setSignalType] = useState("ALL");
  const [outcomeFilter, setOutcomeFilter] = useState("ALL");
  const [highConvictionOnly, setHighConvictionOnly] = useState(false);
  const [signalSortMode, setSignalSortMode] = useState<SignalSortMode>("time");
  const [selectedSignal, setSelectedSignal] = useState<SignalRow | null>(null);
  const { advMap } = useAdvAiScoreMap(true);

  const modeQ = useQuery({
    queryKey: [...TRADER_EXECUTION_MODE_QUERY_KEY],
    queryFn: fetchTraderExecutionMode,
    staleTime: 30_000,
  });
  const executionMode = modeQ.data ?? "PAPER";

  const q = useQuery<SignalRow[]>({
    queryKey: ["trader-signals-feed", executionMode],
    queryFn: async () => {
      const res = await api.get("/api/trader/strategy-feed?limit=500");
      return (Array.isArray(res.data?.data) ? res.data.data : []) as SignalRow[];
    },
    refetchInterval: 15_000,
  });

  const rows = useMemo(() => {
    const filtered = (q.data ?? []).filter((r) => {
      const symOk = !symbol.trim() || (r.symbol ?? "").toUpperCase().includes(symbol.toUpperCase());
      const typeOk = signalType === "ALL" || (r.signalType ?? "").toUpperCase() === signalType;
      const outcomeOk = outcomeFilter === "ALL"
        || (outcomeFilter === "RUNNING" && !r.outcomeStatus)
        || (outcomeFilter === "TARGET_HIT" && (r.outcomeStatus ?? "").toUpperCase() === "TARGET_HIT")
        || (outcomeFilter === "SL_HIT" && (r.outcomeStatus ?? "").toUpperCase() === "SL_HIT")
        || (outcomeFilter === "PRESSURE_EXIT" && (r.outcomeStatus ?? "").toUpperCase() === "PRESSURE_EXIT")
        || (outcomeFilter === "PROFIT" && fmtPnl(r.pnl) != null && fmtPnl(r.pnl)! > 0)
        || (outcomeFilter === "LOSS" && fmtPnl(r.pnl) != null && fmtPnl(r.pnl)! < 0);
      if (!symOk || !typeOk || !outcomeOk || !signalMatchesExecutionMode(r, executionMode)) {
        return false;
      }
      if (highConvictionOnly) {
        return passesHighConvictionFilter(normalizeSignalRow(r as Record<string, unknown>), advMap);
      }
      return true;
    });
    return sortSignals(
      filtered.map((r) => normalizeSignalRow(r as Record<string, unknown>) as SignalRow),
      signalSortMode,
      advMap,
    );
  }, [q.data, symbol, signalType, outcomeFilter, executionMode, highConvictionOnly, signalSortMode, advMap]);

  const bg = isLight ? "bg-white" : "bg-neutral-950";
  const borderCls = isLight ? "border-neutral-200" : "border-white/[0.07]";
  const hdrBg = isLight ? "bg-neutral-50/95" : "bg-neutral-900/95";
  const rowHover = isLight ? "hover:bg-neutral-50" : "hover:bg-white/[0.04]";
  const rowAlt = isLight ? "bg-neutral-50/50" : "bg-white/[0.01]";
  const textMuted = isLight ? "text-neutral-400" : "text-neutral-500";
  const inputCls = cn(
    "rounded-lg border px-3 py-1.5 text-xs outline-none focus:ring-1",
    isLight
      ? "border-neutral-200 bg-white text-neutral-900 placeholder-neutral-400 focus:border-blue-400 focus:ring-blue-100"
      : "border-white/[0.1] bg-white/[0.05] text-white placeholder-neutral-500 focus:border-sky-500/60"
  );
  const selectCls = cn(
    "rounded-lg border px-2 py-1.5 text-xs outline-none",
    isLight ? "border-neutral-200 bg-white text-neutral-800" : "border-white/[0.1] bg-neutral-900 text-white"
  );

  return (
    <div className="flex min-h-[calc(100vh-11rem)] flex-col space-y-4">
      {/* Header */}
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className={cn("text-xl font-bold tracking-tight", isLight ? "text-neutral-900" : "text-white")}>
            My Signals
          </h1>
          <p className={cn("mt-0.5 text-xs", textMuted)}>
            Strategy-generated signals with live P&L tracking · {rows.length} shown · auto-refresh 15s
          </p>
        </div>
        <button
          type="button"
          onClick={() => void q.refetch()}
          className={cn("flex items-center gap-1.5 rounded-lg border px-3 py-1.5 text-xs font-semibold transition",
            isLight ? "border-neutral-200 bg-white text-neutral-600 hover:bg-neutral-50" : "border-white/[0.1] bg-white/[0.05] text-neutral-300 hover:bg-white/[0.09]")}
        >
          <RefreshCw className={cn("h-3.5 w-3.5", q.isFetching && "animate-spin")} /> Refresh
        </button>
      </div>

      {/* Summary Stats Banner */}
      {rows.length > 0 && <SummaryBanner rows={rows} light={isLight} />}

      {/* Filters */}
      <div className={cn("flex flex-wrap items-center gap-2 rounded-xl border px-4 py-3",
        isLight ? "border-neutral-200 bg-neutral-50" : "border-white/[0.07] bg-white/[0.03]")}>
        <input className={inputCls} placeholder="Filter symbol..." value={symbol} onChange={e => setSymbol(e.target.value)} />
        <select className={selectCls} value={signalType} onChange={e => setSignalType(e.target.value)}>
          <option value="ALL">All sides</option>
          <option value="BUY">BUY</option>
          <option value="SELL">SELL</option>
          <option value="HOLD">HOLD</option>
        </select>
        <select className={selectCls} value={outcomeFilter} onChange={e => setOutcomeFilter(e.target.value)}>
          <option value="ALL">All outcomes</option>
          <option value="RUNNING">Running</option>
          <option value="TARGET_HIT">Target Hit</option>
          <option value="SL_HIT">SL Hit</option>
          <option value="PRESSURE_EXIT">Pressure Exit</option>
          <option value="PROFIT">In Profit</option>
          <option value="LOSS">In Loss</option>
        </select>
        <select className={selectCls} value={signalSortMode} onChange={e => setSignalSortMode(e.target.value as SignalSortMode)}>
          <option value="confirmation">Sort: confirmation</option>
          <option value="time">Sort: newest</option>
        </select>
        <label
          className={cn(
            "inline-flex cursor-pointer items-center gap-2 rounded-lg border px-3 py-1.5 text-xs font-semibold",
            highConvictionOnly
              ? isLight
                ? "border-emerald-300 bg-emerald-50 text-emerald-800"
                : "border-emerald-500/40 bg-emerald-500/10 text-emerald-300"
              : isLight
                ? "border-neutral-200 bg-white text-neutral-600"
                : "border-white/[0.1] bg-white/[0.05] text-neutral-300",
          )}
        >
          <input
            type="checkbox"
            className="sr-only"
            checked={highConvictionOnly}
            onChange={e => setHighConvictionOnly(e.target.checked)}
          />
          High conviction only
        </label>
        <button
          type="button"
          onClick={() => navigate("/intraday")}
          className={cn(
            "rounded-lg border px-3 py-1.5 text-xs font-semibold",
            isLight ? "border-indigo-200 text-indigo-700 hover:bg-indigo-50" : "border-indigo-500/30 text-indigo-300",
          )}
        >
          Intraday top pick
        </button>
        {(symbol || signalType !== "ALL" || outcomeFilter !== "ALL" || highConvictionOnly) && (
          <button type="button" onClick={() => { setSymbol(""); setSignalType("ALL"); setOutcomeFilter("ALL"); setHighConvictionOnly(false); }}
            className={cn("flex items-center gap-1 text-xs", textMuted, "hover:text-red-500")}>
            <X className="h-3 w-3" /> Clear
          </button>
        )}
      </div>

      {/* Empty state */}
      {!q.isLoading && rows.length === 0 && (
        <div className={cn("flex flex-col items-center gap-3 rounded-xl border px-6 py-10 text-center",
          isLight ? "border-amber-200 bg-amber-50 text-amber-800" : "border-amber-500/25 bg-amber-500/[0.07] text-amber-300")}>
          <AlertTriangle className="h-8 w-8 opacity-60" />
          <div>
            <div className="font-semibold">{highConvictionOnly ? "No high-conviction signals" : "No signals yet"}</div>
            <div className="mt-1 text-xs opacity-80">
              {highConvictionOnly
                ? "Try turning off High conviction only or check Intraday Terminal for A-tier setups."
                : "Signals appear here once the market opens and your strategies begin scanning."}
            </div>
          </div>
          <div className="flex gap-2 mt-1">
            <button type="button" onClick={() => navigate("/strategies")}
              className={cn("rounded-lg border px-3 py-1.5 text-xs font-semibold",
                isLight ? "border-amber-400 bg-white text-amber-800 hover:bg-amber-50" : "border-amber-500/40 text-amber-200 hover:bg-amber-500/15")}>
              My Strategies
            </button>
            <button type="button" onClick={() => navigate("/intraday")}
              className={cn("rounded-lg border px-3 py-1.5 text-xs font-semibold",
                isLight ? "border-neutral-300 bg-white text-neutral-700 hover:bg-neutral-50" : "border-white/[0.1] text-neutral-300 hover:bg-white/[0.07]")}>
              Intraday Terminal
            </button>
          </div>
        </div>
      )}

      {/* Table */}
      {(q.isLoading || rows.length > 0) && (
        <div className={cn("flex-1 overflow-auto rounded-xl border", borderCls, bg)}>
          <table className="w-full min-w-[1350px] text-xs">
            <thead className={cn("sticky top-0 z-10 border-b", borderCls, hdrBg)}>
              <tr>
                {["Time", "Strategy", "Symbol", "Side", "Entry", "LTP", "SL", "Target", "RR", "Rank", "P&L", "Status", "Conf", "Rationale"].map(h => (
                  <th key={h} className={cn("whitespace-nowrap px-4 py-3 text-left text-[10px] font-bold uppercase tracking-widest", textMuted)}>{h}</th>
                ))}
                <th className="w-8" />
              </tr>
            </thead>
            <tbody>
              {q.isLoading && (
                <tr><td colSpan={15} className={cn("px-4 py-12 text-center", textMuted)}>Loading signals...</td></tr>
              )}
              {rows.length === 0 ? null : rows.map((r, i) => {
                const pnlVal = fmtPnl(r.pnl);
                const confirmation = resolveConfirmation(
                  normalizeSignalRow(r as Record<string, unknown>),
                  lookupAdvScore(normalizeSignalRow(r as Record<string, unknown>), advMap),
                );
                return (
                  <tr key={r.id}
                    onClick={() => setSelectedSignal(r)}
                    className={cn(
                      "cursor-pointer border-b transition-colors", borderCls,
                      i % 2 === 0 ? rowAlt : "",
                      rowHover,
                      selectedSignal?.id === r.id && (isLight ? "bg-blue-50" : "bg-sky-500/[0.07]"),
                      pnlVal != null && pnlVal > 0 && !isLight && "bg-emerald-500/[0.02]",
                      pnlVal != null && pnlVal < 0 && !isLight && "bg-rose-500/[0.02]",
                    )}>
                    <td className={cn("whitespace-nowrap px-4 py-3 font-mono", textMuted)}>{fmtTime(r.createdAt)}</td>
                    <td className={cn("max-w-[140px] truncate px-4 py-3 font-medium", isLight ? "text-neutral-800" : "text-white")}
                      title={r.strategyName ?? ""}>{r.strategyName ?? "—"}</td>
                    <td className={cn("whitespace-nowrap px-4 py-3 font-mono font-bold", isLight ? "text-neutral-900" : "text-white")}>{r.symbol ?? "—"}</td>
                    <td className="whitespace-nowrap px-4 py-3"><SideChip type={r.signalType} light={isLight} /></td>
                    <td className={cn("whitespace-nowrap px-4 py-3 font-mono", isLight ? "text-neutral-700" : "text-neutral-300")}>{fmt(r.entryReferencePrice)}</td>
                    <td className={cn("whitespace-nowrap px-4 py-3 font-mono font-semibold", isLight ? "text-sky-600" : "text-sky-400")}>{fmt(r.ltp)}</td>
                    <td className={cn("whitespace-nowrap px-4 py-3 font-mono", isLight ? "text-rose-600" : "text-rose-400")}>{fmt(r.stopPrice)}</td>
                    <td className={cn("whitespace-nowrap px-4 py-3 font-mono", isLight ? "text-emerald-600" : "text-emerald-400")}>{fmt(r.targetPrice)}</td>
                    <td className={cn("whitespace-nowrap px-4 py-3 font-mono", isLight ? "text-violet-700" : "text-violet-300")}>
                      {confirmation.riskReward != null ? confirmation.riskReward.toFixed(2) : "—"}
                    </td>
                    <td className="whitespace-nowrap px-4 py-3">
                      <ConfirmationTierChip rank={confirmation} isLight={isLight} compact />
                    </td>
                    <td className="whitespace-nowrap px-4 py-3"><PnlCell value={r.pnl} light={isLight} /></td>
                    <td className="whitespace-nowrap px-4 py-3"><OutcomeBadge status={r.outcomeStatus} light={isLight} /></td>
                    <td className={cn("whitespace-nowrap px-4 py-3 font-mono", isLight ? "text-neutral-600" : "text-neutral-300")}>
                      {r.confidenceScore != null ? `${(Number(r.confidenceScore) <= 1 ? Number(r.confidenceScore) * 100 : Number(r.confidenceScore)).toFixed(0)}%` : "—"}
                    </td>
                    <td className={cn("max-w-[180px] truncate px-4 py-3", textMuted)} title={r.reason ?? ""}>{r.reason ?? "—"}</td>
                    <td className={cn("px-4 py-3", textMuted)}><ChevronRight className="h-3.5 w-3.5" /></td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      <AnimatePresence>
        {selectedSignal ? (
          <SignalDetailDrawer signal={selectedSignal} onClose={() => setSelectedSignal(null)} light={isLight} advMap={advMap} />
        ) : null}
      </AnimatePresence>
    </div>
  );
}
