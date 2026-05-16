import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { motion } from "framer-motion";
import { Area, AreaChart, ResponsiveContainer } from "recharts";
import {
  ArrowDownRight,
  ArrowUpRight,
  Bot,
  Crosshair,
  Globe,
  Layers,
  LayoutDashboard,
  ListOrdered,
  Loader2,
  Newspaper,
  PencilLine,
  Radio,
  ShieldCheck,
  SlidersHorizontal,
  Sparkles,
  Terminal,
  Wallet,
  LineChart as LineChartIcon,
} from "lucide-react";
import { useEffect, useId, useMemo, useState } from "react";
import type { ReactNode } from "react";
import { api } from "../api/client";
import { GlassPanel } from "../components/ds/GlassPanel";
import { cn } from "../lib/utils";
import { useSessionStore } from "../state/session";
import { useUiThemeStore } from "../state/uiTheme";
import { NiftyCandleChart } from "../components/charts/NiftyCandleChart";

type DashboardDto = {
  overview: {
    realizedPnl: string;
    unrealizedPnl: string;
    mtmPnl: string;
    openPositionCount: number;
    latestSnapshotAt: string | null;
  };
};

const DUMMY_STRATEGIES = [
  { name: "Trend Hunter Pro", mode: "LIVE" as const, pnl: "+â‚¹ 42,180", pct: "+2.81%" },
  { name: "Mean Reversion V2", mode: "PAPER" as const, pnl: "+â‚¹ 8,420", pct: "+1.06%" },
  { name: "ORB Session", mode: "LIVE" as const, pnl: "âˆ’â‚¹ 2,110", pct: "âˆ’0.44%" },
];

const DUMMY_POSITIONS = [
  { sym: "RELIANCE", qty: "120", val: "â‚¹ 1,71,240", chg: "+1.2%", up: true },
  { sym: "INFY", qty: "90", val: "â‚¹ 1,38,870", chg: "+0.4%", up: true },
  { sym: "TCS", qty: "45", val: "â‚¹ 1,84,500", chg: "âˆ’0.3%", up: false },
];

const DUMMY_ORDERS = [
  { t: "09:42:11", sym: "NIFTY24NOVFUT", side: "BUY" as const, st: "FILLED" },
  { t: "09:38:02", sym: "RELIANCE", side: "SELL" as const, st: "PENDING" },
  { t: "09:21:55", sym: "INFY", side: "BUY" as const, st: "FILLED" },
];

const DUMMY_NEWS = [
  { t: "09:41", msg: "High volatility in NIFTY options tape - desks trimming gamma." },
  { t: "09:27", msg: "Banks lead breadth; PSU flows steady into midcaps." },
  { t: "08:52", msg: "RBI watchlist  ·  commentary expected on liquidity stance." },
];

const SPARK = (seed: number) =>
  Array.from({ length: 18 }, (_, i) => ({
    x: i,
    y: 30 + Math.sin(i * 0.45 + seed) * 14 + (i % 4) * 2,
  }));

function MiniSpark({ tone }: { tone: "green" | "blue" }) {
  const gid = useId();
  const stroke = tone === "green" ? "#10b981" : "#2563eb";
  const data = useMemo(() => SPARK(tone === "green" ? 1 : 2), [tone]);
  const fillId = `spark-${gid}-${tone}`;
  return (
    <div className="h-10 w-full opacity-90">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={data}>
          <defs>
            <linearGradient id={fillId} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={stroke} stopOpacity={0.35} />
              <stop offset="100%" stopColor={stroke} stopOpacity={0} />
            </linearGradient>
          </defs>
          <Area type="monotone" dataKey="y" stroke={stroke} strokeWidth={1.5} fill={`url(#${fillId})`} />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}

function formatInr(n: number) {
  return new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 0 }).format(n);
}

export function DashboardPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const panel = isLight ? ("light" as const) : ("dark" as const);
  const displayName = useSessionStore((s) => s.displayName);
  const username = useSessionStore((s) => s.username);
  const greetName = displayName || username || "Rohan Trader";
  const liveApproved = useSessionStore((s) => s.liveTradingApproved);
  const [paperHero, setPaperHero] = useState(!liveApproved);
  const [mwTab, setMwTab] = useState<"Indices" | "Stocks" | "Futures">("Indices");

  useEffect(() => {
    setPaperHero(!liveApproved);
  }, [liveApproved]);

  const q = useQuery({
    queryKey: ["portfolio-dashboard-lite"],
    queryFn: async () => {
      const res = await api.get("/api/portfolio/dashboard?equityPoints=32");
      return res.data?.data as DashboardDto;
    },
  });

  const ov = q.data?.overview;
  const mtm = ov ? Number(ov.mtmPnl) : null;
  const unreal = ov ? Number(ov.unrealizedPnl) : null;
  const realized = ov ? Number(ov.realizedPnl) : null;
  const openPos = ov?.openPositionCount;
  const portfolioLive = q.isSuccess && !!ov;
  const portfolioErr = q.isError;

  const hour = new Date().getHours();
  const salute = hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening";
  const greetFirst = greetName.split(" ")[0] ?? greetName;

  return (
    <div className="space-y-8 pb-16">
      <motion.section
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        className="flex flex-col gap-5 lg:flex-row lg:items-end lg:justify-between"
      >
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <span
              className={cn(
                "inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-[10px] font-bold uppercase tracking-[0.16em]",
                isLight ? "border-neutral-200 bg-white text-neutral-500 shadow-sm" : "border-white/10 bg-neutral-900/80 text-neutral-400",
              )}
            >
              <LayoutDashboard className="h-3 w-3 opacity-70" aria-hidden />
              Dashboard
            </span>
            {q.isFetching ? (
              <span className="inline-flex items-center gap-1 rounded-full border border-blue-200/80 bg-blue-50/90 px-2 py-0.5 text-[10px] font-semibold text-blue-800 dark:border-blue-500/30 dark:bg-blue-500/10 dark:text-blue-200">
                <Loader2 className="h-3 w-3 animate-spin" aria-hidden />
                Syncing
              </span>
            ) : portfolioLive ? (
              <span className="rounded-full border border-emerald-200/90 bg-emerald-50 px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide text-emerald-800 dark:border-emerald-500/35 dark:bg-emerald-500/10 dark:text-emerald-200">
                Portfolio linked
              </span>
            ) : portfolioErr ? (
              <span className="rounded-full border border-amber-200 bg-amber-50 px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide text-amber-900 dark:border-amber-500/35 dark:bg-amber-500/10 dark:text-amber-100">
                Demo figures
              </span>
            ) : null}
            {openPos != null ? (
              <span className={cn("text-[11px] font-medium", isLight ? "text-neutral-500" : "text-neutral-500")}>
                <span className="font-mono font-semibold text-neutral-700 dark:text-neutral-300">{openPos}</span> open legs
              </span>
            ) : null}
          </div>
          <h1
            className={cn(
              "mt-3 text-3xl font-semibold tracking-tight sm:text-[2.15rem] sm:leading-tight",
              isLight ? "text-neutral-900" : "text-white",
            )}
          >
            {salute}, {greetFirst}{" "}
            <span aria-hidden>ðŸ‘‹</span>
          </h1>
          <p className={cn("mt-2 max-w-xl text-[15px] leading-relaxed", isLight ? "text-neutral-600" : "text-neutral-400")}>
            Here&apos;s your trading overview - tape, risk, and flow in one surface.
          </p>
          <div className="mt-4 flex flex-wrap gap-2">
            {[
              { to: "/terminal", label: "Terminal", icon: Terminal },
              { to: "/brokers", label: "Brokers", icon: Wallet },
              { to: "/orders", label: "Orders", icon: ListOrdered },
            ].map(({ to, label, icon: Icon }) => (
              <Link
                key={to}
                to={to}
                className={cn(
                  "group inline-flex items-center gap-2 rounded-xl border px-3 py-2 text-[12px] font-semibold transition",
                  isLight
                    ? "border-neutral-200/90 bg-white text-neutral-800 shadow-sm hover:border-blue-300/80 hover:bg-blue-50/50"
                    : "border-white/10 bg-neutral-900/50 text-neutral-200 hover:border-blue-500/35 hover:bg-blue-500/10",
                )}
              >
                <Icon className="h-4 w-4 opacity-70 transition group-hover:opacity-100" aria-hidden />
                {label}
              </Link>
            ))}
          </div>
        </div>
        <div className="flex shrink-0 flex-wrap items-center gap-2">
          <div
            className={cn(
              "flex items-center rounded-xl border p-0.5 shadow-sm",
              isLight ? "border-neutral-200 bg-neutral-100/90" : "border-white/[0.08] bg-neutral-900/50",
            )}
          >
            <button
              type="button"
              onClick={() => setPaperHero(true)}
              className={cn(
                "rounded-lg px-4 py-2 text-[11px] font-bold uppercase tracking-wide transition",
                paperHero ? (isLight ? "bg-white text-neutral-900 shadow" : "bg-white text-neutral-950 shadow") : isLight ? "text-neutral-500" : "text-neutral-500",
              )}
            >
              Paper
            </button>
            <button
              type="button"
              onClick={() => setPaperHero(false)}
              className={cn(
                "rounded-lg px-4 py-2 text-[11px] font-bold uppercase tracking-wide transition",
                !paperHero ? "bg-blue-600 text-white shadow-md shadow-blue-500/25" : isLight ? "text-neutral-500" : "text-neutral-500",
              )}
            >
              Live
            </button>
          </div>
          <button
            type="button"
            className={cn(
              "inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[12px] font-semibold transition active:scale-[0.98]",
              isLight
                ? "border-neutral-200 bg-white text-neutral-700 shadow-sm hover:bg-neutral-50"
                : "border-white/[0.08] bg-neutral-900/60 text-neutral-300 hover:bg-neutral-800",
            )}
          >
            <SlidersHorizontal className="h-4 w-4" />
            Customize
          </button>
        </div>
      </motion.section>

      {/* KPI */}
      <motion.div
        initial="hidden"
        animate="show"
        variants={{
          hidden: {},
          show: { transition: { staggerChildren: 0.06 } },
        }}
        className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4"
      >
        <KpiTile
          panel={panel}
          label="Total P&L"
          value={mtm != null && !Number.isNaN(mtm) ? formatInr(mtm) : "â‚¹ 1,24,568"}
          delta="+2.45%"
          up
          spark={<MiniSpark tone="green" />}
          isLight={isLight}
          loading={q.isPending}
          accent="emerald"
        />
        <KpiTile
          panel={panel}
          label="Unrealized P&L"
          value={unreal != null && !Number.isNaN(unreal) ? formatInr(unreal) : "â‚¹ 45,230"}
          delta="+1.12%"
          up
          spark={<MiniSpark tone="green" />}
          isLight={isLight}
          loading={q.isPending}
          accent="emerald"
        />
        <KpiTile
          panel={panel}
          label="Realized P&L"
          value={realized != null && !Number.isNaN(realized) ? formatInr(realized) : "â‚¹ 79,338"}
          delta="+3.21%"
          up
          spark={<MiniSpark tone="blue" />}
          isLight={isLight}
          loading={q.isPending}
          accent="blue"
        />

        <GlassPanel variant={panel} interactive className="relative overflow-hidden p-4 xl:col-span-1">
          <div className={cn("text-[11px] font-semibold uppercase tracking-[0.14em]", isLight ? "text-neutral-500" : "text-neutral-500")}>
            Total capital / risk
          </div>
          <div className={cn("stokr-tabular mt-1 font-mono text-xl font-semibold tracking-tight", isLight ? "text-neutral-900" : "text-white")}>
            â‚¹ 12,45,000
          </div>
          <div className={cn("mt-1 text-[11px]", isLight ? "text-neutral-500" : "text-neutral-500")}>Used 62%</div>
          <div className={cn("mt-2 h-2 w-full overflow-hidden rounded-full", isLight ? "bg-neutral-100" : "bg-neutral-800")}>
            <div className="h-full w-[62%] rounded-full bg-gradient-to-r from-blue-500 to-blue-700" />
          </div>
          <div className="mt-4 flex items-start justify-between gap-3">
            <div>
              <div className={cn("text-[10px] font-bold uppercase tracking-wide", isLight ? "text-neutral-500" : "text-neutral-500")}>
                Risk utilization
              </div>
              <span className={cn("mt-2 inline-block rounded-full px-3 py-1 text-[11px] font-semibold text-blue-700", isLight ? "bg-blue-50" : "bg-blue-500/15 text-blue-300")}>
                42%
              </span>
            </div>
            <div className="relative flex h-[72px] w-[72px] shrink-0 items-center justify-center">
              <div
                className="absolute inset-0 rounded-full"
                style={{
                  background: isLight
                    ? "conic-gradient(from 205deg, rgb(59 130 246) 0deg 152deg, rgb(229 231 235) 152deg 360deg)"
                    : "conic-gradient(from 205deg, rgb(59 130 246) 0deg 152deg, rgb(38 38 42) 152deg 360deg)",
                }}
              />
              <div className={cn("relative flex h-[52px] w-[52px] flex-col items-center justify-center rounded-full", isLight ? "bg-white" : "bg-neutral-950")}>
                <span className={cn("font-mono text-sm font-bold", isLight ? "text-neutral-900" : "text-white")}>42%</span>
                <span className={cn("text-[8px] font-bold uppercase text-neutral-500")}>risk</span>
              </div>
            </div>
          </div>
        </GlassPanel>
      </motion.div>

      {/* Chart + double rail */}
      <div className="grid gap-4 xl:grid-cols-12">
        <GlassPanel variant={panel} interactive className="relative overflow-hidden xl:col-span-8">
          <div
            className={cn(
              "flex flex-col gap-3 border-b p-5 pb-4 sm:flex-row sm:items-start sm:justify-between",
              isLight ? "border-neutral-200" : "border-white/[0.06]",
            )}
          >
            <div>
              <div className="flex flex-wrap items-center gap-2">
                <Globe className={cn("h-4 w-4", isLight ? "text-blue-600" : "text-blue-400")} />
                <span className={cn("text-[14px] font-semibold", isLight ? "text-neutral-900" : "text-white")}>NIFTY 50</span>
                <span className={cn("text-[13px]", isLight ? "text-neutral-400" : "text-neutral-500")}> ·  5m</span>
                <span className={cn(
                  "rounded-full border px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide",
                  isLight ? "border-emerald-200 bg-emerald-50 text-emerald-800" : "border-emerald-500/30 bg-emerald-500/10 text-emerald-200",
                )}
                >
                  Live  ·  demo
                </span>
              </div>
              <div className="mt-4 flex flex-wrap gap-2">
                {["1m", "5m", "15m", "1h", "D"].map((x) => (
                  <button
                    key={x}
                    type="button"
                    className={cn(
                      "rounded-lg px-3 py-1 text-[11px] font-semibold uppercase tracking-wide",
                      x === "5m"
                        ? isLight
                          ? "bg-blue-600 text-white shadow-md"
                          : "bg-blue-600 text-white shadow-md"
                        : isLight
                          ? "border border-neutral-200 bg-white text-neutral-600 hover:bg-neutral-50"
                          : "border border-neutral-700 bg-neutral-950/70 text-neutral-400 hover:bg-neutral-900",
                    )}
                  >
                    {x}
                  </button>
                ))}
              </div>
            </div>
            <div className="flex flex-wrap items-center gap-2 lg:justify-end">
              {[Crosshair, PencilLine, LineChartIcon].map((Ic, idx) => (
                <button
                  key={idx}
                  type="button"
                  className={cn(
                    "rounded-lg border p-2",
                    isLight ? "border-neutral-200 bg-white text-neutral-600 hover:bg-neutral-50" : "border-neutral-700 bg-neutral-950/70 text-neutral-300 hover:bg-neutral-900",
                  )}
                  aria-label="Drawing tool"
                >
                  <Ic className="h-4 w-4" />
                </button>
              ))}
              <div className="flex gap-2">
                {["Buy", "Sell", "Exit"].map((lab) => (
                  <span
                    key={lab}
                    className={cn(
                      "rounded-md px-2 py-1 text-[10px] font-black uppercase tracking-wide",
                      lab === "Buy" &&
                        (isLight ? "bg-emerald-100 text-emerald-800" : "bg-emerald-500/15 text-emerald-200"),
                      lab === "Sell" && (isLight ? "bg-rose-50 text-rose-700" : "bg-rose-500/15 text-rose-200"),
                      lab === "Exit" && (isLight ? "bg-amber-50 text-amber-800" : "bg-amber-500/15 text-amber-100"),
                    )}
                  >
                    {lab}
                  </span>
                ))}
              </div>
            </div>
          </div>
          <div className={cn(isLight ? "bg-white" : "bg-neutral-950/40", "px-2 pb-2 pt-1")}>
            <NiftyCandleChart variant={isLight ? "light" : "dark"} height={360} />
          </div>
        </GlassPanel>

        <div className="flex flex-col gap-4 xl:col-span-4">
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-2">
            <div className="flex flex-col gap-4">
              <GlassPanel variant={panel} interactive className="p-4">
                <div className="flex items-center justify-between gap-3">
                  <span className={cn("text-[12px] font-semibold", isLight ? "text-neutral-900" : "text-white")}>Active strategies</span>
                  <Link to="/strategies" className="text-[11px] font-bold text-blue-600 hover:underline dark:text-blue-400">
                    Manage
                  </Link>
                </div>
                <div className="mt-3 space-y-2">
                  {DUMMY_STRATEGIES.map((s) => (
                    <div
                      key={s.name}
                      className={cn(
                        "rounded-xl border px-3 py-2.5",
                        isLight ? "border-neutral-100 bg-neutral-50/85" : "border-white/[0.05] bg-neutral-950/60",
                      )}
                    >
                      <div className="flex justify-between gap-2">
                        <div className="min-w-0">
                          <div className={cn("truncate text-[13px] font-medium", isLight ? "text-neutral-800" : "text-neutral-100")}>
                            {s.name}
                          </div>
                          <span
                            className={cn(
                              "mt-1 inline-block rounded px-1.5 py-px text-[9px] font-black uppercase",
                              s.mode === "LIVE"
                                ? isLight
                                  ? "bg-blue-50 text-blue-800"
                                  : "bg-violet-500/20 text-violet-200"
                                : isLight
                                  ? "bg-sky-50 text-sky-800"
                                  : "bg-sky-500/20 text-sky-200",
                            )}
                          >
                            {s.mode}
                          </span>
                        </div>
                        <div className="text-right">
                          <div className={cn("font-mono text-[13px]", s.pnl.startsWith("+") ? "text-emerald-600" : "text-rose-500")}>
                            {s.pnl}
                          </div>
                          <div
                            className={cn(
                              "font-mono text-[11px]",
                              s.pct.startsWith("+") ? "text-emerald-600" : "text-rose-500",
                            )}
                          >
                            {s.pct}
                          </div>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              </GlassPanel>

              <GlassPanel variant={panel} interactive className="p-4">
                <div className="flex items-center justify-between">
                  <span className={cn("text-[12px] font-semibold", isLight ? "text-neutral-900" : "text-white")}>Positions</span>
                  <Link to="/positions" className="text-[11px] font-bold text-blue-600 hover:underline dark:text-blue-400">
                    All
                  </Link>
                </div>
                <div className="mt-3 space-y-2">
                  {DUMMY_POSITIONS.map((p) => (
                    <div
                      key={p.sym}
                      className={cn(
                        "flex flex-col gap-0.5 rounded-lg border px-3 py-2 text-[13px]",
                        isLight ? "border-neutral-100 bg-white" : "border-white/[0.04] bg-neutral-950/50",
                      )}
                    >
                      <div className="flex justify-between">
                        <span className={cn("font-semibold", isLight ? "text-neutral-800" : "text-neutral-200")}>{p.sym}</span>
                        <span className={cn("font-mono text-[12px]", p.up ? "text-emerald-600" : "text-rose-500")}>{p.chg}</span>
                      </div>
                      <div className={cn("flex justify-between text-[11px]", isLight ? "text-neutral-500" : "text-neutral-500")}>
                        <span>{p.qty} qty</span>
                        <span className="font-mono">{p.val}</span>
                      </div>
                    </div>
                  ))}
                </div>
              </GlassPanel>

              <GlassPanel variant={panel} interactive className="p-4">
                <div className="flex items-center justify-between">
                  <span className={cn("text-[12px] font-semibold", isLight ? "text-neutral-900" : "text-white")}>Recent orders</span>
                  <Link to="/orders" className="text-[11px] font-bold text-blue-600 hover:underline dark:text-blue-400">
                    Blotter
                  </Link>
                </div>
                <div className="mt-3 space-y-2">
                  {DUMMY_ORDERS.map((o) => (
                    <div
                      key={o.t + o.sym}
                      className={cn(
                        "rounded-lg border px-2 py-2 text-[11px]",
                        isLight ? "border-neutral-100 bg-white" : "border-white/[0.04] bg-neutral-950/50",
                      )}
                    >
                      <div className="flex justify-between text-neutral-500">
                        <span className={isLight ? "text-neutral-500" : "text-neutral-500"}>{o.t}</span>
                        <span className={cn("font-black", o.side === "BUY" ? "text-emerald-600" : "text-rose-500")}>{o.side}</span>
                      </div>
                      <div className={cn("mt-1 flex justify-between font-mono", isLight ? "text-neutral-800" : "text-neutral-200")}>
                        <span>{o.sym}</span>
                        <span className={isLight ? "text-neutral-700" : "text-neutral-400"}>{o.st}</span>
                      </div>
                    </div>
                  ))}
                </div>
              </GlassPanel>
            </div>

            <div className="flex flex-col gap-4">
              <GlassPanel variant={panel} interactive className="p-4">
                <div className={cn("flex items-center gap-2 text-[12px] font-semibold", isLight ? "text-neutral-900" : "text-white")}>
                  <Radio className={cn("h-4 w-4", isLight ? "text-blue-600" : "text-blue-400")} /> Market watch
                </div>
                <div className={cn("mt-3 flex gap-2 border-b pb-2", isLight ? "border-neutral-200" : "border-white/[0.06]")}>
                  {(["Indices", "Stocks", "Futures"] as const).map((tab) => (
                    <button
                      key={tab}
                      type="button"
                      onClick={() => setMwTab(tab)}
                      className={cn(
                        "rounded-lg px-3 py-1 text-[11px] font-bold uppercase tracking-wide",
                        mwTab === tab
                          ? isLight
                            ? "bg-blue-600 text-white shadow-md"
                            : "bg-white text-neutral-950"
                          : isLight
                            ? "text-neutral-500 hover:text-neutral-800"
                            : "text-neutral-500 hover:text-neutral-300",
                      )}
                    >
                      {tab}
                    </button>
                  ))}
                </div>
                <div className={cn("mt-3 space-y-2 font-mono text-[12px]", isLight ? "text-neutral-800" : "text-neutral-300")}>
                  {[
                    ["NIFTY 50", "+0.42%"],
                    ["BANK NIFTY", "+0.18%"],
                    ["SENSEX", "âˆ’0.09%"],
                  ].map(([n, pct]) => (
                    <div
                      key={n}
                      className={cn(
                        "flex items-center justify-between gap-2 rounded-lg px-2 py-1",
                        isLight ? "bg-neutral-50/80" : "bg-neutral-950/40",
                      )}
                    >
                      <span className="truncate">{n}</span>
                      <span className={cn("font-mono shrink-0", pct.startsWith("âˆ’") ? "text-rose-500" : "text-emerald-600")}>
                        {pct}
                      </span>
                    </div>
                  ))}
                </div>
              </GlassPanel>

              <GlassPanel variant={panel} interactive className="p-4">
                <div className={cn("flex items-center gap-2 text-[12px] font-semibold", isLight ? "text-neutral-900" : "text-white")}>
                  <Newspaper className={cn("h-4 w-4", isLight ? "text-neutral-600" : "text-neutral-400")} /> News &amp; alerts
                </div>
                <div className={cn("mt-4 space-y-3 border-t pt-4", isLight ? "border-neutral-100" : "border-white/[0.05]")}>
                  {DUMMY_NEWS.map((n) => (
                    <div key={n.t} className={cn(
                      "border-l-2 pl-3",
                      isLight ? "border-blue-500/55" : "border-blue-500/45",
                    )}>
                      <div className={cn("font-mono text-[10px]", isLight ? "text-neutral-500" : "text-neutral-500")}>{n.t}</div>
                      <div className={cn("mt-1 text-[12px] leading-snug", isLight ? "text-neutral-600" : "text-neutral-300")}>{n.msg}</div>
                    </div>
                  ))}
                </div>
              </GlassPanel>

              <GlassPanel variant={panel} className={cn(
                "relative overflow-hidden ring-2",
                isLight ? "border-indigo-200/75 bg-gradient-to-br from-blue-50/95 via-white to-neutral-50 ring-blue-400/35" :
                  "border-violet-500/25 bg-gradient-to-br from-violet-950/50 via-neutral-950/90 to-neutral-950 ring-violet-500/35",
              )}
              >
                <div className="pointer-events-none absolute -right-10 -top-10 h-40 w-40 rounded-full bg-violet-500/10 blur-3xl dark:block" aria-hidden />
                <div className="relative flex flex-col gap-3 p-4">
                  <div className="flex items-center gap-2">
                    <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-blue-600/90 text-white shadow-lg shadow-blue-500/35">
                      <Bot className="h-6 w-6" aria-hidden />
                    </div>
                    <div className={cn(isLight ? "text-neutral-900" : "text-white")}>
                      <div className="flex flex-wrap items-center gap-2 text-[13px] font-semibold">
                        AI insights
                        <span className={cn(
                          "rounded-full px-2 py-px text-[9px] font-black uppercase tracking-wide",
                          isLight ? "bg-indigo-100 text-indigo-800" : "bg-violet-500/25 text-violet-200",
                        )}
                        >
                          Beta
                        </span>
                      </div>
                    </div>
                    <Sparkles className={cn(
                      "ml-auto h-5 w-5",
                      isLight ? "text-indigo-600" : "text-violet-300",
                    )} aria-hidden />
                  </div>
                  <p className={cn("text-[13px] leading-relaxed", isLight ? "text-neutral-600" : "text-neutral-400")}>
                    NIFTY showing strong momentum vs recent balance - breadth supportive. Fade mean-reversion fades until volatility
                    compresses on 5m structure.
                  </p>
                  <button
                    type="button"
                    className={cn(
                      "rounded-xl border px-4 py-2.5 text-[12px] font-bold transition",
                      isLight
                        ? "border-blue-300 bg-blue-600 text-white shadow-md hover:bg-blue-700"
                        : "border-violet-500/35 bg-violet-500/10 text-violet-100 hover:bg-violet-500/25",
                    )}
                  >
                    View analysis
                  </button>
                </div>
              </GlassPanel>
            </div>
          </div>
        </div>
      </div>

      {/* Footer */}
      <div className={cn(
        "flex flex-wrap items-center justify-between gap-3 rounded-xl border px-4 py-3 text-[11px]",
        isLight ? "border-neutral-200 bg-white text-neutral-500 shadow-sm" : "border-white/[0.06] bg-neutral-950/40 text-neutral-500",
      )}>
        <div className="flex items-center gap-2">
          <ShieldCheck className={cn("h-4 w-4 shrink-0", isLight ? "text-emerald-600" : "text-emerald-500/90")} />
          <span>
            Ledger-backed totals when API is reachable - tiles mix live and demonstration data.&nbsp;
            <Link className="font-semibold text-blue-600 underline-offset-4 hover:underline dark:text-blue-400" to="/terminal">
              Open terminal
            </Link>
          </span>
        </div>
        <div className={cn("flex items-center gap-2 font-mono text-[10px]", isLight ? "text-neutral-400" : "text-neutral-600")}>
          <Layers className="h-3.5 w-3.5" /> Portfolio snap {ov?.latestSnapshotAt ? new Date(ov.latestSnapshotAt).toLocaleTimeString() : "-"}
        </div>
      </div>
    </div>
  );
}

function KpiTile({
  panel,
  label,
  value,
  delta,
  up,
  spark,
  isLight,
  loading,
  accent,
}: {
  panel: "light" | "dark";
  label: ReactNode;
  value: string;
  delta: string;
  up: boolean;
  spark: ReactNode;
  isLight: boolean;
  loading?: boolean;
  accent?: "blue" | "emerald";
}) {
  return (
    <motion.div variants={{ hidden: { opacity: 0, y: 8 }, show: { opacity: 1, y: 0 } }}>
      <GlassPanel
        variant={panel}
        interactive
        className={cn(
          "relative overflow-hidden p-4",
          accent === "blue" && isLight && "ring-1 ring-blue-100/80",
          accent === "emerald" && isLight && "ring-1 ring-emerald-100/80",
        )}
      >
      <div className={cn("text-[11px] font-semibold uppercase tracking-[0.12em]", isLight ? "text-neutral-500" : "text-neutral-500")}>
        {label}
      </div>
      {loading ? (
        <>
          <div className={cn("mt-2 h-7 w-32 animate-pulse rounded-md", isLight ? "bg-neutral-100" : "bg-neutral-800")} />
          <div className={cn("mt-2 h-4 w-20 animate-pulse rounded-md", isLight ? "bg-neutral-100" : "bg-neutral-800")} />
        </>
      ) : (
        <>
          <div className={cn("stokr-tabular mt-1 font-mono text-xl font-semibold tracking-tight", isLight ? "text-neutral-900" : "text-white")}>
            {value}
          </div>
          <div className={cn("mt-1 flex items-center gap-1 text-[11px] font-medium", up ? "text-emerald-600" : "text-rose-500")}>
            {up ? <ArrowUpRight className="h-3.5 w-3.5" /> : <ArrowDownRight className="h-3.5 w-3.5" />}
            {delta}
          </div>
        </>
      )}
      <div className="mt-3">{spark}</div>
      </GlassPanel>
    </motion.div>
  );
}
