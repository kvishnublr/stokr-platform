import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useSessionStore } from "../state/session";
import { api } from "../api/client";
import { cn } from "../lib/utils";
import { useState } from "react";
import { toast } from "sonner";

interface V5Status {
  dailyPnl: number;
  dailyLossLimit: number;
  dailyLossLimitHit: boolean;
  consecutiveLossesHit: boolean;
  openTradeCount: number;
  todayTradeCount: number;
  todayWins: number;
  todayClosed: number;
  winRate: number;
}

interface V5Trade {
  id: string;
  symbol: string;
  side: string;
  quantity: number;
  entryPrice: number;
  currentPrice?: number;
  stopLoss: number;
  status: string;
  strategy: string;
  realizedPnl?: number;
  unrealizedPnl?: number;
  enteredAt: string;
  closedAt?: string;
  exitReason?: string;
}

interface V5Alert {
  id: string;
  level: string;
  category: string;
  message: string;
  createdAt: string;
}

interface V5AdminAction {
  id: string;
  action: string;
  createdAt: string;
}

interface V5Config {
  capitalPerTrade: number;
  maxConcurrentTrades: number;
  dailyLossLimit: number;
  consecutiveLossesHalt: number;
  disasterStopPct: number;
}

interface V5Dashboard {
  timestamp: string;
  tradingPaused: boolean;
  liveEnabled: boolean;
  orbEnabled: boolean;
  brokerCircuitOpen: boolean;
  marketHours: boolean;
  status: V5Status;
  openTrades: V5Trade[];
  todayTrades: V5Trade[];
  alerts: V5Alert[];
  adminActions: V5AdminAction[];
  config: V5Config;
}

function fmtRupee(n: number | null | undefined): string {
  if (n == null) return "—";
  const sign = n < 0 ? "-" : n > 0 ? "+" : "";
  return `${sign}₹${Math.abs(n).toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function fmtTime(iso: string): string {
  try {
    return new Date(iso).toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
  } catch { return iso; }
}

function fmtDateTime(iso: string): string {
  try {
    return new Date(iso).toLocaleString("en-IN", { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" });
  } catch { return iso; }
}

function StatusBadge({ on, label, color = "green" }: { on: boolean; label: string; color?: "green" | "red" | "yellow" | "blue" }) {
  const colors = {
    green: on ? "bg-green-100 text-green-800 border-green-200" : "bg-gray-100 text-gray-500 border-gray-200",
    red: on ? "bg-red-100 text-red-800 border-red-200" : "bg-gray-100 text-gray-500 border-gray-200",
    yellow: on ? "bg-yellow-100 text-yellow-800 border-yellow-200" : "bg-gray-100 text-gray-500 border-gray-200",
    blue: on ? "bg-blue-100 text-blue-800 border-blue-200" : "bg-gray-100 text-gray-500 border-gray-200",
  };
  return (
    <span className={cn("inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-semibold", colors[color])}>
      <span className={cn("h-1.5 w-1.5 rounded-full", on ? (color === "green" ? "bg-green-500" : color === "red" ? "bg-red-500" : color === "yellow" ? "bg-yellow-500" : "bg-blue-500") : "bg-gray-400")} />
      {label}
    </span>
  );
}

export function V5DashboardPage() {
  const username = useSessionStore((s) => s.username);
  const queryClient = useQueryClient();

  const { data: dash, isLoading, error } = useQuery<V5Dashboard>({
    queryKey: ["v5-dashboard"],
    queryFn: async () => (await api.get("/api/v5/dashboard")).data,
    refetchInterval: 5000,
    staleTime: 2000,
    retry: 2,
    retryDelay: 3000,
  });

  const haltMut = useMutation({
    mutationFn: () => api.post("/api/v5/halt"),
    onSuccess: () => { toast.success("Trading HALTED"); queryClient.invalidateQueries({ queryKey: ["v5-dashboard"] }); },
    onError: () => toast.error("Failed to halt trading"),
  });

  const resumeMut = useMutation({
    mutationFn: () => api.post("/api/v5/resume"),
    onSuccess: () => { toast.success("Trading RESUMED"); queryClient.invalidateQueries({ queryKey: ["v5-dashboard"] }); },
    onError: () => toast.error("Failed to resume trading"),
  });

  const toggleOrbMut = useMutation({
    mutationFn: (enabled: boolean) => api.post(`/api/v5/strategy/toggle?enabled=${enabled}`),
    onSuccess: () => { toast.success("ORB toggled"); queryClient.invalidateQueries({ queryKey: ["v5-dashboard"] }); },
  });

  const closeTradesMut = useMutation({
    mutationFn: (id: string) => api.post(`/api/v5/trade/${id}/close`),
    onSuccess: () => { toast.success("Trade closed"); queryClient.invalidateQueries({ queryKey: ["v5-dashboard"] }); },
    onError: () => toast.error("Failed to close trade"),
  });

  if (isLoading) {
    return (
      <div className="flex h-64 items-center justify-center text-sm text-gray-500">
        Loading v5 dashboard...
      </div>
    );
  }

  if (error || !dash) {
    return (
      <div className="rounded-lg border border-orange-200 bg-orange-50 p-6 text-sm text-orange-700">
        v5 trading API is not available — this dashboard requires the v5 backend endpoints
        (<code className="mx-1 rounded bg-orange-100 px-1">/api/v5/dashboard</code>).
        You can still access the full admin panel via the sidebar.
        {error && <details className="mt-2"><summary className="cursor-pointer text-xs">Error details</summary><pre className="mt-1 text-xs">{error instanceof Error ? error.message : "Unknown error"}</pre></details>}
      </div>
    );
  }

  const pnlColor = dash.status.dailyPnl > 0 ? "text-green-600" : dash.status.dailyPnl < 0 ? "text-red-600" : "text-gray-600";

  return (
    <div className="flex flex-col gap-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">V5 Trading Dashboard</h1>
          <p className="text-sm text-gray-500">Welcome back, {username || "trader"} — Last sync: {fmtTime(dash.timestamp)}</p>
        </div>
        <div className="flex items-center gap-2">
          {dash.tradingPaused ? (
            <button onClick={() => resumeMut.mutate()} disabled={resumeMut.isPending}
              className="rounded-lg bg-green-600 px-4 py-2 text-sm font-bold text-white hover:bg-green-700 disabled:opacity-50">
              Resume Trading
            </button>
          ) : (
            <button onClick={() => haltMut.mutate()} disabled={haltMut.isPending}
              className="rounded-lg bg-red-600 px-4 py-2 text-sm font-bold text-white hover:bg-red-700 disabled:opacity-50">
              HALT Trading
            </button>
          )}
        </div>
      </div>

      {/* System Status Badges */}
      <div className="flex flex-wrap gap-2">
        <StatusBadge on={!dash.tradingPaused} label={dash.tradingPaused ? "PAUSED" : "TRADING"} color={dash.tradingPaused ? "red" : "green"} />
        <StatusBadge on={dash.liveEnabled} label={dash.liveEnabled ? "LIVE" : "PAPER"} color={dash.liveEnabled ? "red" : "blue"} />
        <StatusBadge on={dash.orbEnabled} label={dash.orbEnabled ? "ORB ON" : "ORB OFF"} color="green" />
        <StatusBadge on={dash.marketHours} label={dash.marketHours ? "MARKET OPEN" : "MARKET CLOSED"} color="green" />
        <StatusBadge on={!dash.brokerCircuitOpen} label={dash.brokerCircuitOpen ? "BROKER DOWN" : "BROKER OK"} color={dash.brokerCircuitOpen ? "red" : "green"} />
        {dash.status.dailyLossLimitHit && <StatusBadge on label="DAILY LOSS LIMIT HIT" color="red" />}
        {dash.status.consecutiveLossesHit && <StatusBadge on label="CONSECUTIVE LOSSES HALT" color="red" />}
      </div>

      {/* Key Metrics */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
        <div className="rounded-xl border bg-white p-4 shadow-sm">
          <p className="text-xs font-medium text-gray-500">Daily P&L</p>
          <p className={cn("mt-1 text-2xl font-bold", pnlColor)}>{fmtRupee(dash.status.dailyPnl)}</p>
          <p className="mt-1 text-xs text-gray-400">Limit: ₹{dash.status.dailyLossLimit}</p>
        </div>
        <div className="rounded-xl border bg-white p-4 shadow-sm">
          <p className="text-xs font-medium text-gray-500">Open Trades</p>
          <p className="mt-1 text-2xl font-bold text-gray-900">{dash.status.openTradeCount}</p>
          <p className="mt-1 text-xs text-gray-400">Max: {dash.config.maxConcurrentTrades}</p>
        </div>
        <div className="rounded-xl border bg-white p-4 shadow-sm">
          <p className="text-xs font-medium text-gray-500">Today's Trades</p>
          <p className="mt-1 text-2xl font-bold text-gray-900">{dash.status.todayTradeCount}</p>
          <p className="mt-1 text-xs text-gray-400">{dash.status.todayWins}W / {dash.status.todayClosed - dash.status.todayWins}L</p>
        </div>
        <div className="rounded-xl border bg-white p-4 shadow-sm">
          <p className="text-xs font-medium text-gray-500">Win Rate</p>
          <p className={cn("mt-1 text-2xl font-bold", dash.status.winRate >= 50 ? "text-green-600" : "text-red-600")}>
            {dash.status.winRate.toFixed(1)}%
          </p>
          <p className="mt-1 text-xs text-gray-400">{dash.status.todayClosed} closed</p>
        </div>
        <div className="rounded-xl border bg-white p-4 shadow-sm">
          <p className="text-xs font-medium text-gray-500">Capital / Trade</p>
          <p className="mt-1 text-2xl font-bold text-gray-900">₹{dash.config.capitalPerTrade.toLocaleString()}</p>
          <p className="mt-1 text-xs text-gray-400">SL: {(dash.config.disasterStopPct * 100).toFixed(1)}%</p>
        </div>
      </div>

      {/* Strategy Controls */}
      <div className="rounded-xl border bg-white p-5 shadow-sm">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-sm font-bold text-gray-900">ORB Strategy</h2>
            <p className="text-xs text-gray-500">Opening Range Breakout — primary intraday strategy</p>
          </div>
          <button
            onClick={() => toggleOrbMut.mutate(!dash.orbEnabled)}
            disabled={toggleOrbMut.isPending}
            className={cn(
              "rounded-lg px-4 py-2 text-sm font-bold transition-colors disabled:opacity-50",
              dash.orbEnabled ? "bg-red-100 text-red-700 hover:bg-red-200" : "bg-green-100 text-green-700 hover:bg-green-200"
            )}
          >
            {dash.orbEnabled ? "Disable ORB" : "Enable ORB"}
          </button>
        </div>
      </div>

      {/* Open Trades Table */}
      <div className="rounded-xl border bg-white shadow-sm">
        <div className="border-b px-5 py-3">
          <h2 className="text-sm font-bold text-gray-900">Open Positions ({dash.openTrades.length})</h2>
        </div>
        {dash.openTrades.length === 0 ? (
          <div className="p-8 text-center text-sm text-gray-400">No open positions</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="bg-gray-50 text-xs font-semibold uppercase text-gray-500">
                <tr>
                  <th className="px-4 py-3">Symbol</th>
                  <th className="px-4 py-3">Side</th>
                  <th className="px-4 py-3">Qty</th>
                  <th className="px-4 py-3">Entry</th>
                  <th className="px-4 py-3">SL</th>
                  <th className="px-4 py-3">Strategy</th>
                  <th className="px-4 py-3">Entered</th>
                  <th className="px-4 py-3 text-right">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {dash.openTrades.map((t) => (
                  <tr key={t.id} className="hover:bg-gray-50">
                    <td className="px-4 py-3 font-semibold">{t.symbol}</td>
                    <td className={cn("px-4 py-3 font-bold", t.side === "BUY" ? "text-green-600" : "text-red-600")}>{t.side}</td>
                    <td className="px-4 py-3">{t.quantity}</td>
                    <td className="px-4 py-3">₹{t.entryPrice?.toFixed(2)}</td>
                    <td className="px-4 py-3 text-red-500">₹{t.stopLoss?.toFixed(2)}</td>
                    <td className="px-4 py-3 text-xs text-gray-500">{t.strategy}</td>
                    <td className="px-4 py-3 text-xs text-gray-500">{fmtTime(t.enteredAt)}</td>
                    <td className="px-4 py-3 text-right">
                      <button
                        onClick={() => closeTradesMut.mutate(t.id)}
                        disabled={closeTradesMut.isPending}
                        className="rounded bg-red-600 px-3 py-1 text-xs font-bold text-white hover:bg-red-700 disabled:opacity-50"
                      >
                        Close
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Today's Closed Trades */}
      {dash.todayTrades.filter(t => t.status === "CLOSED").length > 0 && (
        <div className="rounded-xl border bg-white shadow-sm">
          <div className="border-b px-5 py-3">
            <h2 className="text-sm font-bold text-gray-900">
              Today's Closed Trades ({dash.todayTrades.filter(t => t.status === "CLOSED").length})
            </h2>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="bg-gray-50 text-xs font-semibold uppercase text-gray-500">
                <tr>
                  <th className="px-4 py-3">Symbol</th>
                  <th className="px-4 py-3">Side</th>
                  <th className="px-4 py-3">Entry</th>
                  <th className="px-4 py-3">P&L</th>
                  <th className="px-4 py-3">Exit Reason</th>
                  <th className="px-4 py-3">Strategy</th>
                  <th className="px-4 py-3">Closed</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {dash.todayTrades.filter(t => t.status === "CLOSED").map((t) => (
                  <tr key={t.id} className="hover:bg-gray-50">
                    <td className="px-4 py-3 font-semibold">{t.symbol}</td>
                    <td className={cn("px-4 py-3 font-bold", t.side === "BUY" ? "text-green-600" : "text-red-600")}>{t.side}</td>
                    <td className="px-4 py-3">₹{t.entryPrice?.toFixed(2)}</td>
                    <td className={cn("px-4 py-3 font-bold", (t.realizedPnl ?? 0) >= 0 ? "text-green-600" : "text-red-600")}>
                      {fmtRupee(t.realizedPnl)}
                    </td>
                    <td className="px-4 py-3 text-xs text-gray-500">{t.exitReason || "—"}</td>
                    <td className="px-4 py-3 text-xs text-gray-500">{t.strategy}</td>
                    <td className="px-4 py-3 text-xs text-gray-500">{t.closedAt ? fmtTime(t.closedAt) : "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Bottom row: Alerts + Admin Activity */}
      <div className="grid gap-4 lg:grid-cols-2">
        {/* Alerts */}
        <div className="rounded-xl border bg-white shadow-sm">
          <div className="border-b px-5 py-3">
            <h2 className="text-sm font-bold text-gray-900">Recent Alerts</h2>
          </div>
          {dash.alerts.length === 0 ? (
            <div className="p-6 text-center text-sm text-gray-400">No alerts</div>
          ) : (
            <div className="max-h-64 divide-y overflow-y-auto">
              {dash.alerts.slice(0, 15).map((a) => (
                <div key={a.id} className="flex items-start gap-3 px-5 py-3">
                  <span className={cn(
                    "mt-0.5 h-2 w-2 shrink-0 rounded-full",
                    a.level === "ERROR" ? "bg-red-500" : a.level === "WARN" ? "bg-yellow-500" : "bg-blue-500"
                  )} />
                  <div className="min-w-0 flex-1">
                    <p className="text-xs text-gray-700">{a.message}</p>
                    <p className="mt-0.5 text-[10px] text-gray-400">{a.category} — {fmtDateTime(a.createdAt)}</p>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Admin Actions */}
        <div className="rounded-xl border bg-white shadow-sm">
          <div className="border-b px-5 py-3">
            <h2 className="text-sm font-bold text-gray-900">Admin Activity</h2>
          </div>
          {dash.adminActions.length === 0 ? (
            <div className="p-6 text-center text-sm text-gray-400">No recent activity</div>
          ) : (
            <div className="max-h-64 divide-y overflow-y-auto">
              {dash.adminActions.slice(0, 15).map((a) => (
                <div key={a.id} className="flex items-center justify-between px-5 py-3">
                  <span className="text-xs font-medium text-gray-700">{a.action.replace(/_/g, " ")}</span>
                  <span className="text-[10px] text-gray-400">{fmtDateTime(a.createdAt)}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
