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

export function AdminOverviewPage() {
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
            <StatusChip status={killOn ? "offline" : "online"} label={killOn ? "Kill armed" : "Markets tolerant"} />
            <h1 className="mt-6 text-[32px] font-semibold tracking-tight text-white">Operations constellation</h1>
            <p className="mt-3 max-w-3xl text-sm text-neutral-400">
              Replay validation, deterministic execution bridges, onboarding escalations · coordinate kill switch + broker
              health from a unified glass cockpit.
            </p>
          </div>
          <GlassPanel className="w-full max-w-sm p-5">
            <div className="flex items-center gap-2 text-[11px] font-bold uppercase tracking-widest text-neutral-500">
              <Siren className="h-4 w-4 text-amber-300" /> Quick lanes
            </div>
            <div className="mt-4 flex flex-wrap gap-2">
              <Link
                to="/admin/users"
                className="rounded-lg bg-white px-3 py-1.5 text-[11px] font-bold uppercase tracking-wide text-neutral-950 hover:bg-neutral-100"
              >
                Traders
              </Link>
              <Link
                to="/admin/oms"
                className="rounded-lg border border-neutral-700 px-3 py-1.5 text-[11px] font-bold uppercase tracking-wide text-neutral-200 hover:bg-neutral-900"
              >
                OMS
              </Link>
              <Link
                to="/admin/ops"
                className="rounded-lg border border-rose-500/40 px-3 py-1.5 text-[11px] font-black uppercase tracking-wide text-rose-50 hover:bg-rose-500/15"
              >
                Emergency rail
              </Link>
            </div>
          </GlassPanel>
        </div>
      </motion.div>

      <WorkspaceTabs tabs={tabs} active={tab} onChange={setTab} />

      <WorkspaceTabPanel id="overview" active={tab}>
        <div className="grid gap-4 md:grid-cols-3">
          <MetricCard highlight={killOn} label="Kill switch" value={health.isLoading ? "…" : killOn ? "ARMED" : "CLEAR"} trend={killOn ? "down" : "up"} />
          <MetricCard label="Service vitality" trend="flat" sublabel={`Uptime · ${health.data?.uptimeSeconds ?? "—"}s`} value={<Cpu className="h-8 w-8 text-neutral-700" />} />
          <MetricCard label="Broker rail" trend="flat" value={<Radio className="h-8 w-8 text-neutral-700" />} sublabel="Rabbit ingest + deterministic ack" />

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
            <div className="mt-5 grid gap-4 md:grid-cols-4">
              {["Users", "Strategies", "OMS", "Ops"].map((label) => (
                <motion.div key={label} whileHover={{ y: -2 }} className="rounded-xl border border-neutral-900 bg-neutral-950/70 p-4">
                  <div className="text-[11px] font-bold uppercase tracking-widest text-neutral-500">{label}</div>
                  <div className="mt-5 text-neutral-700">
                    <Activity className="h-6 w-6" />
                  </div>
                </motion.div>
              ))}
            </div>
          </GlassPanel>

          <GlassPanel className="border border-rose-500/25 bg-rose-950/30 p-5 md:col-span-3">
            <div className="flex items-start gap-3">
              <ZapOff className="mt-1 h-5 w-5 text-rose-200" />
              <div className="text-sm text-neutral-400">
                <span className="font-semibold text-white">Operational doctrine · </span>
                Never route LIVE executions without onboarding matrix completion, Zerodha session freshness, Telegram binding,
                and explicit admin approvals — Redis arm doubles the gate.
              </div>
            </div>
          </GlassPanel>
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
