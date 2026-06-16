import React from "react";
import { useQuery } from "@tanstack/react-query";
import { motion } from "framer-motion";
import { TrendingUp, TrendingDown, DollarSign } from "lucide-react";
import { fetchGlobalCapital, type StrategyCapitalSummary } from "../../api/riskDashboard";
import {
  AdminPageShell,
  AdminPanel,
  AdminSection,
  AdminSkeletonGrid,
  adminFadeUp,
} from "../../components/admin/institutional/AdminDesignSystem";
import { fmtDateTime } from "../../lib/dateUtils";
import { cn } from "../../lib/utils";
import { useUiThemeStore } from "../../state/uiTheme";

const QK = ["admin-capital"] as const;

function CapitalCard({
  label,
  value,
  icon: Icon,
  positive,
  negative,
  isLight,
  index,
}: {
  label: string;
  value: number;
  icon: React.ElementType;
  positive?: boolean;
  negative?: boolean;
  isLight: boolean;
  index: number;
}) {
  return (
    <motion.div
      variants={adminFadeUp}
      custom={index}
      whileHover={{ y: -2 }}
      className={cn(
        "flex items-center gap-3 rounded-2xl border px-4 py-3 transition-shadow hover:shadow-md",
        isLight ? "border-neutral-200 bg-white" : "border-neutral-800 bg-neutral-950/60",
      )}
    >
      <Icon
        className={`h-5 w-5 flex-shrink-0 ${positive ? "text-green-500" : negative ? "text-red-500" : "text-muted-foreground"}`}
      />
      <div>
        <div className="text-xs text-muted-foreground">{label}</div>
        <div
          className={`text-lg font-bold tabular-nums ${positive ? "text-green-500" : negative ? "text-red-500" : ""}`}
        >
          {value.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
        </div>
      </div>
    </motion.div>
  );
}

function fmt(v: number | null | undefined): string {
  if (v == null) return "—";
  return v.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function pct(utilized: number, allocated: number): number {
  if (allocated <= 0) return 0;
  return Math.min(100, (utilized / allocated) * 100);
}

export function AdminCapitalPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const { data, isLoading, dataUpdatedAt } = useQuery({
    queryKey: QK,
    queryFn: fetchGlobalCapital,
    refetchInterval: 30_000,
  });

  const updatedLabel = dataUpdatedAt ? fmtDateTime(new Date(dataUpdatedAt).toISOString()) : "—";

  return (
    <AdminPageShell
      isLight={isLight}
      eyebrow="Treasury"
      title="Capital management"
      subtitle="Global allocation, utilization, and per-strategy exposure roll-up."
      actions={
        <span className="text-xs text-muted-foreground tabular-nums">Updated {updatedLabel}</span>
      }
    >
      {isLoading ? (
        <AdminSkeletonGrid cols={4} isLight={isLight} />
      ) : !data ? null : (
        <>
          <AdminSection isLight={isLight} title="Global summary" subtitle="Platform-wide capital posture">
            <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
              <CapitalCard label="Total Allocated" value={data.totalAllocatedCapital} icon={DollarSign} isLight={isLight} index={0} />
              <CapitalCard label="Total Utilized" value={data.totalUtilizedCapital} icon={DollarSign} isLight={isLight} index={1} />
              <CapitalCard label="Available" value={data.totalAvailableCapital} icon={DollarSign} positive isLight={isLight} index={2} />
              <CapitalCard
                label="Total PnL (Realized)"
                value={data.totalRealizedPnl}
                icon={data.totalRealizedPnl >= 0 ? TrendingUp : TrendingDown}
                positive={data.totalRealizedPnl >= 0}
                negative={data.totalRealizedPnl < 0}
                isLight={isLight}
                index={3}
              />
            </div>
          </AdminSection>

          <AdminPanel
            isLight={isLight}
            title="Per-strategy breakdown"
            subtitle={`${data.strategies.length} configured strateg${data.strategies.length === 1 ? "y" : "ies"}`}
            noPadding
          >
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className={cn("bg-muted/40", isLight ? "bg-neutral-100/80" : "bg-neutral-900/60")}>
                  <tr>
                    {["Strategy", "Allocated", "Utilized", "Available", "Utilization", "Realized PnL", "Unrealized PnL", "Positions", "Status"].map((h) => (
                      <th key={h} className="px-3 py-2 text-left font-medium text-muted-foreground">
                        {h}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {data.strategies.map((row: StrategyCapitalSummary) => {
                    const utilPct = pct(row.utilizedCapital, row.allocatedCapital);
                    return (
                      <tr
                        key={row.strategyKey}
                        className={cn(
                          "border-t transition-all duration-200 hover:bg-muted/20 hover:shadow-[inset_3px_0_0_0] hover:shadow-blue-500/50",
                          isLight ? "border-neutral-200" : "border-neutral-800",
                        )}
                      >
                        <td className="px-3 py-2 font-mono text-xs">{row.strategyKey}</td>
                        <td className="px-3 py-2">{fmt(row.allocatedCapital)}</td>
                        <td className="px-3 py-2">{fmt(row.utilizedCapital)}</td>
                        <td className="px-3 py-2">{fmt(row.availableCapital)}</td>
                        <td className="min-w-[120px] px-3 py-2">
                          <div className="flex items-center gap-2">
                            <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-muted">
                              <div
                                className={`h-full rounded-full ${utilPct > 90 ? "bg-red-500" : utilPct > 70 ? "bg-amber-500" : "bg-green-500"}`}
                                style={{ width: `${utilPct}%` }}
                              />
                            </div>
                            <span className="w-10 text-right text-xs tabular-nums">{utilPct.toFixed(0)}%</span>
                          </div>
                        </td>
                        <td className={`px-3 py-2 tabular-nums ${row.realizedPnl >= 0 ? "text-green-500" : "text-red-500"}`}>
                          {fmt(row.realizedPnl)}
                        </td>
                        <td className={`px-3 py-2 tabular-nums ${row.unrealizedPnl >= 0 ? "text-green-400" : "text-red-400"}`}>
                          {fmt(row.unrealizedPnl)}
                        </td>
                        <td className="px-3 py-2">
                          {row.openPositions} / {row.maxPositions}
                        </td>
                        <td className="px-3 py-2">
                          {row.emergencyStopEnabled ? (
                            <span className="rounded bg-red-100 px-1.5 py-0.5 text-xs text-red-700 dark:bg-red-900/30 dark:text-red-400">
                              STOP
                            </span>
                          ) : row.liveEnabled ? (
                            <span className="rounded bg-green-100 px-1.5 py-0.5 text-xs text-green-700 dark:bg-green-900/30 dark:text-green-400">
                              LIVE
                            </span>
                          ) : (
                            <span className="rounded bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">PAPER</span>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                  {data.strategies.length === 0 && (
                    <tr>
                      <td colSpan={9} className="px-3 py-6 text-center text-muted-foreground">
                        No strategy configs found
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </AdminPanel>
        </>
      )}
    </AdminPageShell>
  );
}
