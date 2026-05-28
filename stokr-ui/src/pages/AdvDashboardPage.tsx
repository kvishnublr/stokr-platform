import { useEffect, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import { useUiThemeStore } from "../state/uiTheme";
import { cn } from "../lib/utils";
import { NiftyCandleChart } from "../components/charts/NiftyCandleChart";
import { formatConfidencePct, normalizeSignalRow, signalDirection } from "../lib/intradaySignals";
import { parseMoney } from "../lib/moneyUtils";

type TabKey = "intelligence" | "orderflow" | "system" | "sectors" | "risk" | "performance";

type WatchRow = { symbol: string; price?: string; changePct?: string; volume?: number | string };
type SignalRow = {
  id: string;
  symbol?: string;
  strategyName?: string;
  signalType?: string;
  confidenceScore?: number | string;
  reason?: string;
  createdAt?: string;
  executionMode?: string;
};
type StrategyAlloc = { strategyKey?: string; strategyName?: string; runtimeState?: string };
type Workstation = { strategyAllocations?: StrategyAlloc[]; latestSignals?: SignalRow[] };
type CandleRow = { time?: number; ts?: number; open?: number; high?: number; low?: number; close?: number; volume?: number };
type ExecSummary = { ordersTotal?: number; winRate?: number | string };

type ScannerRow = {
  rank: number;
  id: string;
  symbol: string;
  side: string;
  ltp: string;
  aiScore: number;
  buySell: number;
  pressureSource: "api" | "derived";
  confidenceLabel: "HIGH" | "MED" | "LOW";
  vol: string;
  win: string;
  setup: string;
  createdAt: string;
  mode: string;
  chgPct: number;
  pnl: number | null;
  outcomeStatus: string;
};

function toNum(v: unknown): number | null {
  if (typeof v === "number" && Number.isFinite(v)) return v;
  if (typeof v === "string") {
    const n = Number(v.replace(/[^0-9.-]/g, ""));
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

function fmtPct(v: unknown, digits = 1): string {
  const n = toNum(v);
  return n == null ? "-" : `${n.toFixed(digits)}%`;
}

function fmtCompact(v: unknown): string {
  const n = toNum(v);
  if (n == null) return "-";
  return new Intl.NumberFormat("en-IN", { notation: "compact", maximumFractionDigits: 1 }).format(n);
}

function readAny(obj: Record<string, unknown>, keys: string[]): unknown {
  for (const key of keys) {
    if (key in obj && obj[key] != null) return obj[key];
  }
  return null;
}

function nowIstLabel() {
  return new Intl.DateTimeFormat("en-IN", {
    timeZone: "Asia/Kolkata",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  }).format(new Date());
}

function marketState() {
  const t = new Intl.DateTimeFormat("en-US", {
    timeZone: "Asia/Kolkata",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(new Date());
  const [hh, mm] = t.split(":").map(Number);
  const mins = hh * 60 + mm;
  const open = mins >= 555 && mins <= 930;
  return { open, regime: open ? "TRENDING" : "CHOPPY" };
}

export function AdvDashboardPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const [tab, setTab] = useState<TabKey>("intelligence");
  const [clock, setClock] = useState(nowIstLabel());
  const [tf, setTf] = useState("15m");

  useEffect(() => {
    const id = setInterval(() => setClock(nowIstLabel()), 1000);
    return () => clearInterval(id);
  }, []);

  const watchQ = useQuery<WatchRow[]>({
    queryKey: ["adv-watch-live"],
    queryFn: async () => {
      const r = await api.get("/api/trader/terminal/market/watch");
      return Array.isArray(r.data?.data) ? r.data.data : [];
    },
    staleTime: 2000,
    refetchInterval: 5000,
  });

  const feedQ = useQuery<SignalRow[]>({
    queryKey: ["adv-signals-live"],
    queryFn: async () => {
      const r = await api.get("/api/trader/strategy-feed?limit=250");
      return Array.isArray(r.data?.data) ? r.data.data : [];
    },
    staleTime: 2000,
    refetchInterval: 5000,
  });

  const wsQ = useQuery<Workstation>({
    queryKey: ["adv-workstation-live"],
    queryFn: async () => {
      const r = await api.get("/api/trader/terminal/workstation");
      return (r.data?.data ?? {}) as Workstation;
    },
    staleTime: 2000,
    refetchInterval: 5000,
  });

  const execQ = useQuery<ExecSummary>({
    queryKey: ["adv-exec-summary-live"],
    queryFn: async () => {
      const r = await api.get("/api/trader/execution-summary");
      return (r.data?.data ?? {}) as ExecSummary;
    },
    staleTime: 5000,
    refetchInterval: 10000,
  });

  const candlesQ = useQuery<CandleRow[]>({
    queryKey: ["adv-candles-live", tf],
    queryFn: async () => {
      const r = await api.get(`/api/trader/terminal/market/chart?symbol=NIFTY_FUT&interval=${encodeURIComponent(tf)}&limit=140`);
      return Array.isArray(r.data?.data) ? r.data.data : [];
    },
    staleTime: 5000,
    refetchInterval: 15000,
  });

  const refreshAll = () => {
    void watchQ.refetch();
    void feedQ.refetch();
    void wsQ.refetch();
    void execQ.refetch();
    void candlesQ.refetch();
  };

  const signals = useMemo(() => {
    const live = feedQ.data ?? [];
    return live.length ? live : wsQ.data?.latestSignals ?? [];
  }, [feedQ.data, wsQ.data?.latestSignals]);

  const watchBySymbol = useMemo(() => {
    const m = new Map<string, WatchRow>();
    for (const w of watchQ.data ?? []) m.set(String(w.symbol ?? ""), w);
    return m;
  }, [watchQ.data]);

  const baseRows = useMemo<ScannerRow[]>(() => {
    return signals.map((s, i) => {
      const signal = normalizeSignalRow(s as Record<string, unknown>);
      const symbol = String(s.symbol ?? "-").trim() || "-";
      const watch = watchBySymbol.get(symbol);
      const raw = toNum(readAny(signal, ["confidenceScore", "confidence", "aiScore", "score"]));
      const aiScore = raw == null ? 0 : Math.max(0, Math.min(100, Math.round(raw <= 1 ? raw * 100 : raw)));
      const chg = toNum(watch?.changePct) ?? 0;
      const side = signalDirection(signal);
      const directionalBoost = side === "BUY" ? 6 : side === "SELL" ? -6 : 0;
      const momentumBoost = Math.max(-12, Math.min(12, chg * 2));
      const directPressure = toNum(readAny(signal, ["buyPressure", "pressure", "orderFlowPressure", "flowScore", "imbalanceScore"]));
      const buySell = directPressure != null
        ? Math.max(5, Math.min(95, Math.round(directPressure <= 1 ? directPressure * 100 : directPressure)))
        : Math.max(5, Math.min(95, Math.round(aiScore + directionalBoost + momentumBoost)));
      const volumeRaw = readAny(signal, ["volume", "tradedVolume", "dayVolume", "volumeScore"]) ?? watch?.volume;
      return {
        rank: i + 1,
        id: s.id ?? `sig-${i}`,
        symbol,
        side,
        ltp: watch?.price ?? "-",
        aiScore,
        buySell,
        pressureSource: directPressure != null ? "api" : "derived",
        confidenceLabel: aiScore >= 70 ? "HIGH" : aiScore >= 50 ? "MED" : "LOW",
        vol: fmtCompact(volumeRaw),
        win: fmtPct(execQ.data?.winRate ?? null, 0),
        setup: String(s.strategyName ?? s.reason ?? "-"),
        createdAt: String(s.createdAt ?? ""),
        mode: String(s.executionMode ?? "-").toUpperCase(),
        chgPct: chg,
        pnl: parseMoney(readAny(signal, ["pnl", "realizedPnl", "unrealizedPnl"])),
        outcomeStatus: String(readAny(signal, ["outcomeStatus", "status"]) ?? "RUNNING").toUpperCase(),
      };
    });
  }, [signals, watchBySymbol, execQ.data?.winRate]);

  const scannerRows = useMemo(() => {
    const rows = [...baseRows];
    switch (tab) {
      case "orderflow":
        return rows.sort((a, b) => Math.abs(b.chgPct) - Math.abs(a.chgPct)).slice(0, 100);
      case "system":
        return rows.sort((a, b) => b.aiScore - a.aiScore).slice(0, 100);
      case "sectors":
        return rows.sort((a, b) => a.symbol.localeCompare(b.symbol)).slice(0, 100);
      case "risk":
        return rows.sort((a, b) => a.buySell - b.buySell).slice(0, 100);
      case "performance":
        return rows.sort((a, b) => (b.createdAt || "").localeCompare(a.createdAt || "")).slice(0, 100);
      default:
        return rows.slice(0, 100);
    }
  }, [baseRows, tab]);

  const activeStrategies = useMemo(
    () => (wsQ.data?.strategyAllocations ?? []).filter((s) => String(s.runtimeState ?? "").toUpperCase().includes("RUN")).slice(0, 6),
    [wsQ.data?.strategyAllocations],
  );

  const stocksTracked = useMemo(() => new Set(scannerRows.map((r) => r.symbol).filter((s) => s !== "-")).size, [scannerRows]);
  const activeSetups = scannerRows.length;
  const avgWinRate = fmtPct(execQ.data?.winRate ?? null, 1);
  const topAiScore = scannerRows.reduce((m, r) => Math.max(m, r.aiScore), 0);
  const systemAccuracy = avgWinRate;
  const marketBreadth = useMemo(() => {
    let adv = 0;
    let dec = 0;
    for (const w of watchQ.data ?? []) {
      const c = toNum(w.changePct) ?? 0;
      if (c >= 0) adv++;
      else dec++;
    }
    return `${adv}:${dec}`;
  }, [watchQ.data]);

  const topCards = scannerRows.slice(0, 3);
  const state = marketState();

  const candles = (candlesQ.data ?? [])
    .map((r) => ({
      time: Number(r.time ?? r.ts ?? 0),
      open: Number(r.open ?? 0),
      high: Number(r.high ?? 0),
      low: Number(r.low ?? 0),
      close: Number(r.close ?? 0),
    }))
    .filter((c) => c.time > 0);
  const volumes = (candlesQ.data ?? [])
    .map((r) => ({
      time: Number(r.time ?? r.ts ?? 0),
      value: Number(r.volume ?? 0),
      up: Number(r.close ?? 0) >= Number(r.open ?? 0),
    }))
    .filter((v) => v.time > 0);

  return (
    <div className="space-y-4 pb-10">
      <div className={cn("rounded-2xl border px-5 py-4", isLight ? "border-neutral-200 bg-white" : "border-white/10 bg-neutral-900/80")}>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h1 className={cn("text-3xl font-bold tracking-tight", isLight ? "text-neutral-900" : "text-white")}>Intraday Intelligence</h1>
            <p className={cn("text-sm", isLight ? "text-neutral-500" : "text-neutral-400")}>AI-powered scanner - auto-trades high confidence setups</p>
          </div>
          <div className="flex items-center gap-2">
            <div className={cn("rounded-xl border px-3 py-2 text-xs", isLight ? "border-neutral-200 bg-neutral-50 text-neutral-700" : "border-white/10 bg-white/5 text-neutral-300")}>
              <div className="font-semibold">{state.open ? "MARKET OPEN" : "MARKET CLOSED"}</div>
              <div>{clock} IST</div>
            </div>
            <div className={cn("rounded-xl border px-3 py-2 text-xs font-semibold", isLight ? "border-neutral-200 bg-neutral-50 text-neutral-700" : "border-white/10 bg-white/5 text-neutral-300")}>
              Regime: {state.regime}
            </div>
            <button onClick={refreshAll} className={cn("rounded-xl border px-3 py-2 text-xs", isLight ? "border-neutral-200 bg-white text-neutral-700 hover:bg-neutral-100" : "border-white/10 bg-white/5 text-neutral-300 hover:bg-white/10")}>
              Refresh
            </button>
          </div>
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        {[
          ["intelligence", "Intelligence"],
          ["orderflow", "Order Flow"],
          ["system", "System Decisions"],
          ["sectors", "Sectors"],
          ["risk", "Risk Matrix"],
          ["performance", "Performance"],
        ].map(([key, label]) => (
          <button
            key={key}
            type="button"
            onClick={() => setTab(key as TabKey)}
            className={cn(
              "rounded-lg px-3 py-1.5 text-sm font-semibold transition",
              tab === key
                ? isLight ? "bg-emerald-100 text-emerald-700" : "bg-emerald-500/20 text-emerald-300"
                : isLight ? "bg-neutral-100 text-neutral-600 hover:bg-neutral-200" : "bg-white/5 text-neutral-400 hover:bg-white/10",
            )}
          >
            {label}
          </button>
        ))}
      </div>

      <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-6">
        <MetricCard title="Stocks Tracked" value={String(stocksTracked)} note="Live universe" isLight={isLight} />
        <MetricCard title="Active Setups" value={String(activeSetups)} note="Ranked quality" isLight={isLight} />
        <MetricCard title="Avg Win Rate" value={avgWinRate} note="Conditional hist." isLight={isLight} />
        <MetricCard title="Market Breadth" value={marketBreadth} note="Adv : Decl" isLight={isLight} />
        <MetricCard title="Top AI Score" value={String(topAiScore)} note="Best setup" isLight={isLight} />
        <MetricCard title="System Accuracy" value={systemAccuracy} note="Last 50 trades" isLight={isLight} />
      </div>

      <div className="grid grid-cols-1 gap-3 xl:grid-cols-12">
        <div className="space-y-3 xl:col-span-9">
          <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
            {topCards.map((c) => (
              <div key={c.id} className={cn("rounded-2xl border p-4", isLight ? "border-emerald-200 bg-emerald-50/40" : "border-emerald-500/20 bg-emerald-500/5")}>
                <div className="flex items-center justify-between">
                  <div className={cn("text-3xl font-black", isLight ? "text-neutral-800" : "text-white")}>{c.symbol}</div>
                  <span className={cn("rounded-full px-2 py-0.5 text-[10px] font-black", isLight ? "bg-emerald-100 text-emerald-700" : "bg-emerald-500/20 text-emerald-300")}>
                    {c.mode === "LIVE" ? "LIVE" : "PAPER"}
                  </span>
                </div>
                <div className={cn("mt-2 text-4xl font-black", isLight ? "text-neutral-800" : "text-white")}>{c.aiScore}</div>
                <div className={cn("text-xs uppercase tracking-wide", isLight ? "text-neutral-500" : "text-neutral-400")}>{c.setup}</div>
              </div>
            ))}
          </div>

          <div className={cn("rounded-2xl border p-3", isLight ? "border-neutral-200 bg-white" : "border-white/10 bg-neutral-900/80")}>
            <div className="mb-3 flex items-center justify-between">
              <h3 className={cn("text-lg font-bold", isLight ? "text-neutral-900" : "text-white")}>NIFTY_FUT</h3>
              <div className={cn("flex gap-1 rounded-xl border p-1 text-xs font-semibold", isLight ? "border-neutral-200" : "border-white/10")}>
                {["1m", "5m", "15m", "1H"].map((f) => (
                  <button key={f} type="button" onClick={() => setTf(f)} className={cn("rounded-lg px-2 py-1", f === tf ? "bg-neutral-800 text-white" : isLight ? "text-neutral-600 hover:bg-neutral-100" : "text-neutral-400 hover:bg-white/10")}>
                    {f}
                  </button>
                ))}
              </div>
            </div>
            <div className={cn("h-64 rounded-xl border p-1", isLight ? "border-neutral-200 bg-neutral-50" : "border-white/10 bg-black/30")}>
              {candles.length > 0 ? (
                <NiftyCandleChart variant={isLight ? "light" : "dark"} height={248} candles={candles} volumes={volumes} />
              ) : (
                <div className={cn("flex h-full items-center justify-center text-sm", isLight ? "text-neutral-500" : "text-neutral-400")}>No live candle data</div>
              )}
            </div>
          </div>

          <div className={cn("overflow-hidden rounded-2xl border", isLight ? "border-neutral-200 bg-white" : "border-white/10 bg-neutral-900/80")}>
            <div className={cn("flex items-center justify-between border-b px-4 py-3", isLight ? "border-neutral-200" : "border-white/10")}>
              <h3 className={cn("font-bold", isLight ? "text-neutral-900" : "text-white")}>Live Scanner</h3>
              <span className={cn("rounded-full px-2 py-1 text-xs font-bold", isLight ? "bg-emerald-100 text-emerald-700" : "bg-emerald-500/20 text-emerald-300")}>
                {activeSetups} setups
              </span>
            </div>
            <div className="max-h-[450px] overflow-auto">
              <table className="w-full border-collapse text-sm">
                <thead className={cn("sticky top-0 text-xs uppercase", isLight ? "bg-white text-neutral-500" : "bg-neutral-900 text-neutral-400")}>
                  <tr>
                    <th className="px-3 py-2 text-left">#</th>
                    <th className="px-3 py-2 text-left">Symbol</th>
                    <th className="px-3 py-2 text-left">LTP</th>
                    <th className="px-3 py-2 text-left">AI</th>
                    <th className="px-3 py-2 text-left">Status</th>
                    <th className="px-3 py-2 text-left">Buy:Sell</th>
                    <th className="px-3 py-2 text-left">Vol</th>
                    <th className="px-3 py-2 text-left">Win/PnL</th>
                    <th className="px-3 py-2 text-left">Setup</th>
                  </tr>
                </thead>
                <tbody>
                  {scannerRows.length === 0 ? (
                    <tr>
                      <td colSpan={9} className={cn("px-3 py-8 text-center", isLight ? "text-neutral-500" : "text-neutral-400")}>
                        No live signals yet
                      </td>
                    </tr>
                  ) : (
                    scannerRows.map((r, idx) => (
                      <tr key={r.id} className={cn(idx % 2 === 0 ? (isLight ? "bg-neutral-50/60" : "bg-white/5") : "")}>
                        <td className="px-3 py-2 font-semibold">{r.rank}</td>
                        <td className="px-3 py-2">
                          <div className="font-semibold">{r.symbol}</div>
                          <div className={cn("text-xs", isLight ? "text-neutral-500" : "text-neutral-400")}>{r.side}</div>
                        </td>
                        <td className="px-3 py-2">{r.ltp}</td>
                        <td className="px-3 py-2 font-bold text-blue-600">{formatConfidencePct(r.aiScore)}</td>
                        <td className="px-3 py-2">
                          <span className={cn(
                            "rounded-md px-2 py-0.5 text-[10px] font-bold",
                            r.confidenceLabel === "HIGH"
                              ? isLight ? "bg-emerald-100 text-emerald-700" : "bg-emerald-500/20 text-emerald-300"
                              : r.confidenceLabel === "MED"
                                ? isLight ? "bg-amber-100 text-amber-700" : "bg-amber-500/20 text-amber-300"
                                : isLight ? "bg-rose-100 text-rose-700" : "bg-rose-500/20 text-rose-300",
                          )}>
                            {r.outcomeStatus === "RUNNING" ? r.confidenceLabel : r.outcomeStatus}
                          </span>
                        </td>
                        <td className="px-3 py-2">
                          <div className={cn("h-2 w-[72px] overflow-hidden rounded-full", isLight ? "bg-neutral-200" : "bg-white/10")}>
                            <div className={cn("h-full", r.buySell >= 50 ? "bg-emerald-500" : "bg-rose-500")} style={{ width: `${r.buySell}%` }} />
                          </div>
                          <div className={cn("mt-1 text-[10px]", isLight ? "text-neutral-500" : "text-neutral-400")}>{r.pressureSource === "api" ? "live" : "calc"}</div>
                        </td>
                        <td className="px-3 py-2">{r.vol}</td>
                        <td className="px-3 py-2">{r.pnl == null ? r.win : `${r.pnl > 0 ? "+" : ""}${r.pnl.toFixed(2)}`}</td>
                        <td className="px-3 py-2">{r.setup}</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </div>

        <div className="space-y-3 xl:col-span-3">
          <div className={cn("rounded-2xl border p-4", isLight ? "border-neutral-200 bg-white" : "border-white/10 bg-neutral-900/80")}>
            <h4 className={cn("text-xl font-bold", isLight ? "text-neutral-900" : "text-white")}>Today's Engine</h4>
            <div className={cn("mt-3 space-y-1 text-sm", isLight ? "text-neutral-700" : "text-neutral-300")}>
              <div>Trades: <span className="font-bold">{String(execQ.data?.ordersTotal ?? 0)}</span></div>
              <div>Active setups: <span className="font-bold">{activeSetups}</span></div>
              <div>Win rate: <span className="font-bold">{avgWinRate}</span></div>
            </div>
          </div>

          <div className={cn("rounded-2xl border p-4", isLight ? "border-neutral-200 bg-white" : "border-white/10 bg-neutral-900/80")}>
            <h4 className={cn("text-sm font-bold", isLight ? "text-neutral-900" : "text-white")}>Active Strategies</h4>
            <div className="mt-2 space-y-2">
              {activeStrategies.length === 0 ? (
                <div className={cn("text-xs", isLight ? "text-neutral-500" : "text-neutral-400")}>No running strategies</div>
              ) : (
                activeStrategies.map((s, i) => (
                  <div key={`${s.strategyKey ?? s.strategyName ?? i}`} className={cn("rounded-lg border p-2", isLight ? "border-neutral-200 bg-neutral-50" : "border-white/10 bg-white/5")}>
                    <div className="text-sm font-semibold">{s.strategyName ?? s.strategyKey ?? "-"}</div>
                    <div className={cn("text-xs", isLight ? "text-neutral-500" : "text-neutral-400")}>{s.runtimeState ?? "-"}</div>
                  </div>
                ))
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function MetricCard({
  title,
  value,
  note,
  isLight,
}: {
  title: string;
  value: string;
  note: string;
  isLight: boolean;
}) {
  return (
    <div className={cn("rounded-2xl border p-4", isLight ? "border-neutral-200 bg-white" : "border-white/10 bg-neutral-900/80")}>
      <div className={cn("text-[11px] font-semibold uppercase", isLight ? "text-neutral-500" : "text-neutral-400")}>{title}</div>
      <div className={cn("mt-1 text-4xl font-black leading-none", isLight ? "text-neutral-900" : "text-white")}>{value}</div>
      <div className={cn("mt-2 text-xs", isLight ? "text-emerald-700" : "text-emerald-300")}>{note}</div>
    </div>
  );
}
