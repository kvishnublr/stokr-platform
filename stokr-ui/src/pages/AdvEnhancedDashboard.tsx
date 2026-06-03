import { useMemo, useState } from "react";
import { motion } from "framer-motion";
import { useQuery } from "@tanstack/react-query";
import {
  AlertTriangle,
  BarChart3,
  BriefcaseBusiness,
  CheckCircle2,
  Cpu,
  Crosshair,
  Gauge,
  LineChart,
  Loader2,
  Lock,
  Radio,
  RefreshCw,
  ShieldCheck,
  Sparkles,
  Zap,
} from "lucide-react";
import {
  type AdvScannerRow,
  type AdvSector,
  type AdvTerminalSnapshot,
  fetchAdvMovers,
  fetchAdvTerminal,
  fetchAdvWorkstation,
} from "../api/advDashboard";
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
  if (["EXECUTABLE", "EXECUTED", "OPEN", "LIVE", "SUCCESS", "OPERATIONAL"].some((x) => s.includes(x))) return "green";
  if (["WATCH", "COOLDOWN", "WARMUP", "PENDING"].some((x) => s.includes(x))) return "amber";
  if (["BLOCK", "REJECT", "STALE", "FAILED", "DISCONNECTED"].some((x) => s.includes(x))) return "red";
  return "blue";
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

function SignalTable({ rows, compact = false }: { rows: AdvScannerRow[]; compact?: boolean }) {
  if (!rows.length) return <Empty text="No live signals from the pipeline yet." />;
  return (
    <TableShell>
      <table className="min-w-full text-left text-sm">
        <thead className="bg-slate-50 text-[11px] uppercase tracking-wide text-slate-500 dark:bg-neutral-950 dark:text-neutral-400">
          <tr>
            <th className="px-3 py-2">Rank</th>
            <th className="px-3 py-2">Symbol</th>
            <th className="px-3 py-2">Side</th>
            <th className="px-3 py-2">Strategy</th>
            <th className="px-3 py-2">Score</th>
            <th className="px-3 py-2">Status</th>
            {!compact ? <th className="px-3 py-2">Reason</th> : null}
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100 dark:divide-neutral-800">
          {rows.map((row, idx) => (
            <tr key={`${row.signalId ?? row.symbol}-${idx}`} className="bg-white dark:bg-neutral-900">
              <td className="px-3 py-3 font-semibold text-slate-500">#{row.rank ?? idx + 1}</td>
              <td className="px-3 py-3">
                <div className="font-black text-slate-950 dark:text-neutral-100">{row.symbol}</div>
                <div className="text-xs text-slate-500">{asText(row.setupType ?? row.source, "scanner")}</div>
              </td>
              <td className="px-3 py-3">
                <Pill tone={String(row.side).toUpperCase() === "SELL" ? "red" : "green"}>{asText(row.side, "WATCH")}</Pill>
              </td>
              <td className="px-3 py-3 text-slate-600 dark:text-neutral-300">{asText(row.strategy, "-")}</td>
              <td className="px-3 py-3">
                <div className="font-black text-blue-600">{score(row.aiScore)}</div>
                <div className="text-xs text-slate-500">{pct(row.probability)}</div>
              </td>
              <td className="px-3 py-3">
                <Pill tone={statusTone(row.executionStatus) as "green" | "amber" | "red" | "blue"}>{asText(row.executionStatus, row.displayStatus ?? "-")}</Pill>
              </td>
              {!compact ? <td className="max-w-[280px] px-3 py-3 text-xs text-slate-500">{asText(row.rejectionReason ?? row.reason ?? row.pipelineStage, "-")}</td> : null}
            </tr>
          ))}
        </tbody>
      </table>
    </TableShell>
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
    .slice(0, 8);
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
            <div className="flex flex-wrap items-center justify-between gap-4">
              <div className="flex items-center gap-4">
                <div className="flex h-14 w-14 items-center justify-center rounded-lg bg-blue-600 text-white shadow-lg shadow-blue-600/20">
                  <Zap className="h-7 w-7" />
                </div>
                <div>
                  <div className="text-[11px] font-black uppercase tracking-[0.22em] text-blue-600">Institutional Grade</div>
                  <h2 className="text-2xl font-black tracking-normal text-slate-950 dark:text-neutral-50">Stokr Elite Enhanced</h2>
                  <div className="mt-1 flex flex-wrap items-center gap-2">
                    <Pill tone={snapshot?.marketOpen ? "green" : "amber"}>{snapshot?.sessionState ?? (snapshot?.marketOpen ? "MARKET OPEN" : "MARKET CLOSED")}</Pill>
                    <Pill tone={statusTone(liveControl.feedStatus) as "green" | "amber" | "red" | "blue"}>{liveControl.feedStatus ?? "FEED CHECK"}</Pill>
                    <Pill tone={terminalQ.isFetching ? "amber" : "green"}>{terminalQ.isFetching ? "Syncing" : `Synced ${timeAgo(terminalFresh)}`}</Pill>
                  </div>
                </div>
              </div>
              <div className="flex items-center gap-3">
                <div className="text-right">
                  <div className="text-xs font-bold uppercase text-slate-500">IST Time</div>
                  <div className="text-sm font-black text-slate-900 dark:text-neutral-100">{snapshot?.istTime ?? new Date().toLocaleTimeString("en-IN")}</div>
                </div>
                <button
                  type="button"
                  onClick={refreshAll}
                  className="inline-flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm font-bold text-slate-700 shadow-sm hover:bg-slate-50 disabled:opacity-60 dark:border-neutral-800 dark:bg-neutral-950 dark:text-neutral-200"
                  disabled={terminalQ.isFetching || moversQ.isFetching || workstationQ.isFetching}
                >
                  <RefreshCw className={cn("h-4 w-4", (terminalQ.isFetching || moversQ.isFetching || workstationQ.isFetching) && "animate-spin")} />
                  Refresh
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
              {activeTab === "dashboard" && <DashboardTab rows={topRows} movers={movers} snapshot={snapshot} />}
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

function DashboardTab({ rows, movers, snapshot }: { rows: AdvScannerRow[]; movers: Mover[]; snapshot?: AdvTerminalSnapshot }) {
  return (
    <div className="space-y-5">
      <SectionTitle icon={Gauge} title="Live Market Dashboard" subtitle={`Source: ${snapshot?.truthSource ?? "terminal API"} · scan ${snapshot?.scanIntervalSec ?? snapshot?.liveControl?.scanIntervalSec ?? 10}s`} />
      <div className="grid gap-4 lg:grid-cols-[1.5fr_1fr]">
        <div>
          <div className="mb-3 flex items-center justify-between">
            <h4 className="text-sm font-black text-slate-900 dark:text-neutral-100">Highest Quality Setups</h4>
            <Pill tone="green">{rows.length} ranked</Pill>
          </div>
          <SignalTable rows={rows} compact />
        </div>
        <div className="space-y-3">
          <h4 className="text-sm font-black text-slate-900 dark:text-neutral-100">Live Movers</h4>
          {movers.slice(0, 8).map((m) => {
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
          {!movers.length ? <Empty text="No mover feed rows loaded yet." /> : null}
        </div>
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
      <SignalTable rows={rows.slice(0, 14)} />
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
      <div className="grid gap-4 lg:grid-cols-2">
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
      <SignalTable rows={rows} />
    </div>
  );
}
