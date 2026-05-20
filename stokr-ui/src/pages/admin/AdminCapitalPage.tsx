import React from "react";
import { useQuery } from "@tanstack/react-query";
import { TrendingUp, TrendingDown, DollarSign } from "lucide-react";
import { fetchGlobalCapital, type StrategyCapitalSummary } from "../../api/riskDashboard";
import { fmtDateTime } from "../../lib/dateUtils";

const QK = ["admin-capital"] as const;

function CapitalCard({
  label, value, icon: Icon, positive, negative,
}: { label: string; value: number; icon: React.ElementType; positive?: boolean; negative?: boolean }) {
  return (
    <div className="rounded-lg border px-4 py-3 bg-card flex items-center gap-3">
      <Icon className={`h-5 w-5 flex-shrink-0 ${positive ? "text-green-500" : negative ? "text-red-500" : "text-muted-foreground"}`} />
      <div>
        <div className="text-xs text-muted-foreground">{label}</div>
        <div className={`text-lg font-bold tabular-nums ${positive ? "text-green-500" : negative ? "text-red-500" : ""}`}>
          {value.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
        </div>
      </div>
    </div>
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
  const { data, isLoading, dataUpdatedAt } = useQuery({
    queryKey: QK,
    queryFn: fetchGlobalCapital,
    refetchInterval: 30_000,
  });

  if (isLoading) return <div className="p-6 text-muted-foreground">Loading capital data…</div>;
  if (!data) return null;

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">Capital Management</h1>
        <span className="text-xs text-muted-foreground">
          Updated {dataUpdatedAt ? fmtDateTime(new Date(dataUpdatedAt).toISOString()) : "—"}
        </span>
      </div>

      {/* Global header */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <CapitalCard label="Total Allocated" value={data.totalAllocatedCapital} icon={DollarSign} />
        <CapitalCard label="Total Utilized" value={data.totalUtilizedCapital} icon={DollarSign} />
        <CapitalCard label="Available" value={data.totalAvailableCapital} icon={DollarSign} positive />
        <CapitalCard
          label="Total PnL (Realized)"
          value={data.totalRealizedPnl}
          icon={data.totalRealizedPnl >= 0 ? TrendingUp : TrendingDown}
          positive={data.totalRealizedPnl >= 0}
          negative={data.totalRealizedPnl < 0}
        />
      </div>

      {/* Per-strategy table */}
      <div className="rounded-md border overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="bg-muted/40">
            <tr>
              {["Strategy", "Allocated", "Utilized", "Available", "Utilization", "Realized PnL", "Unrealized PnL", "Positions", "Status"].map((h) => (
                <th key={h} className="text-left px-3 py-2 font-medium text-muted-foreground">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {data.strategies.map((row: StrategyCapitalSummary) => {
              const utilPct = pct(row.utilizedCapital, row.allocatedCapital);
              return (
                <tr key={row.strategyKey} className="border-t hover:bg-muted/20">
                  <td className="px-3 py-2 font-mono text-xs">{row.strategyKey}</td>
                  <td className="px-3 py-2">{fmt(row.allocatedCapital)}</td>
                  <td className="px-3 py-2">{fmt(row.utilizedCapital)}</td>
                  <td className="px-3 py-2">{fmt(row.availableCapital)}</td>
                  <td className="px-3 py-2 min-w-[120px]">
                    <div className="flex items-center gap-2">
                      <div className="flex-1 h-1.5 rounded-full bg-muted overflow-hidden">
                        <div
                          className={`h-full rounded-full ${utilPct > 90 ? "bg-red-500" : utilPct > 70 ? "bg-amber-500" : "bg-green-500"}`}
                          style={{ width: `${utilPct}%` }}
                        />
                      </div>
                      <span className="text-xs tabular-nums w-10 text-right">{utilPct.toFixed(0)}%</span>
                    </div>
                  </td>
                  <td className={`px-3 py-2 tabular-nums ${row.realizedPnl >= 0 ? "text-green-500" : "text-red-500"}`}>
                    {fmt(row.realizedPnl)}
                  </td>
                  <td className={`px-3 py-2 tabular-nums ${row.unrealizedPnl >= 0 ? "text-green-400" : "text-red-400"}`}>
                    {fmt(row.unrealizedPnl)}
                  </td>
                  <td className="px-3 py-2">{row.openPositions} / {row.maxPositions}</td>
                  <td className="px-3 py-2">
                    {row.emergencyStopEnabled
                      ? <span className="text-xs bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400 px-1.5 py-0.5 rounded">STOP</span>
                      : row.liveEnabled
                        ? <span className="text-xs bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400 px-1.5 py-0.5 rounded">LIVE</span>
                        : <span className="text-xs bg-muted text-muted-foreground px-1.5 py-0.5 rounded">PAPER</span>}
                  </td>
                </tr>
              );
            })}
            {data.strategies.length === 0 && (
              <tr>
                <td colSpan={9} className="px-3 py-6 text-center text-muted-foreground">No strategy configs found</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
