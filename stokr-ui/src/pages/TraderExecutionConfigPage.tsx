import { motion } from "framer-motion";
import { Activity, Clock3, RefreshCw, Search, Settings2, Sparkles, Zap } from "lucide-react";
import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { api, parseAxiosMessage } from "../api/client";
import {
  fetchMyExecutionConfigs,
  patchMyExecutionConfig,
} from "../api/traderExecutionConfig";
import type {
  TraderExecutionConfigDto,
  TraderExecutionConfigPatchRequest,
} from "../api/traderExecutionConfig";
import { EmptyState } from "../components/ds/EmptyState";
import { GlassPanel } from "../components/ds/GlassPanel";
import { RiskBadge } from "../components/ds/RiskBadge";
import { SkeletonCard } from "../components/ds/SkeletonLoader";
import { useSessionStore } from "../state/session";
import { useUiThemeStore } from "../state/uiTheme";
import { CapitalAllocationManager } from "../components/trader/CapitalAllocationManager";

type RuntimeRow = {
  definitionId: string;
  strategyKey: string;
  health: string;
  lastSignalAt: string | null;
  signalCount: number;
};
type SignalRow = { strategyName: string | null; createdAt: string | null };

function toSinceLabel(ts: string | null | undefined): string {
  if (!ts) return "—";
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

function ModeBadge({ mode }: { mode: string }) {
  const m = mode.toUpperCase();
  const color =
    m === "LIVE"
      ? "bg-emerald-500/15 text-emerald-700 border-emerald-500/30"
      : m === "BOTH"
        ? "bg-sky-500/15 text-sky-700 border-sky-500/30"
        : "bg-neutral-500/15 text-neutral-700 border-neutral-500/30";
  return (
    <span className={`inline-flex items-center rounded-md border px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide ${color}`}>
      {m}
    </span>
  );
}

function Toggle({ value, onChange, disabled }: { value: boolean; onChange: () => void; disabled?: boolean }) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onChange}
      className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors disabled:opacity-40 ${
        value ? "bg-primary" : "bg-border"
      }`}
    >
      <span
        className={`inline-block h-3 w-3 transform rounded-full bg-white transition-transform ${
          value ? "translate-x-5" : "translate-x-1"
        }`}
      />
    </button>
  );
}

function configToPatch(cfg: TraderExecutionConfigDto): TraderExecutionConfigPatchRequest {
  return {
    enabled: cfg.enabled,
    telegramEnabled: cfg.telegramEnabled,
    forceFixedQty: cfg.forceFixedQty,
    fixedQty: Number(cfg.fixedQty) || 1,
    maxPositions: cfg.maxPositions,
    dailyLossLimit: cfg.dailyLossLimit != null ? Number(cfg.dailyLossLimit) : null,
    cooldownMinutes: cfg.cooldownMinutes,
    allowPyramiding: cfg.allowPyramiding,
    emergencyStopEnabled: cfg.emergencyStopEnabled,
  };
}

const DEFAULT_PATCH: TraderExecutionConfigPatchRequest = {
  enabled: true,
  telegramEnabled: true,
  forceFixedQty: true,
  fixedQty: 1,
  maxPositions: 2,
  dailyLossLimit: null,
  cooldownMinutes: 0,
  allowPyramiding: false,
  emergencyStopEnabled: false,
};

type EnrichedConfig = TraderExecutionConfigDto & {
  signalsToday: number;
  lastSignalAt: string;
  candleReadiness: string;
  pipelineActive: boolean;
};

function StrategySettingsCard({
  cfg,
  index,
  busy,
  onSave,
  onReset,
  onSubscribe,
}: {
  cfg: EnrichedConfig;
  index: number;
  busy: boolean;
  onSave: (patch: TraderExecutionConfigPatchRequest) => void;
  onReset: () => void;
  onSubscribe: () => void;
}) {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const [draft, setDraft] = useState(() => configToPatch(cfg));

  const title = cfg.displayName?.trim() || cfg.strategyKey.replace(/_/g, " ");
  const readinessTone =
    cfg.candleReadiness === "OK" || cfg.candleReadiness === "HEALTHY"
      ? isLight
        ? "text-emerald-700 bg-emerald-50 border-emerald-200"
        : "text-emerald-300 bg-emerald-500/10 border-emerald-500/30"
      : cfg.candleReadiness === "STALE" || cfg.candleReadiness === "DEGRADED"
        ? isLight
          ? "text-amber-700 bg-amber-50 border-amber-200"
          : "text-amber-300 bg-amber-500/10 border-amber-500/30"
        : isLight
          ? "text-muted-foreground bg-muted border-border"
          : "text-neutral-400 bg-neutral-900 border-neutral-800";

  return (
    <motion.div
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: Math.min(index * 0.05, 0.35) }}
    >
      <GlassPanel variant={isLight ? "light" : "dark"} interactive className="p-4">
        <div className="mb-3 flex items-start justify-between gap-3">
          <div className="flex min-w-0 items-start gap-2.5">
            <div className={isLight ? "rounded-lg bg-blue-50 p-2 ring-1 ring-blue-200" : "rounded-lg bg-blue-500/10 p-2 ring-1 ring-blue-500/20"}>
              <Sparkles className={isLight ? "h-4 w-4 text-blue-600" : "h-4 w-4 text-blue-300"} />
            </div>
            <div className="min-w-0">
              <div className={isLight ? "truncate text-base font-semibold text-foreground" : "truncate text-base font-semibold text-white"}>
                {title}
              </div>
              <div className={isLight ? "truncate font-mono text-[11px] text-muted-foreground" : "truncate font-mono text-[11px] text-neutral-400"}>
                {cfg.strategyKey}
              </div>
            </div>
          </div>
          <div className="flex shrink-0 flex-col items-end gap-1">
            <RiskBadge level={cfg.riskLevel ?? "MEDIUM"} variant={isLight ? "light" : "dark"} />
            {cfg.isGlobalFallback ? (
              <span className={isLight ? "text-[10px] text-amber-700" : "text-[10px] text-amber-400"}>defaults</span>
            ) : null}
          </div>
        </div>

        <div className="mb-3 flex flex-wrap gap-2">
          <ModeBadge mode={cfg.executionMode} />
          <span className={`rounded-md border px-2 py-0.5 text-[10px] font-medium ${readinessTone}`}>
            Candles: {cfg.candleReadiness}
          </span>
          {!cfg.pipelineActive ? (
            <span className={isLight ? "rounded-md border border-rose-200 bg-rose-50 px-2 py-0.5 text-[10px] text-rose-700" : "rounded-md border border-rose-500/30 bg-rose-500/10 px-2 py-0.5 text-[10px] text-rose-300"}>
              Pipeline blocked
            </span>
          ) : null}
        </div>

        <div className={isLight ? "mb-4 grid grid-cols-2 gap-2 text-xs text-muted-foreground" : "mb-4 grid grid-cols-2 gap-2 text-xs text-neutral-400"}>
          <div className="flex items-center gap-1.5">
            <Zap className="h-3.5 w-3.5" />
            <span>{cfg.signalsToday} signals today</span>
          </div>
          <div className="flex items-center gap-1.5">
            <Clock3 className="h-3.5 w-3.5" />
            <span>Last {cfg.lastSignalAt}</span>
          </div>
          <div className="flex items-center gap-1.5">
            <Activity className="h-3.5 w-3.5" />
            <span>Live {cfg.liveEnabled ? "on" : "off"} · Paper {cfg.paperEnabled ? "on" : "off"}</span>
          </div>
          <div className="flex items-center gap-1.5">
            <Settings2 className="h-3.5 w-3.5" />
            <span>Qty {draft.forceFixedQty ? draft.fixedQty : "capital"}</span>
          </div>
        </div>

        <div className="space-y-2.5 border-t border-border/60 pt-3">
          {(
            [
              ["Strategy enabled", "enabled"],
              ["Telegram alerts", "telegramEnabled"],
              ["Force fixed qty", "forceFixedQty"],
              ["Emergency stop", "emergencyStopEnabled"],
            ] as [string, keyof TraderExecutionConfigPatchRequest][]
          ).map(([label, key]) => (
            <div key={key} className="flex items-center justify-between">
              <span className={isLight ? "text-sm text-foreground" : "text-sm text-neutral-200"}>{label}</span>
              <Toggle
                value={draft[key] as boolean}
                disabled={busy}
                onChange={() => setDraft((d) => ({ ...d, [key]: !d[key] }))}
              />
            </div>
          ))}

          <div className="flex items-center justify-between gap-3">
            <span className={isLight ? "text-sm text-foreground" : "text-sm text-neutral-200"}>Fixed quantity</span>
            <input
              type="number"
              min={0.01}
              step={0.01}
              disabled={busy || !draft.forceFixedQty}
              value={draft.fixedQty}
              onChange={(e) => setDraft((d) => ({ ...d, fixedQty: parseFloat(e.target.value) || 1 }))}
              className={isLight ? "w-24 rounded-md border border-border bg-background px-2 py-1 text-right text-sm" : "w-24 rounded-md border border-neutral-700 bg-neutral-950 px-2 py-1 text-right text-sm text-white"}
            />
          </div>
        </div>

        <div className="mt-4 flex flex-wrap gap-2">
          <button
            type="button"
            disabled={busy}
            onClick={() => onSave(draft)}
            className="rounded-lg bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground hover:opacity-90 disabled:opacity-50"
          >
            Save
          </button>
          <button
            type="button"
            disabled={busy}
            onClick={onReset}
            className={isLight ? "rounded-lg border border-border px-3 py-1.5 text-xs text-muted-foreground hover:bg-muted/50" : "rounded-lg border border-neutral-700 px-3 py-1.5 text-xs text-neutral-300 hover:bg-neutral-900"}
          >
            Reset defaults
          </button>
          {cfg.definitionId ? (
            <button
              type="button"
              disabled={busy}
              onClick={onSubscribe}
              className={isLight ? "rounded-lg border border-blue-200 px-3 py-1.5 text-xs text-blue-700 hover:bg-blue-50" : "rounded-lg border border-blue-500/40 px-3 py-1.5 text-xs text-blue-300 hover:bg-blue-500/10"}
            >
              Subscribe
            </button>
          ) : null}
        </div>
      </GlassPanel>
    </motion.div>
  );
}

export function TraderExecutionConfigPage() {
  const queryClient = useQueryClient();
  const token = useSessionStore((s) => s.accessToken);
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const [searchKey, setSearchKey] = useState("");

  const configs = useQuery({
    queryKey: ["trader-exec-configs"],
    queryFn: fetchMyExecutionConfigs,
    refetchInterval: 30_000,
    enabled: !!token,
  });

  const runtimeQuery = useQuery({
    queryKey: ["strategy-runtime-metrics-settings"],
    queryFn: async () => (await api.get("/api/strategies/runtime-metrics")).data?.data as RuntimeRow[],
    refetchInterval: 15_000,
    enabled: !!token,
  });

  const signalsQuery = useQuery({
    queryKey: ["trader-signals-feed-settings"],
    queryFn: async () => (await api.get("/api/trader/strategy-feed")).data?.data as SignalRow[],
    refetchInterval: 15_000,
    enabled: !!token,
  });

  const readinessQuery = useQuery({
    queryKey: ["strategy-pipeline-status-settings"],
    queryFn: async () => (await api.get("/api/strategies/runtime-metrics/pipeline-status")).data?.data as Record<string, unknown>,
    refetchInterval: 15_000,
    enabled: !!token,
  });

  const saveMut = useMutation({
    mutationFn: async (payload: { strategyKey: string; body: TraderExecutionConfigPatchRequest }) =>
      patchMyExecutionConfig(payload.strategyKey, payload.body),
    onSuccess: () => {
      toast.success("Strategy settings saved");
      void queryClient.invalidateQueries({ queryKey: ["trader-exec-configs"] });
    },
    onError: (err) => toast.error(parseAxiosMessage(err)),
  });

  const subscribeMut = useMutation({
    mutationFn: async (definitionId: string) => api.post(`/api/strategies/catalog/${definitionId}/subscription/toggle`),
    onSuccess: () => toast.success("Subscription updated"),
    onError: (err) => toast.error(parseAxiosMessage(err)),
  });

  const busy = saveMut.isPending || subscribeMut.isPending;

  const enriched = useMemo((): EnrichedConfig[] => {
    const runtime = runtimeQuery.data ?? [];
    const sigs = signalsQuery.data ?? [];
    const pipelineActive = readinessQuery.data?.executionPipelineActive !== false;
    const sigByStrategy = new Map<string, { count: number; last: string | null }>();
    for (const s of sigs) {
      const k = String(s.strategyName ?? "").trim();
      if (!k) continue;
      const prev = sigByStrategy.get(k) ?? { count: 0, last: null };
      prev.count += 1;
      if (!prev.last || (s.createdAt && Date.parse(s.createdAt) > Date.parse(prev.last))) {
        prev.last = s.createdAt;
      }
      sigByStrategy.set(k, prev);
    }

    return (configs.data ?? []).map((cfg) => {
      const met = runtime.find((m) => m.strategyKey === cfg.strategyKey || m.definitionId === cfg.definitionId);
      const sig = sigByStrategy.get(cfg.strategyKey);
      return {
        ...cfg,
        signalsToday: sig?.count ?? met?.signalCount ?? 0,
        lastSignalAt: toSinceLabel(sig?.last ?? met?.lastSignalAt),
        candleReadiness: met?.health ?? "NO_DATA",
        pipelineActive,
      };
    });
  }, [configs.data, runtimeQuery.data, signalsQuery.data, readinessQuery.data]);

  const rows = enriched.filter((c) => {
    const q = searchKey.trim().toLowerCase();
    if (!q) return true;
    return (
      c.strategyKey.toLowerCase().includes(q) ||
      (c.displayName ?? "").toLowerCase().includes(q)
    );
  });

  return (
    <div className="flex h-full flex-col">
      <div className="border-b border-border px-6 py-5">
        <h1 className={isLight ? "text-xl font-semibold text-foreground" : "text-xl font-semibold text-white"}>
          ⚙️ Trader Execution & Capital Settings
        </h1>
        <p className={isLight ? "mt-0.5 text-sm text-muted-foreground" : "mt-0.5 text-sm text-neutral-400"}>
          Configure capital allocation across asset classes, set daily loss limits, and manage per-strategy execution preferences.
        </p>
      </div>

      <div className="flex-1 space-y-4 overflow-auto p-6">
        {/* Capital Allocation Manager */}
        <CapitalAllocationManager />

        <GlassPanel variant={isLight ? "light" : "dark"} className="p-3">
          <div className={isLight ? "flex items-center gap-2 rounded-lg border border-border bg-background px-3 py-2" : "flex items-center gap-2 rounded-lg border border-neutral-800 bg-neutral-950 px-3 py-2"}>
            <Search className="h-4 w-4 text-muted-foreground" />
            <input
              type="text"
              placeholder="Search strategy…"
              value={searchKey}
              onChange={(e) => setSearchKey(e.target.value)}
              className={isLight ? "w-full bg-transparent text-sm text-foreground outline-none placeholder:text-muted-foreground" : "w-full bg-transparent text-sm text-white outline-none placeholder:text-neutral-500"}
            />
            <button
              type="button"
              onClick={() => void queryClient.invalidateQueries({ queryKey: ["trader-exec-configs"] })}
              className={isLight ? "rounded-md p-1 text-muted-foreground hover:bg-muted" : "rounded-md p-1 text-neutral-400 hover:bg-neutral-800"}
              title="Refresh"
            >
              <RefreshCw className="h-4 w-4" />
            </button>
          </div>
        </GlassPanel>

        <div className="flex items-center justify-between">
          <span className={isLight ? "text-xs text-muted-foreground" : "text-xs text-neutral-400"}>
            {rows.length} strateg{rows.length !== 1 ? "ies" : "y"}
            {searchKey && configs.data && rows.length < configs.data.length ? ` (filtered from ${configs.data.length})` : ""}
          </span>
        </div>

        {!token ? (
          <GlassPanel variant={isLight ? "light" : "dark"} className="p-6 text-center text-sm text-muted-foreground">
            Sign in to manage strategy settings.
          </GlassPanel>
        ) : configs.isLoading ? (
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
            {[1, 2, 3].map((i) => (
              <SkeletonCard key={i} />
            ))}
          </div>
        ) : configs.isError ? (
          <EmptyState icon={Search} title="Could not load settings" description="Check API connectivity and refresh." />
        ) : rows.length === 0 ? (
          <EmptyState
            icon={Settings2}
            title={searchKey ? "No matching strategies" : "No strategies in catalog"}
            description={
              searchKey
                ? `Nothing matches "${searchKey}".`
                : "Enable strategies in the admin catalog (e.g. NSE_SPIKE_DETECTION) and refresh."
            }
          />
        ) : (
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
            {rows.map((cfg, idx) => (
              <StrategySettingsCard
                key={cfg.strategyKey}
                cfg={cfg}
                index={idx}
                busy={busy}
                onSave={(body) => saveMut.mutate({ strategyKey: cfg.strategyKey, body })}
                onReset={() => saveMut.mutate({ strategyKey: cfg.strategyKey, body: DEFAULT_PATCH })}
                onSubscribe={() => {
                  if (!cfg.definitionId) return;
                  subscribeMut.mutate(cfg.definitionId);
                }}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
