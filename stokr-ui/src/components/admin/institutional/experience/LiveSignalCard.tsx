import { motion } from "framer-motion";
import { ChevronRight, TrendingDown, TrendingUp } from "lucide-react";
import { cn } from "../../../../lib/utils";
import { fmtDateTime } from "../../../../lib/dateUtils";
import { provenanceBadge, provenanceShell, resolveProvenance } from "./provenanceTheme";

export type LiveSignalCardData = {
  id: string;
  strategyName: string | null;
  symbol: string | null;
  signalType: string | null;
  pipeline: string | null;
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
  if (o === "STOPLOSS_HIT") return 100;
  if (o === "PARTIAL_TARGET" || o === "BREAKEVEN_EXIT") return 75;
  if (o === "RUNNING") return 45;
  if (o === "EXPIRED" || o === "MISSED" || o === "REJECTED") return 100;
  return 15;
}

function lifecycleLabel(outcome: string | null): string {
  const o = String(outcome ?? "").toUpperCase();
  if (!o || o === "PENDING") return "Emitted";
  return o.replace(/_/g, " ").toLowerCase();
}

function computeRR(entry: number | null, stop: number | null, target: number | null): string {
  if (entry == null || stop == null || target == null) return "—";
  const risk = Math.abs(entry - stop);
  if (risk <= 0) return "—";
  return (Math.abs(target - entry) / risk).toFixed(1);
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
  const side = String(signal.signalType ?? "").toUpperCase();
  const isBuy = side === "BUY";
  const provenance = resolveProvenance(signal.pipeline);
  const conf = confidencePct(signal.confidenceScore);
  const pnl = signal.realizedPnl ?? signal.unrealizedPnl;
  const pnlNum = pnl != null ? Number(pnl) : null;
  const progress = lifecycleProgress(signal.outcomeStatus);

  return (
    <motion.button
      type="button"
      initial={{ opacity: 0, y: 14, scale: 0.98 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      transition={{ delay: Math.min(index * 0.04, 0.4), duration: 0.35, ease: [0.22, 1, 0.36, 1] }}
      whileHover={{ y: -3 }}
      onClick={onSelect}
      className={cn(
        "group relative w-full overflow-hidden rounded-2xl border p-4 text-left transition-shadow",
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

      <div className="relative flex items-start justify-between gap-3">
        <div className="flex items-center gap-3">
          <div className="relative h-12 w-12 shrink-0">
            <svg viewBox="0 0 36 36" className="h-12 w-12 -rotate-90">
              <circle cx="18" cy="18" r="15" fill="none" stroke={isLight ? "#e5e7eb" : "#404040"} strokeWidth="3" />
              <circle
                cx="18"
                cy="18"
                r="15"
                fill="none"
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
              <span
                className={cn(
                  "inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[10px] font-bold uppercase",
                  isBuy ? "bg-emerald-500/20 text-emerald-300" : "bg-rose-500/20 text-rose-300",
                )}
              >
                {isBuy ? <TrendingUp className="h-3 w-3" /> : <TrendingDown className="h-3 w-3" />}
                {side || "—"}
              </span>
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

      <div className="relative mt-4 grid grid-cols-4 gap-2 text-center">
        {[
          { label: "Entry", value: fmt(signal.entryReferencePrice) },
          { label: "SL", value: fmt(signal.stopPrice), tone: "text-rose-400" },
          { label: "Target", value: fmt(signal.targetPrice), tone: "text-emerald-400" },
          { label: "RR", value: signal.riskRewardAchieved != null ? fmt(signal.riskRewardAchieved, 1) : computeRR(signal.entryReferencePrice, signal.stopPrice, signal.targetPrice) },
        ].map((cell) => (
          <div key={cell.label}>
            <div className={cn("text-[9px] uppercase tracking-wide opacity-60")}>{cell.label}</div>
            <div className={cn("font-mono text-xs font-semibold", cell.tone)}>{cell.value}</div>
          </div>
        ))}
      </div>

      <div className="relative mt-4">
        <div className="mb-1 flex items-center justify-between text-[10px]">
          <span className={isLight ? "text-neutral-500" : "text-neutral-400"}>{lifecycleLabel(signal.outcomeStatus)}</span>
          {signal.marketRegime ? (
            <span className={cn("rounded px-1.5 py-0.5 font-medium", isLight ? "bg-neutral-100 text-neutral-600" : "bg-neutral-800 text-neutral-300")}>
              {signal.marketRegime}
            </span>
          ) : null}
        </div>
        <div className={cn("h-1.5 overflow-hidden rounded-full", isLight ? "bg-neutral-200" : "bg-neutral-800")}>
          <motion.div
            className={cn("h-full rounded-full", progress >= 100 ? "bg-emerald-400" : "bg-blue-500")}
            initial={{ width: 0 }}
            animate={{ width: `${progress}%` }}
            transition={{ duration: 0.6 }}
          />
        </div>
      </div>

      <div className="relative mt-3 flex items-center justify-between text-[11px]">
        <span className={isLight ? "text-neutral-400" : "text-neutral-500"}>{fmtDateTime(signal.createdAt)}</span>
        <span
          className={cn(
            "font-mono font-semibold",
            pnlNum == null ? "text-neutral-500" : pnlNum >= 0 ? "text-emerald-400" : "text-rose-400",
            pnlNum != null && pnlNum !== 0 && "drop-shadow-[0_0_8px_rgba(52,211,153,0.35)]",
          )}
        >
          {pnlNum == null ? "—" : `${pnlNum >= 0 ? "+" : ""}${pnlNum.toFixed(0)}`}
        </span>
        <ChevronRight className="h-4 w-4 opacity-40 transition group-hover:opacity-100" />
      </div>
    </motion.button>
  );
}
