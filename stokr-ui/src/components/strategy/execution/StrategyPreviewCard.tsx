import { motion } from "framer-motion";
import type { StrategyMetadataResponse } from "../../../types/strategyMetadata";
import { cn } from "../../../lib/utils";
import { useUiThemeStore } from "../../../state/uiTheme";

type Props = {
  meta: StrategyMetadataResponse;
  className?: string;
};

function pct(n: number, digits = 1): string {
  return `${n.toFixed(digits)}%`;
}

export function StrategyPreviewCard({ meta, className }: Props) {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const dd = meta.deploymentDefaults;
  const pm = meta.previewMetrics;
  const universe = dd ? `${dd.symbol}  ·  ${dd.timeframe}` : "-";
  const typeLabel = meta.category?.replace(/_/g, " ") ?? "Strategy";

  const statCell = isLight
    ? "rounded-xl border border-slate-900/[0.08] bg-[#F8FAFC] px-3 py-2.5"
    : "rounded-xl border border-[rgba(255,255,255,0.06)] bg-[#111827] px-3 py-2.5";

  const pill = isLight
    ? "rounded-md border border-slate-900/[0.1] bg-[#F8FAFC] px-2 py-0.5 font-medium text-[#475569]"
    : "rounded-md border border-[rgba(255,255,255,0.08)] bg-[#0B1220] px-2 py-0.5 font-medium text-[#CBD5E1]";

  return (
    <motion.section
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.35, ease: [0.22, 1, 0.36, 1] }}
      className={cn(
        "rounded-2xl border p-5 sm:p-6 shadow-sm",
        isLight
          ? "border-slate-900/[0.08] bg-white"
          : "border-[rgba(255,255,255,0.06)] bg-[#172033] shadow-[0_20px_50px_-24px_rgba(0,0,0,0.65)]",
        className,
      )}
    >
      <div className="flex flex-col gap-1 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <p className="text-[10px] font-semibold uppercase tracking-[0.2em] text-[#94A3B8]">Strategy</p>
          <h2
            className={cn(
              "mt-1 text-xl font-semibold tracking-tight sm:text-2xl",
              isLight ? "text-[#0F172A]" : "text-[#F8FAFC]",
            )}
          >
            {meta.displayName}
          </h2>
          <p className="mt-1 text-xs font-medium text-[#64748B]">{typeLabel}</p>
        </div>
        <div
          className={cn(
            "mt-3 rounded-full border px-3 py-1 text-[11px] font-medium sm:mt-0",
            isLight ? "border-slate-900/[0.1] bg-[#F8FAFC] text-[#475569]" : "border-[rgba(255,255,255,0.08)] bg-[#111827] text-[#94A3B8]",
          )}
        >
          Historical replay
        </div>
      </div>

      <p className={cn("mt-2 font-mono text-sm", isLight ? "text-[#475569]" : "text-[#94A3B8]")}>{universe}</p>

      {pm ? (
        <dl className="mt-5 grid grid-cols-2 gap-3 sm:grid-cols-4">
          <div className={statCell}>
            <dt className="text-[10px] font-medium uppercase tracking-wide text-[#64748B]">Win rate</dt>
            <dd className={cn("mt-0.5 text-lg font-semibold tabular-nums", isLight ? "text-[#0F172A]" : "text-[#F8FAFC]")}>
              {pct(pm.winRatePct, 0)}
            </dd>
          </div>
          <div className={statCell}>
            <dt className="text-[10px] font-medium uppercase tracking-wide text-[#64748B]">Max drawdown</dt>
            <dd className={cn("mt-0.5 text-lg font-semibold tabular-nums", isLight ? "text-[#0F172A]" : "text-[#F8FAFC]")}>
              {pct(pm.maxDrawdownPct)}
            </dd>
          </div>
          <div className={statCell}>
            <dt className="text-[10px] font-medium uppercase tracking-wide text-[#64748B]">Avg mo. return</dt>
            <dd
              className={cn(
                "mt-0.5 text-lg font-semibold tabular-nums",
                isLight ? "text-emerald-700" : "text-emerald-400/95",
              )}
            >
              +{pct(pm.avgMonthlyReturnPct)}
            </dd>
          </div>
          <div className={statCell}>
            <dt className="text-[10px] font-medium uppercase tracking-wide text-[#64748B]">Trades / day</dt>
            <dd className={cn("mt-0.5 text-lg font-semibold tabular-nums", isLight ? "text-[#0F172A]" : "text-[#F8FAFC]")}>
              {pm.avgTradesPerDay.toFixed(1)}
            </dd>
          </div>
        </dl>
      ) : null}

      {pm ? (
        <p className="mt-2 text-[10px] leading-snug text-[#64748B]">
          Figures summarize the published strategy dossier for orientation - not a forecast or offer of performance.
        </p>
      ) : null}

      <div className={cn("mt-4 flex flex-wrap items-center gap-2 text-xs", isLight ? "text-[#475569]" : "text-[#94A3B8]")}>
        {pm ? (
          <>
            <span className={pill}>Risk  ·  {pm.riskLevel}</span>
            {pm.tradeFrequency ? <span className={pill}>{pm.tradeFrequency}</span> : null}
          </>
        ) : null}
      </div>

      {meta.description ? (
        <p
          className={cn(
            "mt-4 border-t pt-4 text-sm leading-relaxed",
            isLight ? "border-slate-900/[0.08] text-[#475569]" : "border-[rgba(255,255,255,0.06)] text-[#94A3B8]",
          )}
        >
          {meta.description}
        </p>
      ) : null}
    </motion.section>
  );
}
