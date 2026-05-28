import { useEffect, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import {
  AdvScannerRow,
  AdvTerminalSnapshot,
  ExecutionStatus,
  fetchAdvTerminal,
} from "../api/advDashboard";
import { useUiThemeStore } from "../state/uiTheme";
import { cn } from "../lib/utils";
import { NiftyCandleChart } from "../components/charts/NiftyCandleChart";
import { formatConfidencePct } from "../lib/intradaySignals";

type TabKey = "intelligence" | "orderflow" | "system" | "sectors" | "risk" | "performance";

type WatchRow = { symbol: string; price?: string; changePct?: string; volume?: number | string };
type CandleRow = { time?: number; ts?: number; open?: number; high?: number; low?: number; close?: number; volume?: number };
type StrategyAlloc = { strategyKey?: string; strategyName?: string; runtimeState?: string };
type Workstation = { strategyAllocations?: StrategyAlloc[] };

type DisplayRow = AdvScannerRow & {
  id: string;
  ltpDisplay: string;
  changePct: number;
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
  return n == null ? "—" : `${n.toFixed(digits)}%`;
}

function fmtCompact(v: unknown): string {
  const n = toNum(v);
  if (n == null) return "—";
  return new Intl.NumberFormat("en-IN", { notation: "compact", maximumFractionDigits: 1 }).format(n);
}

function fmtPrice(v: unknown): string {
  const n = toNum(v);
  if (n == null) return "—";
  return n.toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function executionBadgeClass(status: string, isLight: boolean): string {
  const base = "rounded-md px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide";
  switch (status) {
    case "EXECUTABLE":
      return cn(base, isLight ? "bg-emerald-100 text-emerald-800" : "bg-emerald-500/20 text-emerald-300");
    case "EXECUTED":
      return cn(base, isLight ? "bg-emerald-200 text-emerald-900" : "bg-emerald-500/30 text-emerald-200");
    case "WATCHLIST":
      return cn(base, isLight ? "bg-blue-100 text-blue-800" : "bg-blue-500/20 text-blue-300");
    case "COOLDOWN":
      return cn(base, isLight ? "bg-amber-100 text-amber-800" : "bg-amber-500/20 text-amber-300");
    case "BLOCKED":
    case "OMS_REJECTED":
    case "QUALITY_REJECTED":
    case "REJECTED":
      return cn(base, isLight ? "bg-rose-100 text-rose-800" : "bg-rose-500/20 text-rose-300");
    case "INTELLIGENCE_ONLY":
      return cn(base, isLight ? "bg-neutral-200 text-neutral-600" : "bg-white/10 text-neutral-400");
    default:
      return cn(base, isLight ? "bg-neutral-100 text-neutral-700" : "bg-white/10 text-neutral-300");
  }
}

function executionLabel(status: string): string {
  return status.replace(/_/g, " ");
}

export function AdvDashboardPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const [tab, setTab] = useState<TabKey>("intelligence");
  const [tf, setTf] = useState("15m");
  const [selectedRow, setSelectedRow] = useState<DisplayRow | null>(null);

  const terminalQ = useQuery<AdvTerminalSnapshot>({
    queryKey: ["adv-terminal-v8"],
    queryFn: fetchAdvTerminal,
    staleTime: 2000,
    refetchInterval: 10000,
  });

  const watchQ = useQuery<WatchRow[]>({
    queryKey: ["adv-watch-live"],
    queryFn: async () => {
      const r = await api.get("/api/trader/terminal/market/watch");
      return Array.isArray(r.data?.data) ? r.data.data : [];
    },
    staleTime: 2000,
    refetchInterval: 10000,
  });

  const wsQ = useQuery<Workstation>({
    queryKey: ["adv-workstation-live"],
    queryFn: async () => {
      const r = await api.get("/api/trader/terminal/workstation");
      return (r.data?.data ?? {}) as Workstation;
    },
    staleTime: 5000,
    refetchInterval: 15000,
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
    void terminalQ.refetch();
    void watchQ.refetch();
    void wsQ.refetch();
    void candlesQ.refetch();
  };

  const terminal = terminalQ.data;
  const scanIntervalSec = terminal?.scanIntervalSec ?? terminal?.liveControl?.scanIntervalSec ?? 10;

  const watchBySymbol = useMemo(() => {
    const m = new Map<string, WatchRow>();
    for (const w of watchQ.data ?? []) m.set(String(w.symbol ?? ""), w);
    return m;
  }, [watchQ.data]);

  const displayRows = useMemo<DisplayRow[]>(() => {
    const rows = terminal?.scannerRows ?? [];
    return rows.map((row, i) => {
      const symbol = String(row.symbol ?? "—");
      const watch = watchBySymbol.get(symbol);
      const ltp = watch?.price ?? fmtPrice(row.ltp);
      return {
        ...row,
        rank: row.rank ?? i + 1,
        id: row.signalId ?? `${symbol}-${row.strategy ?? i}`,
        aiScore: toNum(row.aiScore) ?? 0,
        executionStatus: (row.executionStatus ?? row.status ?? "INTELLIGENCE_ONLY") as ExecutionStatus,
        ltpDisplay: ltp,
        changePct: toNum(watch?.changePct) ?? 0,
      };
    });
  }, [terminal?.scannerRows, watchBySymbol]);

  const tabRows = useMemo(() => {
    const rows = [...displayRows];
    switch (tab) {
      case "orderflow":
        return rows.sort((a, b) => Math.abs(b.changePct) - Math.abs(a.changePct));
      case "system":
        return rows.sort((a, b) => {
          const rank = (s: string) => {
            if (s === "EXECUTABLE") return 0;
            if (s === "EXECUTED") return 1;
            if (s === "WATCHLIST") return 2;
            return 3;
          };
          return rank(String(a.executionStatus)) - rank(String(b.executionStatus)) || b.aiScore - a.aiScore;
        });
      case "sectors":
        return rows.sort((a, b) => a.symbol.localeCompare(b.symbol));
      case "risk":
        return rows.filter((r) => ["BLOCKED", "REJECTED", "OMS_REJECTED", "QUALITY_REJECTED", "COOLDOWN"].includes(String(r.executionStatus)));
      case "performance":
        return rows.filter((r) => r.outcomeStatus || r.executionStatus === "EXECUTED");
      default:
        return rows;
    }
  }, [displayRows, tab]);

  const metrics = terminal?.metrics ?? {};
  const liveControl = terminal?.liveControl;
  const topCards = (terminal?.liveCards?.length ? terminal.liveCards : displayRows.filter((r) =>
    ["EXECUTABLE", "WATCHLIST", "EXECUTED"].includes(String(r.executionStatus)),
  )).slice(0, 3);

  const activeStrategies = useMemo(
    () => (wsQ.data?.strategyAllocations ?? []).filter((s) => String(s.runtimeState ?? "").toUpperCase().includes("RUN")).slice(0, 6),
    [wsQ.data?.strategyAllocations],
  );

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

  const executableCount = toNum(metrics.executableCount) ?? displayRows.filter((r) => r.executionStatus === "EXECUTABLE").length;
  const engine = terminal?.engine ?? {};

  return (
    <div className="space-y-4 pb-10">
      <div className={cn("rounded-2xl border px-5 py-4", isLight ? "border-neutral-200 bg-white" : "border-white/10 bg-neutral-900/80")}>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h1 className={cn("text-3xl font-bold tracking-tight", isLight ? "text-neutral-900" : "text-white")}>Intraday Intelligence</h1>
            <p className={cn("text-sm", isLight ? "text-neutral-500" : "text-neutral-400")}>
              Production pipeline terminal — scan every {scanIntervalSec}s · {terminal?.truthSource ?? "PRODUCTION_PIPELINE"}
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <div className={cn("rounded-xl border px-3 py-2 text-xs", isLight ? "border-neutral-200 bg-neutral-50 text-neutral-700" : "border-white/10 bg-white/5 text-neutral-300")}>
              <div className="font-semibold">{terminal?.marketOpen ? "MARKET OPEN" : "MARKET CLOSED"}</div>
              <div>{terminal?.istTime ?? "—"} IST</div>
            </div>
            <div className={cn("rounded-xl border px-3 py-2 text-xs font-semibold", isLight ? "border-neutral-200 bg-neutral-50 text-neutral-700" : "border-white/10 bg-white/5 text-neutral-300")}>
              Regime: {terminal?.marketRegime ?? "—"}
            </div>
            <button onClick={refreshAll} className={cn("rounded-xl border px-3 py-2 text-xs", isLight ? "border-neutral-200 bg-white text-neutral-700 hover:bg-neutral-100" : "border-white/10 bg-white/5 text-neutral-300 hover:bg-white/10")}>
              Refresh
            </button>
          </div>
        </div>
        {terminal?.regimeNarrative ? (
          <p className={cn("mt-2 text-xs", isLight ? "text-neutral-600" : "text-neutral-400")}>{terminal.regimeNarrative}</p>
        ) : null}
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
        <MetricCard title="Stocks Tracked" value={String(metrics.stocksTracked ?? "—")} note="Live universe" isLight={isLight} />
        <MetricCard title="Active Setups" value={String(metrics.activeSetups ?? displayRows.length)} note="Pipeline rows" isLight={isLight} />
        <MetricCard title="Executable" value={String(executableCount)} note="OMS eligible now" isLight={isLight} accent />
        <MetricCard title="Market Breadth" value={String(metrics.marketBreadth ?? "—")} note="Adv : Decl" isLight={isLight} />
        <MetricCard title="Top AI Score" value={String(metrics.topScore ?? 0)} note="Best setup" isLight={isLight} />
        <MetricCard title="Scan Cadence" value={`${scanIntervalSec}s`} note="Honest interval" isLight={isLight} />
      </div>

      <div className="grid grid-cols-1 gap-3 xl:grid-cols-12">
        <div className="space-y-3 xl:col-span-9">
          <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
            {topCards.length === 0 ? (
              <div className={cn("col-span-3 rounded-2xl border p-6 text-center text-sm", isLight ? "border-neutral-200 bg-neutral-50 text-neutral-500" : "border-white/10 bg-white/5 text-neutral-400")}>
                No executable or watchlist setups in production pipeline
              </div>
            ) : (
              topCards.map((c) => {
                const status = String(c.executionStatus ?? c.status ?? "WATCHLIST");
                return (
                  <div key={String(c.signalId ?? c.symbol)} className={cn("rounded-2xl border p-4", isLight ? "border-emerald-200 bg-emerald-50/40" : "border-emerald-500/20 bg-emerald-500/5")}>
                    <div className="flex items-center justify-between gap-2">
                      <div className={cn("text-2xl font-black", isLight ? "text-neutral-800" : "text-white")}>{c.symbol}</div>
                      <span className={executionBadgeClass(status, isLight)}>{executionLabel(status)}</span>
                    </div>
                    <div className={cn("mt-2 text-4xl font-black", isLight ? "text-neutral-800" : "text-white")}>{c.aiScore ?? 0}</div>
                    <div className={cn("text-xs uppercase tracking-wide", isLight ? "text-neutral-500" : "text-neutral-400")}>{c.setupType ?? c.strategy ?? "—"}</div>
                    <div className={cn("mt-2 text-[11px]", isLight ? "text-neutral-600" : "text-neutral-400")}>
                      {c.effectiveMode ?? "—"} route · {c.tradeQuality ?? "—"}
                    </div>
                    {c.rejectionReason ? (
                      <div className={cn("mt-1 text-[11px] font-medium", isLight ? "text-rose-700" : "text-rose-300")}>{c.rejectionReason}</div>
                    ) : null}
                  </div>
                );
              })
            )}
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
              <h3 className={cn("font-bold", isLight ? "text-neutral-900" : "text-white")}>Production Scanner</h3>
              <span className={cn("rounded-full px-2 py-1 text-xs font-bold", isLight ? "bg-emerald-100 text-emerald-700" : "bg-emerald-500/20 text-emerald-300")}>
                {executableCount} executable · {displayRows.length} total
              </span>
            </div>
            <div className="max-h-[480px] overflow-auto">
              <table className="w-full border-collapse text-sm">
                <thead className={cn("sticky top-0 text-[10px] uppercase", isLight ? "bg-white text-neutral-500" : "bg-neutral-900 text-neutral-400")}>
                  <tr>
                    <th className="px-2 py-2 text-left">#</th>
                    <th className="px-2 py-2 text-left">Symbol</th>
                    <th className="px-2 py-2 text-left">AI</th>
                    <th className="px-2 py-2 text-left">Execution</th>
                    <th className="px-2 py-2 text-left">Quality</th>
                    <th className="px-2 py-2 text-left">Mode</th>
                    <th className="px-2 py-2 text-left">Rejection / Reason</th>
                    <th className="px-2 py-2 text-left">Age</th>
                  </tr>
                </thead>
                <tbody>
                  {terminalQ.isLoading ? (
                    <tr>
                      <td colSpan={8} className={cn("px-3 py-8 text-center", isLight ? "text-neutral-500" : "text-neutral-400")}>Loading production pipeline…</td>
                    </tr>
                  ) : tabRows.length === 0 ? (
                    <tr>
                      <td colSpan={8} className={cn("px-3 py-8 text-center", isLight ? "text-neutral-500" : "text-neutral-400")}>No pipeline signals for this view</td>
                    </tr>
                  ) : (
                    tabRows.map((r, idx) => (
                      <tr
                        key={r.id}
                        onClick={() => setSelectedRow(r)}
                        className={cn(
                          "cursor-pointer transition hover:opacity-90",
                          idx % 2 === 0 ? (isLight ? "bg-neutral-50/60" : "bg-white/5") : "",
                          selectedRow?.id === r.id ? (isLight ? "ring-1 ring-inset ring-emerald-300" : "ring-1 ring-inset ring-emerald-500/40") : "",
                        )}
                      >
                        <td className="px-2 py-2 font-semibold">{r.rank}</td>
                        <td className="px-2 py-2">
                          <div className="font-semibold">{r.symbol}</div>
                          <div className={cn("text-[10px]", isLight ? "text-neutral-500" : "text-neutral-400")}>{r.side ?? "—"} · {r.strategy ?? r.setupType ?? "—"}</div>
                        </td>
                        <td className="px-2 py-2 font-bold text-blue-600">{formatConfidencePct(r.aiScore)}</td>
                        <td className="px-2 py-2">
                          <span className={executionBadgeClass(String(r.executionStatus), isLight)}>{executionLabel(String(r.executionStatus))}</span>
                          {r.cooldownSecRemaining ? (
                            <div className={cn("mt-1 text-[10px]", isLight ? "text-amber-700" : "text-amber-300")}>{r.cooldownSecRemaining}s cooldown</div>
                          ) : null}
                        </td>
                        <td className="px-2 py-2 text-xs">{r.qualityGate ?? "—"} / {r.riskGate ?? "—"}</td>
                        <td className="px-2 py-2 text-xs">
                          <div>{r.requestedMode ?? "—"}</div>
                          <div className={cn(isLight ? "text-neutral-500" : "text-neutral-400")}>→ {r.effectiveMode ?? "—"}</div>
                        </td>
                        <td className={cn("px-2 py-2 text-xs max-w-[220px]", isLight ? "text-neutral-700" : "text-neutral-300")}>
                          {r.rejectionReason ?? r.reason ?? (r.omsEligible ? "OMS eligible" : "—")}
                        </td>
                        <td className="px-2 py-2 text-xs">{r.signalAgeSec != null ? `${r.signalAgeSec}s` : "—"}</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
          </div>

          {selectedRow ? (
            <SignalDiagnosticsPanel row={selectedRow} isLight={isLight} onClose={() => setSelectedRow(null)} />
          ) : null}
        </div>

        <div className="space-y-3 xl:col-span-3">
          <LiveControlPanel liveControl={liveControl} isLight={isLight} scanIntervalSec={scanIntervalSec} />

          <div className={cn("rounded-2xl border p-4", isLight ? "border-neutral-200 bg-white" : "border-white/10 bg-neutral-900/80")}>
            <h4 className={cn("text-xl font-bold", isLight ? "text-neutral-900" : "text-white")}>Today&apos;s Engine</h4>
            <div className={cn("mt-3 space-y-1 text-sm", isLight ? "text-neutral-700" : "text-neutral-300")}>
              <div>Signals: <span className="font-bold">{String(engine.trades ?? metrics.executedCount ?? 0)}</span></div>
              <div>Active: <span className="font-bold">{String(engine.active ?? displayRows.length)}</span></div>
              <div>Executable: <span className="font-bold text-emerald-600">{String(engine.executable ?? executableCount)}</span></div>
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
                    <div className="text-sm font-semibold">{s.strategyName ?? s.strategyKey ?? "—"}</div>
                    <div className={cn("text-xs", isLight ? "text-neutral-500" : "text-neutral-400")}>{s.runtimeState ?? "—"}</div>
                  </div>
                ))
              )}
            </div>
          </div>

          {tab === "system" && terminal?.decisions?.length ? (
            <div className={cn("rounded-2xl border p-4", isLight ? "border-neutral-200 bg-white" : "border-white/10 bg-neutral-900/80")}>
              <h4 className={cn("text-sm font-bold", isLight ? "text-neutral-900" : "text-white")}>Recent Decisions</h4>
              <div className="mt-2 max-h-64 space-y-2 overflow-auto">
                {terminal.decisions.slice(0, 8).map((d, i) => (
                  <div key={`${d.symbol}-${d.time}-${i}`} className={cn("rounded-lg border p-2 text-xs", isLight ? "border-neutral-200" : "border-white/10")}>
                    <div className="font-semibold">{d.time} · {d.symbol}</div>
                    <div className={cn(isLight ? "text-neutral-600" : "text-neutral-400")}>{d.strategy} · AI {d.aiScore}</div>
                    <div className="mt-1">{d.rejectionReason ?? d.result}</div>
                  </div>
                ))}
              </div>
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
}

function LiveControlPanel({
  liveControl,
  isLight,
  scanIntervalSec,
}: {
  liveControl?: AdvTerminalSnapshot["liveControl"];
  isLight: boolean;
  scanIntervalSec: number;
}) {
  const items = [
    { label: "LIVE enabled", ok: liveControl?.liveEnabled },
    { label: "Platform LIVE flag", ok: liveControl?.platformLiveFlag },
    { label: "Live gate open", ok: liveControl?.liveGateOpen },
    { label: "Feed operational", ok: liveControl?.feedOperational },
    { label: "Safe startup ready", ok: liveControl?.safeStartupReady },
    { label: "Market open", ok: liveControl?.marketOpen },
  ];

  return (
    <div className={cn("rounded-2xl border p-4", isLight ? "border-neutral-200 bg-white" : "border-white/10 bg-neutral-900/80")}>
      <h4 className={cn("text-sm font-bold", isLight ? "text-neutral-900" : "text-white")}>Live Control</h4>
      <div className="mt-2 space-y-1.5">
        {items.map((item) => (
          <div key={item.label} className="flex items-center justify-between text-xs">
            <span className={isLight ? "text-neutral-600" : "text-neutral-400"}>{item.label}</span>
            <span className={cn("font-bold", item.ok ? "text-emerald-600" : "text-rose-600")}>{item.ok ? "OK" : "BLOCKED"}</span>
          </div>
        ))}
        <div className="flex items-center justify-between text-xs pt-1 border-t border-dashed border-neutral-200 dark:border-white/10">
          <span className={isLight ? "text-neutral-600" : "text-neutral-400"}>Scan interval</span>
          <span className="font-bold">{scanIntervalSec}s</span>
        </div>
        {liveControl?.feedEquityStale || liveControl?.feedIndexStale ? (
          <div className={cn("mt-2 rounded-lg p-2 text-[11px] font-medium", isLight ? "bg-rose-50 text-rose-800" : "bg-rose-500/10 text-rose-300")}>
            Feed stale — equity={String(liveControl.feedEquityStale)} index={String(liveControl.feedIndexStale)}
          </div>
        ) : null}
      </div>
    </div>
  );
}

function SignalDiagnosticsPanel({
  row,
  isLight,
  onClose,
}: {
  row: DisplayRow;
  isLight: boolean;
  onClose: () => void;
}) {
  const lifecycle = row.lifecycle ?? [];
  return (
    <div className={cn("rounded-2xl border p-4", isLight ? "border-neutral-200 bg-white" : "border-white/10 bg-neutral-900/80")}>
      <div className="flex items-start justify-between gap-3">
        <div>
          <h4 className={cn("text-lg font-bold", isLight ? "text-neutral-900" : "text-white")}>Signal Diagnostics — {row.symbol}</h4>
          <p className={cn("text-xs mt-1", isLight ? "text-neutral-500" : "text-neutral-400")}>{row.strategy ?? row.setupType} · {row.signalId ?? "preview"}</p>
        </div>
        <button type="button" onClick={onClose} className={cn("rounded-lg border px-2 py-1 text-xs", isLight ? "border-neutral-200" : "border-white/10")}>Close</button>
      </div>
      <div className="mt-4 grid grid-cols-2 gap-3 md:grid-cols-4 text-xs">
        <DiagField label="Execution Status" value={String(row.executionStatus)} isLight={isLight} />
        <DiagField label="Pipeline Stage" value={row.pipelineStage ?? "—"} isLight={isLight} />
        <DiagField label="Quality Gate" value={row.qualityGate ?? "—"} isLight={isLight} />
        <DiagField label="Risk Gate" value={row.riskGate ?? "—"} isLight={isLight} />
        <DiagField label="Requested Mode" value={row.requestedMode ?? "—"} isLight={isLight} />
        <DiagField label="Effective Mode" value={row.effectiveMode ?? "—"} isLight={isLight} />
        <DiagField label="OMS Eligible" value={row.omsEligible ? "YES" : "NO"} isLight={isLight} />
        <DiagField label="Rejection Code" value={row.rejectionCode ?? "—"} isLight={isLight} />
      </div>
      {row.rejectionReason ? (
        <div className={cn("mt-3 rounded-lg p-3 text-sm font-medium", isLight ? "bg-rose-50 text-rose-800" : "bg-rose-500/10 text-rose-300")}>
          {row.rejectionReason}
        </div>
      ) : null}
      <div className="mt-4">
        <div className={cn("text-xs font-bold uppercase mb-2", isLight ? "text-neutral-500" : "text-neutral-400")}>Lifecycle</div>
        <div className="flex flex-wrap gap-2">
          {lifecycle.length === 0 ? (
            <span className={cn("text-xs", isLight ? "text-neutral-500" : "text-neutral-400")}>No lifecycle data</span>
          ) : (
            lifecycle.map((stage, i) => (
              <span key={`${stage}-${i}`} className={cn("rounded-full px-2 py-1 text-[10px] font-bold", isLight ? "bg-neutral-100 text-neutral-700" : "bg-white/10 text-neutral-300")}>
                {i + 1}. {stage}
              </span>
            ))
          )}
        </div>
      </div>
    </div>
  );
}

function DiagField({ label, value, isLight }: { label: string; value: string; isLight: boolean }) {
  return (
    <div>
      <div className={cn("text-[10px] uppercase", isLight ? "text-neutral-500" : "text-neutral-400")}>{label}</div>
      <div className={cn("font-semibold mt-0.5", isLight ? "text-neutral-800" : "text-white")}>{value}</div>
    </div>
  );
}

function MetricCard({
  title,
  value,
  note,
  isLight,
  accent,
}: {
  title: string;
  value: string;
  note: string;
  isLight: boolean;
  accent?: boolean;
}) {
  return (
    <div className={cn("rounded-2xl border p-4", isLight ? "border-neutral-200 bg-white" : "border-white/10 bg-neutral-900/80")}>
      <div className={cn("text-[11px] font-semibold uppercase", isLight ? "text-neutral-500" : "text-neutral-400")}>{title}</div>
      <div className={cn("mt-1 text-4xl font-black leading-none", accent ? "text-emerald-600" : isLight ? "text-neutral-900" : "text-white")}>{value}</div>
      <div className={cn("mt-2 text-xs", isLight ? "text-emerald-700" : "text-emerald-300")}>{note}</div>
    </div>
  );
}
