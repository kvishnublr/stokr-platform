import { useMemo } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";
import { api, parseAxiosMessage } from "../api/client";
import { INTRADAY_SETUPS } from "../lib/intradaySetups";
import { useSessionStore } from "../state/session";
import { useUiThemeStore } from "../state/uiTheme";

type CatalogRow = { id: string; code: string; name: string; subscribed: boolean; subscriptionEnabled: boolean };
type InstanceRow = { id: string; definitionId: string; executionMode: string; runtimeState: string; symbol: string };
type RuntimeRow = { definitionId: string; strategyKey: string; signalCount: number; runtimeState?: string; health?: string; symbol?: string; lastSignalAt?: string | null };
type SignalRow = { id: string; createdAt: string | null; symbol: string | null; signalType: string | null; strategyName: string | null; reason: string | null; confidenceScore: string | null };

function since(ts: string | null | undefined): string {
  if (!ts) return "-";
  const t = Date.parse(ts);
  if (!Number.isFinite(t)) return "-";
  const sec = Math.max(1, Math.floor((Date.now() - t) / 1000));
  if (sec < 60) return `${sec}s`;
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m`;
  const hr = Math.floor(min / 60);
  return `${hr}h`;
}

export function IntradayTraderPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const token = useSessionStore((s) => s.accessToken);
  const navigate = useNavigate();
  const qc = useQueryClient();

  const catalogQ = useQuery({
    queryKey: ["strategy-catalog-intraday"],
    queryFn: async () => (await api.get("/api/strategies/catalog?size=200")).data?.data?.content as CatalogRow[],
    enabled: !!token,
    refetchInterval: 20000,
  });
  const runtimeQ = useQuery({
    queryKey: ["strategy-runtime-intraday"],
    queryFn: async () => (await api.get("/api/strategies/runtime-metrics")).data?.data as RuntimeRow[],
    enabled: !!token,
    refetchInterval: 15000,
  });
  const instancesQ = useQuery({
    queryKey: ["strategy-instances-intraday"],
    queryFn: async () => (await api.get("/api/strategies/instances?size=200")).data?.data?.content as InstanceRow[],
    enabled: !!token,
    refetchInterval: 15000,
  });
  const signalsQ = useQuery({
    queryKey: ["trader-signals-intraday"],
    queryFn: async () => (await api.get("/api/trader/strategy-feed?limit=500")).data?.data as SignalRow[],
    enabled: !!token,
    refetchInterval: 15000,
  });

  const toggleSub = useMutation({
    mutationFn: async (definitionId: string) => api.post(`/api/strategies/catalog/${definitionId}/subscription/toggle`),
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });
  const patchInstance = useMutation({
    mutationFn: async (payload: { id: string; body: Record<string, unknown> }) => api.patch(`/api/strategies/instances/${payload.id}`, payload.body),
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });
  const startInstance = useMutation({
    mutationFn: async (id: string) => api.post(`/api/strategies/instances/${id}/start`),
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });
  const pauseInstance = useMutation({
    mutationFn: async (id: string) => api.post(`/api/strategies/instances/${id}/pause`),
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });
  const actionsBusy = toggleSub.isPending || patchInstance.isPending || startInstance.isPending || pauseInstance.isPending;

  async function ensureInstanceByStrategy(strategyKey: string): Promise<InstanceRow | null> {
    const catalog = catalogQ.data ?? [];
    const def = catalog.find((c) => c.code === strategyKey);
    if (!def) return null;
    const existing = (instancesQ.data ?? []).find((i) => i.definitionId === def.id);
    if (existing) return existing;
    await toggleSub.mutateAsync(def.id);
    await qc.invalidateQueries({ queryKey: ["strategy-instances-intraday"] });
    const latest = await qc.fetchQuery({
      queryKey: ["strategy-instances-intraday"],
      queryFn: async () => (await api.get("/api/strategies/instances?size=200")).data?.data?.content as InstanceRow[],
    });
    return (latest ?? []).find((i) => i.definitionId === def.id) ?? null;
  }

  async function routeSetup(strategyKey: string, mode: "BACKTEST" | "PAPER" | "LIVE" | "PAUSE") {
    if (actionsBusy) return;
    try {
      if (mode === "BACKTEST") {
        navigate(`/backtests/launch?strategyKey=${encodeURIComponent(strategyKey)}`);
        return;
      }
      const inst = await ensureInstanceByStrategy(strategyKey);
      if (!inst) {
        toast.error(`Could not resolve instance for ${strategyKey}`);
        return;
      }
      if (mode === "PAUSE") {
        await pauseInstance.mutateAsync(inst.id);
        await qc.invalidateQueries({ queryKey: ["strategy-runtime-intraday"] });
        return;
      }
      await patchInstance.mutateAsync({ id: inst.id, body: { executionMode: mode } });
      await startInstance.mutateAsync(inst.id);
      await qc.invalidateQueries({ queryKey: ["strategy-runtime-intraday"] });
      await qc.invalidateQueries({ queryKey: ["strategy-instances-intraday"] });
      await qc.invalidateQueries({ queryKey: ["trader-signals-intraday"] });
      toast.success(`${strategyKey} routed to ${mode}`);
    } catch (e) {
      toast.error(parseAxiosMessage(e));
      return;
    }
  }

  const setupRows = useMemo(() => {
    const rt = runtimeQ.data ?? [];
    const sig = signalsQ.data ?? [];
    return INTRADAY_SETUPS.map((s) => {
      const r = rt.find((x) => x.strategyKey === s.strategyKey);
      const related = sig.filter((x) => x.strategyName === s.strategyKey);
      return {
        ...s,
        signals: related.length,
        lastSignalAt: related[0]?.createdAt ?? r?.lastSignalAt ?? null,
        runtimeState: (r?.runtimeState ?? "IDLE").toUpperCase(),
        health: (r?.health ?? "UNKNOWN").toUpperCase(),
      };
    });
  }, [runtimeQ.data, signalsQ.data]);

  const recentSignals = useMemo(() => {
    const allowed = new Set(INTRADAY_SETUPS.map((s) => s.strategyKey));
    return [...(signalsQ.data ?? [])]
      .filter((s) => allowed.has(String(s.strategyName ?? "")))
      .sort((a, b) => Date.parse(b.createdAt ?? "") - Date.parse(a.createdAt ?? ""))
      .slice(0, 20);
  }, [signalsQ.data]);

  return (
    <div className="space-y-6">
      <div>
        <h1 className={isLight ? "text-2xl font-semibold text-neutral-900" : "text-2xl font-semibold text-white"}>Intraday Terminal</h1>
        <p className={isLight ? "text-sm text-neutral-600" : "text-sm text-neutral-400"}>
          Dedicated intraday workstation with setup-specific routing and signal history.
        </p>
      </div>

      {catalogQ.isError || runtimeQ.isError || instancesQ.isError || signalsQ.isError ? (
        <div className="rounded-xl border border-red-300 bg-red-50 px-4 py-3 text-sm text-red-700">
          {parseAxiosMessage(catalogQ.error ?? runtimeQ.error ?? instancesQ.error ?? signalsQ.error)}
        </div>
      ) : null}

      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        {setupRows.map((s) => (
          <div key={s.key} className={isLight ? "rounded-2xl border border-neutral-200 bg-white p-4" : "rounded-2xl border border-neutral-800 bg-neutral-950 p-4"}>
            <div className="flex items-center justify-between gap-3">
              <p className={isLight ? "font-semibold text-neutral-900" : "font-semibold text-white"}>{s.title}</p>
              <span className={isLight ? "rounded-full border border-neutral-300 px-2 py-0.5 text-xs text-neutral-700" : "rounded-full border border-neutral-700 px-2 py-0.5 text-xs text-neutral-300"}>{s.runtimeState}</span>
            </div>
            <p className={isLight ? "mt-1 text-xs text-neutral-500" : "mt-1 text-xs text-neutral-400"}>{s.note}</p>
            <p className={isLight ? "mt-2 text-xs text-neutral-500" : "mt-2 text-xs text-neutral-400"}>Best window: {s.bestWindow}</p>
            <p className={isLight ? "mt-2 text-xs text-neutral-500" : "mt-2 text-xs text-neutral-400"}>Health: {s.health} • Signals: {s.signals} • Last: {since(s.lastSignalAt)}</p>
            <div className="mt-3 grid grid-cols-2 gap-2">
              <button disabled={actionsBusy} onClick={() => void routeSetup(s.strategyKey, "BACKTEST")} className="rounded-lg bg-blue-600 px-2 py-1.5 text-xs font-semibold text-white hover:bg-blue-700 disabled:opacity-60">Backtest</button>
              <button disabled={actionsBusy} onClick={() => void routeSetup(s.strategyKey, "PAPER")} className="rounded-lg border border-neutral-300 px-2 py-1.5 text-xs font-semibold hover:bg-neutral-50 disabled:opacity-60 dark:border-neutral-700 dark:hover:bg-neutral-900">Paper</button>
              <button disabled={actionsBusy} onClick={() => void routeSetup(s.strategyKey, "LIVE")} className="rounded-lg border border-emerald-500 px-2 py-1.5 text-xs font-semibold text-emerald-600 hover:bg-emerald-50 disabled:opacity-60 dark:hover:bg-emerald-950/20">Live</button>
              <button disabled={actionsBusy} onClick={() => void routeSetup(s.strategyKey, "PAUSE")} className="rounded-lg border border-amber-500 px-2 py-1.5 text-xs font-semibold text-amber-600 hover:bg-amber-50 disabled:opacity-60 dark:hover:bg-amber-950/20">Pause</button>
            </div>
          </div>
        ))}
      </div>

      <div className={isLight ? "rounded-2xl border border-neutral-200 bg-white" : "rounded-2xl border border-neutral-800 bg-neutral-950"}>
        <div className={isLight ? "border-b border-neutral-200 px-4 py-3 text-sm font-semibold text-neutral-900" : "border-b border-neutral-800 px-4 py-3 text-sm font-semibold text-white"}>Intraday Signal History</div>
        <div className="overflow-x-auto">
          <table className="min-w-full text-sm">
            <thead className={isLight ? "text-neutral-500" : "text-neutral-400"}>
              <tr>
                <th className="px-4 py-2 text-left">Time</th>
                <th className="px-4 py-2 text-left">Strategy</th>
                <th className="px-4 py-2 text-left">Symbol</th>
                <th className="px-4 py-2 text-left">Side</th>
                <th className="px-4 py-2 text-left">Confidence</th>
                <th className="px-4 py-2 text-left">Reason</th>
              </tr>
            </thead>
            <tbody>
              {recentSignals.length === 0 ? (
                <tr><td className="px-4 py-6 text-center text-neutral-500" colSpan={6}>No intraday setup signals yet</td></tr>
              ) : (
                recentSignals.map((s) => (
                  <tr key={s.id} className={isLight ? "border-t border-neutral-100" : "border-t border-neutral-900"}>
                    <td className="px-4 py-2">{s.createdAt ? new Date(s.createdAt).toLocaleString() : "-"}</td>
                    <td className="px-4 py-2">{s.strategyName ?? "-"}</td>
                    <td className="px-4 py-2">{s.symbol ?? "-"}</td>
                    <td className="px-4 py-2">{s.signalType ?? "-"}</td>
                    <td className="px-4 py-2">{s.confidenceScore ?? "-"}</td>
                    <td className="px-4 py-2">{s.reason ?? "-"}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
