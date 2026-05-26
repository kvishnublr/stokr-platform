import { motion } from "framer-motion";
import { Activity, Dna, GitBranch, TrendingUp } from "lucide-react";
import { cn } from "../../../../lib/utils";
import { toneEyebrowClasses } from "../../../../lib/statusTone";
import {
  assessMarketFit,
  buildRejectionWaterfall,
  buildStrategyCorrelationPairs,
  computeStrategyQualityScore,
  extractMarketRegime,
  inferStrategyDna,
  type StrategyEngineIntelInput,
} from "../../../../lib/adminOperationalIntelligence";
import type { OpsSnapshot } from "../../cockpit/opsTypes";

function DnaBar({ label, value, isLight }: { label: string; value: number; isLight: boolean }) {
  return (
    <div>
      <div className="mb-1 flex justify-between text-[10px]">
        <span className="opacity-70">{label}</span>
        <span className="font-mono font-semibold">{value}</span>
      </div>
      <div className={cn("h-1.5 overflow-hidden rounded-full", isLight ? "bg-neutral-200" : "bg-neutral-800")}>
        <motion.div className="h-full rounded-full bg-violet-500" initial={{ width: 0 }} animate={{ width: `${value}%` }} transition={{ duration: 0.5 }} />
      </div>
    </div>
  );
}

export function StrategyDnaPanel({ code, riskLevel, isLight }: { code: string; riskLevel: string; isLight: boolean }) {
  const dna = inferStrategyDna(code, riskLevel);
  return (
    <div className={cn("rounded-xl border p-3", isLight ? "border-violet-200 bg-violet-50/40" : "border-violet-500/30 bg-violet-500/10")}>
      <div className="mb-2 flex items-center gap-2">
        <Dna className="h-3.5 w-3.5 text-violet-400" />
        <span className="text-[10px] font-bold uppercase tracking-wide">Strategy DNA · {dna.archetype}</span>
      </div>
      <div className="space-y-2">
        <DnaBar label="Momentum" value={dna.momentum} isLight={isLight} />
        <DnaBar label="Mean reversion" value={dna.meanReversion} isLight={isLight} />
        <DnaBar label="Breakout" value={dna.breakout} isLight={isLight} />
        <DnaBar label="Vol expansion" value={dna.volatilityExpansion} isLight={isLight} />
        <DnaBar label="Aggressiveness" value={dna.aggressiveness} isLight={isLight} />
      </div>
    </div>
  );
}

export function StrategyMarketFitPanel({
  engine,
  snapshot,
  isLight,
}: {
  engine: StrategyEngineIntelInput;
  snapshot: OpsSnapshot | undefined;
  isLight: boolean;
}) {
  const dna = inferStrategyDna(engine.code, engine.riskLevel);
  const regime = engine.marketRegime ?? extractMarketRegime(snapshot);
  const fit = assessMarketFit(dna, regime, engine.signalsToday ?? 0, engine.scanFailures ?? 0);
  const quality = computeStrategyQualityScore(engine.signalsToday ?? 0, engine.scanFailures ?? 0, fit);

  return (
    <div className={cn("rounded-xl border p-3", isLight ? "border-blue-200 bg-blue-50/40" : "border-blue-500/30 bg-blue-500/10")}>
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <TrendingUp className="h-3.5 w-3.5 text-blue-400" />
          <span className="text-[10px] font-bold uppercase tracking-wide">Market fit</span>
        </div>
        <span className={cn("rounded-full px-2 py-0.5 text-[10px] font-bold uppercase",
          fit.verdict === "favored" ? "bg-emerald-500/15 text-emerald-400" :
          fit.verdict === "degraded" ? "bg-rose-500/15 text-rose-400" : "bg-amber-500/15 text-amber-400",
        )}>
          {fit.verdict}
        </span>
      </div>
      <div className="mt-2 flex items-end gap-3">
        <span className="text-2xl font-bold tabular-nums">{fit.score}</span>
        <span className={cn("pb-1 text-[11px]", isLight ? "text-neutral-600" : "text-neutral-400")}>Live quality {quality}</span>
      </div>
      <ul className={cn("mt-2 space-y-1 text-[11px]", isLight ? "text-neutral-700" : "text-neutral-300")}>
        {fit.reasons.map((r) => <li key={r}>• {r}</li>)}
      </ul>
    </div>
  );
}

export function RejectionWaterfallPanel({ engine, isLight }: { engine: StrategyEngineIntelInput; isLight: boolean }) {
  const reasons = buildRejectionWaterfall(engine);
  if (reasons.length === 0) return null;

  return (
    <div className={cn("rounded-xl border p-3", isLight ? "border-amber-200 bg-amber-50/50" : "border-amber-500/30 bg-amber-500/10")}>
      <p className="text-[10px] font-bold uppercase tracking-wide text-amber-400">Rejection waterfall</p>
      <div className="mt-2 space-y-1.5">
        {reasons.map((r, i) => (
          <div key={r.code} className="flex items-center gap-2">
            <div className={cn("h-2 rounded-full", isLight ? "bg-amber-200" : "bg-amber-900/50")} style={{ width: `${Math.max(20, r.severity)}%` }} />
            <span className="text-[11px]">{r.label}</span>
            {i === 0 ? <span className="ml-auto text-[9px] uppercase text-amber-500">Primary block</span> : null}
          </div>
        ))}
      </div>
    </div>
  );
}

export function StrategyCorrelationPanel({
  engines,
  isLight,
}: {
  engines: { code: string; signalsToday?: number }[];
  isLight: boolean;
}) {
  const pairs = buildStrategyCorrelationPairs(engines);
  if (pairs.length === 0) {
    return (
      <div className={cn("rounded-xl border px-4 py-6 text-center text-xs", isLight ? "border-neutral-200 text-neutral-500" : "border-neutral-800 text-neutral-400")}>
        No overlapping strategy behavior detected today
      </div>
    );
  }

  return (
    <div className={cn("rounded-2xl border p-4", isLight ? "border-neutral-200 bg-white" : "border-neutral-800 bg-neutral-950/50")}>
      <div className="mb-3 flex items-center gap-2">
        <GitBranch className={cn("h-4 w-4", toneEyebrowClasses(isLight, "indigo"))} />
        <p className={cn("text-[10px] font-bold uppercase tracking-[0.18em]", toneEyebrowClasses(isLight, "indigo"))}>
          Strategy correlation engine
        </p>
      </div>
      <div className="grid gap-2 sm:grid-cols-2">
        {pairs.map((p) => (
          <div key={`${p.a}-${p.b}`} className={cn("rounded-lg border px-3 py-2", isLight ? "border-neutral-200" : "border-neutral-800")}>
            <div className="flex items-center gap-2 text-[11px] font-mono">
              <span>{p.a}</span>
              <Activity className="h-3 w-3 opacity-50" />
              <span>{p.b}</span>
            </div>
            <div className={cn("mt-1.5 h-1.5 overflow-hidden rounded-full", isLight ? "bg-neutral-200" : "bg-neutral-800")}>
              <div className="h-full rounded-full bg-indigo-500" style={{ width: `${p.overlap}%` }} />
            </div>
            <p className={cn("mt-1 text-[10px]", isLight ? "text-neutral-600" : "text-neutral-400")}>{p.overlap}% overlap · duplicate risk</p>
          </div>
        ))}
      </div>
    </div>
  );
}
