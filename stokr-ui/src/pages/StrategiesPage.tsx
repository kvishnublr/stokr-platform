import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { LayoutGrid, Search } from "lucide-react";
import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";
import { api, parseAxiosMessage } from "../api/client";
import { fetchMyExecutionConfigs } from "../api/traderExecutionConfig";
import { TRADER_EXECUTION_MODE_QUERY_KEY, invalidateTraderExecutionModeQueries } from "../lib/traderExecutionMode";
import { AssetClassTabs, normalizeStrategyAssetClass, type AssetClassTabId } from "../components/ds/AssetClassTabs";
import { EmptyState } from "../components/ds/EmptyState";
import { GlassPanel } from "../components/ds/GlassPanel";
import { SkeletonCard } from "../components/ds/SkeletonLoader";
import {
  StrategyCard,
  type StrategyCatalogCard,
  type StrategyExecutionMode,
} from "../components/ds/StrategyCard";
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
type SubscriptionToggleResult = { subscribed: boolean; subscriptionEnabled: boolean };

type CardAction = "BACKTEST" | "PAUSE" | "RESUME" | "SUBSCRIBE" | "UNSUBSCRIBE";
type BusyState = { strategyId: string; action: CardAction | StrategyExecutionMode } | null;

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

function normalizeExecutionMode(raw: string | null | undefined): StrategyExecutionMode {
  const u = String(raw ?? "PAPER").trim().toUpperCase();
  if (u === "LIVE") return "LIVE";
  if (u === "BOTH") return "BOTH";
  return "PAPER";
}

export function StrategiesPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const token = useSessionStore((s) => s.accessToken);
  const hasRole = useSessionStore((s) => s.hasRole);
  const isAdmin = hasRole("ROLE_ADMIN");
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const [qText, setQText] = useState("");
  const [assetTab, setAssetTab] = useState<AssetClassTabId>("ALL");
  const [busy, setBusy] = useState<BusyState>(null);

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
  const execConfigsQuery = useQuery({
    queryKey: ["trader-exec-configs"],
    queryFn: fetchMyExecutionConfigs,
    refetchInterval: 30_000,
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
    queryFn: async () => (await api.get("/api/strategies/runtime-metrics/pipeline-status")).data?.data as Record<string, unknown>,
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
    mutationFn: async (definitionId: string) => {
      const res = await api.post(`/api/strategies/catalog/${definitionId}/subscription/toggle`);
      return res.data?.data as SubscriptionToggleResult;
    },
    onSuccess: (result, definitionId) => {
      queryClient.setQueryData<PageResponse<StrategyCatalogCard>>(["strategy-catalog"], (prev) => {
        if (!prev?.content) return prev;
        return {
          ...prev,
          content: prev.content.map((row) =>
            row.id === definitionId
              ? { ...row, subscribed: result.subscribed, subscriptionEnabled: result.subscriptionEnabled }
              : row,
          ),
        };
      });
      void queryClient.invalidateQueries({ queryKey: ["strategy-catalog"] });
      void queryClient.invalidateQueries({ queryKey: ["strategy-instances"] });
    },
    onError: (err) => toast.error(parseAxiosMessage(err)),
  });

  const patchInstance = useMutation({
    mutationFn: async (payload: { id: string; body: Record<string, unknown> }) =>
      api.patch(`/api/strategies/instances/${payload.id}`, payload.body),
    onError: (err) => toast.error(parseAxiosMessage(err)),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["strategy-instances"] });
      void queryClient.invalidateQueries({ queryKey: ["strategy-runtime-metrics"] });
      void queryClient.invalidateQueries({ queryKey: ["strategy-catalog"] });
    },
  });
  const startInstance = useMutation({
    mutationFn: async (id: string) => api.post(`/api/strategies/instances/${id}/start`),
    onError: (err) => toast.error(parseAxiosMessage(err)),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["strategy-runtime-metrics"] });
      void queryClient.invalidateQueries({ queryKey: ["strategy-catalog"] });
    },
  });
  const pauseInstance = useMutation({
    mutationFn: async (id: string) => api.post(`/api/strategies/instances/${id}/pause`),
    onError: (err) => toast.error(parseAxiosMessage(err)),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ["strategy-runtime-metrics"] }),
  });

  const actionPending =
    toggleSub.isPending || patchInstance.isPending || startInstance.isPending || pauseInstance.isPending || !!busy;

  const rows = useMemo(() => {
    const runtime = runtimeQuery.data ?? [];
    const instances = instancesQuery.data ?? [];
    const execByKey = new Map((execConfigsQuery.data ?? []).map((c) => [c.strategyKey.toUpperCase(), c]));
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

    const modeOrder = (m: string) => {
      const u = m.toUpperCase();
      if (u === "LIVE") return 0;
      if (u === "BOTH") return 1;
      return 2;
    };

    return [...(catalogQuery.data?.content ?? [])]
      .map((c) => {
      const codeKey = c.code.trim().toUpperCase();
      const inst = instances.find((i) => i.definitionId === c.id);
      const execCfg = execByKey.get(codeKey);
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

      const executionMode = normalizeExecutionMode(
        inst?.executionMode || execCfg?.executionMode || met?.executionMode || selectedMode,
      );

      return {
        ...c,
        executionMode,
        runtimeTag,
        runtimeNote: !activePipeline
          ? "Execution pipeline inactive"
          : inst?.runtimeState?.toUpperCase() === "RUNNING"
            ? `Running in ${executionMode} mode`
            : inst?.runtimeState?.toUpperCase() === "PAUSED"
              ? "Paused"
              : !inst && hasPlatformBinding && (sig?.count ?? 0) > 0
                ? "Platform scanner active — subscribe and choose execution mode"
                : c.subscriptionEnabled
                ? "Subscribed — pick Paper, Live, or Both and resume"
                : c.subscribed
                  ? "Subscription paused — click Subscribe to re-enable"
                  : hasPlatformBinding
                    ? "Not subscribed — scanner may still emit signals"
                    : "Not subscribed",
        signalsToday: sig?.count ?? 0,
        lastSignalAt: toSinceLabel(sig?.last ?? met?.lastSignalAt),
        lastEvaluationAt: toSinceLabel(met?.lastSignalAt),
        assignedSymbols: (c as StrategyCatalogCard & { universeGroups?: string[] }).universeGroups?.length
          ? (c as StrategyCatalogCard & { universeGroups?: string[] }).universeGroups
          : [inst?.symbol ?? met?.symbol ?? "—"],
        candleReadiness: met?.health ?? "NO_DATA",
        omsState: activePipeline ? "READY" : "BLOCKED",
      } as StrategyCatalogCard;
    })
      .sort((a, b) => {
        const ma = modeOrder(a.executionMode ?? "");
        const mb = modeOrder(b.executionMode ?? "");
        if (ma !== mb) return ma - mb;
        return (a.name ?? "").localeCompare(b.name ?? "");
      });
  }, [
    catalogQuery.data,
    runtimeQuery.data,
    instancesQuery.data,
    execConfigsQuery.data,
    signalsQuery.data,
    adminSignalsQuery.data,
    catalogSignalStatsQuery.data,
    isAdmin,
    readinessQuery.data,
    modeQuery.data,
  ]);

  const assetCounts = useMemo(() => {
    const counts: Partial<Record<AssetClassTabId, number>> = { ALL: rows.length };
    for (const row of rows) {
      const ac = normalizeStrategyAssetClass(row.assetClass);
      counts[ac] = (counts[ac] ?? 0) + 1;
    }
    return counts;
  }, [rows]);

  const filtered = rows.filter((s) => {
    if (assetTab !== "ALL" && normalizeStrategyAssetClass(s.assetClass) !== assetTab) return false;
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

  async function onToggleSubscription(strategy: StrategyCatalogCard) {
    if (!token || actionPending) return;
    setBusy({ strategyId: strategy.id, action: strategy.subscriptionEnabled ? "UNSUBSCRIBE" : "SUBSCRIBE" });
    try {
      await toggleSub.mutateAsync(strategy.id);
      toast.success(strategy.subscriptionEnabled ? "Unsubscribed" : "Subscribed");
    } finally {
      setBusy(null);
    }
  }

  async function onModeChange(strategy: StrategyCatalogCard, mode: StrategyExecutionMode) {
    if (!token || actionPending || !strategy.subscriptionEnabled) return;
    if (strategy.executionMode === mode) return;

    setBusy({ strategyId: strategy.id, action: mode });
    try {
      const inst = await ensureInstance(strategy.id);
      if (!inst) {
        toast.error("Could not resolve strategy instance.");
        return;
      }
      await patchInstance.mutateAsync({ id: inst.id, body: { executionMode: mode } });

      if (mode === "LIVE" || mode === "PAPER") {
        const res = await api.put("/api/trader/me/execution-mode", { executionMode: mode });
        const saved = String(res.data?.data?.executionMode ?? mode).toUpperCase() === "LIVE" ? "LIVE" : "PAPER";
        queryClient.setQueryData([...TRADER_EXECUTION_MODE_QUERY_KEY], saved);
        invalidateTraderExecutionModeQueries(queryClient);
      }

      await startInstance.mutateAsync(inst.id);
      toast.success(`Execution mode set to ${mode}`);
    } finally {
      setBusy(null);
    }
  }

  async function onAction(strategy: StrategyCatalogCard, action: CardAction) {
    if (!token || actionPending) return;

    if (action === "BACKTEST") {
      navigate(`/backtests/launch?strategyKey=${encodeURIComponent(strategy.code)}`);
      return;
    }

    if (action === "SUBSCRIBE" || action === "UNSUBSCRIBE") {
      await onToggleSubscription(strategy);
      return;
    }

    setBusy({ strategyId: strategy.id, action });
    try {
      const inst = await ensureInstance(strategy.id);
      if (!inst) {
        toast.error("Could not resolve strategy instance.");
        return;
      }
      if (action === "RESUME") {
        await startInstance.mutateAsync(inst.id);
        toast.success("Strategy resumed");
      } else if (action === "PAUSE") {
        await pauseInstance.mutateAsync(inst.id);
        toast.success("Strategy paused");
      }
    } finally {
      setBusy(null);
    }
  }

  const activeTabLabel =
    assetTab === "ALL" ? "All" : assetTab === "EQUITY" ? "Cash" : assetTab.charAt(0) + assetTab.slice(1).toLowerCase();

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <div className="mb-1 flex items-center gap-2">
            <LayoutGrid className={cnIcon(isLight)} />
            <span className={isLight ? "text-xs font-semibold uppercase tracking-wider text-muted-foreground" : "text-xs font-semibold uppercase tracking-wider text-neutral-500"}>
              Strategy catalog
            </span>
          </div>
          <h1 className={isLight ? "text-3xl font-semibold tracking-tight text-foreground" : "text-3xl font-semibold tracking-tight text-white"}>
            Strategies
          </h1>
          <p className={isLight ? "mt-1 max-w-2xl text-sm text-muted-foreground" : "mt-1 max-w-2xl text-sm text-neutral-400"}>
            Browse by asset class, subscribe, and route each strategy through Paper, Live, or Both execution.
          </p>
        </div>
        <div
          className={
            isLight
              ? "rounded-xl border border-border bg-card px-4 py-2 text-xs text-muted-foreground shadow-sm"
              : "rounded-xl border border-neutral-800 bg-neutral-950/80 px-4 py-2 text-xs text-neutral-300"
          }
        >
          <span className="font-semibold tabular-nums text-foreground">{filtered.length}</span> visible
          {assetTab !== "ALL" ? ` · ${activeTabLabel}` : ""}
        </div>
      </div>

      <GlassPanel variant={isLight ? "light" : "dark"} className="space-y-3 p-3">
        <AssetClassTabs
          active={assetTab}
          onChange={(id) => setAssetTab(id)}
          counts={assetCounts}
          variant={isLight ? "light" : "dark"}
        />
        <div
          className={
            isLight
              ? "flex items-center gap-2 rounded-xl border border-border bg-background px-3 py-2.5 shadow-sm"
              : "flex items-center gap-2 rounded-xl border border-neutral-800 bg-neutral-950 px-3 py-2.5"
          }
        >
          <Search className="h-4 w-4 text-muted-foreground" />
          <input
            value={qText}
            onChange={(e) => setQText(e.target.value)}
            placeholder="Filter by strategy name, code, or description"
            className={
              isLight
                ? "w-full bg-transparent text-sm text-foreground outline-none placeholder:text-muted-foreground"
                : "w-full bg-transparent text-sm text-white outline-none placeholder:text-neutral-500"
            }
          />
        </div>
      </GlassPanel>

      {!token ? (
        <GlassPanel
          variant={isLight ? "light" : "dark"}
          className={
            isLight
              ? "border border-border bg-card px-4 py-3 text-sm text-muted-foreground"
              : "border border-neutral-700 px-4 py-3 text-sm text-neutral-300"
          }
        >
          Sign in to manage strategy execution.
        </GlassPanel>
      ) : null}

      {catalogQuery.isLoading ? (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {[1, 2, 3, 4, 5, 6].map((i) => (
            <SkeletonCard key={i} />
          ))}
        </div>
      ) : catalogQuery.isError ? (
        <EmptyState icon={Search} title="Could not load strategies" description="Check API and refresh." />
      ) : filtered.length === 0 ? (
        <EmptyState
          icon={Search}
          title={assetTab === "ALL" ? "No strategy matches" : `No ${activeTabLabel.toLowerCase()} strategies`}
          description={assetTab === "ALL" ? "Adjust filter and retry." : "Try another asset class tab or clear the search filter."}
        />
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {filtered.map((s, idx) => (
            <StrategyCard
              key={s.id}
              strategy={s}
              index={idx}
              variant={isLight ? "light" : "dark"}
              actionDisabled={!token}
              actionBusy={busy?.strategyId === s.id ? busy.action : null}
              onModeChange={(mode) => void onModeChange(s, mode)}
              onAction={(action) => void onAction(s, action)}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function cnIcon(isLight: boolean) {
  return isLight ? "h-4 w-4 text-blue-600" : "h-4 w-4 text-blue-400";
}
