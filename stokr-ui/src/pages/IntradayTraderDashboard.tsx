import { useMemo, useState } from "react";
import { motion } from "framer-motion";
import { useQuery } from "@tanstack/react-query";
import {
  Activity,
  AlertCircle,
  Clock3,
  DollarSign,
  Eye,
  EyeOff,
  Percent,
  RefreshCw,
  Shield,
  Target,
  TrendingDown,
  TrendingUp,
  Zap,
} from "lucide-react";
import { api, parseAxiosMessage } from "../api/client";
import { useSessionStore } from "../state/session";
import { useUiThemeStore } from "../state/uiTheme";
import { isBrokerSyncPulseLive, useBrokerPositionSync } from "../lib/hooks/useBrokerPositionSync";
import { cn } from "../lib/utils";
import { fmtDateTime, fmtNseClock } from "../lib/dateUtils";
import {
  filterBrokerMirrorPositions,
  formatInr,
  formatPnlDisplay,
  isBrokerSessionLive,
  parseMoney,
  resolveAccountPnl,
} from "../lib/moneyUtils";

type ReadinessIssue = {
  code?: string;
  message?: string;
  title?: string;
  detail?: string;
};

type Readiness = {
  overallStatus?: string;
  lastValidatedAt?: string;
  feed?: {
    status?: string;
    severity?: string;
    feedLagMs?: number;
    websocketState?: string;
    detail?: string;
  };
  broker?: {
    status?: string;
    tokenValid?: boolean;
    health?: string;
    lastSyncAt?: string;
  };
  runtime?: {
    totalStrategies?: number;
    runningStrategies?: number;
    staleStrategies?: number;
  };
  session?: {
    sessionState?: string;
    detail?: string;
  };
  strategies?: Array<{
    strategy?: string;
    strategyKey?: string;
    runtime?: string;
    status?: string;
    lastSignalTime?: string;
    historicalCoverage?: { state?: string; detail?: string };
  }>;
  warnings?: ReadinessIssue[];
  blockers?: ReadinessIssue[];
  severityCounters?: Record<string, number>;
};

type Workstation = {
  accountSummary?: {
    totalPnl?: string | number;
    unrealizedPnl?: string | number;
    realizedPnl?: string | number;
    openPositions?: number;
    activeStrategies?: number;
    brokerConnectionState?: string;
    executionMode?: string;
  };
  openPositions?: Array<Record<string, unknown>>;
  latestSignals?: Array<Record<string, unknown>>;
  executionQualityScore?: Record<string, unknown>;
  brokerTruth?: Record<string, unknown>;
};

type PortfolioOverview = Record<string, unknown>;
type ExecutionSummary = Record<string, unknown>;

type IntradayPosition = {
  symbol: string;
  side: "BUY" | "SELL";
  entryPrice: number;
  currentPrice: number;
  quantity: number;
  entryTime: string;
  unrealizedPnL: number;
  unrealizedPnLPct: number;
  targetPrice: number;
  stopLossPrice: number;
  riskRewardRatio: number;
  status: "ACTIVE" | "TARGET_HIT" | "SL_HIT" | "EXITED";
  confidence: number;
  capital: number;
  quantitySource?: string;
  brokerQty?: number | null;
};

const rupee = new Intl.NumberFormat("en-IN", {
  style: "currency",
  currency: "INR",
  maximumFractionDigits: 0,
});
const pct = new Intl.NumberFormat("en-IN", {
  style: "percent",
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

function toNumber(value: unknown): number | null {
  const n = parseMoney(value);
  return n == null || !Number.isFinite(n) ? null : n;
}

function outcomeCode(row: Record<string, unknown>): string {
  return String(row.outcomeStatus ?? row.status ?? row.signalOutcome ?? row.signalStatus ?? "").trim().toUpperCase();
}

function fmtIst(value?: string | null): string {
  if (!value) return "—";
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? value : fmtDateTime(d);
}

export function IntradayTraderDashboard() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const token = useSessionStore((s) => s.accessToken);
  const userId = useSessionStore((s) => s.userId);
  const [showDetails, setShowDetails] = useState(true);
  const [refreshInterval, setRefreshInterval] = useState(5000);

  useBrokerPositionSync(token, userId, !!token);

  const refetchMs = refreshInterval === 0 ? false : refreshInterval;

  const readinessQ = useQuery<Readiness>({
    queryKey: ["intraday-trader-readiness"],
    queryFn: async () => (await api.get("/api/trader/intraday/readiness")).data?.data as Readiness,
    refetchInterval: refetchMs,
    staleTime: 5_000,
    enabled: !!token,
    retry: 1,
  });

  const workstationQ = useQuery<Workstation>({
    queryKey: ["intraday-trader-workstation"],
    queryFn: async () => (await api.get("/api/trader/terminal/workstation")).data?.data as Workstation,
    refetchInterval: refetchMs,
    staleTime: 3_000,
    enabled: !!token,
    retry: 1,
  });

  const portfolioQ = useQuery<PortfolioOverview>({
    queryKey: ["intraday-trader-portfolio-overview"],
    queryFn: async () => (await api.get("/api/portfolio/overview")).data?.data as PortfolioOverview,
    refetchInterval: 20_000,
    staleTime: 10_000,
    enabled: !!token,
    retry: 1,
  });

  const executionSummaryQ = useQuery<ExecutionSummary>({
    queryKey: ["intraday-trader-execution-summary"],
    queryFn: async () => (await api.get("/api/trader/execution-summary")).data?.data as ExecutionSummary,
    refetchInterval: 15_000,
    staleTime: 8_000,
    enabled: !!token,
    retry: 1,
  });

  const brokerTruthQ = useQuery<Record<string, unknown>>({
    queryKey: ["intraday-trader-broker-truth"],
    queryFn: async () => (await api.get("/api/trader/terminal/broker-truth")).data?.data as Record<string, unknown>,
    refetchInterval: refetchMs,
    staleTime: 3_000,
    enabled: !!token,
    retry: 1,
  });

  const readiness = readinessQ.data;
  const workstation = workstationQ.data;
  const portfolio = portfolioQ.data;
  const executionSummary = executionSummaryQ.data;
  const brokerTruth = brokerTruthQ.data ?? workstation?.brokerTruth;

  const brokerConnected = isBrokerSessionLive(brokerTruth) || readiness?.broker?.tokenValid === true;
  const syncPulseLive = isBrokerSyncPulseLive(String(brokerTruth?.lastSyncAt ?? readiness?.broker?.lastSyncAt ?? ""));

  const openPositionRows = useMemo<IntradayPosition[]>(() => {
    const rows = workstation?.openPositions ?? [];
    const filtered = filterBrokerMirrorPositions(
      rows.map((row) => {
        const rawQty = toNumber(row.qty ?? row.quantity ?? row.openQty ?? row.netQty) ?? 0;
        const side: IntradayPosition["side"] =
          String(row.side ?? (rawQty < 0 ? "SELL" : "BUY")).toUpperCase().includes("SELL") ? "SELL" : "BUY";
        const entryPrice = toNumber(row.entryPrice ?? row.avgPrice ?? row.averagePrice ?? row.price ?? row.costPrice) ?? 0;
        const currentPrice = toNumber(row.currentPrice ?? row.ltp ?? row.lastPrice ?? row.markPrice ?? row.price) ?? entryPrice;
        const unrealizedPnL = toNumber(row.unrealizedPnl ?? row.mtmPnl ?? row.pnl) ?? 0;
        const qty = Math.abs(rawQty);
        const targetPrice = toNumber(row.targetPrice ?? row.target ?? currentPrice) ?? currentPrice;
        const stopLossPrice = toNumber(row.stopLossPrice ?? row.stopLoss ?? entryPrice) ?? entryPrice;
        const capital = toNumber(row.capital ?? row.notional ?? (qty > 0 ? qty * entryPrice : 0)) ?? 0;
        const rrDenom = Math.max(Math.abs(entryPrice - stopLossPrice), 0.01);
        const riskRewardRatio = Math.abs(targetPrice - entryPrice) / rrDenom;
        const unrealizedPnLPct = entryPrice > 0
          ? ((currentPrice - entryPrice) / entryPrice) * 100 * (side === "SELL" ? -1 : 1)
          : 0;
        const confidence = toNumber(row.confidence ?? row.confidenceScore ?? row.tradeConfidence) ?? 0;
        const quantitySource = String(row.quantitySource ?? row.pnlSource ?? "OMS").toUpperCase();
        const brokerQty = toNumber(row.brokerQty);
        const statusRaw = String(row.status ?? row.parityState ?? "ACTIVE").toUpperCase();
        const status: IntradayPosition["status"] =
          statusRaw.includes("TARGET")
            ? "TARGET_HIT"
            : statusRaw.includes("SL")
              ? "SL_HIT"
              : statusRaw.includes("EXIT")
                ? "EXITED"
                : "ACTIVE";
        const entryTime = String(row.entryTime ?? row.createdAt ?? row.updatedAt ?? "");
        return {
          symbol: String(row.symbol ?? "—"),
          side,
          entryPrice,
          currentPrice,
          quantity: qty,
          entryTime: entryTime ? fmtIst(entryTime) : "—",
          unrealizedPnL,
          unrealizedPnLPct,
          targetPrice,
          stopLossPrice,
          riskRewardRatio,
          status,
          confidence,
          capital,
          quantitySource,
          brokerQty,
        };
      }),
      brokerConnected,
    );
    return filtered.slice(0, 12);
  }, [brokerConnected, workstation?.openPositions]);

  const capitalUsed = openPositionRows.reduce((sum, row) => sum + row.capital, 0);
  const portfolioCapital = toNumber(portfolio?.totalCapital ?? portfolio?.accountValue ?? portfolio?.totalEquity);
  const capitalAvailableFromApi = toNumber(portfolio?.availableMargin ?? portfolio?.cashAvailable ?? portfolio?.availableCash);
  const totalCapitalAllocated = portfolioCapital ?? (capitalAvailableFromApi != null ? capitalUsed + capitalAvailableFromApi : Math.max(capitalUsed * 1.25, 100000));
  const capitalAvailable = capitalAvailableFromApi ?? Math.max(totalCapitalAllocated - capitalUsed, 0);
  const capitalUtilization = totalCapitalAllocated > 0 ? Math.max(0, Math.min(100, (capitalUsed / totalCapitalAllocated) * 100)) : 0;
  const positionUtilization = readiness?.runtime?.totalStrategies
    ? Math.max(0, Math.min(100, (openPositionRows.length / readiness.runtime.totalStrategies) * 100))
    : Math.max(0, Math.min(100, (openPositionRows.length / Math.max(openPositionRows.length || 1, 5)) * 100));

  const pnlSnapshot = resolveAccountPnl({
    brokerTruth,
    accountSummary: workstation?.accountSummary as Record<string, unknown> | undefined,
    openPositions: workstation?.openPositions,
    portfolioOverview: portfolio,
  });
  const totalUnrealizedPnL = pnlSnapshot.unrealized ?? toNumber(workstation?.accountSummary?.unrealizedPnl) ?? 0;
  const totalUnrealizedPnLPct = totalCapitalAllocated > 0 ? (totalUnrealizedPnL / totalCapitalAllocated) * 100 : 0;
  const targetsHit = (workstation?.latestSignals ?? []).filter((row) => ["TARGET_HIT", "TP_HIT", "TAKE_PROFIT_HIT", "CLOSED"].includes(outcomeCode(row))).length;
  const slHit = (workstation?.latestSignals ?? []).filter((row) => ["SL_HIT", "STOPLOSS_HIT", "STOP_LOSS_HIT"].includes(outcomeCode(row))).length;
  const hitRate = targetsHit + slHit > 0 ? (targetsHit / (targetsHit + slHit)) * 100 : 0;
  const avgRiskReward = openPositionRows.length > 0
    ? openPositionRows.reduce((sum, row) => sum + row.riskRewardRatio, 0) / openPositionRows.length
    : 0;
  const dayHighBalance = totalCapitalAllocated + Math.max(totalUnrealizedPnL, 0);
  const dayLowBalance = totalCapitalAllocated + Math.min(totalUnrealizedPnL, 0);
  const maxDrawdown = dayHighBalance > 0 ? Math.max(0, ((dayHighBalance - dayLowBalance) / dayHighBalance) * 100) : 0;
  const activeStrategies = readiness?.runtime?.runningStrategies ?? workstation?.accountSummary?.activeStrategies ?? 0;
  const maxPositions = readiness?.runtime?.totalStrategies ?? Math.max(openPositionRows.length, 5);
  const lastValidated = fmtIst(readiness?.lastValidatedAt ?? null);
  const feedStatus = readiness?.feed?.status ?? "SYNCING";
  const runtimeStatus = readiness?.overallStatus ?? "SYNCING";
  const executionOrders = toNumber(executionSummary?.ordersTotal) ?? 0;
  const rejectedOrders = toNumber(executionSummary?.rejectedOrders) ?? 0;
  const initialLoading =
    readinessQ.isLoading &&
    workstationQ.isLoading &&
    portfolioQ.isLoading &&
    executionSummaryQ.isLoading &&
    brokerTruthQ.isLoading;
  const hasAnyData = Boolean(readiness || workstation || portfolio || executionSummary || brokerTruth);
  const topError =
    !hasAnyData && !initialLoading
      ? parseAxiosMessage(readinessQ.error ?? workstationQ.error ?? portfolioQ.error ?? executionSummaryQ.error ?? brokerTruthQ.error)
      : null;

  const refreshAll = () => {
    void readinessQ.refetch();
    void workstationQ.refetch();
    void portfolioQ.refetch();
    void executionSummaryQ.refetch();
    void brokerTruthQ.refetch();
  };

  if (initialLoading) {
    return (
      <div className={cn("min-h-screen p-4 sm:p-6", isLight ? "bg-gradient-to-br from-slate-50 via-blue-50 to-slate-100" : "bg-neutral-950")}>
        <div className="mx-auto max-w-7xl">
          <div className={cn("rounded-2xl border p-8 text-center shadow-sm", isLight ? "border-slate-200 bg-white" : "border-neutral-800 bg-neutral-950 text-neutral-100")}>
            <div className="flex items-center justify-center gap-3">
              <RefreshCw className="h-5 w-5 animate-spin text-blue-600" />
              <p className={cn("text-sm", isLight ? "text-slate-600" : "text-neutral-400")}>Loading trader intraday data...</p>
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className={cn("min-h-screen p-4 sm:p-6", isLight ? "bg-gradient-to-br from-slate-50 via-blue-50 to-slate-100" : "bg-neutral-950")}>
      <div className="mx-auto max-w-7xl space-y-6">
        <motion.div initial={{ opacity: 0, y: -16 }} animate={{ opacity: 1, y: 0 }} className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
          <div className="space-y-1">
            <h1 className={cn("text-xl font-black sm:text-2xl md:text-3xl", isLight ? "text-slate-950" : "text-neutral-50")}>Intraday Trader Dashboard</h1>
            <p className={cn("text-sm", isLight ? "text-slate-600" : "text-neutral-400")}>
              Real-time position tracking, broker readiness, and execution health.
            </p>
            <p className={cn("text-xs", isLight ? "text-slate-500" : "text-neutral-500")}>
              Readiness {runtimeStatus} · Feed {feedStatus} · Broker {brokerConnected ? "CONNECTED" : "OFFLINE"} · Updated {lastValidated || fmtNseClock()}
            </p>
          </div>

          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={() => setShowDetails((v) => !v)}
              className={cn(
                "inline-flex items-center gap-2 rounded-lg px-4 py-2 text-sm shadow-sm transition hover:shadow-md",
                isLight ? "bg-white text-slate-900" : "border border-neutral-800 bg-neutral-900 text-neutral-100",
              )}
            >
              {showDetails ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              {showDetails ? "Hide" : "Show"}
            </button>
            <button
              type="button"
              onClick={refreshAll}
              className="inline-flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm text-white shadow-sm transition hover:bg-blue-700"
            >
              <RefreshCw className="h-4 w-4" />
              Refresh
            </button>
          </div>
        </motion.div>

        {topError ? (
          <div className="rounded-2xl border border-rose-200 bg-rose-50 p-4 text-rose-700">
            <p className="font-semibold">Unable to load live trader data</p>
            <p className="mt-1 text-sm">{topError}</p>
          </div>
        ) : null}

        <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ delay: 0.05 }} className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <div className="rounded-2xl border border-emerald-200 bg-gradient-to-br from-emerald-50 to-emerald-100 p-4 shadow-sm">
            <div className="flex items-start justify-between gap-2">
              <div className="min-w-0 flex-1">
                <p className="text-xs font-bold uppercase tracking-wide text-emerald-700">Capital Allocated</p>
                <p className="mt-2 text-lg font-black text-emerald-900 sm:text-2xl">{rupee.format(totalCapitalAllocated)}</p>
                <p className="mt-1 text-xs text-emerald-700">
                  Used: {rupee.format(capitalUsed)} ({capitalUtilization.toFixed(1)}%)
                </p>
              </div>
              <DollarSign className="h-6 w-6 shrink-0 text-emerald-600 sm:h-8 sm:w-8" />
            </div>
            <div className="mt-3 h-1.5 w-full rounded-full bg-emerald-200">
              <div className="h-full rounded-full bg-emerald-600 transition-all" style={{ width: `${capitalUtilization}%` }} />
            </div>
          </div>

          <div className="rounded-2xl border border-blue-200 bg-gradient-to-br from-blue-50 to-blue-100 p-4 shadow-sm">
            <div className="flex items-start justify-between gap-2">
              <div className="min-w-0 flex-1">
                <p className="text-xs font-bold uppercase tracking-wide text-blue-700">Active Positions</p>
                <p className="mt-2 text-lg font-black text-blue-900 sm:text-2xl">
                  {openPositionRows.length}/{maxPositions}
                </p>
                <p className="mt-1 text-xs text-blue-700">{Math.max(maxPositions - openPositionRows.length, 0)} slots available</p>
              </div>
              <Activity className="h-6 w-6 shrink-0 text-blue-600 sm:h-8 sm:w-8" />
            </div>
            <div className="mt-3 h-1.5 w-full rounded-full bg-blue-200">
              <div className="h-full rounded-full bg-blue-600 transition-all" style={{ width: `${positionUtilization}%` }} />
            </div>
          </div>

          <div
            className={`rounded-2xl border p-4 shadow-sm ${
              totalUnrealizedPnL >= 0
                ? "border-green-200 bg-gradient-to-br from-green-50 to-green-100"
                : "border-red-200 bg-gradient-to-br from-red-50 to-red-100"
            }`}
          >
            <div className="flex items-start justify-between gap-2">
              <div className="min-w-0 flex-1">
                <p className="text-xs font-bold uppercase tracking-wide text-slate-700">Unrealized P&L</p>
                <p className={`mt-2 text-lg font-black sm:text-2xl ${totalUnrealizedPnL >= 0 ? "text-green-900" : "text-red-900"}`}>
                  {formatPnlDisplay(totalUnrealizedPnL)}
                </p>
                <p className={`mt-1 text-xs ${totalUnrealizedPnL >= 0 ? "text-green-700" : "text-red-700"}`}>
                  {totalUnrealizedPnLPct >= 0 ? "+" : ""}
                  {pct.format(totalUnrealizedPnLPct / 100)}
                </p>
              </div>
              {totalUnrealizedPnL >= 0 ? <TrendingUp className="h-6 w-6 shrink-0 text-green-600 sm:h-8 sm:w-8" /> : <TrendingDown className="h-6 w-6 shrink-0 text-red-600 sm:h-8 sm:w-8" />}
            </div>
          </div>

          <div className="rounded-2xl border border-purple-200 bg-gradient-to-br from-purple-50 to-purple-100 p-4 shadow-sm">
            <div className="flex items-start justify-between gap-2">
              <div className="min-w-0 flex-1">
                <p className="text-xs font-bold uppercase tracking-wide text-purple-700">Hit Rate</p>
                <p className="mt-2 text-lg font-black text-purple-900 sm:text-2xl">{hitRate.toFixed(1)}%</p>
                <p className="mt-1 text-xs text-purple-700">
                  {targetsHit} targets / {slHit} SL
                </p>
              </div>
              <Percent className="h-6 w-6 shrink-0 text-purple-600 sm:h-8 sm:w-8" />
            </div>
          </div>
        </motion.div>

        {showDetails && openPositionRows.length > 0 ? (
          <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ delay: 0.15 }}>
            <div className="rounded-2xl border border-slate-200 bg-white p-4 sm:p-6 shadow-sm">
              <h2 className="mb-4 flex items-center gap-2 text-lg font-bold text-slate-900 sm:text-xl">
                <Zap className="h-5 w-5 text-blue-600" />
                Active Positions ({openPositionRows.length})
              </h2>

              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 sm:gap-4 md:grid-cols-2 lg:grid-cols-3">
                {openPositionRows.map((position, idx) => (
                  <motion.div
                    key={`${position.symbol}-${idx}`}
                    initial={{ opacity: 0, scale: 0.96 }}
                    animate={{ opacity: 1, scale: 1 }}
                    transition={{ delay: idx * 0.04 }}
                    className={cn(
                      "rounded-xl border-2 p-4",
                      position.status === "TARGET_HIT"
                        ? "border-green-200 bg-gradient-to-br from-green-50 to-green-50"
                        : position.status === "SL_HIT"
                          ? "border-red-200 bg-gradient-to-br from-red-50 to-red-50"
                          : position.status === "EXITED"
                            ? "border-slate-200 bg-gradient-to-br from-slate-50 to-slate-50"
                            : "border-blue-200 bg-gradient-to-br from-blue-50 to-blue-50",
                    )}
                  >
                    <div className="flex items-start justify-between gap-2">
                      <div>
                        <p className="text-lg font-bold text-slate-900">{position.symbol}</p>
                        <p className="text-xs text-slate-600">{position.side === "BUY" ? "Long" : "Short"} · Qty {position.quantity}</p>
                      </div>
                      <span
                        className={cn(
                          "inline-block rounded-full px-2 py-1 text-xs font-bold",
                          position.status === "TARGET_HIT"
                            ? "bg-green-200 text-green-700"
                            : position.status === "SL_HIT"
                              ? "bg-red-200 text-red-700"
                              : position.status === "EXITED"
                                ? "bg-slate-200 text-slate-700"
                                : "bg-blue-200 text-blue-700",
                        )}
                      >
                        {position.status}
                      </span>
                    </div>

                    <div className="mt-3 space-y-2">
                      <div className="flex justify-between text-sm">
                        <span className="text-slate-600">Entry</span>
                        <span className="font-bold text-slate-900">{formatInr(position.entryPrice)}</span>
                      </div>
                      <div className="flex justify-between text-sm">
                        <span className="text-slate-600">Current</span>
                        <span
                          className={cn(
                            "font-bold",
                            position.currentPrice > position.entryPrice
                              ? "text-green-700"
                              : position.currentPrice < position.entryPrice
                                ? "text-red-700"
                                : "text-slate-900",
                          )}
                        >
                          {formatInr(position.currentPrice)}
                        </span>
                      </div>
                      <div className="border-t border-slate-200 pt-2" />
                      <div className="flex justify-between text-xs">
                        <span className="text-slate-600">Target</span>
                        <span className="font-bold text-green-700">{formatInr(position.targetPrice)}</span>
                      </div>
                      <div className="flex justify-between text-xs">
                        <span className="text-slate-600">Stop Loss</span>
                        <span className="font-bold text-red-700">{formatInr(position.stopLossPrice)}</span>
                      </div>
                    </div>

                    <div className="mt-3 grid grid-cols-2 gap-2 rounded-lg bg-white p-2">
                      <div>
                        <p className="text-xs text-slate-600">Unrealized P&L</p>
                        <p className={cn("text-sm font-bold", position.unrealizedPnL >= 0 ? "text-green-700" : "text-red-700")}>
                          {formatPnlDisplay(position.unrealizedPnL)}
                        </p>
                        <p className={cn("text-xs", position.unrealizedPnLPct >= 0 ? "text-green-700" : "text-red-700")}>
                          {position.unrealizedPnLPct >= 0 ? "+" : ""}
                          {position.unrealizedPnLPct.toFixed(2)}%
                        </p>
                      </div>
                      <div>
                        <p className="text-xs text-slate-600">Risk:Reward</p>
                        <p className="text-sm font-bold text-blue-700">1:{position.riskRewardRatio.toFixed(2)}</p>
                        <p className="text-xs text-slate-600">Confidence {Math.round(position.confidence)}%</p>
                      </div>
                    </div>

                    <div className="mt-2 border-t border-slate-200 pt-2 text-xs text-slate-600">
                      <div className="flex justify-between">
                        <span>Capital: {formatInr(position.capital)}</span>
                        <span className="inline-flex items-center gap-1">
                          <Clock3 className="h-3 w-3" />
                          {position.entryTime}
                        </span>
                      </div>
                    </div>
                  </motion.div>
                ))}
              </div>
            </div>
          </motion.div>
        ) : showDetails ? (
          <div className="rounded-2xl border border-slate-200 bg-white p-4 sm:p-6 text-center text-sm sm:text-base text-slate-600 shadow-sm">No active positions loaded yet.</div>
        ) : null}

        {showDetails && (
          <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ delay: 0.2 }} className="grid grid-cols-1 gap-3 sm:grid-cols-3 sm:gap-4">
            <div className="rounded-2xl border border-orange-200 bg-gradient-to-br from-orange-50 to-orange-100 p-4 shadow-sm">
              <div className="flex items-start justify-between">
                <div>
                  <p className="text-xs font-bold uppercase tracking-wide text-orange-700">Avg Risk:Reward</p>
                  <p className="mt-2 text-2xl font-black text-orange-900">1:{avgRiskReward.toFixed(2)}</p>
                  <p className="mt-1 text-xs text-orange-700">Derived from open positions</p>
                </div>
                <Target className="h-8 w-8 text-orange-600" />
              </div>
            </div>

            <div className="rounded-2xl border border-indigo-200 bg-gradient-to-br from-indigo-50 to-indigo-100 p-4 shadow-sm">
              <div className="flex items-start justify-between">
                <div>
                  <p className="text-xs font-bold uppercase tracking-wide text-indigo-700">Day High Balance</p>
                  <p className="mt-2 text-2xl font-black text-indigo-900">{formatInr(dayHighBalance)}</p>
                  <p className="mt-1 text-xs text-indigo-700">Derived from current P&L</p>
                </div>
                <TrendingUp className="h-8 w-8 text-indigo-600" />
              </div>
            </div>

            <div className="rounded-2xl border border-rose-200 bg-gradient-to-br from-rose-50 to-rose-100 p-4 shadow-sm">
              <div className="flex items-start justify-between">
                <div>
                  <p className="text-xs font-bold uppercase tracking-wide text-rose-700">Max Drawdown</p>
                  <p className="mt-2 text-2xl font-black text-rose-900">{maxDrawdown.toFixed(2)}%</p>
                  <p className="mt-1 text-xs text-rose-700">Current session estimate</p>
                </div>
                <AlertCircle className="h-8 w-8 text-rose-600" />
              </div>
            </div>
          </motion.div>
        )}

        {showDetails && (
          <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ delay: 0.25 }} className="rounded-2xl border border-slate-200 bg-white p-4 sm:p-6 shadow-sm">
            <h3 className="mb-4 flex items-center gap-2 text-base font-bold text-slate-900 sm:text-lg">
              <Shield className="h-5 w-5 text-blue-600" />
              Dashboard Settings
            </h3>

            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 sm:gap-4 md:grid-cols-2 lg:grid-cols-3">
              <div>
                <label className="block text-sm font-semibold text-slate-700">Auto-Refresh Interval</label>
                <select
                  value={refreshInterval}
                  onChange={(e) => setRefreshInterval(Number(e.target.value))}
                  className="mt-2 w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm font-medium text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-600"
                >
                  <option value={2000}>Every 2 seconds (Fast)</option>
                  <option value={5000}>Every 5 seconds (Normal)</option>
                  <option value={10000}>Every 10 seconds (Slow)</option>
                  <option value={0}>Manual only</option>
                </select>
              </div>

              <div>
                <label className="block text-sm font-semibold text-slate-700">Execution Summary</label>
                <div className="mt-2 flex items-center gap-2 rounded-lg border border-slate-200 bg-slate-50 px-3 py-2">
                  <div className={cn("h-2 w-2 rounded-full", brokerConnected ? "animate-pulse bg-emerald-600" : "bg-rose-500")} />
                  <span className="text-sm font-semibold text-slate-700">
                    Orders {Math.round(executionOrders)} · Rejected {Math.round(rejectedOrders)}
                  </span>
                </div>
              </div>

              <div>
                <label className="block text-sm font-semibold text-slate-700">Status</label>
                <div className="mt-2 flex items-center gap-2 rounded-lg border border-emerald-300 bg-emerald-50 px-3 py-2">
                  <div className="h-2 w-2 rounded-full bg-emerald-600 animate-pulse" />
                  <span className="text-sm font-semibold text-emerald-700">
                    {brokerConnected ? "Live" : "Syncing"} · {syncPulseLive ? "Fresh feed" : "Waiting for sync"}
                  </span>
                </div>
              </div>
            </div>
          </motion.div>
        )}
      </div>
    </div>
  );
}
