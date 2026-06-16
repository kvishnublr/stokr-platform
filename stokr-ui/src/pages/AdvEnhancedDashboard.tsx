import { useMemo, useState } from "react";
import { motion } from "framer-motion";
import { useMutation, useQuery } from "@tanstack/react-query";
import {
  AlertTriangle,
  BarChart3,
  BriefcaseBusiness,
  CheckCircle2,
  ChevronDown,
  Cpu,
  Crosshair,
  Gauge,
  LineChart,
  Loader2,
  Lock,
  Moon,
  Radio,
  RefreshCw,
  ShieldCheck,
  Sparkles,
  Sun,
  Zap,
} from "lucide-react";
import { toast } from "sonner";
import {
  type AdvScannerRow,
  type AdvSector,
  type AdvTerminalSnapshot,
  fetchAdvMovers,
  fetchAdvTerminal,
  fetchAdvWorkstation,
} from "../api/advDashboard";
import { postBrokerTestOrder, validateTestTradingsymbol } from "../api/broker";
import { parseAxiosMessage } from "../api/client";
import { cn } from "../lib/utils";
import { useUiThemeStore } from "../state/uiTheme";

type TabId = "dashboard" | "intelligence" | "patterns" | "analytics" | "execution" | "portfolio" | "advanced" | "trading";

type Mover = { symbol: string; price?: string; changePct?: string; source?: string; aiScore?: number };
type Workstation = Record<string, unknown> & {
  accountSummary?: Record<string, unknown>;
  openPositions?: Record<string, unknown>[];
  closedPositions?: Record<string, unknown>[];
  orders?: Record<string, unknown>[];
  executions?: Record<string, unknown>[];
  badges?: Record<string, unknown>;
};

const tabs: { id: TabId; label: string; icon: React.ComponentType<{ className?: string }> }[] = [
  { id: "dashboard", label: "Dashboard", icon: BarChart3 },
  { id: "intelligence", label: "Intelligence", icon: Sparkles },
  { id: "patterns", label: "Patterns", icon: Crosshair },
  { id: "analytics", label: "Analytics", icon: LineChart },
  { id: "execution", label: "Execution", icon: Zap },
  { id: "portfolio", label: "Portfolio", icon: BriefcaseBusiness },
  { id: "advanced", label: "Advanced", icon: Cpu },
  { id: "trading", label: "Live Trading", icon: Radio },
];

const rupee = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 0 });
const numberFmt = new Intl.NumberFormat("en-IN", { maximumFractionDigits: 2 });

function asNum(v: unknown, fallback = 0) {
  if (typeof v === "number" && Number.isFinite(v)) return v;
  if (typeof v === "string") {
    const n = Number(v.replace(/[,%₹,\s]/g, ""));
    if (Number.isFinite(n)) return n;
  }
  return fallback;
}

function asText(v: unknown, fallback = "-") {
  if (v === null || v === undefined || v === "") return fallback;
  return String(v);
}

function getMetric(snapshot: AdvTerminalSnapshot | undefined, keys: string[], fallback = 0) {
  const metrics = snapshot?.metrics ?? {};
  for (const key of keys) {
    const value = metrics[key];
    if (value !== undefined && value !== null) return asNum(value, fallback);
  }
  return fallback;
}

function getWsNum(ws: Workstation | undefined, keys: string[], fallback = 0) {
  const pools = [ws?.accountSummary, ws?.badges, ws];
  for (const pool of pools) {
    if (!pool) continue;
    for (const key of keys) {
      const value = pool[key];
      if (value !== undefined && value !== null) return asNum(value, fallback);
    }
  }
  return fallback;
}

function statusTone(status?: string) {
  const s = String(status ?? "").toUpperCase();
  if (["EXECUTED"].some((x) => s.includes(x))) return "green";
  if (["EXECUTABLE", "OPEN", "LIVE", "SUCCESS", "OPERATIONAL"].some((x) => s.includes(x))) return "green";
  if (["INTELLIGENCE_ONLY", "INTELLIGENCE"].some((x) => s.includes(x))) return "blue";
  if (["WATCH", "COOLDOWN", "WARMUP", "PENDING"].some((x) => s.includes(x))) return "amber";
  if (["BLOCK", "REJECT", "STALE", "FAILED", "DISCONNECTED"].some((x) => s.includes(x))) return "red";
  return "blue";
}

function formatPrice(v: unknown) {
  const n = asNum(v, Number.NaN);
  return Number.isFinite(n) ? numberFmt.format(n) : "-";
}

function entryZone(row: AdvScannerRow) {
  const low = row.entryZoneLow ?? row.entryPrice ?? row.ltp;
  const high = row.entryZoneHigh ?? row.entryPrice ?? row.ltp;
  if (low == null && high == null) return "-";
  if (String(low) === String(high)) return formatPrice(low);
  return `${formatPrice(low)} – ${formatPrice(high)}`;
}

function setupRowKey(row: AdvScannerRow, idx: number) {
  return row.signalId ?? `${row.symbol}-${row.strategy ?? ""}-${row.side ?? ""}-${idx}`;
}

function normalizeSymbol(raw: unknown) {
  return String(raw ?? "")
    .replace(/^NSE:/i, "")
    .replace(/^BSE:/i, "")
    .trim()
    .toUpperCase();
}

function aiEntryPrice(row: AdvScannerRow) {
  if (row.entryPrice != null) return formatPrice(row.entryPrice);
  const low = asNum(row.entryZoneLow ?? row.ltp, Number.NaN);
  const high = asNum(row.entryZoneHigh ?? row.ltp, Number.NaN);
  if (Number.isFinite(low) && Number.isFinite(high) && low !== high) {
    return formatPrice((low + high) / 2);
  }
  if (Number.isFinite(low)) return formatPrice(low);
  if (Number.isFinite(high)) return formatPrice(high);
  return formatPrice(row.ltp);
}

function tradeSide(row: AdvScannerRow): "BUY" | "SELL" {
  const s = String(row.side ?? "").toUpperCase();
  return s === "SELL" ? "SELL" : "BUY";
}

function positionSymbol(p: Record<string, unknown>) {
  return normalizeSymbol(p.symbol ?? p.tradingSymbol ?? p.tradingsymbol);
}

function hasOpenPosition(positions: Record<string, unknown>[], symbol: string) {
  const sym = normalizeSymbol(symbol);
  return positions.some((p) => positionSymbol(p) === sym && asNum(p.quantity ?? p.qty, 0) !== 0);
}

function pct(v: unknown) {
  const n = asNum(v, Number.NaN);
  return Number.isFinite(n) ? `${numberFmt.format(n)}%` : "-";
}

function score(v: unknown) {
  const n = asNum(v, Number.NaN);
  return Number.isFinite(n) ? Math.round(n).toString() : "-";
}

function timeAgo(date: Date | undefined) {
  if (!date) return "not synced";
  const sec = Math.max(0, Math.floor((Date.now() - date.getTime()) / 1000));
  if (sec < 60) return `${sec}s ago`;
  const min = Math.floor(sec / 60);
  return `${min}m ago`;
}

function Card({ children, className }: { children: React.ReactNode; className?: string }) {
  return <div className={cn("rounded-lg border border-slate-200 bg-white shadow-sm dark:border-neutral-800 dark:bg-neutral-900", className)}>{children}</div>;
}

function Pill({ children, tone = "blue" }: { children: React.ReactNode; tone?: "green" | "amber" | "red" | "blue" | "slate" }) {
  const tones = {
    green: "border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-900/60 dark:bg-emerald-950/40 dark:text-emerald-300",
    amber: "border-amber-200 bg-amber-50 text-amber-700 dark:border-amber-900/60 dark:bg-amber-950/40 dark:text-amber-300",
    red: "border-rose-200 bg-rose-50 text-rose-700 dark:border-rose-900/60 dark:bg-rose-950/40 dark:text-rose-300",
    blue: "border-blue-200 bg-blue-50 text-blue-700 dark:border-blue-900/60 dark:bg-blue-950/40 dark:text-blue-300",
    slate: "border-slate-200 bg-slate-50 text-slate-600 dark:border-neutral-800 dark:bg-neutral-900 dark:text-neutral-300",
  };
  return <span className={cn("inline-flex items-center gap-1 rounded-full border px-2.5 py-1 text-xs font-semibold", tones[tone])}>{children}</span>;
}

function SectionTitle({ icon: Icon, title, subtitle }: { icon: React.ComponentType<{ className?: string }>; title: string; subtitle?: string }) {
  return (
    <div className="flex items-start justify-between gap-3">
      <div className="flex items-center gap-3">
        <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-blue-50 text-blue-600 dark:bg-blue-950/50 dark:text-blue-300">
          <Icon className="h-4 w-4" />
        </div>
        <div>
          <h3 className="text-base font-bold text-slate-950 dark:text-neutral-100">{title}</h3>
          {subtitle ? <p className="text-xs font-medium text-slate-500 dark:text-neutral-400">{subtitle}</p> : null}
        </div>
      </div>
    </div>
  );
}

function MetricTile({ label, value, hint, tone = "blue" }: { label: string; value: string; hint?: string; tone?: "green" | "amber" | "red" | "blue" | "slate" }) {
  const toneClass = {
    green: "text-emerald-600",
    amber: "text-amber-600",
    red: "text-rose-600",
    blue: "text-blue-600",
    slate: "text-slate-900 dark:text-neutral-100",
  }[tone];
  return (
    <Card className="p-4">
      <div className="text-[11px] font-bold uppercase tracking-wide text-slate-500 dark:text-neutral-400">{label}</div>
      <div className={cn("mt-2 text-2xl font-black tracking-normal", toneClass)}>{value}</div>
      {hint ? <div className="mt-1 text-xs font-medium text-slate-500 dark:text-neutral-400">{hint}</div> : null}
    </Card>
  );
}

function TableShell({ children }: { children: React.ReactNode }) {
  return <div className="overflow-hidden rounded-lg border border-slate-200 dark:border-neutral-800"><div className="overflow-x-auto">{children}</div></div>;
}

function Empty({ text }: { text: string }) {
  return <div className="rounded-lg border border-dashed border-slate-300 p-6 text-center text-sm font-medium text-slate-500 dark:border-neutral-700 dark:text-neutral-400">{text}</div>;
}

function ErrorBox({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-rose-200 bg-rose-50 p-4 text-sm text-rose-700 dark:border-rose-900/60 dark:bg-rose-950/40 dark:text-rose-300">
      <div className="flex items-center gap-2">
        <AlertTriangle className="h-4 w-4" />
        <span>{parseAxiosMessage(error)}</span>
      </div>
      <button className="rounded-md border border-rose-200 px-3 py-1.5 text-xs font-bold dark:border-rose-800" onClick={onRetry}>Retry</button>
    </div>
  );
}

function ExecutionBadge({ row, compact = false }: { row: AdvScannerRow; compact?: boolean }) {
  const st = String(row.executionStatus ?? "").toUpperCase();
  const tone = statusTone(st) as "green" | "amber" | "red" | "blue";
  const label = st.replace(/_/g, " ") || "-";
  if (compact) {
    return <Pill tone={tone}>{label}</Pill>;
  }
  return (
    <div className="space-y-1">
      <Pill tone={tone}>{label}</Pill>
      <div className="max-w-[200px] text-[10px] font-medium leading-snug text-slate-500 dark:text-neutral-400">
        {row.executionLabel ?? row.displayStatus ?? row.executionStatus ?? "-"}
      </div>
    </div>
  );
}

function SignalTable({ rows, showTradePlan = false, compact = false }: { rows: AdvScannerRow[]; showTradePlan?: boolean; compact?: boolean }) {
  if (!rows.length) return <Empty text="No live signals from the pipeline yet." />;
  const minimal = compact || showTradePlan;
  return (
    <TableShell>
      <table className="min-w-full text-left text-sm">
        <thead className="bg-slate-50 text-[10px] uppercase tracking-wide text-slate-500 dark:bg-neutral-950 dark:text-neutral-400">
          <tr>
            <th className="px-3 py-2.5">#</th>
            <th className="px-3 py-2.5">Symbol</th>
            {!minimal ? <th className="px-3 py-2.5">Call</th> : null}
            <th className="px-3 py-2.5">Side</th>
            <th className="px-3 py-2.5">Score</th>
            {showTradePlan || minimal ? (
              <>
                <th className="px-3 py-2.5">Entry</th>
                <th className="px-3 py-2.5">Stop</th>
                <th className="px-3 py-2.5">Target</th>
              </>
            ) : null}
            {!minimal ? <th className="px-3 py-2.5">Exit plan</th> : null}
            <th className="px-3 py-2.5">Status</th>
            {!minimal ? <th className="min-w-[220px] px-3 py-2.5">Setup reason</th> : null}
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100 dark:divide-neutral-800">
          {rows.map((row, idx) => {
            const isSell = String(row.side).toUpperCase() === "SELL";
            return (
              <tr key={`${row.signalId ?? row.symbol}-${idx}`} className="bg-white align-middle dark:bg-neutral-900">
                <td className="px-3 py-2.5 text-xs font-semibold text-slate-400">{row.rank ?? idx + 1}</td>
                <td className="px-3 py-2.5">
                  <div className="font-bold text-slate-950 dark:text-neutral-100">{row.symbol}</div>
                  {!minimal ? (
                    <>
                      <div className="text-xs text-slate-500">{asText(row.setupType ?? row.source, "scanner")}</div>
                      <div className="text-[10px] text-slate-400">{asText(row.strategy, "")}</div>
                    </>
                  ) : (
                    <div className="text-[10px] text-slate-400">{asText(row.strategy ?? row.setupType, "")}</div>
                  )}
                </td>
                {!minimal ? (
                  <td className="max-w-[200px] px-3 py-2.5">
                    <Pill tone={isSell ? "red" : "green"}>{asText(row.side, "WATCH")}</Pill>
                    <div className="mt-1 text-[11px] font-semibold leading-snug text-slate-700 dark:text-neutral-300">
                      {asText(row.tradeCall, row.entryTrigger ?? "Awaiting AI plan")}
                    </div>
                  </td>
                ) : null}
                {minimal ? (
                  <td className="px-3 py-2.5">
                    <span
                      className={cn(
                        "inline-flex rounded-md px-2 py-0.5 text-[11px] font-bold uppercase",
                        isSell
                          ? "bg-rose-50 text-rose-700 dark:bg-rose-950/40 dark:text-rose-300"
                          : "bg-emerald-50 text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-300",
                      )}
                    >
                      {asText(row.side, "—")}
                    </span>
                  </td>
                ) : null}
                <td className="px-3 py-2.5">
                  <span className="font-bold text-blue-600">{score(row.aiScore)}</span>
                  {!minimal ? <div className="text-xs text-slate-500">{pct(row.probability)}</div> : null}
                </td>
                {showTradePlan || minimal ? (
                  <>
                    <td className="px-3 py-2.5 font-medium text-slate-800 dark:text-neutral-200">{entryZone(row)}</td>
                    <td className="px-3 py-2.5 font-semibold text-rose-600">{formatPrice(row.stopLoss)}</td>
                    <td className="px-3 py-2.5 font-semibold text-emerald-600">{formatPrice(row.targetPrice)}</td>
                  </>
                ) : null}
                {!minimal ? (
                  <td className="max-w-[260px] px-3 py-2.5 text-[11px] leading-snug text-slate-600 dark:text-neutral-400">{asText(row.exitPlan, "-")}</td>
                ) : null}
                <td className="px-3 py-2.5">
                  <ExecutionBadge row={row} compact={minimal} />
                </td>
                {!minimal ? (
                  <td className="max-w-[280px] px-3 py-2.5 text-xs leading-snug text-slate-500">
                    <div>{asText(row.reason ?? row.rejectionReason ?? row.pipelineStage, "-")}</div>
                    {row.invalidation ? <div className="mt-1 text-[10px] text-rose-600/90">Inv: {row.invalidation}</div> : null}
                  </td>
                ) : null}
              </tr>
            );
          })}
        </tbody>
      </table>
    </TableShell>
  );
}

function SetupLevels({ row, className }: { row: AdvScannerRow; className?: string }) {
  return (
    <div className={cn("flex flex-wrap items-center gap-x-4 gap-y-1 text-[11px] text-slate-500", className)}>
      <span>
        Entry <span className="font-semibold text-slate-800 dark:text-neutral-200">{aiEntryPrice(row)}</span>
      </span>
      <span>
        Stop <span className="font-semibold text-rose-600">{formatPrice(row.stopLoss)}</span>
      </span>
      <span>
        Target <span className="font-semibold text-emerald-600">{formatPrice(row.targetPrice)}</span>
      </span>
    </div>
  );
}

function SmartSetupGrid({
  rows,
  liveGateOpen,
  openPositions,
  onRefresh,
}: {
  rows: AdvScannerRow[];
  liveGateOpen: boolean;
  openPositions: Record<string, unknown>[];
  onRefresh: () => void;
}) {
  const [expandedKey, setExpandedKey] = useState<string | null>(null);
  const [qtyByKey, setQtyByKey] = useState<Record<string, number>>({});
  const [localWatching, setLocalWatching] = useState<Record<string, boolean>>({});
  const [pendingKey, setPendingKey] = useState<string | null>(null);

  const orderMutation = useMutation({
    mutationFn: async ({ row, qty }: { row: AdvScannerRow; qty: number; key: string }) => {
      const tradingsymbol = normalizeSymbol(row.symbol);
      const symErr = validateTestTradingsymbol(tradingsymbol);
      if (symErr) throw new Error(symErr);
      return postBrokerTestOrder({
        variety: "REGULAR",
        exchange: "NSE",
        tradingsymbol,
        side: tradeSide(row),
        quantity: qty,
        orderType: "MARKET",
        product: "MIS",
      });
    },
    onSuccess: (data, vars) => {
      setLocalWatching((prev) => ({ ...prev, [vars.key]: true }));
      setExpandedKey(null);
      setPendingKey(null);
      onRefresh();
      if (data.dryRun) {
        toast.message("Dry run — order simulated");
      } else if (data.orderId) {
        toast.success(data.message || `Order placed · ${data.orderId}`);
      } else {
        toast.error(data.message || "Order rejected");
      }
    },
    onError: (e) => {
      setPendingKey(null);
      toast.error(parseAxiosMessage(e));
    },
  });

  if (!rows.length) return <Empty text="No live signals from the pipeline yet." />;

  return (
    <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
      {rows.map((row, idx) => {
        const key = setupRowKey(row, idx);
        const side = tradeSide(row);
        const isSell = side === "SELL";
        const expanded = expandedKey === key;
        const watching = Boolean(localWatching[key]) || hasOpenPosition(openPositions, row.symbol);
        const qty = qtyByKey[key] ?? 1;
        const submitting = pendingKey === key && orderMutation.isPending;
        const aiScore = asNum(row.aiScore, 0);

        const openTrade = () => {
          if (watching) return;
          setExpandedKey(key);
          setQtyByKey((prev) => ({ ...prev, [key]: prev[key] ?? 1 }));
        };

        const confirmTrade = () => {
          if (!liveGateOpen) {
            toast.error("Live gate is closed — orders blocked");
            return;
          }
          const q = Math.floor(Number(qty));
          if (!Number.isFinite(q) || q < 1 || q > 5) {
            toast.error("Quantity must be 1–5");
            return;
          }
          setPendingKey(key);
          orderMutation.mutate({ row, qty: q, key });
        };

        return (
          <Card
            key={key}
            className={cn(
              "overflow-hidden p-3.5 transition-shadow hover:shadow-md",
              watching && "ring-1 ring-emerald-500/50",
              aiScore >= 85 && !watching && "border-blue-200/80 dark:border-blue-900/40",
            )}
          >
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-base font-bold tracking-tight text-slate-950 dark:text-neutral-50">{row.symbol}</span>
                  <span
                    className={cn(
                      "rounded px-1.5 py-0.5 text-[10px] font-bold uppercase",
                      isSell
                        ? "bg-rose-50 text-rose-700 dark:bg-rose-950/50 dark:text-rose-300"
                        : "bg-emerald-50 text-emerald-700 dark:bg-emerald-950/50 dark:text-emerald-300",
                    )}
                  >
                    {side}
                  </span>
                </div>
                <div className="mt-0.5 truncate text-[10px] font-medium text-slate-400">{asText(row.strategy ?? row.setupType, "setup")}</div>
              </div>
              <div className="shrink-0 rounded-lg bg-blue-50 px-2.5 py-1 text-right dark:bg-blue-950/40">
                <div className="text-lg font-bold leading-none text-blue-600">{score(row.aiScore)}</div>
              </div>
            </div>

            <SetupLevels row={row} className="mt-2.5 rounded-md bg-slate-50/80 px-2 py-1.5 dark:bg-neutral-950/50" />

            {watching ? (
              <div className="mt-2.5 flex items-center gap-2 rounded-md border border-emerald-200/80 bg-emerald-50/60 px-2.5 py-2 dark:border-emerald-900/40 dark:bg-emerald-950/20">
                <span className="relative flex h-2 w-2 shrink-0">
                  <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-60" />
                  <span className="relative inline-flex h-2 w-2 rounded-full bg-emerald-500" />
                </span>
                <span className="text-xs font-semibold text-emerald-800 dark:text-emerald-200">AI watching</span>
              </div>
            ) : expanded ? (
              <div className="mt-2.5 space-y-2 rounded-md border border-slate-200 bg-slate-50/50 p-2.5 dark:border-neutral-800 dark:bg-neutral-950/30">
                <div className="flex items-end gap-2">
                  <label className="flex-1">
                    <span className="text-[10px] font-semibold uppercase text-slate-400">Qty</span>
                    <input
                      type="number"
                      min={1}
                      max={5}
                      value={qty}
                      onChange={(e) => setQtyByKey((prev) => ({ ...prev, [key]: Math.max(1, Math.min(5, Math.floor(Number(e.target.value) || 1))) }))}
                      className="mt-0.5 w-full rounded-md border border-slate-200 bg-white px-2 py-1.5 text-sm font-bold text-slate-900 dark:border-neutral-700 dark:bg-neutral-950 dark:text-neutral-100"
                    />
                  </label>
                  <button
                    type="button"
                    onClick={confirmTrade}
                    disabled={submitting || !liveGateOpen}
                    className={cn(
                      "rounded-md px-3 py-1.5 text-xs font-bold text-white disabled:opacity-50",
                      isSell ? "bg-rose-600 hover:bg-rose-700" : "bg-emerald-600 hover:bg-emerald-700",
                    )}
                  >
                    {submitting ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : `Confirm ${side}`}
                  </button>
                </div>
                <button
                  type="button"
                  onClick={() => setExpandedKey(null)}
                  className="w-full text-center text-[11px] font-medium text-slate-500 hover:text-slate-700 dark:hover:text-neutral-300"
                >
                  Cancel
                </button>
                {!liveGateOpen ? <p className="text-center text-[10px] text-amber-700 dark:text-amber-300">Gate closed</p> : null}
              </div>
            ) : (
              <button
                type="button"
                onClick={openTrade}
                className={cn(
                  "mt-2.5 flex w-full items-center justify-center rounded-md border py-2 text-sm font-semibold transition active:scale-[0.99]",
                  isSell
                    ? "border-rose-200 bg-rose-50 text-rose-700 hover:bg-rose-100 dark:border-rose-900/50 dark:bg-rose-950/30 dark:text-rose-300 dark:hover:bg-rose-950/50"
                    : "border-emerald-200 bg-emerald-50 text-emerald-700 hover:bg-emerald-100 dark:border-emerald-900/50 dark:bg-emerald-950/30 dark:text-emerald-300 dark:hover:bg-emerald-950/50",
                )}
              >
                Trade {side}
              </button>
            )}
          </Card>
        );
      })}
    </div>
  );
}

export function AdvEnhancedDashboard() {
  const isDark = useUiThemeStore((s) => s.mode === "dark");
  const [activeTab, setActiveTab] = useState<TabId>("dashboard");

  const advRetry = (failureCount: number, error: unknown) => {
    if (failureCount >= 5) return false;
    if (!error || typeof error !== "object") return failureCount < 3;
    const status = (error as { response?: { status?: number } }).response?.status;
    const code = (error as { code?: string }).code;
    return status === 502 || status === 503 || status === 504 || code === "ECONNABORTED" || code === "ERR_NETWORK";
  };

  const terminalQ = useQuery({
    queryKey: ["adv-enhanced-terminal"],
    queryFn: fetchAdvTerminal,
    refetchInterval: (q) => Math.max(5000, ((q.state.data?.scanIntervalSec ?? q.state.data?.liveControl?.scanIntervalSec ?? 10) as number) * 1000),
    retry: advRetry,
    retryDelay: (attempt) => Math.min(4000 * 2 ** attempt, 30_000),
    placeholderData: (prev) => prev,
  });
  const moversQ = useQuery({
    queryKey: ["adv-enhanced-movers"],
    queryFn: fetchAdvMovers,
    refetchInterval: 10000,
    retry: advRetry,
    retryDelay: (attempt) => Math.min(3000 * 2 ** attempt, 20_000),
    placeholderData: (prev) => prev,
  });
  const workstationQ = useQuery({
    queryKey: ["adv-enhanced-workstation"],
    queryFn: fetchAdvWorkstation,
    refetchInterval: 10000,
    retry: advRetry,
    retryDelay: (attempt) => Math.min(3000 * 2 ** attempt, 20_000),
    placeholderData: (prev) => prev,
  });

  const snapshot = terminalQ.data;
  const movers = moversQ.data ?? [];
  const ws = workstationQ.data as Workstation | undefined;
  const allRows = useMemo(() => {
    const rows = [...(snapshot?.scannerRows ?? []), ...(snapshot?.liveCards ?? [])];
    const seen = new Set<string>();
    return rows.filter((row) => {
      const key = row.signalId ?? `${row.symbol}-${row.strategy}-${row.side}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  }, [snapshot]);

  const topRows = allRows
    .slice()
    .sort((a, b) => asNum(b.aiScore) - asNum(a.aiScore))
    .slice(0, 18);
  const executableRows = allRows.filter((r) => String(r.executionStatus ?? "").toUpperCase().includes("EXEC"));
  const blockedRows = allRows.filter((r) => /BLOCK|REJECT|COOLDOWN/i.test(String(r.executionStatus ?? "")));
  const openPositions = Array.isArray(ws?.openPositions) ? ws.openPositions : [];
  const orders = Array.isArray(ws?.orders) ? ws.orders : [];
  const executions = Array.isArray(ws?.executions) ? ws.executions : [];
  const pnl = getWsNum(ws, ["dayPnl", "todayPnl", "pnl", "mtm"], getMetric(snapshot, ["todayPnL", "todayPnl", "pnl"], 0));
  const winRate = getMetric(snapshot, ["winRate", "winPct"], 0);
  const liveControl = snapshot?.liveControl ?? {};
  const terminalFresh = terminalQ.dataUpdatedAt ? new Date(terminalQ.dataUpdatedAt) : undefined;
  const terminalFailed = Boolean(terminalQ.error && !terminalQ.data);

  const refreshAll = () => {
    void terminalQ.refetch();
    void moversQ.refetch();
    void workstationQ.refetch();
  };

  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.25 }}
      className={cn(
        "h-full w-full overflow-auto rounded-xl border",
        isDark ? "border-neutral-800 bg-neutral-950" : "border-slate-200 bg-slate-50",
      )}
    >
      <div className="space-y-5 p-5">
        <Card className="overflow-hidden">
          <div className="border-b border-slate-100 bg-white px-5 py-4 dark:border-neutral-800 dark:bg-neutral-900">
            <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
              <div className="flex items-center gap-3 sm:gap-4">
                <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-lg bg-blue-600 text-white shadow-lg shadow-blue-600/20 sm:h-14 sm:w-14">
                  <Zap className="h-6 w-6 sm:h-7 sm:w-7" />
                </div>
                <div>
                  <div className="text-[10px] font-black uppercase tracking-[0.22em] text-blue-600 sm:text-[11px]">Institutional Grade</div>
                  <h2 className="text-xl font-black tracking-normal text-slate-950 dark:text-neutral-50 sm:text-2xl">Stokr Elite Enhanced</h2>
                  <div className="mt-1 flex flex-wrap items-center gap-2">
                    <Pill tone={snapshot?.marketOpen ? "green" : "amber"}>{snapshot?.sessionState ?? (snapshot?.marketOpen ? "MARKET OPEN" : "MARKET CLOSED")}</Pill>
                    <Pill tone={statusTone(liveControl.feedStatus) as "green" | "amber" | "red" | "blue"}>{liveControl.feedStatus ?? "FEED CHECK"}</Pill>
                    <Pill tone={terminalQ.isFetching ? "amber" : "green"}>{terminalQ.isFetching ? "Syncing" : `Synced ${timeAgo(terminalFresh)}`}</Pill>
                  </div>
                </div>
              </div>
              <div className="flex flex-wrap items-center justify-end gap-2 sm:gap-3">
                <div className="hidden text-right sm:block">
                  <div className="text-xs font-bold uppercase text-slate-500">IST Time</div>
                  <div className="text-sm font-black text-slate-900 dark:text-neutral-100">{snapshot?.istTime ?? new Date().toLocaleTimeString("en-IN", { timeZone: "Asia/Kolkata", hour12: false })}</div>
                </div>
                <button
                  type="button"
                  onClick={() => useUiThemeStore.getState().toggle()}
                  className="inline-flex items-center gap-1 sm:gap-2 rounded-lg border border-slate-200 bg-white px-2 sm:px-3 py-2 text-xs sm:text-sm font-bold text-slate-700 shadow-sm hover:bg-slate-50 dark:border-neutral-800 dark:bg-neutral-950 dark:text-neutral-200"
                  title="Toggle light/dark theme"
                >
                  {isDark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
                </button>
                <button
                  type="button"
                  onClick={refreshAll}
                  className="inline-flex items-center gap-1 sm:gap-2 rounded-lg border border-slate-200 bg-white px-2 sm:px-3 py-2 text-xs sm:text-sm font-bold text-slate-700 shadow-sm hover:bg-slate-50 disabled:opacity-60 dark:border-neutral-800 dark:bg-neutral-950 dark:text-neutral-200"
                  disabled={terminalQ.isFetching || moversQ.isFetching || workstationQ.isFetching}
                >
                  <RefreshCw className={cn("h-4 w-4", (terminalQ.isFetching || moversQ.isFetching || workstationQ.isFetching) && "animate-spin")} />
                  <span className="sm:inline">Refresh</span>
                </button>
              </div>
            </div>
          </div>
          <div className="grid gap-3 p-5 sm:grid-cols-2 xl:grid-cols-5">
            <MetricTile label="Market Regime" value={snapshot?.marketRegime ?? "Loading"} hint={snapshot?.regimeNarrative ?? "Live scanner truth"} tone="slate" />
            <MetricTile label="Active Signals" value={String(allRows.length)} hint={`${executableRows.length} executable`} tone="green" />
            <MetricTile label="Quality Blocks" value={String(blockedRows.length)} hint="Rejected or cooldown" tone={blockedRows.length ? "amber" : "green"} />
            <MetricTile label="Today P&L" value={rupee.format(pnl)} hint={`${openPositions.length} open positions`} tone={pnl >= 0 ? "green" : "red"} />
            <MetricTile label="Win Rate" value={winRate ? `${Math.round(winRate)}%` : "-"} hint="From live performance payload" tone="blue" />
          </div>
        </Card>

        {terminalFailed ? <ErrorBox error={terminalQ.error} onRetry={refreshAll} /> : null}

        <Card className="p-3">
          <div className="flex flex-wrap gap-2">
            {tabs.map((tab) => {
              const Icon = tab.icon;
              const active = activeTab === tab.id;
              return (
                <button
                  key={tab.id}
                  onClick={() => setActiveTab(tab.id)}
                  className={cn(
                    "inline-flex h-10 items-center gap-2 rounded-lg border px-3 text-sm font-bold transition",
                    active
                      ? "border-blue-200 bg-blue-50 text-blue-700 shadow-sm dark:border-blue-900/60 dark:bg-blue-950/40 dark:text-blue-200"
                      : "border-transparent text-slate-600 hover:bg-slate-50 dark:text-neutral-300 dark:hover:bg-neutral-800",
                  )}
                >
                  <Icon className="h-4 w-4" />
                  {tab.label}
                </button>
              );
            })}
          </div>
        </Card>

        <Card className="min-h-[440px] p-5">
          {terminalQ.isLoading && !snapshot ? (
            <div className="flex min-h-[320px] items-center justify-center gap-2 text-sm font-bold text-slate-500">
              <Loader2 className="h-4 w-4 animate-spin" />
              Loading live terminal data
            </div>
          ) : (
            <>
              {activeTab === "dashboard" && (
                <DashboardTab
                  rows={topRows}
                  movers={movers}
                  snapshot={snapshot}
                  liveGateOpen={Boolean(snapshot?.liveControl?.liveGateOpen)}
                  openPositions={openPositions}
                  onRefresh={refreshAll}
                />
              )}
              {activeTab === "intelligence" && <IntelligenceTab rows={allRows} blockedRows={blockedRows} />}
              {activeTab === "patterns" && <PatternsTab rows={allRows} sectors={snapshot?.sectors ?? []} />}
              {activeTab === "analytics" && <AnalyticsTab snapshot={snapshot} rows={allRows} pnl={pnl} winRate={winRate} />}
              {activeTab === "execution" && <ExecutionTab snapshot={snapshot} orders={orders} executions={executions} />}
              {activeTab === "portfolio" && (
                <PortfolioTab
                  ws={ws}
                  positions={openPositions}
                  pnl={pnl}
                  loadError={workstationQ.error}
                  onRetry={() => void workstationQ.refetch()}
                />
              )}
              {activeTab === "advanced" && <AdvancedTab snapshot={snapshot} />}
              {activeTab === "trading" && <LiveTradingTab snapshot={snapshot} rows={topRows} />}
            </>
          )}
        </Card>
      </div>
    </motion.div>
  );
}

function DashboardTab({
  rows,
  movers,
  snapshot,
  liveGateOpen,
  openPositions,
  onRefresh,
}: {
  rows: AdvScannerRow[];
  movers: Mover[];
  snapshot?: AdvTerminalSnapshot;
  liveGateOpen: boolean;
  openPositions: Record<string, unknown>[];
  onRefresh: () => void;
}) {
  const [showDetails, setShowDetails] = useState(false);
  return (
    <div className="space-y-6">
      <SectionTitle icon={Gauge} title="Live Market Dashboard" subtitle={`Source: ${snapshot?.truthSource ?? "terminal API"} · scan ${snapshot?.scanIntervalSec ?? snapshot?.liveControl?.scanIntervalSec ?? 10}s · tap to trade`} />
      <div>
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
          <h4 className="text-sm font-black text-slate-900 dark:text-neutral-100">Highest Quality Setups</h4>
          <div className="flex flex-wrap items-center gap-2">
            <Pill tone="green">{rows.length} ranked</Pill>
            <Pill tone={liveGateOpen ? "green" : "amber"}>{liveGateOpen ? "Gate open" : "Gate closed"}</Pill>
            <button
              type="button"
              onClick={() => setShowDetails((v) => !v)}
              className="inline-flex items-center gap-1 rounded-lg border border-slate-200 px-2.5 py-1 text-xs font-bold text-slate-600 dark:border-neutral-700 dark:text-neutral-300"
            >
              Details
              <ChevronDown className={cn("h-3.5 w-3.5 transition", showDetails && "rotate-180")} />
            </button>
          </div>
        </div>
        <SmartSetupGrid rows={rows} liveGateOpen={liveGateOpen} openPositions={openPositions} onRefresh={onRefresh} />
        {showDetails ? (
          <div className="mt-4">
            <SignalTable rows={rows} showTradePlan compact />
          </div>
        ) : null}
      </div>
      <div>
        <div className="mb-3 flex items-center justify-between">
          <h4 className="text-sm font-black text-slate-900 dark:text-neutral-100">Live Movers</h4>
          <Pill tone="slate">{Math.min(movers.length, 12)} active</Pill>
        </div>
        {movers.length ? (
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {movers.slice(0, 12).map((m) => {
              const chg = asNum(m.changePct, 0);
              return (
                <div key={m.symbol} className="flex items-center justify-between rounded-lg border border-slate-200 p-3 dark:border-neutral-800">
                  <div>
                    <div className="font-black text-slate-950 dark:text-neutral-100">{m.symbol}</div>
                    <div className="text-xs text-slate-500">{m.source ?? "market watch"}</div>
                  </div>
                  <div className="text-right">
                    <div className="font-bold">{asText(m.price)}</div>
                    <div className={cn("text-xs font-black", chg >= 0 ? "text-emerald-600" : "text-rose-600")}>{pct(m.changePct)}</div>
                  </div>
                </div>
              );
            })}
          </div>
        ) : (
          <Empty text="No mover feed rows loaded yet." />
        )}
      </div>
    </div>
  );
}

function IntelligenceTab({ rows, blockedRows }: { rows: AdvScannerRow[]; blockedRows: AdvScannerRow[] }) {
  const buckets = [
    { label: "80+ score", value: rows.filter((r) => asNum(r.aiScore) >= 80).length, tone: "green" as const },
    { label: "60-79 score", value: rows.filter((r) => asNum(r.aiScore) >= 60 && asNum(r.aiScore) < 80).length, tone: "blue" as const },
    { label: "Below 60", value: rows.filter((r) => asNum(r.aiScore) < 60).length, tone: "amber" as const },
    { label: "Blocked", value: blockedRows.length, tone: blockedRows.length ? ("red" as const) : ("green" as const) },
  ];
  return (
    <div className="space-y-5">
      <SectionTitle icon={Sparkles} title="Signal Intelligence" subtitle="Score distribution, rejection causes, and current decision quality." />
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">{buckets.map((b) => <MetricTile key={b.label} label={b.label} value={String(b.value)} tone={b.tone} />)}</div>
      <SignalTable rows={rows.slice(0, 24)} showTradePlan />
    </div>
  );
}

function PatternsTab({ rows, sectors }: { rows: AdvScannerRow[]; sectors: AdvSector[] }) {
  const setupCounts = Object.entries(rows.reduce<Record<string, number>>((acc, row) => {
    const key = asText(row.setupType ?? row.strategy, "UNKNOWN");
    acc[key] = (acc[key] ?? 0) + 1;
    return acc;
  }, {})).sort((a, b) => b[1] - a[1]);
  return (
    <div className="space-y-5">
      <SectionTitle icon={Crosshair} title="Patterns And Clusters" subtitle="Shows what the scanner is actually finding today." />
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card className="p-4">
          <h4 className="mb-3 text-sm font-black">Setup Concentration</h4>
          {setupCounts.length ? setupCounts.map(([name, count]) => (
            <div key={name} className="mb-3">
              <div className="mb-1 flex justify-between text-xs font-bold"><span>{name}</span><span>{count}</span></div>
              <div className="h-2 rounded-full bg-slate-100 dark:bg-neutral-800"><div className="h-2 rounded-full bg-blue-600" style={{ width: `${Math.min(100, (count / Math.max(1, rows.length)) * 100)}%` }} /></div>
            </div>
          )) : <Empty text="No pattern distribution yet." />}
        </Card>
        <Card className="p-4">
          <h4 className="mb-3 text-sm font-black">Sector Clusters</h4>
          {sectors.length ? sectors.slice(0, 8).map((sector) => (
            <div key={sector.name} className="mb-3 flex items-center justify-between rounded-lg border border-slate-200 p-3 dark:border-neutral-800">
              <div>
                <div className="font-black">{sector.name}</div>
                <div className="text-xs text-slate-500">{sector.advancers ?? 0} up · {sector.decliners ?? 0} down</div>
              </div>
              <Pill tone={asNum(sector.avgScore) >= 70 ? "green" : "blue"}>{score(sector.avgScore)} avg</Pill>
            </div>
          )) : <Empty text="Sector intelligence loads with terminal sector payload." />}
        </Card>
      </div>
    </div>
  );
}

function AnalyticsTab({ snapshot, rows, pnl, winRate }: { snapshot?: AdvTerminalSnapshot; rows: AdvScannerRow[]; pnl: number; winRate: number }) {
  const perf = snapshot?.performance ?? {};
  const avgScore = rows.length ? rows.reduce((sum, r) => sum + asNum(r.aiScore), 0) / rows.length : 0;
  return (
    <div className="space-y-5">
      <SectionTitle icon={LineChart} title="Performance Analytics" subtitle="Live performance numbers plus pipeline-derived quality." />
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <MetricTile label="P&L" value={rupee.format(pnl)} tone={pnl >= 0 ? "green" : "red"} />
        <MetricTile label="Win Rate" value={winRate ? `${Math.round(winRate)}%` : "-"} tone="blue" />
        <MetricTile label="Avg AI Score" value={avgScore ? Math.round(avgScore).toString() : "-"} tone="green" />
        <MetricTile label="Executable Ratio" value={rows.length ? `${Math.round((rows.filter((r) => /EXEC/i.test(String(r.executionStatus))).length / rows.length) * 100)}%` : "-"} tone="amber" />
      </div>
      {Array.isArray(perf.bySetupType) && perf.bySetupType.length ? (
        <TableShell>
          <table className="min-w-full text-left text-sm">
            <thead className="bg-slate-50 text-xs uppercase text-slate-500 dark:bg-neutral-950"><tr><th className="px-3 py-2">Setup</th><th className="px-3 py-2">Count</th><th className="px-3 py-2">Executable</th><th className="px-3 py-2">Avg Score</th></tr></thead>
            <tbody>{perf.bySetupType.map((r) => <tr key={r.setup} className="border-t border-slate-100 dark:border-neutral-800"><td className="px-3 py-3 font-bold">{r.setup}</td><td className="px-3 py-3">{r.count}</td><td className="px-3 py-3">{r.executable}</td><td className="px-3 py-3">{score(r.avgScore)}</td></tr>)}</tbody>
          </table>
        </TableShell>
      ) : <Empty text="Detailed setup performance is not present in the live payload yet." />}
    </div>
  );
}

function ExecutionTab({ snapshot, orders, executions }: { snapshot?: AdvTerminalSnapshot; orders: Record<string, unknown>[]; executions: Record<string, unknown>[] }) {
  const flow = snapshot?.orderFlow ?? [];
  return (
    <div className="space-y-5">
      <SectionTitle icon={Zap} title="Execution Control" subtitle="Order flow, OMS orders, and execution timeline from live workstation data." />
      <div className="grid gap-3 sm:grid-cols-3">
        <MetricTile label="OMS Orders" value={String(orders.length)} tone="blue" />
        <MetricTile label="Executions" value={String(executions.length)} tone="green" />
        <MetricTile label="Flow Rows" value={String(flow.length)} tone="slate" />
      </div>
      <SignalTable rows={flow.map((f, idx) => ({ rank: idx + 1, symbol: f.symbol, side: asNum(f.buyPct) >= asNum(f.sellPct) ? "BUY" : "SELL", aiScore: 0, executionStatus: f.executionStatus ?? "FLOW", strategy: f.trend, rejectionReason: f.rejectionReason }))} />
    </div>
  );
}

function PortfolioTab({
  ws,
  positions,
  pnl,
  loadError,
  onRetry,
}: {
  ws?: Workstation;
  positions: Record<string, unknown>[];
  pnl: number;
  loadError?: unknown;
  onRetry?: () => void;
}) {
  return (
    <div className="space-y-5">
      <SectionTitle icon={BriefcaseBusiness} title="Portfolio And Risk" subtitle="Workstation position mirror and account summary." />
      {loadError && !ws ? <ErrorBox error={loadError} onRetry={onRetry ?? (() => undefined)} /> : null}
      <div className="grid gap-3 sm:grid-cols-3">
        <MetricTile label="Open Positions" value={String(positions.length)} tone="blue" />
        <MetricTile label="Today P&L" value={rupee.format(pnl)} tone={pnl >= 0 ? "green" : "red"} />
        <MetricTile label="Available Cash" value={rupee.format(getWsNum(ws, ["availableCash", "cash", "availableMargin"], 0))} tone="slate" />
      </div>
      {positions.length ? (
        <TableShell>
          <table className="min-w-full text-left text-sm">
            <thead className="bg-slate-50 text-xs uppercase text-slate-500 dark:bg-neutral-950"><tr><th className="px-3 py-2">Symbol</th><th className="px-3 py-2">Qty</th><th className="px-3 py-2">Avg</th><th className="px-3 py-2">LTP</th><th className="px-3 py-2">P&L</th></tr></thead>
            <tbody>{positions.map((p, idx) => <tr key={`${asText(p.symbol ?? p.tradingSymbol)}-${idx}`} className="border-t border-slate-100 dark:border-neutral-800"><td className="px-3 py-3 font-black">{asText(p.symbol ?? p.tradingSymbol)}</td><td className="px-3 py-3">{asText(p.quantity ?? p.qty)}</td><td className="px-3 py-3">{asText(p.averagePrice ?? p.avgPrice)}</td><td className="px-3 py-3">{asText(p.ltp ?? p.lastPrice)}</td><td className={cn("px-3 py-3 font-black", asNum(p.pnl ?? p.mtm) >= 0 ? "text-emerald-600" : "text-rose-600")}>{rupee.format(asNum(p.pnl ?? p.mtm))}</td></tr>)}</tbody>
          </table>
        </TableShell>
      ) : <Empty text="No open positions in the workstation payload." />}
    </div>
  );
}

function AdvancedTab({ snapshot }: { snapshot?: AdvTerminalSnapshot }) {
  const control = snapshot?.liveControl ?? {};
  const checks = [
    ["Platform live flag", control.platformLiveFlag],
    ["Live gate open", control.liveGateOpen],
    ["Feed operational", control.feedOperational],
    ["Websocket connected", control.websocketConnected],
    ["Safe startup ready", control.safeStartupReady],
    ["Market open", control.marketOpen],
  ] as const;
  return (
    <div className="space-y-5">
      <SectionTitle icon={Cpu} title="Advanced Diagnostics" subtitle="Truth source, feed status, safety flags, and system health." />
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
        {checks.map(([label, ok]) => (
          <Card key={label} className="flex items-center justify-between p-4">
            <div className="text-sm font-bold">{label}</div>
            {ok ? <CheckCircle2 className="h-5 w-5 text-emerald-600" /> : <AlertTriangle className="h-5 w-5 text-amber-600" />}
          </Card>
        ))}
      </div>
      <Card className="p-4">
        <div className="grid gap-3 text-sm sm:grid-cols-2">
          <div><span className="text-slate-500">Truth source</span><div className="font-black">{snapshot?.truthSource ?? "-"}</div></div>
          <div><span className="text-slate-500">Tick gap</span><div className="font-black">{asText(control.tickGapSeconds, "0")} sec</div></div>
          <div><span className="text-slate-500">Scan interval</span><div className="font-black">{snapshot?.scanIntervalSec ?? control.scanIntervalSec ?? 10} sec</div></div>
          <div><span className="text-slate-500">Session</span><div className="font-black">{snapshot?.sessionState ?? "-"}</div></div>
        </div>
      </Card>
    </div>
  );
}

function LiveTradingTab({ snapshot, rows }: { snapshot?: AdvTerminalSnapshot; rows: AdvScannerRow[] }) {
  const liveGate = Boolean(snapshot?.liveControl?.liveGateOpen);
  const liveEnabled = Boolean(snapshot?.liveControl?.liveEnabled ?? snapshot?.liveControl?.platformLiveFlag);
  return (
    <div className="space-y-5">
      <SectionTitle icon={Radio} title="Live Trading Readiness" subtitle="Preview-safe staging view. Use this to verify flow before production." />
      <div className="grid gap-3 sm:grid-cols-3">
        <MetricTile label="Live Enabled" value={liveEnabled ? "YES" : "NO"} tone={liveEnabled ? "green" : "amber"} />
        <MetricTile label="Live Gate" value={liveGate ? "OPEN" : "BLOCKED"} tone={liveGate ? "green" : "red"} />
        <MetricTile label="Executable Signals" value={String(rows.filter((r) => /EXEC/i.test(String(r.executionStatus))).length)} tone="blue" />
      </div>
      {!liveGate ? (
        <div className="flex items-center gap-3 rounded-lg border border-amber-200 bg-amber-50 p-4 text-sm font-semibold text-amber-800 dark:border-amber-900/60 dark:bg-amber-950/40 dark:text-amber-300">
          <Lock className="h-5 w-5" />
          Live order placement is blocked by the platform gate. Signals can be reviewed, but should not be assumed executed.
        </div>
      ) : (
        <div className="flex items-center gap-3 rounded-lg border border-emerald-200 bg-emerald-50 p-4 text-sm font-semibold text-emerald-800 dark:border-emerald-900/60 dark:bg-emerald-950/40 dark:text-emerald-300">
          <ShieldCheck className="h-5 w-5" />
          Live gate is open. Confirm broker account and order size before firing any manual trade.
        </div>
      )}
      <SignalTable rows={rows} showTradePlan />
    </div>
  );
}
