import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  AdvScannerRow,
  AdvTerminalSnapshot,
  fetchAdvExecutionSummary,
  fetchAdvTerminal,
  fetchAdvWatch,
  fetchAdvWorkstation,
} from "../api/advDashboard";
import "./adv/adv-terminal.css";

type TabId = "intelligence" | "orderflow" | "decisions" | "sectors" | "risk" | "performance";

const TABS: { id: TabId; label: string }[] = [
  { id: "intelligence", label: "Intelligence" },
  { id: "orderflow", label: "Order Flow" },
  { id: "decisions", label: "System Decisions" },
  { id: "sectors", label: "Sectors" },
  { id: "risk", label: "Risk Matrix" },
  { id: "performance", label: "Performance" },
];

type EnrichedRow = AdvScannerRow & { ltpDisplay: string; changePct: number };

export function AdvDashboardPage() {
  const [tab, setTab] = useState<TabId>("intelligence");
  const [selected, setSelected] = useState<EnrichedRow | null>(null);

  const terminalQ = useQuery({
    queryKey: ["adv-terminal"],
    queryFn: fetchAdvTerminal,
    refetchInterval: 10_000,
    retry: 2,
  });

  const watchQ = useQuery({ queryKey: ["adv-watch"], queryFn: fetchAdvWatch, refetchInterval: 10_000 });
  const execQ = useQuery({ queryKey: ["adv-exec"], queryFn: fetchAdvExecutionSummary, refetchInterval: 15_000 });
  const wsQ = useQuery({ queryKey: ["adv-ws"], queryFn: fetchAdvWorkstation, refetchInterval: 15_000 });

  const data = terminalQ.data;
  const rows = useEnrichedRows(data, watchQ.data);

  const refresh = () => {
    void terminalQ.refetch();
    void watchQ.refetch();
    void execQ.refetch();
    void wsQ.refetch();
  };

  return (
    <div className="adv-terminal">
      <header className="adv-header">
        <div>
          <div className="adv-title">
            <span className="adv-logo">◎</span>
            Intraday Intelligence
          </div>
          <p className="adv-sub">
            AI-powered scanner · Production pipeline · scan every {data?.scanIntervalSec ?? data?.liveControl?.scanIntervalSec ?? 10}s
          </p>
        </div>
        <div className="adv-pills">
          <div className="adv-pill">
            <strong>{data?.marketOpen ? "MARKET OPEN" : "MARKET CLOSED"}</strong>
            <div>{data?.istTime ?? "—"} IST</div>
          </div>
          <div className="adv-pill">
            Regime: <strong>{data?.marketRegime?.replace(/_/g, " ") ?? "—"}</strong>
          </div>
          <div className="adv-pill adv-pill-truth">{data?.truthSource ?? "PRODUCTION_PIPELINE"}</div>
          <button type="button" className="adv-pill adv-pill-btn" onClick={refresh} disabled={terminalQ.isFetching}>
            {terminalQ.isFetching ? "Refreshing…" : "Refresh"}
          </button>
        </div>
      </header>

      <div className="adv-tabs">
        {TABS.map((t) => (
          <button key={t.id} type="button" className={`adv-tab${tab === t.id ? " on" : ""}`} onClick={() => setTab(t.id)}>
            {t.label}
          </button>
        ))}
        <span className="adv-live-badge"><span className="adv-live-dot" /> LIVE</span>
      </div>

      {terminalQ.isLoading && <div className="adv-empty">Loading production intelligence terminal…</div>}
      {terminalQ.error && (
        <div className="adv-error">
          Failed to load terminal — log in and ensure API is running. {(terminalQ.error as Error).message}
        </div>
      )}

      {data && (
        <div className="adv-main">
          {tab === "intelligence" && (
            <IntelligenceTab data={data} rows={rows} exec={execQ.data} ws={wsQ.data} selected={selected} onSelect={setSelected} />
          )}
          {tab === "orderflow" && <OrderFlowTab data={data} rows={rows} />}
          {tab === "decisions" && <DecisionsTab data={data} rows={rows} />}
          {tab === "sectors" && <SectorsTab data={data} />}
          {tab === "risk" && <RiskTab data={data} rows={rows} />}
          {tab === "performance" && <PerformanceTab data={data} exec={execQ.data} />}
        </div>
      )}

      {data && (
        <aside className="adv-side-panels">
          <LiveControlPanel live={data.liveControl} scanSec={data.scanIntervalSec ?? 10} />
          <EnginePanel engine={data.engine} metrics={data.metrics} exec={execQ.data} />
          <StrategiesPanel ws={wsQ.data} />
        </aside>
      )}
    </div>
  );
}

function useEnrichedRows(
  data: AdvTerminalSnapshot | undefined,
  watch: { symbol: string; price?: string; changePct?: string }[] | undefined,
): EnrichedRow[] {
  return useMemo(() => {
    const watchMap = new Map((watch ?? []).map((w) => [String(w.symbol), w]));
    return (data?.scannerRows ?? []).map((r) => {
      const w = watchMap.get(String(r.symbol));
      return {
        ...r,
        aiScore: Number(r.aiScore ?? 0),
        ltpDisplay: w?.price ?? fmtNum(r.ltp),
        changePct: toNum(w?.changePct) ?? 0,
      };
    });
  }, [data?.scannerRows, watch]);
}

function IntelligenceTab({
  data,
  rows,
  exec,
  ws,
  selected,
  onSelect,
}: {
  data: AdvTerminalSnapshot;
  rows: EnrichedRow[];
  exec?: Record<string, unknown>;
  ws?: Record<string, unknown>;
  selected: EnrichedRow | null;
  onSelect: (r: EnrichedRow | null) => void;
}) {
  const m = data.metrics ?? {};
  const cards = (data.liveCards?.length ? data.liveCards : rows.slice(0, 3)) as AdvScannerRow[];

  return (
    <>
      <div className="adv-metrics">
        <Metric label="Stocks Tracked" value={String(m.stocksTracked ?? 0)} hint="Live universe" />
        <Metric label="Active Setups" value={String(m.activeSetups ?? rows.length)} hint="Pipeline rows" />
        <Metric label="Executable" value={String(m.executableCount ?? 0)} hint="OMS eligible" accent />
        <Metric label="Market Breadth" value={String(m.marketBreadth ?? "—")} hint="Adv : Decl" />
        <Metric label="Top AI Score" value={String(m.topScore ?? 0)} hint="Best setup" />
        <Metric label="Orders Today" value={String(exec?.ordersTotal ?? data.engine?.trades ?? 0)} hint="Execution client" />
      </div>

      <div className="adv-live-cards">
        {cards.length === 0 ? (
          <div className="adv-empty adv-span-all">No pipeline rows yet — scanner runs during NSE session.</div>
        ) : (
          cards.map((c) => (
            <div key={`${c.symbol}-${c.strategy}`} className={`adv-live-card${String(c.side).includes("SELL") ? " short" : ""}`}>
              <div className="adv-live-card-head">
                <strong>{c.symbol}</strong>
                <ExecBadge status={String(c.executionStatus ?? c.status ?? "—")} />
              </div>
              <div className="adv-live-card-body">
                <div>
                  <div className="adv-metric-value">{c.aiScore ?? 0}</div>
                  <div className="adv-live-setup">{shortSetup(c.setupType ?? c.strategy)}</div>
                  <div className="adv-live-meta">{c.effectiveMode ?? "—"} · {c.tradeQuality ?? "—"}</div>
                </div>
                <div className="adv-score-ring">{c.aiScore ?? 0}</div>
              </div>
              {c.rejectionReason ? <div className="adv-reject-line">{c.rejectionReason}</div> : null}
            </div>
          ))
        )}
        <EngineMini engine={data.engine} ws={ws} />
      </div>

      <div className="adv-card">
        <div className="adv-card-head">
          <span className="adv-card-title">Live Scanner</span>
          <span className="adv-badge adv-badge-green">{rows.length} setups · {String(m.executableCount ?? 0)} executable</span>
        </div>
        <div className="adv-scroll">
          <ScannerTable rows={rows} onSelect={onSelect} />
        </div>
      </div>

      {selected ? <DiagnosticsPanel row={selected} onClose={() => onSelect(null)} /> : null}
      {data.regimeNarrative ? <p className="adv-footnote">{data.regimeNarrative}</p> : null}
    </>
  );
}

function OrderFlowTab({ data, rows }: { data: AdvTerminalSnapshot; rows: EnrichedRow[] }) {
  const summary = data.orderFlowSummary ?? {};
  const flow = data.orderFlow ?? [];

  return (
    <>
      <div className="adv-metrics adv-metrics-5">
        <Metric label="Bullish Flow" value={String(summary.bullishCount ?? 0)} hint="Buy pressure ≥55%" accent />
        <Metric label="Bearish Flow" value={String(summary.bearishCount ?? 0)} hint="Sell pressure ≥55%" />
        <Metric label="Neutral" value={String(summary.neutralCount ?? 0)} hint="Balanced book" />
        <Metric label="Imbalance" value={fmtImbalance(summary.imbalanceIndex)} hint="Skew index" />
        <Metric label="Symbols" value={String(flow.length)} hint="Tracked in scanner" />
      </div>

      <div className="adv-layout-2">
        <div className="adv-card">
          <div className="adv-card-head"><span className="adv-card-title">Order Book Pressure</span></div>
          {flow.length === 0 ? (
            <div className="adv-empty">Order flow populates when scanner has active symbols.</div>
          ) : (
            <table className="adv-table">
              <thead>
                <tr><th>Symbol</th><th>Buy%</th><th>Sell%</th><th>OBI</th><th>Trend</th><th>Status</th></tr>
              </thead>
              <tbody>
                {flow.map((r) => (
                  <tr key={r.symbol}>
                    <td><strong>{r.symbol}</strong></td>
                    <td><span className="adv-badge adv-badge-green">{r.buyPct ?? 50}%</span></td>
                    <td><span className="adv-badge adv-badge-red">{r.sellPct ?? 50}%</span></td>
                    <td>{r.obi ?? "—"}</td>
                    <td>{r.trend ?? "—"}</td>
                    <td><ExecBadge status={String(r.executionStatus ?? "—")} small /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        <div className="adv-card">
          <div className="adv-card-head"><span className="adv-card-title">Flow by Scanner Row</span></div>
          <div className="adv-scroll adv-scroll-sm">
            <table className="adv-table">
              <thead><tr><th>Symbol</th><th>Pressure</th><th>AI</th><th>Reason</th></tr></thead>
              <tbody>
                {rows.slice(0, 15).map((r) => (
                  <tr key={`${r.rank}-${r.symbol}`}>
                    <td><strong>{r.symbol}</strong></td>
                    <td><PressureBar buyPct={r.buyPct ?? 50} /></td>
                    <td><strong>{r.aiScore}</strong></td>
                    <td className="adv-truncate">{r.rejectionReason ?? (r.omsEligible ? "OMS eligible" : "—")}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </>
  );
}

function DecisionsTab({ data, rows }: { data: AdvTerminalSnapshot; rows: EnrichedRow[] }) {
  const decisions = data.decisions ?? [];
  const rejected = data.rejectedSetups ?? [];
  const health = data.systemHealth ?? {};

  return (
    <>
      <div className="adv-metrics adv-metrics-4">
        <Metric label="Pipeline Rows" value={String(rows.length)} hint="Today" />
        <Metric label="Executable" value={String(data.metrics?.executableCount ?? 0)} hint="OMS eligible" accent />
        <Metric label="Rejected" value={String(rejected.length)} hint="With reasons" />
        <Metric label="System" value={String(health.status ?? "—")} hint="Feed + startup" />
      </div>

      <div className="adv-layout-2">
        <div className="adv-card">
          <div className="adv-card-head"><span className="adv-card-title">Decision Log</span></div>
          {decisions.length === 0 ? (
            <div className="adv-empty">No decisions logged today.</div>
          ) : (
            <table className="adv-table">
              <thead><tr><th>Time</th><th>Symbol</th><th>Action</th><th>Strategy</th><th>AI</th><th>Status</th><th>Result</th></tr></thead>
              <tbody>
                {decisions.map((r, i) => (
                  <tr key={`${r.time}-${r.symbol}-${i}`}>
                    <td>{r.time}</td>
                    <td><strong>{r.symbol}</strong></td>
                    <td><span className="adv-badge adv-badge-blue">{r.action}</span></td>
                    <td>{shortSetup(r.strategy)}</td>
                    <td><strong>{r.aiScore}</strong></td>
                    <td><ExecBadge status={String(r.executionStatus ?? r.result)} small /></td>
                    <td className="adv-truncate">{r.rejectionReason ?? r.result}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        <div className="adv-stack">
          <div className="adv-card">
            <div className="adv-card-head"><span className="adv-card-title">Rejected Setups</span></div>
            {rejected.length === 0 ? (
              <div className="adv-empty">No rejections in pipeline audit.</div>
            ) : (
              <ul className="adv-reject-list">
                {rejected.map((r) => (
                  <li key={`${r.symbol}-${r.rejectionCode}`}>
                    <strong>{r.symbol}</strong> · {shortSetup(r.strategy)} · AI {r.aiScore ?? 0}
                    <div className="adv-reject-reason">{r.rejectionReason ?? r.executionStatus}</div>
                  </li>
                ))}
              </ul>
            )}
          </div>

          <div className="adv-card">
            <div className="adv-card-head"><span className="adv-card-title">System Health</span></div>
            <SystemHealthList health={health} live={data.liveControl} />
          </div>
        </div>
      </div>
    </>
  );
}

function SectorsTab({ data }: { data: AdvTerminalSnapshot }) {
  const sectors = data.sectors ?? [];
  if (sectors.length === 0) {
    return <div className="adv-empty">Sector rotation appears when scanner rows are grouped by sector.</div>;
  }

  const strongest = sectors[0];
  const weakest = sectors[sectors.length - 1];

  return (
    <>
      <div className="adv-metrics adv-metrics-4">
        <Metric label="Strongest" value={strongest?.name ?? "—"} hint={`Avg AI ${Math.round(Number(strongest?.avgScore ?? 0))}`} accent />
        <Metric label="Weakest" value={weakest?.name ?? "—"} hint={`Avg AI ${Math.round(Number(weakest?.avgScore ?? 0))}`} />
        <Metric label="Sectors" value={String(sectors.length)} hint="Tracked groups" />
        <Metric label="Breadth" value={String(data.metrics?.marketBreadth ?? "—")} hint="Market adv:dec" />
      </div>

      <div className="adv-grid-3">
        {sectors.map((s) => (
          <div key={s.name} className="adv-card adv-sector-card">
            <div className="adv-sector-head">
              <span className="adv-card-title">{s.name}</span>
              <span className="adv-badge adv-badge-blue">{s.count} symbols</span>
            </div>
            <div className="adv-sector-meta">
              Adv {s.advancers ?? 0} · Dec {s.decliners ?? 0} · Avg {Math.round(Number(s.avgScore ?? 0))}
            </div>
            <ul className="adv-sector-stocks">
              {(Array.isArray(s.stocks) ? s.stocks : []).map((st) => {
                const stock = typeof st === "string" ? { symbol: st } : st;
                return (
                  <li key={stock.symbol}>
                    <span>{stock.symbol}</span>
                    <span className="adv-sector-score">{stock.aiScore ?? "—"}</span>
                    {stock.executionStatus ? <ExecBadge status={String(stock.executionStatus)} small /> : null}
                  </li>
                );
              })}
            </ul>
          </div>
        ))}
      </div>
    </>
  );
}

function RiskTab({ data, rows }: { data: AdvTerminalSnapshot; rows: EnrichedRow[] }) {
  const r = data.risk ?? {};
  const positions = (r.positions as { symbol: string; side?: string; aiScore?: number; executionStatus?: string }[] | undefined) ?? [];
  const risky = rows.filter((row) =>
    ["BLOCKED", "REJECTED", "OMS_REJECTED", "QUALITY_REJECTED", "COOLDOWN"].includes(String(row.executionStatus)),
  );

  return (
    <>
      <div className="adv-metrics adv-metrics-4">
        <Metric label="Open Risk" value={String(r.openRisk ?? "—")} hint="Blocked exposure" />
        <Metric label="Capital Used" value={`${r.capitalUsedPct ?? 0}%`} hint="Executable share" />
        <Metric label="Corr Risk" value={String(r.corrRisk ?? "—")} hint="Pipeline stress" />
        <Metric label="Cooldown" value={String(r.cooldownCount ?? 0)} hint="Re-entry blocks" />
      </div>

      <div className="adv-layout-2">
        <div className="adv-card">
          <div className="adv-card-head"><span className="adv-card-title">Executable Positions</span></div>
          {positions.length === 0 ? (
            <div className="adv-empty">No OMS-eligible positions in pipeline right now.</div>
          ) : (
            <table className="adv-table">
              <thead><tr><th>Symbol</th><th>Side</th><th>AI</th><th>Status</th></tr></thead>
              <tbody>
                {positions.map((p) => (
                  <tr key={p.symbol}>
                    <td><strong>{p.symbol}</strong></td>
                    <td>{p.side ?? "—"}</td>
                    <td><strong>{p.aiScore ?? "—"}</strong></td>
                    <td><ExecBadge status={String(p.executionStatus ?? "—")} small /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        <div className="adv-card">
          <div className="adv-card-head"><span className="adv-card-title">Risk Blocks & Cooldowns</span></div>
          {risky.length === 0 ? (
            <div className="adv-empty">No blocked or cooldown signals.</div>
          ) : (
            <table className="adv-table">
              <thead><tr><th>Symbol</th><th>Status</th><th>Reason</th></tr></thead>
              <tbody>
                {risky.slice(0, 12).map((row) => (
                  <tr key={`${row.rank}-${row.symbol}`}>
                    <td><strong>{row.symbol}</strong></td>
                    <td><ExecBadge status={String(row.executionStatus)} small /></td>
                    <td className="adv-truncate">{row.rejectionReason ?? "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </>
  );
}

function PerformanceTab({ data, exec }: { data: AdvTerminalSnapshot; exec?: Record<string, unknown> }) {
  const p = data.performance ?? {};
  const bySetup = p.bySetupType ?? [];

  return (
    <>
      <div className="adv-metrics adv-metrics-4">
        <Metric label="Signals Today" value={String(p.trades ?? 0)} hint="All pipelines" />
        <Metric label="Orders" value={String(exec?.ordersTotal ?? 0)} hint="OMS total" />
        <Metric label="Rejected Orders" value={String(exec?.rejectedOrders ?? 0)} hint="Broker/OMS" />
        <Metric label="Open Pipeline" value={String(exec?.openPipelineOrdersApprox ?? 0)} hint="In flight" accent />
      </div>

      <div className="adv-layout-2">
        <div className="adv-card">
          <div className="adv-card-head"><span className="adv-card-title">By Setup Type</span></div>
          {bySetup.length === 0 ? (
            <div className="adv-empty">Setup breakdown appears when scanner has strategy rows.</div>
          ) : (
            <table className="adv-table">
              <thead><tr><th>Setup</th><th>Count</th><th>Executable</th><th>Avg AI</th></tr></thead>
              <tbody>
                {bySetup.map((s) => (
                  <tr key={s.setup}>
                    <td>{shortSetup(s.setup)}</td>
                    <td>{s.count}</td>
                    <td>{s.executable}</td>
                    <td><strong>{Math.round(s.avgScore)}</strong></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        <div className="adv-card">
          <div className="adv-card-head"><span className="adv-card-title">Execution Quality</span></div>
          <div className="adv-kv-list">
            <div><span>Win rate</span><strong>{String(p.winRate ?? "—")}</strong></div>
            <div><span>Avg latency</span><strong>{p.avgLatencyMs != null ? `${p.avgLatencyMs}ms` : "—"}</strong></div>
            <div><span>Fill rate</span><strong>{String(p.fillRate ?? "—")}</strong></div>
            <div><span>Executable now</span><strong>{String(data.metrics?.executableCount ?? 0)}</strong></div>
          </div>
        </div>
      </div>
    </>
  );
}

function ScannerTable({ rows, onSelect }: { rows: EnrichedRow[]; onSelect?: (r: EnrichedRow) => void }) {
  if (!rows.length) {
    return <div className="adv-empty">No scanner data — feed refreshes during NSE session (9:15–15:30 IST).</div>;
  }
  return (
    <table className="adv-table">
      <thead>
        <tr>
          <th>#</th><th>Symbol</th><th>LTP</th><th>AI</th><th>Execution</th><th>Buy:Sell</th><th>Quality</th><th>Mode</th><th>Reason</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={`${r.rank}-${r.symbol}`} onClick={() => onSelect?.(r)} className={onSelect ? "adv-clickable" : ""}>
            <td><strong>{r.rank}</strong></td>
            <td>
              <strong>{r.symbol}</strong>
              <div className="adv-cell-sub">{r.side} · {shortSetup(r.setupType ?? r.strategy)}</div>
            </td>
            <td>{r.ltpDisplay}</td>
            <td><strong className={r.aiScore >= 75 ? "adv-score-high" : "adv-score-mid"}>{r.aiScore}</strong></td>
            <td><ExecBadge status={String(r.executionStatus ?? r.status ?? "—")} /></td>
            <td><PressureBar buyPct={r.buyPct ?? 50} /></td>
            <td className="adv-cell-sub">{r.qualityGate ?? "—"}/{r.riskGate ?? "—"}</td>
            <td className="adv-cell-sub">{r.requestedMode ?? "—"}→{r.effectiveMode ?? "—"}</td>
            <td className="adv-truncate">{r.rejectionReason ?? (r.omsEligible ? "OMS eligible" : "—")}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function LiveControlPanel({ live, scanSec }: { live?: AdvTerminalSnapshot["liveControl"]; scanSec: number }) {
  const items = [
    ["LIVE enabled", live?.liveEnabled],
    ["Feed operational", live?.feedOperational],
    ["Safe startup", live?.safeStartupReady],
    ["Market open", live?.marketOpen],
    ["Live gate", live?.liveGateOpen],
  ] as const;

  return (
    <div className="adv-card adv-side-card">
      <div className="adv-card-title">Live Control</div>
      <div className="adv-kv-list">
        {items.map(([label, ok]) => (
          <div key={label}>
            <span>{label}</span>
            <strong className={ok ? "adv-ok" : "adv-bad"}>{ok ? "OK" : "BLOCKED"}</strong>
          </div>
        ))}
        <div><span>Scan interval</span><strong>{scanSec}s</strong></div>
      </div>
    </div>
  );
}

function EnginePanel({
  engine,
  metrics,
  exec,
}: {
  engine?: Record<string, unknown>;
  metrics: Record<string, unknown>;
  exec?: Record<string, unknown>;
}) {
  return (
    <div className="adv-card adv-side-card">
      <div className="adv-card-title">Today&apos;s Engine</div>
      <div className="adv-kv-list">
        <div><span>Signals</span><strong>{String(engine?.trades ?? metrics.executedCount ?? 0)}</strong></div>
        <div><span>Active</span><strong>{String(engine?.active ?? metrics.activeSetups ?? 0)}</strong></div>
        <div><span>Executable</span><strong className="adv-ok">{String(engine?.executable ?? metrics.executableCount ?? 0)}</strong></div>
        <div><span>Orders</span><strong>{String(exec?.ordersTotal ?? 0)}</strong></div>
      </div>
    </div>
  );
}

function EngineMini({ engine, ws }: { engine?: Record<string, unknown>; ws?: Record<string, unknown> }) {
  const strategies = (ws?.strategyAllocations as { strategyName?: string; runtimeState?: string }[] | undefined) ?? [];
  const running = strategies.filter((s) => String(s.runtimeState ?? "").toUpperCase().includes("RUN")).length;
  return (
    <div className="adv-card adv-engine-mini">
      <div className="adv-card-title">Today&apos;s Engine</div>
      <div className="adv-kv-list">
        <div><span>Trades</span><strong>{String(engine?.trades ?? 0)}</strong></div>
        <div><span>Active</span><strong>{String(engine?.active ?? 0)}</strong></div>
        <div><span>Strategies</span><strong>{running} running</strong></div>
      </div>
    </div>
  );
}

function StrategiesPanel({ ws }: { ws?: Record<string, unknown> }) {
  const strategies = ((ws?.strategyAllocations as { strategyKey?: string; strategyName?: string; runtimeState?: string }[] | undefined) ?? [])
    .filter((s) => String(s.runtimeState ?? "").toUpperCase().includes("RUN"))
    .slice(0, 5);

  return (
    <div className="adv-card adv-side-card">
      <div className="adv-card-title">Active Strategies</div>
      {strategies.length === 0 ? (
        <div className="adv-empty-sm">No running strategies</div>
      ) : (
        <ul className="adv-strategy-list">
          {strategies.map((s) => (
            <li key={s.strategyKey ?? s.strategyName}>
              <strong>{s.strategyName ?? s.strategyKey}</strong>
              <span>{s.runtimeState}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function SystemHealthList({ health, live }: { health: Record<string, unknown>; live?: AdvTerminalSnapshot["liveControl"] }) {
  const items = [
    ["Pipeline", String(health.status ?? "—")],
    ["Feed", live?.feedOperational ? "Operational" : "Stale/blocked"],
    ["Startup gate", live?.safeStartupReady ? "Ready" : "Warmup"],
    ["LIVE route", live?.liveEnabled ? "Enabled" : "Paper/disabled"],
    ["Scan", `${live?.scanIntervalSec ?? health.scanIntervalSec ?? 10}s`],
  ];
  return (
    <div className="adv-kv-list">
      {items.map(([k, v]) => (
        <div key={k}><span>{k}</span><strong>{v}</strong></div>
      ))}
    </div>
  );
}

function DiagnosticsPanel({ row, onClose }: { row: EnrichedRow; onClose: () => void }) {
  return (
    <div className="adv-card adv-diagnostics">
      <div className="adv-card-head">
        <span className="adv-card-title">Signal Diagnostics — {row.symbol}</span>
        <button type="button" className="adv-pill-btn" onClick={onClose}>Close</button>
      </div>
      <div className="adv-diag-grid">
        <Diag label="Execution" value={String(row.executionStatus)} />
        <Diag label="Stage" value={row.pipelineStage ?? "—"} />
        <Diag label="Quality" value={row.qualityGate ?? "—"} />
        <Diag label="Risk" value={row.riskGate ?? "—"} />
        <Diag label="Requested" value={row.requestedMode ?? "—"} />
        <Diag label="Effective" value={row.effectiveMode ?? "—"} />
        <Diag label="OMS" value={row.omsEligible ? "YES" : "NO"} />
        <Diag label="Code" value={row.rejectionCode ?? "—"} />
      </div>
      {row.rejectionReason ? <div className="adv-reject-line">{row.rejectionReason}</div> : null}
      {row.lifecycle?.length ? (
        <div className="adv-lifecycle">
          {row.lifecycle.map((s, i) => (
            <span key={`${s}-${i}`} className="adv-badge">{i + 1}. {s}</span>
          ))}
        </div>
      ) : null}
    </div>
  );
}

function ExecBadge({ status, small }: { status: string; small?: boolean }) {
  const cls = statusClass(status);
  return <span className={`adv-badge ${cls}${small ? " adv-badge-sm" : ""}`}>{status.replace(/_/g, " ")}</span>;
}

function PressureBar({ buyPct }: { buyPct: number }) {
  return (
    <div className="adv-pressure" title={`${buyPct}:${100 - buyPct}`}>
      <div className="adv-pressure-buy" style={{ width: `${buyPct}%` }} />
      <div className="adv-pressure-sell" style={{ width: `${100 - buyPct}%` }} />
    </div>
  );
}

function Metric({ label, value, hint, accent }: { label: string; value: string; hint: string; accent?: boolean }) {
  return (
    <div className={`adv-metric${accent ? " adv-metric-accent" : ""}`}>
      <div className="adv-metric-label">{label}</div>
      <div className="adv-metric-value">{value}</div>
      <div className="adv-metric-hint">{hint}</div>
    </div>
  );
}

function Diag({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="adv-metric-label">{label}</div>
      <div className="adv-diag-val">{value}</div>
    </div>
  );
}

function statusClass(status: string): string {
  if (status === "EXECUTABLE" || status === "EXECUTED" || status === "TRADING") return "adv-badge-green";
  if (status === "WATCHLIST" || status === "WATCHING") return "adv-badge-blue";
  if (status === "COOLDOWN") return "adv-badge-amber";
  if (["BLOCKED", "REJECTED", "OMS_REJECTED", "QUALITY_REJECTED"].includes(status)) return "adv-badge-red";
  return "adv-badge-gray";
}

function shortSetup(v?: string): string {
  if (!v) return "—";
  return v.replace(/_/g, " ").replace(/V\d+(\.\d+)?/gi, "").trim().slice(0, 24) || v;
}

function fmtNum(v: unknown): string {
  if (v == null) return "—";
  if (typeof v === "number") return v.toFixed(2);
  return String(v);
}

function toNum(v: unknown): number | null {
  if (typeof v === "number" && Number.isFinite(v)) return v;
  if (typeof v === "string") {
    const n = Number(v.replace(/[^0-9.-]/g, ""));
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

function fmtImbalance(v: unknown): string {
  const n = toNum(v);
  if (n == null) return "—";
  return n >= 0 ? `+${n.toFixed(2)}` : n.toFixed(2);
}
