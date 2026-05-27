import { motion } from "framer-motion";
import { Clock3, Shield, Sparkles } from "lucide-react";
import { cn } from "../../lib/utils";
import { ASSET_CLASS_TABS, normalizeStrategyAssetClass } from "./AssetClassTabs";
import { RiskBadge } from "./RiskBadge";

export type StrategyCatalogCard = {
  id: string;
  code: string;
  name: string;
  description: string | null;
  riskLevel: string;
  subscribed: boolean;
  subscriptionEnabled: boolean;
  assetClass?: string | null;
  segment?: string | null;
  runtimeTag?: "RUNNING" | "PAUSED" | "BLOCKED" | "DEGRADED" | "NO_DATA" | "OFFLINE";
  runtimeNote?: string;
  executionMode?: "PAPER" | "LIVE";
  signalsToday?: number;
  lastSignalAt?: string | null;
  lastEvaluationAt?: string | null;
  assignedSymbols?: string[];
  candleReadiness?: string;
  omsState?: string;
};

type ActionId = "BACKTEST" | "PAPER" | "LIVE" | "PAUSE" | "UNSUBSCRIBE";

function statusTone(tag: StrategyCatalogCard["runtimeTag"], isLight: boolean): string {
  if (!tag) return isLight ? "bg-neutral-100 text-neutral-700" : "bg-neutral-800 text-neutral-300";
  if (tag === "RUNNING") return isLight ? "bg-emerald-100 text-emerald-800" : "bg-emerald-500/15 text-emerald-300";
  if (tag === "PAUSED") return isLight ? "bg-amber-100 text-amber-800" : "bg-amber-500/15 text-amber-300";
  if (tag === "BLOCKED" || tag === "OFFLINE") return isLight ? "bg-rose-100 text-rose-800" : "bg-rose-500/15 text-rose-300";
  return isLight ? "bg-orange-100 text-orange-800" : "bg-orange-500/15 text-orange-300";
}

export function StrategyCard({
  strategy,
  index,
  onAction,
  actionBusy,
  actionDisabled,
  variant = "dark",
}: {
  strategy: StrategyCatalogCard;
  index: number;
  onAction: (action: ActionId) => void;
  actionBusy?: ActionId | null;
  actionDisabled?: boolean;
  variant?: "dark" | "light";
}) {
  const isLight = variant === "light";
  const busy = !!actionBusy;
  const assetMeta = ASSET_CLASS_TABS.find((t) => t.id === normalizeStrategyAssetClass(strategy.assetClass));
  const actions: Array<{ id: ActionId; label: string }> = [
    { id: "BACKTEST", label: "Backtest" },
    { id: "PAPER", label: "Paper" },
    { id: "LIVE", label: "Live" },
    { id: "PAUSE", label: strategy.subscriptionEnabled ? "Pause" : "Resume" },
    { id: "UNSUBSCRIBE", label: strategy.subscribed ? "Unsubscribe" : "Subscribe" },
  ];

  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: Math.min(index * 0.04, 0.4) }}
      className={cn(
        "rounded-2xl border p-4",
        isLight ? "border-border bg-card text-foreground" : "border-neutral-800 bg-neutral-950 text-white",
      )}
    >
      <div className="mb-3 flex items-start justify-between gap-3">
        <div className="flex min-w-0 items-start gap-2.5">
          <div className={cn("rounded-lg p-2 ring-1", isLight ? "bg-blue-50 ring-blue-200" : "bg-blue-500/10 ring-blue-500/20")}>
            <Sparkles className={cn("h-4 w-4", isLight ? "text-blue-600" : "text-blue-300")} />
          </div>
          <div className="min-w-0">
            <div className={cn("truncate text-base font-semibold", isLight ? "text-foreground" : "text-white")}>{strategy.name}</div>
            <div className={cn("truncate font-mono text-[11px]", isLight ? "text-muted-foreground" : "text-neutral-400")}>{strategy.code}</div>
            {assetMeta ? (
              <span className={cn("mt-1 inline-flex rounded-full px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide", isLight ? "bg-muted text-muted-foreground" : "bg-neutral-800 text-neutral-400")}>
                {assetMeta.label}{strategy.segment ? ` · ${strategy.segment}` : ""}
              </span>
            ) : null}
          </div>
        </div>
        <RiskBadge level={strategy.riskLevel} variant={variant} />
      </div>

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <span className={cn("rounded-full px-2 py-1 text-[10px] font-bold uppercase tracking-wide", statusTone(strategy.runtimeTag, isLight))}>
          {strategy.runtimeTag ?? "NO_DATA"}
        </span>
        <span className={cn("rounded-full px-2 py-1 text-[10px] font-bold uppercase tracking-wide", isLight ? "bg-sky-100 text-sky-800" : "bg-sky-500/15 text-sky-300")}>
          {strategy.executionMode ?? "PAPER"}
        </span>
        <span className={cn("inline-flex items-center gap-1 text-[11px]", isLight ? "text-muted-foreground" : "text-neutral-400")}>
          <Clock3 className="h-3 w-3" />
          last signal {strategy.lastSignalAt ?? "-"}
        </span>
      </div>

      <div className={cn("grid grid-cols-2 gap-2 border-y py-3 text-[11px]", isLight ? "border-border" : "border-neutral-800")}>
        <div>
          <div className={isLight ? "text-muted-foreground" : "text-neutral-400"}>Signals today</div>
          <div className="font-semibold">{strategy.signalsToday ?? 0}</div>
        </div>
        <div>
          <div className={isLight ? "text-muted-foreground" : "text-neutral-400"}>Candle readiness</div>
          <div className="font-semibold">{strategy.candleReadiness ?? "-"}</div>
        </div>
        <div>
          <div className={isLight ? "text-muted-foreground" : "text-neutral-400"}>Assigned symbols</div>
          <div className="truncate font-semibold">{strategy.assignedSymbols?.join(", ") || "-"}</div>
        </div>
        <div>
          <div className={isLight ? "text-muted-foreground" : "text-neutral-400"}>OMS state</div>
          <div className="font-semibold">{strategy.omsState ?? "-"}</div>
        </div>
      </div>

      <div className={cn("mt-2 text-[11px]", isLight ? "text-muted-foreground" : "text-neutral-400")}>
        <Shield className="mr-1 inline h-3 w-3" />
        {strategy.runtimeNote ?? "Runtime eligible"}
      </div>

      <div className="mt-3 flex flex-wrap gap-2">
        {actions.map((a) => (
          <button
            key={a.id}
            type="button"
            disabled={actionDisabled || busy}
            onClick={() => onAction(a.id)}
            className={cn(
              "rounded-lg border px-2.5 py-1.5 text-[11px] font-semibold",
              isLight ? "border-border bg-background text-foreground hover:bg-card" : "border-neutral-700 bg-neutral-900 text-neutral-200 hover:bg-neutral-800",
              (actionDisabled || busy) && "cursor-not-allowed opacity-60",
            )}
          >
            {actionBusy === a.id ? "Working..." : a.label}
          </button>
        ))}
      </div>
    </motion.div>
  );
}
