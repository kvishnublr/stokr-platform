import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Search } from "lucide-react";
import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";
import { api, parseAxiosMessage } from "../api/client";
import { TRADER_EXECUTION_MODE_QUERY_KEY, invalidateTraderExecutionModeQueries } from "../lib/traderExecutionMode";
import { EmptyState } from "../components/ds/EmptyState";
import { GlassPanel } from "../components/ds/GlassPanel";
import { SkeletonCard } from "../components/ds/SkeletonLoader";
import { StrategyCard, type StrategyCatalogCard } from "../components/ds/StrategyCard";
import { useSessionStore } from "../state/session";
import { useUiThemeStore } from "../state/uiTheme";

type PageResponse<T> = { content: T[] };
type RuntimeRow = {
  instanceId: string;
  definitionId: string;
  strategyKey: string;
  symbol: string;
  executionMode: string;
  runtimeState: string;
  signalCount: number;
  lastSignalAt: string | null;
  health: string;
};
type InstanceRow = { id: string; definitionId: string; enabled: boolean; executionMode: string; runtimeState: string; symbol: string };
type SignalRow = { strategyName: string | null; createdAt: string | null };
type CatalogSignalStats = { strategyKey: string; signalsToday: number; lastSignalAt: string | null };

const IST_DAY_START_MS = () => {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Kolkata",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date());
  const y = parts.find((p) => p.type === "year")?.value ?? "1970";
  const m = parts.find((p) => p.type === "month")?.value ?? "01";
  const d = parts.find((p) => p.type === "day")?.value ?? "01";
  return Date.parse(`${y}-${m}-${d}T00:00:00+05:30`);
};

function isTodaySignal(createdAt: string | null | undefined): boolean {
  if (!createdAt) return false;
  const t = Date.parse(createdAt);
  return Number.isFinite(t) && t >= IST_DAY_START_MS();
}

function toSinceLabel(ts: string | null | undefined): string {
  if (!ts) return "-";
  const t = Date.parse(ts);
  if (!Number.isFinite(t)) return ts;
  const sec = Math.max(1, Math.floor((Date.now() - t) / 1000));
  if (sec < 60) return `${sec}s ago`;
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m ago`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}h ago`;
  return `${Math.floor(hr / 24)}d ago`;
}

export function StrategiesPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const token = useSessionStore((s) => s.accessToken);
  const hasRole = useSessionStore((s) => s.hasRole);
  const isAdmin = hasRole("ROLE_ADMIN");
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const [qText, setQText] = useState("");

  const catalogQuery = useQuery({
    queryKey: ["strategy-catalog"],
    queryFn: async () => (await api.get("/api/strategies/catalog?size=100")).data?.data as PageResponse<StrategyCatalogCard>,
    refetchInterval: 20_000,
  });
  const runtimeQuery = useQuery({
    queryKey: ["strategy-runtime-metrics"],
    queryFn: async () => (await api.get("/api/strategies/runtime-metrics")).data?.data as RuntimeRow[],
    refetchInterval: 15_000,
    enabled: !!token,
  });
  const instancesQuery = useQuery({
    queryKey: ["strategy-instances"],
    queryFn: async () => (await api.get("/api/strategies/instances?size=100")).data?.data?.content as InstanceRow[],
    refetchInterval: 15_000,
    enabled: !!token,
  });
  const signalsQuery = useQuery({
    queryKey: ["trader-signals-feed"],
    queryFn: async () => (await api.get("/api/trader/strategy-feed")).data?.data as SignalRow[],
    refetchInterval: 15_000,
    enabled: !!token,
  });
  const adminSignalsQuery = useQuery({
    queryKey: ["admin-signals-for-strategy-cards"],
    queryFn: async () => {
      const p = new URLSearchParams();
      p.set("page", "0");
      p.set("size", "500");
      p.set("sort", "createdAt,desc");
      const res = await api.get(`/api/admin/signals?${p.toString()}`);
      return (res.data?.data?.content ?? []) as Array<{ strategyName?: string | null; createdAt?: string | null }>;
    },
    refetchInterval: 15_000,
    enabled: !!token && isAdmin,
  });
  const modeQuery = useQuery({
    queryKey: [...TRADER_EXECUTION_MODE_QUERY_KEY],
    queryFn: async () => {
      const res = await api.get("/api/trader/me/execution-mode");
      const raw = String(res.data?.data?.executionMode ?? "PAPER").toUpperCase();
      return raw === "LIVE" ? "LIVE" : "PAPER";
    },
    enabled: !!token,
  });
  const readinessQuery = useQuery({
    queryKey: ["strategy-pipeline-status"],
    queryFn: async () => (await api.get("/api/strategies/runtime-metrics/pipeline-status")).data?.data as Record<string, any>,
    refetchInterval: 15_000,
    enabled: !!token,
  });
  const catalogSignalStatsQuery = useQuery({
    queryKey: ["strategy-catalog-signal-stats"],
    queryFn: async () => (await api.get("/api/strategies/catalog/signal-stats")).data?.data as CatalogSignalStats[],
    refetchInterval: 15_000,
    enabled: !!token,
  });

  const toggleSub = useMutation({
    mutationFn: async (definitionId: string) => api.post(`/api/strategies/catalog/${definitionId}/subscription/toggle`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["strategy-catalog"] });
      void queryClient.invalidateQueries({ queryKey: ["strategy-instances"] });
    },
    onError: (err) => toast.error(parseAxiosMessage(err)),
  });

  const patchInstance = useMutation({
    mutationFn: async (payload: { id: string; body: Record<string, unknown> }) => api.patch(`/api/strategies/instances/${payload.id}`, payload.body),
    onError: (err) => toast.error(parseAxiosMessage(err)),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["strategy-instances"] });
      void queryClient.invalidateQueries({ queryKey: ["strategy-runtime-metrics"] });
    },
  });
  const startInstance = useMutation({
    mutationFn: async (id: string) => api.post(`/api/strategies/instances/${id}/start`),
    onError: (err) => toast.error(parseAxiosMessage(err)),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ["strategy-runtime-metrics"] }),
  });
  const pauseInstance = useMutation({
    mutationFn: async (id: string) => api.post(`/api/strategies/instances/${id}/pause`),
    onError: (err) => toast.error(parseAxiosMessage(err)),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ["strategy-runtime-metrics"] }),
  });

  const busy = toggleSub.isPending || patchInstance.isPending || startInstance.isPending || pauseInstance.isPending;

  const rows = useMemo(() => {
    const runtime = runtimeQuery.data ?? [];
    const instances = instancesQuery.data ?? [];
    const sigs = signalsQuery.data ?? [];
    const sourceSignals = isAdmin
      ? (adminSignalsQuery.data ?? []).map((x) => ({ strategyName: x.strategyName ?? null, createdAt: x.createdAt ?? null }))
      : sigs;
    const sigByStrategy = new Map<string, { count: number; last: string | null }>();
    for (const s of sourceSignals) {
      const k = String(s.strategyName ?? "").trim().toUpperCase();
      if (!k || !isTodaySignal(s.createdAt)) continue;
      const prev = sigByStrategy.get(k) ?? { count: 0, last: null };
      prev.count += 1;
      if (!prev.last || (s.createdAt && Date.parse(s.createdAt) > Date.parse(prev.last))) {
        prev.last = s.createdAt;
      }
      sigByStrategy.set(k, prev);
    }
    for (const row of catalogSignalStatsQuery.data ?? []) {
      const k = String(row.strategyKey ?? "").trim().toUpperCase();
      if (!k) continue;
      const prev = sigByStrategy.get(k) ?? { count: 0, last: null };
      if (row.signalsToday > prev.count) prev.count = row.signalsToday;
      const last = row.lastSignalAt ?? null;
      if (last && (!prev.last || Date.parse(last) > Date.parse(prev.last))) prev.last = last;
      sigByStrategy.set(k, prev);
    }

    const activePipeline = readinessQuery.data?.executionPipelineActive !== false;
    const selectedMode = modeQuery.data === "LIVE" ? "LIVE" : "PAPER";

    const catalogRows = [...(catalogQuery.data?.content ?? [])].sort((a, b) => {
      if (a.code === "NSE_SPIKE_DETECTION") return -1;
      if (b.code === "NSE_SPIKE_DETECTION") return 1;
      return a.name.localeCompare(b.name);
    });

    return catalogRows.map((c) => {
      const codeKey = c.code.trim().toUpperCase();
      const inst = instances.find((i) => i.definitionId === c.id);
      const met = runtime.find((m) => m.definitionId === c.id || m.strategyKey?.toUpperCase() === codeKey);
      const sig = sigByStrategy.get(codeKey);
      const hasPlatformBinding = (c as StrategyCatalogCard & { universeGroups?: string[] }).universeGroups?.length;

      let runtimeTag: StrategyCatalogCard["runtimeTag"] = "NO_DATA";
      if (!activePipeline) runtimeTag = "BLOCKED";
      else if (inst?.runtimeState?.toUpperCase() === "RUNNING") runtimeTag = "RUNNING";
      else if (!inst && hasPlatformBinding && (sig?.count ?? 0) > 0) runtimeTag = "RUNNING";
      else if (["PAUSED", "STOPPED"].includes(inst?.runtimeState?.toUpperCase() ?? "") || c.subscriptionEnabled === false) runtimeTag = "PAUSED";
      else if (inst) runtimeTag = "PAUSED";
      else if (!inst && hasPlatformBinding) runtimeTag = "PAUSED";
      else if (met?.health === "STALE") runtimeTag = "DEGRADED";

      return {
        ...c,
        executionMode: ((inst?.executionMode || met?.executionMode || selectedMode).toUpperCase() === "LIVE" ? "LIVE" : "PAPER") as "LIVE" | "PAPER",
        runtimeTag,
        runtimeNote: !activePipeline
          ? "Execution pipeline inactive"
          : inst?.runtimeState?.toUpperCase() === "RUNNING"
            ? "Running"
            : inst?.runtimeState?.toUpperCase() === "PAUSED"
              ? "Paused"
              : !inst && hasPlatformBinding && (sig?.count ?? 0) > 0
                ? "Platform scanner active — subscribe and choose Paper or Live to trade"
                : c.subscribed
                  ? c.subscriptionEnabled
                    ? "Subscribed — click Paper or Live to start"
                    : "Subscription paused"
                  : hasPlatformBinding
                    ? "Not subscribed — scanner may still emit signals"
                    : "Not subscribed",
        signalsToday: sig?.count ?? 0,
        lastSignalAt: toSinceLabel(sig?.last ?? met?.lastSignalAt),
        lastEvaluationAt: toSinceLabel(met?.lastSignalAt),
        assignedSymbols: (c as any).universeGroups?.length ? (c as any).universeGroups : [inst?.symbol ?? met?.symbol ?? "—"],
        candleReadiness: met?.health ?? "NO_DATA",
        omsState: activePipeline ? "READY" : "BLOCKED",
      } as StrategyCatalogCard;
    });
  }, [catalogQuery.data, runtimeQuery.data, instancesQuery.data, signalsQuery.data, adminSignalsQuery.data, catalogSignalStatsQuery.data, isAdmin, readinessQuery.data, modeQuery.data]);

  const filtered = rows.filter((s) => {
    const t = qText.trim().toLowerCase();
    if (!t) return true;
    return s.code.toLowerCase().includes(t) || s.name.toLowerCase().includes(t) || (s.description ?? "").toLowerCase().includes(t);
  });

  async function ensureInstance(definitionId: string): Promise<InstanceRow | null> {
    const existing = (instancesQuery.data ?? []).find((i) => i.definitionId === definitionId);
    if (existing) return existing;
    await toggleSub.mutateAsync(definitionId);
    await queryClient.invalidateQueries({ queryKey: ["strategy-instances"] });
    const latest = ((await queryClient.fetchQuery({
      queryKey: ["strategy-instances"],
      queryFn: async () => (await api.get("/api/strategies/instances?size=100")).data?.data?.content as InstanceRow[],
    })) ?? []) as InstanceRow[];
    return latest.find((i) => i.definitionId === definitionId) ?? null;
  }

  async function onAction(strategy: StrategyCatalogCard, action: "BACKTEST" | "PAPER" | "LIVE" | "PAUSE" | "UNSUBSCRIBE") {
    if (!token || busy) return;
    if (action === "BACKTEST") {
      navigate(`/backtests/launch?strategyKey=${encodeURIComponent(strategy.code)}`);
      return;
    }
    if (action === "UNSUBSCRIBE") {
      await toggleSub.mutateAsync(strategy.id);
      return;
    }

    const inst = await ensureInstance(strategy.id);
    if (!inst) {
      toast.error("Could not resolve strategy instance.");
      return;
    }
    if (action === "PAPER" || action === "LIVE") {
      await patchInstance.mutateAsync({ id: inst.id, body: { executionMode: action } });
      const res = await api.put("/api/trader/me/execution-mode", { executionMode: action });
      const saved = String(res.data?.data?.executionMode ?? action).toUpperCase() === "LIVE" ? "LIVE" : "PAPER";
      queryClient.setQueryData([...TRADER_EXECUTION_MODE_QUERY_KEY], saved);
      invalidateTraderExecutionModeQueries(queryClient);
      await startInstance.mutateAsync(inst.id);
      return;
    }
    await pauseInstance.mutateAsync(inst.id);
  }

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className={isLight ? "text-2xl font-semibold text-foreground" : "text-2xl font-semibold text-white"}>Strategies</h1>
          <p className={isLight ? "text-sm text-muted-foreground" : "text-sm text-neutral-400"}>
            Unified strategy execution: Backtest, Paper, Live.
          </p>
        </div>
        <div className={isLight ? "rounded-lg border border-border bg-card px-3 py-1 text-xs text-muted-foreground" : "rounded-lg border border-neutral-700 px-3 py-1 text-xs text-neutral-300"}>
          {filtered.length} visible
        </div>
      </div>

      <GlassPanel variant={isLight ? "light" : "dark"} className="p-3">
        <div className={isLight ? "flex items-center gap-2 rounded-lg border border-border bg-background px-3 py-2" : "flex items-center gap-2 rounded-lg border border-neutral-800 bg-neutral-950 px-3 py-2"}>
          <Search className="h-4 w-4 text-muted-foreground" />
          <input
            value={qText}
            onChange={(e) => setQText(e.target.value)}
            placeholder="Filter by strategy or symbol"
            className={isLight ? "w-full bg-transparent text-sm text-foreground outline-none placeholder:text-muted-foreground" : "w-full bg-transparent text-sm text-white outline-none placeholder:text-neutral-500"}
          />
        </div>
      </GlassPanel>

      {!token ? (
        <GlassPanel variant={isLight ? "light" : "dark"} className={isLight ? "border border-border bg-card px-4 py-3 text-sm text-muted-foreground" : "border border-neutral-700 px-4 py-3 text-sm text-neutral-300"}>
          Sign in to manage strategy execution.
        </GlassPanel>
      ) : null}

      {catalogQuery.isLoading ? (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">{[1, 2, 3].map((i) => <SkeletonCard key={i} />)}</div>
      ) : catalogQuery.isError ? (
        <EmptyState icon={Search} title="Could not load strategies" description="Check API and refresh." />
      ) : filtered.length === 0 ? (
        <EmptyState icon={Search} title="No strategy matches" description="Adjust filter and retry." />
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {filtered.map((s, idx) => (
            <StrategyCard
              key={s.id}
              strategy={s}
              index={idx}
              variant={isLight ? "light" : "dark"}
              actionDisabled={!token}
              actionBusy={null}
              onAction={(action) => void onAction(s, action)}
            />
          ))}
        </div>
      )}
    </div>
  );
}
