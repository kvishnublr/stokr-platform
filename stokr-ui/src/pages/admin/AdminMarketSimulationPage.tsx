import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2, Play, ShieldCheck, Trash2 } from "lucide-react";
import {
  cleanupSimulation,
  disableSimulationRuntime,
  enableSimulationRuntime,
  fetchSimulationDashboard,
  fetchSimulationStatus,
  listScenarios,
  runSimulationScenario,
  runValidationPack,
  type SimulationHarnessReport,
  type ValidationPackReport,
} from "../../api/simulation";
import {
  SimulationDashboardPanel,
  SimulationRunResultCard,
  ValidationPackResults,
} from "../../components/admin/simulation/SimulationHarnessViews";
import { AdminPageShell, AdminPanel, AdminSection, AdminStatusChip } from "../../components/admin/institutional/AdminDesignSystem";
import { fmtDateTime } from "../../lib/dateUtils";
import { useUiThemeStore } from "../../state/uiTheme";
import { cn } from "../../lib/utils";

export function AdminMarketSimulationPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const qc = useQueryClient();
  const [scenario, setScenario] = useState("GAP_FILL_WIN");
  const [symbol, setSymbol] = useState("SBIN");
  const [lastReport, setLastReport] = useState<SimulationHarnessReport | null>(null);
  const [packReport, setPackReport] = useState<ValidationPackReport | null>(null);
  const [dashboardRunId, setDashboardRunId] = useState<string | undefined>();

  const statusQ = useQuery({ queryKey: ["sim-runtime"], queryFn: fetchSimulationStatus });
  const scenariosQ = useQuery({ queryKey: ["sim-scenarios"], queryFn: listScenarios });
  const dashboardQ = useQuery({
    queryKey: ["sim-dashboard", dashboardRunId ?? "all"],
    queryFn: () => fetchSimulationDashboard(dashboardRunId),
    enabled: statusQ.data?.enabled === true,
  });

  const enabled = statusQ.data?.enabled === true;

  const matchedSignal = lastReport?.signalId
    ? dashboardQ.data?.signals.find((s) => s.signalId === lastReport.signalId)
    : dashboardQ.data?.signals[0];

  const focusedRun = dashboardRunId
    ? dashboardQ.data?.runs.find((r) => r.runId === dashboardRunId)
    : undefined;

  const enableM = useMutation({
    mutationFn: enableSimulationRuntime,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["sim-runtime"] }),
  });
  const disableM = useMutation({
    mutationFn: disableSimulationRuntime,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["sim-runtime"] }),
  });
  const runM = useMutation({
    mutationFn: () => runSimulationScenario({ scenario, symbol, sessionBars: 120 }),
    onSuccess: (r) => {
      setLastReport(r);
      setPackReport(null);
      setDashboardRunId(r.simulationRunId);
      qc.invalidateQueries({ queryKey: ["sim-dashboard", r.simulationRunId] });
    },
  });
  const packM = useMutation({
    mutationFn: runValidationPack,
    onSuccess: (r) => {
      setPackReport(r);
      setLastReport(null);
      qc.invalidateQueries({ queryKey: ["sim-dashboard"] });
    },
  });
  const cleanupM = useMutation({
    mutationFn: () => cleanupSimulation({ scenario }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["sim-dashboard"] });
      setLastReport(null);
      setDashboardRunId(undefined);
    },
  });

  const inputClass = cn(
    "rounded-lg border px-2.5 py-1.5 text-sm outline-none transition focus:ring-1",
    isLight
      ? "border-neutral-300 bg-white text-neutral-900 focus:border-indigo-400 focus:ring-indigo-200"
      : "border-neutral-700 bg-neutral-900/60 text-neutral-100 focus:border-indigo-500 focus:ring-indigo-500/30",
  );

  const btnPrimary = "inline-flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3 py-1.5 text-sm font-medium text-white transition hover:bg-indigo-500 disabled:opacity-50";
  const btnWarn = "inline-flex items-center gap-1.5 rounded-lg bg-amber-600 px-3 py-1.5 text-sm font-medium text-white transition hover:bg-amber-500 disabled:opacity-50";
  const btnSecondary = cn(
    "inline-flex items-center gap-1.5 rounded-lg border px-3 py-1.5 text-sm font-medium transition disabled:opacity-50",
    isLight ? "border-neutral-300 bg-white text-neutral-800 hover:bg-neutral-50" : "border-neutral-700 bg-neutral-900/60 text-neutral-200 hover:bg-neutral-800",
  );

  return (
    <AdminPageShell
      isLight={isLight}
      title="Market Simulation Harness"
      subtitle="After-hours E2E validation — runtime enable required; never affects production analytics"
    >
      <AdminSection isLight={isLight} title="Runtime control">
        <AdminPanel isLight={isLight}>
          <div className="flex flex-wrap items-center gap-3">
            <AdminStatusChip tone={enabled ? "warn" : "success"} isLight={isLight}>
              {enabled ? "ENABLED" : "DISABLED"}
            </AdminStatusChip>
            {statusQ.data?.enabledAt && (
              <span className={cn("text-xs", isLight ? "text-neutral-500" : "text-neutral-400")}>
                since {fmtDateTime(statusQ.data.enabledAt)}
              </span>
            )}
          </div>
          <div className="mt-3 flex flex-wrap gap-2">
            <button
              type="button"
              disabled={enabled || enableM.isPending}
              className={btnWarn}
              onClick={() => enableM.mutate()}
            >
              Enable simulation
            </button>
            <button
              type="button"
              disabled={!enabled || disableM.isPending}
              className={btnSecondary}
              onClick={() => disableM.mutate()}
            >
              Disable simulation
            </button>
          </div>
        </AdminPanel>
      </AdminSection>

      {enabled && (
        <>
          <AdminSection
            isLight={isLight}
            title="Run scenario"
            subtitle="Single scenario E2E with pictorial pipeline trace"
          >
            <AdminPanel isLight={isLight}>
              <div className="flex flex-wrap items-end gap-3">
                <label className={cn("text-sm", isLight ? "text-neutral-700" : "text-neutral-300")}>
                  <span className="mb-1 block text-[10px] font-semibold uppercase tracking-wide text-neutral-500">Scenario</span>
                  <select className={inputClass} value={scenario} onChange={(e) => setScenario(e.target.value)}>
                    {(scenariosQ.data ?? []).map((s) => (
                      <option key={s} value={s}>
                        {s}
                      </option>
                    ))}
                  </select>
                </label>
                <label className={cn("text-sm", isLight ? "text-neutral-700" : "text-neutral-300")}>
                  <span className="mb-1 block text-[10px] font-semibold uppercase tracking-wide text-neutral-500">Symbol</span>
                  <input
                    className={cn(inputClass, "w-28 font-mono uppercase")}
                    value={symbol}
                    onChange={(e) => setSymbol(e.target.value.toUpperCase())}
                  />
                </label>
                <button type="button" className={btnPrimary} disabled={runM.isPending} onClick={() => runM.mutate()}>
                  {runM.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}
                  Run scenario
                </button>
                <button type="button" className={btnSecondary} disabled={packM.isPending} onClick={() => packM.mutate()}>
                  {packM.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <ShieldCheck className="h-4 w-4" />}
                  Run release validation pack
                </button>
              </div>

              {runM.isError && (
                <p className="mt-3 text-sm text-rose-500">Run failed — check server logs.</p>
              )}

              {lastReport && (
                <SimulationRunResultCard
                  report={lastReport}
                  isLight={isLight}
                  confidenceScore={matchedSignal?.confidence ?? null}
                  confidenceVersion={matchedSignal?.confidenceVersion ?? null}
                />
              )}

              {packReport && <ValidationPackResults report={packReport} isLight={isLight} />}
            </AdminPanel>
          </AdminSection>

          <AdminSection
            isLight={isLight}
            title="Dashboard"
            subtitle="Run history and signal outcomes"
            action={
              dashboardRunId ? (
                <button
                  type="button"
                  className={cn("text-xs underline-offset-2 hover:underline", isLight ? "text-blue-700" : "text-blue-400")}
                  onClick={() => setDashboardRunId(undefined)}
                >
                  Show all runs
                </button>
              ) : undefined
            }
          >
            <SimulationDashboardPanel
              runs={dashboardQ.data?.runs ?? []}
              signals={dashboardQ.data?.signals ?? []}
              aggregates={dashboardQ.data?.aggregates ?? {}}
              orderCount={focusedRun?.orderCount}
              isLight={isLight}
              isLoading={dashboardQ.isLoading}
              focusedRunId={dashboardRunId}
            />
          </AdminSection>

          <AdminSection isLight={isLight} title="Cleanup">
            <AdminPanel isLight={isLight}>
              <button
                type="button"
                className="inline-flex items-center gap-1.5 rounded-lg bg-rose-700 px-3 py-1.5 text-sm font-medium text-white hover:bg-rose-600 disabled:opacity-50"
                disabled={cleanupM.isPending}
                onClick={() => cleanupM.mutate()}
              >
                {cleanupM.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Trash2 className="h-4 w-4" />}
                Soft-delete simulation data for scenario {scenario}
              </button>
              {cleanupM.data && (
                <p className={cn("mt-2 text-xs", isLight ? "text-neutral-600" : "text-neutral-400")}>
                  Cleanup completed
                </p>
              )}
            </AdminPanel>
          </AdminSection>
        </>
      )}
    </AdminPageShell>
  );
}
