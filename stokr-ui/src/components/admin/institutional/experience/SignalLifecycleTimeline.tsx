import { motion } from "framer-motion";
import { cn } from "../../../../lib/utils";

const STAGES = [
  { key: "EMITTED", label: "Emitted", match: ["", "PENDING", "NO_EXECUTION"] },
  { key: "ENTERED", label: "Entered", match: ["RUNNING"] },
  { key: "PARTIAL", label: "Partial", match: ["PARTIAL_TARGET", "BREAKEVEN_EXIT"] },
  { key: "TARGET", label: "Target", match: ["TARGET_HIT"] },
  { key: "SL", label: "SL", match: ["STOPLOSS_HIT"] },
  { key: "EXPIRED", label: "Expired", match: ["EXPIRED", "MISSED", "REJECTED", "CANCELLED"] },
] as const;

function stageIndex(outcome: string | null): number {
  const o = String(outcome ?? "").toUpperCase();
  const idx = STAGES.findIndex((s) => (s.match as readonly string[]).includes(o));
  return idx >= 0 ? idx : 0;
}

export function SignalLifecycleTimeline({
  outcome,
  isLight,
  compact,
}: {
  outcome: string | null;
  isLight: boolean;
  compact?: boolean;
}) {
  const active = stageIndex(outcome);

  return (
    <div className={cn("overflow-x-auto pb-1", compact ? "" : "px-1")}>
      <div className="flex min-w-[520px] items-center gap-1">
        {STAGES.map((stage, i) => {
          const done = i <= active;
          const current = i === active;
          return (
            <div key={stage.key} className="flex flex-1 flex-col items-center gap-1.5">
              <div className="relative flex w-full items-center">
                {i > 0 ? (
                  <div
                    className={cn(
                      "absolute left-0 right-1/2 top-1/2 h-0.5 -translate-y-1/2",
                      done ? (isLight ? "bg-blue-400" : "bg-blue-500") : isLight ? "bg-neutral-200" : "bg-neutral-800",
                    )}
                  />
                ) : null}
                {i < STAGES.length - 1 ? (
                  <div
                    className={cn(
                      "absolute left-1/2 right-0 top-1/2 h-0.5 -translate-y-1/2",
                      i < active ? (isLight ? "bg-blue-400" : "bg-blue-500") : isLight ? "bg-neutral-200" : "bg-neutral-800",
                    )}
                  />
                ) : null}
                <motion.span
                  animate={current ? { scale: [1, 1.15, 1] } : {}}
                  transition={{ duration: 2, repeat: current ? Infinity : 0 }}
                  className={cn(
                    "relative z-10 mx-auto flex h-3 w-3 rounded-full ring-2",
                    done
                      ? current
                        ? "bg-blue-500 ring-blue-400/40"
                        : "bg-blue-500/80 ring-blue-500/20"
                      : isLight
                        ? "bg-neutral-200 ring-neutral-100"
                        : "bg-neutral-800 ring-neutral-900",
                  )}
                />
              </div>
              <span
                className={cn(
                  "text-[9px] font-semibold uppercase tracking-wide",
                  current ? (isLight ? "text-blue-700" : "text-blue-300") : isLight ? "text-neutral-400" : "text-neutral-600",
                )}
              >
                {stage.label}
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

export function StrategyPerformanceHeatmap({
  rows,
  isLight,
}: {
  rows: Array<{ strategyKey: string; signalsToday: number }>;
  isLight: boolean;
}) {
  const max = Math.max(1, ...rows.map((r) => r.signalsToday ?? 0));

  return (
    <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-4">
      {rows.slice(0, 12).map((r) => {
        const t = (r.signalsToday ?? 0) / max;
        return (
          <div
            key={r.strategyKey}
            className={cn("rounded-xl border px-3 py-2", isLight ? "border-neutral-200" : "border-neutral-800")}
            style={{
              background: isLight
                ? `rgba(59,130,246,${0.05 + t * 0.2})`
                : `rgba(59,130,246,${0.1 + t * 0.35})`,
            }}
          >
            <div className="truncate text-[10px] font-medium uppercase tracking-wide opacity-70">{r.strategyKey}</div>
            <div className="mt-1 font-mono text-lg font-bold tabular-nums">{r.signalsToday ?? 0}</div>
            <div className="text-[10px] opacity-60">signals today</div>
          </div>
        );
      })}
    </div>
  );
}
