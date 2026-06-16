export type KpiStat = {
  label: string;
  value: string;
  delta: string;
  positive: boolean;
};

export type StrategyItem = {
  name: string;
  mode: "LIVE" | "PAPER";
  pnl: string;
  positive: boolean;
};

export type PositionItem = {
  symbol: string;
  quantity: string;
  price: string;
  change: string;
  positive: boolean;
};

export type OrderItem = {
  symbol: string;
  side: "BUY" | "SELL";
  time: string;
  status: "FILLED" | "PENDING";
};

export type AlertItem = {
  level: "info" | "warn" | "critical";
  title: string;
  detail: string;
};

export type MarketWatchItem = {
  symbol: string;
  price: string;
  change: string;
  positive: boolean;
};

export type TraderDashboardData = {
  greetingName: string;
  kpis: KpiStat[];
  chart: {
    symbol: string;
    last: string;
    change: string;
    candles?: { time: number; open: number; high: number; low: number; close: number }[];
    volumes?: { time: number; value: number; up?: boolean }[];
  };
  strategies: StrategyItem[];
  positions: PositionItem[];
  orders: OrderItem[];
  alerts: AlertItem[];
  marketWatch: MarketWatchItem[];
  portfolio: {
    equity: string;
    margin: string;
    marginUsedPct: number;
  };
  brokerStatus: "HEALTHY" | "DEGRADED" | "DOWN";
  runtimeStatus: string;
  notifications: { unread: number };
  websocketMetrics?: { connectedClients: string; dropped: string };
};

export type AdminDashboardData = {
  userMetrics: KpiStat[];
  runtimeMetrics: { label: string; value: string }[];
  riskMetrics: { label: string; value: string; level?: "ok" | "warn" | "critical" }[];
  brokerHealth: { name: string; status: "HEALTHY" | "WARNING" | "DOWN" }[];
  replayMetrics: { label: string; value: string }[];
  omsStats: { label: string; value: string }[];
  websocketMetrics: { label: string; value: string }[];
  auditActivity: { time: string; action: string; actor: string }[];
  liveTraderActivity: { name: string; tier: string; online: boolean }[];
  alerts: AlertItem[];
};
