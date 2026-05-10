import type { ReactNode } from "react";
import { motion } from "framer-motion";
import { cn } from "../../lib/utils";
import { GlassPanel } from "./GlassPanel";

type MetricTrend = "up" | "down" | "flat";

export function MetricCard({
  label,
  value,
  sublabel,
  trend,
  highlight,
  action,
}: {
  label: string;
  value: ReactNode;
  sublabel?: string;
  trend?: MetricTrend;
  highlight?: boolean;
  action?: ReactNode;
}) {
  const trendDot =
    trend === "up" ? "bg-emerald-400" : trend === "down" ? "bg-rose-400" : trend === "flat" ? "bg-neutral-600" : "";

  return (
    <motion.div initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.25 }}>
      <GlassPanel className={cn("relative overflow-hidden p-5", highlight && "stokr-pulse-live border-blue-500/25")}>
        <div className="flex items-start justify-between gap-3">
          <div>
            <div className="flex items-center gap-2">
              {trendDot ? <span className={cn("inline-block h-1.5 w-1.5 rounded-full", trendDot)} aria-hidden /> : null}
              <div className="text-[11px] font-semibold uppercase tracking-[0.12em] text-neutral-500">{label}</div>
            </div>
            <div className="mt-2 font-mono text-2xl font-semibold tracking-tight text-white">{value}</div>
            {sublabel ? <div className="mt-2 text-xs text-neutral-500">{sublabel}</div> : null}
          </div>
          {action}
        </div>
        <div
          aria-hidden
          className="pointer-events-none absolute -right-8 -top-12 h-32 w-32 rounded-full bg-blue-500/5 blur-2xl"
        />
      </GlassPanel>
    </motion.div>
  );
}
