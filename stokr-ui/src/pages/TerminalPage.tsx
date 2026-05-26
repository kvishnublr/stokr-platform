import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { RefreshCw } from "lucide-react";
import { api, parseAxiosMessage } from "../api/client";
import { WorkspaceTabPanel, WorkspaceTabs } from "../components/ds/WorkspaceTabs";
import { useUiThemeStore } from "../state/uiTheme";
import { cn } from "../lib/utils";
import { fmtDateTime, fmtNseClock } from "../lib/dateUtils";
import { extractAccountPnl, formatInr, sumPositionsPnl } from "../lib/moneyUtils";

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
  executionGuardEvents?: Array<Record<string, unknown>>;
  executionQualityMetrics?: Array<Record<string, unknown>>;
  executionTimeline?: Array<Record<string, unknown>>;
  executionQualityScore?: Record<string, unknown>;
};

const TABS = [
  { id: "open", label: "Open Positions" },
  { id: "closed", label: "Closed Positions" },
  { id: "orders", label: "Orders" },
  { id: "execs", label: "Executions" },
  { id: "alloc", label: "Strategy Allocations" },
  { id: "risk", label: "Risk Controls" },
  { id: "guard", label: "Execution Guard" },
];

function fmt(v: unknown) {
  if (v == null || v === "") return "—";
  if (typeof v === "number") return v.toFixed(2);
  return String(v);
}

function fmtCell(v: unknown, col: string) {
  if (col === "createdAt" || col === "executedAt" || col === "eventTime" || col === "emittedAt" || col === "openTime") {
    return fmtDateTime(String(v ?? ""));
  }
  return fmt(v);
}

function severityTone(v: string) {
  const x = v.toUpperCase();
  if (x.includes("CRITICAL") || x.includes("HARD_FAIL") || x.includes("REJECT")) return "bad";
  if (x.includes("WARNING") || x.includes("SOFT_FAIL")) return "warn";
  return "ok";
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
  const [tradeSymbol, setTradeSymbol] = useState("INFY");
  const [tradeQty, setTradeQty] = useState(1);
  const [tradeSide, setTradeSide] = useState<"BUY" | "SELL">("BUY");
  const [tradeVariety, setTradeVariety] = useState<"REGULAR" | "AMO">("REGULAR");
  const [tradeExchange, setTradeExchange] = useState<"NSE" | "BSE">("NSE");
  const [tradeProduct, setTradeProduct] = useState<"CNC" | "MIS">("CNC");
  const [guardStream, setGuardStream] = useState<Array<Record<string, unknown>>>([]);
  const [tradeResult, setTradeResult] = useState<Record<string, unknown> | null>(null);
  const [tradeError, setTradeError] = useState<string | null>(null);
  const [pendingOrderCancel, setPendingOrderCancel] = useState<{
    source: string;
    orderId?: string | null;
    brokerOrderId?: string | null;
    variety?: string | null;
    symbol?: string | null;
    side?: string | null;
    state?: string | null;
    quantity?: string | null;
  } | null>(null);
  const [orderScope, setOrderScope] = useState<"ALL" | "OPEN" | "EXECUTED" | "REJECTED">("ALL");
  const [confirmState, setConfirmState] = useState<{
    action: string;
    token: string;
    impact: Record<string, unknown>;
  } | null>(null);
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: ["trader-workstation"],
    queryFn: async () => (await api.get("/api/trader/terminal/workstation")).data?.data as Workstation,
    staleTime: 2_000,
    refetchInterval: 5_000,
    retry: 2,
  });

  const wsError = q.isError ? parseAxiosMessage(q.error) : null;

  useEffect(() => {
    const token = localStorage.getItem("accessToken");
    if (!token) return;
    const ctrl = new AbortController();
    let cancelled = false;
    const run = async () => {
      const res = await fetch("/api/trader/terminal/execution-guard/stream", {
        headers: { Authorization: `Bearer ${token}`, Accept: "text/event-stream" },
        signal: ctrl.signal,
      });
      if (!res.ok || !res.body) return;
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      while (!cancelled) {
        const chunk = await reader.read();
        if (chunk.done) break;
        buffer += decoder.decode(chunk.value, { stream: true });
        let idx;
        while ((idx = buffer.indexOf("\n\n")) >= 0) {
          const frame = buffer.slice(0, idx).trim();
          buffer = buffer.slice(idx + 2);
          if (!frame) continue;
          const data = frame.split("\n").find((l) => l.startsWith("data:"));
          if (!data) continue;
          try {
            const parsed = JSON.parse(data.slice(5).trim()) as Record<string, unknown>;
            setGuardStream((prev) => [parsed, ...prev].slice(0, 100));
          } catch {
            // ignore malformed event frame
          }
        }
      }
    };
    void run();
    return () => {
      cancelled = true;
      ctrl.abort();
    };
  }, []);

  const controlMutation = useMutation({
    mutationFn: async (action: string) => {
      setControlError(null);
      const preview = (await api.get(`/api/trader/terminal/control/preview?action=${encodeURIComponent(action)}`)).data?.data;
      if (!preview?.supported) {
        throw new Error(String(preview?.reason ?? "Action not supported"));
      }
      setConfirmState({
        action,
        token: String(preview.confirmationToken ?? ""),
        impact: (preview?.impact ?? {}) as Record<string, unknown>,
      });
      return null;
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

  const executeConfirmedAction = useMutation({
    mutationFn: async (payload: { action: string; token: string }) => {
      const exec = (await api.post("/api/trader/terminal/control/execute", {
        action: payload.action,
        confirmationToken: payload.token,
      })).data?.data;
      return exec;
    },
    onSuccess: (res) => {
      setControlError(null);
      setControlResult(res ?? null);
      setConfirmState(null);
      qc.invalidateQueries({ queryKey: ["trader-workstation"] });
    },
    onError: (err: unknown) => {
      const msg = err instanceof Error ? err.message : "Action failed";
      setControlError(msg);
    },
  });

  const sampleTradeMutation = useMutation({
    mutationFn: async () => {
      const payload = {
        variety: tradeVariety,
        exchange: tradeExchange,
        tradingsymbol: tradeSymbol.trim().toUpperCase(),
        side: tradeSide,
        quantity: Math.max(1, Math.min(5, Math.floor(tradeQty))),
        orderType: "MARKET",
        product: tradeProduct,
      };
      const res = await api.post("/api/trader/broker/test-order", payload);
      return { payload, response: res.data?.data as Record<string, unknown> };
    },
    onSuccess: (x) => {
      setTradeError(null);
      setTradeResult({
        submitted: x.payload,
        ...x.response,
      });
    },
    onError: (err: unknown) => {
      const msg = err instanceof Error ? err.message : "Sample trade failed";
      setTradeError(msg);
      setTradeResult({
        submitted: {
          variety: tradeVariety,
          exchange: tradeExchange,
          tradingsymbol: tradeSymbol.trim().toUpperCase(),
          side: tradeSide,
          quantity: Math.max(1, Math.min(5, Math.floor(tradeQty))),
          orderType: "MARKET",
          product: tradeProduct,
        },
      });
    },
  });

  const cancelOrderMutation = useMutation({
    mutationFn: async (payload: { source: string; orderId?: string | null; brokerOrderId?: string | null; variety?: string | null }) => {
      if (payload.source === "OMS" && payload.orderId) {
        return (await api.post(`/api/trader/terminal/orders/${encodeURIComponent(payload.orderId)}/cancel`)).data?.data;
      }
      if (payload.source === "BROKER" && payload.brokerOrderId) {
        return (await api.post("/api/trader/broker/cancel-order", {
          orderId: payload.brokerOrderId,
          variety: payload.variety ?? "regular",
        })).data?.data;
      }
      throw new Error("Order cannot be cancelled from this row.");
    },
    onSuccess: (res) => {
      setControlError(null);
      setControlResult({
        action: "CANCEL_ORDER",
        ok: true,
        notes: [String(res?.message ?? "Cancel requested")],
        executedAt: new Date().toISOString(),
      });
      qc.invalidateQueries({ queryKey: ["trader-workstation"] });
    },
    onError: (err: unknown) => {
      const msg = err instanceof Error ? err.message : "Cancel failed";
      setControlError(msg);
    },
  });

  const sum = q.data?.accountSummary;
  const open = q.data?.openPositions ?? [];
  const accountPnl = useMemo(() => {
    const fromSummary = extractAccountPnl(
      sum as Record<string, unknown> | undefined,
      typeof sum?.openPositions === "number" ? sum.openPositions : null,
    );
    const fromRows = sumPositionsPnl(open);
    return {
      mtm: fromSummary.mtm ?? fromRows.mtm,
      unrealized: fromSummary.unrealized ?? fromRows.unrealized,
      realized: fromSummary.realized ?? fromRows.realized,
    };
  }, [sum, open]);
  const closed = q.data?.closedPositions ?? [];
  const orders = q.data?.orders ?? [];
  const execs = q.data?.executions ?? [];
  const alloc = q.data?.strategyAllocations ?? [];
  const risk = q.data?.riskControls;
  const signals = q.data?.latestSignals ?? [];
  const guardEvents = q.data?.executionGuardEvents ?? [];
  const guardQuality = q.data?.executionQualityMetrics ?? [];
  const guardTimeline = q.data?.executionTimeline ?? [];
  const guardScore = q.data?.executionQualityScore ?? {};
  const filteredOrders = useMemo(() => {
    const classify = (stateRaw: unknown): "OPEN" | "EXECUTED" | "REJECTED" => {
      const s = String(stateRaw ?? "").toUpperCase();
      if (s.includes("REJECT") || s.includes("CANCEL") || s.includes("FAIL") || s.includes("ERROR")) {
        return "REJECTED";
      }
      if (s.includes("COMPLETE") || s.includes("FILLED") || s.includes("EXECUTED") || s.includes("TRADED")) {
        return "EXECUTED";
      }
      return "OPEN";
    };
    if (orderScope === "ALL") return orders;
    return orders.filter((r) => classify(r.state) === orderScope);
  }, [orders, orderScope]);

  const riskBlock = useMemo(() => {
    if (!risk) return false;
    return !risk.tokenValid || risk.parityState === "MISMATCH" || (risk.reconciliationWarnings?.length ?? 0) > 0;
  }, [risk]);

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <p className={cn("text-[11px] font-semibold uppercase tracking-widest", isLight ? "text-neutral-500" : "text-neutral-400")}>
            Analytics · {fmtNseClock()}
          </p>
          <h1 className={cn("text-2xl font-semibold tracking-tight", isLight ? "text-neutral-900" : "text-white")}>
            Trader Execution Workstation
          </h1>
          <div className="mt-2 flex flex-wrap gap-2">
            {(q.isLoading ? ["LOADING…"] : q.data?.badges ?? []).map((b) => <Chip key={b} value={b} />)}
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

      {wsError ? (
        <div className={cn(
          "flex flex-wrap items-center justify-between gap-3 rounded-xl border px-4 py-3 text-sm",
          isLight ? "border-rose-200 bg-rose-50 text-rose-800" : "border-rose-900 bg-rose-950/40 text-rose-300",
        )}>
          <span>Could not load workstation data: {wsError}</span>
          <button
            type="button"
            onClick={() => void q.refetch()}
            disabled={q.isFetching}
            className={cn(
              "inline-flex items-center gap-1.5 rounded-lg border px-3 py-1.5 text-xs font-semibold",
              isLight ? "border-rose-300 bg-white hover:bg-rose-100" : "border-rose-800 hover:bg-rose-900/60",
            )}
          >
            <RefreshCw className={cn("h-3.5 w-3.5", q.isFetching && "animate-spin")} />
            Retry
          </button>
        </div>
      ) : null}

      <div className="grid gap-3 md:grid-cols-3 xl:grid-cols-6">
        <Metric title="MTM P&L" value={q.isLoading ? "…" : formatInr(accountPnl.mtm ?? 0)} loading={q.isLoading} pnlValue={accountPnl.mtm} />
        <Metric title="Realized P&L" value={q.isLoading ? "…" : formatInr(accountPnl.realized ?? 0)} loading={q.isLoading} pnlValue={accountPnl.realized} />
        <Metric title="Unrealized P&L" value={q.isLoading ? "…" : formatInr(accountPnl.unrealized ?? 0)} loading={q.isLoading} pnlValue={accountPnl.unrealized} />
        <Metric title="Open Positions" value={q.isLoading ? "…" : fmt(sum?.openPositions)} loading={q.isLoading} />
        <Metric title="Active Strategies" value={q.isLoading ? "…" : fmt(sum?.activeStrategies)} loading={q.isLoading} />
        <Metric title="Execution Mode" value={q.isLoading ? "…" : fmt(sum?.executionMode)} loading={q.isLoading} />
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
        <WorkspaceTabs tabs={TABS} active={tab} onChange={setTab} variant={isLight ? "light" : "dark"} />

        <WorkspaceTabPanel id="open" active={tab}>
          <Table
            rows={open}
            loading={q.isLoading}
            emptyLabel="No open positions"
            cols={["symbol", "side", "qty", "avgPrice", "ltp", "mtmPnl", "realizedPnl", "unrealizedPnl", "exposurePct", "parityState", "executionMode", "brokerStatus", "currentSignalState"]}
          />
        </WorkspaceTabPanel>

        <WorkspaceTabPanel id="closed" active={tab}>
          <Table rows={closed} cols={["symbol", "side", "qty", "avgPrice", "mtmPnl", "realizedPnl", "exitReason", "parityState"]} />
        </WorkspaceTabPanel>

        <WorkspaceTabPanel id="orders" active={tab}>
          <div className="mt-2 mb-2 flex flex-wrap items-center gap-2">
            {(["ALL", "OPEN", "EXECUTED", "REJECTED"] as const).map((scope) => (
              <button
                key={scope}
                type="button"
                onClick={() => setOrderScope(scope)}
                className={cn(
                  "rounded-full border px-3 py-1 text-[11px] font-semibold",
                  orderScope === scope
                    ? "border-neutral-900 bg-neutral-900 text-white dark:border-white dark:bg-white dark:text-neutral-900"
                    : "border-neutral-300 bg-white text-neutral-600 hover:bg-neutral-100 dark:border-neutral-700 dark:bg-neutral-900 dark:text-neutral-300 dark:hover:bg-neutral-800",
                )}
              >
                {scope}
              </button>
            ))}
            <span className="text-[11px] text-neutral-500">Showing {filteredOrders.length} of {orders.length}</span>
          </div>
          <OrdersTable rows={filteredOrders} onCancel={(r) => setPendingOrderCancel(r)} cancelling={cancelOrderMutation.isPending} />
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
                    <div className="font-medium">{fmt(s.symbol)} - {fmt(s.signalType)}</div>
                    <div className="text-neutral-500">{fmt(s.strategyName)} - {fmt(s.createdAt)}</div>
                  </div>
                ))}
              </div>
            </div>
          </div>
          <div className="mt-4 grid gap-4 lg:grid-cols-2">
            <div className={cn("rounded-xl border p-3", isLight ? "border-neutral-200" : "border-neutral-800")}>
              <div className="text-xs font-semibold uppercase tracking-wide text-neutral-500">Sample Trade (Terminal)</div>
              <div className="mt-3 grid grid-cols-2 gap-2 text-xs">
                <label className="col-span-1">
                  Symbol
                  <input
                    value={tradeSymbol}
                    onChange={(e) => setTradeSymbol(e.target.value)}
                    className="mt-1 w-full rounded-md border border-neutral-300 px-2 py-1 dark:border-neutral-700 dark:bg-neutral-900"
                  />
                </label>
                <label className="col-span-1">
                  Qty (1-5)
                  <input
                    type="number"
                    min={1}
                    max={5}
                    step={1}
                    value={tradeQty}
                    onChange={(e) => setTradeQty(Number(e.target.value))}
                    className="mt-1 w-full rounded-md border border-neutral-300 px-2 py-1 dark:border-neutral-700 dark:bg-neutral-900"
                  />
                </label>
                <label className="col-span-1">
                  Variety
                  <select
                    value={tradeVariety}
                    onChange={(e) => setTradeVariety(e.target.value as "REGULAR" | "AMO")}
                    className="mt-1 w-full rounded-md border border-neutral-300 px-2 py-1 dark:border-neutral-700 dark:bg-neutral-900"
                  >
                    <option value="REGULAR">REGULAR</option>
                    <option value="AMO">AMO</option>
                  </select>
                </label>
                <label className="col-span-1">
                  Exchange
                  <select
                    value={tradeExchange}
                    onChange={(e) => setTradeExchange(e.target.value as "NSE" | "BSE")}
                    className="mt-1 w-full rounded-md border border-neutral-300 px-2 py-1 dark:border-neutral-700 dark:bg-neutral-900"
                  >
                    <option value="NSE">NSE</option>
                    <option value="BSE">BSE</option>
                  </select>
                </label>
                <label className="col-span-1">
                  Side
                  <select
                    value={tradeSide}
                    onChange={(e) => setTradeSide(e.target.value as "BUY" | "SELL")}
                    className="mt-1 w-full rounded-md border border-neutral-300 px-2 py-1 dark:border-neutral-700 dark:bg-neutral-900"
                  >
                    <option value="BUY">BUY</option>
                    <option value="SELL">SELL</option>
                  </select>
                </label>
                <label className="col-span-1">
                  Product
                  <select
                    value={tradeProduct}
                    onChange={(e) => setTradeProduct(e.target.value as "CNC" | "MIS")}
                    className="mt-1 w-full rounded-md border border-neutral-300 px-2 py-1 dark:border-neutral-700 dark:bg-neutral-900"
                  >
                    <option value="CNC">CNC</option>
                    <option value="MIS">MIS</option>
                  </select>
                </label>
              </div>
              <div className="mt-3 flex gap-2">
                <button
                  type="button"
                  onClick={() => sampleTradeMutation.mutate()}
                  disabled={sampleTradeMutation.isPending}
                  className="rounded-md border border-sky-300 bg-sky-50 px-3 py-1.5 text-xs font-semibold text-sky-800 disabled:opacity-50 dark:border-sky-900 dark:bg-sky-950/30 dark:text-sky-300"
                >
                  {sampleTradeMutation.isPending ? "Submitting..." : "Submit Sample Trade"}
                </button>
              </div>
            </div>
            <div className={cn("rounded-xl border p-3", isLight ? "border-neutral-200" : "border-neutral-800")}>
              <div className="text-xs font-semibold uppercase tracking-wide text-neutral-500">Sample Trade Result</div>
              {tradeError ? (
                <div className="mt-2 rounded-md border border-rose-300 bg-rose-50 px-2 py-1 text-xs text-rose-800 dark:border-rose-900 dark:bg-rose-950/30 dark:text-rose-300">
                  {tradeError}
                </div>
              ) : null}
              <div className="mt-2 space-y-1 text-xs">
                <div>Submitted variety: <span className="font-mono">{fmt((tradeResult?.submitted as Record<string, unknown> | undefined)?.variety)}</span></div>
                <div>Symbol: <span className="font-mono">{fmt((tradeResult?.submitted as Record<string, unknown> | undefined)?.tradingsymbol)}</span></div>
                <div>Status: <span className="font-mono">{fmt(tradeResult?.status)}</span></div>
                <div>Order ID: <span className="font-mono">{fmt(tradeResult?.orderId)}</span></div>
                <div>Message: <span className="font-mono">{fmt(tradeResult?.message)}</span></div>
                <div>Raw status: <span className="font-mono">{fmt(tradeResult?.rawStatus)}</span></div>
              </div>
            </div>
          </div>
        </WorkspaceTabPanel>

        <WorkspaceTabPanel id="guard" active={tab}>
          <div className="mt-4 grid gap-3 md:grid-cols-3">
            <Metric title="Guard Events" value={fmt(guardEvents.length)} />
            <Metric title="Hard Fails" value={fmt(guardEvents.filter((x) => String(x.outcome ?? "").toUpperCase() === "HARD_FAIL").length)} />
            <Metric title="Soft Fails" value={fmt(guardEvents.filter((x) => String(x.outcome ?? "").toUpperCase() === "SOFT_FAIL").length)} />
            <Metric title="Quality Score" value={fmt(guardScore.score)} />
          </div>
          <div className="mt-4 grid gap-4 xl:grid-cols-2">
            <div>
              <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-neutral-500">Guard Rejections and Outcomes</div>
              <Table
                rows={guardEvents.map((x) => ({
                  createdAt: x.createdAt,
                  symbol: x.symbol,
                  strategyKey: x.strategyKey,
                  guardMode: x.guardMode,
                  outcome: x.outcome,
                  severity: x.severity,
                  reason: x.reason,
                }))}
                cols={["createdAt", "symbol", "strategyKey", "guardMode", "outcome", "severity", "reason"]}
              />
            </div>
            <div>
              <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-neutral-500">Execution Quality</div>
              <Table
                rows={guardQuality.map((x) => ({
                  createdAt: x.createdAt,
                  symbol: x.symbol,
                  executionMode: x.executionMode,
                  expectedPrice: x.expectedPrice,
                  actualFillPrice: x.actualFillPrice,
                  realizedSlippagePct: x.realizedSlippagePct,
                  fillLatencyMs: x.fillLatencyMs,
                  spreadAtExecution: x.spreadAtExecution,
                  volatilityAtExecution: x.volatilityAtExecution,
                }))}
                cols={["createdAt", "symbol", "executionMode", "expectedPrice", "actualFillPrice", "realizedSlippagePct", "fillLatencyMs", "spreadAtExecution", "volatilityAtExecution"]}
              />
            </div>
          </div>
          <div className="mt-4 rounded-xl border border-neutral-200 bg-neutral-50 p-3 text-xs dark:border-neutral-800 dark:bg-neutral-900/50">
            <div className="mb-2 font-semibold uppercase tracking-wide text-neutral-500">Severity Legend</div>
            <div className="flex flex-wrap gap-2">
              <span className={cn("rounded-md border px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide",
                severityTone("CRITICAL") === "bad"
                  ? "border-rose-300/60 bg-rose-50 text-rose-700 dark:border-rose-900 dark:bg-rose-950/40 dark:text-rose-300"
                  : ""
              )}>CRITICAL</span>
              <span className={cn("rounded-md border px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide",
                severityTone("WARNING") === "warn"
                  ? "border-amber-300/60 bg-amber-50 text-amber-800 dark:border-amber-900 dark:bg-amber-950/40 dark:text-amber-300"
                  : ""
              )}>WARNING</span>
              <span className="rounded-md border border-emerald-300/60 bg-emerald-50 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-emerald-800 dark:border-emerald-900 dark:bg-emerald-950/40 dark:text-emerald-300">INFO</span>
            </div>
          </div>
          <div className="mt-4">
            <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-neutral-500">Realtime Guard Stream</div>
            <Table
              rows={guardStream.map((e) => ({
                emittedAt: e.emittedAt,
                severity: e.severity,
                outcome: e.outcome,
                symbol: e.symbol,
                strategyKey: e.strategyKey,
                reason: (e.payload as Record<string, unknown> | undefined)?.reason,
              }))}
              cols={["emittedAt", "severity", "outcome", "symbol", "strategyKey", "reason"]}
            />
          </div>
          <div className="mt-4">
            <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-neutral-500">Execution Timeline</div>
            <Table
              rows={guardTimeline}
              cols={["eventTime", "eventType", "symbol", "strategyKey", "guardOutcome", "severity", "latencyMs"]}
            />
          </div>
        </WorkspaceTabPanel>
      </div>

      {confirmState ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 px-4">
          <div className={cn("w-full max-w-xl rounded-2xl border p-5 shadow-2xl", isLight ? "border-neutral-200 bg-white" : "border-neutral-800 bg-neutral-950")}>
            <div className="flex items-start justify-between gap-3">
              <div>
                <div className={cn("text-[11px] font-semibold uppercase tracking-[0.14em]", isLight ? "text-neutral-500" : "text-neutral-400")}>Confirm Action</div>
                <h3 className={cn("mt-1 text-xl font-semibold tracking-tight", isLight ? "text-neutral-900" : "text-white")}>
                  {confirmState.action.replaceAll("_", " ")}
                </h3>
              </div>
              <Chip value={confirmState.action.includes("KILL") ? "HIGH IMPACT" : "OPERATIONAL"} />
            </div>

            <div className={cn("mt-4 rounded-xl border p-3", isLight ? "border-neutral-200 bg-neutral-50" : "border-neutral-800 bg-neutral-900/80")}>
              <div className="grid grid-cols-3 gap-3">
                <Metric title="Pending Orders" value={fmt(confirmState.impact.pendingOrderCount)} />
                <Metric title="Running Strategies" value={fmt(confirmState.impact.runningStrategyCount)} />
                <Metric title="Open Positions" value={fmt(confirmState.impact.openPositionCount)} />
              </div>
            </div>

            <p className={cn("mt-3 text-xs", isLight ? "text-neutral-600" : "text-neutral-400")}>
              This action will apply immediately to the current execution workspace. Please confirm to proceed.
            </p>

            <div className="mt-5 flex items-center justify-end gap-2">
              <button
                type="button"
                onClick={() => setConfirmState(null)}
                className={cn("rounded-lg border px-3 py-2 text-xs font-semibold", isLight ? "border-neutral-300 text-neutral-700 hover:bg-neutral-100" : "border-neutral-700 text-neutral-200 hover:bg-neutral-800")}
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => executeConfirmedAction.mutate({ action: confirmState.action, token: confirmState.token })}
                disabled={executeConfirmedAction.isPending}
                className={cn(
                  "rounded-lg px-3 py-2 text-xs font-semibold text-white disabled:opacity-60",
                  confirmState.action.includes("KILL")
                    ? "bg-rose-600 hover:bg-rose-500"
                    : "bg-blue-600 hover:bg-blue-500"
                )}
              >
                {executeConfirmedAction.isPending ? "Executing..." : "Confirm & Execute"}
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {pendingOrderCancel ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 px-4">
          <div className={cn("w-full max-w-lg rounded-2xl border p-5 shadow-2xl", isLight ? "border-neutral-200 bg-white" : "border-neutral-800 bg-neutral-950")}>
            <div className="flex items-start justify-between gap-3">
              <div>
                <div className={cn("text-[11px] font-semibold uppercase tracking-[0.14em]", isLight ? "text-neutral-500" : "text-neutral-400")}>Cancel Order</div>
                <h3 className={cn("mt-1 text-xl font-semibold tracking-tight", isLight ? "text-neutral-900" : "text-white")}>
                  Confirm cancellation
                </h3>
              </div>
              <Chip value={pendingOrderCancel.source === "BROKER" ? "BROKER" : "OMS"} />
            </div>

            <div className={cn("mt-4 rounded-xl border p-3", isLight ? "border-neutral-200 bg-neutral-50" : "border-neutral-800 bg-neutral-900/80")}>
              <div className="grid grid-cols-2 gap-3">
                <Metric title="Symbol" value={fmt(pendingOrderCancel.symbol)} />
                <Metric title="Side" value={fmt(pendingOrderCancel.side)} />
                <Metric title="State" value={fmt(pendingOrderCancel.state)} />
                <Metric title="Qty" value={fmt(pendingOrderCancel.quantity)} />
              </div>
              <div className="mt-3 text-xs text-neutral-600 dark:text-neutral-300">
                {pendingOrderCancel.source === "BROKER"
                  ? `Broker order id: ${fmt(pendingOrderCancel.brokerOrderId)}`
                  : `OMS order id: ${fmt(pendingOrderCancel.orderId)}`}
              </div>
            </div>

            <p className={cn("mt-3 text-xs", isLight ? "text-neutral-600" : "text-neutral-400")}>
              This will send a cancel request immediately. Continue?
            </p>

            <div className="mt-5 flex items-center justify-end gap-2">
              <button
                type="button"
                onClick={() => setPendingOrderCancel(null)}
                className={cn("rounded-lg border px-3 py-2 text-xs font-semibold", isLight ? "border-neutral-300 text-neutral-700 hover:bg-neutral-100" : "border-neutral-700 text-neutral-200 hover:bg-neutral-800")}
              >
                Keep Order
              </button>
              <button
                type="button"
                onClick={() => {
                  cancelOrderMutation.mutate({
                    source: pendingOrderCancel.source,
                    orderId: pendingOrderCancel.orderId ?? null,
                    brokerOrderId: pendingOrderCancel.brokerOrderId ?? null,
                    variety: pendingOrderCancel.variety ?? null,
                  });
                  setPendingOrderCancel(null);
                }}
                disabled={cancelOrderMutation.isPending}
                className="rounded-lg bg-rose-600 px-3 py-2 text-xs font-semibold text-white hover:bg-rose-500 disabled:opacity-60"
              >
                {cancelOrderMutation.isPending ? "Cancelling..." : "Confirm Cancel"}
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}

function Metric({
  title,
  value,
  loading,
  pnlValue,
}: {
  title: string;
  value: string;
  loading?: boolean;
  pnlValue?: number | null;
}) {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const n = pnlValue != null ? pnlValue : NaN;
  const tone =
    pnlValue != null && Number.isFinite(n)
      ? n > 0
        ? "text-emerald-600 dark:text-emerald-400"
        : n < 0
          ? "text-rose-600 dark:text-rose-400"
          : ""
      : "";
  return (
    <div className={cn(
      "rounded-xl border p-3 shadow-sm transition-shadow hover:shadow-md",
      isLight ? "border-neutral-200 bg-white" : "border-neutral-800 bg-neutral-950/60",
    )}>
      <div className="text-[11px] font-semibold uppercase tracking-wide text-neutral-500">{title}</div>
      <div className={cn(
        "mt-1 font-mono text-base font-semibold tabular-nums",
        loading ? "animate-pulse text-neutral-400" : tone || (isLight ? "text-neutral-900" : "text-neutral-100"),
      )}>
        {value}
      </div>
    </div>
  );
}

function OrdersTable({
  rows,
  onCancel,
  cancelling,
}: {
  rows: Array<Record<string, unknown>>;
  onCancel: (payload: {
    source: string;
    orderId?: string | null;
    brokerOrderId?: string | null;
    variety?: string | null;
    symbol?: string | null;
    side?: string | null;
    state?: string | null;
    quantity?: string | null;
  }) => void;
  cancelling: boolean;
}) {
  const cancellable = (state: string) => {
    const s = state.toUpperCase();
    return s.includes("REQ_RECEIVED") || s.includes("OPEN") || s.includes("PENDING") || s.includes("SUBMITTED") || s.includes("ACCEPTED") || s.includes("PARTIAL");
  };
  return (
    <div className="mt-4 overflow-x-auto">
      <table className="w-full text-left text-xs">
        <thead className="text-neutral-500">
          <tr>
            {["createdAt", "symbol", "side", "state", "executionMode", "strategyKey", "quantity", "rejectReason", "source", "action"].map((c) => (
              <th key={c} className="pb-2 pr-3 uppercase tracking-wide">{c}</th>
            ))}
          </tr>
        </thead>
        <tbody className="text-neutral-700 dark:text-neutral-200">
          {rows.map((r, i) => {
            const state = fmt(r.state).toUpperCase();
            const source = fmt(r.source).toUpperCase();
            const canCancel = cancellable(state);
            return (
              <tr key={String(r.orderId ?? r.brokerOrderId ?? `${i}`)} className="border-t border-neutral-200/80 dark:border-neutral-800">
                {["createdAt", "symbol", "side", "state", "executionMode", "strategyKey", "quantity", "rejectReason", "source"].map((c) => (
                  <td key={c} className="py-2 pr-3 font-mono">
                    {c.toLowerCase().includes("state") || c.toLowerCase().includes("mode") || c.toLowerCase().includes("status") ? (
                      <Chip value={fmt(r[c]).toUpperCase()} />
                    ) : (
                      fmtCell(r[c], c)
                    )}
                  </td>
                ))}
                <td className="py-2 pr-3">
                  <button
                    type="button"
                    disabled={!canCancel || cancelling}
                    onClick={() =>
                      onCancel({
                        source,
                        orderId: (r.orderId as string | null | undefined) ?? null,
                        brokerOrderId: (r.brokerOrderId as string | null | undefined) ?? null,
                        variety: (r.variety as string | null | undefined) ?? "regular",
                        symbol: fmt(r.symbol),
                        side: fmt(r.side),
                        state: fmt(r.state),
                        quantity: fmt(r.quantity),
                      })
                    }
                    className="rounded-md border border-rose-300 bg-rose-50 px-2 py-1 text-[11px] font-semibold text-rose-700 disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    Cancel
                  </button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {rows.length === 0 ? <div className="py-6 text-center text-sm text-neutral-500">No records</div> : null}
    </div>
  );
}

function Table({
  rows,
  cols,
  loading,
  emptyLabel = "No records",
}: {
  rows: Array<Record<string, unknown>>;
  cols: string[];
  loading?: boolean;
  emptyLabel?: string;
}) {
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
                    fmtCell(r[c], c)
                  )}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {rows.length === 0 ? (
        <div className="py-6 text-center text-sm text-neutral-500">
          {loading ? "Loading…" : emptyLabel}
        </div>
      ) : null}
    </div>
  );
}
