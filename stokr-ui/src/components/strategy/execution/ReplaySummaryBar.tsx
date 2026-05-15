import { motion } from "framer-motion";
import { Play } from "lucide-react";
import { cn } from "../../../lib/utils";
import { useUiThemeStore } from "../../../state/uiTheme";
import type { ExecutionModeChoice } from "./ExecutionModeToggle";

type Props = {
  capitalLabel: string;
  rangeSummary: string;
  mode: ExecutionModeChoice;
  estimatedTrades: number;
  estimatedSeconds: number;
  onLaunch: () => void;
  disabled: boolean;
  pending: boolean;
  className?: string;
};

export function ReplaySummaryBar({
  capitalLabel,
  rangeSummary,
  mode,
  estimatedTrades,
  estimatedSeconds,
  onLaunch,
  disabled,
  pending,
  className,
}: Props) {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const cta =
    mode === "BACKTEST"
      ? pending
        ? "Initializing replay…"
        : "Launch historical replay"
      : "Coming soon";

  return (
    <motion.div
      layout
      className={cn(
        "sticky bottom-0 z-20 mt-6 rounded-2xl border p-4 backdrop-blur-md sm:p-5",
        isLight
          ? "border-slate-900/[0.08] bg-white/95 shadow-sm"
          : "border-[rgba(255,255,255,0.08)] bg-[#172033]/95 shadow-[0_-12px_40px_-16px_rgba(0,0,0,0.75)]",
        className,
      )}
    >
      <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
        <div className="grid flex-1 grid-cols-2 gap-3 text-sm sm:grid-cols-4">
          <div>
            <p className="text-[10px] font-semibold uppercase tracking-wide text-[#64748B]">Capital</p>
            <p className={cn("mt-0.5 font-semibold", isLight ? "text-[#0F172A]" : "text-[#F8FAFC]")}>{capitalLabel}</p>
          </div>
          <div>
            <p className="text-[10px] font-semibold uppercase tracking-wide text-[#64748B]">Range</p>
            <p className={cn("mt-0.5 font-medium", isLight ? "text-[#475569]" : "text-[#CBD5E1]")}>{rangeSummary}</p>
          </div>
          <div>
            <p className="text-[10px] font-semibold uppercase tracking-wide text-[#64748B]">Mode</p>
            <p className={cn("mt-0.5 font-semibold", isLight ? "text-[#0F172A]" : "text-[#F8FAFC]")}>{mode}</p>
          </div>
          <div>
            <p className="text-[10px] font-semibold uppercase tracking-wide text-[#64748B]">Estimates</p>
            <p className={cn("mt-0.5", isLight ? "text-[#64748B]" : "text-[#94A3B8]")}>
              ~{estimatedTrades} trades · ~{estimatedSeconds}s
            </p>
          </div>
        </div>
        <motion.button
          type="button"
          whileHover={{ scale: disabled ? 1 : 1.02 }}
          whileTap={{ scale: disabled ? 1 : 0.98 }}
          disabled={disabled}
          onClick={onLaunch}
          className={cn(
            "inline-flex w-full items-center justify-center gap-2 rounded-xl px-5 py-3 text-sm font-semibold text-white transition shadow-sm lg:w-auto lg:min-w-[220px]",
            "bg-[#2563EB] hover:bg-[#1d4ed8] disabled:cursor-not-allowed disabled:opacity-45",
          )}
        >
          <Play className="h-4 w-4 fill-current" />
          {cta}
        </motion.button>
      </div>
    </motion.div>
  );
}
