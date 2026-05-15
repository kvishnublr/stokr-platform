import { api } from "../../api/client";
import type { TraderDashboardData } from "./types";

const EMPTY_TRADER_DASHBOARD: TraderDashboardData = {
  greetingName: "Trader",
  kpis: [
    { label: "Total P&L", value: "—", delta: "No data available", positive: false },
    { label: "Unrealized P&L", value: "—", delta: "No data available", positive: false },
    { label: "Realized P&L", value: "—", delta: "No data available", positive: false },
    { label: "Total Capital", value: "—", delta: "No data available", positive: false },
  ],
  chart: { symbol: "No market data", last: "—", change: "—" },
  strategies: [],
  positions: [],
  orders: [],
  alerts: [],
  marketWatch: [],
  portfolio: { equity: "—", margin: "—", marginUsedPct: 0 },
  brokerStatus: "DEGRADED",
  runtimeStatus: "Unavailable",
  notifications: { unread: 0 },
  websocketMetrics: { connectedClients: "0", dropped: "0" },
};

function safeString(value: unknown, fallback: string): string {
  if (typeof value === "string" && value.length) return value;
  if (typeof value === "number") return String(value);
  return fallback;
}

export async function fetchTraderDashboardData(): Promise<TraderDashboardData> {
  const requests = await Promise.allSettled([
    api.get("/api/portfolio/dashboard?equityPoints=60"),
    api.get("/api/oms/orders?page=0&size=4"),
    api.get("/api/strategies/runtime-metrics"),
    api.get("/api/trader/strategy-feed"),
    api.get("/api/trader/execution-summary"),
    api.get("/api/trader/replay-summary"),
    api.get("/api/trader/broker/status"),
    api.get("/api/trader/terminal/market/watch"),
    api.get("/api/trader/terminal/market/chart?symbol=NIFTY_FUT&interval=5m&limit=120"),
  ]);

  const merged: TraderDashboardData = {
    ...EMPTY_TRADER_DASHBOARD,
    kpis: [...EMPTY_TRADER_DASHBOARD.kpis],
  };

  const [portfolio, orders, strategies, signalFeed, executionSummary, replaySummary, brokers, watch, candles] = requests;

  if (portfolio.status === "fulfilled") {
    const root = portfolio.value.data?.data;
    const ov = root?.overview;
    const profile = root?.profile;
    if (ov) {
      merged.kpis = [
        {
          label: "Total P&L",
          value: safeString(ov.mtmPnl, "—"),
          delta: safeString(ov.mtmPnlDeltaLabel ?? ov.dayChangeLabel, "No P&L delta"),
          positive: Number(ov.mtmPnl ?? 0) >= 0,
        },
        {
          label: "Unrealized P&L",
          value: safeString(ov.unrealizedPnl, "—"),
          delta: safeString(ov.unrealizedPnlDeltaLabel, "No unrealized delta"),
          positive: Number(ov.unrealizedPnl ?? 0) >= 0,
        },
        {
          label: "Realized P&L",
          value: safeString(ov.realizedPnl, "—"),
          delta: safeString(ov.realizedPnlDeltaLabel, "No realized delta"),
          positive: Number(ov.realizedPnl ?? 0) >= 0,
        },
        {
          label: "Total Capital",
          value: safeString(ov.totalCapital ?? ov.accountValue, "—"),
          delta: safeString(ov.capitalUsageLabel ?? "No capital usage data", "No capital usage data"),
          positive: true,
        },
      ];
      merged.portfolio = {
        equity: safeString(ov.totalEquity ?? ov.accountValue, "—"),
        margin: safeString(ov.availableMargin ?? ov.cashAvailable, "—"),
        marginUsedPct: Math.max(0, Math.min(100, Number(ov.marginUsedPct ?? 0) || 0)),
      };
    }
    if (profile?.displayName || profile?.firstName || profile?.username) {
      merged.greetingName = safeString(profile.displayName ?? profile.firstName ?? profile.username, "Trader");
    }
  }

  if (orders.status === "fulfilled" && Array.isArray(orders.value.data?.data?.content)) {
    merged.orders = orders.value.data.data.content.slice(0, 4).map((o: any) => ({
      symbol: safeString(o.symbol, "—"),
      side: String(o.side).toUpperCase() === "SELL" ? "SELL" : "BUY",
      time: safeString(o.createdAt, "—"),
      status: String(o.state).toUpperCase().includes("PEND") ? "PENDING" : "FILLED",
    }));
  }

  if (strategies.status === "fulfilled" && Array.isArray(strategies.value.data?.data)) {
    merged.strategies = strategies.value.data.data.slice(0, 4).map((s: any) => ({
      name: safeString(s.strategyKey ?? s.name, "Strategy"),
      mode: String(s.executionMode).toUpperCase() === "PAPER" ? "PAPER" : "LIVE",
      pnl: safeString(s.unrealizedPnl, "0"),
      positive: Number(s.unrealizedPnl ?? 0) >= 0,
    }));
  }

  if (signalFeed.status === "fulfilled" && Array.isArray(signalFeed.value.data?.data)) {
    merged.alerts = signalFeed.value.data.data.slice(0, 3).map((a: any) => ({
      level: "info",
      title: `${safeString(a.strategyName, "Strategy")} · ${safeString(a.symbol, "—")} · ${safeString(a.signalType, "SIGNAL")}`,
      detail: safeString(a.createdAt, ""),
    }));
  }

  if (watch.status === "fulfilled" && Array.isArray(watch.value.data?.data)) {
    merged.marketWatch = watch.value.data.data.slice(0, 4).map((m: any) => ({
      symbol: safeString(m.symbol, "—"),
      price: safeString(m.price, "—"),
      change: safeString(m.changePct, "—"),
      positive: Number(m.changePct ?? 0) >= 0,
    }));
    const first = merged.marketWatch[0];
    if (first) {
      merged.chart = {
        symbol: first.symbol,
        last: first.price,
        change: first.change,
      };
    }
  }

  if (brokers.status === "fulfilled") {
    const raw = brokers.value.data?.data as { health?: string; status?: string } | undefined;
    const state = String(raw?.health ?? raw?.status ?? "HEALTHY").toUpperCase();
    merged.brokerStatus = state === "DOWN" ? "DOWN" : state === "DEGRADED" || state === "UNKNOWN" ? "DEGRADED" : "HEALTHY";
  }

  if (executionSummary.status === "fulfilled") {
    const row = executionSummary.value.data?.data as Record<string, unknown> | undefined;
    merged.runtimeStatus = `Orders ${safeString(row?.ordersTotal, "0")} · Rejected ${safeString(row?.rejectedOrders, "0")}`;
  }

  if (replaySummary.status === "fulfilled") {
    const row = replaySummary.value.data?.data as Record<string, unknown> | undefined;
    if (row?.hasRecentJob) {
      merged.notifications = { unread: 1 };
      merged.websocketMetrics = {
        connectedClients: safeString(row?.status, "0"),
        dropped: safeString(row?.replayDiagnosis ?? "none", "0"),
      };
    }
  }

  if (candles.status === "fulfilled" && Array.isArray(candles.value.data?.data)) {
    const rows = candles.value.data.data as Array<Record<string, unknown>>;
    merged.chart.candles = rows
      .map((row) => ({
        time: Number(row.time ?? row.ts ?? 0),
        open: Number(row.open ?? 0),
        high: Number(row.high ?? 0),
        low: Number(row.low ?? 0),
        close: Number(row.close ?? 0),
      }))
      .filter((c) => Number.isFinite(c.time) && c.time > 0)
      .slice(-200);
    merged.chart.volumes = rows
      .map((row) => {
        const open = Number(row.open ?? 0);
        const close = Number(row.close ?? 0);
        return {
          time: Number(row.time ?? row.ts ?? 0),
          value: Number(row.volume ?? 0),
          up: close >= open,
        };
      })
      .filter((v) => Number.isFinite(v.time) && v.time > 0 && Number.isFinite(v.value));
  }

  return merged;
}
