import { useMutation, useQuery } from "@tanstack/react-query";
import { motion } from "framer-motion";
import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";
import {
  enqueueReplayJob,
  fetchReplayCoverageBounds,
  fetchReplayReadiness,
  pollReplayJobUntilTerminal,
  type ExecutionRequest,
} from "../../../api/backtest";
import { api, parseAxiosMessage } from "../../../api/client";
import { fetchStrategyMetadata } from "../../../api/strategyMetadata";
import { buildStrategyParametersFromDefaults, validateClientExecution } from "../../../lib/strategyExecutionForm";
import {
  clampReplayRange,
  computePresetRange,
  formatReplayBoundsLabel,
  resolveReplayMaxEnd,
  resolveReplayMinStart,
  type DateRangePreset,
} from "../../../lib/replayDateRange";
import { cn } from "../../../lib/utils";
import { useSessionStore } from "../../../state/session";
import { useUiThemeStore } from "../../../state/uiTheme";
import { CapitalAllocationCard } from "./CapitalAllocationCard";
import { DateRangeChips } from "./DateRangeChips";
import { ExecutionModeToggle, type ExecutionModeChoice } from "./ExecutionModeToggle";
import { ReplaySummaryBar } from "./ReplaySummaryBar";
import { StrategyPreviewCard } from "./StrategyPreviewCard";

function browserTimeZone(): string {
  try {
    return "Asia/Kolkata";
  } catch {
    return "Asia/Kolkata";
  }
}

function formatInr(n: number): string {
  return new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 0 }).format(n);
}

function estimateReplaySummary(from: Date, to: Date, timeframe: string, avgTradesPerDay: number) {
  const tfMin =
    timeframe === "1m" ? 1 : timeframe === "5m" ? 5 : timeframe === "15m" ? 15 : timeframe === "1h" ? 60 : 5;
  const ms = Math.max(1, to.getTime() - from.getTime());
  const approxBars = Math.max(1, Math.floor(ms / (tfMin * 60 * 1000)));
  const days = ms / 86_400_000;
  const trades = Math.max(1, Math.round(avgTradesPerDay * Math.max(0.5, days * 0.68)));
  const seconds = Math.max(3, Math.min(120, Math.round(approxBars / 7000)));
  return { trades, seconds };
}

const PRESET_SUMMARY: Record<DateRangePreset, string> = {
  "1W": "Last 1 week",
  "15D": "Last 15 days",
  "1M": "Last 1 month",
  "3M": "Last 3 months",
  "6M": "Last 6 months",
  "1Y": "Last 1 year",
};

type Props = {
  strategyKey: string;
};

export function StrategyExecutionLauncher({ strategyKey }: Props) {
  const navigate = useNavigate();
  const accessToken = useSessionStore((s) => s.accessToken);
  const isLight = useUiThemeStore((s) => s.mode === "light");

  const [capital, setCapital] = useState(100_000);
  const [preset, setPreset] = useState<DateRangePreset>("3M");
  const [customOpen, setCustomOpen] = useState(false);
  const [{ from, to }, setRange] = useState(() => computePresetRange("3M"));
  const [executionMode, setExecutionMode] = useState<ExecutionModeChoice>("BACKTEST");

  const metaQuery = useQuery({
    queryKey: ["strategy-metadata", strategyKey],
    queryFn: () => fetchStrategyMetadata(strategyKey),
    enabled: Boolean(accessToken),
    retry: false,
  });

  const meta = metaQuery.data;
  const dd = meta?.deploymentDefaults;

  const boundsQuery = useQuery({
    queryKey: ["backtest-coverage-bounds", dd?.symbol, dd?.timeframe],
    queryFn: () => fetchReplayCoverageBounds(dd!.symbol, dd!.timeframe),
    enabled: Boolean(accessToken && dd?.symbol && dd?.timeframe),
    staleTime: 60_000,
    retry: 1,
  });

  const coverageBounds = boundsQuery.data ?? null;
  const boundsLabel = useMemo(() => formatReplayBoundsLabel(coverageBounds), [coverageBounds]);
  const minFrom = useMemo(() => resolveReplayMinStart(coverageBounds), [coverageBounds]);
  const maxTo = useMemo(() => resolveReplayMaxEnd(coverageBounds), [coverageBounds]);

  useEffect(() => {
    if (!coverageBounds) return;
    setRange((prev) => clampReplayRange(prev.from, prev.to, coverageBounds));
  }, [coverageBounds?.coveredFrom, coverageBounds?.latestCandleAt, coverageBounds?.effectiveReplayEnd]);

  const coverageQuery = useQuery({
    queryKey: ["backtest-launch-coverage-readiness", dd?.symbol, dd?.timeframe, from.toISOString(), to.toISOString()],
    queryFn: () => fetchReplayReadiness(dd!.symbol, dd!.timeframe, from.toISOString(), to.toISOString()),
    enabled: Boolean(accessToken && dd?.symbol && dd?.timeframe),
    refetchInterval: 20_000,
    retry: 1,
  });

  const opsQuery = useQuery({
    queryKey: ["backtest-launch-ops"],
    queryFn: async () => (await api.get("/api/admin/operations/snapshot")).data?.data as Record<string, any>,
    enabled: Boolean(accessToken),
    refetchInterval: 20_000,
    retry: 1,
  });

  const m = useMutation({
    mutationFn: async (body: ExecutionRequest) => {
      const jobId = await enqueueReplayJob(body);
      return pollReplayJobUntilTerminal(jobId, { intervalMs: 2000, maxWaitMs: 3 * 60 * 60 * 1000 });
    },
    onMutate: () => {
      toast.loading("Running replay (async)...", { id: "replay-job" });
    },
    onSuccess: (job) => {
      toast.dismiss("replay-job");
      if (job.status === "FAILED") {
        toast.error(job.message ?? "Replay failed");
        return;
      }
      if (job.status === "CANCELLED") {
        toast.message(job.message ?? "Replay cancelled");
        return;
      }
      if (!job.runId) {
        toast.error("Replay finished but no run id was returned.");
        return;
      }
      const bits: string[] = [];
      if (job.replayDiagnosis) bits.push(`Diagnosis: ${job.replayDiagnosis}`);
      if (job.replayCandlesExpected != null || job.replayCandlesProcessed != null) {
        bits.push(`Bars ${job.replayCandlesProcessed ?? "-"}/${job.replayCandlesExpected ?? "-"}`);
      }
      if (job.replaySignalsEmitted != null) bits.push(`Signals ${job.replaySignalsEmitted}`);
      if (job.replayExecutionEvents != null) bits.push(`Exec events ${job.replayExecutionEvents}`);
      if (job.replayDurationMs != null) bits.push(`${Math.round(job.replayDurationMs / 1000)}s`);
      toast.success("Replay completed", { description: bits.length ? bits.join("  ·  ") : undefined });
      navigate(`/backtests/${job.runId}`);
    },
    onError: (e) => {
      toast.dismiss("replay-job");
      toast.error(parseAxiosMessage(e));
    },
  });

  function handlePresetChange(p: DateRangePreset) {
    setPreset(p);
    setRange(computePresetRange(p, coverageBounds));
  }

  function handleFromChange(nextFrom: Date) {
    setRange(clampReplayRange(nextFrom, to, coverageBounds));
  }

  function handleToChange(nextTo: Date) {
    setRange(clampReplayRange(from, nextTo, coverageBounds));
  }

  const rangeSummary = useMemo(() => {
    if (customOpen) return "Custom range";
    return PRESET_SUMMARY[preset];
  }, [customOpen, preset]);

  const { trades: estTrades, seconds: estSec } = useMemo(() => {
    const tf = dd?.timeframe ?? "5m";
    const atd = meta?.previewMetrics?.avgTradesPerDay ?? 4;
    return estimateReplaySummary(from, to, tf, atd);
  }, [from, to, dd?.timeframe, meta?.previewMetrics?.avgTradesPerDay]);

  const preflightBlocker = useMemo(() => {
    if (!dd) return null;
    if (coverageQuery.isLoading || boundsQuery.isLoading) return null;
    if (coverageQuery.isError) {
      return { code: "READINESS_CHECK_FAILED", detail: parseAxiosMessage(coverageQuery.error) };
    }
    const row = coverageQuery.data;
    const brokerState = String(
      opsQuery.data?.platformMarketFeed?.vendors?.ZERODHA?.connectionState ??
        opsQuery.data?.operationalLifecycle?.platformTapeState ??
        "",
    ).toUpperCase();
    if (brokerState === "AUTH_EXPIRED" || brokerState === "TOKEN_INVALID") {
      return { code: "TOKEN_INVALID", detail: "Platform broker token invalid/expired." };
    }
    if (brokerState === "OFFLINE" || brokerState === "DISCONNECTED") {
      return { code: "PLATFORM_FEED_OFFLINE", detail: "Platform broker feed is offline." };
    }
    if (!row) {
      return { code: "NO_DATA", detail: "Historical data unavailable. Admin market backfill required." };
    }
    if (!row.ready) {
      const boundsHint = boundsLabel ? ` Available history: ${boundsLabel}.` : "";
      return {
        code: String(row.state || "BLOCKED").toUpperCase(),
        detail: (row.detail || `Replay blocked for ${dd.symbol} ${dd.timeframe}.`) + boundsHint,
      };
    }
    return null;
  }, [dd, boundsLabel, boundsQuery.isLoading, coverageQuery.data, coverageQuery.error, coverageQuery.isError, coverageQuery.isLoading, opsQuery.data]);

  function runBacktest() {
    if (!meta || !dd) {
      toast.error("Strategy metadata not loaded.");
      return;
    }
    const clamped = clampReplayRange(from, to, coverageBounds);
    if (clamped.from.getTime() !== from.getTime() || clamped.to.getTime() !== to.getTime()) {
      setRange(clamped);
    }
    const launchFrom = clamped.from;
    const launchTo = clamped.to;
    if (!(launchFrom.getTime() < launchTo.getTime())) {
      toast.error("End must be after start.");
      return;
    }
    if (!Number.isFinite(capital) || capital < 1) {
      toast.error("Capital must be at least Rs 1.");
      return;
    }
    const strategyParameters = buildStrategyParametersFromDefaults(meta);
    const cErr = validateClientExecution(meta, strategyParameters);
    if (cErr) {
      toast.error(cErr);
      return;
    }
    const tz = browserTimeZone();
    const body: ExecutionRequest = {
      strategyKey,
      symbol: dd.symbol,
      timeframe: dd.timeframe,
      executionMode: "BACKTEST",
      executionProfile: dd.executionProfile,
      capital,
      feeModel: dd.feeModel,
      slippageModel: dd.slippageModel,
      seed: null,
      range: {
        from: launchFrom.toISOString(),
        to: launchTo.toISOString(),
        timezone: tz,
      },
      strategyParameters,
    };
    m.mutate(body);
  }

  const loading = metaQuery.isLoading;
  const blocked = loading || !meta || !dd || m.isPending || Boolean(preflightBlocker);

  return (
    <div className="relative min-h-[70vh] pb-28">
      {loading ? (
        <div className="space-y-4">
          <div className={cn("h-48 animate-pulse rounded-2xl", isLight ? "bg-[#F8FAFC]" : "bg-[#111827]")} />
          <div className={cn("h-40 animate-pulse rounded-2xl", isLight ? "bg-[#F8FAFC]" : "bg-[#111827]")} />
          <div className={cn("h-36 animate-pulse rounded-2xl", isLight ? "bg-[#F8FAFC]" : "bg-[#111827]")} />
        </div>
      ) : null}

      {metaQuery.isError ? (
        <p
          className={cn(
            "rounded-xl border p-4 text-sm shadow-sm",
            isLight ? "border-rose-200 bg-rose-50 text-rose-900" : "border-rose-500/30 bg-rose-950/20 text-rose-200",
          )}
        >
          Could not load strategy metadata. {parseAxiosMessage(metaQuery.error)}
        </p>
      ) : null}

      {meta && dd ? (
        <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: 0.3 }} className="space-y-5">
          {boundsLabel ? (
            <p className={cn("text-xs", isLight ? "text-[#64748B]" : "text-[#94A3B8]")}>
              Available market history: {boundsLabel}
            </p>
          ) : null}
          {preflightBlocker ? (
            <div
              className={cn(
                "rounded-xl border p-3 text-sm",
                isLight ? "border-amber-300 bg-amber-50 text-amber-900" : "border-amber-500/40 bg-amber-950/30 text-amber-100",
              )}
            >
              <div className="font-semibold">Historical data unavailable</div>
              <div className="mt-1">Blocker: {preflightBlocker.code}</div>
              <div className="mt-1 text-xs opacity-90">{preflightBlocker.detail}</div>
            </div>
          ) : null}
          <StrategyPreviewCard meta={meta} />

          <div className="grid gap-5 lg:grid-cols-3">
            <CapitalAllocationCard value={capital} onChange={setCapital} disabled={m.isPending} className="lg:col-span-1" />
            <DateRangeChips
              preset={preset}
              onPresetChange={handlePresetChange}
              customOpen={customOpen}
              onCustomOpenChange={setCustomOpen}
              from={from}
              to={to}
              onFromChange={handleFromChange}
              onToChange={handleToChange}
              disabled={m.isPending}
              minFrom={minFrom}
              maxTo={maxTo}
              className="lg:col-span-2"
            />
          </div>

          <ExecutionModeToggle value={executionMode} onChange={setExecutionMode} disabled={m.isPending} />
        </motion.div>
      ) : null}

      {meta && dd ? (
        <ReplaySummaryBar
          capitalLabel={formatInr(capital)}
          rangeSummary={rangeSummary}
          mode={executionMode}
          estimatedTrades={estTrades}
          estimatedSeconds={estSec}
          onLaunch={runBacktest}
          disabled={blocked || executionMode !== "BACKTEST"}
          pending={m.isPending}
        />
      ) : null}
    </div>
  );
}
