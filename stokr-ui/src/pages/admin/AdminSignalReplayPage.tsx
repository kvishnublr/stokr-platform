import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api } from "../../api/client";
import { Play, RefreshCw, CheckCircle, AlertTriangle } from "lucide-react";

type StrategyCatalogItem = { strategyKey: string; displayName: string };
type ReplayResult = {
  strategyKey: string; from: string; to: string;
  symbolsScanned: number; barsProcessed: number; signalsGenerated: number;
};

const today = () => new Date().toISOString().slice(0, 10);

export function AdminSignalReplayPage() {
  const [strategyKey, setStrategyKey] = useState("VWAP_MEAN_REVERSION");
  const [from, setFrom] = useState(today());
  const [to, setTo] = useState(today());
  const [result, setResult] = useState<ReplayResult | null>(null);
  const [trackResult, setTrackResult] = useState<{ processed: number } | null>(null);

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
      return r.data?.data as ReplayResult;
    },
    onSuccess: (data) => {
      setResult(data);
      setTrackResult(null);
    },
  });

  const trackMut = useMutation({
    mutationFn: async () => {
      const r = await api.post("/api/admin/signals/track-outcomes");
      return r.data?.data as { processed: number };
    },
    onSuccess: (data) => setTrackResult(data),
  });

  const strategies: StrategyCatalogItem[] = catalogQ.data ?? [
    { strategyKey: "VWAP_MEAN_REVERSION",    displayName: "VWAP Mean Reversion" },
    { strategyKey: "MEAN_REVERSION",          displayName: "Mean Reversion" },
    { strategyKey: "MEAN_REVERSION_V2",       displayName: "Mean Reversion V2" },
    { strategyKey: "MOMENTUM_BREAKOUT",       displayName: "Momentum Breakout" },
    { strategyKey: "OPENING_RANGE_BREAKOUT",  displayName: "Opening Range Breakout" },
    { strategyKey: "EMA_TREND_FOLLOWING",     displayName: "EMA Trend Following" },
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
          Session: 09:25–14:45 IST · Generates live signals (not backtest) · Existing signals for same bars will be deduplicated
        </div>

        <button
          onClick={() => replayMut.mutate()}
          disabled={replayMut.isPending}
          className="flex w-full items-center justify-center gap-2 rounded-lg bg-indigo-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-indigo-700 disabled:opacity-50 transition-colors"
        >
          {replayMut.isPending
            ? <><RefreshCw className="h-4 w-4 animate-spin" /> Running Replay…</>
            : <><Play className="h-4 w-4" /> Run Replay</>}
        </button>
      </div>

      {/* Replay result */}
      {replayMut.isError && (
        <div className="flex items-start gap-2 rounded-lg border border-rose-200 bg-rose-50 p-4 text-sm text-rose-700">
          <AlertTriangle className="h-4 w-4 mt-0.5 shrink-0" />
          <span>{String((replayMut.error as any)?.message ?? "Replay failed")}</span>
        </div>
      )}

      {result && (
        <div className="rounded-xl border border-emerald-200 bg-emerald-50 p-5 space-y-3">
          <div className="flex items-center gap-2 text-sm font-semibold text-emerald-700">
            <CheckCircle className="h-4 w-4" /> Replay Complete
          </div>
          <div className="grid grid-cols-3 gap-3">
            {[
              { label: "Signals Generated", value: result.signalsGenerated, highlight: true },
              { label: "Symbols Scanned",   value: result.symbolsScanned },
              { label: "Bars Processed",    value: result.barsProcessed },
            ].map(({ label, value, highlight }) => (
              <div key={label} className="rounded-lg bg-white border border-emerald-100 p-3 text-center">
                <div className={`text-2xl font-bold ${highlight ? "text-emerald-600" : "text-slate-800"}`}>
                  {value.toLocaleString()}
                </div>
                <div className="text-[11px] text-slate-500 mt-0.5">{label}</div>
              </div>
            ))}
          </div>
          <div className="text-xs text-emerald-600">
            {result.strategyKey} · {result.from} → {result.to}
          </div>

          {/* Track outcomes */}
          <button
            onClick={() => trackMut.mutate()}
            disabled={trackMut.isPending}
            className="flex items-center gap-2 rounded-lg border border-emerald-300 bg-white px-4 py-2 text-sm font-semibold text-emerald-700 hover:bg-emerald-50 disabled:opacity-50 transition-colors"
          >
            {trackMut.isPending
              ? <><RefreshCw className="h-4 w-4 animate-spin" /> Computing Outcomes…</>
              : <><RefreshCw className="h-4 w-4" /> Compute Outcomes & PNL</>}
          </button>

          {trackResult && (
            <div className="text-xs text-emerald-700 font-medium">
              ✓ Outcomes computed for {trackResult.processed.toLocaleString()} signals
            </div>
          )}
        </div>
      )}

      {/* Info */}
      <div className="rounded-lg border border-slate-100 bg-slate-50 p-4 text-xs text-slate-500 space-y-1">
        <div className="font-semibold text-slate-600 mb-2">How it works</div>
        <div>1. Fetches all universe symbols bound to the selected strategy</div>
        <div>2. Replays each 5m bar from 09:25–14:45 through the live strategy logic</div>
        <div>3. Qualifying signals are saved as live signals (visible in Signal Intelligence)</div>
        <div>4. Click "Compute Outcomes &amp; PNL" to evaluate TARGET_HIT / SL_HIT / RUNNING</div>
      </div>
    </div>
  );
}
