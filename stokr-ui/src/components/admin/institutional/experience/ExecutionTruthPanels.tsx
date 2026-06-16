import { motion } from "framer-motion";
import { Activity, CheckCircle2, Clock, Radio, ShieldCheck } from "lucide-react";
import { cn } from "../../../../lib/utils";
import { toneChipClasses } from "../../../../lib/statusTone";
import {
  buildBrokerTruthScore,
  extractOmsLatencyMs,
  mapOrderToExecutionSteps,
} from "../../../../lib/adminOperationalIntelligence";
import type { OpsSnapshot } from "../../cockpit/opsTypes";
import type { ReconciliationEventDto } from "../../../../api/reconciliation";

export function ExecutionStateMachinePanel({
  orderState,
  isLight,
}: {
  orderState: string | null;
  isLight: boolean;
}) {
  const steps = mapOrderToExecutionSteps(orderState);

  function stepClasses(status: string) {
    if (status === "done") return toneChipClasses(isLight, "success");
    if (status === "active") return isLight
      ? "border-blue-400 bg-blue-50 text-blue-900 ring-1 ring-blue-300/60"
      : "border-blue-500/40 bg-blue-500/15 text-blue-200 ring-1 ring-blue-500/30";
    if (status === "failed") return toneChipClasses(isLight, "critical");
    return isLight
      ? "border-neutral-300 bg-neutral-100 text-neutral-600"
      : "border-neutral-700 bg-neutral-900/40 text-neutral-400";
  }

  return (
    <div className={cn("rounded-2xl border p-5", isLight ? "border-neutral-200 bg-white" : "border-neutral-800 bg-neutral-950/50")}>
      <p className={cn("mb-4 text-[10px] font-bold uppercase tracking-[0.18em]", isLight ? "text-sky-700" : "text-sky-400")}>
        Live execution state machine
      </p>
      <div className="flex flex-wrap items-center gap-1">
        {steps.map((step, i) => (
          <div key={step.id} className="flex items-center gap-1">
            <div
              className={cn(
                "rounded-lg border px-2 py-1.5 text-[10px] font-semibold",
                stepClasses(step.status),
              )}
            >
              {step.label}
            </div>
            {i < steps.length - 1 ? <span className="text-neutral-600">→</span> : null}
          </div>
        ))}
      </div>
    </div>
  );
}

export function BrokerTruthScorePanel({
  snapshot,
  reconEvents,
  isLight,
}: {
  snapshot: OpsSnapshot | undefined;
  reconEvents: ReconciliationEventDto[];
  isLight: boolean;
}) {
  const truth = buildBrokerTruthScore(snapshot, reconEvents);
  const metrics = [
    { label: "Websocket", value: truth.websocketHealth },
    { label: "Fill sync", value: truth.fillSync },
    { label: "Position freshness", value: truth.positionFreshness },
    { label: "OMS consistency", value: truth.omsConsistency },
  ];

  return (
    <div className={cn("rounded-2xl border p-5", isLight ? "border-neutral-200 bg-gradient-to-br from-white to-emerald-50/30" : "border-neutral-800 bg-gradient-to-br from-neutral-950 to-emerald-950/15")}>
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <ShieldCheck className="h-4 w-4 text-emerald-400" />
          <p className={cn("text-[10px] font-bold uppercase tracking-[0.18em]", isLight ? "text-emerald-700" : "text-emerald-400")}>
            Broker truth confidence
          </p>
        </div>
        <motion.span
          className={cn("text-2xl font-bold tabular-nums", truth.score >= 75 ? "text-emerald-400" : truth.score >= 55 ? "text-amber-400" : "text-rose-400")}
          animate={{ opacity: [1, 0.85, 1] }}
          transition={{ duration: 3, repeat: Infinity }}
        >
          {truth.score}%
        </motion.span>
      </div>
      <div className="mt-4 grid grid-cols-2 gap-2 sm:grid-cols-4">
        {metrics.map((m) => (
          <div key={m.label} className={cn("rounded-lg border px-2 py-2 text-center", isLight ? "border-neutral-200" : "border-neutral-800")}>
            <p className={cn("text-[9px] uppercase", isLight ? "text-neutral-600" : "text-neutral-400")}>{m.label}</p>
            <p className={cn("font-mono text-sm font-bold", isLight ? "text-neutral-900" : "text-neutral-100")}>{m.value}%</p>
          </div>
        ))}
      </div>
      {truth.flags.length > 0 ? (
        <ul className={cn("mt-3 space-y-1 text-[11px]", isLight ? "text-amber-900" : "text-amber-200")}>
          {truth.flags.map((f) => <li key={f}>• {f}</li>)}
        </ul>
      ) : (
        <p className={cn("mt-3 text-[11px]", isLight ? "text-emerald-700" : "text-emerald-400")}>Execution truth reliable</p>
      )}
    </div>
  );
}

export function PositionTruthPanel({
  reconEvents,
  isLight,
}: {
  reconEvents: ReconciliationEventDto[];
  isLight: boolean;
}) {
  const open = reconEvents.filter((e) => e.status === "OPEN");
  const ghost = open.filter((e) => e.discrepancyType.toUpperCase().includes("GHOST") || (e.internalQty ?? 0) > (e.brokerQty ?? 0));
  const stale = open.filter((e) => e.discrepancyType.toUpperCase().includes("STALE"));
  const mismatch = open.filter((e) => !ghost.includes(e) && !stale.includes(e));

  return (
    <div className={cn("rounded-2xl border p-5", isLight ? "border-neutral-200 bg-white" : "border-neutral-800 bg-neutral-950/50")}>
      <p className={cn("mb-3 text-[10px] font-bold uppercase tracking-[0.18em]", isLight ? "text-violet-700" : "text-violet-400")}>
        Position truth engine
      </p>
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        {[
          { label: "Broker-only", count: open.filter((e) => (e.brokerQty ?? 0) > (e.internalQty ?? 0)).length },
          { label: "Platform-only", count: ghost.length },
          { label: "Stale", count: stale.length },
          { label: "Mismatches", count: mismatch.length },
        ].map((row) => (
          <div key={row.label} className={cn("rounded-lg border px-3 py-2", row.count > 0 ? "border-rose-500/30 bg-rose-500/10" : isLight ? "border-neutral-200" : "border-neutral-800")}>
            <p className="text-[9px] uppercase opacity-60">{row.label}</p>
            <p className="text-lg font-bold tabular-nums">{row.count}</p>
          </div>
        ))}
      </div>
    </div>
  );
}

export function ExecutionLatencyVisualizer({
  snapshot,
  isLight,
}: {
  snapshot: OpsSnapshot | undefined;
  isLight: boolean;
}) {
  const oms = snapshot?.oms as Record<string, unknown> | undefined;
  const avg = extractOmsLatencyMs(snapshot) ?? 0;
  const stuck = Number(oms?.stuckOrdersApprox ?? 0);
  const lanes = [
    { label: "API latency", ms: Math.round(avg * 0.35), icon: Activity },
    { label: "Queue delay", ms: Math.round(stuck * 12 + avg * 0.15), icon: Clock },
    { label: "Broker delay", ms: Math.round(avg * 0.3), icon: Radio },
    { label: "Fill delay", ms: Math.round(avg * 0.2), icon: CheckCircle2 },
  ];
  const maxMs = Math.max(...lanes.map((l) => l.ms), 1);

  return (
    <div className={cn("rounded-2xl border p-5", isLight ? "border-neutral-200 bg-white" : "border-neutral-800 bg-neutral-950/50")}>
      <p className={cn("mb-4 text-[10px] font-bold uppercase tracking-[0.18em]", isLight ? "text-cyan-700" : "text-cyan-400")}>
        Execution latency visualizer
      </p>
      <div className="space-y-3">
        {lanes.map((lane) => {
          const Icon = lane.icon;
          return (
            <div key={lane.label} className="flex items-center gap-3">
              <Icon className="h-4 w-4 shrink-0 opacity-60" />
              <span className="w-24 text-[11px]">{lane.label}</span>
              <div className={cn("h-2 flex-1 overflow-hidden rounded-full", isLight ? "bg-neutral-200" : "bg-neutral-800")}>
                <motion.div
                  className={cn("h-full rounded-full", lane.ms > avg ? "bg-rose-500" : "bg-cyan-500")}
                  initial={{ width: 0 }}
                  animate={{ width: `${(lane.ms / maxMs) * 100}%` }}
                  transition={{ duration: 0.6 }}
                />
              </div>
              <span className="w-12 text-right font-mono text-[11px]">{lane.ms}ms</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
