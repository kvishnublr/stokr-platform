import { useMemo, useState } from "react";
import { motion } from "framer-motion";
import { useQuery } from "@tanstack/react-query";
import {
  TrendingUp,
  TrendingDown,
  Target,
  AlertCircle,
  BarChart3,
  Clock,
  DollarSign,
  Zap,
  Shield,
  Activity,
  RefreshCw,
  Eye,
  EyeOff,
  CheckCircle2,
  XCircle,
  Clock3,
  Percent,
} from "lucide-react";
import { parseAxiosMessage } from "../api/client";
import { useSessionStore } from "../state/session";

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
};

type IntradayMetrics = {
  totalCapitalAllocated: number;
  capitalUsed: number;
  capitalAvailable: number;
  activePositions: number;
  maxPositions: number;
  totalUnrealizedPnL: number;
  totalUnrealizedPnLPct: number;
  hitRate: number;
  targetsHit: number;
  slHit: number;
  avgRiskReward: number;
  dayHighBalance: number;
  dayLowBalance: number;
  maxDrawdown: number;
  positions: IntradayPosition[];
};

const rupee = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 0 });
const pct = new Intl.NumberFormat("en-IN", { style: "percent", minimumFractionDigits: 2, maximumFractionDigits: 2 });

export function IntradayTraderDashboard() {
  const token = useSessionStore((s) => s.accessToken);
  const [showDetails, setShowDetails] = useState(true);
  const [refreshInterval, setRefreshInterval] = useState(5000);

  const { data: metrics, isLoading, refetch } = useQuery({
    queryKey: ["intraday-metrics"],
    queryFn: async () => {
      try {
        const response = await fetch("/api/trader/intraday/metrics", {
          headers: { Authorization: `Bearer ${token}` },
        });
        if (!response.ok) throw new Error(`API error: ${response.status}`);
        return (await response.json()).data as IntradayMetrics;
      } catch (error) {
        console.error("Failed to fetch intraday metrics:", parseAxiosMessage(error));
        return null;
      }
    },
    refetchInterval: refreshInterval,
    enabled: !!token,
  });

  const stats = useMemo(() => {
    if (!metrics) return null;

    const capitalUtilization = (metrics.capitalUsed / metrics.totalCapitalAllocated) * 100;
    const positionUtilization = (metrics.activePositions / metrics.maxPositions) * 100;

    return {
      capitalUtilization,
      positionUtilization,
      remainingCapital: metrics.capitalAvailable,
      remainingPositions: metrics.maxPositions - metrics.activePositions,
    };
  }, [metrics]);

  if (!metrics) {
    return (
      <div className="min-h-screen bg-gradient-to-br from-slate-50 to-slate-100 p-6">
        <div className="mx-auto max-w-7xl">
          <div className="rounded-2xl bg-white p-8 text-center shadow-sm">
            {isLoading ? (
              <div className="flex items-center justify-center gap-3">
                <RefreshCw className="h-5 w-5 animate-spin text-blue-600" />
                <p className="text-slate-600">Loading intraday metrics...</p>
              </div>
            ) : (
              <p className="text-slate-600">Unable to load intraday data</p>
            )}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 via-blue-50 to-slate-100 p-4 sm:p-6">
      <div className="mx-auto max-w-7xl space-y-6">
        {/* HEADER */}
        <motion.div initial={{ opacity: 0, y: -20 }} animate={{ opacity: 1, y: 0 }} className="flex items-center justify-between gap-4">
          <div>
            <h1 className="text-3xl font-black text-slate-950">📊 Intraday Trader Dashboard</h1>
            <p className="mt-1 text-slate-600">Real-time position tracking, P&L monitoring, and execution analytics</p>
          </div>
          <div className="flex items-center gap-3">
            <button
              onClick={() => setShowDetails(!showDetails)}
              className="flex items-center gap-2 rounded-lg bg-white px-4 py-2 shadow-sm hover:shadow-md transition"
            >
              {showDetails ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              {showDetails ? "Hide" : "Show"}
            </button>
            <button
              onClick={() => refetch()}
              className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-white shadow-sm hover:shadow-md transition"
            >
              <RefreshCw className="h-4 w-4" />
              Refresh
            </button>
          </div>
        </motion.div>

        {/* KEY METRICS - TOP CARDS */}
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ delay: 0.1 }}
          className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4"
        >
          {/* CAPITAL STATUS */}
          <div className="rounded-2xl border-2 border-emerald-200 bg-gradient-to-br from-emerald-50 to-emerald-100 p-4 shadow-sm">
            <div className="flex items-start justify-between">
              <div>
                <p className="text-xs font-bold uppercase tracking-wide text-emerald-700">Capital Allocated</p>
                <p className="mt-2 text-2xl font-black text-emerald-900">{rupee.format(metrics.totalCapitalAllocated)}</p>
                <p className="mt-1 text-xs text-emerald-700">
                  Used: {rupee.format(metrics.capitalUsed)} ({stats?.capitalUtilization.toFixed(1)}%)
                </p>
              </div>
              <DollarSign className="h-8 w-8 text-emerald-600" />
            </div>
            <div className="mt-3 h-1.5 w-full rounded-full bg-emerald-200">
              <div
                className="h-full rounded-full bg-emerald-600 transition-all"
                style={{ width: `${stats?.capitalUtilization || 0}%` }}
              />
            </div>
          </div>

          {/* POSITIONS STATUS */}
          <div className="rounded-2xl border-2 border-blue-200 bg-gradient-to-br from-blue-50 to-blue-100 p-4 shadow-sm">
            <div className="flex items-start justify-between">
              <div>
                <p className="text-xs font-bold uppercase tracking-wide text-blue-700">Active Positions</p>
                <p className="mt-2 text-2xl font-black text-blue-900">
                  {metrics.activePositions}/{metrics.maxPositions}
                </p>
                <p className="mt-1 text-xs text-blue-700">
                  {stats?.remainingPositions} slots available
                </p>
              </div>
              <Activity className="h-8 w-8 text-blue-600" />
            </div>
            <div className="mt-3 h-1.5 w-full rounded-full bg-blue-200">
              <div
                className="h-full rounded-full bg-blue-600 transition-all"
                style={{ width: `${stats?.positionUtilization || 0}%` }}
              />
            </div>
          </div>

          {/* P&L STATUS */}
          <div
            className={`rounded-2xl border-2 p-4 shadow-sm ${
              metrics.totalUnrealizedPnL >= 0
                ? "border-green-200 bg-gradient-to-br from-green-50 to-green-100"
                : "border-red-200 bg-gradient-to-br from-red-50 to-red-100"
            }`}
          >
            <div className="flex items-start justify-between">
              <div>
                <p className="text-xs font-bold uppercase tracking-wide text-slate-700">Unrealized P&L</p>
                <p
                  className={`mt-2 text-2xl font-black ${
                    metrics.totalUnrealizedPnL >= 0 ? "text-green-900" : "text-red-900"
                  }`}
                >
                  {rupee.format(metrics.totalUnrealizedPnL)}
                </p>
                <p
                  className={`mt-1 text-xs ${
                    metrics.totalUnrealizedPnL >= 0 ? "text-green-700" : "text-red-700"
                  }`}
                >
                  {metrics.totalUnrealizedPnLPct >= 0 ? "+" : ""}{pct.format(metrics.totalUnrealizedPnLPct / 100)}
                </p>
              </div>
              {metrics.totalUnrealizedPnL >= 0 ? (
                <TrendingUp className="h-8 w-8 text-green-600" />
              ) : (
                <TrendingDown className="h-8 w-8 text-red-600" />
              )}
            </div>
          </div>

          {/* HIT RATE */}
          <div className="rounded-2xl border-2 border-purple-200 bg-gradient-to-br from-purple-50 to-purple-100 p-4 shadow-sm">
            <div className="flex items-start justify-between">
              <div>
                <p className="text-xs font-bold uppercase tracking-wide text-purple-700">Hit Rate</p>
                <p className="mt-2 text-2xl font-black text-purple-900">{metrics.hitRate.toFixed(1)}%</p>
                <p className="mt-1 text-xs text-purple-700">
                  {metrics.targetsHit} targets / {metrics.slHit} SL
                </p>
              </div>
              <Percent className="h-8 w-8 text-purple-600" />
            </div>
          </div>
        </motion.div>

        {/* ACTIVE POSITIONS GRID */}
        {showDetails && metrics.positions.length > 0 && (
          <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ delay: 0.2 }}>
            <div className="rounded-2xl border-2 border-slate-200 bg-white p-6 shadow-sm">
              <h2 className="mb-4 flex items-center gap-2 text-xl font-bold text-slate-900">
                <Zap className="h-5 w-5 text-blue-600" />
                Active Positions ({metrics.positions.length})
              </h2>

              <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
                {metrics.positions.map((position, idx) => (
                  <motion.div
                    key={idx}
                    initial={{ opacity: 0, scale: 0.95 }}
                    animate={{ opacity: 1, scale: 1 }}
                    transition={{ delay: idx * 0.05 }}
                    className={`rounded-xl border-2 p-4 ${
                      position.status === "ACTIVE"
                        ? "border-blue-200 bg-gradient-to-br from-blue-50 to-blue-50"
                        : position.status === "TARGET_HIT"
                          ? "border-green-200 bg-gradient-to-br from-green-50 to-green-50"
                          : position.status === "SL_HIT"
                            ? "border-red-200 bg-gradient-to-br from-red-50 to-red-50"
                            : "border-slate-200 bg-gradient-to-br from-slate-50 to-slate-50"
                    }`}
                  >
                    {/* HEADER */}
                    <div className="flex items-start justify-between gap-2">
                      <div>
                        <p className="text-lg font-bold text-slate-900">{position.symbol}</p>
                        <p className="text-xs text-slate-600">
                          {position.side === "BUY" ? "📈" : "📉"} {position.side}
                        </p>
                      </div>
                      <div className="text-right">
                        <span
                          className={`inline-block rounded-full px-2 py-1 text-xs font-bold ${
                            position.status === "ACTIVE"
                              ? "bg-blue-200 text-blue-700"
                              : position.status === "TARGET_HIT"
                                ? "bg-green-200 text-green-700"
                                : position.status === "SL_HIT"
                                  ? "bg-red-200 text-red-700"
                                  : "bg-slate-200 text-slate-700"
                          }`}
                        >
                          {position.status}
                        </span>
                      </div>
                    </div>

                    {/* PRICES */}
                    <div className="mt-3 space-y-2">
                      <div className="flex justify-between text-sm">
                        <span className="text-slate-600">Entry</span>
                        <span className="font-bold text-slate-900">₹{position.entryPrice.toFixed(2)}</span>
                      </div>
                      <div className="flex justify-between text-sm">
                        <span className="text-slate-600">Current</span>
                        <span
                          className={`font-bold ${
                            position.currentPrice > position.entryPrice
                              ? "text-green-700"
                              : position.currentPrice < position.entryPrice
                                ? "text-red-700"
                                : "text-slate-900"
                          }`}
                        >
                          ₹{position.currentPrice.toFixed(2)}
                        </span>
                      </div>
                      <div className="border-t border-slate-200 pt-2" />
                      <div className="flex justify-between text-xs">
                        <span className="text-slate-600">Target</span>
                        <span className="font-bold text-green-700">₹{position.targetPrice.toFixed(2)}</span>
                      </div>
                      <div className="flex justify-between text-xs">
                        <span className="text-slate-600">Stop Loss</span>
                        <span className="font-bold text-red-700">₹{position.stopLossPrice.toFixed(2)}</span>
                      </div>
                    </div>

                    {/* P&L & RISK/REWARD */}
                    <div className="mt-3 grid grid-cols-2 gap-2 rounded-lg bg-white p-2">
                      <div>
                        <p className="text-xs text-slate-600">Unrealized P&L</p>
                        <p className={`text-sm font-bold ${position.unrealizedPnL >= 0 ? "text-green-700" : "text-red-700"}`}>
                          {rupee.format(position.unrealizedPnL)}
                        </p>
                        <p className={`text-xs ${position.unrealizedPnLPct >= 0 ? "text-green-700" : "text-red-700"}`}>
                          {position.unrealizedPnLPct >= 0 ? "+" : ""}{position.unrealizedPnLPct.toFixed(2)}%
                        </p>
                      </div>
                      <div>
                        <p className="text-xs text-slate-600">Risk:Reward</p>
                        <p className="text-sm font-bold text-blue-700">1:{position.riskRewardRatio.toFixed(2)}</p>
                        <p className="text-xs text-slate-600">Confidence {position.confidence}%</p>
                      </div>
                    </div>

                    {/* QTY & CAPITAL */}
                    <div className="mt-2 border-t border-slate-200 pt-2 text-xs text-slate-600">
                      <div className="flex justify-between">
                        <span>Qty: {position.quantity}</span>
                        <span>Capital: {rupee.format(position.capital)}</span>
                      </div>
                    </div>

                    {/* TIME */}
                    <div className="mt-2 flex items-center gap-1 text-xs text-slate-500">
                      <Clock3 className="h-3 w-3" />
                      {position.entryTime}
                    </div>
                  </motion.div>
                ))}
              </div>
            </div>
          </motion.div>
        )}

        {/* RISK METRICS */}
        {showDetails && (
          <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ delay: 0.3 }} className="grid gap-4 sm:grid-cols-3">
            <div className="rounded-2xl border-2 border-orange-200 bg-gradient-to-br from-orange-50 to-orange-100 p-4 shadow-sm">
              <div className="flex items-start justify-between">
                <div>
                  <p className="text-xs font-bold uppercase tracking-wide text-orange-700">Avg Risk:Reward</p>
                  <p className="mt-2 text-2xl font-black text-orange-900">1:{metrics.avgRiskReward.toFixed(2)}</p>
                  <p className="mt-1 text-xs text-orange-700">Better risk management</p>
                </div>
                <Target className="h-8 w-8 text-orange-600" />
              </div>
            </div>

            <div className="rounded-2xl border-2 border-indigo-200 bg-gradient-to-br from-indigo-50 to-indigo-100 p-4 shadow-sm">
              <div className="flex items-start justify-between">
                <div>
                  <p className="text-xs font-bold uppercase tracking-wide text-indigo-700">Day High Balance</p>
                  <p className="mt-2 text-2xl font-black text-indigo-900">{rupee.format(metrics.dayHighBalance)}</p>
                  <p className="mt-1 text-xs text-indigo-700">Peak today</p>
                </div>
                <TrendingUp className="h-8 w-8 text-indigo-600" />
              </div>
            </div>

            <div className="rounded-2xl border-2 border-rose-200 bg-gradient-to-br from-rose-50 to-rose-100 p-4 shadow-sm">
              <div className="flex items-start justify-between">
                <div>
                  <p className="text-xs font-bold uppercase tracking-wide text-rose-700">Max Drawdown</p>
                  <p className="mt-2 text-2xl font-black text-rose-900">{metrics.maxDrawdown.toFixed(2)}%</p>
                  <p className="mt-1 text-xs text-rose-700">Today's decline</p>
                </div>
                <AlertCircle className="h-8 w-8 text-rose-600" />
              </div>
            </div>
          </motion.div>
        )}

        {/* CONTROLS */}
        {showDetails && (
          <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ delay: 0.4 }} className="rounded-2xl border-2 border-slate-200 bg-white p-6 shadow-sm">
            <h3 className="mb-4 flex items-center gap-2 text-lg font-bold text-slate-900">
              <Shield className="h-5 w-5 text-blue-600" />
              Dashboard Settings
            </h3>

            <div className="grid gap-4 sm:grid-cols-2">
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
                <label className="block text-sm font-semibold text-slate-700">Status</label>
                <div className="mt-2 flex items-center gap-2 rounded-lg border border-emerald-300 bg-emerald-50 px-3 py-2">
                  <div className="h-2 w-2 rounded-full bg-emerald-600 animate-pulse" />
                  <span className="text-sm font-semibold text-emerald-700">Live • Market Open</span>
                </div>
              </div>
            </div>
          </motion.div>
        )}
      </div>
    </div>
  );
}
