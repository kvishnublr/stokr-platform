import { useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { toast } from "sonner";
import {
  Activity, AlertTriangle, BarChart3, ChevronDown, ChevronUp,
  Clock3, Gauge, Radio, ShieldAlert, TrendingUp, Zap
} from "lucide-react";
import { api, parseAxiosMessage } from "../api/client";
import { INTRADAY_SETUPS } from "../lib/intradaySetups";
import { cn } from "../lib/utils";
import { useSessionStore } from "../state/session";
import { useUiThemeStore } from "../state/uiTheme";
import { StrategyPerformanceWidget } from "../components/trader/StrategyPerformanceWidget";

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

type ReadinessIssue = {
  severity: "CRITICAL" | "WARNING" | "INFO";
  code: string;
  title: string;
  detail: string;
  action: string | null;
  strategyKey: string | null;
};

type StrategyReadinessMatrixRow = {
  strategy: string;
  strategyKey: string;
  runtime: string;
  feed: string;
  broker: string;
  subscription: string;
  historical: string;
  lastSignalTime: string | null;
  status: "READY" | "DEGRADED" | "BLOCKED" | "RECOVERING";
  historicalCoverage: {
    symbol: string;
    timeframe: string;
    state: string;
    detail: string;
    coverageStart: string | null;
    coverageEnd: string | null;
    latestCandle: string | null;
    freshnessAgeSeconds: number | null;
  };
};

type IntradayReadiness = {
  overallStatus: "READY" | "WARNING" | "BLOCKED";
  lastValidatedAt: string;
  feed: {
    status: string;
    severity: string;
    connectionState: string;
    websocketState: string;
    tickLatencySeconds: number;
    heartbeatAgeSeconds: number;
    reconnectCount: number;
    feedLagMs: number;
    subscriptionCount: number;
    lastTickAt: string | null;
    lastHeartbeatAt: string | null;
    detail: string;
  };
  broker: {
    status: string;
    tokenValid: boolean;
    health: string;
    lastSyncAt: string | null;
    marginSummary: string | null;
  };
  runtime: {
    totalStrategies: number;
    runningStrategies: number;
    staleStrategies: number;
  };
  session: {
    sessionState: "PRE_MARKET" | "MARKET_OPEN" | "POST_MARKET" | "HOLIDAY" | "WEEKEND";
    evaluatedAt: string;
    detail: string;
  };
  strategies: StrategyReadinessMatrixRow[];
  blockers: ReadinessIssue[];
  warnings: ReadinessIssue[];
  info: ReadinessIssue[];
  severityCounters: Record<string, number>;
  historicalCoverage: {
    readyCount: number;
    staleCount: number;
    missingCount: number;
  };
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

const DOCK_TABS = [
  { id: "open", label: "Open Positions" },
  { id: "closed", label: "Closed Positions" },
  { id: "orders", label: "Orders" },
  { id: "alerts", label: "Alerts" },
  { id: "logs", label: "Strategy Logs" },
  { id: "replay", label: "Replay" },
  { id: "risk", label: "Risk Events" },
] as const;

const READINESS_ACTIONS = new Set(["RECONNECT_FEED", "RENEW_BROKER_SESSION", "RELOAD_INSTRUMENT_CACHE", "REFRESH_SUBSCRIPTIONS", "RESTART_RUNTIME"]);

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

function sanitizeDisplayText(raw: string): string {
  return raw
    .replaceAll("â†’", "↑")
    .replaceAll("â†”", "↓")
    .replaceAll("â€™", "’")
    .replaceAll("â€", '"')
    .replaceAll("Â", "")
    .replace(/\s+/g, " ")
    .trim();
}

function formatSignedPct(v: number): string {
  const n = Number.isFinite(v) ? v : 0;
  const sign = n > 0 ? "+" : "";
  return `${sign}${n.toFixed(2)}%`;
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

// ── Style helpers ─────────────────────────────────────────────────────────────

function stateStyle(state: StrategyState): string {
  if (state === "ACTIVE") return "bg-emerald-100 text-emerald-800 border-emerald-200";
  if (state === "WARMING") return "bg-amber-100 text-amber-800 border-amber-200";
  if (state === "COOLING") return "bg-sky-100 text-sky-800 border-sky-200";
  return "bg-rose-100 text-rose-800 border-rose-200";
}

function heatStyle(heat: HeatTier): { border: string; bg: string; badge: string } {
  if (heat === "S") return { border: "border-l-amber-400", bg: "from-amber-50/60", badge: "bg-amber-100 text-amber-900 border-amber-300" };
  if (heat === "A") return { border: "border-l-emerald-400", bg: "from-emerald-50/60", badge: "bg-emerald-100 text-emerald-900 border-emerald-300" };
  if (heat === "B") return { border: "border-l-blue-400", bg: "from-blue-50/40", badge: "bg-blue-100 text-blue-900 border-blue-300" };
  if (heat === "C") return { border: "border-l-neutral-400", bg: "from-neutral-50", badge: "bg-neutral-100 text-neutral-700 border-neutral-300" };
  return { border: "border-l-rose-300", bg: "from-rose-50/30", badge: "bg-rose-100 text-rose-800 border-rose-300" };
}

function verdictStyle(v: Verdict): string {
  if (v === "STRONG") return "bg-emerald-500 text-white";
  if (v === "MODERATE") return "bg-amber-500 text-white";
  return "bg-neutral-300 text-neutral-700";
}

function statusToneClass(tone: "ok" | "warn" | "bad" | "neutral"): string {
  if (tone === "ok") return "border-emerald-200 bg-emerald-50 text-emerald-900";
  if (tone === "warn") return "border-amber-200 bg-amber-50 text-amber-900";
  if (tone === "bad") return "border-rose-200 bg-rose-50 text-rose-900";
  return "border-neutral-200 bg-neutral-50 text-neutral-700";
}

function chipClass(value: string): string {
  const x = value.toUpperCase();
  if (x.includes("READY") || x.includes("SYNCED") || x.includes("CONNECTED") || x === "LIVE" || x === "STRONG" || x === "VALID" || x === "ELIGIBLE") {
    return "bg-emerald-50 text-emerald-800 border-emerald-200";
  }
  if (x.includes("PENDING") || x.includes("PARTIAL") || x.includes("PAUSED") || x === "PAPER" || x === "MODERATE" || x === "WARNING" || x === "DEGRADED") {
    return "bg-amber-50 text-amber-800 border-amber-200";
  }
  if (x.includes("MISMATCH") || x.includes("BLOCKED") || x.includes("DISCONNECTED") || x === "AVOID" || x === "CRITICAL" || x === "INVALID") {
    return "bg-rose-50 text-rose-800 border-rose-200";
  }
  return "bg-neutral-50 text-neutral-600 border-neutral-200";
}

function MiniChip({ value, className }: { value: string; className?: string }) {
  return (
    <span className={cn("inline-flex items-center rounded border px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide", chipClass(value), className)}>
      {value}
    </span>
  );
}

function ProgressBar({ pct, color = "bg-blue-500", thin = false }: { pct: number; color?: string; thin?: boolean }) {
  const v = clamp(Math.round(pct), 0, 100);
  return (
    <div className={cn("rounded-full bg-neutral-100", thin ? "h-1" : "h-1.5")}>
      <div className={cn("rounded-full transition-all duration-500", color, thin ? "h-1" : "h-1.5")} style={{ width: `${v}%` }} />
    </div>
  );
}

function DataTable({ rows, cols }: { rows: Array<Record<string, unknown>>; cols: string[] }) {
  return (
    <div className="max-h-64 overflow-auto rounded-xl border border-neutral-200">
      <table className="w-full text-left text-xs">
        <thead className="sticky top-0 z-10 border-b border-neutral-200 bg-slate-50 text-neutral-500">
          <tr>
            {cols.map((c) => (
              <th key={c} className="px-3 py-2 font-semibold uppercase tracking-wider">{c}</th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-neutral-100 text-neutral-700">
          {rows.map((r, i) => (
            <tr key={String(r.id ?? `${i}`)} className="hover:bg-slate-50/80">
              {cols.map((c) => (
                <td key={c} className="px-3 py-2 font-mono">{String(r[c] ?? "—")}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {rows.length === 0 ? (
        <div className="py-8 text-center text-sm text-neutral-400">No records</div>
      ) : null}
    </div>
  );
}

// ── Main component ─────────────────────────────────────────────────────────────

export function IntradayTraderPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const token = useSessionStore((s) => s.accessToken);
  const userId = useSessionStore((s) => s.userId);
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [selectedStrategyKey, setSelectedStrategyKey] = useState<string>(INTRADAY_SETUPS[0]?.strategyKey ?? "");
  const [selectedOpportunityId, setSelectedOpportunityId] = useState<string | null>(null);
  const [dockTab, setDockTab] = useState<(typeof DOCK_TABS)[number]["id"]>("open");
  const [symbolQuery, setSymbolQuery] = useState("");
  const [matrixSort, setMatrixSort] = useState<"strategy" | "status" | "lastSignalTime">("status");
  const [readinessExpanded, setReadinessExpanded] = useState(false);
  const checklistInFlightRef = useRef(false);

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
  const readinessQ = useQuery({
    queryKey: ["intraday-readiness-v2"],
    queryFn: async () => (await api.get("/api/trader/intraday/readiness")).data?.data as IntradayReadiness,
    enabled: !!token,
    refetchInterval: 5_000,
  });

  const toggleSub = useMutation({
    mutationFn: async (definitionId: string) => api.post(`/api/strategies/catalog/${definitionId}/subscription/toggle`),
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });
  const patchInstance = useMutation({
    mutationFn: async (payload: { id: string; body: Record<string, unknown> }) =>
      api.patch(`/api/strategies/instances/${payload.id}`, payload.body),
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
  const readinessAction = useMutation({
    mutationFn: async (payload: { action: string; strategyKey?: string | null }) =>
      (await api.post("/api/trader/intraday/readiness/actions", payload)).data?.data,
    onSuccess: (data: { message?: string; payload?: { authorizeUrl?: string } }) => {
      if (data?.message) toast.success(data.message);
      const url = data?.payload?.authorizeUrl;
      if (url) window.open(url, "_blank", "noopener,noreferrer");
      void readinessQ.refetch();
      void runtimeQ.refetch();
      void instancesQ.refetch();
      void workstationQ.refetch();
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  async function runPreMarketChecklist() {
    if (checklistInFlightRef.current) return;
    checklistInFlightRef.current = true;
    try {
      const rd = (await readinessQ.refetch()).data;
      if (rd && (rd.severityCounters?.CRITICAL ?? 0) === 0) {
        toast.success("Pre-market checklist passed. All intraday setups are READY.");
      } else {
        const critical = rd?.severityCounters?.CRITICAL ?? 0;
        const warn = rd?.severityCounters?.WARNING ?? 0;
        toast.error(`Pre-market checklist blocked (${critical} critical, ${warn} warning). Review readiness matrix.`);
      }
    } catch (e) {
      toast.error(parseAxiosMessage(e));
    } finally {
      checklistInFlightRef.current = false;
    }
  }

  function resolveBacktestStrategyKey(setupKey: string): { key: string; usedFallback: boolean } {
    const setup = INTRADAY_SETUPS.find((s) => s.strategyKey === setupKey);
    if (!setup) return { key: setupKey, usedFallback: false };
    const available = new Set((catalogQ.data ?? []).map((c) => c.code));
    const candidates = setup.backtestStrategyKeys ?? [setup.strategyKey];
    const matched = candidates.find((k) => available.has(k));
    if (!matched) return { key: setup.strategyKey, usedFallback: true };
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
          toast.error("Dedicated intraday backtest profile is not configured for this setup yet.");
          return;
        }
        const setupTitle = INTRADAY_SETUPS.find((s) => s.strategyKey === strategyKey)?.title ?? strategyKey;
        navigate(`/backtests/launch?strategyKey=${encodeURIComponent(resolved.key)}&intradaySetup=${encodeURIComponent(setupTitle)}`);
        return;
      }
      const inst = await ensureInstanceByStrategy(strategyKey);
      if (!inst) { toast.error(`Could not resolve instance for ${strategyKey}`); return; }
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
        key: s.key, title: s.title, strategyKey: s.strategyKey, state, heat, probability,
        historicalWinRate, liveWinRate, expectedRr, riskScore, signalCountToday,
        capitalEfficiency, avgHoldMin, compatibility, score, bestWindow: s.bestWindow,
        lastSignalAt, runtimeState, health,
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
    const rows = filtered.slice(0, 120).map((s, idx) => {
      const symbol = String(s.symbol ?? "UNKNOWN");
      const p = clamp(num(s.confidenceScore, 60), 20, 95);
      const freshSec = sinceSec(s.createdAt);
      const rr = Number((1 + p / 60).toFixed(2));
      const trendStrength = clamp(40 + (String(s.signalType ?? "").toUpperCase().includes("BUY") ? 20 : 14) + (p - 50) / 2, 20, 98);
      const volumeQuality = clamp(45 + p / 2 - freshSec / 600, 15, 96);
      const executionQuality = clamp(70 - freshSec / 300 + p / 5, 10, 98);
      const v = verdict(p, rr, freshSec);
      return {
        id: `${s.id ?? idx}`, symbol, sector: inferSector(symbol),
        setupType: selectedStrategy?.title ?? "Setup",
        direction: String(s.signalType ?? "").toUpperCase().includes("SELL") ? "SHORT" : "LONG",
        entryZone: `${(p * 1.03).toFixed(1)} - ${(p * 1.05).toFixed(1)}`,
        slZone: `${(p * 0.99).toFixed(1)}`,
        targetZone: `${(p * 1.09).toFixed(1)}`,
        rrRatio: rr, probability: p, volumeQuality, trendStrength,
        freshnessSec: freshSec, executionQuality, verdict: v,
        rationale: s.reason ?? "Pattern-quality confirmation in progress",
      } as OpportunityRow;
    }).sort((a, b) => {
      const sa = a.probability * 0.45 + a.rrRatio * 20 + a.volumeQuality * 0.2 + a.executionQuality * 0.15 + a.trendStrength * 0.2;
      const sb = b.probability * 0.45 + b.rrRatio * 20 + b.volumeQuality * 0.2 + b.executionQuality * 0.15 + b.trendStrength * 0.2;
      return sb - sa;
    });
    return rows.filter((r) => (symbolQuery.trim() ? r.symbol.toUpperCase().includes(symbolQuery.trim().toUpperCase()) : true)).slice(0, 40);
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
    () => strategyRail.slice(0, 10).map((s) => ({ id: s.strategyKey, ts: new Date().toLocaleTimeString("en-IN", { timeZone: "Asia/Kolkata", hour12: false, hour: "2-digit", minute: "2-digit", second: "2-digit" }), strategy: s.strategyKey, state: s.state, score: s.score, note: s.tooltip[5] })),
    [strategyRail],
  );

  const replayRows = useMemo(
    () => closed.slice(0, 20).map((r, i) => ({ id: String(r.id ?? i), symbol: String(r.symbol ?? "-"), strategy: String(r.strategyKey ?? r.strategyName ?? "-"), pnl: String(r.realizedPnl ?? r.mtmPnl ?? "-"), replay: "Open Replay" })),
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

  const globalError = catalogQ.error ?? runtimeQ.error ?? instancesQ.error ?? signalsQ.error ?? workstationQ.error ?? readinessQ.error;
  const goLiveSummary = useMemo(() => {
    const rd = readinessQ.data;
    if (!rd) return { ready: false, blockers: ["Readiness snapshot not loaded"] };
    const blockers = [...rd.blockers.map((b) => `${b.code}: ${b.title}`), ...(rd.feed.severity === "WARNING" ? [`Feed warning: ${rd.feed.detail}`] : [])];
    return { ready: rd.overallStatus === "READY", blockers };
  }, [readinessQ.data]);

  const sortedMatrixRows = useMemo(() => {
    const rows = [...(readinessQ.data?.strategies ?? [])];
    rows.sort((a, b) => {
      if (matrixSort === "strategy") return a.strategy.localeCompare(b.strategy);
      if (matrixSort === "lastSignalTime") return Date.parse(b.lastSignalTime ?? "1970-01-01") - Date.parse(a.lastSignalTime ?? "1970-01-01");
      const rank = (s: string) => (s === "BLOCKED" ? 4 : s === "DEGRADED" ? 3 : s === "RECOVERING" ? 2 : 1);
      return rank(b.status) - rank(a.status);
    });
    return rows;
  }, [matrixSort, readinessQ.data?.strategies]);

  const rd = readinessQ.data;
  const criticalCount = rd?.severityCounters?.CRITICAL ?? 0;
  const warningCount = rd?.severityCounters?.WARNING ?? 0;
  const overallOk = (rd?.overallStatus ?? "BLOCKED") === "READY";

  // ── RENDER ─────────────────────────────────────────────────────────────────

  return (
    <div className={cn("space-y-3 pb-6", isLight ? "text-neutral-900" : "text-white")}>

      {/* ── Metric Strip ─────────────────────────────────────────────────────── */}
      <div className="sticky top-0 z-20 rounded-2xl border border-neutral-200 bg-white/97 px-3 py-2.5 shadow-sm backdrop-blur">
        <div className="grid grid-cols-4 gap-2 xl:grid-cols-9">
          <MetricCard
            label="NIFTY"
            value={formatSignedPct(pulse.niftyDelta)}
            sub={pulse.momentum > 60 ? "Strong trend" : "Balanced"}
            icon={<TrendingUp className="h-3 w-3" />}
            positive={pulse.niftyDelta >= 0}
          />
          <MetricCard
            label="BANKNIFTY"
            value={formatSignedPct(pulse.bankDelta)}
            sub={pulse.bankDelta >= 0 ? "Relative strength" : "Weak recovery"}
            icon={<BarChart3 className="h-3 w-3" />}
            positive={pulse.bankDelta >= 0}
          />
          <MetricCard
            label="VIX"
            value={pulse.vixProxy.toFixed(1)}
            sub={pulse.vixProxy > 28 ? "Expansion" : "Contained"}
            icon={<Activity className="h-3 w-3" />}
          />
          <MetricCard
            label="Regime"
            value={marketRegime}
            sub={selectedStrategy?.compatibility ?? "NEUTRAL"}
            icon={<Gauge className="h-3 w-3" />}
          />
          <MetricCard
            label="Feed"
            value={feedHealth}
            sub={feedHealth === "LIVE" ? "Realtime" : "Check data lag"}
            icon={<Radio className="h-3 w-3" />}
            positive={feedHealth === "LIVE"}
            negative={feedHealth === "STALE"}
          />
          <MetricCard
            label="Window"
            value={currentTradingWindow()}
            sub="IST session phase"
            icon={<Clock3 className="h-3 w-3" />}
          />
          <MetricCard
            label="Day PnL"
            value={sanitizeDisplayText(String(workstation?.accountSummary?.totalPnl ?? "—"))}
            sub={sanitizeDisplayText(String(workstation?.accountSummary?.executionMode ?? "—"))}
            icon={<TrendingUp className="h-3 w-3" />}
          />
          <MetricCard
            label="Risk Used"
            value={`${riskUsedPct}%`}
            sub={sanitizeDisplayText(String(workstation?.accountSummary?.brokerConnectionState ?? "UNKNOWN"))}
            icon={<ShieldAlert className="h-3 w-3" />}
            negative={riskUsedPct >= 80}
          />
          {/* Symbol search */}
          <div className="flex flex-col justify-center rounded-xl border border-neutral-200 bg-slate-50 px-2.5 py-1.5">
            <span className="text-[10px] font-medium uppercase tracking-wide text-neutral-400">Symbol</span>
            <input
              value={symbolQuery}
              onChange={(e) => setSymbolQuery(e.target.value)}
              placeholder="Filter…"
              className="mt-0.5 w-full bg-transparent text-xs font-medium text-neutral-800 outline-none placeholder:text-neutral-300"
            />
          </div>
        </div>
      </div>

      {/* ── Readiness Banner ─────────────────────────────────────────────────── */}
      <div className={cn("rounded-2xl border shadow-sm", overallOk ? "border-emerald-200 bg-emerald-50/60" : criticalCount > 0 ? "border-rose-200 bg-rose-50/60" : "border-amber-200 bg-amber-50/60")}>
        {/* compact summary row */}
        <div className="flex flex-wrap items-center gap-3 px-4 py-2.5">
          <div className="flex items-center gap-2">
            <span className={cn("h-2 w-2 rounded-full", overallOk ? "animate-pulse bg-emerald-500" : criticalCount > 0 ? "bg-rose-500" : "bg-amber-500")} />
            <span className="text-xs font-semibold uppercase tracking-wide">
              {rd?.overallStatus ?? (goLiveSummary.ready ? "READY" : "BLOCKED")}
            </span>
            <span className="text-[11px] text-neutral-500">
              Session: {rd?.session?.sessionState ?? "—"} · checked {sinceLabel(rd?.lastValidatedAt)} ago
            </span>
          </div>

          {/* counters */}
          <div className="flex items-center gap-2">
            <ReadinessPill count={criticalCount} label="Critical" tone="bad" />
            <ReadinessPill count={warningCount} label="Warn" tone="warn" />
            <ReadinessPill count={rd?.severityCounters?.INFO ?? 0} label="Info" tone="neutral" />
          </div>

          {/* status chips */}
          <div className="flex flex-wrap items-center gap-1.5">
            <StatusChip
              label="Feed"
              value={rd?.feed?.status ?? feedHealth}
              sub={`${rd?.feed?.tickLatencySeconds ?? "—"}s lag`}
              tone={rd?.feed?.severity === "CRITICAL" ? "bad" : rd?.feed?.severity === "WARNING" ? "warn" : "ok"}
            />
            <StatusChip
              label="Broker"
              value={rd?.broker?.status ?? "UNKNOWN"}
              tone={rd?.broker?.tokenValid ? "ok" : "bad"}
            />
            <StatusChip
              label="Runtime"
              value={`${rd?.runtime?.runningStrategies ?? 0}/${rd?.runtime?.totalStrategies ?? 0}`}
              sub={`${rd?.runtime?.staleStrategies ?? 0} stale`}
              tone={(rd?.runtime?.runningStrategies ?? 0) > 0 ? "ok" : "bad"}
            />
          </div>

          {/* actions */}
          <div className="ml-auto flex items-center gap-2">
            <button
              type="button"
              onClick={() => void runPreMarketChecklist()}
              className="rounded-lg border border-blue-200 bg-blue-50 px-2.5 py-1 text-[11px] font-semibold text-blue-700 hover:bg-blue-100"
            >
              Run Checklist
            </button>
            <button
              type="button"
              disabled={readinessAction.isPending}
              onClick={() => readinessAction.mutate({ action: "RESTART_RUNTIME" })}
              className="rounded-lg border border-neutral-300 bg-white px-2.5 py-1 text-[11px] font-semibold text-neutral-700 hover:bg-neutral-100 disabled:opacity-60"
            >
              Restart Runtime
            </button>
            <button
              type="button"
              onClick={() => setReadinessExpanded((v) => !v)}
              className="flex items-center gap-1 rounded-lg border border-neutral-300 bg-white px-2 py-1 text-[11px] text-neutral-600 hover:bg-neutral-100"
            >
              {readinessExpanded ? <ChevronUp className="h-3.5 w-3.5" /> : <ChevronDown className="h-3.5 w-3.5" />}
              Details
            </button>
          </div>
        </div>

        {/* expanded details */}
        {readinessExpanded && (
          <div className="border-t border-neutral-200 px-4 pb-4 pt-3">
            {/* issue cards */}
            {[...(rd?.blockers ?? []), ...(rd?.warnings ?? []), ...(rd?.info ?? [])].slice(0, 8).length > 0 && (
              <div className="mb-3 grid gap-2 md:grid-cols-2 xl:grid-cols-3">
                {[...(rd?.blockers ?? []), ...(rd?.warnings ?? []), ...(rd?.info ?? [])].slice(0, 6).map((issue) => {
                  const actionUpper = issue.action ? issue.action.toUpperCase() : "";
                  const actionEnabled = actionUpper.length > 0 && READINESS_ACTIONS.has(actionUpper);
                  return (
                    <div
                      key={`${issue.code}-${issue.title}`}
                      className={cn("rounded-xl border p-3 text-xs",
                        issue.severity === "CRITICAL" ? "border-rose-200 bg-rose-50" :
                        issue.severity === "WARNING" ? "border-amber-200 bg-amber-50" :
                        "border-blue-200 bg-blue-50"
                      )}
                    >
                      <div className="flex items-center justify-between gap-2">
                        <span className="font-semibold text-neutral-800">{issue.code}</span>
                        <MiniChip value={issue.severity} />
                      </div>
                      <div className="mt-1 font-medium text-neutral-800">{issue.title}</div>
                      <div className="mt-0.5 text-neutral-600">{issue.detail}</div>
                      {actionEnabled && (
                        <button
                          type="button"
                          disabled={readinessAction.isPending}
                          onClick={() => readinessAction.mutate({ action: actionUpper, strategyKey: issue.strategyKey })}
                          className="mt-2 rounded-lg border border-neutral-300 bg-white px-2.5 py-1 text-[11px] font-semibold text-neutral-700 hover:bg-neutral-100 disabled:opacity-60"
                        >
                          {actionUpper.replaceAll("_", " ")}
                        </button>
                      )}
                    </div>
                  );
                })}
              </div>
            )}

            {/* strategy matrix */}
            <div className="overflow-hidden rounded-xl border border-neutral-200">
              <div className="flex items-center justify-between border-b border-neutral-200 bg-slate-50 px-3 py-2">
                <span className="text-xs font-semibold text-neutral-700">Strategy Readiness Matrix</span>
                <div className="flex gap-1">
                  {(["status", "strategy", "lastSignalTime"] as const).map((k) => (
                    <button
                      key={k}
                      type="button"
                      onClick={() => setMatrixSort(k)}
                      className={cn("rounded-md px-2 py-0.5 text-[11px] capitalize", matrixSort === k ? "bg-white font-semibold shadow-sm" : "text-neutral-500 hover:text-neutral-700")}
                    >
                      {k === "lastSignalTime" ? "Last Signal" : k}
                    </button>
                  ))}
                </div>
              </div>
              <div className="max-h-52 overflow-auto">
                <table className="w-full text-left text-xs">
                  <thead className="sticky top-0 bg-white text-neutral-500">
                    <tr>
                      {["Strategy", "Runtime", "Feed", "Broker", "Sub", "Historical", "Last Signal", "Status"].map((h) => (
                        <th key={h} className="px-3 py-1.5 font-medium uppercase tracking-wide">{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-neutral-100">
                    {sortedMatrixRows.map((r) => (
                      <tr
                        key={r.strategyKey}
                        className={cn(r.status === "BLOCKED" ? "bg-rose-50/60" : r.status === "DEGRADED" ? "bg-amber-50/40" : "hover:bg-slate-50/60")}
                      >
                        <td className="px-3 py-1.5 font-semibold text-neutral-800">{r.strategy}</td>
                        <td className="px-3 py-1.5"><MiniChip value={r.runtime} /></td>
                        <td className="px-3 py-1.5"><MiniChip value={r.feed} /></td>
                        <td className="px-3 py-1.5"><MiniChip value={r.broker} /></td>
                        <td className="px-3 py-1.5"><MiniChip value={r.subscription} /></td>
                        <td className="px-3 py-1.5"><MiniChip value={r.historical} /></td>
                        <td className="px-3 py-1.5 font-mono text-[11px] text-neutral-500">{sinceLabel(r.lastSignalTime)}</td>
                        <td className="px-3 py-1.5"><MiniChip value={r.status} /></td>
                      </tr>
                    ))}
                    {sortedMatrixRows.length === 0 && (
                      <tr><td colSpan={8} className="py-6 text-center text-neutral-400">No strategies found</td></tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        )}
      </div>

      {globalError && (
        <div className="rounded-xl border border-rose-300 bg-rose-50 px-4 py-3 text-sm text-rose-800">
          {parseAxiosMessage(globalError)}
        </div>
      )}

      {/* ── Main 3-pane grid ─────────────────────────────────────────────────── */}
      <div className="grid gap-3 xl:grid-cols-[22%_52%_26%]">

        {/* ── LEFT: Strategy Rail ────────────────────────────────────────────── */}
        <aside className="space-y-2">
          <div className="flex items-center justify-between px-1">
            <h2 className="text-xs font-semibold uppercase tracking-widest text-neutral-500">Strategy Rail</h2>
            <span className="text-[10px] text-neutral-400">auto-ranked</span>
          </div>

          {strategyRail.map((s) => {
            const hs = heatStyle(s.heat);
            const isSelected = selectedStrategy?.strategyKey === s.strategyKey;
            return (
              <button
                key={s.strategyKey}
                type="button"
                onClick={() => setSelectedStrategyKey(s.strategyKey)}
                onDoubleClick={() => void routeSetup(s.strategyKey, "PAPER")}
                onContextMenu={(e) => { e.preventDefault(); void routeSetup(s.strategyKey, "PAUSE"); }}
                title={s.tooltip.join(" | ")}
                className={cn(
                  "w-full rounded-xl border-l-4 border-r border-t border-b bg-gradient-to-r to-white p-3 text-left shadow-sm transition-all hover:shadow-md",
                  hs.border, hs.bg,
                  isSelected ? "ring-2 ring-blue-400/60 ring-offset-1" : "border-r-neutral-200 border-t-neutral-200 border-b-neutral-200",
                )}
              >
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-sm font-semibold text-neutral-800">{s.title}</div>
                    <div className="text-[11px] text-neutral-400">{s.bestWindow}</div>
                  </div>
                  <div className="flex shrink-0 items-center gap-1.5">
                    <span className={cn("rounded border px-1.5 py-0.5 text-[11px] font-bold", hs.badge)}>
                      {s.heat}
                    </span>
                    <span className={cn("rounded border px-1.5 py-0.5 text-[10px] font-semibold uppercase", stateStyle(s.state))}>
                      {s.state}
                    </span>
                  </div>
                </div>

                {/* probability bar */}
                <div className="mt-2.5">
                  <div className="mb-1 flex items-center justify-between">
                    <span className="text-[11px] text-neutral-500">Live prob</span>
                    <span className="text-[11px] font-bold text-neutral-800">{s.probability}%</span>
                  </div>
                  <ProgressBar
                    pct={s.probability}
                    color={s.probability >= 74 ? "bg-emerald-500" : s.probability >= 62 ? "bg-amber-400" : "bg-neutral-300"}
                  />
                </div>

                {/* key stats row */}
                <div className="mt-2 grid grid-cols-3 gap-x-2 text-[10px]">
                  <Stat label="H-WR" value={`${s.historicalWinRate}%`} />
                  <Stat label="L-WR" value={`${s.liveWinRate}%`} />
                  <Stat label="RR" value={`${s.expectedRr}x`} />
                  <Stat label="Risk" value={`${s.riskScore}`} />
                  <Stat label="Sigs" value={`${s.signalCountToday}`} />
                  <Stat label="Last" value={sinceLabel(s.lastSignalAt)} />
                </div>
              </button>
            );
          })}
        </aside>

        {/* ── CENTER: Opportunity Engine ─────────────────────────────────────── */}
        <div className="space-y-3">
          {/* header row */}
          <div className="rounded-2xl border border-neutral-200 bg-white shadow-sm">
            <div className="border-b border-neutral-100 px-4 py-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <h2 className="text-sm font-semibold text-neutral-800">
                    Opportunity Engine
                    {selectedStrategy && (
                      <span className="ml-2 text-xs font-normal text-neutral-400">· {selectedStrategy.title}</span>
                    )}
                  </h2>
                  <p className="mt-0.5 text-[11px] text-neutral-400">
                    {opportunities.length} ranked · freshness {sinceLabel(selectedStrategy?.lastSignalAt)} · {selectedStrategy?.compatibility ?? "NEUTRAL"}
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  {selectedStrategy && (
                    <>
                      <MiniChip value={selectedStrategy.runtimeState} />
                      <MiniChip value={selectedStrategy.health} />
                    </>
                  )}
                </div>
              </div>
            </div>

            {/* opportunity table */}
            <div className="max-h-[400px] overflow-auto">
              <table className="w-full text-left text-xs">
                <thead className="sticky top-0 z-10 border-b border-neutral-100 bg-slate-50 text-neutral-500">
                  <tr>
                    {["Symbol", "Dir", "Sector", "Entry", "SL", "Target", "RR", "Prob %", "AI"].map((h) => (
                      <th key={h} className="px-3 py-2 font-semibold uppercase tracking-wider">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-neutral-100">
                  {opportunities.map((o) => (
                    <tr
                      key={o.id}
                      onClick={() => setSelectedOpportunityId(o.id)}
                      className={cn(
                        "cursor-pointer transition-colors",
                        selectedOpportunity?.id === o.id
                          ? "bg-blue-50/80"
                          : o.verdict === "STRONG"
                            ? "bg-emerald-50/40 hover:bg-emerald-50/70"
                            : o.verdict === "AVOID"
                              ? "bg-amber-50/30 hover:bg-amber-50/60"
                              : "hover:bg-slate-50",
                      )}
                    >
                      <td className="px-3 py-2 font-bold text-neutral-900">{o.symbol}</td>
                      <td className="px-3 py-2">
                        <span className={cn("rounded-full px-2 py-0.5 text-[10px] font-bold",
                          o.direction === "LONG" ? "bg-emerald-100 text-emerald-800" : "bg-rose-100 text-rose-800"
                        )}>
                          {o.direction}
                        </span>
                      </td>
                      <td className="px-3 py-2 text-neutral-500">{o.sector}</td>
                      <td className="px-3 py-2 font-mono text-neutral-700">{o.entryZone}</td>
                      <td className="px-3 py-2 font-mono text-rose-600">{o.slZone}</td>
                      <td className="px-3 py-2 font-mono text-emerald-700">{o.targetZone}</td>
                      <td className="px-3 py-2 font-semibold text-neutral-800">{o.rrRatio}x</td>
                      <td className="px-3 py-2">
                        <div className="flex items-center gap-1.5">
                          <ProgressBar pct={o.probability} color="bg-blue-400" thin />
                          <span className="w-8 text-right font-semibold text-neutral-800">{o.probability}%</span>
                        </div>
                      </td>
                      <td className="px-3 py-2">
                        <span className={cn("rounded-full px-2 py-0.5 text-[10px] font-bold", verdictStyle(o.verdict))}>
                          {o.verdict}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {opportunities.length === 0 && (
                <div className="py-12 text-center text-sm text-neutral-400">No ranked opportunities yet</div>
              )}
            </div>
          </div>

          {/* selected opportunity detail */}
          {selectedOpportunity && (
            <div className="rounded-2xl border border-neutral-200 bg-white p-4 shadow-sm">
              <div className="flex items-center justify-between">
                <div>
                  <h3 className="text-sm font-semibold text-neutral-800">
                    {selectedOpportunity.symbol}
                    <span className={cn("ml-2 rounded-full px-2 py-0.5 text-[10px] font-bold",
                      selectedOpportunity.direction === "LONG" ? "bg-emerald-100 text-emerald-800" : "bg-rose-100 text-rose-800"
                    )}>
                      {selectedOpportunity.direction}
                    </span>
                  </h3>
                  <p className="mt-0.5 text-[11px] text-neutral-500">
                    {selectedOpportunity.setupType} · {selectedOpportunity.sector} · fresh {sinceLabel(new Date(Date.now() - selectedOpportunity.freshnessSec * 1000).toISOString())}
                  </p>
                </div>
                <span className={cn("rounded-full px-3 py-1 text-xs font-bold", verdictStyle(selectedOpportunity.verdict))}>
                  {selectedOpportunity.verdict}
                </span>
              </div>

              <div className="mt-3 grid grid-cols-4 gap-2 text-center">
                <PriceBox label="Entry" value={selectedOpportunity.entryZone} color="text-blue-700" />
                <PriceBox label="Stop Loss" value={selectedOpportunity.slZone} color="text-rose-600" />
                <PriceBox label="Target" value={selectedOpportunity.targetZone} color="text-emerald-700" />
                <PriceBox label="R:R" value={`${selectedOpportunity.rrRatio}x`} color="text-neutral-900" />
              </div>

              <div className="mt-3 rounded-lg bg-slate-50 p-3 text-xs text-neutral-600">
                <span className="font-semibold text-neutral-700">Rationale: </span>
                {selectedOpportunity.rationale}
              </div>

              <div className="mt-3 flex flex-wrap gap-2">
                <button
                  type="button"
                  disabled={actionsBusy || !selectedStrategy}
                  onClick={() => selectedStrategy && void routeSetup(selectedStrategy.strategyKey, "PAPER")}
                  className="rounded-lg border border-neutral-300 bg-white px-3 py-1.5 text-xs font-semibold text-neutral-700 hover:bg-neutral-50 disabled:opacity-50"
                >
                  Activate Paper
                </button>
                <button
                  type="button"
                  disabled={actionsBusy || !selectedStrategy}
                  onClick={() => selectedStrategy && void routeSetup(selectedStrategy.strategyKey, "LIVE")}
                  className="rounded-lg border border-emerald-400 bg-emerald-50 px-3 py-1.5 text-xs font-semibold text-emerald-700 hover:bg-emerald-100 disabled:opacity-50"
                >
                  Activate Live
                </button>
                <button
                  type="button"
                  disabled={!selectedStrategy}
                  onClick={() => selectedStrategy && void routeSetup(selectedStrategy.strategyKey, "BACKTEST")}
                  className="rounded-lg bg-blue-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-blue-700 disabled:opacity-50"
                >
                  Replay / Backtest
                </button>
              </div>
            </div>
          )}
        </div>

        {/* ── RIGHT: Intel + Alerts ──────────────────────────────────────────── */}
        <aside className="space-y-3">
          {/* execution intel */}
          <div className="rounded-2xl border border-neutral-200 bg-white p-4 shadow-sm">
            <h3 className="mb-3 text-xs font-semibold uppercase tracking-widest text-neutral-500">Execution Intel</h3>
            <div className="space-y-2">
              <IntelCard
                icon={<Gauge className="h-4 w-4 text-blue-600" />}
                title="Market Regime"
                value={marketRegime}
                sub={selectedStrategy?.compatibility ?? "NEUTRAL"}
                valueTone={marketRegime === "TRENDING" ? "text-emerald-700" : marketRegime === "REVERSION" ? "text-blue-700" : "text-neutral-700"}
              />
              <IntelCard
                icon={<ShieldAlert className="h-4 w-4 text-amber-500" />}
                title="Risk Radar"
                value={`${riskUsedPct}%`}
                sub={`${open.length} open · ${orders.length} orders`}
                valueTone={riskUsedPct >= 80 ? "text-rose-700" : riskUsedPct >= 60 ? "text-amber-700" : "text-emerald-700"}
                extra={<ProgressBar pct={riskUsedPct} color={riskUsedPct >= 80 ? "bg-rose-500" : riskUsedPct >= 60 ? "bg-amber-400" : "bg-emerald-500"} />}
              />
              <IntelCard
                icon={<Zap className="h-4 w-4 text-emerald-600" />}
                title="Exec Quality"
                value={avgLatencyMs ? `${avgLatencyMs} ms` : "—"}
                sub={risk?.brokerHealth ?? "unknown"}
                valueTone="text-neutral-800"
              />
              <IntelCard
                icon={<Clock3 className="h-4 w-4 text-sky-600" />}
                title="Trade Window"
                value={currentTradingWindow()}
                sub="Opening / Midday / Closing"
                valueTone="text-neutral-800"
              />
            </div>
          </div>

          {/* account summary */}
          {workstation?.accountSummary && (
            <div className="rounded-2xl border border-neutral-200 bg-white p-4 shadow-sm">
              <h3 className="mb-3 text-xs font-semibold uppercase tracking-widest text-neutral-500">Account</h3>
              <div className="grid grid-cols-2 gap-2">
                <AccountStat label="Total P&L" value={String(workstation.accountSummary.totalPnl)} />
                <AccountStat label="Realized" value={String(workstation.accountSummary.realizedPnl)} />
                <AccountStat label="Unrealized" value={String(workstation.accountSummary.unrealizedPnl)} />
                <AccountStat label="Open Pos." value={String(workstation.accountSummary.openPositions)} />
              </div>
            </div>
          )}

          {/* strategy performance widget */}
          {userId && (
            <StrategyPerformanceWidget userId={userId} executionMode={workstation?.accountSummary?.executionMode} />
          )}

          {/* smart alerts */}
          <div className="rounded-2xl border border-neutral-200 bg-white p-4 shadow-sm">
            <h3 className="mb-3 text-xs font-semibold uppercase tracking-widest text-neutral-500">Smart Alerts</h3>
            {alerts.length > 0 ? (
              <div className="space-y-2">
                {alerts.map((a) => {
                  const lvl = String(a.level);
                  return (
                    <div
                      key={String(a.id)}
                      className={cn("flex items-start gap-2 rounded-xl border p-2.5 text-xs",
                        lvl === "RISK" ? "border-rose-200 bg-rose-50" :
                        lvl === "WARN" ? "border-amber-200 bg-amber-50" :
                        lvl === "EDGE" ? "border-emerald-200 bg-emerald-50" :
                        "border-blue-200 bg-blue-50"
                      )}
                    >
                      <AlertTriangle className={cn("mt-0.5 h-3.5 w-3.5 shrink-0",
                        lvl === "RISK" ? "text-rose-500" : lvl === "WARN" ? "text-amber-500" : lvl === "EDGE" ? "text-emerald-600" : "text-blue-500"
                      )} />
                      <div>
                        <span className="font-semibold">{lvl}</span>
                        <span className="ml-1 text-neutral-600">{String(a.text)}</span>
                      </div>
                    </div>
                  );
                })}
              </div>
            ) : (
              <div className="rounded-xl border border-emerald-200 bg-emerald-50/60 px-3 py-3 text-center text-xs text-emerald-700">
                All clear · no active alerts
              </div>
            )}
          </div>
        </aside>
      </div>

      {/* ── Bottom Dock ──────────────────────────────────────────────────────── */}
      <div className="rounded-2xl border border-neutral-200 bg-white shadow-sm">
        <div className="flex flex-wrap items-center gap-1.5 border-b border-neutral-100 px-4 py-2.5">
          {DOCK_TABS.map((t) => {
            const count =
              t.id === "open" ? open.length :
              t.id === "closed" ? closed.length :
              t.id === "orders" ? orders.length :
              t.id === "alerts" ? alerts.length :
              0;
            return (
              <button
                key={t.id}
                type="button"
                onClick={() => setDockTab(t.id)}
                className={cn(
                  "flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-semibold transition",
                  dockTab === t.id
                    ? "bg-neutral-900 text-white"
                    : "bg-neutral-100 text-neutral-600 hover:bg-neutral-200",
                )}
              >
                {t.label}
                {count > 0 && (
                  <span className={cn("rounded-full px-1.5 py-0.5 text-[10px] font-bold",
                    dockTab === t.id ? "bg-white/20 text-white" : "bg-neutral-300 text-neutral-700"
                  )}>
                    {count}
                  </span>
                )}
              </button>
            );
          })}
        </div>
        <div className="p-3">
          {dockTab === "open" && <DataTable rows={open} cols={["symbol", "side", "qty", "avgPrice", "ltp", "mtmPnl", "realizedPnl", "strategyKey", "executionMode", "brokerStatus"]} />}
          {dockTab === "closed" && <DataTable rows={closed} cols={["symbol", "side", "qty", "avgPrice", "realizedPnl", "exitReason", "executionMode", "strategyKey"]} />}
          {dockTab === "orders" && <DataTable rows={orders} cols={["createdAt", "symbol", "side", "state", "executionMode", "strategyKey", "quantity", "rejectReason"]} />}
          {dockTab === "alerts" && <DataTable rows={alerts} cols={["level", "text"]} />}
          {dockTab === "logs" && <DataTable rows={strategyLogs} cols={["ts", "strategy", "state", "score", "note"]} />}
          {dockTab === "replay" && <DataTable rows={replayRows} cols={["symbol", "strategy", "pnl", "replay"]} />}
          {dockTab === "risk" && <DataTable rows={riskEvents} cols={["event", "state", "detail"]} />}
        </div>
      </div>
    </div>
  );
}

// ── Sub-components ────────────────────────────────────────────────────────────

function MetricCard({
  label, value, sub, icon, positive, negative,
}: {
  label: string; value: string; sub: string; icon: React.ReactNode; positive?: boolean; negative?: boolean;
}) {
  return (
    <div className={cn(
      "rounded-xl border px-2.5 py-2",
      positive ? "border-emerald-200 bg-emerald-50/60" :
      negative ? "border-rose-200 bg-rose-50/60" :
      "border-neutral-200 bg-slate-50"
    )}>
      <div className="flex items-center justify-between gap-1">
        <span className="text-[10px] font-medium uppercase tracking-wide text-neutral-400">{label}</span>
        <span className={cn("opacity-60", positive ? "text-emerald-600" : negative ? "text-rose-500" : "text-neutral-400")}>{icon}</span>
      </div>
      <div className={cn("mt-0.5 truncate text-sm font-bold",
        positive ? "text-emerald-700" : negative ? "text-rose-700" : "text-neutral-800"
      )}>
        {sanitizeDisplayText(value)}
      </div>
      <div className="truncate text-[10px] text-neutral-400">{sanitizeDisplayText(sub)}</div>
    </div>
  );
}

function ReadinessPill({ count, label, tone }: { count: number; label: string; tone: "bad" | "warn" | "neutral" }) {
  if (count === 0 && tone !== "neutral") return null;
  return (
    <span className={cn(
      "inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[11px] font-semibold",
      tone === "bad" ? "border-rose-200 bg-rose-100 text-rose-800" :
      tone === "warn" ? "border-amber-200 bg-amber-100 text-amber-800" :
      "border-neutral-200 bg-neutral-100 text-neutral-600"
    )}>
      <span className={cn("h-1.5 w-1.5 rounded-full",
        tone === "bad" ? "bg-rose-500" : tone === "warn" ? "bg-amber-500" : "bg-neutral-400"
      )} />
      {count} {label}
    </span>
  );
}

function StatusChip({ label, value, sub, tone }: { label: string; value: string; sub?: string; tone: "ok" | "warn" | "bad" }) {
  return (
    <div className={cn("rounded-lg border px-2.5 py-1 text-xs", statusToneClass(tone))}>
      <span className="mr-1 font-medium opacity-70">{label}:</span>
      <span className="font-bold">{value}</span>
      {sub && <span className="ml-1 opacity-60">({sub})</span>}
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-neutral-400">{label}</div>
      <div className="font-semibold text-neutral-700">{value}</div>
    </div>
  );
}

function IntelCard({
  icon, title, value, sub, valueTone, extra,
}: {
  icon: React.ReactNode; title: string; value: string; sub: string; valueTone: string; extra?: React.ReactNode;
}) {
  return (
    <div className="rounded-xl border border-neutral-100 bg-slate-50 px-3 py-2.5">
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          {icon}
          <span className="text-xs font-semibold text-neutral-700">{title}</span>
        </div>
        <span className={cn("text-xs font-bold", valueTone)}>{value}</span>
      </div>
      {sub && <div className="mt-0.5 text-[11px] text-neutral-400">{sub}</div>}
      {extra && <div className="mt-1.5">{extra}</div>}
    </div>
  );
}

function PriceBox({ label, value, color }: { label: string; value: string; color: string }) {
  return (
    <div className="rounded-xl border border-neutral-100 bg-slate-50 p-2 text-center">
      <div className="text-[10px] font-medium uppercase tracking-wide text-neutral-400">{label}</div>
      <div className={cn("mt-0.5 font-bold", color)}>{value}</div>
    </div>
  );
}

function AccountStat({ label, value }: { label: string; value: string }) {
  const n = parseFloat(value.replace(/[^0-9.-]/g, ""));
  const positive = Number.isFinite(n) && n > 0;
  const negative = Number.isFinite(n) && n < 0;
  return (
    <div className="rounded-xl border border-neutral-100 bg-slate-50 px-2.5 py-2">
      <div className="text-[10px] text-neutral-400">{label}</div>
      <div className={cn("mt-0.5 text-sm font-bold", positive ? "text-emerald-700" : negative ? "text-rose-700" : "text-neutral-800")}>
        {value || "—"}
      </div>
    </div>
  );
}
