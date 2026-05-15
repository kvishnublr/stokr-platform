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
    api.get("/api/positions/open"),
    api.get("/api/orders/recent"),
    api.get("/api/strategies/runtime"),
    api.get("/api/executions/recent"),
    api.get("/api/alerts/feed"),
    api.get("/api/trader/broker/status"),
    api.get("/api/trader/terminal/market/watch"),
    api.get("/api/runtime/status"),
    api.get("/api/notifications/unread-count"),
    api.get("/api/ws/metrics"),
    api.get("/api/trader/terminal/market/chart?symbol=NIFTY%2050&interval=5m&limit=120"),
    api.get("/api/analytics/overview"),
    api.get("/api/backtests/recent"),
  ]);

  const merged: TraderDashboardData = {
    ...EMPTY_TRADER_DASHBOARD,
    kpis: [...EMPTY_TRADER_DASHBOARD.kpis],
  };
  const [portfolio, positions, orders, strategies, executions, alerts, brokers, watch, runtime, notifications, wsMetrics, candles] = requests;

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
  if (positions.status === "fulfilled" && Array.isArray(positions.value.data?.data)) {
    merged.positions = positions.value.data.data.slice(0, 4).map((p: any) => ({
      symbol: safeString(p.symbol, "—"),
      quantity: safeString(p.quantity, "0"),
      price: safeString(p.avgPrice, "₹ —"),
      change: safeString(p.pnl, "0"),
      positive: Number(p.pnl ?? 0) >= 0,
    }));
  }
  if (orders.status === "fulfilled" && Array.isArray(orders.value.data?.data)) {
    merged.orders = orders.value.data.data.slice(0, 4).map((o: any) => ({
      symbol: safeString(o.symbol, "—"),
      side: String(o.side).toUpperCase() === "SELL" ? "SELL" : "BUY",
      time: safeString(o.time ?? o.createdAt, "—"),
      status: String(o.status).toUpperCase().includes("PEND") ? "PENDING" : "FILLED",
    }));
  }
  if (strategies.status === "fulfilled" && Array.isArray(strategies.value.data?.data)) {
    merged.strategies = strategies.value.data.data.slice(0, 4).map((s: any) => ({
      name: safeString(s.name, "Strategy"),
      mode: String(s.mode).toUpperCase() === "PAPER" ? "PAPER" : "LIVE",
      pnl: safeString(s.pnl, "0"),
      positive: Number(s.pnl ?? 0) >= 0,
    }));
  }
  if (alerts.status === "fulfilled" && Array.isArray(alerts.value.data?.data)) {
    merged.alerts = alerts.value.data.data.slice(0, 3).map((a: any) => ({
      level: a.severity === "critical" ? "critical" : a.severity === "warn" ? "warn" : "info",
      title: safeString(a.title, "Alert"),
      detail: safeString(a.timeAgo ?? a.detail, ""),
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
    merged.brokerStatus =
      state === "DOWN" ? "DOWN" : state === "DEGRADED" || state === "UNKNOWN" ? "DEGRADED" : "HEALTHY";
  }
  if (runtime.status === "fulfilled") {
    merged.runtimeStatus = safeString(runtime.value.data?.data?.state, "Unavailable");
  }
  if (notifications.status === "fulfilled") {
    merged.notifications = { unread: Number(notifications.value.data?.data?.unread ?? 0) };
  }
  if (wsMetrics.status === "fulfilled") {
    merged.websocketMetrics = {
      connectedClients: safeString(wsMetrics.value.data?.data?.connectedClients, "0"),
      dropped: safeString(wsMetrics.value.data?.data?.dropped, "0"),
    };
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
  if (executions.status === "fulfilled" && Array.isArray(executions.value.data?.data)) {
    // Execution feed endpoint is consumed to keep service layer integration-ready.
  }

  return merged;
}
