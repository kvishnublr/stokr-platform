import { useQuery } from "@tanstack/react-query";
import { motion } from "framer-motion";
import {
  Activity,
  AlertTriangle,
  ArrowDown,
  CheckCircle2,
  Circle,
  Clock,
  Database,
  GitBranch,
  Radio,
  ShieldCheck,
  TrendingUp,
  Wifi,
  XCircle,
  Zap,
} from "lucide-react";
import { api } from "../../api/client";
import { fetchAdminOpsSnapshotMerged } from "../../lib/fetchAdminOpsSnapshotMerged";
import { asRecord, type OpsSnapshot } from "../../components/admin/cockpit/opsTypes";
import { cn } from "../../lib/utils";

type SettingsSummary = {
  killSwitch: string;
  liveTradingArmed: string;
  uptimeSeconds: number;
  uptimeHuman: string;
  strategiesTotal: number;
  marketFeedState?: string;
  marketFeedSubscriptions?: number;
  marketFeedTicksPerSec?: string;
  marketFeedLastPacket?: string;
};

type HealthData = {
  killSwitch: boolean;
  liveTradingArmed: boolean;
  uptimeSeconds: number;
};

type OrderRow = { state: string; executionMode: string | null };
type OrdersResp = { content: OrderRow[]; totalElements?: number };

type NodeStatus = "ok" | "warn" | "error" | "idle" | "loading";

// ─── Color tokens ─────────────────────────────────────────────────────────────

const TOKEN = {
  ok: {
    text:       "text-emerald-300",
    textBright: "text-emerald-200",
    border:     "border-emerald-500/40",
    bg:         "bg-emerald-500/[0.08]",
    glow:       "shadow-[0_0_0_1px_rgba(52,211,153,0.25),0_4px_24px_rgba(52,211,153,0.12)]",
    dot:        "bg-emerald-400 shadow-[0_0_10px_3px_rgba(52,211,153,0.6)]",
    iconBg:     "bg-emerald-500/20 border-emerald-500/40",
    ping:       "bg-emerald-400",
    badge:      "bg-emerald-500/15 text-emerald-300 border-emerald-500/30",
    leftBar:    "bg-emerald-500",
  },
  warn: {
    text:       "text-amber-300",
    textBright: "text-amber-200",
    border:     "border-amber-500/40",
    bg:         "bg-amber-500/[0.07]",
    glow:       "shadow-[0_0_0_1px_rgba(245,158,11,0.25),0_4px_24px_rgba(245,158,11,0.10)]",
    dot:        "bg-amber-400 shadow-[0_0_10px_3px_rgba(245,158,11,0.6)]",
    iconBg:     "bg-amber-500/20 border-amber-500/40",
    ping:       "bg-amber-400",
    badge:      "bg-amber-500/15 text-amber-300 border-amber-500/30",
    leftBar:    "bg-amber-500",
  },
  error: {
    text:       "text-rose-300",
    textBright: "text-rose-200",
    border:     "border-rose-500/50",
    bg:         "bg-rose-500/[0.09]",
    glow:       "shadow-[0_0_0_1px_rgba(244,63,94,0.30),0_4px_28px_rgba(244,63,94,0.18)]",
    dot:        "bg-rose-500 shadow-[0_0_12px_4px_rgba(244,63,94,0.65)]",
    iconBg:     "bg-rose-500/20 border-rose-500/40",
    ping:       "bg-rose-500",
    badge:      "bg-rose-500/15 text-rose-300 border-rose-500/30",
    leftBar:    "bg-rose-500",
  },
  idle: {
    text:       "text-neutral-400",
    textBright: "text-neutral-300",
    border:     "border-neutral-700/50",
    bg:         "bg-neutral-800/50",
    glow:       "",
    dot:        "bg-neutral-500",
    iconBg:     "bg-neutral-800 border-neutral-700/50",
    ping:       "",
    badge:      "bg-neutral-700/50 text-neutral-400 border-neutral-600/40",
    leftBar:    "bg-neutral-600",
  },
  loading: {
    text:       "text-sky-300",
    textBright: "text-sky-200",
    border:     "border-sky-500/30",
    bg:         "bg-sky-500/[0.07]",
    glow:       "shadow-[0_0_0_1px_rgba(56,189,248,0.20),0_4px_20px_rgba(56,189,248,0.10)]",
    dot:        "bg-sky-400 shadow-[0_0_10px_3px_rgba(56,189,248,0.5)]",
    iconBg:     "bg-sky-500/20 border-sky-500/30",
    ping:       "bg-sky-400",
    badge:      "bg-sky-500/15 text-sky-300 border-sky-500/30",
    leftBar:    "bg-sky-500",
  },
};

function t(s: NodeStatus) { return TOKEN[s]; }

function StatusIcon({ status, className }: { status: NodeStatus; className?: string }) {
  const cls = cn("h-4 w-4", t(status).text, className);
  if (status === "ok")      return <CheckCircle2 className={cls} />;
  if (status === "error")   return <XCircle className={cls} />;
  if (status === "warn")    return <AlertTriangle className={cls} />;
  if (status === "loading") return <Activity className={cn(cls, "animate-spin")} />;
  return <Circle className={cls} />;
}

// ─── Pipeline node card ───────────────────────────────────────────────────────

function PipelineNode({
  step,
  icon: Icon,
  title,
  status,
  metrics,
  issue,
  fix,
}: {
  step: number;
  icon: React.ElementType;
  title: string;
  status: NodeStatus;
  metrics: { label: string; value: string }[];
  issue?: string;
  fix?: string;
}) {
  const tk = t(status);

  return (
    <motion.div
      initial={{ opacity: 0, x: -16 }}
      animate={{ opacity: 1, x: 0 }}
      transition={{ duration: 0.35, delay: step * 0.07 }}
      className="relative flex gap-4"
    >
      {/* Step icon column */}
      <div className="flex flex-col items-center">
        <div
          className={cn(
            "relative z-10 flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl border-2 transition-all",
            tk.iconBg,
          )}
        >
          <Icon className={cn("h-5 w-5", tk.text)} />
          {(status === "ok" || status === "warn" || status === "error") && (
            <span
              className={cn("absolute inset-0 rounded-2xl animate-ping opacity-15", tk.ping)}
              style={{ animationDuration: status === "error" ? "1.4s" : "2.8s" }}
            />
          )}
        </div>
      </div>

      {/* Card */}
      <div className="flex-1 pb-6">
        <div
          className={cn(
            "relative overflow-hidden rounded-2xl border transition-all duration-300",
            tk.bg, tk.border, tk.glow,
          )}
        >
          {/* Colored left accent bar */}
          <div className={cn("absolute inset-y-0 left-0 w-1 rounded-l-2xl", tk.leftBar)} />

          <div className="pl-4 pr-4 pt-3.5 pb-3.5">
            {/* Header */}
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2.5">
                <span className={cn("inline-block h-2.5 w-2.5 shrink-0 rounded-full", tk.dot)} />
                <span className={cn("text-[13px] font-bold tracking-tight", tk.textBright)}>{title}</span>
              </div>
              <div className={cn("flex items-center gap-1.5 rounded-lg border px-2 py-0.5 text-[10px] font-bold uppercase tracking-widest", tk.badge)}>
                <StatusIcon status={status} className="h-3 w-3" />
                {status}
              </div>
            </div>

            {/* Metrics grid */}
            {metrics.length > 0 && (
              <div className="mt-3 grid grid-cols-2 gap-x-6 gap-y-2.5 border-t border-white/[0.06] pt-3 sm:grid-cols-3">
                {metrics.map((m) => (
                  <div key={m.label}>
                    <div className="text-[10px] font-semibold uppercase tracking-[0.13em] text-neutral-500">
                      {m.label}
                    </div>
                    <div className={cn("mt-0.5 font-mono text-sm font-semibold", tk.textBright)}>
                      {m.value}
                    </div>
                  </div>
                ))}
              </div>
            )}

            {/* Issue */}
            {issue && (
              <div className="mt-3 flex items-start gap-2 rounded-xl border border-rose-500/25 bg-rose-500/[0.12] px-3 py-2.5 text-xs text-rose-300">
                <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-rose-400" />
                <span>{issue}</span>
              </div>
            )}

            {/* Fix */}
            {fix && (
              <div className="mt-2 flex items-center gap-1.5 rounded-lg border border-emerald-500/20 bg-emerald-500/[0.07] px-2.5 py-1.5 text-[11px] text-emerald-400">
                <CheckCircle2 className="h-3.5 w-3.5 shrink-0" />
                <span>{fix}</span>
              </div>
            )}
          </div>
        </div>
      </div>
    </motion.div>
  );
}

// ─── Animated flow connector ──────────────────────────────────────────────────

function Connector({ fromStatus }: { fromStatus?: NodeStatus }) {
  const color = fromStatus === "ok" ? "#34d399" : fromStatus === "warn" ? "#fbbf24" : fromStatus === "error" ? "#f43f5e" : "#404040";
  return (
    <div className="relative -mt-4 mb-0 ml-[21px] flex h-8 w-px flex-col items-center">
      <div
        className="h-full w-0.5 rounded-full"
        style={{ background: `linear-gradient(to bottom, ${color}55, ${color}10)` }}
      />
      <ArrowDown
        className="absolute -bottom-1 -translate-x-1/2 h-3.5 w-3.5"
        style={{ left: "50%", color }}
      />
    </div>
  );
}

// ─── Summary strip ────────────────────────────────────────────────────────────

function SummaryStrip({
  okCount, warnCount, errorCount, idleCount,
}: { okCount: number; warnCount: number; errorCount: number; idleCount: number }) {
  const total = okCount + warnCount + errorCount + idleCount;
  const overall: NodeStatus =
    errorCount > 0 ? "error" : warnCount > 0 ? "warn" : idleCount === total ? "idle" : "ok";

  const label = {
    ok:      "ALL SYSTEMS GO",
    warn:    "NEEDS ATTENTION",
    error:   "PIPELINE BLOCKED",
    idle:    "AWAITING DATA",
    loading: "LOADING",
  }[overall];

  const tk = t(overall);

  return (
    <motion.div
      initial={{ opacity: 0, scale: 0.98 }}
      animate={{ opacity: 1, scale: 1 }}
      transition={{ duration: 0.3 }}
      className={cn(
        "mb-8 flex flex-wrap items-center justify-between gap-4 rounded-2xl border-2 px-6 py-4 transition-all",
        tk.bg, tk.border, tk.glow,
      )}
    >
      <div className="flex items-center gap-3">
        <span className={cn("relative inline-flex h-4 w-4 shrink-0")}>
          <span className={cn("absolute inline-flex h-full w-full animate-ping rounded-full opacity-40", tk.ping)} style={{ animationDuration: "1.6s" }} />
          <span className={cn("relative inline-flex h-4 w-4 rounded-full", tk.dot)} />
        </span>
        <span className={cn("text-base font-black tracking-[0.18em] uppercase", tk.textBright)}>{label}</span>
      </div>

      <div className="flex flex-wrap items-center gap-4 text-xs font-semibold">
        <span className="flex items-center gap-1.5 rounded-lg bg-emerald-500/10 border border-emerald-500/25 px-3 py-1.5 text-emerald-300">
          <CheckCircle2 className="h-3.5 w-3.5" /> {okCount} healthy
        </span>
        {warnCount > 0 && (
          <span className="flex items-center gap-1.5 rounded-lg bg-amber-500/10 border border-amber-500/25 px-3 py-1.5 text-amber-300">
            <AlertTriangle className="h-3.5 w-3.5" /> {warnCount} warning
          </span>
        )}
        {errorCount > 0 && (
          <span className="flex items-center gap-1.5 rounded-lg bg-rose-500/10 border border-rose-500/25 px-3 py-1.5 text-rose-300">
            <XCircle className="h-3.5 w-3.5" /> {errorCount} blocked
          </span>
        )}
        {idleCount > 0 && (
          <span className="flex items-center gap-1.5 rounded-lg bg-neutral-700/40 border border-neutral-600/30 px-3 py-1.5 text-neutral-400">
            <Clock className="h-3.5 w-3.5" /> {idleCount} pending
          </span>
        )}
      </div>
    </motion.div>
  );
}

// ─── Main page ────────────────────────────────────────────────────────────────

export function AdminPipelineHealthPage() {
  const settings = useQuery({
    queryKey: ["pipeline-health-settings"],
    queryFn: async () => (await api.get("/api/admin/settings/summary")).data?.data as SettingsSummary,
    refetchInterval: 10_000,
    retry: 1,
  });

  const health = useQuery({
    queryKey: ["pipeline-health-health"],
    queryFn: async () => (await api.get("/api/admin/health")).data?.data as HealthData,
    refetchInterval: 10_000,
    retry: 1,
  });

  const orders = useQuery({
    queryKey: ["pipeline-health-orders"],
    queryFn: async () =>
      (await api.get("/api/admin/oms/orders?page=0&size=50&sort=createdAt,desc")).data?.data as OrdersResp,
    refetchInterval: 15_000,
    retry: 1,
  });

  const snapshot = useQuery({
    queryKey: ["pipeline-health-snapshot"],
    queryFn: fetchAdminOpsSnapshotMerged,
    refetchInterval: 10_000,
    retry: 1,
  });

  const s = settings.data;
  const h = health.data;
  const orderList = orders.data?.content ?? [];
  const snap = snapshot.data;
  const sigDist = asRecord(snap?.signalDistribution);
  const omsSnap = asRecord(snap?.oms);
  const signalsTotal = Number(sigDist?.signalsPersistedTotal ?? 0);
  const signalsLast60m = Number(sigDist?.signalsEmittedLast60m ?? 0);
  const signalsRoutedToOms = Number(sigDist?.signalsRoutedToOmsTotal ?? 0);

  const wsState = s?.marketFeedState ?? "UNKNOWN";
  const wsOk = wsState === "CONNECTED" || wsState === "OPEN";
  const feedStatus: NodeStatus   = settings.isLoading ? "loading" : wsOk ? "ok" : "error";
  const coverageStatus: NodeStatus = feedStatus === "ok" ? "ok" : feedStatus === "loading" ? "loading" : "warn";
  const strategyStatus: NodeStatus = snapshot.isLoading
    ? "loading" : signalsLast60m > 0 ? "ok" : feedStatus === "ok" ? "warn" : "idle";
  const dispatchStatus: NodeStatus =
    signalsTotal > 0 ? "ok" : strategyStatus === "idle" ? "idle" : "warn";

  const recentOrders    = orderList.slice(0, 20);
  const createdOrders   = recentOrders.filter((o) => o.state === "CREATED" || o.state === "VALIDATED").length;
  const rejectedOrders  = recentOrders.filter((o) => o.state === "REJECTED").length;
  const filledOrders    = recentOrders.filter((o) => o.state === "FILLED" || o.state === "PARTIAL_FILL").length;
  const omsStatus: NodeStatus = orders.isLoading
    ? "loading"
    : filledOrders > 0 || createdOrders > 0 ? "ok"
    : rejectedOrders > recentOrders.length * 0.8 && recentOrders.length > 0 ? "error"
    : orderList.length === 0 ? "idle" : "warn";
  const riskStatus: NodeStatus  = omsStatus === "idle" ? "idle" : omsStatus === "ok" ? "ok" : "warn";
  const execStatus: NodeStatus  = filledOrders > 0 ? "ok" : omsStatus === "idle" ? "idle" : omsStatus === "ok" ? "warn" : "idle";
  const ordersLast60s = Number(omsSnap?.ordersLast60s ?? 0);

  const statusList = [feedStatus, coverageStatus, strategyStatus, dispatchStatus, omsStatus, riskStatus, execStatus];
  const okCount    = statusList.filter((x) => x === "ok").length;
  const warnCount  = statusList.filter((x) => x === "warn").length;
  const errorCount = statusList.filter((x) => x === "error").length;
  const idleCount  = statusList.filter((x) => x === "idle" || x === "loading").length;

  const uptime = s?.uptimeHuman ?? (h ? `${Math.floor((h.uptimeSeconds ?? 0) / 3600)}h ${Math.floor(((h.uptimeSeconds ?? 0) % 3600) / 60)}m` : "—");

  return (
    <div className="mx-auto max-w-2xl space-y-2 px-1 pb-12">
      {/* Page header */}
      <motion.div initial={{ opacity: 0, y: -10 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.28 }}>
        <div className="mb-6 flex items-start justify-between gap-4">
          <div>
            <h1 className="text-2xl font-black tracking-tight text-white">Pipeline Health</h1>
            <p className="mt-1 text-xs text-neutral-500">
              Live signal flow · Zerodha → Strategy → OMS → Execution · auto-refresh 10s
            </p>
          </div>
          <div className="flex items-center gap-2 rounded-xl border border-neutral-700/60 bg-neutral-800/70 px-3.5 py-2.5 text-xs font-semibold text-neutral-300 shadow-inner">
            <Clock className="h-3.5 w-3.5 text-sky-400" />
            <span>Uptime <span className="text-sky-300">{uptime}</span></span>
          </div>
        </div>
      </motion.div>

      <SummaryStrip okCount={okCount} warnCount={warnCount} errorCount={errorCount} idleCount={idleCount} />

      {/* 1. Market data feed */}
      <PipelineNode
        step={0}
        icon={Wifi}
        title="Market Data Feed  ·  Zerodha WebSocket"
        status={feedStatus}
        metrics={[
          { label: "WS State",     value: wsState },
          { label: "Subscriptions",value: String(s?.marketFeedSubscriptions ?? 0) },
          { label: "Ticks / sec",  value: s?.marketFeedTicksPerSec ?? "-" },
          { label: "Last packet",  value: s?.marketFeedLastPacket ? new Date(s.marketFeedLastPacket).toLocaleTimeString() : "—" },
          { label: "Mode",         value: s?.liveTradingArmed ?? "—" },
          { label: "Kill switch",  value: s?.killSwitch ?? "—" },
        ]}
        issue={!wsOk && !settings.isLoading ? `WebSocket ${wsState} — re-authenticate Zerodha or check token` : undefined}
      />
      <Connector fromStatus={feedStatus} />

      {/* 2. Historical backfill */}
      <PipelineNode
        step={1}
        icon={Database}
        title="Historical Backfill  ·  30-day lookback"
        status={feedStatus === "ok" ? "ok" : "warn"}
        metrics={[
          { label: "Schedule",   value: "08:55 AM IST + startup" },
          { label: "Lookback",   value: "30 days" },
          { label: "Chunk size", value: "55 days" },
          { label: "Rate limit", value: "350 ms" },
        ]}
        fix="Bug fixed — backfill no longer skips stocks with live ticks (commit a48e970)"
      />
      <Connector fromStatus={feedStatus === "ok" ? "ok" : "warn"} />

      {/* 3. Coverage gate */}
      <PipelineNode
        step={2}
        icon={ShieldCheck}
        title="Readiness Gate  ·  Coverage check per symbol"
        status={coverageStatus}
        metrics={[
          { label: "Mode",      value: "autoRefresh=true" },
          { label: "Lookback",  value: "180 min" },
          { label: "Tolerance", value: "STALE / GAPS" },
        ]}
        fix="Bug fixed — NO_DATA for all symbols resolved (commit 652e943)"
      />
      <Connector fromStatus={coverageStatus} />

      {/* 4. Strategy evaluation */}
      <PipelineNode
        step={3}
        icon={GitBranch}
        title="Strategy Evaluation  ·  6 generators per symbol"
        status={strategyStatus}
        metrics={[
          { label: "Generators",      value: "6" },
          { label: "Signals last 60m",value: String(signalsLast60m) },
          { label: "Signals total",   value: String(signalsTotal) },
          { label: "Scan interval",   value: "60 sec" },
          { label: "Strategies",      value: String(s?.strategiesTotal ?? "—") },
          { label: "Execution mode",  value: "PAPER" },
        ]}
        fix="3 NPEs fixed — EMA slope, null candle prices, system userId (commits 14245d4, 6edc248, 52cb339)"
        issue={strategyStatus === "warn" ? "Market closed or insufficient bars — signals expected after 09:30 IST" : undefined}
      />
      <Connector fromStatus={strategyStatus} />

      {/* 5. Signal dispatch */}
      <PipelineNode
        step={4}
        icon={Radio}
        title="Signal Dispatch  ·  RabbitMQ → OMS_ORDER queue"
        status={dispatchStatus}
        metrics={[
          { label: "Queue",         value: "OMS_ORDER" },
          { label: "Signals total", value: String(signalsTotal) },
          { label: "Routed to OMS", value: String(signalsRoutedToOms) },
        ]}
      />
      <Connector fromStatus={dispatchStatus} />

      {/* 6. OMS */}
      <PipelineNode
        step={5}
        icon={Zap}
        title="OMS Order Intent  ·  Risk gate + order creation"
        status={omsStatus}
        metrics={[
          { label: "Recent orders",   value: String(recentOrders.length) },
          { label: "Created",         value: String(createdOrders) },
          { label: "Filled",          value: String(filledOrders) },
          { label: "Rejected",        value: String(rejectedOrders) },
          { label: "Orders last 60s", value: String(ordersLast60s) },
        ]}
        fix="Bug fixed — null userId NPE in OmsOrderIntentListener resolved (commit 52cb339)"
        issue={omsStatus === "idle" ? "No orders yet — will activate after first signal flows through" : undefined}
      />
      <Connector fromStatus={omsStatus} />

      {/* 7. Risk engine */}
      <PipelineNode
        step={6}
        icon={ShieldCheck}
        title="Risk Engine  ·  Pre-execution safety checks"
        status={riskStatus}
        metrics={[
          { label: "Gate",       value: "PAPER allowed" },
          { label: "Kill switch",value: h?.killSwitch ? "ENGAGED" : "OFF" },
          { label: "Live armed", value: h?.liveTradingArmed ? "ARMED" : "DISARMED" },
        ]}
        issue={h?.killSwitch ? "Kill switch is ENGAGED — no orders will execute" : undefined}
      />
      <Connector fromStatus={riskStatus} />

      {/* 8. Execution */}
      <PipelineNode
        step={7}
        icon={TrendingUp}
        title="Execution  ·  Paper sim / Zerodha live"
        status={execStatus}
        metrics={[
          { label: "Mode",   value: "PAPER" },
          { label: "Filled", value: String(filledOrders) },
          { label: "Broker", value: "SIM / ZERODHA" },
        ]}
        issue={execStatus === "idle" ? "Not yet reached — waiting for first signal to complete full pipeline" : undefined}
      />

      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ delay: 0.7 }}
        className="pt-6 text-center text-[11px] text-neutral-600"
      >
        Data from /api/admin/settings/summary · /api/admin/health · /api/admin/oms/orders · /api/admin/signals
      </motion.div>
    </div>
  );
}
