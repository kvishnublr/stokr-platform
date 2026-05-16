import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client";
import { WorkspaceTabPanel, WorkspaceTabs } from "../components/ds/WorkspaceTabs";
import { useUiThemeStore } from "../state/uiTheme";
import { cn } from "../lib/utils";

type Workstation = {
  accountSummary: {
    totalPnl: string;
    realizedPnl: string;
    unrealizedPnl: string;
    openPositions: number;
    activeStrategies: number;
    brokerConnectionState: string;
    executionMode: string;
  };
  badges: string[];
  openPositions: Array<Record<string, unknown>>;
  closedPositions: Array<Record<string, unknown>>;
  orders: Array<Record<string, unknown>>;
  executions: Array<Record<string, unknown>>;
  strategyAllocations: Array<Record<string, unknown>>;
  riskControls: {
    reconciliationWarnings: string[];
    parityState: string;
    tokenValid: boolean;
    brokerHealth: string;
    liveEligible: boolean;
  };
  latestSignals: Array<Record<string, unknown>>;
};

const TABS = [
  { id: "open", label: "Open Positions" },
  { id: "closed", label: "Closed Positions" },
  { id: "orders", label: "Orders" },
  { id: "execs", label: "Executions" },
  { id: "alloc", label: "Strategy Allocations" },
  { id: "risk", label: "Risk Controls" },
];

function fmt(v: unknown) {
  if (v == null) return "-";
  if (typeof v === "number") return v.toFixed(2);
  return String(v);
}

function badgeTone(v: string) {
  const x = v.toUpperCase();
  if (x.includes("READY") || x.includes("SYNCED") || x.includes("CONNECTED") || x === "LIVE") return "ok";
  if (x.includes("PENDING") || x.includes("PARTIAL") || x.includes("PAUSED") || x === "PAPER") return "warn";
  if (x.includes("MISMATCH") || x.includes("BLOCKED") || x.includes("DISCONNECTED")) return "bad";
  return "neutral";
}

function Chip({ value }: { value: string }) {
  const tone = badgeTone(value);
  const cls =
    tone === "ok"
      ? "border-emerald-300/60 bg-emerald-50 text-emerald-800 dark:border-emerald-900 dark:bg-emerald-950/40 dark:text-emerald-300"
      : tone === "warn"
        ? "border-amber-300/60 bg-amber-50 text-amber-800 dark:border-amber-900 dark:bg-amber-950/40 dark:text-amber-300"
        : tone === "bad"
          ? "border-rose-300/60 bg-rose-50 text-rose-800 dark:border-rose-900 dark:bg-rose-950/40 dark:text-rose-300"
          : "border-neutral-300/60 bg-white text-neutral-700 dark:border-neutral-700 dark:bg-neutral-900 dark:text-neutral-300";
  return <span className={cn("rounded-md border px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide", cls)}>{value}</span>;
}

export function TerminalPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const [tab, setTab] = useState("open");
  const [controlResult, setControlResult] = useState<Record<string, unknown> | null>(null);
  const [controlError, setControlError] = useState<string | null>(null);
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: ["trader-workstation"],
    queryFn: async () => (await api.get("/api/trader/terminal/workstation")).data?.data as Workstation,
    staleTime: 2_000,
    refetchInterval: 5_000,
  });

  const controlMutation = useMutation({
    mutationFn: async (action: string) => {
      setControlError(null);
      const preview = (await api.get(`/api/trader/terminal/control/preview?action=${encodeURIComponent(action)}`)).data?.data;
      if (!preview?.supported) {
        throw new Error(String(preview?.reason ?? "Action not supported"));
      }
      const impact = preview?.impact ?? {};
      const ok = window.confirm(
        `Confirm ${action}?\n` +
        `Pending orders: ${impact.pendingOrderCount ?? 0}\n` +
        `Running strategies: ${impact.runningStrategyCount ?? 0}\n` +
        `Open positions: ${impact.openPositionCount ?? 0}`
      );
      if (!ok) {
        return null;
      }
      const exec = (await api.post("/api/trader/terminal/control/execute", {
        action,
        confirmationToken: preview.confirmationToken,
      })).data?.data;
      return exec;
    },
    onSuccess: (res) => {
      setControlResult(res ?? null);
      qc.invalidateQueries({ queryKey: ["trader-workstation"] });
    },
    onError: (err: unknown) => {
      const msg = err instanceof Error ? err.message : "Action failed";
      setControlError(msg);
    },
  });

  const sum = q.data?.accountSummary;
  const open = q.data?.openPositions ?? [];
  const closed = q.data?.closedPositions ?? [];
  const orders = q.data?.orders ?? [];
  const execs = q.data?.executions ?? [];
  const alloc = q.data?.strategyAllocations ?? [];
  const risk = q.data?.riskControls;
  const signals = q.data?.latestSignals ?? [];

  const riskBlock = useMemo(() => {
    if (!risk) return false;
    return !risk.tokenValid || risk.parityState === "MISMATCH" || (risk.reconciliationWarnings?.length ?? 0) > 0;
  }, [risk]);

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className={cn("text-2xl font-semibold tracking-tight", isLight ? "text-neutral-900" : "text-white")}>Trader Execution Workstation</h1>
          <div className="mt-2 flex flex-wrap gap-2">
            {(q.data?.badges ?? []).map((b) => <Chip key={b} value={b} />)}
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            onClick={() => controlMutation.mutate("PAUSE_TRADING")}
            className="rounded-lg border border-neutral-300 px-3 py-1.5 text-xs font-semibold dark:border-neutral-700"
          >
            Pause Trading
          </button>
          <button
            type="button"
            onClick={() => controlMutation.mutate("RESUME_TRADING")}
            className="rounded-lg border border-neutral-300 px-3 py-1.5 text-xs font-semibold dark:border-neutral-700"
          >
            Resume Trading
          </button>
          <button
            type="button"
            onClick={() => controlMutation.mutate("CANCEL_ALL_PENDING")}
            className="rounded-lg border border-neutral-300 px-3 py-1.5 text-xs font-semibold dark:border-neutral-700"
          >
            Cancel All Pending
          </button>
          <button
            type="button"
            onClick={() => controlMutation.mutate("FLATTEN_POSITIONS")}
            className="rounded-lg border border-neutral-300 px-3 py-1.5 text-xs font-semibold dark:border-neutral-700"
          >
            Flatten Positions
          </button>
          <button
            type="button"
            onClick={() => controlMutation.mutate("EXIT_ALL")}
            className="rounded-lg border border-neutral-300 px-3 py-1.5 text-xs font-semibold dark:border-neutral-700"
          >
            Exit All
          </button>
          <button
            type="button"
            onClick={() => controlMutation.mutate("DISABLE_NEW_ENTRIES")}
            className="rounded-lg border border-neutral-300 px-3 py-1.5 text-xs font-semibold dark:border-neutral-700"
          >
            Disable New Entries
          </button>
          <button
            type="button"
            onClick={() => controlMutation.mutate("EMERGENCY_KILL_SWITCH")}
            className="rounded-lg border border-rose-300 bg-rose-50 px-3 py-1.5 text-xs font-semibold text-rose-700 dark:border-rose-900 dark:bg-rose-950/40 dark:text-rose-300"
          >
            Emergency Kill Switch
          </button>
        </div>
      </div>

      <div className="grid gap-3 md:grid-cols-3 xl:grid-cols-6">
        <Metric title="Total PnL" value={fmt(sum?.totalPnl)} />
        <Metric title="Realized" value={fmt(sum?.realizedPnl)} />
        <Metric title="Unrealized" value={fmt(sum?.unrealizedPnl)} />
        <Metric title="Open Positions" value={fmt(sum?.openPositions)} />
        <Metric title="Active Strategies" value={fmt(sum?.activeStrategies)} />
        <Metric title="Execution Mode" value={fmt(sum?.executionMode)} />
      </div>

      {riskBlock ? (
        <div className="rounded-xl border border-rose-300 bg-rose-50 px-4 py-3 text-sm text-rose-800 dark:border-rose-900 dark:bg-rose-950/40 dark:text-rose-300">
          Execution warning: parity/token/risk blocker detected. Review Risk Controls before new entries.
        </div>
      ) : null}
      {controlError ? (
        <div className="rounded-xl border border-rose-300 bg-rose-50 px-4 py-3 text-sm text-rose-800 dark:border-rose-900 dark:bg-rose-950/40 dark:text-rose-300">
          Control action failed: {controlError}
        </div>
      ) : null}
      {controlResult ? (
        <div className={cn("rounded-2xl border p-4", isLight ? "border-neutral-200 bg-white" : "border-neutral-800 bg-neutral-950/60")}>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="text-sm font-semibold">Control Result</div>
            <div className="flex items-center gap-2">
              <Chip value={fmt(controlResult.action).toUpperCase()} />
              <Chip value={Boolean(controlResult.ok) ? "SUCCESS" : "FAILED"} />
              <button
                type="button"
                onClick={() => setControlResult(null)}
                className="rounded-md border border-neutral-300 px-2 py-1 text-[10px] font-semibold uppercase tracking-wide dark:border-neutral-700"
              >
                Dismiss
              </button>
            </div>
          </div>
          <div className="mt-3 grid gap-3 md:grid-cols-3">
            <Metric title="Orders Updated" value={fmt(controlResult.ordersUpdated)} />
            <Metric title="Strategies Paused" value={fmt(controlResult.strategiesPaused)} />
            <Metric title="Executed At" value={fmt(controlResult.executedAt)} />
          </div>
          {Array.isArray(controlResult.notes) && (controlResult.notes as unknown[]).length > 0 ? (
            <div className="mt-3 rounded-xl border border-amber-300/60 bg-amber-50 p-3 text-xs text-amber-900 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-300">
              <div className="font-semibold uppercase tracking-wide">Notes</div>
              <ul className="mt-2 list-disc pl-5 space-y-1">
                {(controlResult.notes as unknown[]).map((n, i) => <li key={`${i}`}>{fmt(n)}</li>)}
              </ul>
            </div>
          ) : null}
          {Array.isArray(controlResult.flattenResults) && (controlResult.flattenResults as unknown[]).length > 0 ? (
            <div className="mt-3">
              <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-neutral-500">Per-Symbol Exit Results</div>
              <Table
                rows={controlResult.flattenResults as Array<Record<string, unknown>>}
                cols={["symbol", "side", "qty", "orderId", "state", "mode", "error"]}
              />
            </div>
          ) : null}
        </div>
      ) : null}

      <div className={cn("rounded-2xl border p-4", isLight ? "border-neutral-200 bg-white" : "border-neutral-800 bg-neutral-950/60")}>
        <WorkspaceTabs tabs={TABS} active={tab} onChange={setTab} />

        <WorkspaceTabPanel id="open" active={tab}>
          <Table
            rows={open}
            cols={["symbol", "side", "qty", "avgPrice", "ltp", "mtmPnl", "realizedPnl", "unrealizedPnl", "exposurePct", "parityState", "executionMode", "brokerStatus", "currentSignalState"]}
          />
        </WorkspaceTabPanel>

        <WorkspaceTabPanel id="closed" active={tab}>
          <Table rows={closed} cols={["symbol", "side", "qty", "avgPrice", "mtmPnl", "realizedPnl", "exitReason", "parityState"]} />
        </WorkspaceTabPanel>

        <WorkspaceTabPanel id="orders" active={tab}>
          <Table rows={orders} cols={["createdAt", "symbol", "side", "state", "executionMode", "strategyKey", "quantity", "rejectReason"]} />
        </WorkspaceTabPanel>

        <WorkspaceTabPanel id="execs" active={tab}>
          <Table rows={execs} cols={["createdAt", "symbol", "filledQty", "avgPrice", "latencyMs", "slippageBps", "executionMode", "orderState"]} />
        </WorkspaceTabPanel>

        <WorkspaceTabPanel id="alloc" active={tab}>
          <Table rows={alloc} cols={["strategyName", "strategyKey", "symbol", "runtimeState", "executionMode", "allocationAmount", "riskMultiplier", "maxDailyLoss"]} />
        </WorkspaceTabPanel>

        <WorkspaceTabPanel id="risk" active={tab}>
          <div className="mt-4 grid gap-4 lg:grid-cols-2">
            <div className={cn("rounded-xl border p-3", isLight ? "border-neutral-200" : "border-neutral-800")}>
              <div className="text-xs font-semibold uppercase tracking-wide text-neutral-500">Risk Status</div>
              <div className="mt-3 flex flex-wrap gap-2">
                <Chip value={`PARITY_${risk?.parityState ?? "UNKNOWN"}`} />
                <Chip value={risk?.tokenValid ? "TOKEN_VALID" : "TOKEN_INVALID"} />
                <Chip value={risk?.liveEligible ? "LIVE_ELIGIBLE" : "LIVE_BLOCKED"} />
              </div>
              <div className="mt-3 text-xs text-neutral-500">Broker health: {fmt(risk?.brokerHealth)}</div>
            </div>
            <div className={cn("rounded-xl border p-3", isLight ? "border-neutral-200" : "border-neutral-800")}>
              <div className="text-xs font-semibold uppercase tracking-wide text-neutral-500">Latest Signals</div>
              <div className="mt-2 max-h-56 overflow-auto space-y-2">
                {signals.map((s) => (
                  <div key={String(s.id)} className={cn("rounded-lg border px-2 py-1 text-xs", isLight ? "border-neutral-200" : "border-neutral-800")}>
                    <div className="font-medium">{fmt(s.symbol)} · {fmt(s.signalType)}</div>
                    <div className="text-neutral-500">{fmt(s.strategyName)} · {fmt(s.createdAt)}</div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </WorkspaceTabPanel>
      </div>
    </div>
  );
}

function Metric({ title, value }: { title: string; value: string }) {
  return (
    <div className="rounded-xl border border-neutral-200 bg-white p-3 dark:border-neutral-800 dark:bg-neutral-950/60">
      <div className="text-[11px] uppercase tracking-wide text-neutral-500">{title}</div>
      <div className="mt-1 font-mono text-base font-semibold text-neutral-900 dark:text-neutral-100">{value}</div>
    </div>
  );
}

function Table({ rows, cols }: { rows: Array<Record<string, unknown>>; cols: string[] }) {
  return (
    <div className="mt-4 overflow-x-auto">
      <table className="w-full text-left text-xs">
        <thead className="text-neutral-500">
          <tr>
            {cols.map((c) => (
              <th key={c} className="pb-2 pr-3 uppercase tracking-wide">{c}</th>
            ))}
          </tr>
        </thead>
        <tbody className="text-neutral-700 dark:text-neutral-200">
          {rows.map((r, i) => (
            <tr key={String(r.id ?? `${i}`)} className="border-t border-neutral-200/80 dark:border-neutral-800">
              {cols.map((c) => (
                <td key={c} className="py-2 pr-3 font-mono">
                  {c.toLowerCase().includes("state") || c.toLowerCase().includes("mode") || c.toLowerCase().includes("status") ? (
                    <Chip value={fmt(r[c]).toUpperCase()} />
                  ) : (
                    fmt(r[c])
                  )}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {rows.length === 0 ? <div className="py-6 text-center text-sm text-neutral-500">No records</div> : null}
    </div>
  );
}
