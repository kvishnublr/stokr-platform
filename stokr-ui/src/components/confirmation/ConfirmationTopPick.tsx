import { Link } from "react-router-dom";
import { motion } from "framer-motion";
import { ArrowRight, Cpu, Sparkles, TrendingUp } from "lucide-react";
import { cn } from "../../lib/utils";
import {
  bareSymbol,
  formatConfidencePct,
  signalDirection,
  signalStrategyKey,
  type IntradaySignalRow,
} from "../../lib/intradaySignals";
import {
  resolveConfirmation,
  tierLabel,
  type ConfirmationBreakdown,
  lookupAdvScore,
} from "../../lib/confirmationRank";

type Props = {
  pick: IntradaySignalRow;
  rank: ConfirmationBreakdown;
  isLight: boolean;
  advAligned?: boolean;
  executionMode?: string;
  onClearStrategyFilter?: () => void;
};

function TierBadge({ tier, isLight }: { tier: ConfirmationBreakdown["tier"]; isLight: boolean }) {
  const label = tierLabel(tier);
  const cls =
    tier === "A_PLUS"
      ? isLight
        ? "bg-emerald-600 text-white"
        : "bg-emerald-500 text-white"
      : tier === "A"
        ? isLight
          ? "bg-indigo-600 text-white"
          : "bg-indigo-500 text-white"
        : isLight
          ? "bg-amber-100 text-amber-900"
          : "bg-amber-500/20 text-amber-200";
  return (
    <span className={cn("rounded-full px-2.5 py-0.5 text-[10px] font-bold uppercase tracking-wide", cls)}>
      {label}
    </span>
  );
}

export function ConfirmationTopPick({
  pick,
  rank,
  isLight,
  advAligned,
  executionMode,
  onClearStrategyFilter,
}: Props) {
  const sym = bareSymbol(pick.symbol);
  const dir = signalDirection(pick);
  const strategy = signalStrategyKey(pick);

  return (
    <motion.div
      layout
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      className={cn(
        "relative overflow-hidden rounded-2xl border px-4 py-3 sm:px-5",
        isLight
          ? "border-indigo-200/90 bg-gradient-to-r from-indigo-50 via-white to-sky-50 shadow-sm"
          : "border-indigo-500/30 bg-gradient-to-r from-indigo-950/80 via-neutral-950 to-sky-950/50",
      )}
    >
      <div
        aria-hidden
        className={cn(
          "pointer-events-none absolute inset-y-0 left-0 w-1",
          rank.tier === "A_PLUS" ? "bg-emerald-500" : "bg-indigo-500",
        )}
      />
      <div className="flex flex-wrap items-center justify-between gap-3 pl-2">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <Sparkles className={cn("h-4 w-4 shrink-0", isLight ? "text-indigo-600" : "text-indigo-300")} />
          <span className={cn("text-[10px] font-bold uppercase tracking-widest", isLight ? "text-indigo-700" : "text-indigo-300")}>
            Top pick
          </span>
          <TierBadge tier={rank.tier} isLight={isLight} />
          {rank.highConviction ? (
            <span
              className={cn(
                "rounded-full px-2 py-0.5 text-[10px] font-semibold",
                isLight ? "bg-emerald-100 text-emerald-800" : "bg-emerald-500/15 text-emerald-300",
              )}
            >
              High conviction
            </span>
          ) : null}
        </div>
        <div className="flex flex-wrap items-center gap-2 text-xs">
          <Link
            to="/adv-dashboard"
            className={cn(
              "inline-flex items-center gap-1 rounded-full border px-2.5 py-1 font-medium transition hover:underline",
              isLight ? "border-slate-200 bg-white text-slate-700" : "border-neutral-700 text-neutral-300",
            )}
          >
            <Cpu className="h-3 w-3" />
            ADV scanner
          </Link>
          {onClearStrategyFilter ? (
            <button
              type="button"
              onClick={onClearStrategyFilter}
              className={cn(
                "rounded-full border px-2.5 py-1 font-medium",
                isLight ? "border-slate-200 text-slate-600" : "border-neutral-700 text-neutral-400",
              )}
            >
              Show all strategies
            </button>
          ) : null}
        </div>
      </div>

      <div className="mt-2 flex flex-wrap items-end justify-between gap-3 pl-2">
        <div>
          <div className="flex flex-wrap items-center gap-2">
            <span className={cn("font-mono text-lg font-bold", isLight ? "text-slate-900" : "text-white")}>{sym}</span>
            <span
              className={cn(
                "rounded-full px-2 py-0.5 text-[11px] font-bold uppercase",
                dir === "BUY"
                  ? "bg-emerald-500 text-white"
                  : dir === "SELL"
                    ? "bg-rose-500 text-white"
                    : isLight
                      ? "bg-slate-200 text-slate-600"
                      : "bg-neutral-800 text-neutral-300",
              )}
            >
              {dir}
            </span>
            <span className={cn("text-sm font-medium", isLight ? "text-slate-600" : "text-neutral-400")}>{strategy}</span>
          </div>
          <p className={cn("mt-1 text-xs", isLight ? "text-slate-500" : "text-neutral-400")}>
            Rank {rank.score}/100 · Conf {formatConfidencePct(pick.confidenceScore ?? pick.confidence)}
            {rank.riskReward != null ? ` · RR ${rank.riskReward.toFixed(2)}` : ""}
            {advAligned && rank.advAiScore != null ? ` · ADV ${rank.advAiScore}` : ""}
            {executionMode ? ` · ${executionMode}` : ""}
          </p>
        </div>
        <Link
          to="/signals"
          className={cn(
            "inline-flex items-center gap-2 rounded-full px-4 py-2 text-sm font-semibold shadow-md transition",
            isLight ? "bg-indigo-600 text-white hover:bg-indigo-700" : "bg-indigo-500 text-white hover:bg-indigo-400",
          )}
        >
          <TrendingUp className="h-4 w-4" />
          Signal detail
          <ArrowRight className="h-4 w-4" />
        </Link>
      </div>
    </motion.div>
  );
}

export function ConfirmationTierChip({
  rank,
  isLight,
  compact,
}: {
  rank: ConfirmationBreakdown;
  isLight: boolean;
  compact?: boolean;
}) {
  const label = tierLabel(rank.tier);
  const cls =
    rank.tier === "A_PLUS"
      ? isLight
        ? "bg-emerald-50 text-emerald-800 ring-emerald-200"
        : "bg-emerald-500/15 text-emerald-300 ring-emerald-500/30"
      : rank.tier === "A"
        ? isLight
          ? "bg-indigo-50 text-indigo-800 ring-indigo-100"
          : "bg-indigo-500/15 text-indigo-200 ring-indigo-500/30"
        : rank.tier === "WATCH"
          ? isLight
            ? "bg-amber-50 text-amber-900 ring-amber-200"
            : "bg-amber-500/15 text-amber-200 ring-amber-500/30"
          : isLight
            ? "bg-slate-100 text-slate-500 ring-slate-200"
            : "bg-neutral-800 text-neutral-400 ring-neutral-700";

  return (
    <span
      className={cn(
        "inline-flex items-center justify-center rounded-lg font-bold ring-1",
        compact ? "px-1.5 py-0.5 text-[10px]" : "px-2 py-1 text-xs",
        cls,
      )}
      title={`Setup confirmation ${rank.score}/100`}
    >
      {label} {compact ? "" : <span className="ml-1 font-mono font-normal opacity-80">{rank.score}</span>}
    </span>
  );
}
