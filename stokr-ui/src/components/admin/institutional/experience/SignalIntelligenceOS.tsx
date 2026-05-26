import { motion } from "framer-motion";
import { AlertTriangle, Radar, Target, TrendingDown } from "lucide-react";
import { cn } from "../../../../lib/utils";
import { toneChipClasses } from "../../../../lib/statusTone";
import {
  buildSignalConfidenceProfile,
  detectFalseBreakoutFlags,
  detectSignalClusters,
  type IntelSignal,
} from "../../../../lib/adminOperationalIntelligence";

function ConfidenceRing({ value, label, isLight }: { value: number; label: string; isLight: boolean }) {
  const color = value >= 75 ? "#34d399" : value >= 55 ? "#fbbf24" : "#f87171";
  return (
    <div className="flex flex-col items-center gap-1">
      <div className="relative h-14 w-14">
        <svg viewBox="0 0 36 36" className="h-14 w-14 -rotate-90">
          <circle cx="18" cy="18" r="15" fill="none" stroke={isLight ? "#e5e7eb" : "#404040"} strokeWidth="2.5" />
          <circle cx="18" cy="18" r="15" fill="none" stroke={color} strokeWidth="2.5" strokeDasharray={`${(value / 100) * 94} 94`} strokeLinecap="round" />
        </svg>
        <span className="absolute inset-0 flex items-center justify-center text-[10px] font-bold tabular-nums">{value}</span>
      </div>
      <span className="text-[9px] uppercase tracking-wide opacity-70">{label}</span>
    </div>
  );
}

function QualityRadar({ radar, isLight }: { radar: { axis: string; value: number }[]; isLight: boolean }) {
  const size = 120;
  const cx = size / 2;
  const cy = size / 2;
  const r = 44;
  const points = radar.map((d, i) => {
    const angle = (Math.PI * 2 * i) / radar.length - Math.PI / 2;
    const dist = (d.value / 100) * r;
    return `${cx + Math.cos(angle) * dist},${cy + Math.sin(angle) * dist}`;
  }).join(" ");

  return (
    <svg viewBox={`0 0 ${size} ${size}`} className="h-[120px] w-[120px]">
      {[0.25, 0.5, 0.75, 1].map((scale) => (
        <polygon
          key={scale}
          points={radar.map((_, i) => {
            const angle = (Math.PI * 2 * i) / radar.length - Math.PI / 2;
            return `${cx + Math.cos(angle) * r * scale},${cy + Math.sin(angle) * r * scale}`;
          }).join(" ")}
          fill="none"
          stroke={isLight ? "#d4d4d8" : "#52525b"}
          strokeWidth="0.5"
        />
      ))}
      <polygon points={points} fill={isLight ? "rgba(59,130,246,0.2)" : "rgba(59,130,246,0.35)"} stroke="#3b82f6" strokeWidth="1.5" />
    </svg>
  );
}

export function SignalConfidenceEnginePanel({
  signal,
  isLight,
}: {
  signal: IntelSignal | undefined;
  isLight: boolean;
}) {
  if (!signal) {
    return (
      <div className={cn("rounded-2xl border border-dashed px-4 py-8 text-center text-sm", isLight ? "border-neutral-300 text-neutral-500" : "border-neutral-700 text-neutral-400")}>
        Select a signal to inspect confidence engine
      </div>
    );
  }

  const profile = buildSignalConfidenceProfile(signal);
  const flags = detectFalseBreakoutFlags(signal, profile);
  const ageMs = signal.createdAt ? Date.now() - new Date(signal.createdAt).getTime() : 0;
  const decay = Math.max(0, 100 - Math.min(100, ageMs / 600_000 * 40));

  return (
    <div className={cn("rounded-2xl border p-5", isLight ? "border-neutral-200 bg-gradient-to-br from-white to-blue-50/30" : "border-neutral-800 bg-gradient-to-br from-neutral-950 to-blue-950/20")}>
      <div className="mb-4 flex flex-wrap items-end justify-between gap-2">
        <div>
          <p className={cn("text-[10px] font-bold uppercase tracking-[0.18em]", isLight ? "text-blue-700" : "text-blue-400")}>
            Signal confidence engine
          </p>
          <p className={cn("mt-1 text-sm", isLight ? "text-neutral-600" : "text-neutral-400")}>
            {String(signal.symbol ?? "").replace(/^NSE:/, "")} · composite {profile.composite}%
          </p>
        </div>
        <motion.div
          className={cn("rounded-full px-3 py-1 text-[10px] font-bold uppercase", profile.composite >= 70 ? "bg-emerald-500/15 text-emerald-400" : profile.composite >= 50 ? "bg-amber-500/15 text-amber-400" : "bg-rose-500/15 text-rose-400")}
          animate={{ opacity: [1, 0.65, 1] }}
          transition={{ duration: 2.5, repeat: Infinity }}
        >
          Conviction {profile.composite}%
        </motion.div>
      </div>

      <div className="grid gap-4 lg:grid-cols-[1fr_auto_1fr]">
        <div className="grid grid-cols-4 gap-2">
          <ConfidenceRing value={profile.probability} label="Prob" isLight={isLight} />
          <ConfidenceRing value={profile.regimeConfidence} label="Regime" isLight={isLight} />
          <ConfidenceRing value={profile.rrQuality} label="RR" isLight={isLight} />
          <ConfidenceRing value={100 - profile.exhaustionRisk} label="Fresh" isLight={isLight} />
        </div>
        <div className="flex items-center justify-center">
          <QualityRadar radar={profile.radar.slice(0, 6)} isLight={isLight} />
        </div>
        <div className="space-y-2">
          <p className={cn("text-[10px] font-bold uppercase tracking-wide", isLight ? "text-neutral-500" : "text-neutral-400")}>Signal decay</p>
          <div className={cn("h-2 overflow-hidden rounded-full", isLight ? "bg-neutral-200" : "bg-neutral-800")}>
            <motion.div className="h-full rounded-full bg-blue-500" initial={{ width: "100%" }} animate={{ width: `${decay}%` }} transition={{ duration: 1 }} />
          </div>
          <p className={cn("text-[11px]", isLight ? "text-neutral-500" : "text-neutral-400")}>
            Structure {profile.structureQuality}% · Participation {profile.participationQuality}%
          </p>
        </div>
      </div>

      {flags.length > 0 ? (
        <div className="mt-4 flex flex-wrap gap-2">
          {flags.map((f) => (
            <span
              key={f.label}
              title={f.detail}
              className={cn(
                "inline-flex items-center gap-1 rounded-full border px-2.5 py-1 text-[10px] font-semibold",
                toneChipClasses(isLight, f.severity === "high" ? "critical" : f.severity === "medium" ? "warn" : "neutral"),
              )}
            >
              <AlertTriangle className="h-3 w-3" /> {f.label}
            </span>
          ))}
        </div>
      ) : null}
    </div>
  );
}

export function SignalClusterStormPanel({ signals, isLight }: { signals: IntelSignal[]; isLight: boolean }) {
  const clusters = detectSignalClusters(signals);
  if (clusters.length === 0) {
    return (
      <div className={cn("rounded-xl border px-4 py-6 text-center text-xs", isLight ? "border-neutral-200 text-neutral-500" : "border-neutral-800 text-neutral-400")}>
        No signal storms detected in current window
      </div>
    );
  }

  return (
    <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
      {clusters.map((c, i) => (
        <motion.div
          key={c.label}
          initial={{ opacity: 0, scale: 0.95 }}
          animate={{ opacity: 1, scale: 1 }}
          transition={{ delay: i * 0.05 }}
          className={cn(
            "relative overflow-hidden rounded-xl border p-3",
            c.intensity > 0.6
              ? isLight ? "border-rose-300 bg-rose-50/80" : "border-rose-500/40 bg-rose-500/10"
              : isLight ? "border-amber-200 bg-amber-50/60" : "border-amber-500/30 bg-amber-500/10",
          )}
        >
          {c.intensity > 0.5 ? (
            <motion.div
              aria-hidden
              className="pointer-events-none absolute inset-0 bg-gradient-to-r from-transparent via-white/10 to-transparent"
              animate={{ x: ["-100%", "100%"] }}
              transition={{ duration: 2.5, repeat: Infinity, ease: "linear" }}
            />
          ) : null}
          <div className="relative flex items-start gap-2">
            {c.kind === "symbol_storm" ? <Target className="h-4 w-4 shrink-0 opacity-70" /> :
             c.kind === "sector_wave" ? <Radar className="h-4 w-4 shrink-0 opacity-70" /> :
             <TrendingDown className="h-4 w-4 shrink-0 opacity-70" />}
            <div>
              <p className="text-[11px] font-semibold">{c.label}</p>
              <p className={cn("text-[10px]", isLight ? "text-neutral-600" : "text-neutral-400")}>{c.count} signals · {Math.round(c.intensity * 100)}% intensity</p>
            </div>
          </div>
        </motion.div>
      ))}
    </div>
  );
}

export function SignalReplayIntelPanel({
  signal,
  isLight,
}: {
  signal: IntelSignal | undefined;
  isLight: boolean;
}) {
  if (!signal) return null;
  const profile = buildSignalConfidenceProfile(signal);
  const flags = detectFalseBreakoutFlags(signal, profile);
  const steps = [
    { phase: "Context", detail: signal.marketRegime ? `${signal.marketRegime} regime at emit` : "Regime unknown at emit", ok: Boolean(signal.marketRegime) },
    { phase: "Why fired", detail: signal.reason?.slice(0, 120) ?? "No rationale captured", ok: Boolean(signal.reason) },
    { phase: "Acceptance", detail: profile.composite >= 55 ? `Confidence gate passed (${profile.composite}%)` : "Would fail modern confidence gate", ok: profile.composite >= 55 },
    { phase: "Risk flags", detail: flags.length ? flags.map((f) => f.label).join(" · ") : "No trap flags", ok: flags.length === 0 },
    { phase: "Evolution", detail: signal.outcomeStatus ? `Outcome: ${signal.outcomeStatus.replace(/_/g, " ")}` : "Awaiting outcome", ok: signal.outcomeStatus === "TARGET_HIT" || signal.outcomeStatus === "RUNNING" },
  ];

  return (
    <div className={cn("rounded-2xl border p-4", isLight ? "border-neutral-200 bg-white" : "border-neutral-800 bg-neutral-950/50")}>
      <p className={cn("mb-3 text-[10px] font-bold uppercase tracking-[0.18em]", isLight ? "text-indigo-700" : "text-indigo-400")}>
        Live signal replay
      </p>
      <div className="relative space-y-0">
        {steps.map((s, i) => (
          <div key={s.phase} className="flex gap-3 pb-4">
            <div className="flex flex-col items-center">
              <div className={cn("h-2.5 w-2.5 rounded-full ring-2", s.ok ? "bg-emerald-400 ring-emerald-400/30" : "bg-amber-400 ring-amber-400/30")} />
              {i < steps.length - 1 ? <div className={cn("mt-1 w-px flex-1 min-h-[28px]", isLight ? "bg-neutral-200" : "bg-neutral-700")} /> : null}
            </div>
            <div>
              <p className="text-xs font-semibold">{s.phase}</p>
              <p className={cn("mt-0.5 text-[11px] leading-relaxed", isLight ? "text-neutral-600" : "text-neutral-400")}>{s.detail}</p>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
