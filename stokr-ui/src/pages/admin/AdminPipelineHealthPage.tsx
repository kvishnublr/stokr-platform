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
import { asRecord } from "../../components/admin/cockpit/opsTypes";
import { cn } from "../../lib/utils";
import { useUiThemeStore } from "../../state/uiTheme";

type SettingsSummary = {
  killSwitch: string;
  liveTradingArmed: string;
  uptimeSeconds: number;
  uptimeHuman: string;
  strategiesTotal: number;
  strategyExecutionModes?: Record<string, string>;
  marketFeedState?: string;
  marketFeedSubscriptions?: number;
  marketFeedTicksPerSec?: string;
  marketFeedLastPacket?: string;
};

function formatExecutionModeSummary(
  modes: Record<string, string> | undefined,
  liveTradingArmed: string | undefined,
): string {
  if (!modes || Object.keys(modes).length === 0) {
    return "—";
  }
  const counts = new Map<string, number>();
  for (const mode of Object.values(modes)) {
    const key = (mode ?? "PAPER").toUpperCase();
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  const order = ["LIVE", "PAPER", "DRY_RUN", "DISABLED"];
  const parts = order
    .filter((k) => (counts.get(k) ?? 0) > 0)
    .map((k) => `${counts.get(k)} ${k}`);
  const armed = liveTradingArmed === "ARMED";
  const suffix = armed ? " · armed" : " · disarmed";
  return parts.length > 0 ? `${parts.join(" / ")}${suffix}` : `—${suffix}`;
}

function riskGateLabel(
  modes: Record<string, string> | undefined,
  liveTradingArmed: string | undefined,
  killSwitchEngaged: boolean,
): string {
  if (killSwitchEngaged) {
    return "blocked (kill switch)";
  }
  const liveCount = modes
    ? Object.values(modes).filter((m) => m?.toUpperCase() === "LIVE").length
    : 0;
  const armed = liveTradingArmed === "ARMED";
  if (liveCount > 0 && armed) {
    return `${liveCount} LIVE validated · armed`;
  }
  if (liveCount > 0 && !armed) {
    return `${liveCount} LIVE configured · disarmed`;
  }
  return "PAPER / DRY_RUN only";
}

type HealthData = {
  killSwitch: boolean;
  liveTradingArmed: boolean;
  uptimeSeconds: number;
};

type OrderRow = { state: string; executionMode: string | null };
type OrdersResp = { content: OrderRow[]; totalElements?: number };

type NodeStatus = "ok" | "warn" | "error" | "idle" | "loading";

// ─── Dual-mode color tokens ───────────────────────────────────────────────────

type ModeTokens = {
  text: string;
  textBright: string;
  border: string;
  bg: string;
  glow: string;
  dot: string;
  iconBg: string;
  ping: string;
  badgeBg: string;
  badgeText: string;
  badgeBorder: string;
  leftBar: string;
  metricValue: string;
  metricLabel: string;
};

const DARK: Record<NodeStatus, ModeTokens> = {
  ok: {
    text:        "text-emerald-400",
    textBright:  "text-emerald-200",
    border:      "border-emerald-500/40",
    bg:          "bg-emerald-500/[0.08]",
    glow:        "shadow-[0_0_0_1px_rgba(52,211,153,0.20),0_4px_24px_rgba(52,211,153,0.10)]",
    dot:         "bg-emerald-400 shadow-[0_0_10px_3px_rgba(52,211,153,0.55)]",
    iconBg:      "bg-emerald-500/15 border-emerald-500/35",
    ping:        "bg-emerald-400",
    badgeBg:     "bg-emerald-500/15",
    badgeText:   "text-emerald-300",
    badgeBorder: "border-emerald-500/30",
    leftBar:     "bg-emerald-500",
    metricValue: "text-emerald-200",
    metricLabel: "text-neutral-500",
  },
  warn: {
    text:        "text-amber-400",
    textBright:  "text-amber-200",
    border:      "border-amber-500/40",
    bg:          "bg-amber-500/[0.07]",
    glow:        "shadow-[0_0_0_1px_rgba(245,158,11,0.20),0_4px_24px_rgba(245,158,11,0.08)]",
    dot:         "bg-amber-400 shadow-[0_0_10px_3px_rgba(245,158,11,0.55)]",
    iconBg:      "bg-amber-500/15 border-amber-500/35",
    ping:        "bg-amber-400",
    badgeBg:     "bg-amber-500/15",
    badgeText:   "text-amber-300",
    badgeBorder: "border-amber-500/30",
    leftBar:     "bg-amber-500",
    metricValue: "text-amber-200",
    metricLabel: "text-neutral-500",
  },
  error: {
    text:        "text-rose-400",
    textBright:  "text-rose-200",
    border:      "border-rose-500/50",
    bg:          "bg-rose-500/[0.09]",
    glow:        "shadow-[0_0_0_1px_rgba(244,63,94,0.28),0_4px_28px_rgba(244,63,94,0.15)]",
    dot:         "bg-rose-500 shadow-[0_0_12px_4px_rgba(244,63,94,0.60)]",
    iconBg:      "bg-rose-500/15 border-rose-500/40",
    ping:        "bg-rose-500",
    badgeBg:     "bg-rose-500/15",
    badgeText:   "text-rose-300",
    badgeBorder: "border-rose-500/30",
    leftBar:     "bg-rose-500",
    metricValue: "text-rose-200",
    metricLabel: "text-neutral-500",
  },
  idle: {
    text:        "text-neutral-400",
    textBright:  "text-neutral-300",
    border:      "border-neutral-700/50",
    bg:          "bg-neutral-800/60",
    glow:        "",
    dot:         "bg-neutral-500",
    iconBg:      "bg-neutral-800 border-neutral-700/50",
    ping:        "",
    badgeBg:     "bg-neutral-700/50",
    badgeText:   "text-neutral-400",
    badgeBorder: "border-neutral-600/40",
    leftBar:     "bg-neutral-600",
    metricValue: "text-neutral-300",
    metricLabel: "text-neutral-600",
  },
  loading: {
    text:        "text-sky-400",
    textBright:  "text-sky-200",
    border:      "border-sky-500/30",
    bg:          "bg-sky-500/[0.07]",
    glow:        "shadow-[0_0_0_1px_rgba(56,189,248,0.18),0_4px_20px_rgba(56,189,248,0.08)]",
    dot:         "bg-sky-400 shadow-[0_0_10px_3px_rgba(56,189,248,0.45)]",
    iconBg:      "bg-sky-500/15 border-sky-500/30",
    ping:        "bg-sky-400",
    badgeBg:     "bg-sky-500/15",
    badgeText:   "text-sky-300",
    badgeBorder: "border-sky-500/30",
    leftBar:     "bg-sky-500",
    metricValue: "text-sky-200",
    metricLabel: "text-neutral-500",
  },
};

const LIGHT: Record<NodeStatus, ModeTokens> = {
  ok: {
    text:        "text-emerald-700",
    textBright:  "text-emerald-900",
    border:      "border-emerald-300",
    bg:          "bg-emerald-50",
    glow:        "shadow-[0_2px_12px_rgba(16,185,129,0.15)]",
    dot:         "bg-emerald-500",
    iconBg:      "bg-emerald-100 border-emerald-300",
    ping:        "bg-emerald-400",
    badgeBg:     "bg-emerald-100",
    badgeText:   "text-emerald-800",
    badgeBorder: "border-emerald-300",
    leftBar:     "bg-emerald-500",
    metricValue: "text-emerald-900",
    metricLabel: "text-emerald-600/70",
  },
  warn: {
    text:        "text-amber-700",
    textBright:  "text-amber-900",
    border:      "border-amber-300",
    bg:          "bg-amber-50",
    glow:        "shadow-[0_2px_12px_rgba(217,119,6,0.15)]",
    dot:         "bg-amber-500",
    iconBg:      "bg-amber-100 border-amber-300",
    ping:        "bg-amber-400",
    badgeBg:     "bg-amber-100",
    badgeText:   "text-amber-800",
    badgeBorder: "border-amber-300",
    leftBar:     "bg-amber-500",
    metricValue: "text-amber-900",
    metricLabel: "text-amber-700/70",
  },
  error: {
    text:        "text-rose-700",
    textBright:  "text-rose-900",
    border:      "border-rose-300",
    bg:          "bg-rose-50",
    glow:        "shadow-[0_2px_14px_rgba(225,29,72,0.18)]",
    dot:         "bg-rose-500",
    iconBg:      "bg-rose-100 border-rose-300",
    ping:        "bg-rose-400",
    badgeBg:     "bg-rose-100",
    badgeText:   "text-rose-800",
    badgeBorder: "border-rose-300",
    leftBar:     "bg-rose-500",
    metricValue: "text-rose-900",
    metricLabel: "text-rose-600/70",
  },
  idle: {
    text:        "text-neutral-500",
    textBright:  "text-neutral-700",
    border:      "border-neutral-200",
    bg:          "bg-neutral-50",
    glow:        "shadow-sm",
    dot:         "bg-neutral-400",
    iconBg:      "bg-neutral-100 border-neutral-200",
    ping:        "",
    badgeBg:     "bg-neutral-100",
    badgeText:   "text-neutral-600",
    badgeBorder: "border-neutral-200",
    leftBar:     "bg-neutral-300",
    metricValue: "text-neutral-700",
    metricLabel: "text-neutral-400",
  },
  loading: {
    text:        "text-sky-600",
    textBright:  "text-sky-900",
    border:      "border-sky-200",
    bg:          "bg-sky-50",
    glow:        "shadow-[0_2px_12px_rgba(14,165,233,0.12)]",
    dot:         "bg-sky-500",
    iconBg:      "bg-sky-100 border-sky-200",
    ping:        "bg-sky-400",
    badgeBg:     "bg-sky-100",
    badgeText:   "text-sky-800",
    badgeBorder: "border-sky-200",
    leftBar:     "bg-sky-500",
    metricValue: "text-sky-900",
    metricLabel: "text-sky-600/70",
  },
};

function useToken(status: NodeStatus, isLight: boolean): ModeTokens {
  return isLight ? LIGHT[status] : DARK[status];
}

// ─── Status icon ──────────────────────────────────────────────────────────────

function StatusIcon({ status, isLight, className }: { status: NodeStatus; isLight: boolean; className?: string }) {
  const tk = useToken(status, isLight);
  const cls = cn("h-3.5 w-3.5", tk.text, className);
  if (status === "ok")      return <CheckCircle2 className={cls} />;
  if (status === "error")   return <XCircle className={cls} />;
  if (status === "warn")    return <AlertTriangle className={cls} />;
  if (status === "loading") return <Activity className={cn(cls, "animate-spin")} />;
  return <Circle className={cls} />;
}

// ─── Pipeline node card ───────────────────────────────────────────────────────

function PipelineNode({
  step, icon: Icon, title, status, metrics, issue, fix, isLight,
}: {
  step: number;
  icon: React.ElementType;
  title: string;
  status: NodeStatus;
  metrics: { label: string; value: string }[];
  issue?: string;
  fix?: string;
  isLight: boolean;
}) {
  const tk = useToken(status, isLight);

  return (
    <motion.div
      initial={{ opacity: 0, x: -16 }}
      animate={{ opacity: 1, x: 0 }}
      transition={{ duration: 0.35, delay: step * 0.07 }}
      className="relative flex gap-4"
    >
      {/* Step icon */}
      <div className="flex flex-col items-center">
        <div className={cn(
          "relative z-10 flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl border-2",
          tk.iconBg,
        )}>
          <Icon className={cn("h-5 w-5", tk.text)} />
          {(status === "ok" || status === "warn" || status === "error") && tk.ping && (
            <span
              className={cn("absolute inset-0 rounded-2xl animate-ping opacity-20", tk.ping)}
              style={{ animationDuration: status === "error" ? "1.4s" : "2.8s" }}
            />
          )}
        </div>
      </div>

      {/* Card */}
      <div className="flex-1 pb-6">
        <div className={cn(
          "relative overflow-hidden rounded-2xl border-2 transition-all duration-300",
          tk.bg, tk.border, tk.glow,
        )}>
          {/* Left accent bar */}
          <div className={cn("absolute inset-y-0 left-0 w-1 rounded-l-2xl", tk.leftBar)} />

          <div className="pl-4 pr-4 pt-3.5 pb-3.5">
            {/* Header */}
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2.5">
                <span className={cn("inline-block h-2.5 w-2.5 shrink-0 rounded-full", tk.dot)} />
                <span className={cn("text-[13px] font-bold tracking-tight", tk.textBright)}>{title}</span>
              </div>
              <div className={cn(
                "flex items-center gap-1.5 rounded-lg border px-2.5 py-1 text-[10px] font-black uppercase tracking-widest",
                tk.badgeBg, tk.badgeText, tk.badgeBorder,
              )}>
                <StatusIcon status={status} isLight={isLight} />
                {status}
              </div>
            </div>

            {/* Metrics */}
            {metrics.length > 0 && (
              <div className={cn(
                "mt-3 grid grid-cols-2 gap-x-6 gap-y-2.5 border-t pt-3 sm:grid-cols-3",
                isLight ? "border-neutral-200" : "border-white/[0.06]",
              )}>
                {metrics.map((m) => (
                  <div key={m.label}>
                    <div className={cn("text-[10px] font-semibold uppercase tracking-[0.13em]", tk.metricLabel)}>
                      {m.label}
                    </div>
                    <div className={cn("mt-0.5 font-mono text-sm font-semibold", tk.metricValue)}>
                      {m.value}
                    </div>
                  </div>
                ))}
              </div>
            )}

            {/* Issue */}
            {issue && (
              <div className={cn(
                "mt-3 flex items-start gap-2 rounded-xl border px-3 py-2.5 text-xs",
                isLight
                  ? "border-rose-300 bg-rose-50 text-rose-700"
                  : "border-rose-500/25 bg-rose-500/[0.12] text-rose-300",
              )}>
                <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                <span>{issue}</span>
              </div>
            )}

            {/* Fix */}
            {fix && (
              <div className={cn(
                "mt-2 flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-[11px]",
                isLight
                  ? "border-emerald-300 bg-emerald-50 text-emerald-700"
                  : "border-emerald-500/20 bg-emerald-500/[0.07] text-emerald-400",
              )}>
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

// ─── Connector ────────────────────────────────────────────────────────────────

function Connector({ fromStatus, isLight }: { fromStatus?: NodeStatus; isLight: boolean }) {
  const colors: Record<NodeStatus, string> = isLight
    ? { ok: "#10b981", warn: "#f59e0b", error: "#f43f5e", idle: "#d1d5db", loading: "#38bdf8" }
    : { ok: "#34d399", warn: "#fbbf24", error: "#f43f5e", idle: "#404040",  loading: "#38bdf8" };
  const color = colors[fromStatus ?? "idle"];
  return (
    <div className="relative -mt-4 mb-0 ml-[21px] flex h-8 w-px flex-col items-center">
      <div
        className="h-full w-0.5 rounded-full"
        style={{ background: `linear-gradient(to bottom, ${color}88, ${color}22)` }}
      />
      <ArrowDown className="absolute -bottom-1 -translate-x-1/2 h-3.5 w-3.5" style={{ left: "50%", color }} />
    </div>
  );
}

// ─── Summary strip ────────────────────────────────────────────────────────────

function SummaryStrip({
  okCount, warnCount, errorCount, idleCount, isLight,
}: { okCount: number; warnCount: number; errorCount: number; idleCount: number; isLight: boolean }) {
  const total = okCount + warnCount + errorCount + idleCount;
  const overall: NodeStatus =
    errorCount > 0 ? "error" : warnCount > 0 ? "warn" : idleCount === total ? "idle" : "ok";
  const tk = useToken(overall, isLight);

  const label = { ok: "ALL SYSTEMS GO", warn: "NEEDS ATTENTION", error: "PIPELINE BLOCKED", idle: "AWAITING DATA", loading: "LOADING" }[overall];

  return (
    <motion.div
      initial={{ opacity: 0, scale: 0.98 }}
      animate={{ opacity: 1, scale: 1 }}
      transition={{ duration: 0.3 }}
      className={cn("mb-8 flex flex-wrap items-center justify-between gap-4 rounded-2xl border-2 px-6 py-4", tk.bg, tk.border, tk.glow)}
    >
      <div className="flex items-center gap-3">
        <span className="relative inline-flex h-4 w-4 shrink-0">
          {tk.ping && (
            <span className={cn("absolute inline-flex h-full w-full animate-ping rounded-full opacity-40", tk.ping)} style={{ animationDuration: "1.6s" }} />
          )}
          <span className={cn("relative inline-flex h-4 w-4 rounded-full", tk.dot)} />
        </span>
        <span className={cn("text-base font-black tracking-[0.16em] uppercase", tk.textBright)}>{label}</span>
      </div>

      <div className="flex flex-wrap items-center gap-3 text-xs font-semibold">
        {[
          { count: okCount,    color: isLight ? "bg-emerald-100 border-emerald-300 text-emerald-800" : "bg-emerald-500/10 border-emerald-500/25 text-emerald-300", icon: <CheckCircle2 className="h-3.5 w-3.5" />, label: "healthy" },
          { count: warnCount,  color: isLight ? "bg-amber-100 border-amber-300 text-amber-800"       : "bg-amber-500/10 border-amber-500/25 text-amber-300",       icon: <AlertTriangle className="h-3.5 w-3.5" />, label: "warning" },
          { count: errorCount, color: isLight ? "bg-rose-100 border-rose-300 text-rose-800"          : "bg-rose-500/10 border-rose-500/25 text-rose-300",          icon: <XCircle className="h-3.5 w-3.5" />, label: "blocked" },
          { count: idleCount,  color: isLight ? "bg-neutral-100 border-neutral-300 text-neutral-700" : "bg-neutral-700/40 border-neutral-600/30 text-neutral-400", icon: <Clock className="h-3.5 w-3.5" />, label: "pending" },
        ].filter(b => b.count > 0).map(b => (
          <span key={b.label} className={cn("flex items-center gap-1.5 rounded-lg border px-3 py-1.5", b.color)}>
            {b.icon} {b.count} {b.label}
          </span>
        ))}
      </div>
    </motion.div>
  );
}

// ─── Main page ────────────────────────────────────────────────────────────────

export function AdminPipelineHealthPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");

  const settings = useQuery({
    queryKey: ["pipeline-health-settings"],
    queryFn: async () => (await api.get("/api/admin/settings/summary")).data?.data as SettingsSummary,
    refetchInterval: 10_000, retry: 1,
  });
  const health = useQuery({
    queryKey: ["pipeline-health-health"],
    queryFn: async () => (await api.get("/api/admin/health")).data?.data as HealthData,
    refetchInterval: 10_000, retry: 1,
  });
  const orders = useQuery({
    queryKey: ["pipeline-health-orders"],
    queryFn: async () => (await api.get("/api/admin/oms/orders?page=0&size=50&sort=createdAt,desc")).data?.data as OrdersResp,
    refetchInterval: 15_000, retry: 1,
  });
  const snapshot = useQuery({
    queryKey: ["pipeline-health-snapshot"],
    queryFn: fetchAdminOpsSnapshotMerged,
    refetchInterval: 10_000, retry: 1,
  });

  const s = settings.data;
  const h = health.data;
  const orderList = orders.data?.content ?? [];
  const snap = snapshot.data;
  const sigDist = asRecord(snap?.signalDistribution);
  const omsSnap = asRecord(snap?.oms);
  const signalsTotal       = Number(sigDist?.signalsPersistedTotal   ?? 0);
  const signalsLast60m     = Number(sigDist?.signalsEmittedLast60m   ?? 0);
  const signalsRoutedToOms = Number(sigDist?.signalsRoutedToOmsTotal ?? 0);

  const wsState = s?.marketFeedState ?? "UNKNOWN";
  const wsOk = wsState === "CONNECTED" || wsState === "OPEN";
  const feedStatus:     NodeStatus = settings.isLoading ? "loading" : wsOk ? "ok" : "error";
  const coverageStatus: NodeStatus = feedStatus === "ok" ? "ok" : feedStatus === "loading" ? "loading" : "warn";
  const strategyStatus: NodeStatus = snapshot.isLoading ? "loading" : signalsLast60m > 0 ? "ok" : feedStatus === "ok" ? "warn" : "idle";
  const dispatchStatus: NodeStatus = signalsTotal > 0 ? "ok" : strategyStatus === "idle" ? "idle" : "warn";

  const recentOrders   = orderList.slice(0, 20);
  const createdOrders  = recentOrders.filter((o) => o.state === "CREATED"  || o.state === "VALIDATED").length;
  const rejectedOrders = recentOrders.filter((o) => o.state === "REJECTED").length;
  const filledOrders   = recentOrders.filter((o) => o.state === "FILLED"   || o.state === "PARTIAL_FILL").length;
  const omsStatus: NodeStatus = orders.isLoading ? "loading"
    : filledOrders > 0 || createdOrders > 0 ? "ok"
    : rejectedOrders > recentOrders.length * 0.8 && recentOrders.length > 0 ? "error"
    : orderList.length === 0 ? "idle" : "warn";
  const riskStatus: NodeStatus = omsStatus === "idle" ? "idle" : omsStatus === "ok" ? "ok" : "warn";
  const execStatus: NodeStatus = filledOrders > 0 ? "ok" : omsStatus === "idle" ? "idle" : omsStatus === "ok" ? "warn" : "idle";
  const ordersLast60s = Number(omsSnap?.ordersLast60s ?? 0);

  const statusList = [feedStatus, coverageStatus, strategyStatus, dispatchStatus, omsStatus, riskStatus, execStatus];
  const okCount    = statusList.filter((x) => x === "ok").length;
  const warnCount  = statusList.filter((x) => x === "warn").length;
  const errorCount = statusList.filter((x) => x === "error").length;
  const idleCount  = statusList.filter((x) => x === "idle" || x === "loading").length;

  const uptime = s?.uptimeHuman ?? (h ? `${Math.floor((h.uptimeSeconds ?? 0) / 3600)}h ${Math.floor(((h.uptimeSeconds ?? 0) % 3600) / 60)}m` : "—");
  const executionModeSummary = formatExecutionModeSummary(s?.strategyExecutionModes, s?.liveTradingArmed);
  const killSwitchEngaged = (s?.killSwitch ?? "").toUpperCase() === "ENGAGED" || Boolean(h?.killSwitch);
  const riskGateValue = riskGateLabel(s?.strategyExecutionModes, s?.liveTradingArmed, killSwitchEngaged);

  const nodeProps = { isLight };

  return (
    <div className="mx-auto max-w-2xl space-y-2 px-1 pb-12">
      {/* Header */}
      <motion.div initial={{ opacity: 0, y: -10 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.28 }}>
        <div className="mb-6 flex items-start justify-between gap-4">
          <div>
            <h1 className={cn("text-2xl font-black tracking-tight", isLight ? "text-neutral-900" : "text-white")}>
              Pipeline Health
            </h1>
            <p className={cn("mt-1 text-xs", isLight ? "text-neutral-500" : "text-neutral-500")}>
              Live signal flow · Zerodha → Strategy → OMS → Execution · auto-refresh 10s
            </p>
          </div>
          <div className={cn(
            "flex items-center gap-2 rounded-xl border px-3.5 py-2.5 text-xs font-semibold",
            isLight
              ? "border-neutral-200 bg-white text-neutral-700 shadow-sm"
              : "border-neutral-700/60 bg-neutral-800/70 text-neutral-300",
          )}>
            <Clock className="h-3.5 w-3.5 text-sky-500" />
            <span>Uptime <span className={isLight ? "text-sky-600 font-bold" : "text-sky-300 font-bold"}>{uptime}</span></span>
          </div>
        </div>
      </motion.div>

      <SummaryStrip okCount={okCount} warnCount={warnCount} errorCount={errorCount} idleCount={idleCount} isLight={isLight} />

      <PipelineNode step={0} icon={Wifi} title="Market Data Feed  ·  Zerodha WebSocket" status={feedStatus} {...nodeProps}
        metrics={[
          { label: "WS State",      value: wsState },
          { label: "Subscriptions", value: String(s?.marketFeedSubscriptions ?? 0) },
          { label: "Ticks / sec",   value: s?.marketFeedTicksPerSec ?? "-" },
          { label: "Last packet",   value: s?.marketFeedLastPacket ? new Date(s.marketFeedLastPacket).toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false, timeZone: "Asia/Kolkata" }) : "—" },
          { label: "Mode",          value: s?.liveTradingArmed ?? "—" },
          { label: "Kill switch",   value: s?.killSwitch ?? "—" },
        ]}
        issue={!wsOk && !settings.isLoading ? `WebSocket ${wsState} — re-authenticate Zerodha or check token` : undefined}
      />
      <Connector fromStatus={feedStatus} isLight={isLight} />

      <PipelineNode step={1} icon={Database} title="Historical Backfill  ·  30-day lookback" status={feedStatus === "ok" ? "ok" : "warn"} {...nodeProps}
        metrics={[
          { label: "Schedule",   value: "08:55 AM IST + startup" },
          { label: "Lookback",   value: "30 days" },
          { label: "Chunk size", value: "55 days" },
          { label: "Rate limit", value: "350 ms" },
        ]}
        fix="Bug fixed — backfill no longer skips stocks with live ticks (commit a48e970)"
      />
      <Connector fromStatus={feedStatus === "ok" ? "ok" : "warn"} isLight={isLight} />

      <PipelineNode step={2} icon={ShieldCheck} title="Readiness Gate  ·  Coverage check per symbol" status={coverageStatus} {...nodeProps}
        metrics={[
          { label: "Mode",      value: "autoRefresh=true" },
          { label: "Lookback",  value: "180 min" },
          { label: "Tolerance", value: "STALE / GAPS" },
        ]}
        fix="Bug fixed — NO_DATA for all symbols resolved (commit 652e943)"
      />
      <Connector fromStatus={coverageStatus} isLight={isLight} />

      <PipelineNode step={3} icon={GitBranch} title="Strategy Evaluation  ·  6 generators per symbol" status={strategyStatus} {...nodeProps}
        metrics={[
          { label: "Generators",       value: "6" },
          { label: "Signals last 60m", value: String(signalsLast60m) },
          { label: "Signals total",    value: String(signalsTotal) },
          { label: "Scan interval",    value: "60 sec" },
          { label: "Strategies",       value: String(s?.strategiesTotal ?? "—") },
          { label: "Execution mode",   value: executionModeSummary },
        ]}
        fix="3 NPEs fixed — EMA slope, null candle prices, system userId (commits 14245d4, 6edc248, 52cb339)"
        issue={strategyStatus === "warn" ? "Market closed or insufficient bars — signals expected after 09:30 IST" : undefined}
      />
      <Connector fromStatus={strategyStatus} isLight={isLight} />

      <PipelineNode step={4} icon={Radio} title="Signal Dispatch  ·  RabbitMQ → OMS_ORDER queue" status={dispatchStatus} {...nodeProps}
        metrics={[
          { label: "Queue",         value: "OMS_ORDER" },
          { label: "Signals total", value: String(signalsTotal) },
          { label: "Routed to OMS", value: String(signalsRoutedToOms) },
        ]}
      />
      <Connector fromStatus={dispatchStatus} isLight={isLight} />

      <PipelineNode step={5} icon={Zap} title="OMS Order Intent  ·  Risk gate + order creation" status={omsStatus} {...nodeProps}
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
      <Connector fromStatus={omsStatus} isLight={isLight} />

      <PipelineNode step={6} icon={ShieldCheck} title="Risk Engine  ·  Pre-execution safety checks" status={riskStatus} {...nodeProps}
        metrics={[
          { label: "Gate",       value: riskGateValue },
          { label: "Kill switch",value: h?.killSwitch ? "ENGAGED" : "OFF" },
          { label: "Live armed", value: h?.liveTradingArmed ? "ARMED" : "DISARMED" },
        ]}
        issue={h?.killSwitch ? "Kill switch is ENGAGED — no orders will execute" : undefined}
      />
      <Connector fromStatus={riskStatus} isLight={isLight} />

      <PipelineNode step={7} icon={TrendingUp} title="Execution  ·  Paper sim / Zerodha live" status={execStatus} {...nodeProps}
        metrics={[
          { label: "Mode",   value: executionModeSummary },
          { label: "Filled", value: String(filledOrders) },
          { label: "Broker", value: "SIM / ZERODHA" },
        ]}
        issue={execStatus === "idle" ? "Not yet reached — waiting for first signal to complete full pipeline" : undefined}
      />

      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ delay: 0.7 }}
        className={cn("pt-6 text-center text-[11px]", isLight ? "text-neutral-400" : "text-neutral-600")}
      >
        Data from /api/admin/settings/summary · /api/admin/health · /api/admin/oms/orders · /api/admin/signals
      </motion.div>
    </div>
  );
}
