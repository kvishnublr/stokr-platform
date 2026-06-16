import { motion } from "framer-motion";
import { cn } from "../../../../lib/utils";

export type OmsLaneStats = {
  totalToday: number;
  filledToday: number;
  rejectedToday: number;
  partialToday: number;
  cancelledToday: number;
  pendingToday: number;
};

const LANES = [
  { key: "pendingToday", label: "Pending", color: "bg-sky-500" },
  { key: "partialToday", label: "Partial", color: "bg-amber-400" },
  { key: "filledToday", label: "Filled", color: "bg-emerald-500" },
  { key: "rejectedToday", label: "Rejected", color: "bg-rose-500" },
  { key: "cancelledToday", label: "Cancelled", color: "bg-neutral-500" },
] as const;

export function ExecutionLaneVisualization({ stats, isLight }: { stats: OmsLaneStats | undefined; isLight: boolean }) {
  const total = Math.max(1, stats?.totalToday ?? 0);

  return (
    <div
      className={cn(
        "rounded-2xl border p-5",
        isLight
          ? "border-neutral-200 bg-gradient-to-r from-neutral-50 via-white to-emerald-50/30"
          : "border-neutral-800 bg-gradient-to-r from-neutral-950 via-neutral-900/60 to-emerald-950/15",
      )}
    >
      <p className={cn("text-[10px] font-bold uppercase tracking-[0.18em]", isLight ? "text-emerald-700" : "text-emerald-400")}>
        Execution lanes · today
      </p>
      <div className="mt-4 flex h-3 overflow-hidden rounded-full">
        {LANES.map((lane, i) => {
          const value = stats?.[lane.key] ?? 0;
          const pct = (value / total) * 100;
          if (pct <= 0) return null;
          return (
            <motion.div
              key={lane.key}
              initial={{ width: 0 }}
              animate={{ width: `${pct}%` }}
              transition={{ delay: i * 0.06, duration: 0.5 }}
              className={cn(lane.color, "relative min-w-[2px]")}
              title={`${lane.label}: ${value}`}
            />
          );
        })}
      </div>
      <div className="mt-3 flex flex-wrap gap-3">
        {LANES.map((lane) => (
          <div key={lane.key} className="flex items-center gap-1.5 text-[11px]">
            <span className={cn("h-2 w-2 rounded-full", lane.color)} />
            <span className={isLight ? "text-neutral-600" : "text-neutral-400"}>{lane.label}</span>
            <span className={cn("font-mono font-semibold", isLight ? "text-neutral-900" : "text-neutral-100")}>
              {stats?.[lane.key] ?? 0}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
