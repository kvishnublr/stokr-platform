import type { ComponentType, ReactNode } from "react";
import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Activity,
  AlertTriangle,
  ArrowDownRight,
  ArrowUpRight,
  BarChart3,
  BriefcaseBusiness,
  Clock3,
  Loader2,
  RefreshCw,
  ShieldCheck,
  Sparkles,
  TrendingUp,
  Zap,
} from "lucide-react";
import {
  fetchAdvEdge,
  fetchAdvExecutionSummary,
  fetchAdvMovers,
  fetchAdvTerminal,
  fetchAdvWorkstation,
  type AdvEdgeSnapshot,
  type AdvScannerRow,
} from "../api/advDashboard";
import { parseAxiosMessage } from "../api/client";
import { fmtTime } from "../lib/dateUtils";
import { cn } from "../lib/utils";

type Workstation = {
  accountSummary?: Record<string, unknown>;
  openPositions?: Record<string, unknown>[];
  closedPositions?: Record<string, unknown>[];
  orders?: Record<string, unknown>[];
  executions?: Record<string, unknown>[];
};

const rupee = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 0 });
const numberFmt = new Intl.NumberFormat("en-IN", { maximumFractionDigits: 2 });

function asNum(value: unknown, fallback = 0) {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string") {
    const cleaned = value.replace(/[^\d.-]/g, "");
    const parsed = Number(cleaned);
    if (Number.isFinite(parsed)) return parsed;
  }
  return fallback;
}

function asText(value: unknown, fallback = "-") {
  if (value === null || value === undefined || value === "") return fallback;
  return String(value);
}

function fmtMoney(value: unknown) {
  const n = asNum(value, Number.NaN);
  return Number.isFinite(n) ? rupee.format(n) : "-";
}

function fmtPct(value: unknown) {
  const n = asNum(value, Number.NaN);
  return Number.isFinite(n) ? `${numberFmt.format(n)}%` : "-";
}

function fmtScore(value: unknown) {
  const n = asNum(value, Number.NaN);
  return Number.isFinite(n) ? Math.round(n).toString() : "-";
}

function statusTone(value: string) {
  const v = value.toUpperCase();
  if (v.includes("EXECUTED") || v.includes("LIVE") || v.includes("OPEN") || v.includes("READY")) return "green";
  if (v.includes("EXECUTABLE") || v.includes("WATCH") || v.includes("PAPER") || v.includes("PENDING")) return "amber";
  if (v.includes("BLOCK") || v.includes("REJECT") || v.includes("STALE") || v.includes("DISCONNECTED")) return "red";
  return "blue";
}

function toneClass(tone: "green" | "amber" | "red" | "blue" | "slate") {
  switch (tone) {
    case "green":
      return "border-emerald-200 bg-emerald-50 text-emerald-700";
    case "amber":
      return "border-amber-200 bg-amber-50 text-amber-700";
    case "red":
      return "border-rose-200 bg-rose-50 text-rose-700";
    case "slate":
      return "border-slate-200 bg-slate-50 text-slate-600";
    default:
      return "border-blue-200 bg-blue-50 text-blue-700";
  }
}

function Card({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn("rounded-2xl border border-slate-200 bg-white shadow-sm", className)}>{children}</div>;
}

function Pill({ children, tone = "blue" }: { children: ReactNode; tone?: "green" | "amber" | "red" | "blue" | "slate" }) {
  return <span className={cn("inline-flex items-center rounded-full border px-2.5 py-1 text-xs font-semibold", toneClass(tone))}>{children}</span>;
}

function MetricCard({
  label,
  value,
  hint,
  tone = "blue",
  icon: Icon,
}: {
  label: string;
  value: string;
  hint?: string;
  tone?: "green" | "amber" | "red" | "blue" | "slate";
  icon: ComponentType<{ className?: string }>;
}) {
  return (
    <Card className="p-3 sm:p-4 md:p-5">
      <div className="flex items-start justify-between gap-2 sm:gap-3">
        <div className="min-w-0 flex-1">
          <div className="text-[10px] font-bold uppercase tracking-wide text-slate-500 sm:text-[11px]">{label}</div>
          <div className={cn("mt-1 text-lg font-black tracking-tight sm:mt-2 sm:text-2xl", tone === "green" ? "text-emerald-700" : tone === "red" ? "text-rose-700" : tone === "amber" ? "text-amber-700" : tone === "slate" ? "text-slate-900" : "text-blue-700")}>
            {value}
          </div>
          {hint ? <div className="mt-1 text-xs font-medium text-slate-500">{hint}</div> : null}
        </div>
        <div className={cn("flex h-9 w-9 shrink-0 items-center justify-center rounded-xl border sm:h-10 sm:w-10", toneClass(tone))}>
          <Icon className="h-4 w-4 sm:h-5 sm:w-5" />
        </div>
      </div>
    </Card>
  );
}

function SectionTitle({
  icon: Icon,
  title,
  subtitle,
}: {
  icon: ComponentType<{ className?: string }>;
  title: string;
  subtitle?: string;
}) {
  return (
    <div className="flex items-start justify-between gap-3">
      <div className="flex items-center gap-3">
        <div className="flex h-10 w-10 items-center justify-center rounded-xl border border-slate-200 bg-slate-50 text-slate-700">
          <Icon className="h-4 w-4" />
        </div>
        <div>
          <h2 className="text-base font-bold text-slate-950">{title}</h2>
          {subtitle ? <p className="text-xs font-medium text-slate-500">{subtitle}</p> : null}
        </div>
      </div>
    </div>
  );
}

function TableShell({ children }: { children: ReactNode }) {
  return <div className="overflow-hidden rounded-xl border border-slate-200 bg-white">{children}</div>;
}

function EmptyState({ text }: { text: string }) {
  return <div className="rounded-xl border border-dashed border-slate-300 bg-slate-50 p-6 text-center text-sm font-medium text-slate-500">{text}</div>;
}

function rowKey(row: AdvScannerRow, idx: number) {
  return row.signalId ?? `${row.symbol}-${row.strategy ?? ""}-${row.side ?? ""}-${idx}`;
}

function normalizeSymbol(value: unknown) {
  return String(value ?? "")
    .replace(/^NSE:/i, "")
    .replace(/^BSE:/i, "")
    .trim()
    .toUpperCase();
}

function positionSymbol(row: Record<string, unknown>) {
  return normalizeSymbol(row.symbol ?? row.tradingsymbol ?? row.tradingSymbol);
}

function isOpenPosition(row: Record<string, unknown>) {
  return asNum(row.quantity ?? row.qty, 0) !== 0 || asNum(row.netQty ?? row.netqty, 0) !== 0;
}

function dedupeRows(rows: AdvScannerRow[]) {
  const seen = new Set<string>();
  return rows.filter((row, idx) => {
    const key = rowKey(row, idx);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function firstFinite(...values: unknown[]) {
  for (const value of values) {
    const n = asNum(value, Number.NaN);
    if (Number.isFinite(n)) return n;
  }
  return 0;
}

/**
 * Edge & Go-Live: the per-strategy decision panel. Rolling counterfactual entry edge
 * (which level got touched first — target or stop) vs the breakeven implied by each
 * strategy's own risk:reward, plus edge-gate demotions and today's fill health.
 */
function EdgeGoLivePanel({ edge, loading }: { edge: AdvEdgeSnapshot | undefined; loading: boolean }) {
  const rows = edge?.entryEdge ?? [];
  const demotions = edge?.activeDemotions ?? [];
  const fills = edge?.todayOrders ?? {};
  const total = asNum(fills.total, 0);
  const filled = asNum(fills.filled, 0);
  const failed = asNum(fills.failed, 0);
  const fillRate = total > 0 ? Math.round((filled / total) * 100) : null;

  return (
    <Card>
      <div className="border-b border-slate-200 px-4 py-4 sm:px-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <SectionTitle
            icon={ShieldCheck}
            title="Edge & Go-Live"
            subtitle="Rolling 14-session entry edge per strategy — decide what deserves LIVE capital"
          />
          <div className="flex flex-wrap items-center gap-2">
            <Pill tone={fillRate === null ? "slate" : fillRate > 0 ? "green" : "red"}>
              {fillRate === null ? "NO ORDERS TODAY" : `FILL RATE ${fillRate}%`}
            </Pill>
            {failed > 0 ? <Pill tone="red">{failed} FAILED</Pill> : null}
            {demotions.length > 0 ? <Pill tone="amber">{demotions.length} DEMOTED</Pill> : null}
          </div>
        </div>
      </div>
      <div className="overflow-x-auto p-4 sm:p-5">
        {loading && !rows.length ? (
          <div className="flex min-h-[120px] items-center justify-center gap-2 text-xs font-semibold text-slate-500 sm:text-sm">
            <Loader2 className="h-4 w-4 animate-spin" />
            Loading entry-edge data
          </div>
        ) : rows.length ? (
          <TableShell>
            <table className="min-w-full text-left text-xs sm:text-sm">
              <thead className="bg-slate-50 text-[10px] uppercase tracking-wide text-slate-500">
                <tr>
                  <th className="px-3 py-2.5">Strategy</th>
                  <th className="px-3 py-2.5">Mode</th>
                  <th className="px-3 py-2.5">Target-first %</th>
                  <th className="px-3 py-2.5">Breakeven</th>
                  <th className="px-3 py-2.5">Avg R:R</th>
                  <th className="px-3 py-2.5">Signals</th>
                  <th className="px-3 py-2.5">Replay P&L</th>
                  <th className="px-3 py-2.5">Verdict</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {rows.map((row) => {
                  const demoted = demotions.some((d) => d.strategy_key === row.strategy_name);
                  const tf = row.target_first_pct;
                  const be = row.breakeven_pct;
                  const margin = tf !== null && be !== null ? tf - be : null;
                  return (
                    <tr key={row.strategy_name} className="align-middle">
                      <td className="px-3 py-2.5 font-bold text-slate-950">{row.strategy_name}</td>
                      <td className="px-3 py-2.5">
                        <Pill tone={demoted ? "red" : row.live_whitelisted ? "green" : "slate"}>
                          {demoted ? "DEMOTED" : row.live_whitelisted ? "LIVE" : "PAPER"}
                        </Pill>
                      </td>
                      <td className={cn("px-3 py-2.5 font-black", row.has_edge ? "text-emerald-700" : "text-rose-700")}>
                        {tf !== null ? `${tf}%` : "-"}
                      </td>
                      <td className="px-3 py-2.5 text-slate-500">{be !== null ? `${be}%` : "-"}</td>
                      <td className="px-3 py-2.5 font-medium text-slate-700">{row.avg_rr ?? "-"}</td>
                      <td className="px-3 py-2.5 text-slate-600">{row.signals}</td>
                      <td className={cn("px-3 py-2.5 font-bold", asNum(row.replay_pnl, 0) >= 0 ? "text-emerald-700" : "text-rose-700")}>
                        {row.replay_pnl ?? "-"}
                      </td>
                      <td className="px-3 py-2.5">
                        {margin === null ? (
                          <span className="text-xs text-slate-400">insufficient data</span>
                        ) : margin >= 8 ? (
                          <Pill tone="green">STRONG EDGE +{margin.toFixed(1)}pp</Pill>
                        ) : margin > 0 ? (
                          <Pill tone="amber">THIN EDGE +{margin.toFixed(1)}pp</Pill>
                        ) : (
                          <Pill tone="red">NO EDGE {margin.toFixed(1)}pp</Pill>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </TableShell>
        ) : (
          <EmptyState text="Entry-edge data appears after the first nightly replay (15:50 IST). Until then all strategies trade on their configured mode." />
        )}
        {demotions.length > 0 ? (
          <div className="mt-3 rounded-xl border border-rose-200 bg-rose-50 p-3 text-xs font-medium text-rose-700">
            {demotions.map((d) => (
              <div key={d.strategy_key}>
                <span className="font-bold">{d.strategy_key}</span> demoted to PAPER — {d.reason ?? "edge below breakeven"}
              </div>
            ))}
          </div>
        ) : null}
      </div>
    </Card>
  );
}

export function ModernDashboard() {
  const retry = (failureCount: number, error: unknown) => {
    if (failureCount >= 4) return false;
    if (!error || typeof error !== "object") return failureCount < 2;
    const status = (error as { response?: { status?: number } }).response?.status;
    const code = (error as { code?: string }).code;
    return status === 502 || status === 503 || status === 504 || code === "ECONNABORTED" || code === "ERR_NETWORK";
  };

  const terminalQ = useQuery({
    queryKey: ["adv-dashboard", "terminal"],
    queryFn: fetchAdvTerminal,
    staleTime: 1_000,
    refetchInterval: 5_000,
    refetchIntervalInBackground: true,
    refetchOnWindowFocus: "always",
    retry,
    retryDelay: (attempt) => Math.min(2_000 * 2 ** attempt, 20_000),
    placeholderData: (prev) => prev,
  });

  const moversQ = useQuery({
    queryKey: ["adv-dashboard", "movers"],
    queryFn: fetchAdvMovers,
    staleTime: 2_000,
    refetchInterval: 10_000,
    refetchIntervalInBackground: true,
    refetchOnWindowFocus: "always",
    retry,
    retryDelay: (attempt) => Math.min(2_000 * 2 ** attempt, 20_000),
    placeholderData: (prev) => prev,
  });

  const workstationQ = useQuery({
    queryKey: ["adv-dashboard", "workstation"],
    queryFn: fetchAdvWorkstation,
    staleTime: 2_000,
    refetchInterval: 10_000,
    refetchIntervalInBackground: true,
    refetchOnWindowFocus: "always",
    retry,
    retryDelay: (attempt) => Math.min(2_000 * 2 ** attempt, 20_000),
    placeholderData: (prev) => prev,
  });

  const executionSummaryQ = useQuery({
    queryKey: ["adv-dashboard", "execution-summary"],
    queryFn: fetchAdvExecutionSummary,
    staleTime: 5_000,
    refetchInterval: 15_000,
    refetchIntervalInBackground: true,
    refetchOnWindowFocus: "always",
    retry,
    retryDelay: (attempt) => Math.min(2_000 * 2 ** attempt, 20_000),
    placeholderData: (prev) => prev,
  });

  const advEdgeQ = useQuery({
    queryKey: ["adv-dashboard", "edge"],
    queryFn: fetchAdvEdge,
    staleTime: 30_000,
    refetchInterval: 60_000,
    refetchIntervalInBackground: true,
    retry,
    retryDelay: (attempt) => Math.min(2_000 * 2 ** attempt, 20_000),
    placeholderData: (prev) => prev,
  });

  const snapshot = terminalQ.data;
  const movers = moversQ.data ?? [];
  const ws = workstationQ.data as Workstation | undefined;
  const execSummary = executionSummaryQ.data ?? {};

  const allRows = useMemo(() => {
    const combined = [...(snapshot?.scannerRows ?? []), ...(snapshot?.liveCards ?? [])];
    return dedupeRows(combined)
      .slice()
      .sort((a, b) => asNum(b.aiScore, 0) - asNum(a.aiScore, 0));
  }, [snapshot]);

  const liveRows = allRows.slice(0, 10);
  const executableRows = allRows.filter((row) => String(row.executionStatus ?? "").toUpperCase().includes("EXEC"));
  const blockedRows = allRows.filter((row) => /BLOCK|REJECT|COOLDOWN/i.test(String(row.executionStatus ?? "")));
  const openPositions = Array.isArray(ws?.openPositions) ? ws.openPositions.filter(isOpenPosition) : [];
  const orders = Array.isArray(ws?.orders) ? ws.orders : [];
  const executions = Array.isArray(ws?.executions) ? ws.executions : [];

  const liveControl = snapshot?.liveControl ?? {};
  const feedStatus = asText(liveControl.feedStatus, "UNKNOWN");
  const feedStale = Boolean(liveControl.feedEquityStale || liveControl.feedIndexStale || feedStatus === "STALE");
  const marketOpen = Boolean(snapshot?.marketOpen ?? liveControl.marketOpen);
  const lastSynced = terminalQ.dataUpdatedAt ? fmtTime(terminalQ.dataUpdatedAt) : "-";

  const accountSummary = ws?.accountSummary ?? {};
  const todayPnl = firstFinite(
    accountSummary.totalPnl,
    accountSummary.realizedPnl,
    (execSummary as Record<string, unknown>).todayPnl,
    snapshot?.metrics?.todayPnL,
  );

  const winRate = asNum((execSummary as Record<string, unknown>).winRate, asNum(snapshot?.metrics?.winRate, 0));
  const openPositionCount = openPositions.length || asNum(accountSummary.openPositions, 0);
  const activeStrategies = asNum(accountSummary.activeStrategies, asNum(snapshot?.metrics?.activeStrategies, allRows.length));
  const scanInterval = asNum(snapshot?.scanIntervalSec ?? liveControl.scanIntervalSec, 10);
  const staleBanner = feedStale || liveControl.feedWarmup === true || liveControl.safeStartupReady === false;

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <div className="mx-auto max-w-7xl space-y-6 px-4 py-4 sm:px-5 md:px-6 lg:px-8">
        <Card className="overflow-hidden">
          <div className="border-b border-slate-200 bg-white px-4 py-4 sm:px-5 md:px-6">
            <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
              <div className="flex items-start gap-3 sm:gap-4">
                <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl bg-blue-600 text-white shadow-sm sm:h-14 sm:w-14">
                  <Zap className="h-6 w-6 sm:h-7 sm:w-7" />
                </div>
                <div>
                  <div className="text-[10px] font-black uppercase tracking-[0.24em] text-blue-600 sm:text-[11px]">ADV Dashboard</div>
                  <h1 className="mt-1 text-xl font-black tracking-tight text-slate-950 sm:text-2xl md:text-3xl">Trading Dashboard</h1>
                  <p className="mt-1 max-w-2xl text-sm font-medium text-slate-500">
                    Live terminal, movers, workstation, and execution data refreshed continuously from the trading pipeline.
                  </p>
                  <div className="mt-3 flex flex-wrap items-center gap-2">
                    <Pill tone={marketOpen ? "green" : "amber"}>{marketOpen ? "MARKET OPEN" : "MARKET CLOSED"}</Pill>
                    <Pill tone={feedStale ? "red" : "green"}>{feedStatus}</Pill>
                    <Pill tone={terminalQ.isFetching ? "amber" : "blue"}>{terminalQ.isFetching ? "SYNCING" : `SYNCED ${lastSynced}`}</Pill>
                    <Pill tone={liveControl.liveGateOpen ? "green" : "amber"}>{liveControl.liveGateOpen ? "LIVE GATE OPEN" : "LIVE GATE CLOSED"}</Pill>
                    {staleBanner ? <Pill tone="red">FEED CHECK</Pill> : null}
                  </div>
                </div>
              </div>

              <div className="flex items-center gap-3">
                <div className="text-right">
                  <div className="text-[11px] font-bold uppercase tracking-wide text-slate-500">Scan interval</div>
                  <div className="text-sm font-black text-slate-900">{scanInterval}s</div>
                </div>
                <button
                  type="button"
                  onClick={() => {
                    void terminalQ.refetch();
                    void moversQ.refetch();
                    void workstationQ.refetch();
                    void executionSummaryQ.refetch();
                  }}
                  className="inline-flex items-center gap-2 rounded-xl border border-slate-200 bg-white px-4 py-2.5 text-sm font-bold text-slate-700 shadow-sm hover:bg-slate-50 disabled:opacity-60"
                  disabled={terminalQ.isFetching || moversQ.isFetching || workstationQ.isFetching || executionSummaryQ.isFetching}
                >
                  <RefreshCw className={cn("h-4 w-4", (terminalQ.isFetching || moversQ.isFetching || workstationQ.isFetching || executionSummaryQ.isFetching) && "animate-spin")} />
                  Refresh
                </button>
              </div>
            </div>
          </div>

          <div className="grid grid-cols-1 gap-3 p-4 sm:p-5 sm:grid-cols-2 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5 md:gap-4">
            <MetricCard label="Market Regime" value={asText(snapshot?.marketRegime, "Loading")} hint={asText(snapshot?.regimeNarrative, "Waiting for live snapshot")} tone="slate" icon={BarChart3} />
            <MetricCard label="Active Signals" value={String(allRows.length)} hint={`${executableRows.length} executable`} tone="green" icon={Sparkles} />
            <MetricCard label="Blocked / Cooldown" value={String(blockedRows.length)} hint="Rejected or cooling setups" tone={blockedRows.length ? "amber" : "green"} icon={AlertTriangle} />
            <MetricCard label="Today P&L" value={fmtMoney(todayPnl)} hint={`${openPositionCount} open positions`} tone={todayPnl >= 0 ? "green" : "red"} icon={TrendingUp} />
            <MetricCard label="Win Rate" value={winRate ? `${Math.round(winRate)}%` : "-"} hint={`${activeStrategies} active strategies`} tone="blue" icon={ShieldCheck} />
          </div>
        </Card>

        {staleBanner ? (
          <Card className="border-amber-200 bg-amber-50 p-4">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="flex items-center gap-2 text-sm font-semibold text-amber-800">
                <AlertTriangle className="h-4 w-4" />
                Live feed needs attention
              </div>
              <div className="text-xs font-medium text-amber-700">
                {liveControl.feedWarmup ? "Feed warmup in progress." : feedStale ? "Equity or index feed is stale." : "Startup readiness is not complete."}
              </div>
            </div>
          </Card>
        ) : null}

        <EdgeGoLivePanel edge={advEdgeQ.data} loading={advEdgeQ.isLoading} />

        <div className="grid grid-cols-1 gap-4 sm:gap-5 md:gap-6 md:grid-cols-2 lg:grid-cols-3">
          <Card className="md:col-span-2">
            <div className="border-b border-slate-200 px-4 py-4 sm:px-5">
              <SectionTitle icon={Activity} title="Live Signals" subtitle="Fresh scanner output from the current pipeline snapshot" />
            </div>
            <div className="overflow-x-auto p-4 sm:p-5">
              {terminalQ.isLoading && !snapshot ? (
                <div className="flex min-h-[220px] items-center justify-center gap-2 text-xs sm:text-sm font-semibold text-slate-500">
                  <Loader2 className="h-4 w-4 animate-spin" />
                  Loading terminal data
                </div>
              ) : liveRows.length ? (
                <TableShell>
                  <table className="min-w-full text-left text-xs sm:text-sm">
                    <thead className="bg-slate-50 text-[10px] uppercase tracking-wide text-slate-500">
                      <tr>
                        <th className="px-3 py-2.5">#</th>
                        <th className="px-3 py-2.5">Symbol</th>
                        <th className="px-3 py-2.5">Side</th>
                        <th className="px-3 py-2.5">Score</th>
                        <th className="px-3 py-2.5">Status</th>
                        <th className="px-3 py-2.5">Entry</th>
                        <th className="px-3 py-2.5">Target</th>
                        <th className="px-3 py-2.5">Reason</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {liveRows.map((row, idx) => {
                        const side = asText(row.side, "WATCH").toUpperCase();
                        const st = asText(row.executionStatus ?? row.displayStatus, "-");
                        return (
                          <tr key={rowKey(row, idx)} className="align-middle">
                            <td className="px-3 py-2.5 text-xs font-semibold text-slate-400">{row.rank ?? idx + 1}</td>
                            <td className="px-3 py-2.5">
                              <div className="font-bold text-slate-950">{row.symbol}</div>
                              <div className="text-[11px] text-slate-500">{asText(row.strategy ?? row.setupType, "scanner")}</div>
                            </td>
                            <td className="px-3 py-2.5">
                              <span className={cn("inline-flex rounded-md px-2 py-0.5 text-[11px] font-bold uppercase", side === "SELL" ? "bg-rose-50 text-rose-700" : "bg-emerald-50 text-emerald-700")}>
                                {side}
                              </span>
                            </td>
                            <td className="px-3 py-2.5 font-bold text-blue-700">{fmtScore(row.aiScore)}</td>
                            <td className="px-3 py-2.5">
                              <Pill tone={statusTone(st) as "green" | "amber" | "red" | "blue"}>{st}</Pill>
                            </td>
                            <td className="px-3 py-2.5 font-medium text-slate-700">{fmtMoney(row.entryPrice ?? row.entryZoneLow ?? row.ltp)}</td>
                            <td className="px-3 py-2.5 font-medium text-emerald-700">{fmtMoney(row.targetPrice)}</td>
                            <td className="max-w-[280px] px-3 py-2.5 text-xs leading-snug text-slate-500">{asText(row.reason ?? row.rejectionReason ?? row.pipelineStage, "-")}</td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </TableShell>
              ) : (
                <EmptyState text="No live scanner rows are available yet." />
              )}
            </div>
          </Card>

          <Card>
            <div className="border-b border-slate-200 px-5 py-4">
              <SectionTitle icon={BriefcaseBusiness} title="Market Movers" subtitle="Top activity from the movers service" />
            </div>
            <div className="p-5">
              {moversQ.isLoading && !movers.length ? (
                <div className="flex min-h-[220px] items-center justify-center gap-2 text-sm font-semibold text-slate-500">
                  <Loader2 className="h-4 w-4 animate-spin" />
                  Loading movers
                </div>
              ) : movers.length ? (
                <div className="space-y-3">
                  {movers.slice(0, 8).map((mover) => (
                    <div key={mover.symbol} className="rounded-xl border border-slate-200 bg-slate-50 p-3">
                      <div className="flex items-start justify-between gap-3">
                        <div>
                          <div className="font-bold text-slate-950">{mover.symbol}</div>
                          <div className="text-[11px] text-slate-500">{asText(mover.source, "live scan")}</div>
                        </div>
                        <div className="text-right">
                          <div className="text-sm font-black text-slate-900">{asText(mover.price, "-")}</div>
                          <div className={cn("text-xs font-semibold", asNum(mover.changePct, 0) >= 0 ? "text-emerald-600" : "text-rose-600")}>
                            {asNum(mover.changePct, 0) >= 0 ? <ArrowUpRight className="mr-1 inline h-3.5 w-3.5" /> : <ArrowDownRight className="mr-1 inline h-3.5 w-3.5" />}
                            {fmtPct(mover.changePct)}
                          </div>
                        </div>
                      </div>
                      <div className="mt-2 flex items-center justify-between text-[11px] text-slate-500">
                        <span>AI score</span>
                        <span className="font-bold text-blue-700">{fmtScore(mover.aiScore)}</span>
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <EmptyState text="No movers returned yet." />
              )}
            </div>
          </Card>
        </div>

        <div className="grid grid-cols-1 gap-4 sm:gap-5 md:gap-6 md:grid-cols-2 lg:grid-cols-3">
          <Card>
            <div className="border-b border-slate-200 px-4 py-4 sm:px-5">
              <SectionTitle icon={BriefcaseBusiness} title="Open Positions" subtitle="Positions currently held by the workstation" />
            </div>
            <div className="p-4 sm:p-5">
              {openPositions.length ? (
                <div className="space-y-3">
                  {openPositions.slice(0, 6).map((pos, idx) => (
                    <div key={`${positionSymbol(pos)}-${idx}`} className="rounded-xl border border-slate-200 p-3">
                      <div className="flex items-start justify-between gap-3">
                        <div>
                          <div className="font-bold text-slate-950">{positionSymbol(pos)}</div>
                          <div className="text-[11px] text-slate-500">{asText(pos.exchange ?? pos.segment, "position")}</div>
                        </div>
                        <div className="text-right">
                          <div className="text-sm font-black text-slate-900">{numberFmt.format(asNum(pos.qty ?? pos.quantity ?? pos.netQty, 0))}</div>
                          <div className="text-[11px] text-slate-500">Qty</div>
                        </div>
                      </div>
                      <div className="mt-2 flex flex-wrap gap-3 text-xs text-slate-500">
                        <span>Entry {fmtMoney(pos.averagePrice ?? pos.entryPrice)}</span>
                        <span>LTP {fmtMoney(pos.ltp ?? pos.lastPrice)}</span>
                        <span>P&L {fmtMoney(pos.pnl ?? pos.mtmPnl ?? pos.realizedPnl)}</span>
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <EmptyState text="No open positions found in the workspace payload." />
              )}
            </div>
          </Card>

          <Card>
            <div className="border-b border-slate-200 px-4 py-4 sm:px-5">
              <SectionTitle icon={Clock3} title="Execution Snapshot" subtitle="Orders and fills from the workstation payload" />
            </div>
            <div className="space-y-4 p-4 sm:p-5">
              <div className="grid grid-cols-1 gap-2 sm:grid-cols-3 sm:gap-3">
                {[
                  { label: "Orders", value: String(orders.length), tone: "blue" as const },
                  { label: "Executions", value: String(executions.length), tone: "green" as const },
                  { label: "Signals", value: String(allRows.length), tone: "slate" as const },
                ].map((item) => (
                  <div key={item.label} className="rounded-xl border border-slate-200 bg-slate-50 p-3">
                    <div className="text-[10px] font-bold uppercase tracking-wide text-slate-500">{item.label}</div>
                    <div className={cn("mt-1 text-xl font-black", item.tone === "green" ? "text-emerald-700" : item.tone === "slate" ? "text-slate-900" : "text-blue-700")}>{item.value}</div>
                  </div>
                ))}
              </div>
              <div className="text-xs leading-6 text-slate-500">
                <div>Feed status: <span className="font-semibold text-slate-800">{feedStatus}</span></div>
                <div>Live gate: <span className="font-semibold text-slate-800">{liveControl.liveGateOpen ? "open" : "closed"}</span></div>
                <div>Startup ready: <span className="font-semibold text-slate-800">{liveControl.safeStartupReady ? "yes" : "no"}</span></div>
                <div>Websocket: <span className="font-semibold text-slate-800">{liveControl.websocketConnected ? "connected" : "disconnected"}</span></div>
              </div>
            </div>
          </Card>

          <Card>
            <div className="border-b border-slate-200 px-4 py-4 sm:px-5">
              <SectionTitle icon={ShieldCheck} title="Confidence / Quality" subtitle="From the current execution summary and terminal snapshot" />
            </div>
            <div className="space-y-3 p-4 sm:p-5">
              {[
                { label: "Win rate", value: winRate ? `${Math.round(winRate)}%` : "-" },
                { label: "Execution gate", value: liveControl.liveGateOpen ? "Live orders are permitted" : "Live orders are blocked" },
                { label: "Data freshness", value: terminalQ.isFetching ? "Refreshing now" : `Last sync ${lastSynced}` },
                { label: "Quality blocks", value: `${blockedRows.length} blocked or cooling setups` },
              ].map((row) => (
                <div key={row.label} className="flex items-center justify-between rounded-xl border border-slate-200 bg-slate-50 px-4 py-3">
                  <div className="text-[11px] font-bold uppercase tracking-wide text-slate-500">{row.label}</div>
                  <div className="text-sm font-semibold text-slate-800">{row.value}</div>
                </div>
              ))}
            </div>
          </Card>
        </div>

        {terminalQ.error || moversQ.error || workstationQ.error || executionSummaryQ.error ? (
          <Card className="border-rose-200 bg-rose-50 p-4 text-sm text-rose-700">
            <div className="flex flex-wrap items-center gap-2">
              <AlertTriangle className="h-4 w-4" />
              <span>{parseAxiosMessage(terminalQ.error ?? moversQ.error ?? workstationQ.error ?? executionSummaryQ.error)}</span>
            </div>
          </Card>
        ) : null}
      </div>
    </div>
  );
}

export default ModernDashboard;
