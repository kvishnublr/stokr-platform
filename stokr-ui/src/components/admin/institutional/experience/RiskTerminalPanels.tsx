import { Link } from "react-router-dom";
import { AlertTriangle, Shield, ShieldOff, Zap } from "lucide-react";
import { toast } from "sonner";
import { cn } from "../../../../lib/utils";
import { AdminHeatCell } from "../AdminDesignSystem";
import {
  buildDrawdownEngine,
  buildExposureMap,
  type OperationalInsight,
} from "../../../../lib/adminOperationalIntelligence";
import type { AdminRiskDashboardDto } from "../../../../api/riskDashboard";
import type { GlobalCapitalSummary } from "../../../../api/riskDashboard";
import type { ReconciliationEventDto } from "../../../../api/reconciliation";

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
          <p className="text-[10px] uppercase opacity-60">Session PnL</p>
          <p className={cn("text-2xl font-bold tabular-nums", dd.sessionPnl >= 0 ? "text-emerald-500" : "text-rose-500")}>
            {dd.sessionPnl >= 0 ? "+" : ""}{dd.sessionPnl.toFixed(0)}
          </p>
        </div>
        <div>
          <p className="text-[10px] uppercase opacity-60">Rolling stress</p>
          <p className="text-2xl font-bold tabular-nums">{dd.rollingStress}%</p>
        </div>
      </div>
      <div className="mt-4">
        <p className="mb-2 text-[10px] font-bold uppercase opacity-60">Worst strategies today</p>
        {dd.worstStrategies.length === 0 ? (
          <p className="text-xs opacity-50">No PnL data</p>
        ) : (
          <div className="space-y-1">
            {dd.worstStrategies.map((s) => (
              <div key={s.strategyKey} className="flex justify-between font-mono text-[11px]">
                <span>{s.strategyKey}</span>
                <span className="text-rose-400">{s.todayPnl?.toFixed(0)}</span>
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
          <button type="button" onClick={onReconcile} className="rounded-lg border px-2 py-1 text-[10px] font-semibold">Run recon</button>
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
  return (
    <div className={cn("rounded-2xl border p-5", isLight ? "border-neutral-200 bg-neutral-950 text-neutral-100" : "border-neutral-700 bg-neutral-950")}>
      <div className="flex items-center gap-2">
        <Shield className="h-4 w-4 text-rose-400" />
        <p className="text-[10px] font-bold uppercase tracking-[0.18em] text-rose-400">Kill switch center</p>
      </div>
      <div className="mt-4 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
        {[
          { label: "Global kill", active: risk.killSwitchActive, to: "/admin/ops" },
          { label: "Broker halt", active: risk.brokerHalt, to: "/admin/broker-infrastructure" },
          { label: "Live armed", active: risk.liveTradingArmed, to: "/admin/execution-config" },
          { label: "Signal halt", active: risk.emergencyStoppedStrategies > 0, to: "/admin/strategies" },
          { label: "Execution halt", active: risk.todayRejects > 5, to: "/admin/oms" },
          { label: "Emergency flatten", active: false, to: "/admin/ops" },
        ].map((item) => (
          <Link
            key={item.label}
            to={item.to}
            className={cn(
              "flex items-center gap-2 rounded-xl border px-3 py-2.5 transition hover:-translate-y-0.5",
              item.active ? "border-rose-500/50 bg-rose-500/15" : "border-neutral-700 bg-neutral-900/60",
            )}
          >
            {item.active ? <ShieldOff className="h-4 w-4 text-rose-400" /> : <Zap className="h-4 w-4 text-neutral-500" />}
            <span className="text-xs font-semibold">{item.label}</span>
            <span className={cn("ml-auto text-[10px] font-bold uppercase", item.active ? "text-rose-400" : "text-emerald-500")}>
              {item.active ? "ACTIVE" : "OFF"}
            </span>
          </Link>
        ))}
      </div>
      {halted.length > 0 ? (
        <div className="mt-4 space-y-1">
          <p className="text-[10px] uppercase opacity-60">Strategy halts</p>
          {halted.map((s) => (
            <button
              key={s.strategyKey}
              type="button"
              onClick={() => { onEmergencyStop(s.strategyKey, false); toast.success(`Clearing stop for ${s.strategyKey}`); }}
              className="block w-full rounded-lg border border-rose-500/30 px-2 py-1 text-left text-[11px] hover:bg-rose-500/10"
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
    <div className={cn("rounded-2xl border p-4", isLight ? "border-indigo-200 bg-indigo-50/40" : "border-indigo-500/30 bg-indigo-500/10")}>
      <p className="mb-2 text-[10px] font-bold uppercase tracking-[0.18em] text-indigo-400">Operational insights</p>
      <div className="flex flex-wrap gap-2">
        {insights.map((ins) => (
          <Link
            key={ins.id}
            to={ins.action ?? "#"}
            className={cn(
              "rounded-xl border px-3 py-2 text-[11px] transition hover:-translate-y-0.5",
              ins.tone === "critical" ? "border-rose-500/40 bg-rose-500/10 text-rose-200" :
              ins.tone === "warn" ? "border-amber-500/40 bg-amber-500/10 text-amber-100" :
              "border-blue-500/30 bg-blue-500/10 text-blue-100",
            )}
          >
            <span className="font-semibold">{ins.title}</span>
            <span className="ml-2 opacity-80">{ins.detail}</span>
          </Link>
        ))}
      </div>
    </div>
  );
}
