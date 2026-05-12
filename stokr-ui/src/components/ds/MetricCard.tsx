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
    <motion.div initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.25 }}>
      <GlassPanel
        variant={panelVariant}
        className={cn(
          "relative overflow-hidden p-5",
          highlight && (light ? "stokr-pulse-live border-blue-400/40" : "stokr-pulse-live border-blue-500/25"),
        )}
      >
        <div className="flex items-start justify-between gap-3">
          <div>
            <div className="flex items-center gap-2">
              {trendDot ? <span className={cn("inline-block h-1.5 w-1.5 rounded-full", trendDot)} aria-hidden /> : null}
              <div
                className={cn(
                  "text-[11px] font-semibold uppercase tracking-[0.12em]",
                  light ? "text-neutral-600" : "text-neutral-500",
                )}
              >
                {label}
              </div>
            </div>
            <div
              className={cn(
                "mt-2 font-mono text-2xl font-semibold tracking-tight",
                light ? "text-neutral-900" : "text-white",
              )}
            >
              {value}
            </div>
            {sublabel ? (
              <div className={cn("mt-2 text-xs", light ? "text-neutral-600" : "text-neutral-500")}>{sublabel}</div>
            ) : null}
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
