import { Link } from "react-router-dom";
import { AlertTriangle, Shield, ShieldOff, Zap } from "lucide-react";
import { toast } from "sonner";
import { cn } from "../../../../lib/utils";
import { AdminHeatCell } from "../AdminDesignSystem";
import {
  mapInsightTone,
  toneButtonClasses,
  toneChipClasses,
  toneEyebrowClasses,
  toneSectionClasses,
} from "../../../../lib/statusTone";
import {
  buildDrawdownEngine,
  buildExposureMap,
  type OperationalInsight,
} from "../../../../lib/adminOperationalIntelligence";
import type { AdminRiskDashboardDto } from "../../../../api/riskDashboard";
import type { GlobalCapitalSummary } from "../../../../api/riskDashboard";
import type { ReconciliationEventDto } from "../../../../api/reconciliation";
import { badgeClassForStatus } from "../../cockpit/opsTypes";

export function LiveExposureMapPanel({
  risk,
  capital,
  isLight,
}: {
  risk: AdminRiskDashboardDto;
  capital: GlobalCapitalSummary | undefined;
  isLight: boolean;
}) {
  const exposure = buildExposureMap(risk, capital);
  const top = exposure.byStrategy.slice(0, 8);

  return (
    <div className={cn("rounded-2xl border p-5", isLight ? "border-neutral-200 bg-white" : "border-neutral-800 bg-neutral-950/50")}>
      <p className={cn("text-[10px] font-bold uppercase tracking-[0.18em]", isLight ? "text-emerald-700" : "text-emerald-400")}>
        Live exposure map
      </p>
      <div className="mt-3 grid grid-cols-3 gap-2">
        <AdminHeatCell isLight={isLight} label="Open positions" value={String(exposure.totalPositions)} intensity={Math.min(1, exposure.totalPositions / 30)} />
        <AdminHeatCell isLight={isLight} label="Live strategies" value={String(exposure.liveStrategies)} intensity={exposure.liveStrategies / Math.max(risk.activeStrategies, 1)} />
        <AdminHeatCell isLight={isLight} label="Bias" value={exposure.directionalBias} intensity={exposure.directionalBias === "neutral" ? 0.3 : 0.7} />
      </div>
      <div className="mt-4 grid gap-1.5">
        {top.map((row) => (
          <div key={row.key} className="flex items-center gap-2">
            <span className="w-28 truncate font-mono text-[10px]">{row.key}</span>
            <div className={cn("h-2 flex-1 overflow-hidden rounded-full", isLight ? "bg-neutral-200" : "bg-neutral-800")}>
              <div
                className={cn("h-full rounded-full", row.utilization > 0.8 ? "bg-rose-500" : row.utilization > 0.5 ? "bg-amber-500" : "bg-emerald-500")}
                style={{ width: `${Math.round(row.utilization * 100)}%` }}
              />
            </div>
            <span className="w-16 text-right font-mono text-[10px]">{row.positions}/{row.max}</span>
          </div>
        ))}
      </div>
      {capital ? (
        <p className={cn("mt-3 text-[11px]", isLight ? "text-neutral-500" : "text-neutral-400")}>
          Capital utilized ₹{capital.totalUtilizedCapital.toLocaleString()} / ₹{capital.totalAllocatedCapital.toLocaleString()}
        </p>
      ) : null}
    </div>
  );
}

export function DrawdownEnginePanel({ risk, isLight }: { risk: AdminRiskDashboardDto; isLight: boolean }) {
  const dd = buildDrawdownEngine(risk);
  return (
    <div className={cn("rounded-2xl border p-5", isLight ? "border-neutral-200 bg-gradient-to-br from-white to-rose-50/30" : "border-neutral-800 bg-gradient-to-br from-neutral-950 to-rose-950/15")}>
      <p className={cn("text-[10px] font-bold uppercase tracking-[0.18em]", isLight ? "text-rose-700" : "text-rose-400")}>
        Real-time drawdown engine
      </p>
      <div className="mt-3 flex flex-wrap items-end gap-4">
        <div>
          <p className={cn("text-[10px] uppercase", isLight ? "text-neutral-600" : "text-neutral-400")}>Session PnL</p>
          <p className={cn("text-2xl font-bold tabular-nums", dd.sessionPnl >= 0 ? "text-emerald-500" : "text-rose-500")}>
            {dd.sessionPnl >= 0 ? "+" : ""}{dd.sessionPnl.toFixed(0)}
          </p>
        </div>
        <div>
          <p className={cn("text-[10px] uppercase", isLight ? "text-neutral-600" : "text-neutral-400")}>Rolling stress</p>
          <p className={cn("text-2xl font-bold tabular-nums", isLight ? "text-neutral-900" : "text-neutral-100")}>{dd.rollingStress}%</p>
        </div>
      </div>
      <div className="mt-4">
        <p className={cn("mb-2 text-[10px] font-bold uppercase", isLight ? "text-neutral-600" : "text-neutral-400")}>Worst strategies today</p>
        {dd.worstStrategies.length === 0 ? (
          <p className={cn("text-xs", isLight ? "text-neutral-500" : "text-neutral-500")}>No PnL data</p>
        ) : (
          <div className="space-y-1">
            {dd.worstStrategies.map((s) => (
              <div key={s.strategyKey} className={cn("flex justify-between font-mono text-[11px]", isLight ? "text-neutral-800" : "text-neutral-200")}>
                <span>{s.strategyKey}</span>
                <span className="text-rose-500">{s.todayPnl?.toFixed(0)}</span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

export function BrokerDivergencePanel({
  events,
  isLight,
  onReconcile,
}: {
  events: ReconciliationEventDto[];
  isLight: boolean;
  onReconcile?: () => void;
}) {
  const open = events.filter((e) => e.status === "OPEN");
  return (
    <div className={cn("rounded-2xl border p-5", open.length > 0 ? (isLight ? "border-rose-300 bg-rose-50/50" : "border-rose-500/40 bg-rose-500/10") : (isLight ? "border-neutral-200 bg-white" : "border-neutral-800 bg-neutral-950/50"))}>
      <div className="flex items-center justify-between gap-2">
        <p className={cn("text-[10px] font-bold uppercase tracking-[0.18em]", isLight ? "text-rose-700" : "text-rose-400")}>
          Broker divergence alerts
        </p>
        {onReconcile ? (
          <button
            type="button"
            onClick={onReconcile}
            className={cn("rounded-lg border px-2 py-1 text-[10px] font-semibold", toneButtonClasses(isLight, "secondary"))}
          >
            Run recon
          </button>
        ) : null}
      </div>
      {open.length === 0 ? (
        <p className={cn("mt-3 text-sm", isLight ? "text-emerald-700" : "text-emerald-400")}>No open divergence events</p>
      ) : (
        <ul className="mt-3 space-y-2">
          {open.slice(0, 6).map((e) => (
            <li key={e.id} className="flex items-start gap-2 text-[11px]">
              <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-rose-400" />
              <span>
                <span className="font-semibold">{e.discrepancyType}</span>
                {e.symbol ? ` · ${e.symbol}` : ""}
                {e.delta != null ? ` · Δ ${e.delta}` : ""}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function killSwitchPillStatus(label: string, active: boolean): string {
  if (!active) return "OFF";
  if (label === "Live armed") return "ARMED";
  return "ON";
}

export function KillSwitchCenterPanel({
  risk,
  isLight,
  onEmergencyStop,
}: {
  risk: AdminRiskDashboardDto;
  isLight: boolean;
  onEmergencyStop: (strategyKey: string, stop: boolean) => void;
}) {
  const halted = risk.strategyRiskStates.filter((s) => s.emergencyStopEnabled);
  const switches = [
    { label: "Global kill", active: risk.killSwitchActive, to: "/admin/safety-diagnostics" },
    { label: "Broker halt", active: risk.brokerHalt, to: "/admin/broker-infrastructure" },
    { label: "Live armed", active: risk.liveTradingArmed, to: "/admin/execution-config" },
    { label: "Signal halt", active: risk.emergencyStoppedStrategies > 0, to: "/admin/strategies" },
    { label: "Execution halt", active: risk.todayRejects > 5, to: "/admin/oms" },
    { label: "Emergency flatten", active: false, to: "/admin/safety-diagnostics" },
  ] as const;

  return (
    <div
      className={cn(
        "rounded-2xl border p-5",
        isLight ? "border-border bg-card text-foreground" : "border-neutral-800 bg-neutral-950/50",
      )}
    >
      <div className="flex items-center gap-2">
        <Shield className={cn("h-4 w-4", isLight ? "text-rose-700" : "text-rose-400")} />
        <p className={cn("text-[10px] font-bold uppercase tracking-[0.18em]", toneEyebrowClasses(isLight, "rose"))}>
          Kill switch center
        </p>
      </div>
      <div className="mt-4 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
        {switches.map((item) => {
          const status = killSwitchPillStatus(item.label, item.active);
          return (
            <Link
              key={item.label}
              to={item.to}
              title={item.label}
              className={cn(
                "flex min-h-[3.25rem] items-center gap-2 rounded-md border-2 px-3 py-2 transition hover:-translate-y-0.5",
                badgeClassForStatus(status),
              )}
            >
              {item.active ? (
                <ShieldOff className="h-4 w-4 shrink-0 opacity-80" />
              ) : (
                <Zap className="h-4 w-4 shrink-0 opacity-70" />
              )}
              <span className="truncate text-xs font-semibold">{item.label}</span>
              <span className="ml-auto shrink-0 font-mono text-[10px] font-bold uppercase tracking-wide">
                {item.active ? (status === "ARMED" ? "ARMED" : "ACTIVE") : "OFF"}
              </span>
            </Link>
          );
        })}
      </div>
      {halted.length > 0 ? (
        <div className="mt-4 space-y-1">
          <p className={cn("text-[10px] font-bold uppercase tracking-wide", isLight ? "text-muted-foreground" : "text-neutral-400")}>
            Strategy halts
          </p>
          {halted.map((s) => (
            <button
              key={s.strategyKey}
              type="button"
              onClick={() => { onEmergencyStop(s.strategyKey, false); toast.success(`Clearing stop for ${s.strategyKey}`); }}
              className={cn(
                "block w-full rounded-md border-2 px-2 py-1.5 text-left text-[11px] font-medium transition",
                toneChipClasses(isLight, "critical"),
              )}
            >
              {s.strategyKey} — click to clear
            </button>
          ))}
        </div>
      ) : null}
    </div>
  );
}

export function RiskHeatmapGrid({
  risk,
  isLight,
}: {
  risk: AdminRiskDashboardDto;
  isLight: boolean;
}) {
  const rejectStress = risk.todayOrders > 0 ? risk.todayRejects / risk.todayOrders : 0;
  const tiles = [
    { label: "Concentration", intensity: Math.min(1, risk.strategyRiskStates.reduce((a, s) => a + s.openPositions, 0) / 40) },
    { label: "Volatility", intensity: rejectStress },
    { label: "PnL instability", intensity: Math.min(1, Math.abs(buildDrawdownEngine(risk).sessionPnl) / 5000) },
    { label: "Queue congestion", intensity: Math.min(1, risk.todayOrders / 200) },
    { label: "Broker instability", intensity: risk.openReconciliationAlerts > 0 ? 0.8 : 0.15 },
  ];
  return (
    <div className="grid grid-cols-2 gap-2 sm:grid-cols-5">
      {tiles.map((t) => (
        <AdminHeatCell key={t.label} isLight={isLight} label={t.label} value={`${Math.round(t.intensity * 100)}%`} intensity={t.intensity} />
      ))}
    </div>
  );
}

export function OperationalInsightsStrip({ insights, isLight }: { insights: OperationalInsight[]; isLight: boolean }) {
  if (insights.length === 0) return null;
  return (
    <div className={cn("rounded-2xl border p-4", toneSectionClasses(isLight, "indigo"))}>
      <p className={cn("mb-2 text-[10px] font-bold uppercase tracking-[0.18em]", toneEyebrowClasses(isLight, "indigo"))}>
        Operational insights
      </p>
      <div className="flex flex-wrap gap-2">
        {insights.map((ins) => (
          <Link
            key={ins.id}
            to={ins.action ?? "#"}
            className={cn(
              "rounded-xl border px-3 py-2 text-[11px] transition hover:-translate-y-0.5",
              toneChipClasses(isLight, mapInsightTone(ins.tone)),
            )}
          >
            <span className="font-semibold">{ins.title}</span>
            <span className={cn("ml-2", isLight ? "opacity-80" : "opacity-90")}>{ins.detail}</span>
          </Link>
        ))}
      </div>
    </div>
  );
}
