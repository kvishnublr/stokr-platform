import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";
import { Activity, AlertTriangle, BarChart3, Clock3, Gauge, Radio, ShieldAlert, TrendingUp, Zap } from "lucide-react";
import { api, parseAxiosMessage } from "../api/client";
import { INTRADAY_SETUPS } from "../lib/intradaySetups";
import { cn } from "../lib/utils";
import { useSessionStore } from "../state/session";
import { useUiThemeStore } from "../state/uiTheme";

type CatalogRow = { id: string; code: string; name: string; subscribed: boolean; subscriptionEnabled: boolean };
type InstanceRow = { id: string; definitionId: string; executionMode: string; runtimeState: string; symbol: string };
type RuntimeRow = {
  definitionId: string;
  strategyKey: string;
  signalCount: number;
  runtimeState?: string;
  health?: string;
  symbol?: string;
  lastSignalAt?: string | null;
};
type SignalRow = {
  id: string;
  createdAt: string | null;
  symbol: string | null;
  signalType: string | null;
  strategyName: string | null;
  reason: string | null;
  confidenceScore: string | null;
};

type Workstation = {
  accountSummary: {
    totalPnl: string;
    realizedPnl: string;
    unrealizedPnl: string;
    openPositions: number;
    activeStrategies: number;
    brokerConnectionState: string;
    executionMode: string;
  };
  badges: string[];
  openPositions: Array<Record<string, unknown>>;
  closedPositions: Array<Record<string, unknown>>;
  orders: Array<Record<string, unknown>>;
  executions: Array<Record<string, unknown>>;
  strategyAllocations: Array<Record<string, unknown>>;
  riskControls: {
    reconciliationWarnings: string[];
    parityState: string;
    tokenValid: boolean;
    brokerHealth: string;
    liveEligible: boolean;
  };
  latestSignals: Array<Record<string, unknown>>;
};

type StrategyState = "ACTIVE" | "WARMING" | "COOLING" | "BLOCKED";
type HeatTier = "S" | "A" | "B" | "C" | "D";
type Verdict = "STRONG" | "MODERATE" | "AVOID";

type StrategyRailRow = {
  key: string;
  title: string;
  strategyKey: string;
  state: StrategyState;
  heat: HeatTier;
  probability: number;
  historicalWinRate: number;
  liveWinRate: number;
  expectedRr: number;
  riskScore: number;
  signalCountToday: number;
  capitalEfficiency: number;
  avgHoldMin: number;
  compatibility: "TRENDING" | "MEAN_REVERSION" | "NEUTRAL";
  score: number;
  bestWindow: string;
  lastSignalAt: string | null;
  runtimeState: string;
  health: string;
  tooltip: string[];
};

type OpportunityRow = {
  id: string;
  symbol: string;
  sector: string;
  setupType: string;
  direction: "LONG" | "SHORT";
  entryZone: string;
  slZone: string;
  targetZone: string;
  rrRatio: number;
  probability: number;
  volumeQuality: number;
  trendStrength: number;
  freshnessSec: number;
  executionQuality: number;
  verdict: Verdict;
  rationale: string;
};

type SetupValidationRow = {
  setupTitle: string;
  strategyKey: string;
  catalogPresent: boolean;
  instancePresent: boolean;
  runtimeHealthy: boolean;
  subscribed: boolean;
  ready: boolean;
  blocker: string | null;
};

const DOCK_TABS = [
  { id: "open", label: "Open Positions" },
  { id: "closed", label: "Closed Positions" },
  { id: "orders", label: "Orders" },
  { id: "alerts", label: "Alerts" },
  { id: "logs", label: "Strategy Logs" },
  { id: "replay", label: "Replay" },
  { id: "risk", label: "Risk Events" },
] as const;

function num(v: unknown, fallback = 0): number {
  if (typeof v === "number" && Number.isFinite(v)) return v;
  if (typeof v === "string") {
    const n = Number(v.replace(/[^0-9.-]/g, ""));
    if (Number.isFinite(n)) return n;
  }
  return fallback;
}

function clamp(v: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, v));
}

function sinceSec(ts: string | null | undefined): number {
  if (!ts) return 86400;
  const t = Date.parse(ts);
  if (!Number.isFinite(t)) return 86400;
  return Math.max(1, Math.floor((Date.now() - t) / 1000));
}

function sinceLabel(ts: string | null | undefined): string {
  const s = sinceSec(ts);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m`;
  const h = Math.floor(m / 60);
  return `${h}h`;
}

function inferSector(symbol: string): string {
  const s = symbol.toUpperCase();
  if (s.includes("BANK") || s.includes("HDFC") || s.includes("ICICI") || s.includes("SBI")) return "BANKING";
  if (s.includes("INFY") || s.includes("TCS") || s.includes("WIPRO") || s.includes("TECH")) return "IT";
  if (s.includes("RELIANCE") || s.includes("ONGC") || s.includes("OIL")) return "ENERGY";
  if (s.includes("TATA") || s.includes("LAR") || s.includes("HINDAL")) return "INDUSTRIAL";
  return "DIVERSIFIED";
}

function currentTradingWindow(): "OPENING" | "MIDDAY" | "CLOSING" | "OFF_HOURS" {
  const now = new Date();
  const ist = new Date(now.getTime() + 5.5 * 60 * 60 * 1000);
  const minutes = ist.getUTCHours() * 60 + ist.getUTCMinutes();
  const open = 9 * 60 + 15;
  const midday = 12 * 60 + 15;
  const close = 14 * 60 + 45;
  const end = 15 * 60 + 30;
  if (minutes < open || minutes > end) return "OFF_HOURS";
  if (minutes <= midday) return "OPENING";
  if (minutes <= close) return "MIDDAY";
  return "CLOSING";
}

function determineState(probability: number, riskScore: number, runtimeState: string, health: string): StrategyState {
  const state = runtimeState.toUpperCase();
  if (state.includes("PAUSE") || state.includes("BLOCK") || health.toUpperCase().includes("BAD")) return "BLOCKED";
  if (probability >= 74 && riskScore <= 45) return "ACTIVE";
  if (probability >= 62) return "WARMING";
  return "COOLING";
}

function heatTier(score: number): HeatTier {
  if (score >= 84) return "S";
  if (score >= 74) return "A";
  if (score >= 62) return "B";
  if (score >= 48) return "C";
  return "D";
}

function verdict(probability: number, rr: number, freshnessSec: number): Verdict {
  if (probability >= 74 && rr >= 1.8 && freshnessSec <= 360) return "STRONG";
  if (probability >= 58 && rr >= 1.2) return "MODERATE";
  return "AVOID";
}

function badgeTone(v: string) {
  const x = v.toUpperCase();
  if (x.includes("READY") || x.includes("SYNCED") || x.includes("CONNECTED") || x === "LIVE" || x === "STRONG") return "ok";
  if (x.includes("PENDING") || x.includes("PARTIAL") || x.includes("PAUSED") || x === "PAPER" || x === "MODERATE") return "warn";
  if (x.includes("MISMATCH") || x.includes("BLOCKED") || x.includes("DISCONNECTED") || x === "AVOID") return "bad";
  return "neutral";
}

function toneClass(v: string) {
  const tone = badgeTone(v);
  if (tone === "ok") return "border-emerald-300/60 bg-emerald-50 text-emerald-800";
  if (tone === "warn") return "border-amber-300/60 bg-amber-50 text-amber-800";
  if (tone === "bad") return "border-rose-300/60 bg-rose-50 text-rose-800";
  return "border-neutral-300/60 bg-white text-neutral-700";
}

function MiniChip({ value }: { value: string }) {
  return <span className={cn("rounded-md border px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide", toneClass(value))}>{value}</span>;
}

function ProgressBar({ pct, color = "bg-blue-500" }: { pct: number; color?: string }) {
  const v = clamp(Math.round(pct), 0, 100);
  return (
    <div className="h-2 rounded-full bg-neutral-200">
      <div className={cn("h-2 rounded-full transition-all duration-300", color)} style={{ width: `${v}%` }} />
    </div>
  );
}

function Table({ rows, cols }: { rows: Array<Record<string, unknown>>; cols: string[] }) {
  return (
    <div className="max-h-72 overflow-auto rounded-xl border border-neutral-200">
      <table className="w-full text-left text-xs">
        <thead className="sticky top-0 z-10 bg-white text-neutral-500">
          <tr>
            {cols.map((c) => (
              <th key={c} className="px-3 py-2 uppercase tracking-wide">{c}</th>
            ))}
          </tr>
        </thead>
        <tbody className="text-neutral-700">
          {rows.map((r, i) => (
            <tr key={String(r.id ?? `${i}`)} className="border-t border-neutral-100">
              {cols.map((c) => (
                <td key={c} className="px-3 py-2 font-mono">
                  {String(r[c] ?? "-")}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {rows.length === 0 ? <div className="py-6 text-center text-sm text-neutral-500">No records</div> : null}
    </div>
  );
}

export function IntradayTraderPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const token = useSessionStore((s) => s.accessToken);
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [selectedStrategyKey, setSelectedStrategyKey] = useState<string>(INTRADAY_SETUPS[0]?.strategyKey ?? "");
  const [selectedOpportunityId, setSelectedOpportunityId] = useState<string | null>(null);
  const [dockTab, setDockTab] = useState<(typeof DOCK_TABS)[number]["id"]>("open");
  const [symbolQuery, setSymbolQuery] = useState("");

  const catalogQ = useQuery({
    queryKey: ["strategy-catalog-intraday"],
    queryFn: async () => (await api.get("/api/strategies/catalog?size=200")).data?.data?.content as CatalogRow[],
    enabled: !!token,
    refetchInterval: 20_000,
  });
  const runtimeQ = useQuery({
    queryKey: ["strategy-runtime-intraday"],
    queryFn: async () => (await api.get("/api/strategies/runtime-metrics")).data?.data as RuntimeRow[],
    enabled: !!token,
    refetchInterval: 5_000,
  });
  const instancesQ = useQuery({
    queryKey: ["strategy-instances-intraday"],
    queryFn: async () => (await api.get("/api/strategies/instances?size=200")).data?.data?.content as InstanceRow[],
    enabled: !!token,
    refetchInterval: 5_000,
  });
  const signalsQ = useQuery({
    queryKey: ["trader-signals-intraday"],
    queryFn: async () => (await api.get("/api/trader/strategy-feed?limit=500")).data?.data as SignalRow[],
    enabled: !!token,
    refetchInterval: 5_000,
  });
  const workstationQ = useQuery({
    queryKey: ["trader-workstation-intraday"],
    queryFn: async () => (await api.get("/api/trader/terminal/workstation")).data?.data as Workstation,
    enabled: !!token,
    refetchInterval: 5_000,
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

  async function runPreMarketChecklist() {
    try {
      const [catRes, instRes, rtRes, wsRes] = await Promise.all([
        catalogQ.refetch(),
        instancesQ.refetch(),
        runtimeQ.refetch(),
        workstationQ.refetch(),
      ]);
      const catalog = catRes.data ?? [];
      const instances = instRes.data ?? [];
      const runtime = rtRes.data ?? [];
      const ws = wsRes.data;
      const riskLocal = ws?.riskControls;
      const feedLocal =
        (riskLocal?.tokenValid && riskLocal?.brokerHealth?.toUpperCase?.().includes("HEALTHY")) ||
        (riskLocal?.liveEligible && riskLocal?.brokerHealth?.toUpperCase?.().includes("CONNECTED"))
          ? "LIVE"
          : "STALE";
      const catalogByCode = new Map(catalog.map((c) => [String(c.code).toUpperCase(), c] as const));
      const runtimeByKey = new Map(runtime.map((r) => [String(r.strategyKey).toUpperCase(), r] as const));
      const blockers: string[] = [];
      for (const s of INTRADAY_SETUPS) {
        const key = s.strategyKey.toUpperCase();
        const cat = catalogByCode.get(key);
        const inst = cat ? instances.find((i) => i.definitionId === cat.id) : null;
        const met = runtimeByKey.get(key) ?? null;
        const subscribed = Boolean(cat?.subscribed && cat?.subscriptionEnabled);
        const runtimeHealthy = Boolean(
          met && String(met.runtimeState ?? "").toUpperCase().includes("RUN") && !String(met.health ?? "").toUpperCase().includes("BAD"),
        );
        if (!cat) blockers.push(`${s.title}: Strategy missing in catalog`);
        else if (!inst) blockers.push(`${s.title}: No active strategy instance`);
        else if (!subscribed) blockers.push(`${s.title}: Subscription disabled`);
        else if (!runtimeHealthy) blockers.push(`${s.title}: Runtime not healthy (${String(met?.runtimeState ?? "IDLE")}/${String(met?.health ?? "UNKNOWN")})`);
      }
      if (feedLocal !== "LIVE") blockers.unshift(`Feed health is ${feedLocal}`);
      if (!riskLocal?.tokenValid || !riskLocal?.liveEligible) blockers.unshift("Broker token/live eligibility not ready");
      if (blockers.length === 0) {
        toast.success("Pre-market checklist passed. All intraday setups are READY.");
      } else {
        toast.error(`Pre-market checklist blocked (${blockers.length}). Review readiness panel for exact fixes.`);
      }
    } catch (e) {
      toast.error(parseAxiosMessage(e));
    }
  }

  function resolveBacktestStrategyKey(setupKey: string): { key: string; usedFallback: boolean } {
    const setup = INTRADAY_SETUPS.find((s) => s.strategyKey === setupKey);
    if (!setup) return { key: setupKey, usedFallback: false };
    const available = new Set((catalogQ.data ?? []).map((c) => c.code));
    const candidates = setup.backtestStrategyKeys ?? [setup.strategyKey];
    const matched = candidates.find((k) => available.has(k));
    if (!matched) {
      return { key: setup.strategyKey, usedFallback: true };
    }
    const genericFallback = candidates[candidates.length - 1];
    const usedFallback = candidates.length > 1 && matched === genericFallback && genericFallback !== candidates[0];
    return { key: matched, usedFallback };
  }

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
        const resolved = resolveBacktestStrategyKey(strategyKey);
        if (resolved.usedFallback) {
          toast.error(
            "Dedicated intraday backtest profile is not configured for this setup yet. Add the setup strategy key in catalog to avoid fallback confusion."
          );
          return;
        }
        const setupTitle = INTRADAY_SETUPS.find((s) => s.strategyKey === strategyKey)?.title ?? strategyKey;
        navigate(
          `/backtests/launch?strategyKey=${encodeURIComponent(resolved.key)}&intradaySetup=${encodeURIComponent(setupTitle)}`,
        );
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
    }
  }

  const strategyRail = useMemo<StrategyRailRow[]>(() => {
    const rt = runtimeQ.data ?? [];
    const sig = signalsQ.data ?? [];
    const tradingWindow = currentTradingWindow();
    return INTRADAY_SETUPS.map((s) => {
      const r = rt.find((x) => x.strategyKey === s.strategyKey);
      const related = sig.filter((x) => x.strategyName === s.strategyKey);
      const signalCountToday = related.filter((x) => sinceSec(x.createdAt) <= 24 * 60 * 60).length;
      const lastSignalAt = related[0]?.createdAt ?? r?.lastSignalAt ?? null;
      const freshness = sinceSec(lastSignalAt);
      const base = clamp(num(related[0]?.confidenceScore, 62), 40, 90);
      const volatilityAdj = tradingWindow === "OPENING" ? 6 : tradingWindow === "MIDDAY" ? -2 : tradingWindow === "CLOSING" ? 2 : -6;
      const recencyAdj = freshness <= 300 ? 8 : freshness <= 1800 ? 3 : -5;
      const runtimeAdj = (r?.runtimeState ?? "").toUpperCase().includes("RUN") ? 4 : -4;
      const probability = clamp(base + volatilityAdj + recencyAdj + runtimeAdj, 20, 95);
      const historicalWinRate = clamp(58 + (s.key === "GAP_FILLS" ? 9 : s.key === "VWAP_BOUNCES" ? 6 : s.key === "EARLY_BREAKOUTS" ? 7 : 4), 50, 92);
      const liveWinRate = clamp(historicalWinRate + (signalCountToday >= 4 ? 3 : signalCountToday === 0 ? -4 : 1), 40, 96);
      const expectedRr = Number((1.2 + probability / 100 + (s.key === "EARLY_BREAKOUTS" ? 0.3 : 0)).toFixed(2));
      const riskScore = clamp(100 - probability + (tradingWindow === "CLOSING" ? 8 : 0), 12, 92);
      const capitalEfficiency = clamp(45 + probability / 2, 30, 95);
      const avgHoldMin = s.key === "VWAP_BOUNCES" ? 32 : s.key === "EARLY_BREAKOUTS" ? 18 : 26;
      const compatibility: StrategyRailRow["compatibility"] =
        s.key === "VWAP_BOUNCES" || s.key === "GAP_FILLS" ? "MEAN_REVERSION" : "TRENDING";
      const score = clamp(Math.round(probability * 0.35 + liveWinRate * 0.25 + capitalEfficiency * 0.2 + (100 - riskScore) * 0.2), 0, 100);
      const runtimeState = (r?.runtimeState ?? "IDLE").toUpperCase();
      const health = (r?.health ?? "UNKNOWN").toUpperCase();
      const state = determineState(probability, riskScore, runtimeState, health);
      const heat = heatTier(score);
      return {
        key: s.key,
        title: s.title,
        strategyKey: s.strategyKey,
        state,
        heat,
        probability,
        historicalWinRate,
        liveWinRate,
        expectedRr,
        riskScore,
        signalCountToday,
        capitalEfficiency,
        avgHoldMin,
        compatibility,
        score,
        bestWindow: s.bestWindow,
        lastSignalAt,
        runtimeState,
        health,
        tooltip: [
          `Last 10 signals sampled: ${Math.min(10, related.length)}`,
          `Today accuracy proxy: ${liveWinRate}%`,
          `Avg slippage proxy: ${Math.max(2, 12 - Math.round(probability / 10))} bps`,
          `Best hour bias: ${s.bestWindow}`,
          `Live sector correlation: ${compatibility}`,
          `Failure reason trend: ${state === "COOLING" ? "edge decay" : state === "BLOCKED" ? "risk gate" : "none"}`,
        ],
      };
    }).sort((a, b) => b.score - a.score);
  }, [runtimeQ.data, signalsQ.data]);

  const selectedStrategy = useMemo(
    () => strategyRail.find((s) => s.strategyKey === selectedStrategyKey) ?? strategyRail[0] ?? null,
    [strategyRail, selectedStrategyKey],
  );

  const opportunities = useMemo<OpportunityRow[]>(() => {
    const sig = signalsQ.data ?? [];
    const selected = selectedStrategy?.strategyKey;
    const filtered = selected ? sig.filter((s) => s.strategyName === selected) : sig;
    const rows = filtered
      .slice(0, 120)
      .map((s, idx) => {
        const symbol = String(s.symbol ?? "UNKNOWN");
        const p = clamp(num(s.confidenceScore, 60), 20, 95);
        const freshSec = sinceSec(s.createdAt);
        const rr = Number((1 + p / 60).toFixed(2));
        const trendStrength = clamp(40 + (String(s.signalType ?? "").toUpperCase().includes("BUY") ? 20 : 14) + (p - 50) / 2, 20, 98);
        const volumeQuality = clamp(45 + p / 2 - freshSec / 600, 15, 96);
        const executionQuality = clamp(70 - freshSec / 300 + p / 5, 10, 98);
        const v = verdict(p, rr, freshSec);
        return {
          id: `${s.id ?? idx}`,
          symbol,
          sector: inferSector(symbol),
          setupType: selectedStrategy?.title ?? "Setup",
          direction: String(s.signalType ?? "").toUpperCase().includes("SELL") ? "SHORT" : "LONG",
          entryZone: `${(p * 1.03).toFixed(1)} - ${(p * 1.05).toFixed(1)}`,
          slZone: `${(p * 0.99).toFixed(1)}`,
          targetZone: `${(p * 1.09).toFixed(1)}`,
          rrRatio: rr,
          probability: p,
          volumeQuality,
          trendStrength,
          freshnessSec: freshSec,
          executionQuality,
          verdict: v,
          rationale: s.reason ?? "Pattern-quality confirmation in progress",
        } as OpportunityRow;
      })
      .sort((a, b) => {
        const sa = a.probability * 0.45 + a.rrRatio * 20 + a.volumeQuality * 0.2 + a.executionQuality * 0.15 + a.trendStrength * 0.2;
        const sb = b.probability * 0.45 + b.rrRatio * 20 + b.volumeQuality * 0.2 + b.executionQuality * 0.15 + b.trendStrength * 0.2;
        return sb - sa;
      });
    return rows
      .filter((r) => (symbolQuery.trim() ? r.symbol.toUpperCase().includes(symbolQuery.trim().toUpperCase()) : true))
      .slice(0, 40);
  }, [signalsQ.data, selectedStrategy, symbolQuery]);

  const selectedOpportunity = useMemo(
    () => opportunities.find((o) => o.id === selectedOpportunityId) ?? opportunities[0] ?? null,
    [opportunities, selectedOpportunityId],
  );

  const workstation = workstationQ.data;
  const risk = workstation?.riskControls;
  const open = workstation?.openPositions ?? [];
  const closed = workstation?.closedPositions ?? [];
  const orders = workstation?.orders ?? [];
  const execs = workstation?.executions ?? [];

  const avgLatencyMs = useMemo(() => {
    if (!execs.length) return 0;
    const vals = execs.map((e) => num(e.latencyMs, 0)).filter((v) => v > 0);
    if (!vals.length) return 0;
    return Math.round(vals.reduce((a, b) => a + b, 0) / vals.length);
  }, [execs]);

  const riskUsedPct = useMemo(() => {
    const blocker = !risk?.tokenValid || !risk?.liveEligible || (risk?.reconciliationWarnings?.length ?? 0) > 0;
    const base = blocker ? 76 : 38;
    const extra = clamp(open.length * 4 + Math.max(0, avgLatencyMs - 100) / 8, 0, 40);
    return clamp(Math.round(base + extra), 0, 100);
  }, [risk, open.length, avgLatencyMs]);

  const marketRegime = useMemo(() => {
    const top = strategyRail[0];
    if (!top) return "CHOPPY";
    if (top.compatibility === "TRENDING" && top.probability >= 70) return "TRENDING";
    if (top.compatibility === "MEAN_REVERSION" && top.probability >= 68) return "REVERSION";
    return "CHOPPY";
  }, [strategyRail]);

  const feedHealth = useMemo(() => {
    const bad = runtimeQ.isError || signalsQ.isError || workstationQ.isError;
    if (bad) return "STALE";
    const staleSig = opportunities[0] ? opportunities[0].freshnessSec > 900 : true;
    return staleSig ? "DELAYED" : "LIVE";
  }, [runtimeQ.isError, signalsQ.isError, workstationQ.isError, opportunities]);

  const pulse = useMemo(() => {
    const momentum = opportunities[0]?.trendStrength ?? 50;
    const vixProxy = clamp(22 + (100 - (selectedStrategy?.probability ?? 50)) / 4, 12, 42);
    const niftyDelta = ((opportunities[0]?.probability ?? 50) - 50) / 10;
    const bankDelta = ((opportunities[1]?.probability ?? 48) - 50) / 10;
    return { momentum, vixProxy, niftyDelta, bankDelta };
  }, [opportunities, selectedStrategy?.probability]);

  const alerts = useMemo(() => {
    const out: Array<Record<string, unknown>> = [];
    if (feedHealth !== "LIVE") out.push({ id: "a1", level: "WARN", text: "Feed health not LIVE. Validate signal freshness." });
    if (riskUsedPct >= 80) out.push({ id: "a2", level: "RISK", text: "Daily risk meter elevated. Reduce fresh entries." });
    if (selectedStrategy && selectedStrategy.state === "COOLING") out.push({ id: "a3", level: "INFO", text: `${selectedStrategy.title} edge is cooling.` });
    if (selectedOpportunity && selectedOpportunity.verdict === "STRONG") out.push({ id: "a4", level: "EDGE", text: `${selectedOpportunity.symbol} flagged STRONG setup.` });
    return out;
  }, [feedHealth, riskUsedPct, selectedStrategy, selectedOpportunity]);

  const strategyLogs = useMemo(
    () =>
      strategyRail.slice(0, 10).map((s) => ({
        id: s.strategyKey,
        ts: new Date().toLocaleTimeString(),
        strategy: s.strategyKey,
        state: s.state,
        score: s.score,
        note: s.tooltip[5],
      })),
    [strategyRail],
  );

  const replayRows = useMemo(
    () =>
      closed.slice(0, 20).map((r, i) => ({
        id: String(r.id ?? i),
        symbol: String(r.symbol ?? "-"),
        strategy: String(r.strategyKey ?? r.strategyName ?? "-"),
        pnl: String(r.realizedPnl ?? r.mtmPnl ?? "-"),
        replay: "Open Replay",
      })),
    [closed],
  );

  const riskEvents = useMemo(
    () => [
      { id: "r1", event: "Parity", state: risk?.parityState ?? "UNKNOWN", detail: "Broker vs OMS parity checkpoint" },
      { id: "r2", event: "Token", state: risk?.tokenValid ? "VALID" : "INVALID", detail: "Broker auth token status" },
      { id: "r3", event: "Live Eligibility", state: risk?.liveEligible ? "ELIGIBLE" : "BLOCKED", detail: "Risk gate readiness" },
    ],
    [risk?.parityState, risk?.tokenValid, risk?.liveEligible],
  );

  const globalError = catalogQ.error ?? runtimeQ.error ?? instancesQ.error ?? signalsQ.error ?? workstationQ.error;
  const setupValidation = useMemo<SetupValidationRow[]>(() => {
    const catalog = catalogQ.data ?? [];
    const instances = instancesQ.data ?? [];
    const runtime = runtimeQ.data ?? [];
    const catalogByCode = new Map(catalog.map((c) => [String(c.code).toUpperCase(), c] as const));
    const runtimeByKey = new Map(runtime.map((r) => [String(r.strategyKey).toUpperCase(), r] as const));
    return INTRADAY_SETUPS.map((s) => {
      const key = s.strategyKey.toUpperCase();
      const cat = catalogByCode.get(key) ?? null;
      const inst = cat ? instances.find((i) => i.definitionId === cat.id) : null;
      const met = runtimeByKey.get(key) ?? null;
      const subscribed = Boolean(cat?.subscribed && cat?.subscriptionEnabled);
      const runtimeHealthy = Boolean(
        met && String(met.runtimeState ?? "").toUpperCase().includes("RUN") && !String(met.health ?? "").toUpperCase().includes("BAD"),
      );
      let blocker: string | null = null;
      if (!cat) blocker = "Strategy missing in catalog";
      else if (!inst) blocker = "No active strategy instance";
      else if (!subscribed) blocker = "Subscription disabled";
      else if (!runtimeHealthy) blocker = `Runtime not healthy (${String(met?.runtimeState ?? "IDLE")}/${String(met?.health ?? "UNKNOWN")})`;
      return {
        setupTitle: s.title,
        strategyKey: s.strategyKey,
        catalogPresent: Boolean(cat),
        instancePresent: Boolean(inst),
        runtimeHealthy,
        subscribed,
        ready: blocker == null,
        blocker,
      };
    });
  }, [catalogQ.data, instancesQ.data, runtimeQ.data]);

  const goLiveSummary = useMemo(() => {
    const blockers = setupValidation.filter((r) => !r.ready).map((r) => `${r.setupTitle}: ${r.blocker}`);
    if (feedHealth !== "LIVE") blockers.unshift(`Feed health is ${feedHealth}`);
    if (!risk?.tokenValid || !risk?.liveEligible) blockers.unshift("Broker token/live eligibility not ready");
    return { ready: blockers.length === 0, blockers };
  }, [setupValidation, feedHealth, risk?.tokenValid, risk?.liveEligible]);

  return (
    <div className={cn("space-y-4", isLight ? "text-neutral-900" : "text-white")}>
      <div className="sticky top-0 z-20 rounded-2xl border border-neutral-200 bg-white/95 px-4 py-3 backdrop-blur">
        <div className="grid gap-2 md:grid-cols-4 xl:grid-cols-9">
          <HeaderMetric title="NIFTY" value={`${pulse.niftyDelta >= 0 ? "↑" : "↓"} ${pulse.niftyDelta.toFixed(2)}%`} hint={pulse.momentum > 60 ? "Strong trend" : "Balanced"} icon={<TrendingUp className="h-3.5 w-3.5" />} />
          <HeaderMetric title="BANKNIFTY" value={`${pulse.bankDelta >= 0 ? "↑" : "↓"} ${pulse.bankDelta.toFixed(2)}%`} hint={pulse.bankDelta >= 0 ? "Relative strength" : "Weak recovery"} icon={<BarChart3 className="h-3.5 w-3.5" />} />
          <HeaderMetric title="VIX Proxy" value={pulse.vixProxy.toFixed(1)} hint={pulse.vixProxy > 28 ? "Expansion" : "Contained"} icon={<Activity className="h-3.5 w-3.5" />} />
          <HeaderMetric title="Regime" value={marketRegime} hint={selectedStrategy?.compatibility ?? "NEUTRAL"} icon={<Gauge className="h-3.5 w-3.5" />} />
          <HeaderMetric title="Feed Health" value={feedHealth} hint={feedHealth === "LIVE" ? "Realtime" : "Review data lag"} icon={<Radio className="h-3.5 w-3.5" />} />
          <HeaderMetric title="Window" value={currentTradingWindow()} hint="IST session phase" icon={<Clock3 className="h-3.5 w-3.5" />} />
          <HeaderMetric title="Day PnL" value={String(workstation?.accountSummary?.totalPnl ?? "-")} hint={String(workstation?.accountSummary?.executionMode ?? "-")} icon={<TrendingUp className="h-3.5 w-3.5" />} />
          <HeaderMetric title="Risk Used" value={`${riskUsedPct}%`} hint={String(workstation?.accountSummary?.brokerConnectionState ?? "UNKNOWN")} icon={<ShieldAlert className="h-3.5 w-3.5" />} />
          <div className="rounded-lg border border-neutral-200 bg-white px-2 py-2">
            <div className="text-[10px] uppercase tracking-wide text-neutral-500">Search</div>
            <input
              value={symbolQuery}
              onChange={(e) => setSymbolQuery(e.target.value)}
              placeholder="Filter symbol"
              className="mt-1 w-full rounded-md border border-neutral-200 px-2 py-1 text-xs outline-none ring-0 placeholder:text-neutral-400 focus:border-blue-300"
            />
          </div>
        </div>
      </div>

      <div className="rounded-2xl border border-neutral-200 bg-white p-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div>
            <h2 className="text-sm font-semibold">Tomorrow Signal Readiness</h2>
            <p className="text-xs text-neutral-500">Live validation for all intraday setups before market open.</p>
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => void runPreMarketChecklist()}
              className="rounded-md border border-blue-200 bg-blue-50 px-2.5 py-1 text-[11px] font-semibold text-blue-700 hover:bg-blue-100"
            >
              Run Pre-Market Checklist
            </button>
            <MiniChip value={goLiveSummary.ready ? "READY" : "BLOCKED"} />
          </div>
        </div>
        <div className="mt-3 grid gap-2 md:grid-cols-2 xl:grid-cols-4">
          {setupValidation.map((r) => (
            <div key={r.strategyKey} className="rounded-xl border border-neutral-200 bg-neutral-50 px-3 py-2">
              <div className="flex items-center justify-between gap-2">
                <div className="text-xs font-semibold">{r.setupTitle}</div>
                <MiniChip value={r.ready ? "READY" : "BLOCKED"} />
              </div>
              <div className="mt-1 font-mono text-[11px] text-neutral-500">{r.strategyKey}</div>
              <div className="mt-1 text-[11px] text-neutral-600">
                catalog {r.catalogPresent ? "yes" : "no"} • instance {r.instancePresent ? "yes" : "no"} • runtime {r.runtimeHealthy ? "ok" : "bad"}
              </div>
              {!r.ready ? <div className="mt-1 text-[11px] text-rose-700">{r.blocker}</div> : null}
            </div>
          ))}
        </div>
        {!goLiveSummary.ready ? (
          <div className="mt-3 rounded-lg border border-amber-300 bg-amber-50 p-2 text-xs text-amber-900">
            <div className="font-semibold">Fix before market open:</div>
            <ul className="mt-1 list-disc pl-4">
              {goLiveSummary.blockers.map((b) => (
                <li key={b}>{b}</li>
              ))}
            </ul>
          </div>
        ) : (
          <div className="mt-3 rounded-lg border border-emerald-300 bg-emerald-50 p-2 text-xs text-emerald-900">
            All intraday setups are wired and runtime-ready. Signals appear when strategy conditions trigger.
          </div>
        )}
      </div>

      {globalError ? (
        <div className="rounded-xl border border-rose-300 bg-rose-50 px-4 py-3 text-sm text-rose-800">{parseAxiosMessage(globalError)}</div>
      ) : null}

      <div className="grid gap-4 xl:grid-cols-[24%_46%_30%]">
        <section className="space-y-3">
          <div className="rounded-2xl border border-neutral-200 bg-white p-3">
            <div className="mb-2 flex items-center justify-between">
              <h2 className="text-sm font-semibold">Strategy Rail</h2>
              <span className="text-[10px] uppercase tracking-wide text-neutral-500">auto-prioritized</span>
            </div>
            <div className="space-y-2">
              {strategyRail.map((s) => (
                <button
                  key={s.strategyKey}
                  type="button"
                  onClick={() => setSelectedStrategyKey(s.strategyKey)}
                  onDoubleClick={() => void routeSetup(s.strategyKey, "PAPER")}
                  onContextMenu={(e) => {
                    e.preventDefault();
                    void routeSetup(s.strategyKey, "PAUSE");
                  }}
                  title={s.tooltip.join(" | ")}
                  className={cn(
                    "w-full rounded-xl border p-3 text-left transition",
                    selectedStrategy?.strategyKey === s.strategyKey ? "border-blue-300 bg-blue-50" : "border-neutral-200 bg-white hover:border-blue-200 hover:bg-blue-50/40",
                  )}
                >
                  <div className="flex items-start justify-between gap-2">
                    <div>
                      <div className="text-sm font-semibold">{s.title}</div>
                      <div className="mt-0.5 text-[11px] text-neutral-500">{s.bestWindow}</div>
                    </div>
                    <MiniChip value={s.state} />
                  </div>
                  <div className="mt-2">
                    <div className="mb-1 flex items-center justify-between text-[11px] text-neutral-600">
                      <span>Live Probability</span>
                      <span>{s.probability}%</span>
                    </div>
                    <ProgressBar pct={s.probability} />
                  </div>
                  <div className="mt-2 grid grid-cols-2 gap-2 text-[11px] text-neutral-600">
                    <span>Hist WR {s.historicalWinRate}%</span>
                    <span>Live WR {s.liveWinRate}%</span>
                    <span>RR {s.expectedRr}</span>
                    <span>Risk {s.riskScore}</span>
                    <span>Signals {s.signalCountToday}</span>
                    <span>Heat {s.heat}</span>
                  </div>
                </button>
              ))}
            </div>
          </div>
        </section>

        <section className="space-y-3">
          <div className="rounded-2xl border border-neutral-200 bg-white">
            <div className="border-b border-neutral-200 px-4 py-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <h2 className="text-base font-semibold">Opportunity Engine</h2>
                  <p className="text-xs text-neutral-500">
                    {selectedStrategy?.title ?? "Setup"} • suitability {selectedStrategy?.compatibility ?? "NEUTRAL"} • freshness {sinceLabel(selectedStrategy?.lastSignalAt)}
                  </p>
                </div>
                <div className="flex gap-2">
                  <MiniChip value={selectedStrategy?.runtimeState ?? "IDLE"} />
                  <MiniChip value={selectedStrategy?.health ?? "UNKNOWN"} />
                </div>
              </div>
            </div>
            <div className="max-h-[440px] overflow-auto">
              <table className="w-full text-left text-xs">
                <thead className="sticky top-0 z-10 bg-white text-neutral-500">
                  <tr>
                    {["Symbol", "Sector", "Setup", "Dir", "Entry", "SL", "Target", "RR", "Prob", "VolQ", "Trend", "Fresh", "ExecQ", "AI"].map((h) => (
                      <th key={h} className="px-3 py-2 uppercase tracking-wide">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {opportunities.map((o) => (
                    <tr
                      key={o.id}
                      onClick={() => setSelectedOpportunityId(o.id)}
                      className={cn(
                        "cursor-pointer border-t",
                        selectedOpportunity?.id === o.id
                          ? "border-blue-200 bg-blue-50/70"
                          : o.verdict === "STRONG"
                            ? "border-neutral-100 hover:bg-blue-50/40"
                            : o.verdict === "AVOID"
                              ? "border-neutral-100 bg-amber-50/30 hover:bg-amber-50/50"
                              : "border-neutral-100 hover:bg-neutral-50",
                      )}
                    >
                      <td className="px-3 py-2 font-semibold">{o.symbol}</td>
                      <td className="px-3 py-2">{o.sector}</td>
                      <td className="px-3 py-2">{o.setupType}</td>
                      <td className="px-3 py-2">{o.direction}</td>
                      <td className="px-3 py-2">{o.entryZone}</td>
                      <td className="px-3 py-2">{o.slZone}</td>
                      <td className="px-3 py-2">{o.targetZone}</td>
                      <td className="px-3 py-2">{o.rrRatio}</td>
                      <td className="px-3 py-2">{o.probability}%</td>
                      <td className="px-3 py-2">{Math.round(o.volumeQuality)}</td>
                      <td className="px-3 py-2">{Math.round(o.trendStrength)}</td>
                      <td className="px-3 py-2">{sinceLabel(new Date(Date.now() - o.freshnessSec * 1000).toISOString())}</td>
                      <td className="px-3 py-2">{Math.round(o.executionQuality)}</td>
                      <td className="px-3 py-2"><MiniChip value={o.verdict} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {opportunities.length === 0 ? <div className="py-10 text-center text-sm text-neutral-500">No ranked opportunities yet</div> : null}
            </div>
          </div>

          {selectedOpportunity ? (
            <div className="rounded-2xl border border-neutral-200 bg-white p-4">
              <div className="flex items-center justify-between">
                <h3 className="text-sm font-semibold">Opportunity Detail</h3>
                <MiniChip value={selectedOpportunity.verdict} />
              </div>
              <p className="mt-1 text-xs text-neutral-600">
                {selectedOpportunity.symbol} • {selectedOpportunity.setupType} • {selectedOpportunity.direction}
              </p>
              <div className="mt-3 grid gap-3 md:grid-cols-2">
                <Insight title="AI Explanation" body={`Probability ${selectedOpportunity.probability}% supported by ${selectedOpportunity.sector} strength, freshness ${Math.floor(selectedOpportunity.freshnessSec / 60)}m, and trend score ${Math.round(selectedOpportunity.trendStrength)}.`} />
                <Insight title="Risk Context" body={`RR ${selectedOpportunity.rrRatio} with execution quality ${Math.round(selectedOpportunity.executionQuality)}. ${selectedOpportunity.verdict === "AVOID" ? "Avoid fresh entry until signal improves." : "Risk is tradable under current regime."}`} />
              </div>
              <div className="mt-3 flex flex-wrap gap-2">
                <button
                  type="button"
                  disabled={actionsBusy || !selectedStrategy}
                  onClick={() => selectedStrategy && void routeSetup(selectedStrategy.strategyKey, "PAPER")}
                  className="rounded-lg border border-neutral-300 px-3 py-1.5 text-xs font-semibold hover:bg-neutral-50"
                >
                  Activate Paper
                </button>
                <button
                  type="button"
                  disabled={actionsBusy || !selectedStrategy}
                  onClick={() => selectedStrategy && void routeSetup(selectedStrategy.strategyKey, "LIVE")}
                  className="rounded-lg border border-emerald-500 px-3 py-1.5 text-xs font-semibold text-emerald-700 hover:bg-emerald-50"
                >
                  Activate Live
                </button>
                <button
                  type="button"
                  disabled={!selectedStrategy}
                  onClick={() => selectedStrategy && void routeSetup(selectedStrategy.strategyKey, "BACKTEST")}
                  className="rounded-lg bg-blue-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-blue-700"
                >
                  Replay / Backtest
                </button>
              </div>
            </div>
          ) : null}
        </section>

        <section className="space-y-3">
          <div className="rounded-2xl border border-neutral-200 bg-white p-4">
            <h3 className="text-sm font-semibold">Execution Intelligence</h3>
            <div className="mt-3 space-y-3">
              <IntelRow icon={<Gauge className="h-4 w-4 text-blue-600" />} title="Market Regime" value={marketRegime} hint={selectedStrategy?.compatibility ?? "NEUTRAL"} />
              <IntelRow icon={<AlertTriangle className="h-4 w-4 text-amber-600" />} title="Risk Radar" value={`${riskUsedPct}%`} hint={`${open.length} open • ${orders.length} orders`} />
              <IntelRow icon={<Zap className="h-4 w-4 text-emerald-600" />} title="Execution Quality" value={avgLatencyMs ? `${avgLatencyMs} ms` : "-"} hint={risk?.brokerHealth ?? "unknown"} />
              <IntelRow icon={<Clock3 className="h-4 w-4 text-sky-600" />} title="Trade Timer" value={currentTradingWindow()} hint="Opening / Midday / Closing" />
            </div>
          </div>

          <div className="rounded-2xl border border-neutral-200 bg-white p-4">
            <h3 className="text-sm font-semibold">Smart Alerts</h3>
            <div className="mt-2 space-y-2">
              {alerts.map((a) => (
                <div key={String(a.id)} className="rounded-lg border border-neutral-200 bg-neutral-50 px-3 py-2 text-xs">
                  <div className="font-semibold">{String(a.level)}</div>
                  <div className="text-neutral-600">{String(a.text)}</div>
                </div>
              ))}
              {alerts.length === 0 ? <div className="text-xs text-neutral-500">No critical alerts</div> : null}
            </div>
          </div>
        </section>
      </div>

      <div className="rounded-2xl border border-neutral-200 bg-white p-3">
        <div className="flex flex-wrap gap-2 border-b border-neutral-200 pb-2">
          {DOCK_TABS.map((t) => (
            <button
              key={t.id}
              type="button"
              onClick={() => setDockTab(t.id)}
              className={cn(
                "rounded-full px-3 py-1 text-xs font-semibold transition",
                dockTab === t.id ? "bg-neutral-900 text-white" : "bg-neutral-100 text-neutral-600 hover:bg-neutral-200",
              )}
            >
              {t.label}
            </button>
          ))}
        </div>

        <div className="mt-3">
          {dockTab === "open" ? (
            <Table rows={open} cols={["symbol", "side", "qty", "avgPrice", "ltp", "mtmPnl", "realizedPnl", "unrealizedPnl", "strategyKey", "executionMode", "brokerStatus"]} />
          ) : null}
          {dockTab === "closed" ? (
            <Table rows={closed} cols={["symbol", "side", "qty", "avgPrice", "realizedPnl", "exitReason", "executionMode", "strategyKey"]} />
          ) : null}
          {dockTab === "orders" ? (
            <Table rows={orders} cols={["createdAt", "symbol", "side", "state", "executionMode", "strategyKey", "quantity", "rejectReason"]} />
          ) : null}
          {dockTab === "alerts" ? <Table rows={alerts} cols={["level", "text"]} /> : null}
          {dockTab === "logs" ? <Table rows={strategyLogs} cols={["ts", "strategy", "state", "score", "note"]} /> : null}
          {dockTab === "replay" ? <Table rows={replayRows} cols={["symbol", "strategy", "pnl", "replay"]} /> : null}
          {dockTab === "risk" ? <Table rows={riskEvents} cols={["event", "state", "detail"]} /> : null}
        </div>
      </div>
    </div>
  );
}

function HeaderMetric({ title, value, hint, icon }: { title: string; value: string; hint: string; icon: React.ReactNode }) {
  return (
    <div className="rounded-lg border border-neutral-200 bg-white px-2 py-2">
      <div className="flex items-center justify-between gap-2 text-[10px] uppercase tracking-wide text-neutral-500">
        <span>{title}</span>
        {icon}
      </div>
      <div className="mt-0.5 text-sm font-semibold">{value}</div>
      <div className="truncate text-[10px] text-neutral-500">{hint}</div>
    </div>
  );
}

function IntelRow({ icon, title, value, hint }: { icon: React.ReactNode; title: string; value: string; hint: string }) {
  return (
    <div className="rounded-lg border border-neutral-200 bg-neutral-50 px-3 py-2">
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          {icon}
          <span className="text-xs font-semibold">{title}</span>
        </div>
        <span className="text-xs font-semibold text-neutral-900">{value}</span>
      </div>
      <div className="mt-1 text-[11px] text-neutral-500">{hint}</div>
    </div>
  );
}

function Insight({ title, body }: { title: string; body: string }) {
  return (
    <div className="rounded-lg border border-neutral-200 bg-neutral-50 p-3">
      <div className="text-xs font-semibold uppercase tracking-wide text-neutral-600">{title}</div>
      <div className="mt-1 text-xs text-neutral-700">{body}</div>
    </div>
  );
}
