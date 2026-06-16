import { useQuery } from "@tanstack/react-query";
import { motion } from "framer-motion";
import { Link } from "react-router-dom";
import {
  Activity,
  AlertTriangle,
  ArrowRight,
  Building2,
  Cpu,
  Radio,
  Shield,
  ShieldCheck,
  Sparkles,
  TrendingUp,
  Zap,
} from "lucide-react";
import { api, parseAxiosMessage } from "../../api/client";
import { ADMIN_OPS_SNAPSHOT_KEY } from "../../lib/adminQueryKeys";
import { fetchAdminOpsSnapshotMerged } from "../../lib/fetchAdminOpsSnapshotMerged";
import { computeSystemReadiness, hasActiveBrokerMarketFeed } from "../../components/admin/adminReadinessModel";
import { asArray, asRecord, fmtInt, fmtNum, type OpsSnapshot } from "../../components/admin/cockpit/opsTypes";
import { IncidentFeed } from "../../components/admin/cockpit/AdminCockpitPanels";
import { MetricCard } from "../../components/ds/MetricCard";
import { StatusChip } from "../../components/ds/StatusChip";
import { useUiThemeStore } from "../../state/uiTheme";
import { useSessionStore } from "../../state/session";
import { cn } from "../../lib/utils";
import {
  AdminHeatCell,
  AdminPageShell,
  AdminPanel,
  AdminPulseDot,
  AdminSection,
  adminStagger,
} from "../../components/admin/institutional/AdminDesignSystem";
import { SafetyDiagnosticsLaunchLink } from "../../components/admin/institutional/SafetyDiagnosticsLaunchLink";
import { LivePlatformTopology } from "../../components/admin/institutional/experience/LivePlatformTopology";
import { OperationalInsightsStrip } from "../../components/admin/institutional/experience/RiskTerminalPanels";
import { extractOmsLatencyMs, buildOperationalInsights } from "../../lib/adminOperationalIntelligence";
import { fetchRiskDashboard } from "../../api/riskDashboard";

function extractLatencyMs(snapshot: OpsSnapshot | undefined): string {
  const v = extractOmsLatencyMs(snapshot);
  return v != null ? `${fmtNum(v, 0)}ms` : "—";
}

function extractSignalQuality(snapshot: OpsSnapshot | undefined): string {
  const dist = asRecord(snapshot?.signalDistribution);
  const rate = dist?.acceptRate ?? dist?.qualityScore ?? dist?.avgConfidence;
  if (rate == null) return "—";
  const n = typeof rate === "number" ? rate : Number(rate);
  if (Number.isNaN(n)) return "—";
  return n <= 1 ? `${Math.round(n * 100)}%` : `${Math.round(n)}%`;
}

function extractExposure(snapshot: OpsSnapshot | undefined): string {
  const oms = asRecord(snapshot?.oms);
  const open = oms?.openOrders ?? oms?.openOrderCount;
  const pos = oms?.openPositions ?? oms?.positionCount;
  if (open != null || pos != null) return `${fmtInt(open ?? 0)} ord · ${fmtInt(pos ?? 0)} pos`;
  return "—";
}

function regimeLabel(snapshot: OpsSnapshot | undefined): string {
  const fresh = asRecord(snapshot?.marketFreshness);
  return String(fresh?.regime ?? fresh?.status ?? snapshot?.marketInfra?.sessionState ?? "—");
}

export function AdminCommandCenterPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const showRiskConsole = useSessionStore((s) => s.canAccessKillSwitchOperations());
  const panel = isLight ? ("light" as const) : ("dark" as const);

  const health = useQuery({
    queryKey: ["admin-health"],
    queryFn: async () => (await api.get("/api/admin/health")).data?.data as Record<string, unknown>,
    refetchInterval: 8000,
    retry: 2,
  });

  const snapshotQ = useQuery({
    queryKey: ADMIN_OPS_SNAPSHOT_KEY,
    queryFn: fetchAdminOpsSnapshotMerged,
    refetchInterval: 8000,
    staleTime: 2000,
    retry: 2,
  });

  const riskQ = useQuery({
    queryKey: ["admin-risk-dashboard"],
    queryFn: fetchRiskDashboard,
    refetchInterval: 30_000,
    staleTime: 15_000,
  });

  const snapshot = snapshotQ.data;
  const insights = buildOperationalInsights(snapshot, riskQ.data, []);
  const readiness = computeSystemReadiness(snapshot);
  const brokerLive = hasActiveBrokerMarketFeed(snapshot);
  const killOn = Boolean(health.data?.killSwitch);
  const sys = asRecord(snapshot?.system);
  const redis = asRecord(sys?.redis);
  const db = asRecord(sys?.database);

  const alertTone =
    killOn || readiness.level === "OFFLINE"
      ? "bad"
      : readiness.level !== "READY" || !brokerLive
        ? "warn"
        : "ok";

  const incidents = asArray(snapshot?.incidents) ?? [];

  return (
    <AdminPageShell
      isLight={isLight}
      eyebrow="Institutional operations war room"
      title="Command Center"
      subtitle="Live platform pulse — broker truth, execution health, signal quality, and risk posture in one operational surface."
      alert={
        alertTone !== "ok" ? (
          <div
            className={cn(
              "flex flex-wrap items-center justify-between gap-3 rounded-xl border px-4 py-3",
              alertTone === "bad"
                ? isLight
                  ? "border-rose-300 bg-rose-50 text-rose-950"
                  : "border-rose-500/40 bg-rose-500/10 text-rose-100"
                : isLight
                  ? "border-amber-300 bg-amber-50 text-amber-950"
                  : "border-amber-500/35 bg-amber-500/10 text-amber-100",
            )}
          >
            <div className="flex items-start gap-3">
              <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0" />
              <div>
                <p className="text-sm font-semibold">{readiness.headline}</p>
                <p className="mt-0.5 text-xs opacity-90">{readiness.subline}</p>
              </div>
            </div>
            <div className="flex flex-wrap gap-2">
              {showRiskConsole ? (
                <Link
                  to="/admin/safety-diagnostics"
                  className={cn(
                    "inline-flex items-center gap-1 rounded-lg px-3 py-1.5 text-xs font-bold uppercase tracking-wide",
                    isLight ? "bg-rose-800 text-white hover:bg-rose-900" : "bg-rose-600 text-white hover:bg-rose-500",
                  )}
                >
                  Safety rail <ArrowRight className="h-3.5 w-3.5" />
                </Link>
              ) : null}
              <Link
                to="/admin/pipeline-health"
                className={cn(
                  "inline-flex items-center gap-1 rounded-lg border px-3 py-1.5 text-xs font-semibold",
                  isLight ? "border-amber-400 bg-white hover:bg-amber-100" : "border-amber-600/50 hover:bg-amber-500/15",
                )}
              >
                Pipeline health
              </Link>
            </div>
          </div>
        ) : null
      }
      actions={
        <div className="flex flex-wrap items-center gap-2">
          <StatusChip
            status={readiness.level === "READY" ? "online" : readiness.level === "OFFLINE" ? "offline" : "degraded"}
            label={readiness.level}
          />
          <AdminPulseDot live={snapshotQ.isFetching} tone={alertTone === "ok" ? "ok" : alertTone === "warn" ? "warn" : "bad"} />
        </div>
      }
    >
      <motion.div variants={adminStagger} initial="hidden" animate="show" className="space-y-8">
        <SafetyDiagnosticsLaunchLink variant="hero" isLight={isLight} killActive={killOn} />
        <OperationalInsightsStrip insights={insights} isLight={isLight} />
        <LivePlatformTopology snapshot={snapshot} isLight={isLight} />

        <section className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          <MetricCard
            panelVariant={panel}
            label="Market regime"
            value={regimeLabel(snapshot)}
            sublabel={brokerLive ? "Broker tape active" : "Tape offline"}
            trend={brokerLive ? "up" : "down"}
            highlight={!brokerLive}
          />
          <MetricCard
            panelVariant={panel}
            label="Execution latency"
            value={extractLatencyMs(snapshot)}
            sublabel="OMS average"
            trend="flat"
          />
          <MetricCard
            panelVariant={panel}
            label="Signal quality"
            value={extractSignalQuality(snapshot)}
            sublabel="Distribution health"
            trend="flat"
          />
          <MetricCard
            panelVariant={panel}
            label="Live exposure"
            value={extractExposure(snapshot)}
            sublabel="Orders & positions"
            trend="flat"
          />
        </section>

        <div className="grid gap-6 xl:grid-cols-12">
          <div className="space-y-6 xl:col-span-8">
            <AdminSection
              isLight={isLight}
              title="Operational topology"
              subtitle="Infrastructure planes — identify failure in under five seconds"
            >
              <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                <TopologyTile
                  isLight={isLight}
                  icon={Radio}
                  label="Market feed"
                  status={brokerLive ? "LIVE" : "OFFLINE"}
                  tone={brokerLive ? "ok" : "bad"}
                  to="/admin/market"
                />
                <TopologyTile
                  isLight={isLight}
                  icon={Building2}
                  label="Broker infra"
                  status={String(asRecord(snapshot?.brokerSessions)?.aggregateStatus ?? "CHECK")}
                  tone={brokerLive ? "ok" : "warn"}
                  to="/admin/broker-infrastructure"
                />
                <TopologyTile
                  isLight={isLight}
                  icon={Zap}
                  label="Signal pipeline"
                  status={String(asRecord(snapshot?.operationalLifecycle)?.signals ?? "—")}
                  tone="ok"
                  to="/admin/signals"
                />
                <TopologyTile
                  isLight={isLight}
                  icon={Cpu}
                  label="OMS plane"
                  status={String(asRecord(snapshot?.oms)?.health ?? asRecord(snapshot?.oms)?.status ?? "—")}
                  tone="ok"
                  to="/admin/oms"
                />
              </div>
            </AdminSection>

            <AdminSection isLight={isLight} title="Risk heatmap" subtitle="Concentration & system stress indicators">
              <div className="grid grid-cols-3 gap-2 sm:grid-cols-6">
                <AdminHeatCell isLight={isLight} label="Redis" value={String(redis?.status ?? "—")} intensity={redis?.status === "CONNECTED" ? 0.2 : 0.9} />
                <AdminHeatCell isLight={isLight} label="Database" value={String(db?.status ?? "—")} intensity={db?.status === "CONNECTED" ? 0.2 : 0.9} />
                <AdminHeatCell isLight={isLight} label="Queue" value={fmtInt(asRecord(snapshot?.system)?.queueDepth)} intensity={0.45} />
                <AdminHeatCell isLight={isLight} label="Replay" value={String(asRecord(snapshot?.replayInfra)?.status ?? "—")} intensity={0.35} />
                <AdminHeatCell isLight={isLight} label="Kill" value={killOn ? "ARMED" : "OFF"} intensity={killOn ? 1 : 0.15} />
                <AdminHeatCell isLight={isLight} label="Incidents" value={String(incidents.length)} intensity={Math.min(1, incidents.length / 5)} />
              </div>
            </AdminSection>

            <AdminPanel isLight={isLight} title="Capital & strategy posture" subtitle="Quick lanes to control surfaces">
              <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                <QuickLane isLight={isLight} to="/admin/safety-diagnostics" icon={Sparkles} label="Safety & diagnostics" />
                <QuickLane isLight={isLight} to="/admin/risk-dashboard" icon={ShieldCheck} label="Risk terminal" />
                <QuickLane isLight={isLight} to="/admin/capital" icon={TrendingUp} label="Capital allocation" />
                <QuickLane isLight={isLight} to="/admin/strategies" icon={Activity} label="Strategy control" />
                <QuickLane isLight={isLight} to="/admin/users" icon={Building2} label="Traders" />
              </div>
            </AdminPanel>
          </div>

          <div className="space-y-6 xl:col-span-4">
            <AdminPanel isLight={isLight} title="System alerts" subtitle={`${incidents.length} active signal(s)`} accent={incidents.length > 0}>
              <IncidentFeed snapshot={snapshot} />
            </AdminPanel>

            <AdminPanel isLight={isLight} title="API & websocket health">
              <dl className="space-y-3 text-xs">
                <HealthRow isLight={isLight} label="Platform uptime" value={formatUptime(health.data?.uptimeSeconds)} />
                <HealthRow isLight={isLight} label="Snapshot age" value={formatSnapshotAge(snapshot?.collectedAt)} />
                <HealthRow isLight={isLight} label="Live trading" value={health.data?.liveTradingArmed ? "ARMED" : "DISARMED"} />
                <HealthRow isLight={isLight} label="Global halt" value={snapshot?.marketInfra?.globalBrokerHalt ? "YES" : "NO"} />
              </dl>
            </AdminPanel>
          </div>
        </div>

        {(snapshotQ.isError || health.isError) && (
          <p className={cn("text-sm", isLight ? "text-amber-800" : "text-amber-200")}>
            {snapshotQ.isError ? parseAxiosMessage(snapshotQ.error) : parseAxiosMessage(health.error)}
          </p>
        )}
      </motion.div>
    </AdminPageShell>
  );
}

function TopologyTile({
  isLight,
  icon: Icon,
  label,
  status,
  tone,
  to,
}: {
  isLight: boolean;
  icon: typeof Radio;
  label: string;
  status: string;
  tone: "ok" | "warn" | "bad";
  to: string;
}) {
  const ring =
    tone === "ok"
      ? isLight
        ? "border-emerald-200 bg-emerald-50/50"
        : "border-emerald-500/30 bg-emerald-500/10"
      : tone === "warn"
        ? isLight
          ? "border-amber-200 bg-amber-50/50"
          : "border-amber-500/30 bg-amber-500/10"
        : isLight
          ? "border-rose-200 bg-rose-50/50"
          : "border-rose-500/30 bg-rose-500/10";

  return (
    <Link
      to={to}
      className={cn(
        "group rounded-xl border p-4 transition hover:-translate-y-0.5 hover:shadow-lg",
        ring,
        isLight ? "hover:border-blue-300" : "hover:border-blue-500/40",
      )}
    >
      <div className="flex items-center justify-between gap-2">
        <Icon className={cn("h-4 w-4", isLight ? "text-neutral-700" : "text-neutral-300")} />
        <ArrowRight className="h-3.5 w-3.5 opacity-0 transition group-hover:opacity-100" />
      </div>
      <p className={cn("mt-3 text-[11px] font-semibold uppercase tracking-wide", isLight ? "text-neutral-600" : "text-neutral-400")}>
        {label}
      </p>
      <p className={cn("mt-1 font-mono text-sm font-bold", isLight ? "text-neutral-900" : "text-neutral-100")}>
        {status.replace(/_/g, " ")}
      </p>
    </Link>
  );
}

function QuickLane({
  isLight,
  to,
  icon: Icon,
  label,
}: {
  isLight: boolean;
  to: string;
  icon: typeof Shield;
  label: string;
}) {
  return (
    <Link
      to={to}
      className={cn(
        "flex items-center gap-2 rounded-xl border px-3 py-3 text-sm font-medium transition hover:-translate-y-0.5",
        isLight
          ? "border-neutral-200 bg-neutral-50 hover:border-blue-300 hover:bg-blue-50"
          : "border-neutral-800 bg-neutral-900/50 hover:border-blue-500/40 hover:bg-blue-500/10",
      )}
    >
      <Icon className="h-4 w-4 text-blue-500" />
      {label}
    </Link>
  );
}

function HealthRow({ isLight, label, value }: { isLight: boolean; label: string; value: string }) {
  return (
    <div className="flex items-center justify-between gap-2">
      <dt className={isLight ? "text-neutral-500" : "text-neutral-500"}>{label}</dt>
      <dd className={cn("font-mono font-semibold", isLight ? "text-neutral-900" : "text-neutral-100")}>{value}</dd>
    </div>
  );
}

function formatUptime(v: unknown): string {
  const sec = typeof v === "number" ? v : typeof v === "string" ? Number(v) : NaN;
  if (!Number.isFinite(sec)) return "—";
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  return `${h}h ${m}m`;
}

function formatSnapshotAge(at: string | undefined): string {
  if (!at) return "—";
  const ms = Date.now() - new Date(at).getTime();
  if (ms < 2000) return "live";
  if (ms < 60_000) return `${Math.round(ms / 1000)}s ago`;
  return `${Math.round(ms / 60_000)}m ago`;
}
