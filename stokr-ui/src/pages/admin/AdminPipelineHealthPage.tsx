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
import { GlassPanel } from "../../components/ds/GlassPanel";
import { fetchAdminOpsSnapshotMerged } from "../../lib/fetchAdminOpsSnapshotMerged";
import { asRecord, type OpsSnapshot } from "../../components/admin/cockpit/opsTypes";
import { cn } from "../../lib/utils";

// ─── API shapes ──────────────────────────────────────────────────────────────

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

// ─── Status helpers ───────────────────────────────────────────────────────────

type NodeStatus = "ok" | "warn" | "error" | "idle" | "loading";

function statusColor(s: NodeStatus) {
  return {
    ok: "text-emerald-400",
    warn: "text-amber-400",
    error: "text-rose-400",
    idle: "text-neutral-500",
    loading: "text-blue-400",
  }[s];
}

function statusBg(s: NodeStatus) {
  return {
    ok: "bg-emerald-500/10 border-emerald-500/25",
    warn: "bg-amber-500/10 border-amber-500/25",
    error: "bg-rose-500/10 border-rose-500/25",
    idle: "bg-neutral-800/60 border-neutral-700/40",
    loading: "bg-blue-500/10 border-blue-500/20",
  }[s];
}

function statusDot(s: NodeStatus) {
  return {
    ok: "bg-emerald-400 shadow-[0_0_8px_2px_rgba(52,211,153,0.45)]",
    warn: "bg-amber-400 shadow-[0_0_8px_2px_rgba(251,191,36,0.45)]",
    error: "bg-rose-500 shadow-[0_0_8px_2px_rgba(239,68,68,0.45)]",
    idle: "bg-neutral-600",
    loading: "bg-blue-400 shadow-[0_0_8px_2px_rgba(96,165,250,0.45)]",
  }[s];
}

function StatusIcon({ status, className }: { status: NodeStatus; className?: string }) {
  const cls = cn("h-4 w-4", statusColor(status), className);
  if (status === "ok") return <CheckCircle2 className={cls} />;
  if (status === "error") return <XCircle className={cls} />;
  if (status === "warn") return <AlertTriangle className={cls} />;
  if (status === "loading") return <Activity className={cls} />;
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
  return (
    <motion.div
      initial={{ opacity: 0, x: -12 }}
      animate={{ opacity: 1, x: 0 }}
      transition={{ duration: 0.3, delay: step * 0.06 }}
      className="relative flex gap-4"
    >
      {/* Step spine */}
      <div className="flex flex-col items-center">
        <div
          className={cn(
            "relative z-10 flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border text-sm font-bold",
            statusBg(status),
          )}
        >
          <Icon className={cn("h-4 w-4", statusColor(status))} />
          {/* Pulse ring on ok/warn */}
          {(status === "ok" || status === "warn") && (
            <span
              className={cn(
                "absolute inset-0 rounded-xl animate-ping opacity-20",
                status === "ok" ? "bg-emerald-400" : "bg-amber-400",
              )}
              style={{ animationDuration: "2.5s" }}
            />
          )}
        </div>
      </div>

      {/* Card body */}
      <div className="flex-1 pb-6">
        <GlassPanel
          variant="dark"
          className={cn("overflow-hidden border p-4 transition-colors duration-300", statusBg(status))}
        >
          {/* Header */}
          <div className="flex items-center justify-between gap-3">
            <div className="flex items-center gap-2.5">
              <span
                className={cn("inline-block h-2 w-2 shrink-0 rounded-full", statusDot(status))}
              />
              <span className="text-[13px] font-semibold tracking-tight text-white">{title}</span>
            </div>
            <StatusIcon status={status} />
          </div>

          {/* Metrics grid */}
          {metrics.length > 0 && (
            <div className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 border-t border-white/[0.05] pt-3 sm:grid-cols-3">
              {metrics.map((m) => (
                <div key={m.label}>
                  <div className="text-[10px] font-semibold uppercase tracking-[0.12em] text-neutral-500">
                    {m.label}
                  </div>
                  <div className="mt-0.5 font-mono text-sm font-medium text-white">{m.value}</div>
                </div>
              ))}
            </div>
          )}

          {/* Issue banner */}
          {issue && (
            <div className="mt-3 flex items-start gap-2 rounded-lg border border-rose-500/20 bg-rose-500/10 px-3 py-2 text-xs text-rose-300">
              <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
              <span>{issue}</span>
            </div>
          )}

          {/* Fix badge */}
          {fix && (
            <div className="mt-2 flex items-center gap-1.5 text-[11px] text-emerald-400">
              <CheckCircle2 className="h-3 w-3" />
              <span>{fix}</span>
            </div>
          )}
        </GlassPanel>
      </div>
    </motion.div>
  );
}

function Connector() {
  return (
    <div className="relative -mt-4 mb-0 ml-5 flex h-6 w-px flex-col items-center">
      <div className="h-full w-px bg-gradient-to-b from-neutral-700 to-neutral-800" />
      <ArrowDown className="absolute -bottom-1 h-3 w-3 -translate-x-1/2 text-neutral-600" style={{ left: "50%" }} />
    </div>
  );
}

// ─── Summary strip ────────────────────────────────────────────────────────────

function SummaryStrip({
  okCount,
  warnCount,
  errorCount,
  idleCount,
}: {
  okCount: number;
  warnCount: number;
  errorCount: number;
  idleCount: number;
}) {
  const total = okCount + warnCount + errorCount + idleCount;
  const overall: NodeStatus =
    errorCount > 0 ? "error" : warnCount > 0 ? "warn" : idleCount === total ? "idle" : "ok";

  const label = {
    ok: "ALL SYSTEMS GO",
    warn: "NEEDS ATTENTION",
    error: "PIPELINE BLOCKED",
    idle: "AWAITING DATA",
    loading: "LOADING",
  }[overall];

  return (
    <GlassPanel
      variant="dark"
      accent={overall === "ok"}
      className={cn("mb-8 flex flex-wrap items-center justify-between gap-4 px-5 py-4 border", statusBg(overall))}
    >
      <div className="flex items-center gap-3">
        <span className={cn("inline-block h-3 w-3 rounded-full", statusDot(overall))} />
        <span className={cn("text-sm font-bold tracking-widest uppercase", statusColor(overall))}>{label}</span>
      </div>
      <div className="flex items-center gap-5 text-xs">
        <span className="flex items-center gap-1.5 text-emerald-400">
          <CheckCircle2 className="h-3.5 w-3.5" /> {okCount} healthy
        </span>
        {warnCount > 0 && (
          <span className="flex items-center gap-1.5 text-amber-400">
            <AlertTriangle className="h-3.5 w-3.5" /> {warnCount} warning
          </span>
        )}
        {errorCount > 0 && (
          <span className="flex items-center gap-1.5 text-rose-400">
            <XCircle className="h-3.5 w-3.5" /> {errorCount} blocked
          </span>
        )}
        {idleCount > 0 && (
          <span className="flex items-center gap-1.5 text-neutral-500">
            <Clock className="h-3.5 w-3.5" /> {idleCount} pending
          </span>
        )}
      </div>
    </GlassPanel>
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

  // ── derive statuses ────────────────────────────────────────────────────────

  const wsState = s?.marketFeedState ?? "UNKNOWN";
  const wsOk = wsState === "CONNECTED" || wsState === "OPEN";
  const feedStatus: NodeStatus = settings.isLoading ? "loading" : wsOk ? "ok" : "error";

  const ticks = s?.marketFeedTicksPerSec ?? "-";
  const subs = s?.marketFeedSubscriptions ?? 0;

  // Coverage: infer from feed being connected
  const coverageStatus: NodeStatus = feedStatus === "ok" ? "ok" : feedStatus === "loading" ? "loading" : "warn";

  // Strategy evaluation: ok if signals exist in last 60m
  const strategyStatus: NodeStatus = snapshot.isLoading
    ? "loading"
    : signalsLast60m > 0
      ? "ok"
      : feedStatus === "ok"
        ? "warn"
        : "idle";

  // Signal dispatch
  const dispatchStatus: NodeStatus =
    signalsTotal > 0 ? "ok" : strategyStatus === "idle" ? "idle" : "warn";

  // OMS — check if there are CREATED/VALIDATED orders
  const recentOrders = orderList.slice(0, 20);
  const createdOrders = recentOrders.filter((o) => o.state === "CREATED" || o.state === "VALIDATED").length;
  const rejectedOrders = recentOrders.filter((o) => o.state === "REJECTED").length;
  const filledOrders = recentOrders.filter((o) => o.state === "FILLED" || o.state === "PARTIAL_FILL").length;
  const omsStatus: NodeStatus = orders.isLoading
    ? "loading"
    : filledOrders > 0 || createdOrders > 0
      ? "ok"
      : rejectedOrders > recentOrders.length * 0.8 && recentOrders.length > 0
        ? "error"
        : orderList.length === 0
          ? "idle"
          : "warn";

  // Risk
  const riskStatus: NodeStatus = omsStatus === "idle" ? "idle" : omsStatus === "ok" ? "ok" : "warn";

  // Execution
  const execStatus: NodeStatus =
    filledOrders > 0 ? "ok" : omsStatus === "idle" ? "idle" : omsStatus === "ok" ? "warn" : "idle";

  const ordersLast60s = Number(omsSnap?.ordersLast60s ?? 0);
  const statusList = [feedStatus, coverageStatus, strategyStatus, dispatchStatus, omsStatus, riskStatus, execStatus];
  const okCount = statusList.filter((x) => x === "ok").length;
  const warnCount = statusList.filter((x) => x === "warn").length;
  const errorCount = statusList.filter((x) => x === "error").length;
  const idleCount = statusList.filter((x) => x === "idle" || x === "loading").length;

  // ── uptime ────────────────────────────────────────────────────────────────
  const uptime = s?.uptimeHuman ?? h ? `${Math.floor((h?.uptimeSeconds ?? 0) / 3600)}h ${Math.floor(((h?.uptimeSeconds ?? 0) % 3600) / 60)}m` : "—";

  return (
    <div className="mx-auto max-w-2xl space-y-2">
      {/* Page header */}
      <motion.div initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.25 }}>
        <div className="mb-6 flex items-start justify-between gap-4">
          <div>
            <h1 className="text-xl font-semibold tracking-tight text-white">Pipeline health</h1>
            <p className="mt-1 text-xs text-neutral-500">
              Live signal flow · Zerodha → Strategy → OMS → Execution · auto-refresh 10s
            </p>
          </div>
          <div className="flex items-center gap-2 rounded-xl border border-neutral-800 bg-neutral-900/60 px-3 py-2 text-xs text-neutral-400">
            <Clock className="h-3.5 w-3.5" />
            <span>Uptime {uptime}</span>
          </div>
        </div>
      </motion.div>

      {/* Overall health strip */}
      <SummaryStrip okCount={okCount} warnCount={warnCount} errorCount={errorCount} idleCount={idleCount} />

      {/* ── Pipeline flow ── */}

      {/* 1. Market data feed */}
      <PipelineNode
        step={0}
        icon={Wifi}
        title="Market Data Feed  ·  Zerodha WebSocket"
        status={feedStatus}
        metrics={[
          { label: "WS State", value: wsState },
          { label: "Subscriptions", value: String(subs) },
          { label: "Ticks / sec", value: ticks },
          { label: "Last packet", value: s?.marketFeedLastPacket ? new Date(s.marketFeedLastPacket).toLocaleTimeString() : "—" },
          { label: "Mode", value: s?.liveTradingArmed ?? "—" },
          { label: "Kill switch", value: s?.killSwitch ?? "—" },
        ]}
        issue={!wsOk && !settings.isLoading ? `WebSocket ${wsState} — re-authenticate Zerodha or check token` : undefined}
      />

      <Connector />

      {/* 2. Historical backfill */}
      <PipelineNode
        step={1}
        icon={Database}
        title="Historical Backfill  ·  30-day lookback"
        status={feedStatus === "ok" ? "ok" : "warn"}
        metrics={[
          { label: "Schedule", value: "08:55 AM IST + startup" },
          { label: "Lookback", value: "30 days" },
          { label: "Chunk size", value: "55 days" },
          { label: "Rate limit", value: "350 ms" },
        ]}
        fix="Bug fixed — backfill no longer skips stocks with live ticks (commit a48e970)"
      />

      <Connector />

      {/* 3. Coverage gate */}
      <PipelineNode
        step={2}
        icon={ShieldCheck}
        title="Readiness Gate  ·  Coverage check per symbol"
        status={coverageStatus}
        metrics={[
          { label: "Mode", value: "autoRefresh=true" },
          { label: "Lookback", value: "180 min" },
          { label: "Tolerance", value: "STALE / GAPS" },
        ]}
        fix="Bug fixed — NO_DATA for all symbols resolved (commit 652e943)"
      />

      <Connector />

      {/* 4. Strategy evaluation */}
      <PipelineNode
        step={3}
        icon={GitBranch}
        title="Strategy Evaluation  ·  6 generators per symbol"
        status={strategyStatus}
        metrics={[
          { label: "Generators", value: "6" },
          { label: "Signals last 60m", value: String(signalsLast60m) },
          { label: "Signals total", value: String(signalsTotal) },
          { label: "Scan interval", value: "60 sec" },
          { label: "Strategies", value: String(s?.strategiesTotal ?? "—") },
          { label: "Execution mode", value: "PAPER" },
        ]}
        fix="3 NPEs fixed — EMA slope, null candle prices, system userId (commits 14245d4, 6edc248, 52cb339)"
        issue={strategyStatus === "warn" ? "Market closed or insufficient bars — signals expected after 09:30 IST" : undefined}
      />

      <Connector />

      {/* 5. Signal dispatch */}
      <PipelineNode
        step={4}
        icon={Radio}
        title="Signal Dispatch  ·  RabbitMQ → OMS_ORDER queue"
        status={dispatchStatus}
        metrics={[
          { label: "Queue", value: "OMS_ORDER" },
          { label: "Signals total", value: String(signalsTotal) },
          { label: "Routed to OMS", value: String(signalsRoutedToOms) },
        ]}
      />

      <Connector />

      {/* 6. OMS intent */}
      <PipelineNode
        step={5}
        icon={Zap}
        title="OMS Order Intent  ·  Risk gate + order creation"
        status={omsStatus}
        metrics={[
          { label: "Recent orders", value: String(recentOrders.length) },
          { label: "Created", value: String(createdOrders) },
          { label: "Filled", value: String(filledOrders) },
          { label: "Rejected", value: String(rejectedOrders) },
          { label: "Orders last 60s", value: String(ordersLast60s) },
        ]}
        fix="Bug fixed — null userId NPE in OmsOrderIntentListener resolved (commit 52cb339)"
        issue={omsStatus === "idle" ? "No orders yet — will activate after first signal flows through" : undefined}
      />

      <Connector />

      {/* 7. Risk engine */}
      <PipelineNode
        step={6}
        icon={ShieldCheck}
        title="Risk Engine  ·  Pre-execution safety checks"
        status={riskStatus}
        metrics={[
          { label: "Gate", value: "PAPER allowed" },
          { label: "Kill switch", value: h?.killSwitch ? "ENGAGED" : "OFF" },
          { label: "Live armed", value: h?.liveTradingArmed ? "ARMED" : "DISARMED" },
        ]}
        issue={h?.killSwitch ? "Kill switch is ENGAGED — no orders will execute" : undefined}
      />

      <Connector />

      {/* 8. Execution */}
      <PipelineNode
        step={7}
        icon={TrendingUp}
        title="Execution  ·  Paper sim / Zerodha live"
        status={execStatus}
        metrics={[
          { label: "Mode", value: "PAPER" },
          { label: "Filled", value: String(filledOrders) },
          { label: "Broker", value: "SIM / ZERODHA" },
        ]}
        issue={execStatus === "idle" ? "Not yet reached — waiting for first signal to complete full pipeline" : undefined}
      />

      {/* Footer note */}
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ delay: 0.6 }}
        className="pt-4 text-center text-[11px] text-neutral-600"
      >
        Data from /api/admin/settings/summary · /api/admin/health · /api/admin/oms/orders · /api/admin/signals
      </motion.div>
    </div>
  );
}
