import { AnimatePresence, motion } from "framer-motion";
import { ChevronDown, TrendingUp, Zap, Shield, Target, Ban, Activity, Wallet } from "lucide-react";
import { useMemo, useState } from "react";
import { cn } from "../../lib/utils";
import {
  buildLiveOpsMetrics,
  type LiveOpsMetric,
  type LiveOpsMetricId,
} from "../../lib/intradayLiveOps";

const ICONS: Record<LiveOpsMetricId, typeof TrendingUp> = {
  livePnl: Wallet,
  tradesHit: Target,
  running: Activity,
  sl: Ban,
  notExecuted: Shield,
  openBook: TrendingUp,
  guards: Zap,
};

const TONE_STYLES = {
  light: {
    positive: "border-emerald-200/90 bg-gradient-to-br from-emerald-50 to-teal-50 text-emerald-900 shadow-emerald-100/50",
    negative: "border-rose-200/90 bg-gradient-to-br from-rose-50 to-orange-50 text-rose-900 shadow-rose-100/50",
    warn: "border-amber-200/90 bg-gradient-to-br from-amber-50 to-yellow-50 text-amber-950 shadow-amber-100/50",
    info: "border-indigo-200/90 bg-gradient-to-br from-indigo-50 to-sky-50 text-indigo-900 shadow-indigo-100/50",
    neutral: "border-slate-200/90 bg-gradient-to-br from-white to-slate-50 text-slate-800 shadow-slate-100/40",
    active: "ring-2 ring-indigo-400/60 ring-offset-2 ring-offset-white",
  },
  dark: {
    positive: "border-emerald-500/35 bg-gradient-to-br from-emerald-500/15 to-teal-500/10 text-emerald-100",
    negative: "border-rose-500/35 bg-gradient-to-br from-rose-500/15 to-orange-500/10 text-rose-100",
    warn: "border-amber-500/35 bg-gradient-to-br from-amber-500/15 to-yellow-500/10 text-amber-100",
    info: "border-indigo-500/35 bg-gradient-to-br from-indigo-500/15 to-sky-500/10 text-indigo-100",
    neutral: "border-neutral-700/80 bg-gradient-to-br from-neutral-900/90 to-neutral-950/90 text-neutral-100",
    active: "ring-2 ring-indigo-400/50 ring-offset-2 ring-offset-neutral-950",
  },
} as const;

type Props = {
  latestSignals?: Array<Record<string, unknown>>;
  openPositions?: Array<Record<string, unknown>>;
  livePnl?: unknown;
  guardEvents?: Array<Record<string, unknown>>;
  runningStrategies?: number;
  rejectedOrders?: number;
  isLight?: boolean;
  className?: string;
};

export function LiveOpsStrip({
  latestSignals,
  openPositions,
  livePnl,
  guardEvents,
  runningStrategies,
  rejectedOrders,
  isLight = true,
  className,
}: Props) {
  const [activeId, setActiveId] = useState<LiveOpsMetricId | null>("livePnl");
  const metrics = useMemo(
    () =>
      buildLiveOpsMetrics({
        latestSignals,
        openPositions,
        livePnl,
        guardEvents,
        runningStrategies,
        rejectedOrders,
      }),
    [latestSignals, openPositions, livePnl, guardEvents, runningStrategies, rejectedOrders],
  );
  const active = metrics.find((m) => m.id === activeId) ?? null;
  const palette = (isLight ? TONE_STYLES.light : TONE_STYLES.dark) as Record<string, string>;

  return (
    <div className={cn("space-y-3", className)}>
      <div className="flex flex-wrap items-center justify-between gap-2 px-0.5">
        <div>
          <p className={cn("text-xs font-bold uppercase tracking-[0.2em]", isLight ? "text-indigo-600" : "text-indigo-300")}>
            Live ops
          </p>
          <p className={cn("text-[11px]", isLight ? "text-slate-500" : "text-neutral-400")}>
            Tap a metric for drill-down · auto-refreshes with workstation
          </p>
        </div>
        <motion.span
          animate={{ opacity: [0.5, 1, 0.5] }}
          transition={{ duration: 2.5, repeat: Infinity }}
          className={cn(
            "inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[10px] font-bold uppercase",
            isLight ? "bg-indigo-100 text-indigo-800" : "bg-indigo-500/20 text-indigo-200",
          )}
        >
          <span className="relative flex h-1.5 w-1.5">
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-indigo-400 opacity-50" />
            <span className="relative h-1.5 w-1.5 rounded-full bg-indigo-500" />
          </span>
          Streaming
        </motion.span>
      </div>

      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-7">
        {metrics.map((m) => (
          <OpsPill
            key={m.id}
            metric={m}
            isActive={activeId === m.id}
            isLight={isLight}
            palette={palette}
            onClick={() => setActiveId((prev) => (prev === m.id ? null : m.id))}
          />
        ))}
      </div>

      <AnimatePresence mode="wait">
        {active && activeId ? (
          <motion.div
            key={active.id}
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -6 }}
            transition={{ duration: 0.25 }}
            className={cn(
              "overflow-hidden rounded-2xl border backdrop-blur-xl",
              isLight ? "border-slate-200/90 bg-white/90 shadow-lg shadow-slate-200/40" : "border-neutral-800 bg-neutral-950/90",
            )}
          >
            <div className={cn("flex items-center justify-between border-b px-4 py-3", isLight ? "border-slate-100" : "border-neutral-800")}>
              <div>
                <p className={cn("text-sm font-semibold", isLight ? "text-slate-900" : "text-neutral-100")}>{active.label} detail</p>
                <p className={cn("text-[11px]", isLight ? "text-slate-500" : "text-neutral-400")}>{active.sublabel}</p>
              </div>
              <span className={cn("font-mono text-lg font-bold tabular-nums", isLight ? "text-indigo-700" : "text-indigo-300")}>{active.value}</span>
            </div>
            <div className="max-h-[200px] overflow-auto p-3">
              {active.details.length === 0 ? (
                <p className={cn("py-6 text-center text-sm", isLight ? "text-slate-500" : "text-neutral-400")}>No rows for this bucket yet.</p>
              ) : (
                <ul className="space-y-1.5">
                  {active.details.map((row) => (
                    <li
                      key={row.key}
                      className={cn(
                        "flex items-center justify-between gap-3 rounded-xl border px-3 py-2 text-xs",
                        isLight ? "border-slate-100 bg-slate-50/80" : "border-neutral-800 bg-neutral-900/60",
                      )}
                    >
                      <div className="min-w-0">
                        <p className={cn("truncate font-semibold", isLight ? "text-slate-900" : "text-neutral-100")}>{row.primary}</p>
                        {row.secondary ? (
                          <p className={cn("truncate text-[10px]", isLight ? "text-slate-500" : "text-neutral-400")}>{row.secondary}</p>
                        ) : null}
                      </div>
                      {row.meta ? (
                        <span
                          className={cn(
                            "shrink-0 font-mono text-[11px] font-semibold tabular-nums",
                            row.tone === "positive"
                              ? "text-emerald-600"
                              : row.tone === "negative"
                                ? "text-rose-600"
                                : row.tone === "warn"
                                  ? "text-amber-700"
                                  : isLight
                                    ? "text-slate-600"
                                    : "text-neutral-300",
                          )}
                        >
                          {row.meta}
                        </span>
                      ) : null}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </motion.div>
        ) : null}
      </AnimatePresence>
    </div>
  );
}

function OpsPill({
  metric,
  isActive,
  isLight,
  palette,
  onClick,
}: {
  metric: LiveOpsMetric;
  isActive: boolean;
  isLight: boolean;
  palette: Record<string, string>;
  onClick: () => void;
}) {
  const Icon = ICONS[metric.id];
  return (
    <motion.button
      type="button"
      layout
      whileHover={{ y: -2 }}
      whileTap={{ scale: 0.98 }}
      onClick={onClick}
      className={cn(
        "group relative flex min-h-[72px] flex-col justify-between overflow-hidden rounded-2xl border p-3 text-left shadow-sm transition",
        palette[metric.tone],
        isActive && palette.active,
      )}
    >
      <div className="flex items-start justify-between gap-1">
        <Icon className={cn("h-3.5 w-3.5 opacity-70", isLight ? "" : "opacity-80")} />
        <ChevronDown className={cn("h-3 w-3 opacity-0 transition group-hover:opacity-50", isActive && "rotate-180 opacity-70")} />
      </div>
      <div>
        <p className="text-[10px] font-semibold uppercase tracking-wider opacity-80">{metric.label}</p>
        <p className="mt-0.5 font-mono text-base font-bold tabular-nums leading-none">{metric.value}</p>
        {metric.sublabel ? <p className="mt-1 text-[9px] opacity-70">{metric.sublabel}</p> : null}
      </div>
    </motion.button>
  );
}
