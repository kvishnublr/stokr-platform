import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, ExternalLink, Power, RefreshCw, Shield, ShieldOff } from "lucide-react";
import { toast } from "sonner";
import {
  activateKillSwitch,
  deactivateKillSwitch,
  fetchKillSwitchStatus,
  fetchOmsDiagnostics,
  fetchOperationalDiagnostics,
  triggerNiftyGapFill,
  fetchStrategyValidationDiagnostics,
  fetchTradeReconciliationDiagnostics,
  redispatchOrphanSignals,
  regenerateCatalogSignal,
  safetyDiagnosticsQueryRetry,
  safetyDiagnosticsRetryDelay,
  setStrategyRedisToggle,
  type KillSwitchStatus,
  type OmsDiagnostics,
  type OperationalDiagnostics,
  type RedisStrategyToggleWarning,
  type SignalPipelineAdminActions,
  type StrategyRuntimeHealthRow,
  type TradeReconciliationDiagnostics,
} from "../../api/safetyDiagnostics";
import {
  AdminPageShell,
  AdminPanel,
  AdminSection,
} from "../../components/admin/institutional/AdminDesignSystem";
import { fmtDateTime } from "../../lib/dateUtils";
import { cn } from "../../lib/utils";
import { toneChipClasses } from "../../lib/statusTone";
import { parseAxiosMessage } from "../../api/client";
import { useSessionStore } from "../../state/session";
import { useUiThemeStore } from "../../state/uiTheme";
import { fetchPositionReconciliation } from "../../api/positionReconciliation";
import {
  PositionReconciliationLoadError,
  PositionReconciliationPanel,
} from "../../components/admin/PositionReconciliationPanel";

const OPS_QK = ["admin-operational-diagnostics"] as const;
const POSITION_RECON_QK = ["admin-position-reconciliation"] as const;
const OMS_QK = ["admin-oms-diagnostics"] as const;
const KS_QK = ["admin-kill-switch-status"] as const;

function formatBlockReason(code: string): string {
  const labels: Record<string, string> = {
    NIFTY_OPENING_INCOMPLETE: "NIFTY session warming up",
    FEED_STALE: "Market feed stale",
    EXECUTION_MODE_DISABLED: "Strategy disabled",
    STRATEGY_DISABLED: "Redis strategy toggle off (risk)",
    STARTUP_WARMUP: "Startup warmup",
    FEED_NOT_FRESH: "Feed not fresh",
    INSUFFICIENT_WARMUP_BARS: "Insufficient warmup bars",
  };
  return labels[code] ?? code.replace(/_/g, " ").toLowerCase();
}

function fmtVal(v: unknown): string {
  if (v == null) return "—";
  if (typeof v === "boolean") return v ? "YES" : "NO";
  if (typeof v === "number") return Number.isFinite(v) ? String(v) : "—";
  if (typeof v === "string") return v;
  return JSON.stringify(v);
}

function normalizeTradePairs(
  tradePairs: TradeReconciliationDiagnostics["tradePairs"] | undefined,
): Array<Record<string, unknown>> {
  if (Array.isArray(tradePairs)) {
    return tradePairs as Array<Record<string, unknown>>;
  }
  return (tradePairs?.pairs ?? []) as Array<Record<string, unknown>>;
}

function hasPairDriftColumns(rows: Array<Record<string, unknown>>): boolean {
  return rows.some(
    (d) =>
      d.slippageP50Bps != null
      || d.latencyP50Ms != null
      || d.winRateDelta != null,
  );
}

function MetricGrid({
  items,
  isLight,
}: {
  items: Array<{ label: string; value: unknown; warn?: boolean }>;
  isLight: boolean;
}) {
  return (
    <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
      {items.map((item) => (
        <div
          key={item.label}
          className={cn(
            "rounded-lg border px-3 py-2",
            item.warn
              ? isLight
                ? "border-rose-300 bg-rose-50"
                : "border-rose-500/40 bg-rose-500/10"
              : isLight
                ? "border-neutral-200 bg-neutral-50"
                : "border-neutral-800 bg-neutral-900/50",
          )}
        >
          <p className={cn("text-[10px] font-semibold uppercase tracking-wide", isLight ? "text-neutral-500" : "text-neutral-400")}>
            {item.label}
          </p>
          <p className={cn("mt-1 font-mono text-xs font-semibold", item.warn ? "text-rose-500" : isLight ? "text-neutral-900" : "text-neutral-100")}>
            {fmtVal(item.value)}
          </p>
        </div>
      ))}
    </div>
  );
}

function KillSwitchPanel({
  status,
  isLight,
  canControl,
  onActivate,
  onDeactivate,
  busy,
}: {
  status: KillSwitchStatus | undefined;
  isLight: boolean;
  canControl: boolean;
  onActivate: (reason: string, flatten: boolean) => void;
  onDeactivate: (reason: string) => void;
  busy: boolean;
}) {
  const [reason, setReason] = useState("");
  const [flatten, setFlatten] = useState(false);
  const [confirmActivate, setConfirmActivate] = useState(false);
  const active = Boolean(status?.active);

  return (
    <AdminPanel
      isLight={isLight}
      accent={active}
      title="Trading kill switch"
      subtitle="P3 OMS safety — forces PAPER mode and blocks LIVE order submission"
    >
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="flex items-center gap-3">
          {active ? (
            <ShieldOff className="h-8 w-8 text-rose-500" />
          ) : (
            <Shield className="h-8 w-8 text-emerald-500" />
          )}
          <div>
            <p className={cn("text-lg font-bold", active ? "text-rose-500" : "text-emerald-500")}>
              {active ? "ACTIVE — LIVE blocked" : "OFF — normal operations"}
            </p>
            {status?.lastEventAt ? (
              <p className={cn("text-xs", isLight ? "text-neutral-500" : "text-neutral-400")}>
                Last event {fmtDateTime(String(status.lastEventAt))}
                {status.lastEventSource ? ` · ${status.lastEventSource}` : ""}
              </p>
            ) : null}
            {status?.lastEventReason ? (
              <p className={cn("mt-1 text-xs", isLight ? "text-neutral-600" : "text-neutral-300")}>
                {status.lastEventReason}
              </p>
            ) : null}
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          <span className={cn("rounded-full border px-2 py-0.5 text-[10px] font-bold uppercase", toneChipClasses(isLight, active ? "critical" : "success"))}>
            Redis {status?.redisKillSwitch ? "ON" : "OFF"}
          </span>
          <span className={cn("rounded-full border px-2 py-0.5 text-[10px] font-bold uppercase", toneChipClasses(isLight, status?.configFlagEnabled ? "warn" : "neutral"))}>
            Config flag {status?.configFlagEnabled ? "ON" : "OFF"}
          </span>
          <span className={cn("rounded-full border px-2 py-0.5 text-[10px] font-bold uppercase", toneChipClasses(isLight, status?.forcesPaperMode ? "warn" : "neutral"))}>
            Forces PAPER {status?.forcesPaperMode ? "YES" : "NO"}
          </span>
        </div>
      </div>

      {canControl ? (
        <div className="mt-5 space-y-3 border-t pt-4 dark:border-neutral-800">
          <label className="block text-xs font-medium">
            Reason
            <input
              value={reason}
              onChange={(e) => { setReason(e.target.value); }}
              placeholder={active ? "Reason for deactivation" : "Reason for activation"}
              className={cn(
                "mt-1 w-full rounded-lg border px-3 py-2 text-sm",
                isLight ? "border-neutral-300 bg-white" : "border-neutral-700 bg-neutral-900",
              )}
            />
          </label>
          {!active ? (
            <label className="flex items-center gap-2 text-xs">
              <input type="checkbox" checked={flatten} onChange={(e) => { setFlatten(e.target.checked); }} />
              Flatten running strategies on activate
            </label>
          ) : null}
          <div className="flex flex-wrap gap-2">
            {!active ? (
              <>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => { setConfirmActivate(true); }}
                  className="inline-flex items-center gap-2 rounded-lg bg-rose-600 px-4 py-2 text-sm font-semibold text-white hover:bg-rose-700 disabled:opacity-50"
                >
                  <Power className="h-4 w-4" /> Activate kill switch
                </button>
                {confirmActivate ? (
                  <div className={cn("w-full rounded-lg border p-3", isLight ? "border-rose-300 bg-rose-50" : "border-rose-500/40 bg-rose-500/10")}>
                    <p className="mb-2 text-sm font-semibold text-rose-600">Confirm activation</p>
                    <p className="mb-3 text-xs text-rose-500">
                      This immediately blocks LIVE orders and forces PAPER mode platform-wide.
                      {flatten ? " Running strategy instances will be stopped." : ""}
                    </p>
                    <div className="flex gap-2">
                      <button
                        type="button"
                        disabled={busy}
                        onClick={() => {
                          onActivate(reason.trim() || "Admin manual activation", flatten);
                          setConfirmActivate(false);
                        }}
                        className="rounded-lg bg-rose-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-rose-700 disabled:opacity-50"
                      >
                        {busy ? "Activating…" : "Confirm activate"}
                      </button>
                      <button
                        type="button"
                        disabled={busy}
                        onClick={() => { setConfirmActivate(false); }}
                        className={cn("rounded-lg border px-3 py-1.5 text-xs", isLight ? "border-rose-300" : "border-rose-500/40")}
                      >
                        Cancel
                      </button>
                    </div>
                  </div>
                ) : null}
              </>
            ) : (
              <button
                type="button"
                disabled={busy}
                onClick={() => {
                  if (!confirm("Deactivate the global kill switch? LIVE orders may resume if other gates allow.")) return;
                  onDeactivate(reason.trim() || "Admin manual deactivation");
                }}
                className="inline-flex items-center gap-2 rounded-lg border border-emerald-500/50 bg-emerald-500/10 px-4 py-2 text-sm font-semibold text-emerald-600 hover:bg-emerald-500/20 disabled:opacity-50 dark:text-emerald-300"
              >
                <Shield className="h-4 w-4" /> Deactivate kill switch
              </button>
            )}
          </div>
        </div>
      ) : (
        <p className={cn("mt-4 text-xs", isLight ? "text-neutral-500" : "text-neutral-400")}>
          Kill switch controls require admin-only access (no trader role).
        </p>
      )}
    </AdminPanel>
  );
}

function StrategyHealthTable({
  rows,
  isLight,
}: {
  rows: StrategyRuntimeHealthRow[];
  isLight: boolean;
}) {
  if (!rows.length) {
    return <p className={cn("text-sm", isLight ? "text-neutral-500" : "text-neutral-400")}>No runtime health rows for today.</p>;
  }
  return (
    <div className="overflow-x-auto rounded-lg border dark:border-neutral-800">
      <table className="w-full text-xs">
        <thead className={isLight ? "bg-neutral-100" : "bg-neutral-900/80"}>
          <tr>
            {["Strategy", "Mode", "Scans", "Blocked", "Signals", "Trades", "Rejection", "Last scan"].map((h) => (
              <th key={h} className="px-2 py-2 text-left font-semibold text-muted-foreground">{h}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.strategyName} className="border-t dark:border-neutral-800">
              <td className="px-2 py-2 font-medium">{r.strategyName}</td>
              <td className="px-2 py-2">{r.executionMode}</td>
              <td className="px-2 py-2 font-mono">{r.scansAttempted}</td>
              <td className="px-2 py-2 font-mono text-amber-500">
                {r.scansBlockedIntegrity + r.scansBlockedFeed}
              </td>
              <td className="px-2 py-2 font-mono" title={r.signalsGenerated !== (r.signalsPersistedToday ?? r.signalsGenerated) ? `scanner counter ${r.signalsGenerated}` : undefined}>
                {r.signalsPersistedToday ?? r.signalsGenerated}
              </td>
              <td className="px-2 py-2 font-mono">{r.tradesOpened}/{r.tradesClosed}</td>
              <td className="px-2 py-2 font-mono">{r.rejectionRate ?? "—"}</td>
              <td className="px-2 py-2">{r.lastScanTime ? fmtDateTime(r.lastScanTime) : "—"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function SignalPipelineToolsPanel({
  actions,
  redisWarnings,
  isLight,
  onRedispatchOrphans,
  onRegenerateCatalog,
  onEnableStrategy,
  redispatchPending,
  regeneratePending,
  enablePending,
}: {
  actions?: SignalPipelineAdminActions;
  redisWarnings: RedisStrategyToggleWarning[];
  isLight: boolean;
  onRedispatchOrphans: () => void;
  onRegenerateCatalog: () => void;
  onEnableStrategy: (strategyKey: string) => void;
  redispatchPending: boolean;
  regeneratePending: boolean;
  enablePending: boolean;
}) {
  const ui = actions?.uiPages ?? {};
  const apiRows = [
    actions?.regenerateCatalogSignal,
    actions?.redispatchOrphanSignals,
    actions?.niftyGapFill,
    actions?.strategyRedisToggle,
    actions?.adminHealth,
  ].filter(Boolean);

  return (
    <AdminPanel
      isLight={isLight}
      title="Signal pipeline tools"
      subtitle="Admin APIs for catalog → OMS → broker (not Test Signal Lab). Use after deploy or when recovering orphans."
    >
      {redisWarnings.length > 0 ? (
        <div
          className={cn(
            "mb-4 rounded-lg border px-3 py-2",
            isLight ? "border-rose-300 bg-rose-50" : "border-rose-500/40 bg-rose-500/10",
          )}
        >
          <p className="text-xs font-semibold text-rose-600 dark:text-rose-300">
            LIVE strategies disabled in Redis — orders will fail risk with &quot;Strategy disabled&quot;
          </p>
          <ul className="mt-2 space-y-2">
            {redisWarnings.map((w) => (
              <li key={w.strategyKey} className="flex flex-wrap items-center justify-between gap-2 text-xs">
                <span className="font-mono font-semibold">{w.strategyKey}</span>
                <span className="text-muted-foreground">mode {w.configuredMode} · redis {String(w.redisOverride)}</span>
                <button
                  type="button"
                  disabled={enablePending}
                  onClick={() => onEnableStrategy(w.strategyKey)}
                  className="rounded border border-emerald-600/40 bg-emerald-50 px-2 py-0.5 text-[10px] font-semibold text-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-200"
                >
                  Enable in Redis
                </button>
              </li>
            ))}
          </ul>
        </div>
      ) : (
        <p className="mb-4 text-xs text-emerald-600 dark:text-emerald-400">
          All LIVE-validated strategy Redis toggles are on.
        </p>
      )}

      <div className="mb-4 flex flex-wrap gap-2">
        <button
          type="button"
          onClick={onRegenerateCatalog}
          disabled={regeneratePending}
          className="rounded-md border border-blue-200 bg-blue-50 px-3 py-1.5 text-xs font-semibold text-blue-800 hover:bg-blue-100 disabled:opacity-50 dark:border-blue-900/50 dark:bg-blue-950/40 dark:text-blue-200"
        >
          {regeneratePending ? "Regenerating…" : "Regenerate last catalog signal (LIVE)"}
        </button>
        <button
          type="button"
          onClick={onRedispatchOrphans}
          disabled={redispatchPending}
          className="rounded-md border border-amber-200 bg-amber-50 px-3 py-1.5 text-xs font-semibold text-amber-900 hover:bg-amber-100 disabled:opacity-50 dark:border-amber-900/50 dark:bg-amber-950/40 dark:text-amber-200"
        >
          {redispatchPending ? "Redispatching…" : "Redispatch today's orphan signals"}
        </button>
      </div>

      <div className="mb-4 flex flex-wrap gap-3 text-xs">
        {(
          [
            ["Safety & diagnostics", ui.safetyDiagnostics],
            ["Signal monitor", ui.signalMonitor],
            ["OMS monitor", ui.omsMonitor],
            ["Test signal lab", ui.testSignalLab],
            ["Command center", ui.commandCenter],
          ] as const
        )
          .filter((entry): entry is readonly [string, string] => Boolean(entry[1]))
          .map(([label, path]) => (
            <Link
              key={path}
              to={path}
              className="inline-flex items-center gap-1 font-medium text-blue-600 hover:underline dark:text-blue-400"
            >
              {label}
              <ExternalLink className="h-3 w-3" />
            </Link>
          ))}
      </div>

      <div className="overflow-x-auto rounded-lg border dark:border-neutral-800">
        <table className="w-full text-left text-[11px]">
          <thead className={isLight ? "bg-neutral-100" : "bg-neutral-900/80"}>
            <tr>
              <th className="px-2 py-1.5 font-semibold">Method</th>
              <th className="px-2 py-1.5 font-semibold">Path</th>
              <th className="px-2 py-1.5 font-semibold">Notes</th>
            </tr>
          </thead>
          <tbody>
            {apiRows.map((row) => (
              <tr key={row!.path} className="border-t dark:border-neutral-800">
                <td className="px-2 py-1.5 font-mono">{row!.method}</td>
                <td className="px-2 py-1.5 font-mono text-blue-700 dark:text-blue-300">
                  {row!.path}
                  {row!.query ? `?${row!.query}` : ""}
                </td>
                <td className="px-2 py-1.5 text-muted-foreground">{row!.description}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </AdminPanel>
  );
}

function OperationalPanel({
  data,
  isLight,
  onNiftyGapFill,
  gapFillPending,
  onRedispatchOrphans,
  onRegenerateCatalog,
  onEnableStrategy,
  redispatchPending,
  regeneratePending,
  enableStrategyPending,
}: {
  data: OperationalDiagnostics;
  isLight: boolean;
  onNiftyGapFill: () => void;
  gapFillPending: boolean;
  onRedispatchOrphans: () => void;
  onRegenerateCatalog: () => void;
  onEnableStrategy: (strategyKey: string) => void;
  redispatchPending: boolean;
  regeneratePending: boolean;
  enableStrategyPending: boolean;
}) {
  const feed = data.feedHealth ?? {};
  const integrity = data.marketDataIntegrity ?? {};
  const startup = data.safeStartup ?? {};
  const feedLevel = String(feed.level ?? "UNKNOWN");
  const feedWarn = feedLevel !== "OK" && feedLevel !== "IDLE";
  const niftyBlocked = integrity.openingSessionReady === false && integrity.midSessionRecoveryAllowed === false;

  const redisWarnings = data.redisStrategyToggleWarnings ?? [];

  return (
    <AdminSection isLight={isLight} title="P2 operational diagnostics" subtitle="Feed health, safe startup gate, strategy runtime health">
      <div className="space-y-4">
        <SignalPipelineToolsPanel
          actions={data.signalPipelineAdminActions}
          redisWarnings={redisWarnings}
          isLight={isLight}
          onRedispatchOrphans={onRedispatchOrphans}
          onRegenerateCatalog={onRegenerateCatalog}
          onEnableStrategy={onEnableStrategy}
          redispatchPending={redispatchPending}
          regeneratePending={regeneratePending}
          enablePending={enableStrategyPending}
        />

        <AdminPanel isLight={isLight} title="Feed health" subtitle={`Level: ${feedLevel}`}>
          <MetricGrid
            isLight={isLight}
            items={[
              { label: "WebSocket", value: feed.websocketConnected },
              { label: "Equity gap (s)", value: feed.equityGapSeconds, warn: Boolean(feed.equityStale) },
              { label: "Index gap (s)", value: feed.indexGapSeconds, warn: Boolean(feed.indexStale) },
              { label: "Tick gap (s)", value: feed.tickGapSeconds, warn: Boolean(feed.tickStale) },
              { label: "Stale incidents", value: feed.staleFeedIncidents, warn: Number(feed.staleFeedIncidents) > 0 },
              { label: "Outage seconds", value: feed.totalOutageSeconds },
              { label: "Reconnect attempts", value: feed.reconnectAttempts },
              { label: "Level", value: feedLevel, warn: feedWarn },
            ]}
          />
        </AdminPanel>

        <AdminPanel
          isLight={isLight}
          title="NIFTY session integrity"
          subtitle={niftyBlocked ? "Index strategies may be blocked until bars recover" : "Opening or mid-session recovery active"}
        >
          <MetricGrid
            isLight={isLight}
            items={[
              { label: "Opening ready", value: integrity.openingSessionReady, warn: integrity.openingSessionReady === false },
              { label: "Mid-session OK", value: integrity.midSessionRecoveryAllowed, warn: integrity.midSessionRecoveryAllowed === false },
              { label: "Session bars", value: integrity.sessionBarCount },
              { label: "Min bars needed", value: integrity.midSessionMinBars },
            ]}
          />
          {niftyBlocked ? (
            <button
              type="button"
              onClick={onNiftyGapFill}
              disabled={gapFillPending}
              className="mt-3 rounded-md border border-blue-200 bg-blue-50 px-3 py-1.5 text-xs font-semibold text-blue-700 hover:bg-blue-100 disabled:opacity-50 dark:border-blue-900/50 dark:bg-blue-950/40 dark:text-blue-300"
            >
              {gapFillPending ? "Filling…" : "Fill NIFTY gaps now"}
            </button>
          ) : null}
        </AdminPanel>

        <AdminPanel isLight={isLight} title="Safe startup gate">
          <MetricGrid
            isLight={isLight}
            items={[
              { label: "Ready", value: startup.ready, warn: startup.ready === false },
              { label: "Block reason", value: startup.blockReason, warn: Boolean(startup.blockReason) },
              { label: "Min warmup (s)", value: startup.minWarmupSeconds },
              { label: "Started at", value: startup.startedAt ? fmtDateTime(String(startup.startedAt)) : "—" },
            ]}
          />
        </AdminPanel>

        <div className="grid gap-4 md:grid-cols-3">
          <AdminPanel isLight={isLight} title="Session counters">
            <MetricGrid
              isLight={isLight}
              items={[
                { label: "Active trades", value: data.activeTrades },
                { label: "Integrity failures", value: data.integrityFailuresToday, warn: data.integrityFailuresToday > 0 },
                { label: "Blocked strategies", value: data.blockedStrategies.length, warn: data.blockedStrategies.length > 0 },
              ]}
            />
          </AdminPanel>
          <AdminPanel isLight={isLight} title="Blocked now" className="md:col-span-2" subtitle="Live gate — clears when feed and NIFTY session recover">
            {data.blockedStrategies.length ? (
              <ul className="space-y-1.5 text-xs">
                {data.blockedStrategies.map((b) => (
                  <li
                    key={`${b.strategyName}-${b.reason}`}
                    className="flex items-center justify-between gap-2 rounded-md border px-2.5 py-1.5 dark:border-neutral-800"
                  >
                    <span className="font-semibold">{b.strategyName.replace(/_/g, " ")}</span>
                    <span className="rounded bg-amber-50 px-2 py-0.5 text-[10px] font-medium text-amber-800 dark:bg-amber-950/40 dark:text-amber-200">
                      {formatBlockReason(b.reason)}
                    </span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-sm text-muted-foreground">All strategies clear to scan.</p>
            )}
          </AdminPanel>
        </div>

        {data.staleSymbols.length > 0 ? (
          <AdminPanel isLight={isLight} title="Stale symbols (sample)">
            <MetricGrid
              isLight={isLight}
              items={data.staleSymbols.map((s) => ({
                label: String(s.symbol),
                value: `${s.lagSeconds ?? "?"}s lag`,
                warn: true,
              }))}
            />
          </AdminPanel>
        ) : null}

        <AdminPanel isLight={isLight} title="Strategy runtime health (today)" subtitle="Signals = persisted to DB (matches Signal Monitor). Trades = opened/closed outcomes.">
          <StrategyHealthTable rows={data.strategyRuntimeHealth ?? []} isLight={isLight} />
        </AdminPanel>
      </div>
    </AdminSection>
  );
}

function OmsPanel({ data, isLight }: { data: OmsDiagnostics; isLight: boolean }) {
  const broker = data.brokerConnection ?? {};
  const limits = data.activeLimits ?? {};
  const mcp = data.marketCloseProtection ?? {};
  const dedupe = data.duplicatePrevention ?? { dedupeWindowSeconds: 0, activeKeysTracked: 0 };
  const latency = data.executionLatency ?? { avgAckLatencyMsLast24h: 0, telemetryEventsLast24h: 0 };

  return (
    <AdminSection isLight={isLight} title="P3 OMS safety diagnostics" subtitle="Broker protection, exposure limits, dedupe, execution telemetry">
      <div className="space-y-4">
        <AdminPanel isLight={isLight} title="Broker connection">
          <MetricGrid
            isLight={isLight}
            items={[
              { label: "Global halt", value: broker.globalHalt, warn: Boolean(broker.globalHalt) },
              { label: "Platform feed degraded", value: broker.platformFeedDegraded, warn: Boolean(broker.platformFeedDegraded) },
              { label: "Trader broker degraded", value: broker.traderBrokerDegraded, warn: Boolean(broker.traderBrokerDegraded) },
              { label: "LIVE orders blocked now", value: broker.liveOrdersBlocked, warn: Boolean(broker.liveOrdersBlocked) },
              { label: "Execution user (LIVE)", value: broker.executionUserId ?? "—" },
              { label: "Block LIVE on disconnect", value: broker.blockLiveOnDisconnectEnabled },
              { label: "Flatten on disconnect", value: broker.flattenOnDisconnect },
              { label: "WebSocket state", value: broker.websocketState },
              { label: "Last tick", value: broker.lastTickAt ? fmtDateTime(String(broker.lastTickAt)) : "—" },
            ]}
          />
        </AdminPanel>

        <div className="grid gap-4 lg:grid-cols-2">
          <AdminPanel isLight={isLight} title="Exposure limits (configured)">
            <MetricGrid
              isLight={isLight}
              items={Object.entries(limits).map(([label, value]) => ({
                label: label.replace(/([A-Z])/g, " $1").trim(),
                value,
                warn: label === "activateKillSwitchOnBreach" && Boolean(value),
              }))}
            />
          </AdminPanel>
          <AdminPanel isLight={isLight} title="Market close protection">
            <MetricGrid
              isLight={isLight}
              items={[
                { label: "No new entries after", value: mcp.noNewEntriesAfter },
                { label: "Flatten time", value: mcp.flattenTime },
                { label: "Blocks LIVE now", value: mcp.blocksNewLiveEntriesNow, warn: Boolean(mcp.blocksNewLiveEntriesNow) },
              ]}
            />
          </AdminPanel>
        </div>

        <div className="grid gap-4 md:grid-cols-3">
          <AdminPanel isLight={isLight} title="Order safety (24h)">
            <MetricGrid
              isLight={isLight}
              items={[
                { label: "Blocked orders", value: data.blockedOrdersLast24h, warn: data.blockedOrdersLast24h > 0 },
                { label: "Dedupe window (s)", value: dedupe.dedupeWindowSeconds },
                { label: "Active dedupe keys", value: dedupe.activeKeysTracked },
              ]}
            />
          </AdminPanel>
          <AdminPanel isLight={isLight} title="Execution latency (24h)">
            <MetricGrid
              isLight={isLight}
              items={[
                { label: "Avg ACK latency (ms)", value: latency.avgAckLatencyMsLast24h },
                { label: "Telemetry events", value: latency.telemetryEventsLast24h },
              ]}
            />
          </AdminPanel>
          {data.dailyPnl ? (
            <AdminPanel isLight={isLight} title="Daily PnL snapshot">
              <MetricGrid
                isLight={isLight}
                items={[
                  { label: "Today MTM", value: data.dailyPnl.todayMtm },
                  { label: "Open positions", value: data.dailyPnl.openPositionCount },
                ]}
              />
            </AdminPanel>
          ) : null}
        </div>
      </div>
    </AdminSection>
  );
}

function TradeReconciliationPanel({
  data,
  isLight,
}: {
  data: TradeReconciliationDiagnostics;
  isLight: boolean;
}) {
  const safety = data.safetyScan ?? {};
  const driftToday = data.driftAnalytics?.today ?? [];
  const driftMeta = data.driftAnalytics?.meta;
  const guardrails = data.promotionGuardrails?.strategies ?? [];
  const pairs = normalizeTradePairs(data.tradePairs);
  const showSignalFallback = driftToday.length > 0 && !hasPairDriftColumns(driftToday);
  const unreconciled = data.unreconciled ?? [];
  const failures = data.reconciliationFailures ?? [];

  return (
    <AdminSection
      isLight={isLight}
      title="Paper vs live reconciliation"
      subtitle="Lifecycle pairs, PnL drift, slippage, promotion guardrails, and safety alerts"
    >
      <div className="space-y-4">
        <MetricGrid
          isLight={isLight}
          items={[
            { label: "Unreconciled", value: safety.unreconciledCount, warn: (safety.unreconciledCount ?? 0) > 0 },
            { label: "Failed reconciliations", value: safety.failedCount, warn: (safety.failedCount ?? 0) > 0 },
            { label: "Safety alerts", value: safety.alertCount, warn: (safety.alertCount ?? 0) > 0 },
            { label: "Trade pairs tracked", value: pairs.length },
          ]}
        />

        {guardrails.length > 0 ? (
          <AdminPanel isLight={isLight} title="Promotion guardrails">
            <div className="overflow-x-auto">
              <table className="w-full text-xs">
                <thead className={isLight ? "bg-neutral-100" : "bg-neutral-900/80"}>
                  <tr>
                    {["Strategy", "Sample", "Allowed", "Blockers"].map((h) => (
                      <th key={h} className="px-2 py-2 text-left font-semibold text-muted-foreground">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {guardrails.map((g) => (
                    <tr key={g.strategyKey} className="border-t dark:border-neutral-800">
                      <td className="px-2 py-2 font-medium">{g.strategyKey}</td>
                      <td className="px-2 py-2 font-mono">{g.sampleSize ?? "—"}</td>
                      <td className="px-2 py-2">{g.promotionAllowed ? "YES" : "NO"}</td>
                      <td className="px-2 py-2 text-rose-500">{(g.blockers ?? []).join("; ") || "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </AdminPanel>
        ) : null}

        {driftToday.length > 0 ? (
          <AdminPanel isLight={isLight} title="Drift analytics (today)">
            {showSignalFallback ? (
              <p className="mb-3 text-xs text-muted-foreground">
                {driftMeta?.pairMetricsRequireBothMode
                  ?? "Pair drift (slippage, latency, win Δ) needs BOTH-mode paper+LIVE orders reconciled today."}
                {" "}
                Reconciled pairs today: {fmtVal(driftMeta?.reconciledPairCountToday ?? pairs.length)}.
                Signal-level degradation and OMS stats shown where pair metrics are unavailable.
              </p>
            ) : null}
            <div className="overflow-x-auto">
              <table className="w-full text-xs">
                <thead className={isLight ? "bg-neutral-100" : "bg-neutral-900/80"}>
                  <tr>
                    {(showSignalFallback
                      ? ["Strategy", "Degradation", "Signals", "OMS reject %", "Integrity %", "Win %"]
                      : ["Strategy", "Degradation", "Slippage P50", "Latency P50", "Underperf %", "Win Δ"]
                    ).map((h) => (
                      <th key={h} className="px-2 py-2 text-left font-semibold text-muted-foreground">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {driftToday.map((d) => (
                    <tr key={String(d.strategyKey)} className="border-t dark:border-neutral-800">
                      <td className="px-2 py-2 font-medium">{String(d.strategyKey ?? "—")}</td>
                      <td className="px-2 py-2 font-mono">{fmtVal(d.strategyDegradationScore)}</td>
                      {showSignalFallback ? (
                        <>
                          <td className="px-2 py-2 font-mono">{fmtVal(d.signalsGenerated)}</td>
                          <td className="px-2 py-2 font-mono">{fmtVal(d.omsRejectRate)}</td>
                          <td className="px-2 py-2 font-mono">{fmtVal(d.integrityRejectionPct)}</td>
                          <td className="px-2 py-2 font-mono">{fmtVal(d.winRate)}</td>
                        </>
                      ) : (
                        <>
                          <td className="px-2 py-2 font-mono">{fmtVal(d.slippageP50Bps)}</td>
                          <td className="px-2 py-2 font-mono">{fmtVal(d.latencyP50Ms)}</td>
                          <td className="px-2 py-2 font-mono">{fmtVal(d.liveUnderperformancePct)}</td>
                          <td className="px-2 py-2 font-mono">{fmtVal(d.winRateDelta)}</td>
                        </>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </AdminPanel>
        ) : null}

        <AdminPanel isLight={isLight} title="Recent paper/live trade pairs">
          <TradePairTable rows={pairs.slice(0, 15)} isLight={isLight} emptyHint={
            pairs.length === 0
              ? "No paper/live pairs yet. Pairs are created when BOTH-mode strategies dispatch a LIVE leg "
                + "(paired with PAPER) and fills reconcile in execution_comparison_metrics. "
                + "Today there are 0 LIVE OMS orders on prod until go-live strategies fire in BOTH mode."
              : undefined
          } />
        </AdminPanel>

        {(unreconciled.length > 0 || failures.length > 0) ? (
          <AdminPanel isLight={isLight} accent title="Reconciliation issues">
            {unreconciled.length > 0 ? (
              <>
                <p className="mb-2 text-xs font-semibold text-amber-500">Unreconciled ({unreconciled.length})</p>
                <TradePairTable rows={unreconciled.slice(0, 10)} isLight={isLight} />
              </>
            ) : null}
            {failures.length > 0 ? (
              <>
                <p className="mb-2 mt-4 text-xs font-semibold text-rose-500">Failures ({failures.length})</p>
                <TradePairTable rows={failures.slice(0, 10)} isLight={isLight} />
              </>
            ) : null}
          </AdminPanel>
        ) : null}
      </div>
    </AdminSection>
  );
}

function TradePairTable({
  rows,
  isLight,
  emptyHint,
}: {
  rows: Array<Record<string, unknown>>;
  isLight: boolean;
  emptyHint?: string;
}) {
  if (rows.length === 0) {
    return (
      <p className="text-xs text-muted-foreground">
        {emptyHint ?? "No trade pairs yet."}
      </p>
    );
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-xs">
        <thead className={isLight ? "bg-neutral-100" : "bg-neutral-900/80"}>
          <tr>
            {["Strategy", "Symbol", "Status", "Paper PnL", "Live PnL", "PnL drift", "Hold drift", "Slippage %"].map((h) => (
              <th key={h} className="px-2 py-2 text-left font-semibold text-muted-foreground">{h}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r, idx) => (
            <tr key={String(r.signalId ?? idx)} className="border-t dark:border-neutral-800">
              <td className="px-2 py-2 font-medium">{fmtVal(r.strategyKey)}</td>
              <td className="px-2 py-2">{fmtVal(r.symbol)}</td>
              <td className="px-2 py-2">{fmtVal(r.reconciliationStatus)}</td>
              <td className="px-2 py-2 font-mono">{fmtVal(r.paperRealizedPnl)}</td>
              <td className="px-2 py-2 font-mono">{fmtVal(r.liveRealizedPnl)}</td>
              <td className="px-2 py-2 font-mono">{fmtVal(r.pnlDrift)}</td>
              <td className="px-2 py-2 font-mono">{fmtVal(r.holdTimeDrift)}</td>
              <td className="px-2 py-2 font-mono">{fmtVal(r.slippageDivergencePct)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function PanelLoadError({
  label,
  error,
  onRetry,
  isLight,
}: {
  label: string;
  error: unknown;
  onRetry: () => void;
  isLight: boolean;
}) {
  return (
    <div
      className={cn(
        "flex flex-wrap items-center justify-between gap-3 rounded-lg border px-4 py-3 text-sm",
        isLight ? "border-amber-300 bg-amber-50 text-amber-900" : "border-amber-500/40 bg-amber-500/10 text-amber-100",
      )}
    >
      <div className="flex items-center gap-2">
        <AlertTriangle className="h-4 w-4 shrink-0" />
        <span>
          {label}: {parseAxiosMessage(error)}
        </span>
      </div>
      <button
        type="button"
        onClick={onRetry}
        className={cn("rounded-md border px-2 py-1 text-xs font-bold", isLight ? "border-amber-400" : "border-amber-600")}
      >
        Retry
      </button>
    </div>
  );
}

export function AdminSafetyDiagnosticsPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const qc = useQueryClient();
  const userId = useSessionStore((s) => s.userId);
  const canControlKillSwitch = useSessionStore((s) => s.canAccessKillSwitchOperations());

  const [initialGateExpired, setInitialGateExpired] = useState(false);
  useEffect(() => {
    const t = window.setTimeout(() => setInitialGateExpired(true), 2500);
    return () => window.clearTimeout(t);
  }, []);

  const queryRetry = { retry: safetyDiagnosticsQueryRetry, retryDelay: safetyDiagnosticsRetryDelay };

  const opsQ = useQuery({
    queryKey: OPS_QK,
    queryFn: fetchOperationalDiagnostics,
    refetchInterval: 20_000,
    ...queryRetry,
  });

  const omsQ = useQuery({
    queryKey: [...OMS_QK, userId],
    queryFn: () => fetchOmsDiagnostics(userId || undefined),
    refetchInterval: 20_000,
    ...queryRetry,
  });

  const ksQ = useQuery({
    queryKey: KS_QK,
    queryFn: fetchKillSwitchStatus,
    refetchInterval: 15_000,
    ...queryRetry,
  });

  const validationQ = useQuery({
    queryKey: ["admin-strategy-validation-diagnostics"],
    queryFn: fetchStrategyValidationDiagnostics,
    refetchInterval: 30_000,
    ...queryRetry,
  });

  const reconciliationQ = useQuery({
    queryKey: ["admin-trade-reconciliation-diagnostics"],
    queryFn: fetchTradeReconciliationDiagnostics,
    refetchInterval: 30_000,
    ...queryRetry,
  });

  const positionReconQ = useQuery({
    queryKey: POSITION_RECON_QK,
    queryFn: fetchPositionReconciliation,
    refetchInterval: 30_000,
    ...queryRetry,
  });

  const activateMut = useMutation({
    mutationFn: ({ reason, flatten }: { reason: string; flatten: boolean }) => activateKillSwitch(reason, flatten),
    onSuccess: (data) => {
      toast.success(data.active ? "Kill switch activated" : "Kill switch updated");
      void qc.invalidateQueries({ queryKey: KS_QK });
      void qc.invalidateQueries({ queryKey: OMS_QK });
      void qc.invalidateQueries({ queryKey: OPS_QK });
    },
    onError: () => { toast.error("Failed to activate kill switch"); },
  });

  const deactivateMut = useMutation({
    mutationFn: (reason: string) => deactivateKillSwitch(reason),
    onSuccess: () => {
      toast.success("Kill switch deactivated");
      void qc.invalidateQueries({ queryKey: KS_QK });
      void qc.invalidateQueries({ queryKey: OMS_QK });
      void qc.invalidateQueries({ queryKey: OPS_QK });
    },
    onError: () => { toast.error("Failed to deactivate kill switch"); },
  });

  const niftyGapFillMut = useMutation({
    mutationFn: triggerNiftyGapFill,
    onSuccess: () => {
      toast.success("NIFTY gap fill triggered");
      void qc.invalidateQueries({ queryKey: OPS_QK });
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const redispatchOrphansMut = useMutation({
    mutationFn: redispatchOrphanSignals,
    onSuccess: (data) => {
      toast.success(`Orphan redispatch: ${String(data.redispatched ?? 0)} signal(s)`);
      void qc.invalidateQueries({ queryKey: OPS_QK });
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const regenerateCatalogMut = useMutation({
    mutationFn: () => regenerateCatalogSignal({ preferLive: true }),
    onSuccess: (data) => {
      toast.success(`Regenerated ${data.strategy ?? "signal"} ${data.symbol ?? ""} → ${data.newSignalId ?? ""}`);
      void qc.invalidateQueries({ queryKey: OPS_QK });
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const enableStrategyMut = useMutation({
    mutationFn: ({ strategyKey, enabled }: { strategyKey: string; enabled: boolean }) =>
      setStrategyRedisToggle(strategyKey, enabled),
    onSuccess: () => {
      toast.success("Strategy Redis toggle updated");
      void qc.invalidateQueries({ queryKey: OPS_QK });
    },
    onError: (e) => toast.error(parseAxiosMessage(e)),
  });

  const killActive = Boolean(ksQ.data?.active ?? omsQ.data?.killSwitch?.active);
  const blockingLoad =
    !initialGateExpired && !opsQ.data && !omsQ.data && !ksQ.data && (opsQ.isFetching || omsQ.isFetching);
  const updatedAt = Math.max(opsQ.dataUpdatedAt, omsQ.dataUpdatedAt, ksQ.dataUpdatedAt, ksQ.dataUpdatedAt);

  if (blockingLoad) {
    return (
      <AdminPageShell isLight={isLight} title="Safety & Diagnostics" subtitle="Loading operational and OMS safety telemetry…">
        <div className="flex items-center gap-2 text-muted-foreground">
          <RefreshCw className="h-4 w-4 animate-spin" />
          Loading diagnostics…
        </div>
      </AdminPageShell>
    );
  }

  return (
    <AdminPageShell
      isLight={isLight}
      eyebrow="Safety & observability"
      title="Safety & Diagnostics"
      subtitle="P2 operational health (feed, startup gate, strategy runtime) and P3 OMS safety layer (kill switch, exposure, broker protection)."
      actions={
        <div className="flex items-center gap-2">
          <span className="text-xs text-muted-foreground">
            Updated {updatedAt ? fmtDateTime(new Date(updatedAt).toISOString()) : "—"}
          </span>
          <button
            type="button"
            onClick={() => {
              void opsQ.refetch();
              void omsQ.refetch();
              void ksQ.refetch();
              void validationQ.refetch();
              void reconciliationQ.refetch();
            }}
            className={cn("rounded-lg border px-2 py-1 text-xs", isLight ? "border-neutral-300" : "border-neutral-700")}
          >
            <RefreshCw className="inline h-3.5 w-3.5" /> Refresh
          </button>
        </div>
      }
      alert={
        killActive ? (
          <div className="flex items-center gap-3 rounded-xl border border-rose-500/40 bg-rose-500/10 px-4 py-3 text-sm text-rose-100">
            <AlertTriangle className="h-5 w-5 shrink-0" />
            <span>Global kill switch is ACTIVE — LIVE orders are blocked and execution is forced to PAPER mode.</span>
          </div>
        ) : undefined
      }
    >
      <div className="space-y-8">
        <KillSwitchPanel
          status={ksQ.data ?? omsQ.data?.killSwitch}
          isLight={isLight}
          canControl={canControlKillSwitch}
          busy={activateMut.isPending || deactivateMut.isPending}
          onActivate={(reason, flatten) => { activateMut.mutate({ reason, flatten }); }}
          onDeactivate={(reason) => { deactivateMut.mutate(reason); }}
        />

        {opsQ.isError && !opsQ.data ? (
          <PanelLoadError label="Operational diagnostics" error={opsQ.error} onRetry={() => void opsQ.refetch()} isLight={isLight} />
        ) : null}
        {opsQ.data ? (
          <OperationalPanel
            data={opsQ.data}
            isLight={isLight}
            gapFillPending={niftyGapFillMut.isPending}
            onNiftyGapFill={() => niftyGapFillMut.mutate()}
            redispatchPending={redispatchOrphansMut.isPending}
            regeneratePending={regenerateCatalogMut.isPending}
            enableStrategyPending={enableStrategyMut.isPending}
            onRedispatchOrphans={() => redispatchOrphansMut.mutate()}
            onRegenerateCatalog={() => regenerateCatalogMut.mutate()}
            onEnableStrategy={(strategyKey) => enableStrategyMut.mutate({ strategyKey, enabled: true })}
          />
        ) : null}

        {omsQ.isError && !omsQ.data ? (
          <PanelLoadError label="OMS diagnostics" error={omsQ.error} onRetry={() => void omsQ.refetch()} isLight={isLight} />
        ) : null}
        {omsQ.data ? <OmsPanel data={omsQ.data} isLight={isLight} /> : null}

        {validationQ.data ? (
          <AdminSection
            isLight={isLight}
            title="Strategy validation & capital"
            subtitle="Lifecycle stage (e.g. LIVE_SHADOW) tracks promotion metrics only — it does not block broker orders. Execution mode (LIVE/PAPER/BOTH) controls OMS routing. Global config (user_id null). FIXED_QUANTITY uses fixed_qty / max_positions."
          >
            <div className="overflow-x-auto rounded-lg border dark:border-neutral-800">
              <table className="w-full text-xs">
                <thead className={isLight ? "bg-neutral-100" : "bg-neutral-900/80"}>
                  <tr>
                    {[
                      "Strategy",
                      "Lifecycle stage",
                      "Execution mode",
                      "Sizing",
                      "Fixed qty",
                      "Max pos",
                      "Open",
                      "Alloc ₹",
                      "Avail ₹",
                      "Util %",
                      "Reservations",
                    ].map((h) => (
                      <th key={h} className="px-2 py-2 text-left font-semibold text-muted-foreground">{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {validationQ.data.strategies.map((s) => {
                    const cfg = s.executionConfig ?? {};
                    const cap = s.capitalState ?? {};
                    const sizingMode = String(cfg.sizingMode ?? cap.sizingMode ?? "");
                    const isFixedQty =
                      sizingMode === "FIXED_QUANTITY" || cfg.forceFixedQty === true || cap.forceFixedQty === true;
                    const fixedQty = cfg.fixedQty ?? cap.fixedQty ?? cap.configuredTradeQty;
                    const maxPos = cfg.maxPositions ?? cap.maxPositions;
                    const allocNum = Number(cap.allocatedCapital ?? cfg.allocatedCapital ?? 0);
                    const showCapital = !isFixedQty || allocNum > 0;
                    const utilDisplay = isFixedQty && !showCapital
                      ? cap.positionSlotUtilPct != null
                        ? `${cap.positionSlotUtilPct}% slots`
                        : "—"
                      : String(cap.utilizationPct ?? "—");
                    return (
                      <tr key={s.strategyKey} className="border-t dark:border-neutral-800">
                        <td className="px-2 py-2 font-medium">{s.strategyKey}</td>
                        <td className="px-2 py-2" title="Promotion/metrics stage — not OMS execution mode">
                          {s.validationStatus}
                        </td>
                        <td className="px-2 py-2 font-medium" title="OMS/broker routing mode">
                          {String(cfg.executionMode ?? "—")}
                        </td>
                        <td className="px-2 py-2">{sizingMode || "—"}</td>
                        <td className="px-2 py-2 font-mono">{isFixedQty ? String(fixedQty ?? "—") : "—"}</td>
                        <td className="px-2 py-2 font-mono">{maxPos != null ? String(maxPos) : "—"}</td>
                        <td className="px-2 py-2 font-mono">{cap.openPositions != null ? String(cap.openPositions) : "0"}</td>
                        <td className="px-2 py-2 font-mono">{showCapital ? String(cap.allocatedCapital ?? "—") : "—"}</td>
                        <td className="px-2 py-2 font-mono">{showCapital ? String(cap.availableCapital ?? "—") : "—"}</td>
                        <td className="px-2 py-2 font-mono">{utilDisplay}</td>
                        <td className="px-2 py-2 font-mono">{s.activeReservations ?? 0}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </AdminSection>
        ) : null}

        {positionReconQ.isError ? (
          <PositionReconciliationLoadError
            error={positionReconQ.error}
            onRetry={() => positionReconQ.refetch()}
            isLight={isLight}
          />
        ) : null}

        {positionReconQ.data ? (
          <PositionReconciliationPanel
            data={positionReconQ.data}
            isLight={isLight}
            queryKey={POSITION_RECON_QK}
          />
        ) : null}

        {reconciliationQ.data ? (
          <TradeReconciliationPanel data={reconciliationQ.data} isLight={isLight} />
        ) : null}

        {opsQ.isFetching || omsQ.isFetching || positionReconQ.isFetching ? (
          <p className={cn("text-xs", isLight ? "text-neutral-500" : "text-neutral-400")}>
            Refreshing diagnostics…
          </p>
        ) : null}
      </div>
    </AdminPageShell>
  );
}
