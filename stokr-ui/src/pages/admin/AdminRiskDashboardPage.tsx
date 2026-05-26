import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { AlertTriangle, RefreshCw } from "lucide-react";
import { fetchRiskDashboard, fetchGlobalCapital, type StrategyRiskStateDto } from "../../api/riskDashboard";
import { fetchReconciliationEvents, triggerReconciliationRun } from "../../api/reconciliation";
import { patchExecutionConfig, fetchExecutionConfigs } from "../../api/executionConfig";
import { useUiThemeStore } from "../../state/uiTheme";
import { AdminPageShell, AdminSection } from "../../components/admin/institutional/AdminDesignSystem";
import {
  BrokerDivergencePanel,
  DrawdownEnginePanel,
  KillSwitchCenterPanel,
  LiveExposureMapPanel,
  RiskHeatmapGrid,
} from "../../components/admin/institutional/experience/RiskTerminalPanels";
import { fmtDateTime } from "../../lib/dateUtils";
import { cn } from "../../lib/utils";

const QK = ["admin-risk-dashboard"] as const;
const CFG_QK = ["admin-execution-configs"] as const;

function pnlColor(pnl: number | null, limit: number | null): string {
  if (pnl == null) return "";
  if (limit != null && pnl < -limit * 0.8) return "text-red-500 font-semibold";
  if (pnl < 0) return "text-red-400";
  return "text-green-500";
}

export function AdminRiskDashboardPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const qc = useQueryClient();
  const { data, isLoading, dataUpdatedAt } = useQuery({
    queryKey: QK,
    queryFn: fetchRiskDashboard,
    refetchInterval: 30_000,
  });

  const capitalQ = useQuery({
    queryKey: ["admin-global-capital"],
    queryFn: fetchGlobalCapital,
    refetchInterval: 30_000,
  });

  const reconQ = useQuery({
    queryKey: ["admin-reconciliation-events"],
    queryFn: () => fetchReconciliationEvents("ALL", 50),
    refetchInterval: 20_000,
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

  const reconMut = useMutation({
    mutationFn: triggerReconciliationRun,
    onSuccess: () => {
      toast.success("Reconciliation run triggered");
      void qc.invalidateQueries({ queryKey: ["admin-reconciliation-events"] });
    },
    onError: () => toast.error("Reconciliation trigger failed"),
  });

  function handleEmergencyStop(row: StrategyRiskStateDto, stop: boolean) {
    const cfg = configs?.find((c) => c.strategyKey === row.strategyKey);
    if (!cfg) { toast.error("Config not found"); return; }
    stopMutation.mutate({ id: cfg.id, stop });
  }

  if (isLoading) {
    return (
      <AdminPageShell isLight={isLight} title="Risk Terminal" subtitle="Loading exposure and limits…">
        <div className="text-muted-foreground">Loading risk dashboard…</div>
      </AdminPageShell>
    );
  }
  if (!data) return null;

  return (
    <AdminPageShell
      isLight={isLight}
      eyebrow="Risk & exposure"
      title="Risk Terminal"
      subtitle="Live exposure map, drawdown engine, broker divergence, kill switch center, and risk heatmaps."
      actions={
        <div className="flex items-center gap-2">
          <span className="text-xs text-muted-foreground">
            Updated {dataUpdatedAt ? fmtDateTime(new Date(dataUpdatedAt).toISOString()) : "—"}
          </span>
          <button
            type="button"
            onClick={() => { void qc.invalidateQueries({ queryKey: QK }); void reconQ.refetch(); }}
            className={cn("rounded-lg border px-2 py-1 text-xs", isLight ? "border-neutral-300" : "border-neutral-700")}
          >
            <RefreshCw className="inline h-3.5 w-3.5" /> Refresh
          </button>
        </div>
      }
      alert={
        data.killSwitchActive || data.brokerHalt || data.openReconciliationAlerts > 0 ? (
          <div className="flex items-center gap-3 rounded-xl border border-rose-500/40 bg-rose-500/10 px-4 py-3 text-sm text-rose-100">
            <AlertTriangle className="h-5 w-5 shrink-0" />
            <span>
              {data.killSwitchActive ? "Global kill switch is ACTIVE. " : ""}
              {data.brokerHalt ? "Broker halt engaged. " : ""}
              {data.openReconciliationAlerts > 0 ? `${data.openReconciliationAlerts} recon alert(s).` : ""}
            </span>
          </div>
        ) : undefined
      }
    >
      <div className="space-y-6">
        <KillSwitchCenterPanel risk={data} isLight={isLight} onEmergencyStop={(key, stop) => {
          const row = data.strategyRiskStates.find((r) => r.strategyKey === key);
          if (row) handleEmergencyStop(row, stop);
        }} />

        <AdminSection isLight={isLight} title="Risk heatmaps" subtitle="Concentration, volatility, PnL instability, queue congestion, broker instability">
          <RiskHeatmapGrid risk={data} isLight={isLight} />
        </AdminSection>

        <div className="grid gap-4 xl:grid-cols-2">
          <LiveExposureMapPanel risk={data} capital={capitalQ.data} isLight={isLight} />
          <DrawdownEnginePanel risk={data} isLight={isLight} />
        </div>

        <BrokerDivergencePanel
          events={reconQ.data ?? []}
          isLight={isLight}
          onReconcile={() => reconMut.mutate()}
        />

        <AdminSection isLight={isLight} title="Strategy risk state" subtitle="Per-strategy limits, PnL, and emergency stops">
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
        </AdminSection>
      </div>
    </AdminPageShell>
  );
}
