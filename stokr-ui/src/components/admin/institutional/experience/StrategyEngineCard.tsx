import { Eye, EyeOff, Layers, Radar } from "lucide-react";
import { Link } from "react-router-dom";
import { motion } from "framer-motion";
import { cn } from "../../../../lib/utils";
import { AdminPulseDot } from "../AdminDesignSystem";
import {
  RejectionWaterfallPanel,
  StrategyDnaPanel,
  StrategyMarketFitPanel,
} from "./StrategyIntelligenceLayer";
import type { OpsSnapshot } from "../../cockpit/opsTypes";
import type { StrategyEngineIntelInput } from "../../../../lib/adminOperationalIntelligence";

export type StrategyEngineData = {
  id: string;
  code: string;
  displayName: string | null;
  enabled: boolean;
  visibleToUsers: boolean;
  riskLevel: string;
  signalsToday?: number;
  lastSignalAt?: string | null;
  runningInstances?: number;
  scanFailures?: number;
  haltedReason?: string | null;
  boundGroups?: number;
};

function whyNotFiring(s: StrategyEngineData): string[] {
  const reasons: string[] = [];
  if (!s.enabled) reasons.push("Strategy disabled in catalog");
  if (!s.visibleToUsers) reasons.push("Hidden from traders");
  if ((s.boundGroups ?? 0) === 0) reasons.push("No universe binding — scanner idle");
  if ((s.runningInstances ?? 0) === 0) reasons.push("No RUNNING runtime instances");
  if ((s.scanFailures ?? 0) > 3) reasons.push(`Scan failures elevated (${s.scanFailures})`);
  if (s.haltedReason) reasons.push(s.haltedReason);
  if ((s.signalsToday ?? 0) === 0 && reasons.length === 0) {
    reasons.push("No signals today — check regime fit or confidence gates");
  }
  return reasons.slice(0, 3);
}

function toIntelInput(s: StrategyEngineData, marketRegime?: string): StrategyEngineIntelInput {
  return {
    code: s.code,
    enabled: s.enabled,
    visibleToUsers: s.visibleToUsers,
    riskLevel: s.riskLevel,
    signalsToday: s.signalsToday,
    runningInstances: s.runningInstances,
    scanFailures: s.scanFailures,
    haltedReason: s.haltedReason,
    boundGroups: s.boundGroups,
    lastSignalAt: s.lastSignalAt,
    marketRegime,
  };
}

export function StrategyEngineCard({
  strategy,
  isLight,
  onToggleEnabled,
  onToggleVisible,
  bindingSlot,
  marketRegime,
  opsSnapshot,
}: {
  strategy: StrategyEngineData;
  isLight: boolean;
  onToggleEnabled: () => void;
  onToggleVisible: () => void;
  bindingSlot?: React.ReactNode;
  marketRegime?: string;
  opsSnapshot?: OpsSnapshot;
}) {
  const reasons = whyNotFiring(strategy);
  const intel = toIntelInput(strategy, marketRegime);
  const firing = strategy.enabled && (strategy.signalsToday ?? 0) > 0 && (strategy.runningInstances ?? 0) > 0;
  const healthPct = firing ? 78 : strategy.enabled ? 42 : 12;

  return (
    <motion.div
      layout
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      className={cn(
        "relative overflow-hidden rounded-2xl border p-5 transition-shadow hover:shadow-xl",
        isLight
          ? strategy.enabled
            ? "border-neutral-200 bg-gradient-to-br from-white via-white to-blue-50/30"
            : "border-neutral-200 bg-neutral-50/80 opacity-80"
          : strategy.enabled
            ? "border-neutral-800 bg-gradient-to-br from-neutral-950 via-neutral-900/80 to-blue-950/20"
            : "border-neutral-800/80 bg-neutral-950/40 opacity-75",
      )}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className={cn("text-sm font-semibold break-words", isLight ? "text-neutral-900" : "text-white")}>
              {strategy.displayName ?? strategy.code}
            </h3>
            <span
              className={cn(
                "rounded-full px-2 py-0.5 text-[10px] font-bold uppercase",
                strategy.riskLevel === "HIGH"
                  ? "bg-rose-500/15 text-rose-400"
                  : strategy.riskLevel === "LOW"
                    ? "bg-emerald-500/15 text-emerald-400"
                    : "bg-amber-500/15 text-amber-400",
              )}
            >
              {strategy.riskLevel}
            </span>
          </div>
          <p className={cn("mt-0.5 font-mono text-[10px]", isLight ? "text-neutral-500" : "text-neutral-500")}>{strategy.code}</p>
        </div>
        <div className="relative h-14 w-14 shrink-0">
          <svg viewBox="0 0 36 36" className="h-14 w-14 -rotate-90">
            <circle cx="18" cy="18" r="15" fill="none" stroke={isLight ? "#e5e7eb" : "#404040"} strokeWidth="2.5" />
            <circle
              cx="18"
              cy="18"
              r="15"
              fill="none"
              stroke={firing ? "#34d399" : "#60a5fa"}
              strokeWidth="2.5"
              strokeDasharray={`${(healthPct / 100) * 94} 94`}
            />
          </svg>
          <span className="absolute inset-0 flex flex-col items-center justify-center">
            <AdminPulseDot live={firing} tone={firing ? "ok" : "warn"} />
          </span>
        </div>
      </div>

      <div className="mt-4 grid grid-cols-3 gap-2 text-center">
        {[
          { label: "Signals", value: String(strategy.signalsToday ?? 0) },
          { label: "Runtime", value: String(strategy.runningInstances ?? 0) },
          { label: "Groups", value: String(strategy.boundGroups ?? 0) },
        ].map((m) => (
          <div key={m.label} className={cn("rounded-lg border px-2 py-2", isLight ? "border-neutral-200 bg-white/60" : "border-neutral-800 bg-neutral-900/40")}>
            <div className="text-[9px] uppercase tracking-wide opacity-60">{m.label}</div>
            <div className="font-mono text-sm font-bold">{m.value}</div>
          </div>
        ))}
      </div>

      <div className="mt-4 grid gap-3 sm:grid-cols-2">
        <StrategyDnaPanel code={strategy.code} riskLevel={strategy.riskLevel} isLight={isLight} />
        <StrategyMarketFitPanel engine={intel} snapshot={opsSnapshot} isLight={isLight} />
      </div>

      {!firing ? <div className="mt-3"><RejectionWaterfallPanel engine={intel} isLight={isLight} /></div> : null}

      {!firing && reasons.length > 0 ? (
        <div className={cn("mt-4 rounded-xl border px-3 py-2.5", isLight ? "border-amber-200 bg-amber-50/80" : "border-amber-500/30 bg-amber-500/10")}>
          <p className={cn("text-[10px] font-bold uppercase tracking-wide", isLight ? "text-amber-800" : "text-amber-200")}>
            Why not firing
          </p>
          <ul className={cn("mt-1.5 space-y-1 text-[11px]", isLight ? "text-amber-900/90" : "text-amber-100/90")}>
            {reasons.map((r) => (
              <li key={r}>• {r}</li>
            ))}
          </ul>
        </div>
      ) : null}

      <div className="mt-4 flex flex-wrap gap-2">
        <button
          type="button"
          onClick={onToggleEnabled}
          className={cn(
            "inline-flex items-center gap-1 rounded-lg border px-2.5 py-1.5 text-[11px] font-semibold",
            strategy.enabled
              ? isLight
                ? "border-emerald-300 bg-emerald-50 text-emerald-800"
                : "border-emerald-600/40 bg-emerald-500/10 text-emerald-200"
              : isLight
                ? "border-neutral-300 bg-white text-neutral-600"
                : "border-neutral-700 bg-neutral-900 text-neutral-300",
          )}
        >
          {strategy.enabled ? "Enabled" : "Disabled"}
        </button>
        <button
          type="button"
          onClick={onToggleVisible}
          className={cn(
            "inline-flex items-center gap-1 rounded-lg border px-2.5 py-1.5 text-[11px] font-semibold",
            isLight ? "border-neutral-300 bg-white text-neutral-700" : "border-neutral-700 bg-neutral-900 text-neutral-200",
          )}
        >
          {strategy.visibleToUsers ? <Eye className="h-3 w-3" /> : <EyeOff className="h-3 w-3" />}
          {strategy.visibleToUsers ? "Visible" : "Hidden"}
        </button>
        <Link
          to="/admin/runtime-bindings"
          className={cn(
            "inline-flex items-center gap-1 rounded-lg border px-2.5 py-1.5 text-[11px] font-semibold",
            isLight ? "border-blue-300 bg-blue-50 text-blue-800" : "border-blue-600/40 bg-blue-500/10 text-blue-200",
          )}
        >
          <Layers className="h-3 w-3" /> Bindings
        </Link>
      </div>

      {bindingSlot ? <div className="mt-3 border-t border-dashed pt-3">{bindingSlot}</div> : null}
    </motion.div>
  );
}

export function StrategyControlTowerHeader({ isLight }: { isLight: boolean }) {
  return (
    <div className="flex flex-wrap gap-2">
      <Link
        to="/admin/strategy-catalog"
        className={cn(
          "inline-flex items-center gap-1 rounded-lg border px-3 py-1.5 text-xs font-semibold",
          isLight ? "border-violet-300 bg-violet-50 text-violet-800" : "border-violet-500/40 bg-violet-500/10 text-violet-200",
        )}
      >
        <Radar className="h-3.5 w-3.5" /> Catalog
      </Link>
    </div>
  );
}
