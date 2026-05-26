import { motion } from "framer-motion";
import { AlertCircle, Cpu, Radio, Shield, TrendingUp, Zap } from "lucide-react";
import { Link } from "react-router-dom";
import { cn } from "../../../../lib/utils";
import { buildTopologyLoad, extractOmsLatencyMs } from "../../../../lib/adminOperationalIntelligence";
import { asArray, asRecord, type OpsSnapshot } from "../../cockpit/opsTypes";

type NodeDef = {
  id: string;
  label: string;
  icon: typeof Radio;
  status: string;
  tone: "ok" | "warn" | "bad";
  delayMs?: number;
  to?: string;
};

function toneClass(tone: "ok" | "warn" | "bad", isLight: boolean) {
  if (tone === "ok") return isLight ? "border-emerald-300/70 bg-emerald-50/60" : "border-emerald-500/35 bg-emerald-500/10";
  if (tone === "warn") return isLight ? "border-amber-300/70 bg-amber-50/60" : "border-amber-500/35 bg-amber-500/10";
  return isLight ? "border-rose-300/70 bg-rose-50/60" : "border-rose-500/35 bg-rose-500/10";
}

export function LivePlatformTopology({ snapshot, isLight }: { snapshot: OpsSnapshot | undefined; isLight: boolean }) {
  const fresh = asRecord(snapshot?.marketFreshness);
  const oms = asRecord(snapshot?.oms);
  const lifecycle = asRecord(snapshot?.operationalLifecycle);
  const scan = asRecord(snapshot?.scannerTelemetry);

  const nodes: NodeDef[] = [
    {
      id: "feed",
      label: "Market feed",
      icon: Radio,
      status: String(fresh?.status ?? snapshot?.marketInfra?.sessionState ?? "—"),
      tone: fresh?.status === "STALE" ? "warn" : fresh?.status === "OK" || fresh?.status === "LIVE" ? "ok" : "bad",
      delayMs: typeof fresh?.lagMs === "number" ? fresh.lagMs : undefined,
      to: "/admin/market",
    },
    {
      id: "signals",
      label: "Signal engine",
      icon: Zap,
      status: String(lifecycle?.signals ?? scan?.signalsFromScannerTotal ?? "—"),
      tone: Number(scan?.failuresTotal ?? 0) > 0 ? "warn" : "ok",
      to: "/admin/signals",
    },
    {
      id: "risk",
      label: "Risk engine",
      icon: Shield,
      status: String(lifecycle?.risk ?? "GUARD"),
      tone: "ok",
      to: "/admin/risk-dashboard",
    },
    {
      id: "oms",
      label: "OMS",
      icon: Cpu,
      status: String(oms?.health ?? oms?.status ?? "—"),
      tone: oms?.health === "DEGRADED" ? "warn" : "ok",
      delayMs: extractOmsLatencyMs(snapshot) ?? undefined,
      to: "/admin/oms",
    },
    {
      id: "broker",
      label: "Broker",
      icon: TrendingUp,
      status: String(asRecord(snapshot?.brokerSessions)?.aggregateStatus ?? "—"),
      tone: snapshot?.marketInfra?.globalBrokerHalt ? "bad" : "ok",
      to: "/admin/broker-infrastructure",
    },
  ];

  const incidents = asArray(snapshot?.incidents)?.length ?? 0;
  const load = buildTopologyLoad(snapshot);

  return (
    <div
      className={cn(
        "relative overflow-hidden rounded-2xl border p-5",
        isLight
          ? "border-neutral-200 bg-gradient-to-br from-neutral-50 via-white to-blue-50/40"
          : "border-neutral-800 bg-gradient-to-br from-neutral-950 via-neutral-900/50 to-blue-950/20",
      )}
    >
      <motion.div
        aria-hidden
        className="pointer-events-none absolute inset-0 opacity-30"
        style={{
          backgroundImage: isLight
            ? "radial-gradient(circle at 20% 20%, rgba(59,130,246,0.12), transparent 40%), radial-gradient(circle at 80% 0%, rgba(168,85,247,0.08), transparent 35%)"
            : "radial-gradient(circle at 20% 20%, rgba(59,130,246,0.18), transparent 42%), radial-gradient(circle at 80% 0%, rgba(168,85,247,0.12), transparent 38%)",
        }}
      />
      <div className="relative mb-4 flex flex-wrap items-end justify-between gap-2">
        <div>
          <p className={cn("text-[10px] font-bold uppercase tracking-[0.18em]", isLight ? "text-blue-700" : "text-blue-400")}>
            Live platform topology
          </p>
          <p className={cn("mt-1 text-sm", isLight ? "text-neutral-600" : "text-neutral-400")}>
            Feed → signals → risk → OMS → broker execution chain
          </p>
        </div>
        {incidents > 0 ? (
          <span className="inline-flex items-center gap-1 rounded-full border border-rose-500/40 bg-rose-500/10 px-2.5 py-1 text-[10px] font-bold uppercase text-rose-200">
            <AlertCircle className="h-3 w-3" /> {incidents} alert(s)
          </span>
        ) : null}
      </div>

      <div className="relative flex flex-col gap-3 lg:flex-row lg:items-stretch lg:gap-2">
        {nodes.map((node, i) => {
          const Icon = node.icon;
          const body = (
            <motion.div
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: i * 0.08 }}
              className={cn(
                "relative flex min-w-[140px] flex-1 flex-col rounded-xl border p-3 transition hover:-translate-y-0.5",
                toneClass(node.tone, isLight),
              )}
            >
              <div className="flex items-center justify-between gap-2">
                <Icon className={cn("h-4 w-4", isLight ? "text-neutral-700" : "text-neutral-200")} />
                {node.tone === "ok" ? (
                  <motion.span
                    className="h-2 w-2 rounded-full bg-emerald-400"
                    animate={{ opacity: [0.4, 1, 0.4] }}
                    transition={{ duration: 2, repeat: Infinity }}
                  />
                ) : null}
              </div>
              <p className={cn("mt-2 text-[10px] font-semibold uppercase tracking-wide opacity-70")}>{node.label}</p>
              <p className={cn("mt-1 font-mono text-sm font-bold", isLight ? "text-neutral-900" : "text-white")}>
                {String(node.status).replace(/_/g, " ")}
              </p>
              {node.delayMs != null ? (
                <p className={cn("mt-1 text-[10px]", isLight ? "text-neutral-500" : "text-neutral-400")}>{node.delayMs}ms</p>
              ) : null}
            </motion.div>
          );
          return (
            <div key={node.id} className="flex flex-1 items-center gap-2">
              {node.to ? <Link to={node.to} className="flex-1">{body}</Link> : body}
              {i < nodes.length - 1 ? (
                <motion.div
                  aria-hidden
                  className={cn("hidden h-0.5 w-6 shrink-0 lg:block", isLight ? "bg-blue-300/60" : "bg-blue-500/40")}
                  animate={{ opacity: [0.3, 1, 0.3] }}
                  transition={{ duration: 1.8, repeat: Infinity, delay: i * 0.2 }}
                />
              ) : null}
            </div>
          );
        })}
      </div>

      <div className="relative mt-5 grid grid-cols-2 gap-2 border-t border-dashed pt-4 sm:grid-cols-3 lg:grid-cols-5">
        {[
          { label: "Queue congestion", value: `${load.queueCongestion}%`, hot: load.queueCongestion > 50 },
          { label: "Throughput", value: load.throughput, hot: false },
          { label: "Strategy load", value: `${load.strategyLoad}%`, hot: load.strategyLoad > 70 },
          { label: "Latency propagation", value: load.latencyPropagation ? `${Math.round(load.latencyPropagation)}ms` : "—", hot: load.latencyPropagation > 600 },
          { label: "Risk pressure", value: `${load.riskPressure}%`, hot: load.riskPressure > 55 },
        ].map((m) => (
          <div
            key={m.label}
            className={cn(
              "rounded-lg border px-2.5 py-2",
              m.hot
                ? isLight ? "border-amber-300 bg-amber-50/70" : "border-amber-500/35 bg-amber-500/10"
                : isLight ? "border-neutral-200 bg-white/60" : "border-neutral-800 bg-neutral-900/40",
            )}
          >
            <p className="text-[9px] uppercase tracking-wide opacity-60">{m.label}</p>
            <p className="font-mono text-xs font-bold">{m.value}</p>
          </div>
        ))}
      </div>
      {(load.brokerDegradation || load.replayActivity > 0) ? (
        <p className={cn("relative mt-3 text-[10px]", isLight ? "text-neutral-500" : "text-neutral-400")}>
          {load.brokerDegradation ? "Broker degradation detected · " : ""}
          WS {load.websocketTraffic}
          {load.replayActivity > 0 ? ` · ${load.replayActivity} replay job(s) active` : ""}
        </p>
      ) : null}
    </div>
  );
}
