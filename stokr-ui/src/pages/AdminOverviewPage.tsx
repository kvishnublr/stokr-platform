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
import { GlassPanel } from "../components/ds/GlassPanel";
import { MetricCard } from "../components/ds/MetricCard";
import { StatusChip } from "../components/ds/StatusChip";
import { WorkspaceTabs, WorkspaceTabPanel } from "../components/ds/WorkspaceTabs";
import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useSessionStore } from "../state/session";
import { useUiThemeStore } from "../state/uiTheme";
import { cn } from "../lib/utils";

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

  const [tab, setTab] = useState("overview");
  const tabs = useMemo(
    () => [
      { id: "overview", label: "Command" },
      { id: "pipeline", label: "Pipeline telemetry" },
    ],
    [],
  );

  const killOn = Boolean(health.data?.killSwitch);
  const uptimeSec =
    typeof health.data?.uptimeSeconds === "number"
      ? Math.floor(health.data.uptimeSeconds)
      : typeof health.data?.uptimeSeconds === "string"
        ? health.data.uptimeSeconds
        : null;

  const panel = isLight ? ("light" as const) : ("dark" as const);

  return (
    <div className="space-y-10">
      <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }}>
        <div className="flex flex-wrap items-start justify-between gap-6">
          <div>
            {showRiskConsole ? (
              <StatusChip status={killOn ? "offline" : "online"} label={killOn ? "Kill armed" : "Markets tolerant"} />
            ) : (
              <StatusChip status="online" label="Staff console" />
            )}
            <h1
              className={cn(
                "mt-6 text-[32px] font-semibold tracking-tight",
                isLight
                  ? "text-neutral-900"
                  : "bg-gradient-to-br from-white via-white to-neutral-400 bg-clip-text text-transparent",
              )}
            >
              {showRiskConsole ? "Operations constellation" : "Admin command center"}
            </h1>
            <p
              className={cn(
                "mt-3 max-w-3xl text-sm leading-relaxed",
                isLight ? "text-neutral-600" : "text-neutral-400",
              )}
            >
              {showRiskConsole
                ? "Replay validation, deterministic execution bridges, onboarding escalations — coordinate kill switch and broker health from a unified glass cockpit."
                : "Manage traders, catalog, and OMS flows. Platform risk controls stay with operations staff only."}
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

      {health.isError ? (
        <div
          className={cn(
            "flex flex-col gap-3 rounded-xl border px-4 py-3 text-sm sm:flex-row sm:items-center sm:justify-between",
            isLight ? "border-amber-200 bg-amber-50 text-amber-950" : "border-amber-500/30 bg-amber-950/20 text-amber-100",
          )}
        >
          <span>Live platform metrics unavailable: {parseAxiosMessage(health.error)}</span>
          <button
            type="button"
            onClick={() => void health.refetch()}
            className={cn(
              "shrink-0 rounded-lg border px-3 py-1.5 text-xs font-semibold",
              isLight ? "border-amber-300 bg-white hover:bg-amber-100" : "border-amber-700 text-amber-50 hover:bg-amber-950/50",
            )}
          >
            Retry health
          </button>
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
              value={health.isLoading ? "…" : killOn ? "ARMED" : "CLEAR"}
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
            sublabel={`Uptime · ${uptimeSec != null ? `${uptimeSec}s` : health.isLoading ? "…" : "—"}`}
            value={
              <Cpu
                className={cn("h-8 w-8", isLight ? "text-blue-600/90" : "text-blue-400/40")}
                aria-hidden
              />
            }
          />
          <MetricCard
            panelVariant={panel}
            label="Broker rail"
            trend="flat"
            value={<Radio className={cn("h-8 w-8", isLight ? "text-emerald-600/85" : "text-emerald-400/35")} aria-hidden />}
            sublabel="Rabbit ingest + deterministic ack"
          />

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
                    Approve LIVE trading with explicit audit lineage.
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
                    Operational doctrine ·{" "}
                  </span>
                  Never route LIVE executions without onboarding matrix completion, Zerodha session freshness, Telegram
                  binding, and explicit admin approvals — Redis arm doubles the gate.
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
                  Trader-safe admin view ·{" "}
                </span>
                You can manage users, strategy catalog, and OMS. Emergency controls and enforcement doctrine are limited
                to dedicated operations accounts.
              </div>
            </GlassPanel>
          )}
        </div>
      </WorkspaceTabPanel>

      <WorkspaceTabPanel id="pipeline" active={tab}>
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
                ? "Loading queue metadata…"
                : health.isError
                  ? "Health endpoint failed — use Retry above."
                  : "No queue map in response yet."}
            </p>
          )}
        </GlassPanel>
      </WorkspaceTabPanel>
    </div>
  );
}
