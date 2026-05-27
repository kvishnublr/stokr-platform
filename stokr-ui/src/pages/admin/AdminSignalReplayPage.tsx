import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle, Clock, Database, Play, RefreshCw } from "lucide-react";
import { api, parseAxiosMessage } from "../../api/client";
import { fetchStrategyCatalogKeys } from "../../api/strategyCatalog";
import { cn } from "../../lib/utils";
import { toneBannerClasses, toneButtonClasses, toneChipClasses } from "../../lib/statusTone";
import { useUiThemeStore } from "../../state/uiTheme";

type StrategyCatalogItem = { strategyKey: string; displayName: string };

type ReplayPreflight = {
  strategyKey: string;
  from: string;
  to: string;
  ready: boolean;
  symbolCount: number;
  symbolsWithData: number;
  symbolsNeedingSeed: number;
  estimatedBars: number;
  blockers: string[];
  warnings: string[];
};

type ReplayStartResponse = ReplayPreflight & {
  status: string;
  message: string;
};

function todayIst(): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Kolkata" }).format(new Date());
}

export function AdminSignalReplayPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const [strategyKey, setStrategyKey] = useState("NSE_SPIKE_DETECTION");
  const [from, setFrom] = useState(todayIst);
  const [to, setTo] = useState(todayIst);
  const [replayStarted, setReplayStarted] = useState<ReplayStartResponse | null>(null);
  const [trackStarted, setTrackStarted] = useState(false);

  const catalogQ = useQuery({
    queryKey: ["strategy-catalog-keys"],
    queryFn: fetchStrategyCatalogKeys,
    staleTime: 60_000,
  });

  const preflightQ = useQuery<ReplayPreflight>({
    queryKey: ["signal-replay-preflight", strategyKey, from, to],
    queryFn: async () =>
      (await api.get("/api/admin/signals/replay/preflight", { params: { strategyKey, from, to } })).data?.data as ReplayPreflight,
    enabled: Boolean(strategyKey && from && to),
    retry: false,
  });

  const preflightEndpointMissing =
    preflightQ.isError &&
    typeof preflightQ.error === "object" &&
    preflightQ.error !== null &&
    "response" in preflightQ.error &&
    (preflightQ.error as { response?: { status?: number } }).response?.status === 404;

  const replayMut = useMutation({
    mutationFn: async () => {
      const r = await api.post(
        `/api/admin/signals/replay?strategyKey=${encodeURIComponent(strategyKey)}&from=${from}&to=${to}`,
      );
      return r.data?.data as ReplayStartResponse;
    },
    onSuccess: (data) => {
      setReplayStarted(data);
      setTrackStarted(false);
    },
  });

  const trackMut = useMutation({
    mutationFn: async () => {
      const r = await api.post("/api/admin/signals/track-outcomes-async");
      return r.data?.data;
    },
    onSuccess: () => setTrackStarted(true),
  });

  const seedMut = useMutation({
    mutationFn: async () => (await api.post("/api/admin/signals/seed-replay-candles")).data?.data,
    onSuccess: () => void preflightQ.refetch(),
  });

  const strategies: StrategyCatalogItem[] = catalogQ.data ?? [
    { strategyKey: "NSE_SPIKE_DETECTION", displayName: "NSE Spike Detection" },
    { strategyKey: "GAP_FILL", displayName: "Gap Fill (82%)" },
    { strategyKey: "VWAP_BOUNCE", displayName: "VWAP Bounce (71%)" },
    { strategyKey: "SECTOR_LAGGARD", displayName: "Sector Laggard (73%)" },
    { strategyKey: "EARLY_BREAKOUT", displayName: "Early Breakout (68%)" },
  ];

  const preflight = preflightQ.data;
  const canRun = preflightEndpointMissing
    ? !replayMut.isPending
    : Boolean(preflight?.ready) && !replayMut.isPending;

  const preflightSummary = useMemo(() => {
    if (!preflight) return null;
    if (preflight.symbolCount === 0) return "No universe symbols bound to this strategy.";
    return `${preflight.symbolCount} symbol(s) · ~${preflight.estimatedBars.toLocaleString()} bars · ${preflight.symbolsWithData} ready`;
  }, [preflight]);

  const inputClass = cn(
    "w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500",
    isLight ? "border-slate-300 bg-white text-slate-900" : "border-neutral-700 bg-neutral-900 text-neutral-100",
  );

  return (
    <div className="mx-auto max-w-2xl space-y-6 p-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className={cn("text-xl font-bold", isLight ? "text-slate-900" : "text-neutral-100")}>Signal Replay</h1>
          <p className={cn("mt-1 text-sm", isLight ? "text-slate-600" : "text-neutral-400")}>
            Replay historical sessions through live strategy logic and generate REPLAY-tagged signals with outcomes.
          </p>
        </div>
        <Link
          to="/admin/signal-lab"
          className={cn("rounded-lg border px-3 py-1.5 text-sm font-medium", toneChipClasses(isLight, "info"))}
        >
          Open Signal Lab
        </Link>
      </div>

      {preflightEndpointMissing ? (
        <div className={toneBannerClasses(isLight, "warn")}>
          <div className="flex items-start gap-2 text-sm">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
            <div>
              <p className="font-semibold">Server update required</p>
              <p className="mt-1 text-xs">
                Replay preflight API is not deployed. Run replay may start but fail silently in the background. Deploy{" "}
                <code className="rounded bg-black/5 px-1">Release_v1</code> on the server, then refresh.
              </p>
            </div>
          </div>
        </div>
      ) : null}

      {preflight && !preflight.ready ? (
        <div className={toneBannerClasses(isLight, "warn")}>
          <div className="flex items-start gap-2 text-sm">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
            <div>
              <p className="font-semibold">Replay blocked</p>
              <ul className="mt-1 list-disc pl-4 text-xs">
                {preflight.blockers.map((b) => (
                  <li key={b}>{b}</li>
                ))}
              </ul>
              <div className="mt-2 flex flex-wrap gap-2">
                <Link to="/admin/runtime-bindings" className={cn("rounded-lg border px-2 py-1 text-xs font-semibold", toneButtonClasses(isLight, "secondary"))}>
                  Runtime bindings
                </Link>
                <Link to="/admin/backfill" className={cn("rounded-lg border px-2 py-1 text-xs font-semibold", toneButtonClasses(isLight, "secondary"))}>
                  Market backfill
                </Link>
                <button
                  type="button"
                  onClick={() => seedMut.mutate()}
                  disabled={seedMut.isPending}
                  className={cn("rounded-lg border px-2 py-1 text-xs font-semibold", toneButtonClasses(isLight, "secondary"))}
                >
                  {seedMut.isPending ? "Seeding…" : "Seed replay candles"}
                </button>
              </div>
            </div>
          </div>
        </div>
      ) : null}

      <div className={cn("rounded-xl border p-5 shadow-sm space-y-4", isLight ? "border-slate-200 bg-white" : "border-neutral-800 bg-neutral-950/50")}>
        <h2 className={cn("text-sm font-semibold", isLight ? "text-slate-700" : "text-neutral-200")}>Replay configuration</h2>

        <div>
          <label className={cn("mb-1 block text-xs font-semibold uppercase tracking-wide", isLight ? "text-slate-500" : "text-neutral-400")}>
            Strategy
          </label>
          <select value={strategyKey} onChange={(e) => setStrategyKey(e.target.value)} className={inputClass}>
            {strategies.map((s) => (
              <option key={s.strategyKey} value={s.strategyKey}>
                {s.displayName}
              </option>
            ))}
          </select>
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className={cn("mb-1 block text-xs font-semibold uppercase tracking-wide", isLight ? "text-slate-500" : "text-neutral-400")}>
              From date (IST)
            </label>
            <input type="date" value={from} max={todayIst()} onChange={(e) => setFrom(e.target.value)} className={inputClass} />
          </div>
          <div>
            <label className={cn("mb-1 block text-xs font-semibold uppercase tracking-wide", isLight ? "text-slate-500" : "text-neutral-400")}>
              To date (IST)
            </label>
            <input type="date" value={to} max={todayIst()} min={from} onChange={(e) => setTo(e.target.value)} className={inputClass} />
          </div>
        </div>

        {preflightSummary ? (
          <div className={cn("rounded-lg border px-3 py-2 text-xs", isLight ? "border-slate-200 bg-slate-50 text-slate-700" : "border-neutral-800 bg-neutral-900/40 text-neutral-300")}>
            <div className="flex items-center gap-2">
              <Database className="h-3.5 w-3.5" />
              <span>{preflightSummary}</span>
            </div>
            {preflight?.warnings?.length ? (
              <ul className="mt-1 list-disc pl-5 text-amber-700 dark:text-amber-200">
                {preflight.warnings.map((w) => (
                  <li key={w}>{w}</li>
                ))}
              </ul>
            ) : null}
          </div>
        ) : null}

        <div className={cn("text-xs", isLight ? "text-slate-500" : "text-neutral-400")}>
          Session 09:25–14:45 IST · 1m bars · REPLAY signals (analytics, not OMS) · Background job ~60s
        </div>

        <button
          type="button"
          onClick={() => replayMut.mutate()}
          disabled={!canRun}
          className={cn(
            "flex w-full items-center justify-center gap-2 rounded-lg px-4 py-2.5 text-sm font-semibold text-white transition-colors disabled:opacity-50",
            isLight ? "bg-indigo-600 hover:bg-indigo-700" : "bg-indigo-500 hover:bg-indigo-400",
          )}
        >
          {replayMut.isPending ? (
            <>
              <RefreshCw className="h-4 w-4 animate-spin" /> Starting replay…
            </>
          ) : (
            <>
              <Play className="h-4 w-4" /> Run replay
            </>
          )}
        </button>
      </div>

      {replayMut.isError ? (
        <div className={toneBannerClasses(isLight, "critical")}>
          <div className="flex items-start gap-2 text-sm">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
            <span>{parseAxiosMessage(replayMut.error)}</span>
          </div>
        </div>
      ) : null}

      {replayStarted ? (
        <div className={cn("rounded-xl border p-5 space-y-3", isLight ? "border-blue-200 bg-blue-50" : "border-blue-500/30 bg-blue-500/10")}>
          <div className={cn("flex items-center gap-2 text-sm font-semibold", isLight ? "text-blue-800" : "text-blue-200")}>
            <Clock className="h-4 w-4" /> Replay running in background
          </div>
          <div className={cn("text-sm", isLight ? "text-blue-900" : "text-blue-100")}>
            {replayStarted.strategyKey} · {replayStarted.from} → {replayStarted.to}
          </div>
          <div className={cn("text-xs", isLight ? "text-blue-700" : "text-blue-200/90")}>
            {replayStarted.message}
            {" "}Use the <strong>Replay / Lab</strong> tab in Signal Monitor — production stats exclude REPLAY signals.
          </div>

          <button
            type="button"
            onClick={() => trackMut.mutate()}
            disabled={trackMut.isPending}
            className={cn("flex items-center gap-2 rounded-lg border px-4 py-2 text-sm font-semibold", toneButtonClasses(isLight, "secondary"))}
          >
            {trackMut.isPending ? (
              <>
                <RefreshCw className="h-4 w-4 animate-spin" /> Starting outcome tracker…
              </>
            ) : (
              <>
                <RefreshCw className="h-4 w-4" /> Compute outcomes &amp; PNL
              </>
            )}
          </button>

          {trackStarted ? (
            <div className={cn("flex items-center gap-2 text-xs font-medium", isLight ? "text-blue-800" : "text-blue-200")}>
              <CheckCircle className="h-3.5 w-3.5" />
              Outcome tracker started — refresh Signal Monitor in ~30s
            </div>
          ) : null}

          <Link to="/admin/signals" className={cn("inline-flex text-xs font-semibold underline", isLight ? "text-blue-800" : "text-blue-200")}>
            Open Signal Monitor →
          </Link>
          <Link
            to="/admin/signals?provenance=replay"
            className={cn("inline-flex text-xs font-semibold underline", isLight ? "text-violet-800" : "text-violet-200")}
          >
            View REPLAY signals tab →
          </Link>
        </div>
      ) : null}

      <div className={cn("rounded-lg border p-4 text-xs space-y-1", isLight ? "border-slate-100 bg-slate-50 text-slate-600" : "border-neutral-800 bg-neutral-900/40 text-neutral-400")}>
        <div className={cn("mb-2 font-semibold", isLight ? "text-slate-700" : "text-neutral-200")}>How it works</div>
        <div>1. Validates runtime bindings and 1m candle coverage for the selected IST dates</div>
        <div>2. Auto-seeds synthetic 1m candles when coverage is sparse (configurable on server)</div>
        <div>3. Walks each bar through the registered strategy and persists REPLAY signals</div>
        <div>4. Run outcome tracker to evaluate TARGET_HIT / SL_HIT / EXPIRED</div>
      </div>
    </div>
  );
}
