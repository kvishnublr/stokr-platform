import { motion } from "framer-motion";
import { Activity, Target, Timer, TrendingUp, Zap } from "lucide-react";
import { cn } from "../../../../lib/utils";
import { AdminHeatCell } from "../AdminDesignSystem";

export type SignalQualityStats = {
  totalToday: number;
  buyToday: number;
  sellToday: number;
  liveToday: number;
  paperToday: number;
  avgConfidence: number | null;
  targetHit: number;
  slHit: number;
  running: number;
  expired: number;
  totalAllTime: number;
};

export function SignalQualityEngine({ stats, isLight }: { stats: SignalQualityStats | undefined; isLight: boolean }) {
  const winDenom = (stats?.targetHit ?? 0) + (stats?.slHit ?? 0);
  const winRate = winDenom > 0 ? Math.round(((stats?.targetHit ?? 0) / winDenom) * 100) : null;
  const expiryRate =
    stats && stats.totalToday > 0 ? Math.round(((stats.expired ?? 0) / stats.totalToday) * 100) : null;
  const confPct = stats?.avgConfidence != null ? Math.round(Number(stats.avgConfidence) * (stats.avgConfidence <= 1 ? 100 : 1)) : null;

  const tiles = [
    { icon: Zap, label: "Signals today", value: String(stats?.totalToday ?? 0), intensity: Math.min(1, (stats?.totalToday ?? 0) / 40) },
    { icon: TrendingUp, label: "Win rate", value: winRate != null ? `${winRate}%` : "—", intensity: (winRate ?? 0) / 100 },
    { icon: Target, label: "Running", value: String(stats?.running ?? 0), intensity: Math.min(1, (stats?.running ?? 0) / 10) },
    { icon: Timer, label: "Expiry %", value: expiryRate != null ? `${expiryRate}%` : "—", intensity: (expiryRate ?? 0) / 100 },
    { icon: Activity, label: "Confidence", value: confPct != null ? `${confPct}%` : "—", intensity: (confPct ?? 0) / 100 },
    { icon: Zap, label: "Live / Paper", value: `${stats?.liveToday ?? 0} / ${stats?.paperToday ?? 0}`, intensity: Math.min(1, (stats?.liveToday ?? 0) / 20) },
  ];

  return (
    <div
      className={cn(
        "rounded-2xl border p-5",
        isLight
          ? "border-neutral-200 bg-gradient-to-br from-white via-blue-50/30 to-indigo-50/40"
          : "border-neutral-800 bg-gradient-to-br from-neutral-950 via-blue-950/20 to-indigo-950/15",
      )}
    >
      <div className="mb-4 flex flex-wrap items-end justify-between gap-2">
        <div>
          <p className={cn("text-[10px] font-bold uppercase tracking-[0.18em]", isLight ? "text-blue-700" : "text-blue-400")}>
            Signal quality engine
          </p>
          <p className={cn("mt-1 text-sm", isLight ? "text-neutral-600" : "text-neutral-400")}>
            Real-time quality, outcome mix, and execution posture
          </p>
        </div>
        <span className={cn("font-mono text-xs", isLight ? "text-neutral-500" : "text-neutral-500")}>
          {(stats?.totalAllTime ?? 0).toLocaleString()} all-time
        </span>
      </div>
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-6">
        {tiles.map((t, i) => (
          <motion.div key={t.label} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: i * 0.05 }}>
            <AdminHeatCell isLight={isLight} label={t.label} value={t.value} intensity={t.intensity} />
          </motion.div>
        ))}
      </div>
    </div>
  );
}
