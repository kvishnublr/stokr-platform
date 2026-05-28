import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { fetchAdvTerminal, type AdvTerminalSnapshot } from "../api/advDashboard";
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

export function AdvDashboardPage() {
  const [tab, setTab] = useState<TabId>("intelligence");
  const { data, isLoading, error, refetch, isFetching } = useQuery({
    queryKey: ["adv-terminal"],
    queryFn: fetchAdvTerminal,
    refetchInterval: 15_000,
  });

  return (
    <div className="adv-terminal">
      <header className="adv-header">
        <div>
          <div className="adv-title">
            <span style={{ width: 28, height: 28, borderRadius: 8, background: "linear-gradient(135deg,#22c55e,#16a34a)", display: "inline-flex", alignItems: "center", justifyContent: "center", color: "#fff", fontSize: 14 }}>◎</span>
            Intraday Intelligence
          </div>
          <p className="adv-sub">AI-powered scanner · Auto-trades high confidence setups</p>
        </div>
        <div className="adv-pills">
          <div className="adv-pill">
            <strong>{data?.marketOpen ? "MARKET OPEN" : "MARKET CLOSED"}</strong>
            <div>{data?.istTime ?? "—"} IST</div>
          </div>
          <div className="adv-pill">
            Regime: <strong>{data?.marketRegime?.replace(/_/g, " ") ?? "—"}</strong>
          </div>
          <button type="button" className="adv-pill" onClick={() => refetch()} disabled={isFetching}>
            {isFetching ? "Refreshing…" : "Refresh"}
          </button>
        </div>
      </header>

      <div className="adv-tabs">
        {TABS.map((t) => (
          <button key={t.id} type="button" className={`adv-tab${tab === t.id ? " on" : ""}`} onClick={() => setTab(t.id)}>
            {t.label}
          </button>
        ))}
        <span className="adv-live-badge"><span style={{ width: 6, height: 6, borderRadius: 999, background: "#22c55e" }} /> LIVE</span>
      </div>

      {isLoading && <div className="adv-empty">Loading intelligence terminal…</div>}
      {error && <div className="adv-error">Failed to load terminal — ensure you are logged in and market feed is active.</div>}
      {data && tab === "intelligence" && <IntelligenceTab data={data} />}
      {data && tab === "orderflow" && <OrderFlowTab data={data} />}
      {data && tab === "decisions" && <DecisionsTab data={data} />}
      {data && tab === "sectors" && <SectorsTab data={data} />}
      {data && tab === "risk" && <RiskTab data={data} />}
      {data && tab === "performance" && <PerformanceTab data={data} />}
    </div>
  );
}

function IntelligenceTab({ data }: { data: AdvTerminalSnapshot }) {
  const m = data.metrics ?? {};
  return (
    <>
      <div className="adv-metrics">
        <Metric label="Stocks Tracked" value={String(m.stocksTracked ?? 0)} hint="Live universe" />
        <Metric label="Active Setups" value={String(m.activeSetups ?? 0)} hint="Ranked quality" />
        <Metric label="Avg Win Rate" value={String(m.avgWinRate ?? "—")} hint="Conditional hist." />
        <Metric label="Market Breadth" value={String(m.marketBreadth ?? "—")} hint="Adv : Decl" />
        <Metric label="Top AI Score" value={String(m.topScore ?? 0)} hint="Best setup" />
        <Metric label="System Accuracy" value={String(m.systemAccuracy ?? "—")} hint="Last 50 trades" />
      </div>

      {data.liveCards && data.liveCards.length > 0 && (
        <div className="adv-live-cards">
          {data.liveCards.map((c) => (
            <div key={String(c.symbol)} className={`adv-live-card${c.side === "SHORT" ? " short" : ""}`}>
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                <strong>{String(c.symbol)}</strong>
                <span className="adv-badge adv-badge-live">● LIVE</span>
              </div>
              <div style={{ display: "flex", justifyContent: "space-between", marginTop: 8, alignItems: "center" }}>
                <div>
                  <div className="adv-metric-value" style={{ fontSize: "1.1rem" }}>{String(c.aiScore)}</div>
                  <div style={{ fontSize: "0.65rem", color: "#64748b" }}>{String(c.setupType ?? c.status)}</div>
                </div>
                <div className="adv-score-ring">{String(c.aiScore)}</div>
              </div>
            </div>
          ))}
          {data.engine && (
            <div className="adv-card" style={{ padding: 14 }}>
              <div className="adv-card-title" style={{ marginBottom: 8 }}>Today&apos;s Engine</div>
              <div style={{ fontSize: "0.7rem", lineHeight: 1.6 }}>
                <div>Trades: <strong>{String(data.engine.trades)}</strong></div>
                <div>Active setups: <strong>{String(data.engine.active)}</strong></div>
                <div>Win rate: <strong>{String(data.engine.winRate)}</strong></div>
              </div>
            </div>
          )}
        </div>
      )}

      <div className="adv-card">
        <div className="adv-card-head">
          <span className="adv-card-title">Live Scanner</span>
          <span className="adv-badge adv-badge-green">{data.scannerRows?.length ?? 0} setups</span>
        </div>
        <div style={{ maxHeight: 320, overflow: "auto" }}>
          <ScannerTable rows={data.scannerRows ?? []} />
        </div>
      </div>

      <p style={{ fontSize: "0.65rem", color: "#94a3b8", marginTop: 8 }}>{data.regimeNarrative}</p>
    </>
  );
}

function ScannerTable({ rows }: { rows: AdvTerminalSnapshot["scannerRows"] }) {
  if (!rows?.length) {
    return <div className="adv-empty">No scanner data yet — feed refreshes during NSE session (9:15–15:30 IST).</div>;
  }
  return (
    <table className="adv-table">
      <thead>
        <tr>
          <th>#</th><th>Symbol</th><th>LTP</th><th>AI</th><th>Status</th><th>Buy:Sell</th><th>Vol</th><th>Win%</th><th>Setup</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={`${r.rank}-${r.symbol}`}>
            <td><strong>{r.rank}</strong></td>
            <td><strong>{r.symbol}</strong><div style={{ fontSize: "0.6rem", color: "#64748b" }}>{r.side}</div></td>
            <td>{fmtNum(r.ltp)}</td>
            <td><strong style={{ color: r.aiScore >= 75 ? "#16a34a" : "#2563eb" }}>{r.aiScore}</strong></td>
            <td><span className={`adv-badge ${r.status === "TRADING" ? "adv-badge-live" : "adv-badge-amber"}`}>{r.status}</span></td>
            <td>
              <div className="adv-pressure">
                <div className="adv-pressure-buy" style={{ width: `${r.buyPct ?? 50}%` }} />
                <div className="adv-pressure-sell" style={{ width: `${100 - (r.buyPct ?? 50)}%` }} />
              </div>
            </td>
            <td>{r.volumeMultiple ?? "—"}</td>
            <td>{r.winPct ?? "—"}%</td>
            <td>{r.setupType ?? "—"}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function OrderFlowTab({ data }: { data: AdvTerminalSnapshot }) {
  const rows = data.orderFlow ?? [];
  return (
    <div className="adv-card">
      <div className="adv-card-head"><span className="adv-card-title">Order Book Pressure</span></div>
      {rows.length === 0 ? (
        <div className="adv-empty">Order flow populates when scanner has active symbols.</div>
      ) : (
        <table className="adv-table">
          <thead><tr><th>Symbol</th><th>Buy%</th><th>Sell%</th><th>OBI</th><th>Trend</th></tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.symbol}>
                <td><strong>{r.symbol}</strong></td>
                <td className="adv-badge-green">{r.buyPct}%</td>
                <td className="adv-badge-red">{r.sellPct}%</td>
                <td>{String(r.obi)}</td>
                <td>{r.trend}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function DecisionsTab({ data }: { data: AdvTerminalSnapshot }) {
  const rows = data.decisions ?? [];
  return (
    <div className="adv-card">
      <div className="adv-card-head"><span className="adv-card-title">Decision Log — signals &amp; system actions</span></div>
      {rows.length === 0 ? (
        <div className="adv-empty">No system decisions today — strategies scan during market hours.</div>
      ) : (
        <table className="adv-table">
          <thead><tr><th>Time</th><th>Symbol</th><th>Action</th><th>Strategy</th><th>AI</th><th>Result</th></tr></thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r.time}-${r.symbol}-${i}`}>
                <td>{r.time}</td>
                <td><strong>{r.symbol}</strong></td>
                <td><span className="adv-badge adv-badge-green">{r.action}</span></td>
                <td>{r.strategy}</td>
                <td><strong>{r.aiScore}</strong></td>
                <td>{r.result}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function SectorsTab({ data }: { data: AdvTerminalSnapshot }) {
  const sectors = data.sectors ?? [];
  return (
    <div className="adv-grid-3">
      {sectors.length === 0 ? (
        <div className="adv-empty" style={{ gridColumn: "1 / -1" }}>Sector rotation data appears when scanner rows are available.</div>
      ) : (
        sectors.map((s) => (
          <div key={s.name} className="adv-card" style={{ padding: 14 }}>
            <div className="adv-card-title">{s.name}</div>
            <div style={{ fontSize: "0.65rem", color: "#64748b", marginTop: 4 }}>{s.count} symbols tracked</div>
            <ul style={{ marginTop: 8, paddingLeft: 16, fontSize: "0.72rem" }}>
              {(s.stocks as string[] | undefined)?.map((sym) => <li key={sym}>{sym}</li>)}
            </ul>
          </div>
        ))
      )}
    </div>
  );
}

function RiskTab({ data }: { data: AdvTerminalSnapshot }) {
  const r = data.risk ?? {};
  return (
    <div className="adv-metrics" style={{ gridTemplateColumns: "repeat(4, minmax(0,1fr))" }}>
      <Metric label="Open Risk" value={String(r.openRisk ?? "—")} hint="Max SL exposure" />
      <Metric label="Capital Used" value={`${r.capitalUsedPct ?? 0}%`} hint="Of limit" />
      <Metric label="Corr Risk" value={String(r.corrRisk ?? "—")} hint="Portfolio" />
      <Metric label="Positions" value={String((r.positions as unknown[] | undefined)?.length ?? 0)} hint="Open legs" />
    </div>
  );
}

function PerformanceTab({ data }: { data: AdvTerminalSnapshot }) {
  const p = data.performance ?? {};
  return (
    <div className="adv-metrics" style={{ gridTemplateColumns: "repeat(4, minmax(0,1fr))" }}>
      <Metric label="Signals Today" value={String(p.trades ?? 0)} hint="All pipelines" />
      <Metric label="Win Rate" value={String(p.winRate ?? "—")} hint="Closed trades" />
      <Metric label="Avg Latency" value={`${p.avgLatencyMs ?? "—"}ms`} hint="Signal → order" />
      <Metric label="Fill Rate" value={String(p.fillRate ?? "—")} hint="Execution" />
    </div>
  );
}

function Metric({ label, value, hint }: { label: string; value: string; hint: string }) {
  return (
    <div className="adv-metric">
      <div className="adv-metric-label">{label}</div>
      <div className="adv-metric-value">{value}</div>
      <div className="adv-metric-hint">{hint}</div>
    </div>
  );
}

function fmtNum(v: unknown): string {
  if (v == null) return "—";
  if (typeof v === "number") return v.toFixed(2);
  return String(v);
}
