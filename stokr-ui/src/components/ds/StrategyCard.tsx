import { motion } from "framer-motion";
import { Clock3, Pause, Play, Shield, Sparkles, TestTube2 } from "lucide-react";
import { cn } from "../../lib/utils";
import { ASSET_CLASS_TABS, normalizeStrategyAssetClass } from "./AssetClassTabs";
import { GlassPanel } from "./GlassPanel";
import { RiskBadge } from "./RiskBadge";

export type StrategyExecutionMode = "PAPER" | "LIVE" | "BOTH";

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
  executionMode?: StrategyExecutionMode;
  signalsToday?: number;
  lastSignalAt?: string | null;
  lastEvaluationAt?: string | null;
  assignedSymbols?: string[];
  candleReadiness?: string;
  omsState?: string;
};

type ActionId = "BACKTEST" | "SET_MODE" | "PAUSE" | "RESUME" | "SUBSCRIBE" | "UNSUBSCRIBE";

function statusTone(tag: StrategyCatalogCard["runtimeTag"], isLight: boolean): string {
  if (!tag) return isLight ? "bg-neutral-100 text-neutral-700" : "bg-neutral-800 text-neutral-300";
  if (tag === "RUNNING") return isLight ? "bg-emerald-100 text-emerald-800" : "bg-emerald-500/15 text-emerald-300";
  if (tag === "PAUSED") return isLight ? "bg-amber-100 text-amber-800" : "bg-amber-500/15 text-amber-300";
  if (tag === "BLOCKED" || tag === "OFFLINE") return isLight ? "bg-rose-100 text-rose-800" : "bg-rose-500/15 text-rose-300";
  return isLight ? "bg-orange-100 text-orange-800" : "bg-orange-500/15 text-orange-300";
}

function modeTone(mode: StrategyExecutionMode | undefined, isLight: boolean): string {
  const m = mode ?? "PAPER";
  if (m === "LIVE") return isLight ? "bg-emerald-100 text-emerald-800 ring-emerald-200" : "bg-emerald-500/15 text-emerald-300 ring-emerald-500/30";
  if (m === "BOTH") return isLight ? "bg-sky-100 text-sky-800 ring-sky-200" : "bg-sky-500/15 text-sky-300 ring-sky-500/30";
  return isLight ? "bg-neutral-100 text-neutral-700 ring-neutral-200" : "bg-neutral-800 text-neutral-300 ring-neutral-700";
}

const EXECUTION_MODES: StrategyExecutionMode[] = ["PAPER", "LIVE", "BOTH"];

export function StrategyCard({
  strategy,
  index,
  onAction,
  onModeChange,
  actionBusy,
  actionDisabled,
  variant = "dark",
}: {
  strategy: StrategyCatalogCard;
  index: number;
  onAction: (action: Exclude<ActionId, "SET_MODE">) => void;
  onModeChange: (mode: StrategyExecutionMode) => void;
  actionBusy?: ActionId | StrategyExecutionMode | null;
  actionDisabled?: boolean;
  variant?: "dark" | "light";
}) {
  const isLight = variant === "light";
  const busy = !!actionBusy;
  const assetMeta = ASSET_CLASS_TABS.find((t) => t.id === normalizeStrategyAssetClass(strategy.assetClass));
  const activeMode = strategy.executionMode ?? "PAPER";
  const isSubscribed = strategy.subscriptionEnabled;

  return (
    <motion.div
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: Math.min(index * 0.04, 0.4), type: "spring", stiffness: 380, damping: 28 }}
    >
      <GlassPanel
        variant={isLight ? "light" : "dark"}
        interactive
        className={cn(
          "group relative overflow-hidden p-0",
          isLight ? "border-border/80" : "border-neutral-800/90",
        )}
      >
        <div
          className={cn(
            "pointer-events-none absolute inset-x-0 top-0 h-24 bg-gradient-to-br opacity-60 transition-opacity group-hover:opacity-80",
            assetMeta?.id === "EQUITY" && (isLight ? "from-blue-500/10 to-transparent" : "from-blue-500/20 to-transparent"),
            assetMeta?.id === "FUTURES" && (isLight ? "from-violet-500/10 to-transparent" : "from-violet-500/20 to-transparent"),
            assetMeta?.id === "OPTIONS" && (isLight ? "from-pink-500/10 to-transparent" : "from-pink-500/20 to-transparent"),
            assetMeta?.id === "COMMODITY" && (isLight ? "from-amber-500/10 to-transparent" : "from-amber-500/20 to-transparent"),
            assetMeta?.id === "CURRENCY" && (isLight ? "from-teal-500/10 to-transparent" : "from-teal-500/20 to-transparent"),
            (!assetMeta || assetMeta.id === "ALL") && (isLight ? "from-slate-500/5 to-transparent" : "from-neutral-500/10 to-transparent"),
          )}
        />

        <div className="relative p-4">
          <div className="mb-3 flex items-start justify-between gap-3">
            <div className="flex min-w-0 items-start gap-3">
              <div
                className={cn(
                  "rounded-xl p-2.5 ring-1",
                  isLight ? "bg-blue-50 ring-blue-200/80" : "bg-blue-500/10 ring-blue-500/25",
                )}
              >
                <Sparkles className={cn("h-4 w-4", isLight ? "text-blue-600" : "text-blue-300")} />
              </div>
              <div className="min-w-0">
                <div className={cn("truncate text-[15px] font-semibold tracking-tight", isLight ? "text-foreground" : "text-white")}>
                  {strategy.name}
                </div>
                <div className={cn("truncate font-mono text-[11px]", isLight ? "text-muted-foreground" : "text-neutral-400")}>
                  {strategy.code}
                </div>
                {assetMeta ? (
                  <span
                    className={cn(
                      "mt-1.5 inline-flex rounded-full px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide",
                      isLight ? "bg-muted/80 text-muted-foreground" : "bg-neutral-800/80 text-neutral-400",
                    )}
                  >
                    {assetMeta.label}
                    {strategy.segment ? ` · ${strategy.segment}` : ""}
                  </span>
                ) : null}
              </div>
            </div>
            <RiskBadge level={strategy.riskLevel} variant={variant} />
          </div>

          {strategy.description ? (
            <p className={cn("mb-3 line-clamp-2 text-xs leading-relaxed", isLight ? "text-muted-foreground" : "text-neutral-400")}>
              {strategy.description}
            </p>
          ) : null}

          <div className="mb-3 flex flex-wrap items-center gap-2">
            <span className={cn("rounded-full px-2 py-1 text-[10px] font-bold uppercase tracking-wide", statusTone(strategy.runtimeTag, isLight))}>
              {strategy.runtimeTag ?? "NO_DATA"}
            </span>
            <span className={cn("rounded-full px-2 py-1 text-[10px] font-bold uppercase tracking-wide ring-1", modeTone(activeMode, isLight))}>
              {activeMode}
            </span>
            <span className={cn("inline-flex items-center gap-1 text-[11px]", isLight ? "text-muted-foreground" : "text-neutral-400")}>
              <Clock3 className="h-3 w-3" />
              {strategy.lastSignalAt ?? "no signals"}
            </span>
          </div>

          <div className={cn("grid grid-cols-2 gap-x-3 gap-y-2 border-y py-3 text-[11px]", isLight ? "border-border/70" : "border-neutral-800/80")}>
            <div>
              <div className={isLight ? "text-muted-foreground" : "text-neutral-500"}>Signals today</div>
              <div className="mt-0.5 text-sm font-semibold tabular-nums">{strategy.signalsToday ?? 0}</div>
            </div>
            <div>
              <div className={isLight ? "text-muted-foreground" : "text-neutral-500"}>Candle readiness</div>
              <div className="mt-0.5 text-sm font-semibold">{strategy.candleReadiness ?? "—"}</div>
            </div>
            <div className="col-span-2">
              <div className={isLight ? "text-muted-foreground" : "text-neutral-500"}>Universe</div>
              <div className="mt-0.5 truncate text-sm font-semibold">{strategy.assignedSymbols?.join(", ") || "—"}</div>
            </div>
          </div>

          <div className={cn("mt-2.5 flex items-start gap-1.5 text-[11px] leading-snug", isLight ? "text-muted-foreground" : "text-neutral-400")}>
            <Shield className="mt-0.5 h-3 w-3 shrink-0" />
            <span>{strategy.runtimeNote ?? "Runtime eligible"}</span>
          </div>

          <div className="mt-4 space-y-3">
            <div>
              <div className={cn("mb-1.5 text-[10px] font-semibold uppercase tracking-wider", isLight ? "text-muted-foreground" : "text-neutral-500")}>
                Execution mode
              </div>
              <div
                className={cn(
                  "inline-flex rounded-xl p-0.5 ring-1",
                  isLight ? "bg-muted/50 ring-border" : "bg-neutral-900/80 ring-neutral-800",
                )}
                role="group"
                aria-label="Execution mode"
              >
                {EXECUTION_MODES.map((mode) => {
                  const selected = activeMode === mode;
                  const modeBusy = actionBusy === mode;
                  return (
                    <button
                      key={mode}
                      type="button"
                      disabled={actionDisabled || busy || !isSubscribed}
                      onClick={() => onModeChange(mode)}
                      className={cn(
                        "relative rounded-lg px-3 py-1.5 text-[11px] font-bold uppercase tracking-wide transition-all",
                        selected
                          ? mode === "LIVE"
                            ? isLight
                              ? "bg-emerald-600 text-white shadow-sm"
                              : "bg-emerald-500 text-white shadow-sm"
                            : mode === "BOTH"
                              ? isLight
                                ? "bg-sky-600 text-white shadow-sm"
                                : "bg-sky-500 text-white shadow-sm"
                              : isLight
                                ? "bg-neutral-800 text-white shadow-sm"
                                : "bg-neutral-200 text-neutral-900 shadow-sm"
                          : isLight
                            ? "text-muted-foreground hover:text-foreground"
                            : "text-neutral-400 hover:text-neutral-200",
                        (actionDisabled || !isSubscribed) && "cursor-not-allowed opacity-50",
                      )}
                    >
                      {modeBusy ? "…" : mode}
                    </button>
                  );
                })}
              </div>
              {!isSubscribed ? (
                <p className={cn("mt-1 text-[10px]", isLight ? "text-muted-foreground" : "text-neutral-500")}>
                  Subscribe to set execution mode
                </p>
              ) : null}
            </div>

            <div className="flex flex-wrap gap-2">
              <button
                type="button"
                disabled={actionDisabled || busy}
                onClick={() => onAction("BACKTEST")}
                className={cn(
                  "inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-[11px] font-semibold transition-colors",
                  isLight
                    ? "border-border bg-background text-foreground hover:bg-muted/60"
                    : "border-neutral-700 bg-neutral-900 text-neutral-200 hover:bg-neutral-800",
                  (actionDisabled || busy) && "cursor-not-allowed opacity-60",
                )}
              >
                <TestTube2 className="h-3 w-3" />
                {actionBusy === "BACKTEST" ? "Working…" : "Backtest"}
              </button>

              {isSubscribed ? (
                <button
                  type="button"
                  disabled={actionDisabled || busy}
                  onClick={() => onAction(strategy.runtimeTag === "RUNNING" ? "PAUSE" : "RESUME")}
                  className={cn(
                    "inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-[11px] font-semibold transition-colors",
                    isLight
                      ? "border-amber-200 bg-amber-50 text-amber-800 hover:bg-amber-100"
                      : "border-amber-500/30 bg-amber-500/10 text-amber-300 hover:bg-amber-500/20",
                    (actionDisabled || busy) && "cursor-not-allowed opacity-60",
                  )}
                >
                  {strategy.runtimeTag === "RUNNING" ? <Pause className="h-3 w-3" /> : <Play className="h-3 w-3" />}
                  {actionBusy === "PAUSE" || actionBusy === "RESUME"
                    ? "Working…"
                    : strategy.runtimeTag === "RUNNING"
                      ? "Pause"
                      : "Resume"}
                </button>
              ) : null}

              <button
                type="button"
                disabled={actionDisabled || busy}
                onClick={() => onAction(isSubscribed ? "UNSUBSCRIBE" : "SUBSCRIBE")}
                className={cn(
                  "inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-[11px] font-semibold transition-colors",
                  isSubscribed
                    ? isLight
                      ? "border-rose-200 bg-rose-50 text-rose-700 hover:bg-rose-100"
                      : "border-rose-500/30 bg-rose-500/10 text-rose-300 hover:bg-rose-500/20"
                    : isLight
                      ? "border-blue-200 bg-blue-50 text-blue-700 hover:bg-blue-100"
                      : "border-blue-500/30 bg-blue-500/10 text-blue-300 hover:bg-blue-500/20",
                  (actionDisabled || busy) && "cursor-not-allowed opacity-60",
                )}
              >
                {actionBusy === "SUBSCRIBE" || actionBusy === "UNSUBSCRIBE"
                  ? "Working…"
                  : isSubscribed
                    ? "Unsubscribe"
                    : "Subscribe"}
              </button>
            </div>
          </div>
        </div>
      </GlassPanel>
    </motion.div>
  );
}
