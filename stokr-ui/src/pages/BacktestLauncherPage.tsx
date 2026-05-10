import { useMutation } from "@tanstack/react-query";
import { Calendar } from "lucide-react";
import { useRef, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";
import { launchReplay, type ReplayLaunchBody } from "../api/backtest";
import { parseAxiosMessage } from "../api/client";

const STRATEGIES = [
  { key: "MEAN_REVERSION_RANGE_FADE", label: "Mean reversion (range fade)" },
  { key: "MEAN_REVERSION_V2", label: "Mean reversion v2 (relaxed bands)" },
  { key: "OPENING_RANGE_BREAKOUT", label: "Opening range breakout" },
  { key: "VWAP_MEAN_REVERSION", label: "VWAP mean reversion" },
  { key: "MOMENTUM_BREAKOUT", label: "Momentum breakout" },
  { key: "EMA_TREND_FOLLOW", label: "EMA trend following" },
] as const;

const TIMEFRAMES = ["1m", "5m", "15m", "1h"] as const;

/** Dark-theme picker; calendar button overlay handles opening (native glyph sits underneath). */
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

function localInputToIso(localDatetime: string): string {
  const d = new Date(localDatetime);
  return d.toISOString();
}

export function BacktestLauncherPage() {
  const navigate = useNavigate();
  const startInputRef = useRef<HTMLInputElement>(null);
  const endInputRef = useRef<HTMLInputElement>(null);

  const m = useMutation({
    mutationFn: async (body: ReplayLaunchBody) => launchReplay(body),
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
    const fd = new FormData(e.currentTarget);
    const symbol = String(fd.get("symbol") || "").trim().toUpperCase();
    const startLocal = String(fd.get("start") || "");
    const endLocal = String(fd.get("end") || "");
    const seed = Number(fd.get("seed") ?? 42);
    const strategyKey = String(fd.get("strategyKey") || "MEAN_REVERSION_RANGE_FADE");
    const timeframe = String(fd.get("timeframe") || "5m");
    const executionProfile = String(fd.get("executionProfile") || "SIMULATED_DEFAULT");
    const feeModel = String(fd.get("feeModel") || "PERCENT_2_BPS");
    const slippageModel = String(fd.get("slippageModel") || "SPREAD_PROXY");

    if (!symbol || !startLocal || !endLocal) {
      toast.error("Symbol and date range are required.");
      return;
    }

    const body: ReplayLaunchBody = {
      symbol,
      start: localInputToIso(startLocal),
      end: localInputToIso(endLocal),
      seed: Number.isFinite(seed) ? seed : 42,
      strategyKey,
      timeframe,
      executionProfile,
      feeModel,
      slippageModel,
    };
    m.mutate(body);
  }

  return (
    <form onSubmit={onSubmit} className="max-w-2xl space-y-6">
      <div className="rounded-2xl border border-neutral-800 bg-neutral-950/60 p-6">
        <div className="grid gap-4 sm:grid-cols-2">
          <label className="block text-sm">
            <span className="text-neutral-400">Symbol</span>
            <input
              name="symbol"
              defaultValue="SPY"
              className="mt-1 w-full rounded-lg border border-neutral-800 bg-neutral-950 px-3 py-2 font-mono text-sm text-white outline-none focus:border-neutral-600"
              required
            />
          </label>
          <label className="block text-sm">
            <span className="text-neutral-400">Seed</span>
            <input
              name="seed"
              type="number"
              defaultValue={42}
              className="mt-1 w-full rounded-lg border border-neutral-800 bg-neutral-950 px-3 py-2 font-mono text-sm text-white outline-none focus:border-neutral-600"
            />
          </label>
          <label className="block text-sm sm:col-span-2">
            <span className="text-neutral-400">Strategy</span>
            <select
              name="strategyKey"
              defaultValue="MEAN_REVERSION_RANGE_FADE"
              className="mt-1 w-full rounded-lg border border-neutral-800 bg-neutral-950 px-3 py-2 text-sm text-white outline-none focus:border-neutral-600"
            >
              {STRATEGIES.map((s) => (
                <option key={s.key} value={s.key}>
                  {s.label}
                </option>
              ))}
            </select>
          </label>
          <label className="block text-sm">
            <span className="text-neutral-400">Timeframe</span>
            <select
              name="timeframe"
              defaultValue="5m"
              className="mt-1 w-full rounded-lg border border-neutral-800 bg-neutral-950 px-3 py-2 text-sm text-white outline-none focus:border-neutral-600"
            >
              {TIMEFRAMES.map((tf) => (
                <option key={tf} value={tf}>
                  {tf}
                </option>
              ))}
            </select>
          </label>
          <label className="block text-sm">
            <span className="text-neutral-400">Execution profile</span>
            <select
              name="executionProfile"
              defaultValue="SIMULATED_DEFAULT"
              className="mt-1 w-full rounded-lg border border-neutral-800 bg-neutral-950 px-3 py-2 text-sm text-white outline-none focus:border-neutral-600"
            >
              <option value="SIMULATED_DEFAULT">Simulated default</option>
              <option value="SIMULATED_HIGH_LATENCY">Simulated high latency</option>
              <option value="REPLAY_RAW">Replay raw</option>
            </select>
          </label>
          <label className="block text-sm">
            <span className="text-neutral-400">Fee model</span>
            <select
              name="feeModel"
              defaultValue="PERCENT_2_BPS"
              className="mt-1 w-full rounded-lg border border-neutral-800 bg-neutral-950 px-3 py-2 text-sm text-white outline-none focus:border-neutral-600"
            >
              <option value="NONE">None</option>
              <option value="PERCENT_2_BPS">2 bps per side</option>
              <option value="PERCENT_5_BPS">5 bps per side</option>
            </select>
          </label>
          <label className="block text-sm">
            <span className="text-neutral-400">Slippage model</span>
            <select
              name="slippageModel"
              defaultValue="SPREAD_PROXY"
              className="mt-1 w-full rounded-lg border border-neutral-800 bg-neutral-950 px-3 py-2 text-sm text-white outline-none focus:border-neutral-600"
            >
              <option value="NONE">None</option>
              <option value="SPREAD_PROXY">Spread proxy</option>
              <option value="VOL_SCALED">Vol scaled</option>
            </select>
          </label>
          <label className="block text-sm">
            <span className="text-neutral-400">Range start (local)</span>
            <div className="relative mt-1">
              <input
                ref={startInputRef}
                name="start"
                type="datetime-local"
                className={DATETIME_LOCAL_CLASS}
                required
              />
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
              <input
                ref={endInputRef}
                name="end"
                type="datetime-local"
                className={DATETIME_LOCAL_CLASS}
                required
              />
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

        <button
          type="submit"
          disabled={m.isPending}
          className="mt-6 w-full rounded-xl bg-blue-600 py-3 text-sm font-semibold text-white hover:bg-blue-500 disabled:opacity-50"
        >
          {m.isPending ? "Running deterministic replay…" : "Run backtest"}
        </button>
        <p className="mt-3 text-xs text-neutral-500">
          Replay executes synchronously on the API thread — large ranges may take noticeable time. Results persist metrics,
          trades, equity curve, and replay integrity hash.
        </p>
      </div>
    </form>
  );
}
