import { useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { ChevronRight, ChevronDown, TrendingDown, TrendingUp, Activity } from "lucide-react";
import { cn } from "../../../../lib/utils";
import { fmtDateTime } from "../../../../lib/dateUtils";
import { provenanceBadge, provenanceShell, resolveProvenance } from "./provenanceTheme";

export type LiveSignalCardData = {
  id: string;
  strategyName: string | null;
  symbol: string | null;
  signalType: string | null;
  pipeline: string | null;
  signalSource?: string | null;
  confidenceScore: number | null;
  entryReferencePrice: number | null;
  stopPrice: number | null;
  targetPrice: number | null;
  outcomeStatus: string | null;
  realizedPnl: number | null;
  unrealizedPnl: number | null;
  riskRewardAchieved: number | null;
  marketRegime: string | null;
  createdAt: string | null;
  executionLatencyMs: number | null;
  ltp?: number | null;
  pnl?: number | null;
};

function fmt(v: number | null | undefined, d = 2) {
  if (v == null || Number.isNaN(Number(v))) return "—";
  return Number(v).toFixed(d);
}

function confidencePct(score: number | null): number {
  if (score == null) return 0;
  const n = Number(score);
  return n <= 1 ? Math.round(n * 100) : Math.round(n);
}

function lifecycleProgress(outcome: string | null): number {
  const o = String(outcome ?? "").toUpperCase();
  if (o === "TARGET_HIT") return 100;
  if (o === "STOPLOSS_HIT" || o === "SL_HIT") return 100;
  if (o === "PRESSURE_EXIT") return 100;
  if (o === "PARTIAL_TARGET" || o === "BREAKEVEN_EXIT") return 75;
  if (o === "RUNNING") return 45;
  if (o === "EXPIRED" || o === "MISSED" || o === "REJECTED") return 100;
  return 15;
}

function lifecycleLabel(outcome: string | null): string {
  const o = String(outcome ?? "").toUpperCase();
  if (!o || o === "PENDING") return "Emitted";
  if (o === "PRESSURE_EXIT") return "pressure exit";
  if (o === "SL_HIT") return "SL hit";
  if (o === "TARGET_HIT") return "target hit";
  return o.replace(/_/g, " ").toLowerCase();
}

function lifecycleBarColor(outcome: string | null): string {
  const o = String(outcome ?? "").toUpperCase();
  if (o === "TARGET_HIT") return "bg-emerald-400";
  if (o === "STOPLOSS_HIT" || o === "SL_HIT") return "bg-rose-400";
  if (o === "PRESSURE_EXIT") return "bg-amber-400";
  if (o === "EXPIRED" || o === "MISSED" || o === "REJECTED") return "bg-neutral-400";
  return "bg-blue-500";
}

function computeRR(entry: number | null, stop: number | null, target: number | null): string {
  if (entry == null || stop == null || target == null) return "—";
  const risk = Math.abs(entry - stop);
  if (risk <= 0) return "—";
  return (Math.abs(target - entry) / risk).toFixed(1);
}

function targetProgress(entry: number | null, stop: number | null, target: number | null, ltp: number | null, isBuy: boolean): number | null {
  if (entry == null || stop == null || target == null || ltp == null || ltp <= 0) return null;
  const totalRange = Math.abs(target - stop);
  if (totalRange <= 0) return null;
  const fromSl = isBuy ? (ltp - stop) : (stop - ltp);
  return Math.max(0, Math.min(100, (fromSl / totalRange) * 100));
}

export function LiveSignalCard({
  signal,
  isLight,
  selected,
  onSelect,
  index,
}: {
  signal: LiveSignalCardData;
  isLight: boolean;
  selected?: boolean;
  onSelect: () => void;
  index: number;
}) {
  const [expanded, setExpanded] = useState(false);

  const side = String(signal.signalType ?? "").toUpperCase();
  const isBuy = side === "BUY";
  const provenance = resolveProvenance(signal.pipeline, signal.signalSource);
  const conf = confidencePct(signal.confidenceScore);

  const pnlNum = signal.pnl != null ? Number(signal.pnl)
    : signal.realizedPnl != null ? Number(signal.realizedPnl)
    : signal.unrealizedPnl != null ? Number(signal.unrealizedPnl)
    : null;

  const progress = lifecycleProgress(signal.outcomeStatus);
  const ltp = signal.ltp != null ? Number(signal.ltp) : null;
  const entry = signal.entryReferencePrice != null ? Number(signal.entryReferencePrice) : null;
  const ltpValid = ltp != null && ltp > 0;

  const ltpChangePct = ltpValid && entry != null && entry > 0
    ? ((isBuy ? (ltp - entry) : (entry - ltp)) / entry) * 100
    : null;

  const tgtProg = targetProgress(
    entry,
    signal.stopPrice != null ? Number(signal.stopPrice) : null,
    signal.targetPrice != null ? Number(signal.targetPrice) : null,
    ltp,
    isBuy
  );

  const isClosed = signal.outcomeStatus != null && ["TARGET_HIT", "SL_HIT", "STOPLOSS_HIT", "PRESSURE_EXIT", "CLOSED", "EXPIRED"].includes(signal.outcomeStatus.toUpperCase());

  return (
    <motion.div
      initial={{ opacity: 0, y: 14, scale: 0.98 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      transition={{ delay: Math.min(index * 0.04, 0.4), duration: 0.35, ease: [0.22, 1, 0.36, 1] }}
      className={cn(
        "group relative w-full overflow-hidden rounded-2xl border text-left transition-shadow",
        provenanceShell(isLight, provenance),
        selected && "ring-2 ring-blue-500/50",
      )}
    >
      {provenance === "LIVE" ? (
        <motion.span
          aria-hidden
          className="pointer-events-none absolute -right-6 -top-6 h-24 w-24 rounded-full bg-rose-500/20 blur-2xl"
          animate={{ opacity: [0.3, 0.6, 0.3] }}
          transition={{ duration: 2.5, repeat: Infinity }}
        />
      ) : null}

      {/* Main card area — clickable for detail drawer */}
      <button type="button" onClick={onSelect} className="relative w-full p-4 text-left">
        {/* Header: Symbol, Side, Provenance */}
        <div className="flex items-start justify-between gap-3">
          <div className="flex items-center gap-3">
            <div className="relative h-12 w-12 shrink-0">
              <svg viewBox="0 0 36 36" className="h-12 w-12 -rotate-90">
                <circle cx="18" cy="18" r="15" fill="none" stroke={isLight ? "#e5e7eb" : "#404040"} strokeWidth="3" />
                <circle
                  cx="18" cy="18" r="15" fill="none"
                  stroke={conf >= 75 ? "#34d399" : conf >= 55 ? "#fbbf24" : "#f87171"}
                  strokeWidth="3"
                  strokeDasharray={`${(conf / 100) * 94} 94`}
                  strokeLinecap="round"
                />
              </svg>
              <span className="absolute inset-0 flex items-center justify-center text-[10px] font-bold tabular-nums">
                {conf}%
              </span>
            </div>
            <div>
              <div className="flex flex-wrap items-center gap-2">
                <span className={cn("font-mono text-sm font-bold", isLight ? "text-neutral-900" : "text-white")}>
                  {String(signal.symbol ?? "—").replace(/^NSE:/, "")}
                </span>
                <span className={cn(
                  "inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[10px] font-bold uppercase",
                  isBuy ? "bg-emerald-500/20 text-emerald-300" : "bg-rose-500/20 text-rose-300",
                )}>
                  {isBuy ? <TrendingUp className="h-3 w-3" /> : <TrendingDown className="h-3 w-3" />}
                  {side || "—"}
                </span>
                {/* Compact LTP + P&L inline */}
                {ltpValid && (
                  <span className={cn("ml-1 font-mono text-[11px] font-semibold", isLight ? "text-sky-600" : "text-sky-400")}>
                    {fmt(ltp)}
                  </span>
                )}
                {pnlNum != null && (
                  <span className={cn("font-mono text-[11px] font-bold",
                    pnlNum > 0 ? (isLight ? "text-emerald-600" : "text-emerald-400") :
                    pnlNum < 0 ? (isLight ? "text-rose-600" : "text-rose-400") :
                    (isLight ? "text-neutral-500" : "text-neutral-400"))}>
                    {pnlNum >= 0 ? "+" : ""}{pnlNum.toFixed(2)}
                  </span>
                )}
              </div>
              <p className={cn("mt-0.5 truncate text-[11px]", isLight ? "text-neutral-500" : "text-neutral-400")}>
                {signal.strategyName ?? "Unknown strategy"}
              </p>
            </div>
          </div>
          <span className={cn("rounded-full border px-2 py-0.5 text-[9px] font-bold uppercase tracking-wider", provenanceBadge(provenance, isLight))}>
            {provenance}
          </span>
        </div>

        {/* Price Grid: Entry, SL, Target, RR */}
        <div className="mt-3 grid grid-cols-4 gap-2 text-center">
          {[
            { label: "Entry", value: fmt(signal.entryReferencePrice) },
            { label: "SL", value: fmt(signal.stopPrice), tone: isLight ? "text-rose-600" : "text-rose-400" },
            { label: "Target", value: fmt(signal.targetPrice), tone: isLight ? "text-emerald-600" : "text-emerald-400" },
            { label: "RR", value: signal.riskRewardAchieved != null ? fmt(signal.riskRewardAchieved, 1) : computeRR(signal.entryReferencePrice, signal.stopPrice, signal.targetPrice) },
          ].map((cell) => (
            <div key={cell.label}>
              <div className="text-[9px] uppercase tracking-wide opacity-60">{cell.label}</div>
              <div className={cn("font-mono text-xs font-semibold", cell.tone)}>{cell.value}</div>
            </div>
          ))}
        </div>

        {/* Lifecycle Progress Bar */}
        <div className="mt-3">
          <div className="mb-1 flex items-center justify-between text-[10px]">
            <span className={cn("flex items-center gap-1", isLight ? "text-neutral-500" : "text-neutral-400")}>
              {!isClosed && <Activity className="h-2.5 w-2.5 animate-pulse text-blue-400" />}
              {lifecycleLabel(signal.outcomeStatus)}
            </span>
            {signal.marketRegime ? (
              <span className={cn("rounded px-1.5 py-0.5 font-medium", isLight ? "bg-neutral-100 text-neutral-600" : "bg-neutral-800 text-neutral-300")}>
                {signal.marketRegime}
              </span>
            ) : null}
          </div>
          <div className={cn("h-1.5 overflow-hidden rounded-full", isLight ? "bg-neutral-200" : "bg-neutral-800")}>
            <motion.div
              className={cn("h-full rounded-full", lifecycleBarColor(signal.outcomeStatus))}
              initial={{ width: 0 }}
              animate={{ width: `${progress}%` }}
              transition={{ duration: 0.6 }}
            />
          </div>
        </div>

        {/* Footer: Time, P&L, Arrow */}
        <div className="mt-3 flex items-center justify-between text-[11px]">
          <span className={isLight ? "text-neutral-400" : "text-neutral-500"}>{fmtDateTime(signal.createdAt)}</span>
          <span className={cn("font-mono font-semibold",
            pnlNum == null ? "text-neutral-500" : pnlNum >= 0 ? "text-emerald-400" : "text-rose-400",
            pnlNum != null && pnlNum !== 0 && (pnlNum > 0 ? "drop-shadow-[0_0_8px_rgba(52,211,153,0.35)]" : "drop-shadow-[0_0_8px_rgba(244,63,94,0.35)]"),
          )}>
            {pnlNum == null ? "—" : `${pnlNum >= 0 ? "+" : ""}${pnlNum.toFixed(2)}`}
          </span>
          <ChevronRight className="h-4 w-4 opacity-40 transition group-hover:opacity-100" />
        </div>
      </button>

      {/* Expandable LTP Detail Section */}
      {ltpValid && (
        <>
          <button
            type="button"
            onClick={(e) => { e.stopPropagation(); setExpanded((v) => !v); }}
            className={cn(
              "relative flex w-full items-center justify-center gap-1 border-t py-1.5 text-[10px] font-semibold transition-colors",
              isLight
                ? "border-neutral-200 text-neutral-400 hover:bg-neutral-50 hover:text-neutral-600"
                : "border-white/[0.06] text-neutral-500 hover:bg-white/[0.03] hover:text-neutral-300",
            )}
          >
            <ChevronDown className={cn("h-3 w-3 transition-transform", expanded && "rotate-180")} />
            {expanded ? "Hide detail" : "LTP & P&L detail"}
          </button>
          <AnimatePresence>
            {expanded && (
              <motion.div
                initial={{ height: 0, opacity: 0 }}
                animate={{ height: "auto", opacity: 1 }}
                exit={{ height: 0, opacity: 0 }}
                transition={{ duration: 0.25 }}
                className="overflow-hidden"
              >
                <div className={cn("relative px-4 pb-3 pt-2",
                  isLight ? "bg-neutral-50" : "bg-white/[0.02]")}>
                  <div className="flex items-center justify-between">
                    <div>
                      <div className={cn("text-[9px] uppercase tracking-wide font-semibold",
                        isLight ? "text-neutral-400" : "text-neutral-500")}>LTP</div>
                      <div className={cn("font-mono text-lg font-bold",
                        isLight ? "text-neutral-900" : "text-white")}>
                        {fmt(ltp)}
                      </div>
                    </div>
                    {ltpChangePct != null && (
                      <div className="text-right">
                        <div className={cn("text-[9px] uppercase tracking-wide font-semibold",
                          isLight ? "text-neutral-400" : "text-neutral-500")}>
                          {isClosed ? "Final" : "Live"} P&L
                        </div>
                        <div className={cn("font-mono text-lg font-bold",
                          pnlNum != null && pnlNum > 0 ? (isLight ? "text-emerald-600" : "text-emerald-400") :
                          pnlNum != null && pnlNum < 0 ? (isLight ? "text-rose-600" : "text-rose-400") :
                          (isLight ? "text-neutral-600" : "text-neutral-300"))}>
                          {pnlNum != null ? `${pnlNum >= 0 ? "+" : ""}${pnlNum.toFixed(2)}` : "—"}
                        </div>
                        <div className={cn("text-[10px] font-mono",
                          ltpChangePct >= 0 ? (isLight ? "text-emerald-500" : "text-emerald-400/80") :
                          (isLight ? "text-rose-500" : "text-rose-400/80"))}>
                          {ltpChangePct >= 0 ? "+" : ""}{ltpChangePct.toFixed(2)}%
                        </div>
                      </div>
                    )}
                  </div>
                  {/* Target progress mini-bar */}
                  {tgtProg != null && !isClosed && (
                    <div className="mt-2">
                      <div className="flex justify-between text-[8px] font-mono mb-0.5">
                        <span className={isLight ? "text-rose-500" : "text-rose-400"}>SL</span>
                        <span className={isLight ? "text-neutral-400" : "text-neutral-500"}>{tgtProg.toFixed(0)}%</span>
                        <span className={isLight ? "text-emerald-500" : "text-emerald-400"}>TGT</span>
                      </div>
                      <div className={cn("h-1 overflow-hidden rounded-full", isLight ? "bg-neutral-200" : "bg-neutral-700")}>
                        <motion.div
                          className={cn("h-full rounded-full",
                            tgtProg > 60 ? "bg-emerald-400" : tgtProg > 30 ? "bg-amber-400" : "bg-rose-400")}
                          initial={{ width: 0 }}
                          animate={{ width: `${tgtProg}%` }}
                          transition={{ duration: 0.6 }}
                        />
                      </div>
                    </div>
                  )}
                </div>
              </motion.div>
            )}
          </AnimatePresence>
        </>
      )}
    </motion.div>
  );
}
