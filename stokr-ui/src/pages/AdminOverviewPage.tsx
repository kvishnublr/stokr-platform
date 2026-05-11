import { useQuery } from "@tanstack/react-query";
import { motion } from "framer-motion";
import {
  Cpu,
  Radio,
  ShieldAlert,
  Siren,
  Users,
  Workflow,
  ZapOff,
  Activity,
} from "lucide-react";
import { api } from "../api/client";
import { GlassPanel } from "../components/ds/GlassPanel";
import { MetricCard } from "../components/ds/MetricCard";
import { StatusChip } from "../components/ds/StatusChip";
import { WorkspaceTabs, WorkspaceTabPanel } from "../components/ds/WorkspaceTabs";
import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useSessionStore } from "../state/session";

export function AdminOverviewPage() {
  const showRiskConsole = useSessionStore((s) => s.canAccessKillSwitchOperations());
  const health = useQuery({
    queryKey: ["admin-health"],
    queryFn: async () => {
      const res = await api.get("/api/admin/health");
      return res.data?.data as Record<string, unknown>;
    },
    refetchInterval: 8000,
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
            <h1 className="mt-6 bg-gradient-to-br from-white via-white to-neutral-400 bg-clip-text text-[32px] font-semibold tracking-tight text-transparent">
              {showRiskConsole ? "Operations constellation" : "Admin command center"}
            </h1>
            <p className="mt-3 max-w-3xl text-sm leading-relaxed text-neutral-400">
              {showRiskConsole
                ? "Replay validation, deterministic execution bridges, onboarding escalations — coordinate kill switch and broker health from a unified glass cockpit."
                : "Manage traders, catalog, and OMS flows. Platform risk controls stay with operations staff only."}
            </p>
          </div>
          <GlassPanel className="relative w-full max-w-sm overflow-hidden border-white/[0.08] p-5 shadow-[0_0_0_1px_rgba(59,130,246,0.08),0_24px_80px_-24px_rgba(0,0,0,0.6)]">
            <div className="pointer-events-none absolute -right-8 -top-8 h-28 w-28 rounded-full bg-blue-500/10 blur-2xl" />
            <div className="flex items-center gap-2 text-[11px] font-bold uppercase tracking-widest text-neutral-400">
              <Siren className="h-4 w-4 text-amber-300/90" /> Quick lanes
            </div>
            <div className="mt-4 flex flex-wrap gap-2">
              <Link
                to="/admin/users"
                className="rounded-lg bg-white px-3 py-1.5 text-[11px] font-bold uppercase tracking-wide text-neutral-950 shadow-lg shadow-black/25 transition hover:bg-neutral-100"
              >
                Traders
              </Link>
              <Link
                to="/admin/oms"
                className="rounded-lg border border-white/[0.1] bg-neutral-950/50 px-3 py-1.5 text-[11px] font-bold uppercase tracking-wide text-neutral-100 backdrop-blur-sm transition hover:border-blue-500/30 hover:bg-blue-500/10"
              >
                OMS
              </Link>
              {showRiskConsole ? (
                <Link
                  to="/admin/ops"
                  className="rounded-lg border border-rose-500/45 bg-rose-500/10 px-3 py-1.5 text-[11px] font-black uppercase tracking-wide text-rose-50 shadow-[0_0_20px_-4px_rgba(244,63,94,0.35)] transition hover:bg-rose-500/20"
                >
                  Emergency rail
                </Link>
              ) : null}
            </div>
          </GlassPanel>
        </div>
      </motion.div>

      <WorkspaceTabs tabs={tabs} active={tab} onChange={setTab} />

      <WorkspaceTabPanel id="overview" active={tab}>
        <div className="grid gap-4 md:grid-cols-3">
          {showRiskConsole ? (
            <MetricCard highlight={killOn} label="Kill switch" value={health.isLoading ? "…" : killOn ? "ARMED" : "CLEAR"} trend={killOn ? "down" : "up"} />
          ) : (
            <MetricCard label="Console mode" trend="flat" value="Staff" sublabel="Risk & incidents hidden for trader profiles" />
          )}
          <MetricCard label="Service vitality" trend="flat" sublabel={`Uptime · ${health.data?.uptimeSeconds ?? "—"}s`} value={<Cpu className="h-8 w-8 text-blue-400/40" />} />
          <MetricCard label="Broker rail" trend="flat" value={<Radio className="h-8 w-8 text-emerald-400/35" />} sublabel="Rabbit ingest + deterministic ack" />

          <GlassPanel className="p-6 md:col-span-3">
            <div className="flex flex-wrap items-center justify-between gap-4 border-b border-white/5 pb-4">
              <div className="flex items-center gap-3">
                <Users className="h-10 w-10 text-neutral-700" />
                <div>
                  <div className="text-[13px] font-semibold text-white">Trader onboarding desk</div>
                  <div className="mt-1 text-xs text-neutral-500">Approve LIVE trading with explicit audit lineage.</div>
                </div>
              </div>
              <Workflow className="h-10 w-10 text-blue-900/70" />
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
                    className="rounded-xl border border-white/[0.06] bg-gradient-to-b from-neutral-900/80 to-neutral-950/90 p-4 shadow-[inset_0_1px_0_0_rgba(255,255,255,0.04)] transition hover:border-blue-500/25 hover:shadow-[0_12px_40px_-12px_rgba(59,130,246,0.25)]"
                  >
                    <div className="text-[11px] font-bold uppercase tracking-widest text-neutral-500">{label}</div>
                    <div className="mt-5 text-blue-400/50">
                      <Activity className="h-6 w-6" />
                    </div>
                  </motion.div>
                </Link>
              ))}
            </div>
          </GlassPanel>

          {showRiskConsole ? (
            <GlassPanel className="border border-rose-500/20 bg-gradient-to-br from-rose-950/40 to-neutral-950/80 p-5 md:col-span-3">
              <div className="flex items-start gap-3">
                <ZapOff className="mt-1 h-5 w-5 shrink-0 text-rose-200" />
                <div className="text-sm leading-relaxed text-neutral-400">
                  <span className="font-semibold text-white">Operational doctrine · </span>
                  Never route LIVE executions without onboarding matrix completion, Zerodha session freshness, Telegram binding,
                  and explicit admin approvals — Redis arm doubles the gate.
                </div>
              </div>
            </GlassPanel>
          ) : (
            <GlassPanel className="border border-blue-500/15 bg-gradient-to-br from-blue-950/20 to-neutral-950/90 p-5 md:col-span-3">
              <div className="text-sm leading-relaxed text-neutral-400">
                <span className="font-semibold text-white">Trader-safe admin view · </span>
                You can manage users, strategy catalog, and OMS. Emergency controls and enforcement doctrine are limited to
                dedicated operations accounts.
              </div>
            </GlassPanel>
          )}
        </div>
      </WorkspaceTabPanel>

      <WorkspaceTabPanel id="pipeline" active={tab}>
        <GlassPanel className="p-5">
          <div className="flex items-center gap-3">
            <ShieldAlert className="h-5 w-5 text-emerald-300" />
            <div className="text-[13px] font-semibold text-white">Ingress / egress topology</div>
          </div>
          {health.data?.queues ? (
            <pre className="mt-4 max-h-[420px] overflow-auto rounded-xl border border-neutral-900 bg-neutral-950 p-4 font-mono text-[11px] text-neutral-400">
              {JSON.stringify(health.data.queues, null, 2)}
            </pre>
          ) : (
            <p className="mt-4 text-xs text-neutral-600">Queue introspection attaches when actuator streams respond.</p>
          )}
        </GlassPanel>
      </WorkspaceTabPanel>
    </div>
  );
}
