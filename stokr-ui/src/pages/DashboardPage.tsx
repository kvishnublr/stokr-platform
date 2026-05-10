import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { motion } from "framer-motion";
import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { Cpu, Layers, Radar, Zap } from "lucide-react";
import { api } from "../api/client";
import { GlassPanel } from "../components/ds/GlassPanel";
import { MetricCard } from "../components/ds/MetricCard";
import { PositionCard } from "../components/ds/PositionCard";
import { RuntimeCard } from "../components/ds/RuntimeCard";
import { StatusChip } from "../components/ds/StatusChip";
import { ActivityTimeline } from "../components/ds/ActivityTimeline";
import { useNotificationStore } from "../state/notifications";
import { useSessionStore } from "../state/session";

type DashboardDto = {
  overview: {
    realizedPnl: string;
    unrealizedPnl: string;
    mtmPnl: string;
    openPositionCount: number;
    latestSnapshotAt: string | null;
  };
  equityCurve: { asOf: string; cumulativePnl: string }[];
  exposure: {
    bySymbol: { symbol: string; quantity: string; exposureNotional: string }[];
    byBrokerNotional: { brokerVendor: string; tradedNotionalApprox: string }[];
  };
  maxDrawdownPct: string;
  bestSymbol: { symbol: string; unrealizedPnl: string } | null;
  worstSymbol: { symbol: string; unrealizedPnl: string } | null;
};

export function DashboardPage() {
  const onboardingComplete = useSessionStore((s) => s.onboardingComplete);
  const liveOk = useSessionStore((s) => s.liveTradingApproved);
  const feed = useNotificationStore((s) => s.items);

  const q = useQuery({
    queryKey: ["portfolio-dashboard"],
    queryFn: async () => {
      const res = await api.get("/api/portfolio/dashboard?equityPoints=120");
      return res.data?.data as DashboardDto;
    },
  });

  const chartData =
    q.data?.equityCurve.map((p) => ({
      t: new Date(p.asOf).toLocaleString(undefined, { month: "short", day: "numeric", hour: "2-digit" }),
      v: Number(p.cumulativePnl),
    })) ?? [];

  const ov = q.data?.overview;
  const topSyms = (q.data?.exposure.bySymbol ?? []).slice(0, 5);

  return (
    <div className="space-y-12">
      <motion.div initial={{ opacity: 0, y: 14 }} animate={{ opacity: 1, y: 0 }} className="space-y-6">
        <div className="flex flex-wrap items-start justify-between gap-6">
          <div className="min-w-[220px]">
            <div className="flex flex-wrap items-center gap-2">
              <div className="inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/5 px-3 py-1 text-[11px] font-bold uppercase tracking-[0.18em] text-neutral-300">
                <Layers className="h-3 w-3" />
                Trader workstation
              </div>
              <StatusChip status={onboardingComplete ? "online" : "degraded"} label="Onboarding" />
              <StatusChip status={liveOk ? "live" : "paper"} label={liveOk ? "LIVE approved" : "Paper-safe"} />
            </div>
            <h1 className="mt-5 text-[34px] font-semibold tracking-tight text-white sm:text-4xl">
              Tactical{" "}
            <span className="bg-gradient-to-r from-blue-400 to-emerald-300 bg-clip-text text-transparent">visibility</span>
            </h1>
            <p className="mt-4 max-w-2xl text-sm leading-relaxed text-neutral-400">
              Replay-safe bookkeeping with ledger-backed dashboards · websocket orders & strategy pulses fan into alert center
              without full reloads · execution truth remains server-side deterministic.
            </p>
          </div>
          <GlassPanel className="flex max-w-xl flex-wrap items-start gap-3 p-4">
            <Cpu className="h-10 w-10 shrink-0 text-blue-400" />
            <div>
              <div className="text-[11px] font-bold uppercase tracking-widest text-neutral-600">Operational posture</div>
              <p className="mt-2 text-xs text-neutral-400">
                {onboardingComplete ? (
                  "Onboarding lattice satisfied · monitor broker telemetry + kill switch from Ops."
                ) : (
                  <>
                    Pending onboarding checkpoints —{" "}
                    <Link to="/onboarding" className="text-blue-300 underline-offset-4 hover:underline">
                      resume wizard
                    </Link>
                    .
                  </>
                )}
              </p>
              <Link
                to="/terminal"
                className="mt-3 inline-flex items-center gap-2 text-[11px] font-black uppercase tracking-widest text-white"
              >
                <Radar className="h-4 w-4 text-violet-400" /> Open execution terminal →
              </Link>
            </div>
          </GlassPanel>
        </div>
      </motion.div>

      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <MetricCard
          highlight
          label="MTM PnL"
          value={ov ? Number(ov.mtmPnl).toFixed(2) : "—"}
          trend="flat"
          sublabel="Ledger-backed · realized + unrealized"
        />
        <MetricCard label="Daily lens" trend="flat" sublabel={`Open positions ${ov?.openPositionCount ?? "—"}`} value={<span>LIVE FEED</span>} />
        <MetricCard label="Drawdown %" value={q.data ? `${Number(q.data.maxDrawdownPct).toFixed(2)}%` : "—"} trend="down" />
        <MetricCard
          label="Desk signals"
          value={feed.length.toString()}
          sublabel="Websocket multiplex"
          trend="up"
          action={<Zap className="h-4 w-4 text-amber-400" />}
        />
      </div>

      <div className="grid gap-6 xl:grid-cols-12">
        <GlassPanel className="p-5 xl:col-span-8">
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-white/5 pb-4">
            <div className="text-[13px] font-semibold text-white">PnL glide path</div>
            <span className="font-mono text-[11px] text-neutral-500">
              Snapshot {ov?.latestSnapshotAt ? new Date(ov.latestSnapshotAt).toLocaleString() : "—"}
            </span>
          </div>
          <div className="mt-4 h-72 w-full lg:h-[28rem]">
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={chartData}>
                <defs>
                  <linearGradient id="eqW" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="#60a5fa" stopOpacity={0.42} />
                    <stop offset="95%" stopColor="#60a5fa" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" stroke="#1f2937" />
                <XAxis dataKey="t" tick={{ fill: "#71717a", fontSize: 10 }} />
                <YAxis tick={{ fill: "#71717a", fontSize: 10 }} />
                <Tooltip
                  contentStyle={{ background: "#09090b", border: "1px solid #27272a", borderRadius: 10 }}
                  labelStyle={{ color: "#a1a1aa", fontSize: 11 }}
                />
                <Area type="monotone" dataKey="v" stroke="#60a5fa" strokeWidth={2} fillOpacity={1} fill="url(#eqW)" />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </GlassPanel>

        <div className="flex flex-col gap-4 xl:col-span-4">
          <RuntimeCard strategyName="Strategy runtime grid" instanceIdShort="Managed instances" mode="MULTI" state="RUNNING" />
          <GlassPanel className="p-5">
            <div className="flex items-center justify-between">
              <div className="text-[13px] font-semibold text-white">Operational rail</div>
              <Link to="/onboarding" className="text-[11px] font-bold uppercase tracking-widest text-blue-400">
                Checklist →
              </Link>
            </div>
            <ActivityTimeline items={feed} maxVisible={14} />
          </GlassPanel>

          <GlassPanel className="p-5">
            <div className="text-[13px] font-semibold text-white">Liquidity hotspots</div>
            <div className="mt-4 space-y-3">
              {topSyms.length === 0 ? (
                <p className="text-xs text-neutral-600">Exposure rows populate after first executions.</p>
              ) : (
                topSyms.map((row) => (
                  <PositionCard
                    key={row.symbol}
                    symbol={row.symbol}
                    quantity={row.quantity}
                    notional={row.exposureNotional}
                  />
                ))
              )}
            </div>
          </GlassPanel>

          <GlassPanel className="p-5">
            <div className="text-[13px] font-semibold text-white">Winner / loser</div>
            <div className="mt-4 space-y-4 text-xs text-neutral-400">
              <div>
                <span className="text-neutral-500">Best pocket</span>
                <div className="mt-1 font-mono text-emerald-300">
                  {q.data?.bestSymbol
                    ? `${q.data.bestSymbol.symbol} (${Number(q.data.bestSymbol.unrealizedPnl).toFixed(2)})`
                    : "—"}
                </div>
              </div>
              <div>
                <span className="text-neutral-500">Drawdown focal</span>
                <div className="mt-1 font-mono text-rose-300">
                  {q.data?.worstSymbol
                    ? `${q.data.worstSymbol.symbol} (${Number(q.data.worstSymbol.unrealizedPnl).toFixed(2)})`
                    : "—"}
                </div>
              </div>
            </div>
          </GlassPanel>
        </div>
      </div>
    </div>
  );
}
