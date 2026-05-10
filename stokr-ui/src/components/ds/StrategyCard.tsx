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
}: {
  strategy: StrategyCatalogCard;
  index: number;
  onToggle: () => void;
  actionDisabled: boolean;
  actionBusy: boolean;
}) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: Math.min(index * 0.04, 0.4) }}
      className="group relative overflow-hidden rounded-2xl border border-neutral-800/90 bg-gradient-to-br from-neutral-900/95 via-neutral-950 to-neutral-950 p-5 shadow-[0_20px_50px_-30px_rgba(0,0,0,0.9)]"
    >
      <div className="pointer-events-none absolute inset-px rounded-2xl bg-[linear-gradient(135deg,rgba(255,255,255,0.06),transparent_40%)]" />
      <div className="absolute right-4 top-4 flex items-center gap-2">
        <RiskBadge level={strategy.riskLevel} />
      </div>
      <div className="relative flex items-start gap-3 pt-6">
        <div className="rounded-xl bg-blue-500/12 p-2.5 ring-1 ring-blue-500/25">
          <Sparkles className="h-5 w-5 text-blue-400" />
        </div>
        <div className="min-w-0 flex-1">
          <div className="text-[15px] font-semibold leading-snug tracking-tight text-white">{strategy.name}</div>
          <div className="mt-1 font-mono text-[11px] text-neutral-500">{strategy.code}</div>
          <p className="mt-3 line-clamp-3 text-sm leading-relaxed text-neutral-400">
            {strategy.description ??
              "Systematic playbook with risk controls, replay lineage, and OMS-backed execution readiness."}
          </p>
        </div>
      </div>

      <div className="relative mt-6 flex flex-wrap items-center justify-between gap-3 border-t border-neutral-800/80 pt-4">
        <div className="flex items-center gap-2 text-[11px] text-neutral-500">
          <Shield className="h-3.5 w-3.5 text-neutral-600" aria-hidden />
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
              "bg-white text-neutral-950 shadow hover:bg-neutral-100 active:scale-[0.98]",
          )}
        >
          {actionBusy ? "…" : strategy.subscribed ? (strategy.subscriptionEnabled ? "Pause" : "Resume") : "Subscribe"}
        </button>
      </div>

      <div className="pointer-events-none absolute -bottom-10 -right-10 h-40 w-40 rounded-full bg-blue-600/[0.04] blur-3xl" />
    </motion.div>
  );
}
