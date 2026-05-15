import { useQuery } from "@tanstack/react-query";
import { motion } from "framer-motion";
import {
  Activity,
  Cpu,
  Radio,
  ShieldAlert,
  Siren,
  Users,
  Workflow,
  ZapOff,
} from "lucide-react";
import { api, parseAxiosMessage } from "../api/client";
import { ADMIN_OPS_SNAPSHOT_KEY } from "../lib/adminQueryKeys";
import { fetchAdminOpsSnapshotMerged } from "../lib/fetchAdminOpsSnapshotMerged";
import { BrokerConnectionControlCenter } from "../components/admin/BrokerConnectionControlCenter";
import { PlatformLiveInfrastructurePanel } from "../components/admin/PlatformLiveInfrastructurePanel";
import { SystemReadinessBanner } from "../components/admin/SystemReadinessBanner";
import { computeSystemReadiness, hasActiveBrokerMarketFeed } from "../components/admin/adminReadinessModel";
import { GlassPanel } from "../components/ds/GlassPanel";
import { MetricCard } from "../components/ds/MetricCard";
import { StatusChip } from "../components/ds/StatusChip";
import { WorkspaceTabs, WorkspaceTabPanel } from "../components/ds/WorkspaceTabs";
import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useSessionStore } from "../state/session";
import { useUiThemeStore } from "../state/uiTheme";
import { cn } from "../lib/utils";
import { asRecord, type OpsSnapshot } from "../components/admin/cockpit/opsTypes";

/** Coarse status for admin cards â€” extends as more probes are added. */
function infraPlaneStatus(snapshot: OpsSnapshot | undefined): "online" | "offline" | "degraded" | "unknown" {
  if (!snapshot) return "unknown";
  const sys = asRecord(snapshot.system);
  const redis = asRecord(sys?.redis);
  const db = asRecord(sys?.database);
  const rOk = redis?.status === "CONNECTED";
  const dOk = db?.status === "CONNECTED";
  const halt = snapshot.marketInfra?.globalBrokerHalt === true;
  const fresh = asRecord(snapshot.marketFreshness);
  const marketStale = fresh?.status === "STALE";
  if (!rOk || !dOk) return "offline";
  if (halt || marketStale) return "degraded";
  return "online";
}

export function AdminOverviewPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const showRiskConsole = useSessionStore((s) => s.canAccessKillSwitchOperations());

  const health = useQuery({
    queryKey: ["admin-health"],
    queryFn: async () => {
      const res = await api.get("/api/admin/health");
      return res.data?.data as Record<string, unknown>;
    },
    refetchInterval: 8000,
    retry: 2,
  });

  const operationsSnapshot = useQuery({
    queryKey: ADMIN_OPS_SNAPSHOT_KEY,
    queryFn: fetchAdminOpsSnapshotMerged,
    staleTime: 15_000,
    retry: 2,
  });

  const [tab, setTab] = useState("overview");
  const tabs = useMemo(
    () => [
      { id: "overview", label: "Command" },
      { id: "pipeline", label: "Pipeline telemetry" },
    ],
    [],
  );

  const killOn = Boolean(health.data?.killSwitch);
  const plane = infraPlaneStatus(operationsSnapshot.data);
  const sys = asRecord(operationsSnapshot.data?.system);
  const redisProbe = asRecord(sys?.redis);
  const dbProbe = asRecord(sys?.database);
  const uptimeSec =
    typeof health.data?.uptimeSeconds === "number"
      ? Math.floor(health.data.uptimeSeconds)
      : typeof health.data?.uptimeSeconds === "string"
        ? health.data.uptimeSeconds
        : null;

  const panel = isLight ? ("light" as const) : ("dark" as const);

  const readiness = computeSystemReadiness(operationsSnapshot.data);
  const brokerLive = hasActiveBrokerMarketFeed(operationsSnapshot.data);

  let overviewChipStatus: "online" | "degraded" | "offline" = "online";
  let overviewChipLabel = "Platform ready";
  if (!brokerLive) {
    overviewChipStatus = "offline";
    overviewChipLabel = "System not ready";
  } else if (killOn && showRiskConsole) {
    overviewChipStatus = "offline";
    overviewChipLabel = "Kill armed";
  } else if (readiness.level !== "READY") {
    overviewChipStatus = readiness.level === "OFFLINE" ? "offline" : "degraded";
    overviewChipLabel =
      readiness.level === "BACKFILLING"
        ? "Replay saturation"
        : readiness.level === "LIMITED"
          ? "Limited readiness"
          : readiness.headline.length > 48
            ? `${readiness.headline.slice(0, 45)}â€¦`
            : readiness.headline;
  } else if (!showRiskConsole) {
    overviewChipLabel = "Staff console";
  }

  return (
    <div className="space-y-8">
      <PlatformLiveInfrastructurePanel snapshot={operationsSnapshot.data} />
      <BrokerConnectionControlCenter snapshot={operationsSnapshot.data} />
      <SystemReadinessBanner snapshot={operationsSnapshot.data} />

      <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }}>
        <div className="flex flex-wrap items-start justify-between gap-6">
          <div>
            <StatusChip status={overviewChipStatus} label={overviewChipLabel} />
            <h1
              className={cn(
                "mt-6 text-[32px] font-semibold tracking-tight",
                isLight
                  ? "text-neutral-900"
                  : "bg-gradient-to-br from-white via-white to-neutral-400 bg-clip-text text-transparent",
              )}
            >
              {showRiskConsole ? "Operations console" : "Admin console"}
            </h1>
            <p
              className={cn(
                "mt-3 max-w-3xl text-sm leading-relaxed",
                isLight ? "text-neutral-600" : "text-neutral-400",
              )}
            >
              {showRiskConsole
                ? "Operate readiness, backfill, replay, and execution from one console."
                : "Manage traders, strategies, and OMS."}
            </p>
          </div>

          <GlassPanel
            variant={panel}
            className={cn(
              "relative w-full max-w-sm overflow-hidden p-5",
              isLight
                ? "border-neutral-200 shadow-[0_4px_24px_-8px_rgba(15,23,42,0.08)]"
                : "border-white/[0.08] shadow-[0_0_0_1px_rgba(59,130,246,0.08),0_24px_80px_-24px_rgba(0,0,0,0.6)]",
            )}
          >
            <div className="pointer-events-none absolute -right-8 -top-8 h-28 w-28 rounded-full bg-blue-500/10 blur-2xl" />
            <div
              className={cn(
                "flex items-center gap-2 text-[11px] font-bold uppercase tracking-widest",
                isLight ? "text-neutral-600" : "text-neutral-400",
              )}
            >
              <Siren className={cn("h-4 w-4", isLight ? "text-amber-600" : "text-amber-300/90")} /> Quick lanes
            </div>
            <div className="mt-4 flex flex-wrap gap-2">
              <Link
                to="/admin/users"
                className={cn(
                  "rounded-lg px-3 py-1.5 text-[11px] font-bold uppercase tracking-wide shadow-lg transition",
                  isLight
                    ? "bg-neutral-900 text-white shadow-neutral-900/15 hover:bg-neutral-800"
                    : "bg-white text-neutral-950 shadow-black/25 hover:bg-neutral-100",
                )}
              >
                Traders
              </Link>
              <Link
                to="/admin/oms"
                className={cn(
                  "rounded-lg border px-3 py-1.5 text-[11px] font-bold uppercase tracking-wide transition",
                  isLight
                    ? "border-neutral-200 bg-neutral-50 text-neutral-800 hover:border-blue-300 hover:bg-blue-50"
                    : "border-white/[0.1] bg-neutral-950/50 text-neutral-100 backdrop-blur-sm hover:border-blue-500/30 hover:bg-blue-500/10",
                )}
              >
                OMS
              </Link>
              {showRiskConsole ? (
                <Link
                  to="/admin/ops"
                  className={cn(
                    "rounded-lg border px-3 py-1.5 text-[11px] font-black uppercase tracking-wide transition",
                    isLight
                      ? "border-rose-300 bg-rose-50 text-rose-800 hover:bg-rose-100"
                      : "border-rose-500/45 bg-rose-500/10 text-rose-50 shadow-[0_0_20px_-4px_rgba(244,63,94,0.35)] hover:bg-rose-500/20",
                  )}
                >
                  Emergency rail
                </Link>
              ) : null}
            </div>
          </GlassPanel>
        </div>
      </motion.div>

      {health.isError || operationsSnapshot.isError ? (
        <div
          className={cn(
            "flex flex-col gap-3 rounded-xl border px-4 py-3 text-sm sm:flex-row sm:items-center sm:justify-between",
            isLight ? "border-amber-200 bg-amber-50 text-amber-950" : "border-amber-500/30 bg-amber-950/20 text-amber-100",
          )}
        >
          <span>
            {health.isError ? <>Health: {parseAxiosMessage(health.error)}. </> : null}
            {operationsSnapshot.isError ? (
              <>Operations snapshot: {parseAxiosMessage(operationsSnapshot.error)}.</>
            ) : null}
          </span>
          <div className="flex shrink-0 flex-wrap gap-2">
            {health.isError ? (
              <button
                type="button"
                onClick={() => void health.refetch()}
                className={cn(
                  "rounded-lg border px-3 py-1.5 text-xs font-semibold",
                  isLight ? "border-amber-300 bg-white hover:bg-amber-100" : "border-amber-700 text-amber-50 hover:bg-amber-950/50",
                )}
              >
                Retry health
              </button>
            ) : null}
            {operationsSnapshot.isError ? (
              <button
                type="button"
                onClick={() => void operationsSnapshot.refetch()}
                className={cn(
                  "rounded-lg border px-3 py-1.5 text-xs font-semibold",
                  isLight ? "border-amber-300 bg-white hover:bg-amber-100" : "border-amber-700 text-amber-50 hover:bg-amber-950/50",
                )}
              >
                Retry snapshot
              </button>
            ) : null}
          </div>
        </div>
      ) : null}

      <WorkspaceTabs tabs={tabs} active={tab} onChange={setTab} variant={isLight ? "light" : "dark"} />

      <WorkspaceTabPanel id="overview" active={tab}>
        <div className="grid gap-4 md:grid-cols-3">
          {showRiskConsole ? (
            <MetricCard
              panelVariant={panel}
              highlight={killOn}
              label="Kill switch"
              value={health.isLoading ? "â€¦" : killOn ? "ARMED" : "CLEAR"}
              trend={killOn ? "down" : "up"}
            />
          ) : (
            <MetricCard
              panelVariant={panel}
              label="Console mode"
              trend="flat"
              value="Staff"
              sublabel="Risk & incidents hidden for trader profiles"
            />
          )}
          <MetricCard
            panelVariant={panel}
            label="Service vitality"
            trend="flat"
            sublabel={`Uptime Â· ${uptimeSec != null ? `${uptimeSec}s` : health.isLoading ? "â€¦" : "â€”"}`}
            value={
              <Cpu
                className={cn("h-8 w-8", isLight ? "text-blue-600/90" : "text-blue-400/40")}
                aria-hidden
              />
            }
          />
          <MetricCard
            panelVariant={panel}
            highlight={plane === "offline" || plane === "degraded"}
            label="Infra plane"
            trend={plane === "online" ? "up" : plane === "offline" ? "down" : "flat"}
            value={
              <Radio
                className={cn(
                  "h-8 w-8",
                  plane === "online"
                    ? isLight
                      ? "text-emerald-600/85"
                      : "text-emerald-400/35"
                    : plane === "degraded"
                      ? isLight
                        ? "text-amber-600/90"
                        : "text-amber-400/50"
                      : isLight
                        ? "text-neutral-400"
                        : "text-neutral-600",
                )}
                aria-hidden
              />
            }
            sublabel={
              operationsSnapshot.isLoading
                ? "Loading snapshotâ€¦"
                : operationsSnapshot.isError
                  ? "Snapshot failed â€” see banner"
                  : [
                      redisProbe?.status === "CONNECTED" ? `Redis ${redisProbe.pingMs ?? "â€”"}ms` : `Redis ${String(redisProbe?.status ?? "â€”")}`,
                      dbProbe?.status === "CONNECTED" ? `DB ${dbProbe.pingMs ?? "â€”"}ms` : `DB ${String(dbProbe?.status ?? "â€”")}`,
                      operationsSnapshot.data?.marketInfra?.globalBrokerHalt === true ? "Broker halt flag" : null,
                    ]
                      .filter(Boolean)
                      .join(" Â· ") || "Probes pending"
            }
          />

          {showRiskConsole && operationsSnapshot.data ? (
            <div className="md:col-span-3 space-y-2">
              <div className={cn("text-[11px] font-bold uppercase tracking-widest", isLight ? "text-neutral-600" : "text-neutral-500")}>
                Operations cockpit
              </div>
              <Link
                to="/admin/ops"
                className={cn(
                  "flex flex-col gap-2 rounded-2xl border p-4 transition-colors",
                  isLight
                    ? "border-neutral-200 bg-white hover:border-blue-300 hover:bg-blue-50/50"
                    : "border-white/10 bg-white/[0.03] hover:border-blue-500/30 hover:bg-blue-500/5",
                )}
              >
                <div className={cn("text-sm font-semibold", isLight ? "text-neutral-900" : "text-white")}>
                  Open operations cockpit
                </div>
                <div className={cn("text-xs", isLight ? "text-neutral-600" : "text-neutral-400")}>
                  Live status for market feed, replay, OMS, and incidents.
                </div>
                <div className={cn("text-[11px] font-mono", isLight ? "text-neutral-500" : "text-neutral-500")}>
                  {operationsSnapshot.data.collectedAt
                    ? `Last snapshot: ${new Date(operationsSnapshot.data.collectedAt).toLocaleString()}`
                    : "Snapshot pending"}
                </div>
              </Link>
            </div>
          ) : null}

          <GlassPanel variant={panel} className="p-6 md:col-span-3">
            <div
              className={cn(
                "flex flex-wrap items-center justify-between gap-4 border-b pb-4",
                isLight ? "border-neutral-200" : "border-white/5",
              )}
            >
              <div className="flex items-center gap-3">
                <Users className={cn("h-10 w-10", isLight ? "text-neutral-700" : "text-neutral-700")} />
                <div>
                  <div className={cn("text-[13px] font-semibold", isLight ? "text-neutral-900" : "text-white")}>
                    Trader onboarding desk
                  </div>
                  <div className={cn("mt-1 text-xs", isLight ? "text-neutral-600" : "text-neutral-500")}>
                    Approve LIVE trading.
                  </div>
                </div>
              </div>
              <Workflow className={cn("h-10 w-10", isLight ? "text-blue-600/80" : "text-blue-900/70")} />
            </div>
            <div className={`mt-5 grid gap-4 ${showRiskConsole ? "md:grid-cols-4" : "md:grid-cols-3"}`}>
              {(
                showRiskConsole
                  ? [
                      { label: "Users", to: "/admin/users" as const },
                      { label: "Strategies", to: "/admin/strategies" as const },
                      { label: "OMS", to: "/admin/oms" as const },
                      { label: "Incidents", to: "/admin/ops" as const },
                    ]
                  : [
                      { label: "Users", to: "/admin/users" as const },
                      { label: "Strategies", to: "/admin/strategies" as const },
                      { label: "OMS", to: "/admin/oms" as const },
                    ]
              ).map(({ label, to }) => (
                <Link key={label} to={to}>
                  <motion.div
                    whileHover={{ y: -3 }}
                    className={cn(
                      "rounded-xl border p-4 transition",
                      isLight
                        ? "border-neutral-200 bg-white shadow-sm hover:border-blue-300 hover:shadow-md"
                        : "border-white/[0.06] bg-gradient-to-b from-neutral-900/80 to-neutral-950/90 shadow-[inset_0_1px_0_0_rgba(255,255,255,0.04)] hover:border-blue-500/25 hover:shadow-[0_12px_40px_-12px_rgba(59,130,246,0.25)]",
                    )}
                  >
                    <div
                      className={cn(
                        "text-[11px] font-bold uppercase tracking-widest",
                        isLight ? "text-neutral-500" : "text-neutral-500",
                      )}
                    >
                      {label}
                    </div>
                    <div className={cn("mt-5", isLight ? "text-blue-600/90" : "text-blue-400/50")}>
                      <Activity className="h-6 w-6" aria-hidden />
                    </div>
                  </motion.div>
                </Link>
              ))}
            </div>
          </GlassPanel>

          {showRiskConsole ? (
            <GlassPanel
              variant={panel}
              className={cn(
                "border p-5 md:col-span-3",
                isLight
                  ? "border-rose-200 bg-rose-50/80"
                  : "border-rose-500/20 bg-gradient-to-br from-rose-950/40 to-neutral-950/80",
              )}
            >
              <div className="flex items-start gap-3">
                <ZapOff className={cn("mt-1 h-5 w-5 shrink-0", isLight ? "text-rose-700" : "text-rose-200")} />
                <div className={cn("text-sm leading-relaxed", isLight ? "text-rose-950/90" : "text-neutral-400")}>
                  <span className={cn("font-semibold", isLight ? "text-rose-950" : "text-white")}>
                    Operational doctrine Â·{" "}
                  </span>
                  LIVE routing guarded by onboarding, broker session, and admin approvals.
                </div>
              </div>
            </GlassPanel>
          ) : (
            <GlassPanel
              variant={panel}
              className={cn(
                "border p-5 md:col-span-3",
                isLight
                  ? "border-blue-200 bg-blue-50/70"
                  : "border-blue-500/15 bg-gradient-to-br from-blue-950/20 to-neutral-950/90",
              )}
            >
              <div className={cn("text-sm leading-relaxed", isLight ? "text-blue-950/85" : "text-neutral-400")}>
                <span className={cn("font-semibold", isLight ? "text-blue-950" : "text-white")}>
                  Trader-safe admin view Â·{" "}
                </span>
                Emergency controls are limited to operations accounts.
              </div>
            </GlassPanel>
          )}
        </div>
      </WorkspaceTabPanel>

      <WorkspaceTabPanel id="pipeline" active={tab}>
        <div className="grid gap-4 lg:grid-cols-2">
          <GlassPanel variant={panel} className="p-5">
            <div className="flex items-center gap-3">
              <ShieldAlert className={cn("h-5 w-5", isLight ? "text-emerald-700" : "text-emerald-300")} />
              <div className={cn("text-[13px] font-semibold", isLight ? "text-neutral-900" : "text-white")}>
                Ingress / egress topology
              </div>
            </div>
            {health.data?.queues ? (
              <pre
                className={cn(
                  "mt-4 max-h-[420px] overflow-auto rounded-xl border p-4 font-mono text-[11px]",
                  isLight
                    ? "border-neutral-200 bg-neutral-100 text-neutral-800"
                    : "border-neutral-900 bg-neutral-950 text-neutral-400",
                )}
              >
                {JSON.stringify(health.data.queues, null, 2)}
              </pre>
            ) : (
              <p className={cn("mt-4 text-xs", isLight ? "text-neutral-600" : "text-neutral-600")}>
                {health.isLoading
                  ? "Loading queue metadataâ€¦"
                  : health.isError
                    ? "Health endpoint failed â€” use Retry above."
                    : "No queue map in response yet."}
              </p>
            )}
          </GlassPanel>
          <GlassPanel variant={panel} className="p-5">
            <div className={cn("text-[13px] font-semibold", isLight ? "text-neutral-900" : "text-white")}>
              Operations snapshot
            </div>
            <p className={cn("mt-1 text-xs", isLight ? "text-neutral-600" : "text-neutral-500")}>
              Live aggregate from <code className="font-mono text-[10px]">GET /api/admin/operations/snapshot</code> â€” market
              DB freshness, replay job counts, OMS rollups, Redis / DB / Rabbit probes.
            </p>
            {operationsSnapshot.data ? (
              <>
                <pre
                  className={cn(
                    "mt-4 max-h-[420px] overflow-auto rounded-xl border p-4 font-mono text-[11px]",
                    isLight
                      ? "border-neutral-200 bg-neutral-100 text-neutral-800"
                      : "border-neutral-900 bg-neutral-950 text-neutral-400",
                  )}
                >
                  {JSON.stringify(operationsSnapshot.data, null, 2)}
                </pre>
                {Array.isArray(operationsSnapshot.data.incidents) && operationsSnapshot.data.incidents.length > 0 ? (
                  <div className="mt-4">
                    <div className={cn("text-[11px] font-semibold uppercase tracking-wide", isLight ? "text-rose-800" : "text-rose-200")}>
                      Active incidents ({operationsSnapshot.data.incidents.length})
                    </div>
                    <pre
                      className={cn(
                        "mt-2 max-h-[200px] overflow-auto rounded-xl border p-3 font-mono text-[11px]",
                        isLight ? "border-rose-200 bg-rose-50 text-rose-950" : "border-rose-900/40 bg-rose-950/30 text-rose-100",
                      )}
                    >
                      {JSON.stringify(operationsSnapshot.data.incidents, null, 2)}
                    </pre>
                  </div>
                ) : null}
              </>
            ) : (
              <p className={cn("mt-4 text-xs", isLight ? "text-neutral-600" : "text-neutral-600")}>
                {operationsSnapshot.isLoading
                  ? "Loading operations snapshotâ€¦"
                  : operationsSnapshot.isError
                    ? "Snapshot failed â€” use Retry above."
                    : "No snapshot yet."}
              </p>
            )}
          </GlassPanel>
        </div>
      </WorkspaceTabPanel>
    </div>
  );
}

