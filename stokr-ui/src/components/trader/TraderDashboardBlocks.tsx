import { useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { cn } from "../../lib/utils";
import type { TraderDashboardData } from "../../services/dashboard/types";
import { NiftyCandleChart } from "../charts/NiftyCandleChart";
import { useUiThemeStore } from "../../state/uiTheme";

const mainNav = [
  { label: "Dashboard", to: "/dashboard" },
  { label: "Strategies", to: "/strategies" },
  { label: "Positions", to: "/positions" },
  { label: "Orders", to: "/orders" },
  { label: "Executions", to: "/executions" },
  { label: "Watchlist", to: "/watchlist" },
  { label: "Analytics", to: "/terminal" },
  { label: "Backtests", to: "/backtests/launch" },
];

export function TraderSidebar({ portfolio }: { portfolio: TraderDashboardData["portfolio"] }) {
  const navigate = useNavigate();
  return (
    <div className="p-5">
      <div className="mb-8 flex items-center gap-2">
        <div className="flex h-8 w-8 items-center justify-center rounded bg-orange-500 font-bold text-white">S</div>
        <span className="text-lg font-bold">Stokr</span>
      </div>
      <div className="mb-8 space-y-2">
        <div className="mb-3 px-3 text-xs font-semibold uppercase tracking-wide text-gray-500">Main</div>
        {mainNav.map((item, idx) => (
          <div
            key={item.label}
            onClick={() => navigate(item.to)}
            className={cn(
              "rounded-lg px-4 py-3 text-sm",
              idx === 0 ? "bg-orange-50 font-medium text-orange-500" : "cursor-pointer text-gray-600 hover:bg-gray-50",
            )}
          >
            {item.label}
          </div>
        ))}
      </div>
      <div className="mb-8 space-y-2">
        <div className="mb-3 px-3 text-xs font-semibold uppercase tracking-wide text-gray-500">Integrations</div>
        <div className="cursor-pointer rounded-lg px-4 py-3 text-sm text-gray-600 hover:bg-gray-50" onClick={() => navigate("/brokers")}>Broker Connect</div>
        <div className="cursor-pointer rounded-lg px-4 py-3 text-sm text-gray-600 hover:bg-gray-50" onClick={() => navigate("/terminal")}>Alerts & Notifications</div>
      </div>
      <div className="space-y-4 rounded-lg bg-gray-50 p-4">
        <div className="text-xs font-semibold uppercase tracking-wide text-gray-500">Portfolio Snapshot</div>
        <div>
          <div className="mb-1 text-xs text-gray-500">Total Equity</div>
          <div className="text-xl font-bold text-gray-900">{portfolio.equity}</div>
        </div>
        <div>
          <div className="mb-1 text-xs text-gray-500">Available Margin</div>
          <div className="text-lg font-bold text-gray-900">{portfolio.margin}</div>
        </div>
        <div>
          <div className="mb-2 text-xs text-gray-500">Margin Used</div>
          <div className="relative h-6 overflow-hidden rounded-full border border-gray-200 bg-white">
            <div className="h-full bg-orange-500" style={{ width: `${portfolio.marginUsedPct}%` }} />
            <span className="absolute inset-0 flex items-center justify-center text-xs font-bold text-gray-700">{portfolio.marginUsedPct}%</span>
          </div>
        </div>
        <button className="w-full rounded-lg bg-orange-500 py-2 text-sm font-medium text-white hover:opacity-90" onClick={() => navigate("/orders")}>+ Quick Order</button>
      </div>
    </div>
  );
}

export function TraderTopbar({
  name,
  realtimeConnected,
  unread,
  marketWatch,
}: {
  name: string;
  realtimeConnected: boolean;
  unread: number;
  marketWatch: TraderDashboardData["marketWatch"];
}) {
  const navigate = useNavigate();
  const toggleTheme = useUiThemeStore((s) => s.toggle);
  const [search, setSearch] = useState("");
  const primary = marketWatch[0];
  const secondary = marketWatch[1];

  function runSearch() {
    const term = search.trim().toLowerCase();
    if (!term) return;
    if (term.includes("order")) return navigate("/orders");
    if (term.includes("position")) return navigate("/positions");
    if (term.includes("execut")) return navigate("/executions");
    if (term.includes("strateg")) return navigate("/strategies");
    if (term.includes("backtest")) return navigate("/backtests/launch");
    navigate("/terminal");
  }

  return (
    <div className="flex items-center gap-5 px-6 py-3.5">
      <button
        className="text-gray-600 hover:text-gray-900 xl:hidden"
        onClick={() => document.getElementById("trader-mobile-menu-trigger")?.click()}
      >
        ☰
      </button>
      <div className="relative max-w-sm flex-1">
        <input
          type="text"
          placeholder="Search markets, strategies, or orders..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") runSearch();
          }}
          className="w-full rounded-lg border border-gray-200 bg-gray-50 px-4 py-2 text-sm focus:border-orange-500 focus:outline-none"
        />
      </div>
      <div className="flex flex-1 items-center gap-6">
        <div className="flex gap-8 text-sm">
          <div className="flex items-center gap-2">
            <span className="font-medium text-gray-500">{primary?.symbol ?? "—"}</span>
            <span className="font-bold text-gray-900">{primary?.price ?? "—"}</span>
            <span className={cn("font-medium", primary?.positive === false ? "text-red-600" : "text-green-600")}>{primary?.change ?? "—"}</span>
          </div>
          <div className="hidden items-center gap-2 md:flex">
            <span className="font-medium text-gray-500">{secondary?.symbol ?? "—"}</span>
            <span className="font-bold text-gray-900">{secondary?.price ?? "—"}</span>
            <span className={cn("font-medium", secondary?.positive === false ? "text-red-600" : "text-green-600")}>{secondary?.change ?? "—"}</span>
          </div>
        </div>
      </div>
      <div className="flex items-center gap-4">
        <button
          className="rounded border border-gray-200 px-2 py-1 text-xs text-gray-600"
          onClick={toggleTheme}
        >
          Theme
        </button>
        <span className="rounded-full bg-red-500 px-1.5 py-0.5 text-xs font-bold text-white">{unread}</span>
        <span className={cn("rounded-full px-2 py-1 text-xs font-semibold", realtimeConnected ? "bg-green-100 text-green-700" : "bg-amber-100 text-amber-700")}>
          {realtimeConnected ? "LIVE" : "SYNCING"}
        </span>
        <div className="flex h-10 w-10 items-center justify-center rounded-full bg-gray-300 font-bold text-gray-700">{name[0] ?? "T"}</div>
      </div>
    </div>
  );
}

export function TraderMain({ data }: { data: TraderDashboardData }) {
  const [interval, setInterval] = useState("15m");
  const [selectedSymbol, setSelectedSymbol] = useState(data.marketWatch[0]?.symbol ?? "NIFTY 50");
  const selected = useMemo(() => data.marketWatch.find((x) => x.symbol === selectedSymbol), [data.marketWatch, selectedSymbol]);
  const navigate = useNavigate();

  return (
    <div className="space-y-5 pt-1">
      <div className="flex items-center justify-between pb-2">
        <div>
          <h1 className="text-3xl font-bold text-gray-900">Good morning, {data.greetingName} 👋</h1>
          <p className="mt-1 text-sm text-gray-500">Here's your trading overview</p>
        </div>
        <div className="hidden gap-3 md:flex">
          <button className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700" onClick={() => navigate("/paper")}>PAPER</button>
          <button className="rounded-full bg-orange-500 px-4 py-2 text-sm font-medium text-white" onClick={() => navigate("/orders")}>● LIVE</button>
          <button className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700" onClick={() => navigate("/terminal")}>⚙️ Customise</button>
        </div>
      </div>
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-4">
        {data.kpis.map((s) => (
          <div key={s.label} className="rounded-xl border border-gray-200 bg-white p-5 transition hover:border-orange-300 hover:shadow-sm">
            <div className="text-xs font-semibold uppercase tracking-wide text-gray-500">{s.label}</div>
            <div className="mt-2 text-2xl font-bold text-gray-900">{s.value}</div>
            <div className={cn("mt-2 text-xs font-medium", s.positive ? "text-green-600" : "text-red-600")}>{s.delta}</div>
          </div>
        ))}
      </div>
      <div className="rounded-2xl border border-gray-200 bg-white p-6">
        <div className="mb-4 flex items-center justify-between">
          <div>
            <h2 className="text-lg font-bold text-gray-900">{data.chart.symbol}</h2>
            <div className="mt-2 flex items-center gap-2 text-sm">
              <span className="font-bold text-gray-900">{selected?.price ?? data.chart.last}</span>
              <span className={cn("font-medium", selected?.positive === false ? "text-red-600" : "text-green-600")}>{selected?.change ?? data.chart.change}</span>
            </div>
          </div>
          <div className="flex flex-wrap gap-2">
            {["1m", "5m", "15m", "1H", "4H"].map((t) => (
              <button
                key={t}
                onClick={() => setInterval(t)}
                className={cn("rounded-lg px-3 py-1.5 text-xs font-medium", t === interval ? "bg-orange-500 text-white" : "bg-gray-100 text-gray-600")}
              >
                {t}
              </button>
            ))}
          </div>
        </div>
        <div className="h-80 rounded-xl border border-gray-200 bg-gradient-to-b from-gray-50 to-white p-2">
          <NiftyCandleChart
            variant="light"
            height={300}
            candles={data.chart.candles}
            volumes={data.chart.volumes}
          />
        </div>
        <div className="mt-3 flex flex-wrap gap-2">
          {data.marketWatch.map((s) => (
            <button
              key={s.symbol}
              onClick={() => setSelectedSymbol(s.symbol)}
              className={cn(
                "rounded-lg border px-3 py-1 text-xs font-semibold",
                selectedSymbol === s.symbol ? "border-orange-500 bg-orange-50 text-orange-600" : "border-gray-200 bg-white text-gray-600",
              )}
            >
              {s.symbol}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}

export function TraderRightRail({ data }: { data: TraderDashboardData }) {
  return (
    <div className="space-y-5">
      <section className="rounded-2xl border border-gray-200 bg-white p-5">
        <h3 className="mb-4 border-b border-gray-200 pb-3 font-bold text-gray-900">Active Strategies</h3>
        <div className="space-y-3">
          {data.strategies.length === 0 ? <div className="rounded-lg bg-gray-50 p-3 text-xs text-gray-500">No data available.</div> : null}
          {data.strategies.map((s) => (
            <div key={s.name} className="flex items-center justify-between rounded-lg bg-gray-50 p-3">
              <div><div className="text-sm font-semibold text-gray-900">{s.name}</div><div className="text-xs text-gray-500">{s.mode}</div></div>
              <div className={cn("text-sm font-bold", s.positive ? "text-green-600" : "text-red-600")}>{s.pnl}</div>
            </div>
          ))}
        </div>
      </section>
      <section className="rounded-2xl border border-gray-200 bg-white p-5">
        <h3 className="mb-4 border-b border-gray-200 pb-3 font-bold text-gray-900">Market Watch</h3>
        <div className="space-y-3">
          {data.marketWatch.length === 0 ? <div className="rounded-lg bg-gray-50 p-3 text-xs text-gray-500">No data available.</div> : null}
          {data.marketWatch.map((m) => (
            <div key={m.symbol} className="flex items-center justify-between rounded-lg bg-gray-50 p-3">
              <div className="text-sm font-semibold text-gray-900">{m.symbol}</div>
              <div className="text-right"><div className="text-sm font-bold text-gray-900">{m.price}</div><div className={cn("text-xs", m.positive ? "text-green-600" : "text-red-600")}>{m.change}</div></div>
            </div>
          ))}
        </div>
      </section>
      <section className="rounded-2xl border border-gray-200 bg-white p-5">
        <h3 className="mb-4 border-b border-gray-200 pb-3 font-bold text-gray-900">Alerts & Notifications</h3>
        <div className="space-y-3">
          {data.alerts.length === 0 ? <div className="rounded-lg bg-gray-50 p-3 text-xs text-gray-500">No data available.</div> : null}
          {data.alerts.map((a) => (
            <div key={a.title} className={cn("rounded-lg border p-3", a.level === "critical" && "border-red-100 bg-red-50", a.level === "warn" && "border-yellow-100 bg-yellow-50", a.level === "info" && "border-blue-100 bg-blue-50")}>
              <div className="text-xs font-semibold text-gray-900">{a.title}</div>
              <div className="mt-1 text-xs text-gray-600">{a.detail}</div>
            </div>
          ))}
        </div>
        <div className="mt-4 text-right">
          <Link to="/terminal" className="text-sm font-semibold text-orange-500 hover:underline">View all</Link>
        </div>
      </section>
    </div>
  );
}
