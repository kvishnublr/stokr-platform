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
  panelVariant = "dark",
}: {
  label: string;
  value: ReactNode;
  sublabel?: string;
  trend?: MetricTrend;
  highlight?: boolean;
  action?: ReactNode;
  /** Use `light` on neutral-50 / white shells so values stay readable. */
  panelVariant?: "dark" | "light";
}) {
  const light = panelVariant === "light";
  const trendDot =
    trend === "up"
      ? light
        ? "bg-emerald-500"
        : "bg-emerald-400"
      : trend === "down"
        ? light
          ? "bg-rose-500"
          : "bg-rose-400"
        : trend === "flat"
          ? light
            ? "bg-neutral-400"
            : "bg-neutral-600"
          : "";

  return (
    <motion.div
      initial={{ opacity: 0, y: 12, scale: 0.95 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      transition={{ duration: 0.35, ease: [0.22, 1, 0.36, 1] }}
      whileHover={{ y: -4, transition: { duration: 0.2 } }}
      className="group"
    >
      <GlassPanel
        variant={panelVariant}
        className={cn(
          "relative overflow-hidden p-5 transition-all duration-300 group-hover:border-accent/40",
          highlight && (light ? "stokr-pulse-live border-blue-400/40" : "stokr-pulse-live border-blue-500/25"),
        )}
      >
        <div className="flex items-start justify-between gap-3">
          <div className="flex-1">
            <div className="flex items-center gap-2">
              {trendDot ? (
                <motion.span
                  className={cn("inline-block h-2 w-2 rounded-full", trendDot)}
                  animate={trend === "up" || trend === "down" ? { scale: [1, 1.2, 1] } : {}}
                  transition={{ duration: 2, repeat: Infinity }}
                  aria-hidden
                />
              ) : null}
              <div
                className={cn(
                  "text-[11px] font-semibold uppercase tracking-[0.12em]",
                  light ? "text-neutral-600" : "text-neutral-500",
                )}
              >
                {label}
              </div>
            </div>
            <motion.div
              className={cn(
                "mt-3 font-mono text-3xl font-bold tracking-tight",
                light ? "text-neutral-900" : "text-white",
              )}
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              transition={{ duration: 0.5, delay: 0.1 }}
            >
              {value}
            </motion.div>
            {sublabel ? (
              <motion.div
                className={cn("mt-2 text-xs", light ? "text-neutral-600" : "text-neutral-500")}
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                transition={{ duration: 0.5, delay: 0.2 }}
              >
                {sublabel}
              </motion.div>
            ) : null}
          </div>
          {action && <motion.div whileHover={{ scale: 1.05 }}>{action}</motion.div>}
        </div>
        <motion.div
          aria-hidden
          className="pointer-events-none absolute -right-8 -top-12 h-32 w-32 rounded-full bg-blue-500/5 blur-2xl transition-all group-hover:bg-blue-500/10"
          animate={{ scale: [1, 1.2, 1] }}
          transition={{ duration: 4, repeat: Infinity }}
        />
      </GlassPanel>
    </motion.div>
  );
}
