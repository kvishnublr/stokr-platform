import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { AlertTriangle, ShieldOff, Zap, RefreshCw, Activity } from "lucide-react";
import { fetchRiskDashboard, type StrategyRiskStateDto } from "../../api/riskDashboard";
import { patchExecutionConfig, fetchExecutionConfigs } from "../../api/executionConfig";
import { fmtDateTime } from "../../lib/dateUtils";

const QK = ["admin-risk-dashboard"] as const;
const CFG_QK = ["admin-execution-configs"] as const;

function pnlColor(pnl: number | null, limit: number | null): string {
  if (pnl == null) return "";
  if (limit != null && pnl < -limit * 0.8) return "text-red-500 font-semibold";
  if (pnl < 0) return "text-red-400";
  return "text-green-500";
}

export function AdminRiskDashboardPage() {
  const qc = useQueryClient();
  const { data, isLoading, dataUpdatedAt } = useQuery({
    queryKey: QK,
    queryFn: fetchRiskDashboard,
    refetchInterval: 30_000,
  });

  const { data: configs } = useQuery({
    queryKey: CFG_QK,
    queryFn: fetchExecutionConfigs,
  });

  const stopMutation = useMutation({
    mutationFn: ({ id, stop }: { id: string; stop: boolean }) =>
      patchExecutionConfig(id, { emergencyStopEnabled: stop }),
    onSuccess: (_, vars) => {
      toast.success(vars.stop ? "Emergency stop activated" : "Emergency stop cleared");
      qc.invalidateQueries({ queryKey: QK });
      qc.invalidateQueries({ queryKey: CFG_QK });
    },
    onError: () => toast.error("Failed to update emergency stop"),
  });

  function handleEmergencyStop(row: StrategyRiskStateDto, stop: boolean) {
    const cfg = configs?.find((c) => c.strategyKey === row.strategyKey);
    if (!cfg) { toast.error("Config not found"); return; }
    stopMutation.mutate({ id: cfg.id, stop });
  }

  if (isLoading) return <div className="p-6 text-muted-foreground">Loading risk dashboard…</div>;
  if (!data) return null;

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">Risk Dashboard</h1>
        <span className="text-xs text-muted-foreground">
          Updated {dataUpdatedAt ? fmtDateTime(new Date(dataUpdatedAt).toISOString()) : "—"}
        </span>
      </div>

      {/* Global state row */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <Pill label="Kill Switch" active={data.killSwitchActive} danger />
        <Pill label="Broker Halt" active={data.brokerHalt} danger />
        <Pill label="Live Armed" active={data.liveTradingArmed} />
        <Pill label="Recon Alerts" value={String(data.openReconciliationAlerts)} danger={data.openReconciliationAlerts > 0} />
      </div>

      {/* Today's OMS stats */}
      <div className="grid grid-cols-3 gap-4">
        <StatCard label="Today Orders" value={data.todayOrders} />
        <StatCard label="Today Fills" value={data.todayFills} />
        <StatCard label="Today Rejects" value={data.todayRejects} danger={data.todayRejects > 0} />
      </div>

      {/* Per-strategy table */}
      <div className="rounded-md border overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="bg-muted/40">
            <tr>
              {["Strategy", "Mode", "Enabled", "Live", "E-Stop", "Today PnL / Limit", "Positions", ""].map((h) => (
                <th key={h} className="text-left px-3 py-2 font-medium text-muted-foreground">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {data.strategyRiskStates.map((row) => (
              <tr key={row.strategyKey} className="border-t hover:bg-muted/20">
                <td className="px-3 py-2 font-mono text-xs">{row.strategyKey}</td>
                <td className="px-3 py-2">
                  <span className={`text-xs px-1.5 py-0.5 rounded ${
                    row.executionMode === "LIVE" ? "bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400" :
                    row.executionMode === "BOTH" ? "bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400" :
                    "bg-muted text-muted-foreground"}`}>
                    {row.executionMode}
                  </span>
                </td>
                <td className="px-3 py-2">{row.enabled ? "✓" : <span className="text-muted-foreground">—</span>}</td>
                <td className="px-3 py-2">{row.liveEnabled ? <span className="text-green-500">✓</span> : <span className="text-muted-foreground">—</span>}</td>
                <td className="px-3 py-2">
                  {row.emergencyStopEnabled
                    ? <span className="text-red-500 font-semibold">STOP</span>
                    : <span className="text-muted-foreground">—</span>}
                </td>
                <td className={`px-3 py-2 ${pnlColor(row.todayPnl, row.dailyLossLimit)}`}>
                  {row.todayPnl != null ? row.todayPnl.toFixed(2) : "—"}
                  {row.dailyLossLimit != null && <span className="text-muted-foreground text-xs"> / {row.dailyLossLimit}</span>}
                </td>
                <td className="px-3 py-2">
                  {row.openPositions} / {row.maxPositions}
                </td>
                <td className="px-3 py-2">
                  {row.emergencyStopEnabled ? (
                    <button
                      className="text-xs px-2 py-1 rounded border border-green-500 text-green-600 hover:bg-green-50 dark:hover:bg-green-900/20"
                      onClick={() => handleEmergencyStop(row, false)}
                      disabled={stopMutation.isPending}
                    >
                      Clear Stop
                    </button>
                  ) : (
                    <button
                      className="text-xs px-2 py-1 rounded border border-red-400 text-red-500 hover:bg-red-50 dark:hover:bg-red-900/20"
                      onClick={() => handleEmergencyStop(row, true)}
                      disabled={stopMutation.isPending}
                    >
                      Emergency Stop
                    </button>
                  )}
                </td>
              </tr>
            ))}
            {data.strategyRiskStates.length === 0 && (
              <tr>
                <td colSpan={8} className="px-3 py-6 text-center text-muted-foreground">No strategy configs found</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function Pill({ label, active, value, danger }: { label: string; active?: boolean; value?: string; danger?: boolean }) {
  const isOn = active || (value != null && value !== "0");
  return (
    <div className={`rounded-lg border px-4 py-3 flex items-center gap-2 ${danger && isOn ? "border-red-400 bg-red-50 dark:bg-red-900/20" : "bg-card"}`}>
      <Activity className={`h-4 w-4 ${danger && isOn ? "text-red-500" : isOn ? "text-green-500" : "text-muted-foreground"}`} />
      <div>
        <div className="text-xs text-muted-foreground">{label}</div>
        <div className={`text-sm font-semibold ${danger && isOn ? "text-red-500" : isOn ? "text-green-500" : "text-muted-foreground"}`}>
          {value ?? (active ? "ACTIVE" : "OFF")}
        </div>
      </div>
    </div>
  );
}

function StatCard({ label, value, danger }: { label: string; value: number; danger?: boolean }) {
  return (
    <div className="rounded-lg border px-4 py-3 bg-card">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className={`text-2xl font-bold ${danger && value > 0 ? "text-red-500" : ""}`}>{value}</div>
    </div>
  );
}
