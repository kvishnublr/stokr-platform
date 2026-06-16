import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { cn } from "../../lib/utils";
import type { TraderDashboardData } from "../../services/dashboard/types";
import { NiftyCandleChart } from "../charts/NiftyCandleChart";
import { useUiThemeStore } from "../../state/uiTheme";

export function TraderMain({ data }: { data: TraderDashboardData }) {
  const [interval, setInterval] = useState("15m");
  const [selectedSymbol, setSelectedSymbol] = useState(data.marketWatch[0]?.symbol ?? "NIFTY 50");
  const selected = useMemo(() => data.marketWatch.find((x) => x.symbol === selectedSymbol), [data.marketWatch, selectedSymbol]);
  const isLight = useUiThemeStore((s) => s.mode === "light");

  return (
    <div className="space-y-5 pt-1">
      <div className="pb-2">
        <h1
          className={cn(
            "text-3xl font-bold tracking-tight",
            isLight ? "text-neutral-900" : "text-white",
          )}
        >
          Execution overview
        </h1>
        <p className={cn("mt-1 text-sm", isLight ? "text-neutral-500" : "text-neutral-400")}>
          {data.greetingName ? <>Signed in as {data.greetingName}. </> : null}
          Strategies, broker status, and portfolio signals - platform operations live in the admin cockpit.
        </p>
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
