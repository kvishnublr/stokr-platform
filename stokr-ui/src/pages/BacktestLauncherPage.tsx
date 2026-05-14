import { useMutation, useQuery } from "@tanstack/react-query";
import { Calendar } from "lucide-react";
import { useMemo, useRef, useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";
import { fetchStrategyMetadata } from "../api/strategyMetadata";
import { launchReplay, type ExecutionRequest } from "../api/backtest";
import { parseAxiosMessage } from "../api/client";
import { DynamicStrategyFields } from "../components/strategy/DynamicStrategyFields";
import { collectStrategyParameters, validateClientExecution } from "../lib/strategyExecutionForm";
import { useSessionStore } from "../state/session";

const MEAN_REVERSION_KEY = "MEAN_REVERSION_RANGE_FADE";

const DATETIME_LOCAL_CLASS =
  "relative z-0 w-full rounded-lg border border-neutral-800 bg-neutral-950 px-3 py-2 pr-11 text-sm text-white outline-none focus:border-neutral-600 " +
  "[color-scheme:dark] " +
  "[&::-webkit-calendar-picker-indicator]:cursor-pointer [&::-webkit-calendar-picker-indicator]:opacity-90 [&::-webkit-calendar-picker-indicator]:invert";

function openDatetimePicker(input: HTMLInputElement | null) {
  if (!input) return;
  try {
    input.showPicker?.();
  } catch {
    input.focus();
  }
}

function browserTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
  } catch {
    return "UTC";
  }
}

export function BacktestLauncherPage() {
  const navigate = useNavigate();
  const accessToken = useSessionStore((s) => s.accessToken);
  const startInputRef = useRef<HTMLInputElement>(null);
  const endInputRef = useRef<HTMLInputElement>(null);
  const [clientErrors, setClientErrors] = useState<string | null>(null);

  const metaQuery = useQuery({
    queryKey: ["strategy-metadata", MEAN_REVERSION_KEY],
    queryFn: () => fetchStrategyMetadata(MEAN_REVERSION_KEY),
    enabled: Boolean(accessToken),
    retry: false,
  });

  const feeOptions = useMemo(() => metaQuery.data?.allowedFeeModels ?? ["NONE", "PERCENT_2_BPS", "PERCENT_5_BPS"], [metaQuery.data]);
  const slipOptions = useMemo(
    () => metaQuery.data?.allowedSlippageModels ?? ["NONE", "SPREAD_PROXY", "VOL_SCALED"],
    [metaQuery.data],
  );
  const profileOptions = useMemo(
    () =>
      metaQuery.data?.allowedExecutionProfiles ?? [
        "SIMULATED_DEFAULT",
        "SIMULATED_HIGH_LATENCY",
        "REPLAY_RAW",
        "CONSERVATIVE",
        "BALANCED",
        "AGGRESSIVE",
      ],
    [metaQuery.data],
  );
  const timeframeOptions = useMemo(() => metaQuery.data?.allowedTimeframes ?? ["1m", "5m", "15m", "1h"], [metaQuery.data]);

  const m = useMutation({
    mutationFn: async (body: ExecutionRequest) => launchReplay(body),
    onSuccess: (res) => {
      toast.success("Replay completed", {
        description: res.correlationId ? `Correlation ${res.correlationId}` : undefined,
      });
      navigate(`/backtests/${res.outcome.runId}`);
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setClientErrors(null);
    const form = e.currentTarget;
    const fd = new FormData(form);
    const meta = metaQuery.data;
    if (!meta) {
      toast.error("Strategy metadata not loaded yet.");
      return;
    }

    const symbol = String(fd.get("symbol") || "").trim().toUpperCase();
    const timeframe = String(fd.get("timeframe") || timeframeOptions[0] || "5m");
    const capital = Number(fd.get("capital"));
    const executionProfile = String(fd.get("executionProfile") || profileOptions[0]);
    const feeModel = String(fd.get("feeModel") || feeOptions[0]);
    const slippageModel = String(fd.get("slippageModel") || slipOptions[0]);
    const seedRaw = fd.get("seed");
    const seed = seedRaw === null || String(seedRaw).trim() === "" ? null : Number(seedRaw);
    const startLocal = String(fd.get("start") || "");
    const endLocal = String(fd.get("end") || "");

    if (!symbol || !startLocal || !endLocal) {
      setClientErrors("Symbol and date range are required.");
      return;
    }
    if (!Number.isFinite(capital) || capital < 1) {
      setClientErrors("Capital must be a number ≥ 1.");
      return;
    }

    const strategyParameters = collectStrategyParameters(form, meta.parameters);
    const cErr = validateClientExecution(meta, strategyParameters);
    if (cErr) {
      setClientErrors(cErr);
      return;
    }

    const tz = browserTimeZone();
    const body: ExecutionRequest = {
      strategyKey: MEAN_REVERSION_KEY,
      symbol,
      timeframe,
      executionMode: "BACKTEST",
      executionProfile,
      capital,
      feeModel,
      slippageModel,
      seed: Number.isFinite(seed as number) ? (seed as number) : null,
      range: {
        from: new Date(startLocal).toISOString(),
        to: new Date(endLocal).toISOString(),
        timezone: tz,
      },
      strategyParameters,
    };
    m.mutate(body);
  }

  return (
    <form onSubmit={onSubmit} className="max-w-2xl space-y-6">
      <div className="rounded-2xl border border-neutral-800 bg-neutral-950/60 p-6">
        <h2 className="mb-1 text-sm font-semibold text-neutral-200">{metaQuery.data?.displayName ?? "Mean reversion"}</h2>
        <p className="mb-4 text-xs text-neutral-500">
          Unified execution envelope (PR-2). Only BACKTEST synchronous replay is enabled. Metadata schema v
          {metaQuery.data?.schemaVersion ?? "—"}.
        </p>

        <div className="grid gap-4 sm:grid-cols-2">
          <label className="block text-sm">
            <span className="text-neutral-400">Symbol</span>
            <input
              name="symbol"
              defaultValue="NIFTY 50"
              className="mt-1 w-full rounded-lg border border-neutral-800 bg-neutral-950 px-3 py-2 font-mono text-sm text-white outline-none focus:border-neutral-600"
              required
            />
          </label>
          <label className="block text-sm">
            <span className="text-neutral-400">Capital (notional)</span>
            <input
              name="capital"
              type="number"
              defaultValue={100000}
              min={1}
              step={100}
              className="mt-1 w-full rounded-lg border border-neutral-800 bg-neutral-950 px-3 py-2 font-mono text-sm text-white outline-none focus:border-neutral-600"
              required
            />
          </label>
          <label className="block text-sm">
            <span className="text-neutral-400">Timeframe</span>
            <select
              name="timeframe"
              defaultValue="5m"
              className="mt-1 w-full rounded-lg border border-neutral-800 bg-neutral-950 px-3 py-2 text-sm text-white outline-none focus:border-neutral-600"
            >
              {timeframeOptions.map((tf) => (
                <option key={tf} value={tf}>
                  {tf}
                </option>
              ))}
            </select>
          </label>
          <label className="block text-sm">
            <span className="text-neutral-400">Execution mode</span>
            <input
              name="executionModeDisplay"
              readOnly
              value="BACKTEST"
              className="mt-1 w-full rounded-lg border border-neutral-800 bg-neutral-900 px-3 py-2 text-sm text-neutral-400 outline-none"
            />
          </label>
          <label className="block text-sm">
            <span className="text-neutral-400">Execution profile</span>
            <select
              name="executionProfile"
              defaultValue="SIMULATED_DEFAULT"
              className="mt-1 w-full rounded-lg border border-neutral-800 bg-neutral-950 px-3 py-2 text-sm text-white outline-none focus:border-neutral-600"
            >
              {profileOptions.map((p) => (
                <option key={p} value={p}>
                  {p}
                </option>
              ))}
            </select>
          </label>
          <label className="block text-sm">
            <span className="text-neutral-400">Fee model</span>
            <select name="feeModel" defaultValue="PERCENT_2_BPS" className="mt-1 w-full rounded-lg border border-neutral-800 bg-neutral-950 px-3 py-2 text-sm text-white outline-none focus:border-neutral-600">
              {feeOptions.map((f) => (
                <option key={f} value={f}>
                  {f}
                </option>
              ))}
            </select>
          </label>
          <label className="block text-sm">
            <span className="text-neutral-400">Slippage model</span>
            <select
              name="slippageModel"
              defaultValue="SPREAD_PROXY"
              className="mt-1 w-full rounded-lg border border-neutral-800 bg-neutral-950 px-3 py-2 text-sm text-white outline-none focus:border-neutral-600"
            >
              {slipOptions.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </label>
          <label className="block text-sm">
            <span className="text-neutral-400">Seed (optional)</span>
            <input
              name="seed"
              type="number"
              className="mt-1 w-full rounded-lg border border-neutral-800 bg-neutral-950 px-3 py-2 font-mono text-sm text-white outline-none focus:border-neutral-600"
            />
          </label>
          <label className="block text-sm">
            <span className="text-neutral-400">Range start (local)</span>
            <div className="relative mt-1">
              <input ref={startInputRef} name="start" type="datetime-local" className={DATETIME_LOCAL_CLASS} required />
              <button
                type="button"
                className="absolute right-1.5 top-1/2 z-10 inline-flex h-8 w-8 -translate-y-1/2 items-center justify-center rounded-md text-neutral-400 hover:bg-neutral-800 hover:text-white"
                aria-label="Open calendar for range start"
                onClick={() => openDatetimePicker(startInputRef.current)}
              >
                <Calendar className="h-4 w-4" />
              </button>
            </div>
          </label>
          <label className="block text-sm">
            <span className="text-neutral-400">Range end (local)</span>
            <div className="relative mt-1">
              <input ref={endInputRef} name="end" type="datetime-local" className={DATETIME_LOCAL_CLASS} required />
              <button
                type="button"
                className="absolute right-1.5 top-1/2 z-10 inline-flex h-8 w-8 -translate-y-1/2 items-center justify-center rounded-md text-neutral-400 hover:bg-neutral-800 hover:text-white"
                aria-label="Open calendar for range end"
                onClick={() => openDatetimePicker(endInputRef.current)}
              >
                <Calendar className="h-4 w-4" />
              </button>
            </div>
          </label>
        </div>

        {metaQuery.isLoading ? <p className="mt-4 text-xs text-neutral-500">Loading strategy metadata…</p> : null}
        {metaQuery.isError ? (
          <p className="mt-4 text-xs text-rose-400">Could not load strategy metadata. Sign in and ensure migrations V15 are applied.</p>
        ) : null}
        {metaQuery.isSuccess ? <DynamicStrategyFields parameters={metaQuery.data.parameters} disabled={m.isPending} className="mt-6" /> : null}

        {clientErrors ? <p className="mt-4 text-sm text-rose-400">{clientErrors}</p> : null}

        <button
          type="submit"
          disabled={m.isPending || metaQuery.isLoading || !metaQuery.isSuccess}
          className="mt-6 w-full rounded-xl bg-blue-600 py-3 text-sm font-semibold text-white hover:bg-blue-500 disabled:opacity-50"
        >
          {m.isPending ? "Running deterministic replay…" : "Run backtest"}
        </button>
        <p className="mt-3 text-xs text-neutral-500">
          Full execution payload is validated server-side and persisted on the run row for audit and deterministic replay.
        </p>
      </div>
    </form>
  );
}
