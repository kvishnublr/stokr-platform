import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api } from "../../api/client";
import { Play, RefreshCw, CheckCircle, AlertTriangle, Clock } from "lucide-react";

type StrategyCatalogItem = { strategyKey: string; displayName: string };
type AsyncResponse = { strategyKey: string; from: string; to: string; status: string; message: string };

const today = () => new Date().toISOString().slice(0, 10);

export function AdminSignalReplayPage() {
  const [strategyKey, setStrategyKey] = useState("NSE_SPIKE_DETECTION");
  const [from, setFrom] = useState(today());
  const [to, setTo] = useState(today());
  const [replayStarted, setReplayStarted] = useState<AsyncResponse | null>(null);
  const [trackStarted, setTrackStarted] = useState(false);

  const catalogQ = useQuery<StrategyCatalogItem[]>({
    queryKey: ["strategy-catalog-keys"],
    queryFn: async () => {
      const r = await api.get("/api/admin/strategy-catalog");
      return (r.data?.data ?? []).map((c: any) => ({
        strategyKey: c.strategyKey,
        displayName: c.displayName ?? c.strategyKey,
      }));
    },
    staleTime: 60_000,
  });

  const replayMut = useMutation({
    mutationFn: async () => {
      const r = await api.post(
        `/api/admin/signals/replay?strategyKey=${encodeURIComponent(strategyKey)}&from=${from}&to=${to}`
      );
      return r.data?.data as AsyncResponse;
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

  const strategies: StrategyCatalogItem[] = catalogQ.data ?? [
    { strategyKey: "NSE_SPIKE_DETECTION",  displayName: "NSE Spike Detection" },
    { strategyKey: "GAP_FILL",             displayName: "Gap Fill (82%)" },
    { strategyKey: "VWAP_BOUNCE",          displayName: "VWAP Bounce (71%)" },
    { strategyKey: "SECTOR_LAGGARD",       displayName: "Sector Laggard (73%)" },
    { strategyKey: "EARLY_BREAKOUT",       displayName: "Early Breakout (68%)" },
  ];

  return (
    <div className="mx-auto max-w-2xl space-y-6 p-6">
      <div>
        <h1 className="text-xl font-bold text-slate-900">Signal Replay</h1>
        <p className="mt-1 text-sm text-slate-500">
          Replay historical sessions through the live strategy logic and generate signals with outcomes.
        </p>
      </div>

      {/* Config card */}
      <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm space-y-4">
        <h2 className="text-sm font-semibold text-slate-700">Replay Configuration</h2>

        {/* Strategy */}
        <div>
          <label className="block text-xs font-semibold uppercase tracking-wide text-slate-500 mb-1">
            Strategy
          </label>
          <select
            value={strategyKey}
            onChange={(e) => setStrategyKey(e.target.value)}
            className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-indigo-500"
          >
            {strategies.map((s) => (
              <option key={s.strategyKey} value={s.strategyKey}>{s.displayName}</option>
            ))}
          </select>
        </div>

        {/* Date range */}
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="block text-xs font-semibold uppercase tracking-wide text-slate-500 mb-1">
              From Date
            </label>
            <input
              type="date"
              value={from}
              max={today()}
              onChange={(e) => setFrom(e.target.value)}
              className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
          </div>
          <div>
            <label className="block text-xs font-semibold uppercase tracking-wide text-slate-500 mb-1">
              To Date
            </label>
            <input
              type="date"
              value={to}
              max={today()}
              min={from}
              onChange={(e) => setTo(e.target.value)}
              className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
          </div>
        </div>

        <div className="text-xs text-slate-400">
          Session: 09:25–14:45 IST · Generates live signals (not backtest) · Runs in background — check Signal Monitor after ~60s
        </div>

        <button
          onClick={() => replayMut.mutate()}
          disabled={replayMut.isPending}
          className="flex w-full items-center justify-center gap-2 rounded-lg bg-indigo-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-indigo-700 disabled:opacity-50 transition-colors"
        >
          {replayMut.isPending
            ? <><RefreshCw className="h-4 w-4 animate-spin" /> Starting Replay…</>
            : <><Play className="h-4 w-4" /> Run Replay</>}
        </button>
      </div>

      {/* Replay error */}
      {replayMut.isError && (
        <div className="flex items-start gap-2 rounded-lg border border-rose-200 bg-rose-50 p-4 text-sm text-rose-700">
          <AlertTriangle className="h-4 w-4 mt-0.5 shrink-0" />
          <span>{String((replayMut.error as any)?.message ?? "Replay failed")}</span>
        </div>
      )}

      {/* Replay started card */}
      {replayStarted && (
        <div className="rounded-xl border border-blue-200 bg-blue-50 p-5 space-y-3">
          <div className="flex items-center gap-2 text-sm font-semibold text-blue-700">
            <Clock className="h-4 w-4" /> Replay Running in Background
          </div>
          <div className="text-sm text-blue-600">
            {replayStarted.strategyKey} · {replayStarted.from} → {replayStarted.to}
          </div>
          <div className="text-xs text-blue-500">
            Signals are being generated. Check Signal Monitor in ~60 seconds.
          </div>

          {/* Track outcomes */}
          <button
            onClick={() => trackMut.mutate()}
            disabled={trackMut.isPending}
            className="flex items-center gap-2 rounded-lg border border-blue-300 bg-white px-4 py-2 text-sm font-semibold text-blue-700 hover:bg-blue-50 disabled:opacity-50 transition-colors"
          >
            {trackMut.isPending
              ? <><RefreshCw className="h-4 w-4 animate-spin" /> Starting Outcome Tracker…</>
              : <><RefreshCw className="h-4 w-4" /> Compute Outcomes & PNL</>}
          </button>

          {trackStarted && (
            <div className="flex items-center gap-2 text-xs text-blue-700 font-medium">
              <CheckCircle className="h-3.5 w-3.5" />
              Outcome tracker started — refresh Signal Monitor in ~30s to see TARGET_HIT / SL_HIT results
            </div>
          )}
        </div>
      )}

      {/* Info */}
      <div className="rounded-lg border border-slate-100 bg-slate-50 p-4 text-xs text-slate-500 space-y-1">
        <div className="font-semibold text-slate-600 mb-2">How it works</div>
        <div>1. Fetches all universe symbols bound to the selected strategy</div>
        <div>2. Replays each 5m bar from 09:25–14:45 through the live strategy logic (background)</div>
        <div>3. Qualifying signals are saved as live signals (visible in Signal Monitor)</div>
        <div>4. Click "Compute Outcomes &amp; PNL" to evaluate TARGET_HIT / SL_HIT / EXPIRED</div>
      </div>
    </div>
  );
}
