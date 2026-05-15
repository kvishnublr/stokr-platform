import { useMemo, useState } from "react";
import { motion } from "framer-motion";
import { ActivitySquare, Cpu, Layers, LineChart, TrendingUp } from "lucide-react";
import { WorkspaceTabPanel, WorkspaceTabs } from "../components/ds/WorkspaceTabs";
import { GlassPanel } from "../components/ds/GlassPanel";
import { MetricCard } from "../components/ds/MetricCard";
import { ActivityTimeline } from "../components/ds/ActivityTimeline";
import { useNotificationStore } from "../state/notifications";
import { useUiThemeStore } from "../state/uiTheme";
import { cn } from "../lib/utils";
import { OrdersPage } from "./OrdersPage";
import { ExecutionsPage } from "./ExecutionsPage";
import { PositionsPage } from "./PositionsPage";

const TABS = [
  { id: "blotter", label: "Order blotter" },
  { id: "tape", label: "Execution tape" },
  { id: "risk", label: "Risk & telemetry" },
  { id: "positions", label: "Positions desk" },
];

export function TerminalPage() {
  const [tab, setTab] = useState("blotter");
  const feed = useNotificationStore((s) => s.items);
  const isLight = useUiThemeStore((s) => s.mode === "light");

  const latencyCue = useMemo(() => Math.min(feed.length * 17, 220), [feed.length]);

  return (
    <div className="space-y-8">
      <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }}>
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <div
              className={cn(
                "inline-flex items-center gap-2 rounded-full px-3 py-1 text-[11px] font-bold uppercase tracking-widest",
                isLight
                  ? "border border-blue-200 bg-blue-50 text-blue-700"
                  : "border border-violet-500/30 bg-violet-500/10 text-violet-200",
              )}
            >
              <Layers className="h-3.5 w-3.5" />
              Institutional terminal
            </div>
            <h1 className={cn("mt-3 text-3xl font-semibold tracking-tight", isLight ? "text-foreground" : "text-white")}>
              Execution workstation
            </h1>
            <p className={cn("mt-2 max-w-3xl text-sm", isLight ? "text-muted-foreground" : "text-neutral-400")}>
              Replay-safe blotter with synchronized executions, exposures, and a live websocket-backed operational rail.
              Section-level virtualization ships through React Query keyed caches - no SPA reload loops.
            </p>
          </div>
          <GlassPanel variant={isLight ? "light" : "dark"} className="px-5 py-3 text-right">
            <div className="text-[11px] font-bold uppercase tracking-widest text-muted-foreground">Notification queue cue</div>
            <div className={cn("font-mono text-xl", isLight ? "text-foreground" : "text-neutral-100")}>~ {latencyCue} ms</div>
            <div className="mt-2 text-[10px] text-muted-foreground">Derived from queued client events (UI-side heuristic).</div>
          </GlassPanel>
        </div>
      </motion.div>

      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <MetricCard panelVariant={isLight ? "light" : "dark"} label="Order surface" sublabel="Idempotent intents" value="OMS" highlight />
        <MetricCard
          panelVariant={isLight ? "light" : "dark"}
          label="Realtime rail"
          trend="flat"
          sublabel="/ws multiplex"
          value={<span className={isLight ? "text-foreground" : "text-neutral-300"}>Healthy</span>}
        />
        <MetricCard
          panelVariant={isLight ? "light" : "dark"}
          label="Execution mode"
          trend="flat"
          sublabel="SIM default"
          value={<span className={isLight ? "text-foreground" : "text-neutral-100"}>Paper</span>}
        />
        <MetricCard panelVariant={isLight ? "light" : "dark"} label="Operational feed" trend="up" value={feed.length.toString()} sublabel="Last 50 hooks" />
      </div>

      <GlassPanel variant={isLight ? "light" : "dark"} className="p-4 sm:p-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <WorkspaceTabs tabs={TABS} active={tab} onChange={setTab} />
          <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
            <Cpu className="h-4 w-4 text-blue-400" />
            Replay lineage preserved - deterministic bridge
          </div>
        </div>

        <WorkspaceTabPanel id="blotter" active={tab}>
          <div className="pt-5">
            <OrdersPage embedded />
          </div>
        </WorkspaceTabPanel>
        <WorkspaceTabPanel id="tape" active={tab}>
          <div className="pt-5">
            <ExecutionsPage embedded />
          </div>
        </WorkspaceTabPanel>
        <WorkspaceTabPanel id="risk" active={tab}>
          <div className="grid gap-6 pt-5 lg:grid-cols-2">
            <GlassPanel variant={isLight ? "light" : "dark"} className="p-4">
              <div className={cn("flex items-center gap-2 text-sm font-semibold", isLight ? "text-foreground" : "text-white")}>
                <TrendingUp className="h-4 w-4 text-amber-300" /> Strategy & risk mix
              </div>
              <p className="mt-3 text-xs text-muted-foreground">
                Server-side LIVE eligibility covers broker health tokens, onboarding matrix, LIVE runtime heartbeats & kill-aware
                risk engine - operator visibility surfaces through admin operations console.
              </p>
              <ActivityTimeline items={feed} className="mt-4 max-h-80" />
            </GlassPanel>
            <GlassPanel variant={isLight ? "light" : "dark"} className="p-4">
              <div className={cn("flex items-center gap-2 text-sm font-semibold", isLight ? "text-foreground" : "text-white")}>
                <ActivitySquare className="h-4 w-4 text-emerald-300" /> Quality rails
              </div>
              <ul className={cn("mt-4 space-y-3 text-xs", isLight ? "text-muted-foreground" : "text-neutral-400")}>
                <li>- Duplicate execution shields via deterministic idempotency keys.</li>
                <li>- Redis arm + LIVE_TRADING_ENABLED double gate precedes trader matrix.</li>
                <li>- Broker adapter registry isolates Zerodha / SIM vendors without UI coupling.</li>
              </ul>
            </GlassPanel>
          </div>
        </WorkspaceTabPanel>
        <WorkspaceTabPanel id="positions" active={tab}>
          <div className="pt-5">
            <PositionsPage embedded />
          </div>
        </WorkspaceTabPanel>
      </GlassPanel>

      <GlassPanel variant={isLight ? "light" : "dark"} className="p-6">
        <div className="flex items-center gap-2">
          <LineChart className="h-5 w-5 text-muted-foreground" />{" "}
          <span className="text-[11px] font-bold uppercase tracking-widest text-muted-foreground">Latency legend</span>
        </div>
        <div className="mt-4 grid gap-4 text-xs sm:grid-cols-3">
          <div className={cn("rounded-xl border p-3", isLight ? "border-emerald-200 bg-emerald-50 text-emerald-800" : "border-emerald-900/60 bg-emerald-950/20 text-emerald-100")}>
            <div className="font-semibold uppercase tracking-wide">Green</div>
            Sub-millisecond websocket fan-out from bridge + ack from OMS.
          </div>
          <div className={cn("rounded-xl border p-3", isLight ? "border-amber-200 bg-amber-50 text-amber-800" : "border-amber-900/50 bg-amber-950/20 text-amber-100")}>
            <div className="font-semibold uppercase tracking-wide">Yellow</div>
            Coalescing UI refresh batching keeps CPU flat on dense feeds.
          </div>
          <div className={cn("rounded-xl border p-3", isLight ? "border-rose-200 bg-rose-50 text-rose-800" : "border-rose-900/55 bg-rose-950/20 text-rose-100")}>
            <div className="font-semibold uppercase tracking-wide">Red</div>
            Hard risk rejections short-circuit before broker hops - surfaced in blotter rejects.
          </div>
        </div>
      </GlassPanel>
    </div>
  );
}
