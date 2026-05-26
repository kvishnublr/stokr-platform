import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import { parseAxiosMessage, api } from "../api/client";
import {
  createRuntimeBinding,
  deleteRuntimeBinding,
  fetchRuntimeBindings,
  fetchUniverseGroups,
} from "../api/strategyCatalog";
import { ADMIN_OPS_SNAPSHOT_KEY } from "../lib/adminQueryKeys";
import { fetchAdminOpsSnapshotMerged } from "../lib/fetchAdminOpsSnapshotMerged";
import { asArray, asRecord } from "../components/admin/cockpit/opsTypes";
import { useUiThemeStore } from "../state/uiTheme";
import { AdminPageShell, AdminSection } from "../components/admin/institutional/AdminDesignSystem";
import { StrategyEngineCard, StrategyControlTowerHeader, type StrategyEngineData } from "../components/admin/institutional/experience/StrategyEngineCard";
import { StrategyCorrelationPanel } from "../components/admin/institutional/experience/StrategyIntelligenceLayer";
import { extractMarketRegime } from "../lib/adminOperationalIntelligence";
import { cn } from "../lib/utils";

type StrategyRow = {
  id: string;
  code: string;
  displayName: string | null;
  description: string | null;
  enabled: boolean;
  visibleToUsers: boolean;
  riskLevel: string;
  createdAt: string;
};

type PageWrap = { content: StrategyRow[] };
type CatalogSignalStats = { strategyKey: string; signalsToday: number; lastSignalAt: string | null };

export function AdminStrategiesPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const qc = useQueryClient();

  const q = useQuery({
    queryKey: ["admin-strategies"],
    queryFn: async () => {
      const res = await api.get("/api/admin/strategies?size=50");
      return res.data?.data as PageWrap;
    },
  });

  const signalStatsQ = useQuery({
    queryKey: ["strategy-catalog-signal-stats"],
    queryFn: async () => (await api.get("/api/strategies/catalog/signal-stats")).data?.data as CatalogSignalStats[],
    refetchInterval: 20_000,
  });

  const opsQ = useQuery({
    queryKey: ADMIN_OPS_SNAPSHOT_KEY,
    queryFn: fetchAdminOpsSnapshotMerged,
    staleTime: 10_000,
  });

  const patch = useMutation({
    mutationFn: async (payload: { id: string; body: Record<string, unknown> }) => {
      await api.patch(`/api/admin/strategies/${payload.id}`, payload.body);
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["admin-strategies"] });
      void qc.invalidateQueries({ queryKey: ["strategy-catalog"] });
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const bindingsQ = useQuery({
    queryKey: ["admin-runtime-bindings"],
    queryFn: async () => fetchRuntimeBindings(0, 300),
  });

  const groupsQ = useQuery({
    queryKey: ["admin-universe-groups", ""],
    queryFn: async () => fetchUniverseGroups(undefined, 0, 300),
  });

  const addBinding = useMutation({
    mutationFn: async (payload: { strategyCatalogId: string; universeGroupId: string }) =>
      createRuntimeBinding({
        strategyCatalogId: payload.strategyCatalogId,
        universeGroupId: payload.universeGroupId,
        runtimeEnabled: true,
        maxPositions: 5,
        scanIntervalSeconds: 60,
        riskProfile: "MEDIUM",
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["admin-runtime-bindings"] });
      toast.success("Group assigned");
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const removeBinding = useMutation({
    mutationFn: async (bindingId: string) => deleteRuntimeBinding(bindingId),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["admin-runtime-bindings"] });
      toast.success("Group removed");
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const scannerRows = asArray(asRecord(opsQ.data?.scannerTelemetry)?.strategyRows) ?? [];
  const statsByKey = new Map((signalStatsQ.data ?? []).map((s) => [String(s.strategyKey).toUpperCase(), s]));

  const marketRegime = extractMarketRegime(opsQ.data);

  const engines: StrategyEngineData[] = (q.data?.content ?? []).map((s) => {
    const scan = asRecord(scannerRows.find((raw) => String(asRecord(raw)?.strategyKey ?? "").toUpperCase() === s.code.toUpperCase()));
    const stat = statsByKey.get(s.code.toUpperCase());
    const allBindings = bindingsQ.data?.content ?? [];
    const bound = allBindings.filter((b) => b.strategyCatalogId === s.id);
    return {
      id: s.id,
      code: s.code,
      displayName: s.displayName,
      enabled: s.enabled,
      visibleToUsers: s.visibleToUsers,
      riskLevel: s.riskLevel,
      signalsToday: stat?.signalsToday,
      lastSignalAt: stat?.lastSignalAt,
      runningInstances: Number(scan?.runningInstances ?? 0),
      scanFailures: Number(scan?.failures ?? scan?.scanFailures ?? 0),
      haltedReason: scan?.haltedReason ? String(scan.haltedReason) : null,
      boundGroups: bound.length,
    };
  });

  return (
    <AdminPageShell
      isLight={isLight}
      eyebrow="Strategies & signals"
      title="Strategy Control Tower"
      subtitle="Institutional strategy orchestration — DNA profiles, market fit engine, rejection waterfall, and correlation network."
      actions={<StrategyControlTowerHeader isLight={isLight} />}
    >
      <div className="mb-4 flex flex-wrap gap-2">
        <Link to="/admin/universe-groups" className={cn("rounded-lg border px-3 py-1.5 text-xs font-semibold", isLight ? "border-blue-300 bg-blue-50 text-blue-800" : "border-blue-600/40 bg-blue-500/10 text-blue-200")}>
          Universe groups
        </Link>
        <Link to="/admin/runtime-bindings" className={cn("rounded-lg border px-3 py-1.5 text-xs font-semibold", isLight ? "border-emerald-300 bg-emerald-50 text-emerald-800" : "border-emerald-600/40 bg-emerald-500/10 text-emerald-200")}>
          Runtime bindings
        </Link>
      </div>

      {q.isLoading ? (
        <div className="grid gap-4 lg:grid-cols-2">
          {[1, 2, 3, 4].map((i) => (
            <div key={i} className={cn("h-56 animate-pulse rounded-2xl", isLight ? "bg-neutral-200/70" : "bg-neutral-800/70")} />
          ))}
        </div>
      ) : q.isError ? (
        <div className="rounded-xl border border-red-300 bg-red-50 px-4 py-3 text-sm text-red-700">{parseAxiosMessage(q.error)}</div>
      ) : engines.length === 0 ? (
        <div className={cn("rounded-2xl border border-dashed px-4 py-12 text-center text-sm", isLight ? "border-neutral-300 text-neutral-500" : "border-neutral-700 text-neutral-400")}>
          No strategy definitions yet. Use Strategy Catalog to create engines.
        </div>
      ) : (
        <AdminSection isLight={isLight} title="Live strategy engines" subtitle={`${engines.filter((e) => e.enabled).length} enabled · ${engines.filter((e) => (e.signalsToday ?? 0) > 0).length} firing today`}>
          <StrategyCorrelationPanel engines={engines} isLight={isLight} />
          <div className="mt-4 grid gap-4 lg:grid-cols-2">
            {engines.map((engine) => {
              const allBindings = bindingsQ.data?.content ?? [];
              const bound = allBindings.filter((b) => b.strategyCatalogId === engine.id);
              const usedGroupIds = new Set(bound.map((b) => b.universeGroupId));
              const availableGroups = (groupsQ.data?.content ?? []).filter((g) => !usedGroupIds.has(g.id) && g.enabled);
              const selectedGroupId = availableGroups[0]?.id ?? "";

              return (
                <StrategyEngineCard
                  key={engine.id}
                  strategy={engine}
                  isLight={isLight}
                  marketRegime={marketRegime}
                  opsSnapshot={opsQ.data}
                  onToggleEnabled={() => patch.mutate({ id: engine.id, body: { enabled: !engine.enabled } })}
                  onToggleVisible={() => patch.mutate({ id: engine.id, body: { visibleToUsers: !engine.visibleToUsers } })}
                  bindingSlot={
                    <div className="space-y-2">
                      <p className={cn("text-[10px] font-bold uppercase tracking-wide", isLight ? "text-neutral-500" : "text-neutral-400")}>
                        Universe groups ({bound.length})
                      </p>
                      <div className="flex flex-wrap gap-1.5">
                        {bound.map((b) => (
                          <button
                            key={b.id}
                            type="button"
                            onClick={() => removeBinding.mutate(b.id)}
                            className="rounded-full border border-violet-400/40 bg-violet-500/10 px-2 py-0.5 text-[10px] font-semibold text-violet-300"
                          >
                            {b.groupDisplayName} ×
                          </button>
                        ))}
                      </div>
                      <div className="flex flex-wrap gap-2">
                        <select
                          defaultValue={selectedGroupId}
                          id={`group-select-${engine.id}`}
                          className={cn("min-w-40 rounded-lg border px-2 py-1 text-xs", isLight ? "border-neutral-300 bg-white" : "border-neutral-700 bg-neutral-900")}
                        >
                          {availableGroups.length === 0 ? (
                            <option value="">No groups</option>
                          ) : (
                            availableGroups.map((g) => (
                              <option key={g.id} value={g.id}>{g.displayName}</option>
                            ))
                          )}
                        </select>
                        <button
                          type="button"
                          disabled={availableGroups.length === 0}
                          onClick={() => {
                            const el = document.getElementById(`group-select-${engine.id}`) as HTMLSelectElement | null;
                            if (el?.value) addBinding.mutate({ strategyCatalogId: engine.id, universeGroupId: el.value });
                          }}
                          className="rounded-lg bg-violet-600 px-2.5 py-1 text-[11px] font-semibold text-white disabled:opacity-50"
                        >
                          Assign
                        </button>
                      </div>
                    </div>
                  }
                />
              );
            })}
          </div>
        </AdminSection>
      )}
    </AdminPageShell>
  );
}
