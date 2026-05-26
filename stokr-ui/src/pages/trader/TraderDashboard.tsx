import { useQuery } from "@tanstack/react-query";
import { fmtDateTime, fmtNseClock, fmtDateLong } from "../../lib/dateUtils";
import { Navigate, Link } from "react-router-dom";
import { useSessionStore } from "../../state/session";
import { useUiThemeStore } from "../../state/uiTheme";
import { api } from "../../api/client";
import { cn } from "../../lib/utils";
import { NiftyCandleChart } from "../../components/charts/NiftyCandleChart";
import { useEffect, useMemo, useState } from "react";
import {
  TrendingUp, TrendingDown, Activity, RefreshCw,
  Zap, BarChart2, Layers, ArrowUpRight, ArrowDownRight,
  CircleDot, ChevronRight, AlertTriangle, Radio,
} from "lucide-react";

// ─── Types ────────────────────────────────────────────────────────────────────

type Workstation = {
  accountSummary?: {
    totalPnl?: string | number;
    realizedPnl?: string | number;
    unrealizedPnl?: string | number;
    openPositions?: number;
    activeStrategies?: number;
    brokerConnectionState?: string;
    executionMode?: string;
  };
  latestSignals?: Array<Record<string, unknown>>;
  strategyAllocations?: Array<Record<string, unknown>>;
  badges?: string[];
};

type ReadinessStrategy = {
  strategy?: string;
  strategyKey?: string;
  runtime?: string;
  status?: string;
  lastSignalTime?: string;
  historicalCoverage?: { symbol?: string; state?: string };
};

type Readiness = {
  strategies?: ReadinessStrategy[];
  broker?: { status?: string; tokenValid?: boolean };
};

type SignalRow = {
  id: string; strategyName?: string; symbol?: string; signalType?: string; createdAt?: string;
  confidenceScore?: string; reason?: string;
};

type OrderRow = {
  id: string; symbol?: string; side?: string; state?: string;
  createdAt?: string; executionMode?: string; strategyKey?: string;
};

type StrategyRow = {
  strategyKey?: string;
  strategyName?: string;
  name?: string;
  code?: string;
  symbol?: string;
  executionMode?: string;
  runtimeState?: string;
  unrealizedPnl?: string;
  subscribed?: boolean;
};

type CandleRow = {
  time?: number; ts?: number; open?: number; high?: number; low?: number;
  close?: number; volume?: number;
};

type WatchRow = {
  symbol: string; price?: string; changePct?: string;
};

// ─── Helpers ──────────────────────────────────────────────────────────────────

const safeNum = (v: unknown): number | null => {
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
};

const fmtCurrency = (v: unknown) => {
  const n = safeNum(v);
  if (n == null) return "—";
  return new Intl.NumberFormat("en-IN", {
    style: "currency", currency: "INR", notation: Math.abs(n) >= 1e7 ? "compact" : "standard",
    maximumFractionDigits: 2,
  }).format(n);
};

const fmtTime = fmtDateTime;

function NseClock({ className }: { className?: string }) {
  const [label, setLabel] = useState(() => fmtNseClock());
  useEffect(() => {
    const tick = () => setLabel(fmtNseClock());
    tick();
    const id = setInterval(tick, 30_000);
    return () => clearInterval(id);
  }, []);
  return <span className={className}>{label}</span>;
}

// ─── Skeleton ─────────────────────────────────────────────────────────────────

function Skeleton({ className }: { className?: string }) {
  return <div className={cn("animate-pulse rounded-md bg-neutral-200 dark:bg-neutral-800", className)} />;
}

// ─── KPI Card ─────────────────────────────────────────────────────────────────

function KpiCard({
  label, value, delta, positive, loading, icon: Icon, accent,
}: {
  label: string; value: string; delta?: string; positive?: boolean;
  loading?: boolean; icon?: React.ElementType; accent?: string;
}) {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  return (
    <div className={cn(
      "relative overflow-hidden rounded-2xl border p-5 transition-all",
      isLight
        ? "border-neutral-200 bg-white shadow-sm hover:shadow-md"
        : "border-white/[0.08] bg-neutral-900/80 hover:border-white/[0.14]"
    )}>
      {/* Accent bar */}
      {accent && <div className={cn("absolute left-0 top-0 h-full w-1 rounded-l-2xl", accent)} />}
      <div className="pl-1">
        <div className={cn("flex items-center justify-between")}>
          <span className={cn("text-xs font-bold uppercase tracking-widest",
            isLight ? "text-neutral-400" : "text-neutral-500")}>{label}</span>
          {Icon && (
            <div className={cn("rounded-xl p-2",
              isLight ? "bg-neutral-100" : "bg-white/[0.06]")}>
              <Icon className={cn("h-3.5 w-3.5", isLight ? "text-neutral-500" : "text-neutral-400")} />
            </div>
          )}
        </div>
        <div className="mt-3">
          {loading
            ? <Skeleton className="h-7 w-28" />
            : <span className={cn("text-2xl font-black tabular-nums tracking-tight",
                isLight ? "text-neutral-900" : "text-white")}>{value}</span>
          }
        </div>
        {(delta || loading) && (
          <div className={cn("mt-1.5 flex items-center gap-1 text-xs font-semibold",
            loading ? "" : positive ? "text-emerald-500" : "text-rose-500")}>
            {loading ? <Skeleton className="h-3.5 w-20" /> : (
              <>
                {positive ? <ArrowUpRight className="h-3 w-3" /> : <ArrowDownRight className="h-3 w-3" />}
                {delta}
              </>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

// ─── Main Dashboard ───────────────────────────────────────────────────────────

export function TraderDashboard() {
  const accessToken = useSessionStore((s) => s.accessToken);
  const hasTraderAccess = useSessionStore((s) => s.hasTraderAccess());
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const displayName = useSessionStore((s) => s.displayName);
  const [chartInterval, setChartInterval] = useState("15m");
  const [chartSymbol, setChartSymbol] = useState("NIFTY_FUT");

  if (!accessToken || !hasTraderAccess) return <Navigate to="/login" replace />;

  // ── Independent parallel queries (each shows immediately as it resolves) ──

  const modeQ = useQuery({
    queryKey: ["trader-exec-mode"],
    queryFn: async () => {
      const r = await api.get("/api/trader/me/execution-mode").catch(() => null);
      return String(r?.data?.data?.executionMode ?? "PAPER").toUpperCase();
    },
    staleTime: 60_000,
  });
  const selectedMode = modeQ.data ?? "PAPER";

  const workstationQ = useQuery<Workstation>({
    queryKey: ["trader-dashboard-workstation"],
    queryFn: async () => (await api.get("/api/trader/terminal/workstation")).data?.data,
    staleTime: 10_000,
    refetchInterval: 20_000,
  });

  const brokerFundsQ = useQuery<Array<{ cashAvailable?: number; availableMargin?: number }>>({
    queryKey: ["trader-dashboard-broker-funds"],
    queryFn: async () => {
      const r = await api.get("/api/trader/broker/accounts").catch(() => null);
      return Array.isArray(r?.data?.data) ? r.data.data : [];
    },
    staleTime: 20_000,
    refetchInterval: 30_000,
  });

  const readinessQ = useQuery<Readiness>({
    queryKey: ["trader-dashboard-readiness"],
    queryFn: async () => (await api.get("/api/trader/intraday/readiness")).data?.data,
    staleTime: 30_000,
    refetchInterval: 60_000,
  });

  const executionSummaryQ = useQuery<Record<string, unknown>>({
    queryKey: ["trader-dashboard-exec-summary"],
    queryFn: async () => (await api.get("/api/trader/execution-summary")).data?.data,
    staleTime: 15_000,
    refetchInterval: 30_000,
  });

  const ordersQ = useQuery<{ content: OrderRow[] }>({
    queryKey: ["trader-dashboard-orders", selectedMode],
    queryFn: async () => (await api.get(`/api/oms/orders?page=0&size=5&executionMode=${selectedMode}&sort=createdAt,desc`)).data?.data,
    staleTime: 10_000,
    refetchInterval: 15_000,
    enabled: modeQ.isSuccess,
  });

  const candlesQ = useQuery<CandleRow[]>({
    queryKey: ["trader-dashboard-candles", chartSymbol, chartInterval],
    queryFn: async () => {
      const r = await api.get(`/api/trader/terminal/market/chart?symbol=${chartSymbol}&interval=${chartInterval}&limit=120`);
      return Array.isArray(r.data?.data) ? r.data.data : [];
    },
    staleTime: 30_000,
    refetchInterval: 60_000,
  });

  const watchQ = useQuery<WatchRow[]>({
    queryKey: ["trader-dashboard-watch"],
    queryFn: async () => {
      const r = await api.get("/api/trader/terminal/market/watch").catch(() => null);
      return Array.isArray(r?.data?.data) ? r.data.data.slice(0, 5) : [];
    },
    staleTime: 15_000,
    refetchInterval: 20_000,
  });

  // ── Derived data ───────────────────────────────────────────────────────────

  const ws = workstationQ.data;
  const acct = ws?.accountSummary;
  const greetName = displayName ?? "Trader";

  const brokerConnected =
    String(acct?.brokerConnectionState ?? "").includes("CONNECTED") ||
    (brokerFundsQ.data?.length ?? 0) > 0;

  const availableMargin = useMemo(() => {
    const row = brokerFundsQ.data?.[0];
    if (!row) return null;
    return safeNum(row.availableMargin ?? row.cashAvailable);
  }, [brokerFundsQ.data]);

  const activeStrategies = useMemo<StrategyRow[]>(() => {
    const fromWs = (ws?.strategyAllocations ?? [])
      .filter((s) => {
        const state = String(s.runtimeState ?? "").toUpperCase();
        return state.includes("RUN") || state === "ACTIVE";
      })
      .map((s) => ({
        strategyKey: String(s.strategyKey ?? ""),
        strategyName: String(s.strategyName ?? s.strategyKey ?? "Strategy"),
        symbol: s.symbol != null ? String(s.symbol) : undefined,
        executionMode: String(s.executionMode ?? acct?.executionMode ?? selectedMode),
        runtimeState: String(s.runtimeState ?? ""),
      }));
    if (fromWs.length > 0) return fromWs.slice(0, 8);

    const fromReady = (readinessQ.data?.strategies ?? [])
      .filter((s) => String(s.runtime ?? "").includes("RUN") || String(s.status ?? "").includes("READY"))
      .map((s) => ({
        strategyKey: s.strategyKey,
        strategyName: s.strategy ?? s.strategyKey,
        symbol: s.historicalCoverage?.symbol,
        executionMode: acct?.executionMode ?? selectedMode,
        runtimeState: s.runtime,
      }));
    return fromReady.slice(0, 8);
  }, [ws?.strategyAllocations, readinessQ.data?.strategies, acct?.executionMode, selectedMode]);

  const latestSignals = useMemo<SignalRow[]>(() => {
    const rows = ws?.latestSignals ?? [];
    return rows.slice(0, 8).map((s, i) => ({
      id: String(s.id ?? `sig-${i}`),
      strategyName: s.strategyName != null ? String(s.strategyName) : undefined,
      symbol: s.symbol != null ? String(s.symbol) : undefined,
      signalType: s.signalType != null ? String(s.signalType) : undefined,
      createdAt: s.createdAt != null ? String(s.createdAt) : undefined,
      confidenceScore: s.confidenceScore != null ? String(s.confidenceScore) : undefined,
      reason: s.reason != null ? String(s.reason) : undefined,
    }));
  }, [ws?.latestSignals]);

  const rejectedOrders = Number(executionSummaryQ.data?.rejectedOrders ?? 0);
  const totalOrders = Number(executionSummaryQ.data?.ordersTotal ?? 0);
  const rejectionPct =
    totalOrders > 0 ? `${((rejectedOrders / totalOrders) * 100).toFixed(0)}% rejected today` : undefined;

  const mtmPnl = acct?.totalPnl;
  const unrealizedPnl = acct?.unrealizedPnl;
  const realizedPnl = acct?.realizedPnl;
  const openPosCount = acct?.openPositions ?? 0;

  const candles = (candlesQ.data ?? [])
    .map(r => ({ time: Number(r.time ?? r.ts ?? 0), open: Number(r.open ?? 0), high: Number(r.high ?? 0), low: Number(r.low ?? 0), close: Number(r.close ?? 0) }))
    .filter(c => c.time > 0);

  const volumes = (candlesQ.data ?? [])
    .map(r => ({ time: Number(r.time ?? r.ts ?? 0), value: Number(r.volume ?? 0), up: Number(r.close ?? 0) >= Number(r.open ?? 0) }))
    .filter(v => v.time > 0);

  const watchSymbol = watchQ.data?.find(w => w.symbol === chartSymbol);

  // ── Theme tokens ───────────────────────────────────────────────────────────

  const pageBg = isLight ? "bg-neutral-50" : "bg-neutral-950";
  const cardBg = isLight ? "bg-white border-neutral-200" : "bg-neutral-900/80 border-white/[0.08]";
  const headingCls = isLight ? "text-neutral-900" : "text-white";
  const mutedCls = isLight ? "text-neutral-400" : "text-neutral-500";
  const subCls = isLight ? "text-neutral-500" : "text-neutral-400";
  const dividerCls = isLight ? "border-neutral-100" : "border-white/[0.06]";
  const rowHover = isLight ? "hover:bg-neutral-50" : "hover:bg-white/[0.04]";
  const rowBg = isLight ? "bg-neutral-50/50" : "bg-white/[0.02]";

  const isLoading = workstationQ.isLoading;
  const hasChartData = candles.length > 0;

  const refetchAll = () => {
    void workstationQ.refetch();
    void brokerFundsQ.refetch();
    void ordersQ.refetch();
    void candlesQ.refetch();
    void watchQ.refetch();
    void readinessQ.refetch();
    void executionSummaryQ.refetch();
  };

  return (
    <div className={cn("min-h-full space-y-6 pb-10", pageBg)}>
      {/* ── Header ── */}
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className={cn("text-2xl font-black tracking-tight", headingCls)}>
            Welcome back, {greetName}
          </h1>
          <p className={cn("mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm", subCls)}>
            <span className={cn(
              "inline-flex items-center rounded-md px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide",
              selectedMode === "LIVE"
                ? isLight ? "bg-rose-100 text-rose-700" : "bg-rose-500/15 text-rose-400"
                : isLight ? "bg-amber-100 text-amber-700" : "bg-amber-500/15 text-amber-400",
            )}>
              {selectedMode === "LIVE" ? "Live" : "Paper"}
            </span>
            <NseClock className={cn("font-mono text-xs", mutedCls)} />
            <span className={mutedCls}>· {fmtDateLong(new Date())}</span>
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Link to="/signals" className={cn(
            "flex items-center gap-1.5 rounded-xl border px-3 py-2 text-xs font-semibold transition",
            isLight ? "border-neutral-200 bg-white text-neutral-700 hover:bg-neutral-50" : "border-white/[0.1] bg-white/[0.05] text-neutral-300 hover:bg-white/[0.09]"
          )}>
            <Zap className="h-3.5 w-3.5 text-amber-400" /> My Signals
          </Link>
          <Link to="/orders" className={cn(
            "flex items-center gap-1.5 rounded-xl border px-3 py-2 text-xs font-semibold transition",
            isLight ? "border-neutral-200 bg-white text-neutral-700 hover:bg-neutral-50" : "border-white/[0.1] bg-white/[0.05] text-neutral-300 hover:bg-white/[0.09]"
          )}>
            <Layers className="h-3.5 w-3.5 text-sky-400" /> Orders
          </Link>
          <button
            type="button"
            onClick={refetchAll}
            className={cn(
              "flex items-center gap-1.5 rounded-xl border px-3 py-2 text-xs font-semibold transition",
              isLight ? "border-neutral-200 bg-white text-neutral-600" : "border-white/[0.1] bg-white/[0.05] text-neutral-400"
            )}
          >
            <RefreshCw className={cn("h-3.5 w-3.5", (workstationQ.isFetching || ordersQ.isFetching) && "animate-spin")} />
          </button>
        </div>
      </div>

      {!brokerConnected && !workstationQ.isLoading && (
        <div className={cn(
          "flex flex-wrap items-center gap-3 rounded-2xl border px-4 py-3 text-sm",
          isLight ? "border-amber-200 bg-amber-50 text-amber-900" : "border-amber-500/30 bg-amber-500/10 text-amber-200",
        )}>
          <AlertTriangle className="h-4 w-4 shrink-0" />
          <span className="flex-1 min-w-[200px]">
            Broker disconnected — P&amp;L reflects OMS positions; margin and live quotes need a connected Zerodha session.
          </span>
          <Link
            to="/brokers"
            className={cn(
              "shrink-0 rounded-lg px-3 py-1.5 text-xs font-semibold",
              isLight ? "bg-amber-200/80 text-amber-900 hover:bg-amber-200" : "bg-amber-500/20 text-amber-100 hover:bg-amber-500/30",
            )}
          >
            Connect broker
          </Link>
        </div>
      )}

      {/* ── KPI Cards ── */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <KpiCard
          label="MTM P&L" loading={isLoading}
          value={fmtCurrency(mtmPnl)}
          delta={openPosCount > 0 ? `${openPosCount} open position${openPosCount === 1 ? "" : "s"}` : rejectionPct}
          positive={(safeNum(mtmPnl) ?? 0) >= 0}
          icon={TrendingUp}
          accent={(safeNum(mtmPnl) ?? 0) >= 0 ? "bg-emerald-500" : "bg-rose-500"}
        />
        <KpiCard
          label="Unrealized P&L" loading={isLoading}
          value={fmtCurrency(unrealizedPnl)}
          positive={(safeNum(unrealizedPnl) ?? 0) >= 0}
          icon={Activity}
          accent={(safeNum(unrealizedPnl) ?? 0) >= 0 ? "bg-sky-400" : "bg-amber-400"}
        />
        <KpiCard
          label="Realized P&L" loading={isLoading}
          value={fmtCurrency(realizedPnl)}
          positive={(safeNum(realizedPnl) ?? 0) >= 0}
          icon={BarChart2}
          accent={(safeNum(realizedPnl) ?? 0) >= 0 ? "bg-violet-400" : "bg-rose-400"}
        />
        <KpiCard
          label="Available Margin" loading={isLoading || brokerFundsQ.isLoading}
          value={brokerConnected ? fmtCurrency(availableMargin) : "—"}
          delta={brokerConnected ? undefined : "Connect broker for live margin"}
          positive={true}
          icon={Layers}
          accent="bg-amber-400"
        />
      </div>

      {/* ── Main 2-col layout ── */}
      <div className="grid grid-cols-1 gap-5 xl:grid-cols-[1fr_340px]">

        {/* Left — Chart + Orders */}
        <div className="space-y-5">

          {/* Chart */}
          <div className={cn("rounded-2xl border p-5 shadow-sm", cardBg)}>
            <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
              <div>
                <div className={cn("text-base font-bold", headingCls)}>
                  {watchSymbol?.symbol ?? chartSymbol}
                </div>
                {watchSymbol && (
                  <div className="mt-1 flex items-center gap-2 text-sm">
                    <span className={cn("font-mono font-bold", headingCls)}>{watchSymbol.price ?? "—"}</span>
                    <span className={cn("font-semibold text-xs",
                      (safeNum(watchSymbol.changePct) ?? 0) >= 0 ? "text-emerald-500" : "text-rose-500")}>
                      {(safeNum(watchSymbol.changePct) ?? 0) >= 0 ? "▲" : "▼"} {watchSymbol.changePct ?? "—"}%
                    </span>
                  </div>
                )}
              </div>
              <div className="flex flex-wrap gap-2">
                {/* Symbol tabs */}
                {(watchQ.data ?? []).slice(0, 4).map(w => (
                  <button key={w.symbol} type="button"
                    onClick={() => setChartSymbol(w.symbol)}
                    className={cn(
                      "rounded-lg border px-2.5 py-1 text-[11px] font-bold transition",
                      chartSymbol === w.symbol
                        ? isLight ? "border-sky-400 bg-sky-50 text-sky-700" : "border-sky-500/50 bg-sky-500/15 text-sky-300"
                        : isLight ? "border-neutral-200 bg-white text-neutral-600 hover:border-neutral-300" : "border-white/[0.07] bg-white/[0.03] text-neutral-400 hover:border-white/[0.12]"
                    )}>
                    {w.symbol.replace("_FUT", "").replace("_", " ")}
                  </button>
                ))}
                {/* Interval tabs */}
                <div className={cn("flex items-center gap-1 rounded-xl border px-1.5 py-1",
                  isLight ? "border-neutral-200 bg-neutral-50" : "border-white/[0.07] bg-white/[0.03]")}>
                  {["1m", "5m", "15m", "1H"].map(t => (
                    <button key={t} type="button" onClick={() => setChartInterval(t)}
                      className={cn(
                        "rounded-lg px-2 py-0.5 text-[11px] font-bold transition",
                        t === chartInterval
                          ? isLight ? "bg-neutral-800 text-white" : "bg-white/[0.15] text-white"
                          : isLight ? "text-neutral-500 hover:text-neutral-800" : "text-neutral-500 hover:text-neutral-300"
                      )}>
                      {t}
                    </button>
                  ))}
                </div>
              </div>
            </div>
            <div className={cn("h-72 rounded-xl", isLight ? "bg-neutral-50 border border-neutral-100" : "bg-neutral-950/60 border border-white/[0.05]")}>
              {candlesQ.isLoading ? (
                <div className="flex h-full items-center justify-center"><Skeleton className="h-48 w-full rounded-xl" /></div>
              ) : hasChartData ? (
                <NiftyCandleChart variant={isLight ? "light" : "dark"} height={272} candles={candles} volumes={volumes} />
              ) : (
                <div className="flex h-full flex-col items-center justify-center gap-2 px-6 text-center">
                  <Radio className={cn("h-8 w-8", mutedCls)} />
                  <p className={cn("text-sm font-medium", subCls)}>No candle data for this symbol yet</p>
                  <p className={cn("text-xs", mutedCls)}>
                    {brokerConnected
                      ? "Market tape may still be warming up — try another symbol or open the intraday cockpit."
                      : "Connect your broker and open the intraday workspace for live charts."}
                  </p>
                  <Link
                    to="/intraday"
                    className={cn(
                      "mt-1 rounded-lg px-3 py-1.5 text-xs font-semibold",
                      isLight ? "bg-sky-600 text-white hover:bg-sky-700" : "bg-sky-500 text-white hover:bg-sky-600",
                    )}
                  >
                    Open Intraday Cockpit
                  </Link>
                </div>
              )}
            </div>
          </div>

          {/* Recent Orders */}
          <div className={cn("rounded-2xl border shadow-sm", cardBg)}>
            <div className={cn("flex items-center justify-between border-b px-5 py-3.5", dividerCls)}>
              <h3 className={cn("font-bold text-sm", headingCls)}>Recent Orders</h3>
              <Link to="/orders" className={cn("flex items-center gap-1 text-xs font-semibold",
                isLight ? "text-sky-600 hover:text-sky-700" : "text-sky-400 hover:text-sky-300")}>
                View all <ChevronRight className="h-3.5 w-3.5" />
              </Link>
            </div>
            <div className="divide-y" style={{ borderColor: isLight ? "#f3f4f6" : "rgba(255,255,255,0.05)" }}>
              {ordersQ.isLoading && [1,2,3].map(i => (
                <div key={i} className="flex items-center justify-between px-5 py-3.5 gap-3">
                  <Skeleton className="h-4 w-24" />
                  <Skeleton className="h-4 w-16" />
                  <Skeleton className="h-4 w-14" />
                </div>
              ))}
              {!ordersQ.isLoading && (ordersQ.data?.content ?? []).length === 0 && (
                <div className={cn("px-5 py-8 text-center text-xs", mutedCls)}>No orders yet</div>
              )}
              {(ordersQ.data?.content ?? []).map(o => {
                const state = (o.state ?? "").toUpperCase();
                const stateCls =
                  state === "FILLED" || state === "EXIT_FILLED" ? "text-emerald-500" :
                  state === "REJECTED" ? "text-rose-500" :
                  state.includes("FILL") ? "text-amber-500" : subCls;
                return (
                  <div key={o.id} className={cn("flex items-center justify-between px-5 py-3 gap-3 transition", rowHover)}>
                    <div className="flex items-center gap-3 min-w-0">
                      <div className={cn(
                        "flex h-7 w-7 shrink-0 items-center justify-center rounded-xl text-[10px] font-black",
                        (o.side ?? "").toUpperCase() === "SELL"
                          ? isLight ? "bg-rose-100 text-rose-700" : "bg-rose-500/15 text-rose-400"
                          : isLight ? "bg-emerald-100 text-emerald-700" : "bg-emerald-500/15 text-emerald-400"
                      )}>
                        {(o.side ?? "?").toUpperCase() === "SELL" ? "S" : "B"}
                      </div>
                      <div className="min-w-0">
                        <div className={cn("font-mono text-sm font-bold truncate", headingCls)}>{o.symbol ?? "—"}</div>
                        <div className={cn("text-[10px] truncate", mutedCls)}>
                          {o.strategyKey ? `${o.strategyKey} · ` : ""}{fmtTime(o.createdAt)}
                        </div>
                      </div>
                    </div>
                    <div className={cn("shrink-0 text-xs font-bold", stateCls)}>{state}</div>
                  </div>
                );
              })}
            </div>
          </div>
        </div>

        {/* Right Rail */}
        <div className="space-y-5">

          {/* Strategies */}
          <div className={cn("rounded-2xl border shadow-sm", cardBg)}>
            <div className={cn("flex items-center justify-between border-b px-5 py-3.5", dividerCls)}>
              <h3 className={cn("font-bold text-sm", headingCls)}>Active Strategies</h3>
              <Link to="/strategies" className={cn("flex items-center gap-1 text-xs font-semibold",
                isLight ? "text-sky-600 hover:text-sky-700" : "text-sky-400 hover:text-sky-300")}>
                Manage <ChevronRight className="h-3.5 w-3.5" />
              </Link>
            </div>
            <div className="divide-y" style={{ borderColor: isLight ? "#f3f4f6" : "rgba(255,255,255,0.05)" }}>
              {workstationQ.isLoading && [1, 2, 3].map((i) => (
                <div key={i} className="flex items-center justify-between px-5 py-3 gap-3">
                  <Skeleton className="h-4 w-32" />
                  <Skeleton className="h-4 w-10" />
                </div>
              ))}
              {!workstationQ.isLoading && activeStrategies.length === 0 && (
                <div className={cn("px-5 py-8 text-center text-xs", mutedCls)}>
                  No running strategies.{" "}
                  <Link to="/strategies" className={cn("font-semibold underline", isLight ? "text-sky-600" : "text-sky-400")}>
                    Start one →
                  </Link>
                </div>
              )}
              {activeStrategies.map((s, i) => {
                const name = s.strategyName ?? s.strategyKey ?? s.code ?? s.name ?? "Strategy";
                const mode = (s.executionMode ?? selectedMode).toUpperCase();
                const runtime = (s.runtimeState ?? "RUNNING").toUpperCase();
                return (
                  <div key={`${s.strategyKey ?? name}-${i}`} className={cn("flex items-center justify-between gap-3 px-5 py-3 transition", rowHover)}>
                    <div className="min-w-0">
                      <div className={cn("truncate text-xs font-semibold", headingCls)} title={name}>{name}</div>
                      <div className="mt-1 flex flex-wrap gap-1">
                        {s.symbol && (
                          <span className={cn(
                            "rounded-md px-1.5 py-0.5 font-mono text-[10px] font-bold",
                            isLight ? "bg-sky-50 text-sky-700" : "bg-sky-500/15 text-sky-300",
                          )}>
                            {s.symbol}
                          </span>
                        )}
                        <span className={cn(
                          "rounded-md px-1.5 py-0.5 text-[10px] font-bold",
                          mode === "LIVE"
                            ? isLight ? "bg-rose-100 text-rose-600" : "bg-rose-500/15 text-rose-400"
                            : isLight ? "bg-amber-100 text-amber-600" : "bg-amber-500/15 text-amber-400",
                        )}>{mode}</span>
                        <span className={cn(
                          "rounded-md px-1.5 py-0.5 text-[10px] font-bold",
                          isLight ? "bg-emerald-50 text-emerald-700" : "bg-emerald-500/15 text-emerald-400",
                        )}>{runtime}</span>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>

          {/* Recent Signals */}
          <div className={cn("rounded-2xl border shadow-sm", cardBg)}>
            <div className={cn("flex items-center justify-between border-b px-5 py-3.5", dividerCls)}>
              <h3 className={cn("font-bold text-sm", headingCls)}>Latest Signals</h3>
              <Link to="/signals" className={cn("flex items-center gap-1 text-xs font-semibold",
                isLight ? "text-sky-600 hover:text-sky-700" : "text-sky-400 hover:text-sky-300")}>
                All signals <ChevronRight className="h-3.5 w-3.5" />
              </Link>
            </div>
            <div className="divide-y" style={{ borderColor: isLight ? "#f3f4f6" : "rgba(255,255,255,0.05)" }}>
              {workstationQ.isLoading && [1, 2, 3].map((i) => (
                <div key={i} className="flex items-center justify-between px-5 py-3 gap-3">
                  <Skeleton className="h-4 w-20" />
                  <Skeleton className="h-4 w-14" />
                </div>
              ))}
              {!workstationQ.isLoading && latestSignals.length === 0 && (
                <div className={cn("px-5 py-8 text-center text-xs", mutedCls)}>
                  No signals yet — strategies begin scanning at market open.
                </div>
              )}
              {latestSignals.map((s) => {
                const type = (s.signalType ?? "").toUpperCase();
                return (
                  <div key={s.id} className={cn("flex items-center justify-between gap-3 px-5 py-3 transition", rowHover)}>
                    <div className="flex items-center gap-2.5 min-w-0">
                      <CircleDot className={cn("h-3.5 w-3.5 shrink-0",
                        type === "BUY" ? "text-emerald-500" : type === "SELL" ? "text-rose-500" : "text-neutral-400")} />
                      <div className="min-w-0">
                        <div className={cn("font-mono text-xs font-bold truncate", headingCls)}>{s.symbol ?? "—"}</div>
                        <div className={cn("truncate text-[10px]", mutedCls)}>{s.strategyName ?? "—"}</div>
                      </div>
                    </div>
                    <div className="flex flex-col items-end gap-0.5 shrink-0">
                      <span className={cn(
                        "rounded-md px-1.5 py-0.5 text-[10px] font-black",
                        type === "BUY" ? isLight ? "bg-emerald-100 text-emerald-700" : "bg-emerald-500/15 text-emerald-400"
                          : type === "SELL" ? isLight ? "bg-rose-100 text-rose-700" : "bg-rose-500/15 text-rose-400"
                          : isLight ? "bg-neutral-100 text-neutral-500" : "bg-neutral-700/50 text-neutral-400"
                      )}>{type || "—"}</span>
                      <span className={cn("text-[10px]", mutedCls)}>{fmtTime(s.createdAt)}</span>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>

          {/* Market Watch */}
          <div className={cn("rounded-2xl border shadow-sm", cardBg)}>
            <div className={cn("border-b px-5 py-3.5", dividerCls)}>
              <h3 className={cn("font-bold text-sm", headingCls)}>Market Watch</h3>
            </div>
            <div className="divide-y" style={{ borderColor: isLight ? "#f3f4f6" : "rgba(255,255,255,0.05)" }}>
              {watchQ.isLoading && [1,2,3].map(i => (
                <div key={i} className="flex items-center justify-between px-5 py-3">
                  <Skeleton className="h-4 w-20" /><Skeleton className="h-4 w-16" />
                </div>
              ))}
              {(watchQ.data ?? []).map((w) => {
                const chg = safeNum(w.changePct) ?? 0;
                return (
                  <div key={w.symbol}
                    onClick={() => setChartSymbol(w.symbol)}
                    className={cn(
                      "flex cursor-pointer items-center justify-between px-5 py-3 transition",
                      rowHover,
                      chartSymbol === w.symbol ? rowBg : ""
                    )}>
                    <div className={cn("font-mono text-xs font-bold", headingCls)}>
                      {w.symbol.replace("_FUT", "").replace("_", " ")}
                    </div>
                    <div className="text-right">
                      <div className={cn("font-mono text-xs font-bold", headingCls)}>{w.price ?? "—"}</div>
                      <div className={cn("text-[10px] font-semibold", chg >= 0 ? "text-emerald-500" : "text-rose-500")}>
                        {chg >= 0 ? "▲" : "▼"} {Math.abs(chg).toFixed(2)}%
                      </div>
                    </div>
                  </div>
                );
              })}
              {!watchQ.isLoading && (watchQ.data ?? []).length === 0 && (
                <div className={cn("px-5 py-6 text-center text-xs", mutedCls)}>No market data — connect broker</div>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
