import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
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
} from "../../api/simulation";
import { AdminPageShell, AdminPanel, AdminSection } from "../../components/admin/institutional/AdminDesignSystem";
import { useUiThemeStore } from "../../state/uiTheme";
import { cn } from "../../lib/utils";

export function AdminMarketSimulationPage() {
  const isLight = useUiThemeStore((s) => s.theme === "light");
  const qc = useQueryClient();
  const [scenario, setScenario] = useState("GAP_FILL_WIN");
  const [symbol, setSymbol] = useState("SBIN");
  const [lastReport, setLastReport] = useState<SimulationHarnessReport | null>(null);

  const statusQ = useQuery({ queryKey: ["sim-runtime"], queryFn: fetchSimulationStatus });
  const scenariosQ = useQuery({ queryKey: ["sim-scenarios"], queryFn: listScenarios });
  const dashboardQ = useQuery({
    queryKey: ["sim-dashboard"],
    queryFn: () => fetchSimulationDashboard(),
    enabled: statusQ.data?.enabled === true,
  });

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
      qc.invalidateQueries({ queryKey: ["sim-dashboard"] });
    },
  });
  const packM = useMutation({
    mutationFn: runValidationPack,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["sim-dashboard"] }),
  });
  const cleanupM = useMutation({
    mutationFn: () => cleanupSimulation({ scenario }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["sim-dashboard"] }),
  });

  const enabled = statusQ.data?.enabled === true;

  return (
    <AdminPageShell
      title="Market Simulation Harness"
      subtitle="After-hours E2E validation — runtime enable required; never affects production analytics"
    >
      <AdminSection title="Runtime control">
        <AdminPanel>
          <p className={cn("text-sm", isLight ? "text-slate-600" : "text-slate-400")}>
            Status:{" "}
            <strong className={enabled ? "text-amber-500" : "text-emerald-500"}>
              {enabled ? "ENABLED" : "DISABLED"}
            </strong>
            {statusQ.data?.enabledAt && (
              <span className="ml-2 text-xs opacity-70">since {statusQ.data.enabledAt}</span>
            )}
          </p>
          <div className="mt-3 flex flex-wrap gap-2">
            <button
              type="button"
              disabled={enabled || enableM.isPending}
              className="rounded bg-amber-600 px-3 py-1.5 text-sm text-white disabled:opacity-50"
              onClick={() => enableM.mutate()}
            >
              Enable simulation
            </button>
            <button
              type="button"
              disabled={!enabled || disableM.isPending}
              className="rounded bg-slate-600 px-3 py-1.5 text-sm text-white disabled:opacity-50"
              onClick={() => disableM.mutate()}
            >
              Disable simulation
            </button>
          </div>
        </AdminPanel>
      </AdminSection>

      {enabled && (
        <>
          <AdminSection title="Run scenario">
            <AdminPanel>
              <div className="flex flex-wrap gap-3 items-end">
                <label className="text-sm">
                  Scenario
                  <select
                    className="ml-2 rounded border px-2 py-1 text-sm"
                    value={scenario}
                    onChange={(e) => setScenario(e.target.value)}
                  >
                    {(scenariosQ.data ?? []).map((s) => (
                      <option key={s} value={s}>
                        {s}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="text-sm">
                  Symbol
                  <input
                    className="ml-2 rounded border px-2 py-1 text-sm w-24"
                    value={symbol}
                    onChange={(e) => setSymbol(e.target.value.toUpperCase())}
                  />
                </label>
                <button
                  type="button"
                  className="rounded bg-indigo-600 px-3 py-1.5 text-sm text-white"
                  disabled={runM.isPending}
                  onClick={() => runM.mutate()}
                >
                  Run scenario
                </button>
                <button
                  type="button"
                  className="rounded bg-violet-700 px-3 py-1.5 text-sm text-white"
                  disabled={packM.isPending}
                  onClick={() => packM.mutate()}
                >
                  Run release validation pack
                </button>
              </div>
              {lastReport && (
                <pre className="mt-4 max-h-48 overflow-auto rounded bg-black/20 p-2 text-xs">
                  {JSON.stringify(lastReport, null, 2)}
                </pre>
              )}
              {packM.data && (
                <pre className="mt-4 max-h-48 overflow-auto rounded bg-black/20 p-2 text-xs">
                  {JSON.stringify(packM.data, null, 2)}
                </pre>
              )}
            </AdminPanel>
          </AdminSection>

          <AdminSection title="Dashboard">
            <AdminPanel>
              {dashboardQ.isLoading && <p className="text-sm opacity-70">Loading…</p>}
              {dashboardQ.data && (
                <div className="space-y-4 text-sm">
                  <div>
                    <strong>Runs</strong>
                    <pre className="mt-1 max-h-32 overflow-auto text-xs">
                      {JSON.stringify(dashboardQ.data.runs, null, 2)}
                    </pre>
                  </div>
                  <div>
                    <strong>Signals</strong>
                    <pre className="mt-1 max-h-40 overflow-auto text-xs">
                      {JSON.stringify(dashboardQ.data.signals, null, 2)}
                    </pre>
                  </div>
                  <div>
                    <strong>Aggregates</strong>
                    <pre className="mt-1 text-xs">{JSON.stringify(dashboardQ.data.aggregates, null, 2)}</pre>
                  </div>
                </div>
              )}
            </AdminPanel>
          </AdminSection>

          <AdminSection title="Cleanup">
            <AdminPanel>
              <button
                type="button"
                className="rounded bg-red-700 px-3 py-1.5 text-sm text-white"
                disabled={cleanupM.isPending}
                onClick={() => cleanupM.mutate()}
              >
                Soft-delete simulation data for scenario {scenario}
              </button>
              {cleanupM.data && (
                <pre className="mt-2 text-xs">{JSON.stringify(cleanupM.data, null, 2)}</pre>
              )}
            </AdminPanel>
          </AdminSection>
        </>
      )}
    </AdminPageShell>
  );
}
