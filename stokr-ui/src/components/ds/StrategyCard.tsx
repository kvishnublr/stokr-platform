import { motion } from "framer-motion";
import { Shield, Sparkles } from "lucide-react";
import { RiskBadge } from "./RiskBadge";
import { cn } from "../../lib/utils";

export type StrategyCatalogCard = {
  id: string;
  code: string;
  name: string;
  description: string | null;
  riskLevel: string;
  subscribed: boolean;
  subscriptionEnabled: boolean;
};

export function StrategyCard({
  strategy,
  index,
  onToggle,
  actionDisabled,
  actionBusy,
  variant = "dark",
}: {
  strategy: StrategyCatalogCard;
  index: number;
  onToggle: () => void;
  actionDisabled: boolean;
  actionBusy: boolean;
  variant?: "dark" | "light";
}) {
  const isLight = variant === "light";
  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: Math.min(index * 0.04, 0.4) }}
      className={cn(
        "group relative overflow-hidden rounded-2xl p-5",
        isLight
          ? "border border-neutral-200 bg-gradient-to-br from-white via-neutral-50 to-white shadow-[0_8px_30px_-18px_rgba(15,23,42,0.28)] transition hover:-translate-y-0.5 hover:border-blue-200 hover:shadow-[0_18px_40px_-20px_rgba(59,130,246,0.3)]"
          : "border border-neutral-800/90 bg-gradient-to-br from-neutral-900/95 via-neutral-950 to-neutral-950 shadow-[0_20px_50px_-30px_rgba(0,0,0,0.9)]",
      )}
    >
      <div
        className={cn(
          "pointer-events-none absolute inset-px rounded-2xl",
          isLight
            ? "bg-[linear-gradient(135deg,rgba(255,255,255,0.9),rgba(255,255,255,0)_45%)]"
            : "bg-[linear-gradient(135deg,rgba(255,255,255,0.06),transparent_40%)]",
        )}
      />
      <div className="absolute right-4 top-4 flex items-center gap-2">
        <RiskBadge level={strategy.riskLevel} variant={variant} />
      </div>
      <div className="relative flex items-start gap-3 pt-6">
        <div
          className={cn(
            "rounded-xl p-2.5 ring-1",
            isLight ? "bg-blue-50 ring-blue-200" : "bg-blue-500/12 ring-blue-500/25",
          )}
        >
          <Sparkles className={cn("h-5 w-5", isLight ? "text-blue-600" : "text-blue-400")} />
        </div>
        <div className="min-w-0 flex-1">
          <div className={cn("text-[15px] font-semibold leading-snug tracking-tight", isLight ? "text-neutral-900" : "text-white")}>
            {strategy.name}
          </div>
          <div className={cn("mt-1 font-mono text-[11px]", isLight ? "text-neutral-500" : "text-neutral-500")}>{strategy.code}</div>
          <p className={cn("mt-3 line-clamp-3 text-sm leading-relaxed", isLight ? "text-neutral-600" : "text-neutral-400")}>
            {strategy.description ??
              "Systematic playbook with risk controls, replay lineage, and OMS-backed execution readiness."}
          </p>
        </div>
      </div>

      <div className={cn("relative mt-6 flex flex-wrap items-center justify-between gap-3 border-t pt-4", isLight ? "border-neutral-200" : "border-neutral-800/80")}>
        <div className={cn("flex items-center gap-2 text-[11px]", isLight ? "text-neutral-500" : "text-neutral-500")}>
          <Shield className={cn("h-3.5 w-3.5", isLight ? "text-neutral-400" : "text-neutral-600")} aria-hidden />
          <span className="max-w-[12rem] truncate">
            {strategy.subscribed
              ? strategy.subscriptionEnabled
                ? "Runtime eligible · subscription enabled"
                : "Paused subscription"
              : "Not subscribed"}
          </span>
        </div>
        <button
          type="button"
          disabled={actionDisabled || actionBusy}
          onClick={onToggle}
          className={cn(
            "rounded-lg px-4 py-2 text-xs font-semibold transition",
            actionDisabled && "cursor-not-allowed opacity-45",
            !actionDisabled &&
              (isLight
                ? "bg-blue-600 text-white shadow hover:bg-blue-500 active:scale-[0.98]"
                : "bg-white text-neutral-950 shadow hover:bg-neutral-100 active:scale-[0.98]"),
          )}
        >
          {actionBusy ? "…" : strategy.subscribed ? (strategy.subscriptionEnabled ? "Pause" : "Resume") : "Subscribe"}
        </button>
      </div>

      <div
        className={cn(
          "pointer-events-none absolute -bottom-10 -right-10 h-40 w-40 rounded-full blur-3xl",
          isLight ? "bg-blue-500/[0.08]" : "bg-blue-600/[0.04]",
        )}
      />
    </motion.div>
  );
}
