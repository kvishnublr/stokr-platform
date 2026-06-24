import { useState, useEffect } from 'react';
import client from '../api/client';

async function ensureAuthenticated() {
  if (localStorage.getItem('token')) return;
  try {
    const res = await fetch('/api/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Test User', email: 'test-' + Date.now() + '@test.com', password: 'Test123!@' })
    });
    if (res.ok) {
      const d = await res.json();
      localStorage.setItem('token', d.accessToken);
      localStorage.setItem('refreshToken', d.refreshToken);
    }
  } catch (e) { /* silent */ }
}

const STRATEGIES = [
  { value: 'ORB',           label: 'ORB Breakout',           desc: 'Opening-range breakout + trailing stop (9:15–9:30 range)' },
  { value: 'VWAP',          label: 'VWAP Triple',            desc: 'Pullback to VWAP + RSI confirmation + volume surge' },
  { value: 'MORNING_SURGE', label: 'Morning Surge',          desc: 'High-volume ORB in first hour (9:30–10:30)' },
  { value: 'SURGE_REV',     label: 'Morning Surge Reversal', desc: 'Counter-trend fade after morning surge exhaustion' },
  { value: 'TBI',           label: 'Trade Book Imbalance',   desc: 'Buy/sell order-book imbalance with volume confirmation' },
  { value: 'PREOPEN',       label: 'Pre-Open Trade Book',    desc: 'Pre-open session order-book imbalance signal' },
];

function MetricCard({ label, value, color = '#1f2937', sub }) {
  return (
    <div style={{ padding: '18px 20px', background: '#f9fafb', borderRadius: '10px', border: '1px solid #e5e7eb' }}>
      <div style={{ fontSize: '11px', color: '#6b7280', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: '8px' }}>{label}</div>
      <div style={{ fontSize: '22px', fontWeight: 800, color }}>{value}</div>
      {sub && <div style={{ fontSize: '11px', color: '#9ca3af', marginTop: '4px' }}>{sub}</div>}
    </div>
  );
}

export default function AdvancedBacktest() {
  useEffect(() => { ensureAuthenticated(); }, []);

  const [universes, setUniverses]   = useState([]);
  const [strategy, setStrategy]     = useState('ORB');
  const [universe, setUniverse]     = useState('NIFTY_100');
  const [chartinkInfo, setChartinkInfo] = useState(null);
  const [dateStart, setDateStart]   = useState('2026-06-01');
  const [dateEnd, setDateEnd]       = useState(new Date().toISOString().slice(0, 10));
  const [results, setResults]       = useState(null);
  const [loading, setLoading]       = useState(false);
  const [error, setError]           = useState(null);
  const [progress, setProgress]     = useState('');
  const [loadOpen, setLoadOpen]     = useState(false);
  const [loadStatus, setLoadStatus] = useState(null);
  const [loadRunning, setLoadRunning] = useState(false);

  // Load universe groups from API
  useEffect(() => {
    client.get('/universe-groups')
      .then(r => {
        const groups = r.data || [];
        setUniverses(groups);
      })
      .catch(() => {});
    // Check Chartink scanner status
    client.get('/backtest/chartink-scan')
      .then(r => setChartinkInfo(r.data))
      .catch(() => {});
  }, []);

  const loadMissingCandles = async () => {
    setLoadRunning(true);
    setLoadStatus(null);
    try {
      const params = new URLSearchParams({ dateStart, dateEnd, universe, timeframe: '1min' });
      const res = await client.post('/admin/candles/backfill?' + params);
      setLoadStatus(res.data);
    } catch (e) {
      setLoadStatus({ error: e.response?.data?.error || e.message });
    } finally {
      setLoadRunning(false);
    }
  };

  const checkCoverage = async () => {
    setLoadRunning(true);
    setLoadStatus(null);
    try {
      const params = new URLSearchParams({ dateStart, dateEnd, universe, timeframe: '1min' });
      const res = await client.get('/admin/candles/coverage?' + params);
      setLoadStatus(res.data);
    } catch (e) {
      setLoadStatus({ error: e.response?.data?.error || e.message });
    } finally {
      setLoadRunning(false);
    }
  };

  const runBacktest = async () => {
    setLoading(true);
    setError(null);
    setResults(null);
    const univ = universes.find(u => u.groupKey === universe);
    setProgress('Loading candles for ' + (univ ? univ.displayName : universe) + '…');
    try {
      const params = new URLSearchParams({
        strategy,
        universe,
        timeframe: '1min',
        dateStart: new Date(dateStart).toISOString(),
        dateEnd:   new Date(dateEnd + 'T23:59:59').toISOString(),
      });
      setProgress('Running strategy simulation…');
      const res = await client.post('/backtest/advanced?' + params);
      setResults(res.data);
    } catch (e) {
      setError(e.response?.data?.error || e.message || 'Backtest failed');
    } finally {
      setLoading(false);
      setProgress('');
    }
  };

  const r = results;
  const pnlColor  = (v) => v > 0 ? '#10b981' : v < 0 ? '#ef4444' : '#1f2937';

  return (
    <div style={{ padding: '28px', maxWidth: '1200px', margin: '0 auto' }}>
      <style>{`
        select, input[type=date] {
          width: 100%; padding: 9px 12px; border-radius: 8px;
          border: 1px solid #d1d5db; font-size: 13px; background: white;
          appearance: auto;
        }
        .label { display: block; font-size: 11px; font-weight: 700; color: #6b7280;
          text-transform: uppercase; letter-spacing: .05em; margin-bottom: 6px; }
        .card { background: white; border-radius: 14px; padding: 24px;
          box-shadow: 0 1px 6px rgba(0,0,0,0.07); margin-bottom: 24px; }
        .univ-btn { padding: 10px 22px; border-radius: 8px; border: 2px solid #d1d5db;
          background: white; font-size: 13px; font-weight: 700; cursor: pointer; transition: all .15s; }
        .univ-btn.active { border-color: #6366f1; background: #6366f1; color: white; }
        .run-btn { padding: 12px 32px; background: linear-gradient(135deg,#6366f1,#8b5cf6);
          color: white; border: none; border-radius: 10px; font-size: 14px; font-weight: 800;
          cursor: pointer; transition: all .2s; }
        .run-btn:hover:not(:disabled) { transform: translateY(-2px); box-shadow: 0 8px 20px rgba(99,102,241,.35); }
        .run-btn:disabled { opacity: .55; cursor: not-allowed; }
        .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
        .grid-4 { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 14px; }
        .sep { height: 1px; background: #f3f4f6; margin: 20px 0; }
        .tag { display: inline-block; padding: 3px 10px; border-radius: 20px; font-size: 11px; font-weight: 700; }
        table { width: 100%; border-collapse: collapse; font-size: 12px; }
        th { background: #f3f4f6; padding: 9px 10px; text-align: left; font-weight: 700; color: #374151;
          border-bottom: 2px solid #e5e7eb; }
        td { padding: 7px 10px; border-bottom: 1px solid #f3f4f6; }
        tr:hover td { background: #fafafa; }
      `}</style>

      {/* Header */}
      <div style={{ marginBottom: '28px' }}>
        <h1 style={{ fontSize: '28px', fontWeight: 800, color: '#1f2937', margin: 0 }}>
          📈 Strategy Backtest
        </h1>
        <p style={{ color: '#6b7280', marginTop: '6px', fontSize: '14px' }}>
          ORB · VWAP Triple · Morning Surge · Surge Reversal · Trade Book Imbalance · Pre-Open · Real 1-min candle data · ₹25,000/trade · Trailing SL
        </p>
      </div>

      {/* Controls */}
      <div className="card">
          {/* Universe toggle */}
          <div style={{ marginBottom: '20px' }}>
            <span className="label">Universe</span>
            <div style={{ display: 'flex', gap: '10px', flexWrap: 'wrap', alignItems: 'center' }}>
              {universes.map(u => (
                <button key={u.groupKey} className={`univ-btn${universe === u.groupKey ? ' active' : ''}`}
                  onClick={() => setUniverse(u.groupKey)}
                  title={u.displayName}>
                  {u.displayName}
                </button>
              ))}
              {/* NIFTY 500 — symbols served from in-memory list */}
              <button
                className={`univ-btn${universe === 'NIFTY_500' ? ' active' : ''}`}
                onClick={() => setUniverse('NIFTY_500')}
                style={{ borderColor: universe === 'NIFTY_500' ? '#10b981' : undefined,
                         background: universe === 'NIFTY_500' ? '#10b981' : undefined }}>
                NIFTY 500
              </button>
              {/* Chartink universe — uses live scanner to filter stocks */}
              <button
                className={`univ-btn${universe === 'CHARTINK' ? ' active' : ''}`}
                onClick={() => setUniverse('CHARTINK')}
                style={{ borderColor: universe === 'CHARTINK' ? '#f59e0b' : undefined,
                         background: universe === 'CHARTINK' ? '#f59e0b' : undefined }}>
                📡 Chartink Scan
              </button>
            </div>
            {universe === 'CHARTINK' && (
              <div style={{ marginTop: '10px', padding: '10px 14px', background: '#fffbeb',
                border: '1px solid #fde68a', borderRadius: '8px', fontSize: '12px', color: '#92400e' }}>
                {chartinkInfo?.configured
                  ? `✅ Chartink connected — ${chartinkInfo.count || 0} stocks in scan. Runs ORB on those stocks.`
                  : '⚠️ Chartink session cookie not set. Using in-house scan (high-volume open stocks) instead. Set chartink.session.cookie in application.properties to connect real Chartink.'}
              </div>
            )}
          </div>

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '16px', marginBottom: '20px' }}>
          {/* Strategy */}
          <div>
            <label className="label">Strategy</label>
            <select value={strategy} onChange={e => setStrategy(e.target.value)}>
              {STRATEGIES.map(s => (
                <option key={s.value} value={s.value} title={s.desc}>
                  {s.label}
                </option>
              ))}
            </select>
            {strategy && (
              <div style={{ fontSize: '11px', color: '#6b7280', marginTop: '5px' }}>
                {STRATEGIES.find(s => s.value === strategy)?.desc}
              </div>
            )}
          </div>
          {/* Timeframe - locked to 1min */}
          <div>
            <label className="label">Timeframe</label>
            <select value="1min" disabled style={{ opacity: .65, cursor: 'not-allowed' }}>
              <option>1min</option>
            </select>
          </div>
          {/* Dates */}
          <div>
            <label className="label">Start Date</label>
            <input type="date" value={dateStart} onChange={e => setDateStart(e.target.value)} />
          </div>
          <div>
            <label className="label">End Date</label>
            <input type="date" value={dateEnd} onChange={e => setDateEnd(e.target.value)} />
          </div>
        </div>

        {/* Data loader panel */}
        <div style={{ marginBottom: '16px' }}>
          <button
            onClick={() => setLoadOpen(o => !o)}
            style={{ background: 'none', border: '1px solid #d1d5db', borderRadius: '8px',
              padding: '7px 14px', fontSize: '12px', cursor: 'pointer', color: '#374151',
              display: 'flex', alignItems: 'center', gap: '6px' }}>
            📦 {loadOpen ? '▲' : '▼'} Load Missing Candle Data
          </button>

          {loadOpen && (
            <div style={{ marginTop: '12px', padding: '16px', background: '#f8fafc',
              border: '1px solid #e2e8f0', borderRadius: '10px' }}>
              <p style={{ fontSize: '12px', color: '#64748b', marginBottom: '12px', margin: '0 0 12px' }}>
                Fetches missing 1-min candles from Zerodha for the selected universe + date range and saves to DB.
                Already-complete symbols are skipped. Safe to re-run.
              </p>
              <div style={{ display: 'flex', gap: '10px', flexWrap: 'wrap' }}>
                <button onClick={checkCoverage} disabled={loadRunning}
                  style={{ padding: '8px 18px', border: '1px solid #6366f1', borderRadius: '8px',
                    background: 'white', color: '#6366f1', fontSize: '13px', fontWeight: 700,
                    cursor: loadRunning ? 'not-allowed' : 'pointer', opacity: loadRunning ? 0.6 : 1 }}>
                  {loadRunning ? '⏳ Checking…' : '🔍 Check Coverage'}
                </button>
                <button onClick={loadMissingCandles} disabled={loadRunning}
                  style={{ padding: '8px 18px', border: 'none', borderRadius: '8px',
                    background: loadRunning ? '#9ca3af' : '#10b981', color: 'white',
                    fontSize: '13px', fontWeight: 700, cursor: loadRunning ? 'not-allowed' : 'pointer' }}>
                  {loadRunning ? '⏳ Loading… (may take 1-3 min)' : '⬇ Load Missing Candles'}
                </button>
              </div>

              {loadStatus && (
                <div style={{ marginTop: '12px', fontSize: '12px' }}>
                  {loadStatus.error ? (
                    <span style={{ color: '#ef4444' }}>❌ {loadStatus.error}</span>
                  ) : loadStatus.status === 'DONE' ? (
                    <div style={{ display: 'flex', gap: '16px', flexWrap: 'wrap' }}>
                      <span style={{ color: '#10b981', fontWeight: 700 }}>✅ Done</span>
                      <span>📊 {loadStatus.candlesSaved?.toLocaleString()} candles saved</span>
                      <span>⏭ {loadStatus.symbolsSkipped} symbols skipped (already complete)</span>
                      {loadStatus.symbolsFailed > 0 && <span style={{ color: '#f59e0b' }}>⚠ {loadStatus.symbolsFailed} failed</span>}
                    </div>
                  ) : (
                    <div style={{ display: 'flex', gap: '16px', flexWrap: 'wrap' }}>
                      <span>🗂 {loadStatus.symbolsTotal} symbols · {loadStatus.tradingDays} trading days</span>
                      <span style={{ color: '#10b981' }}>✅ Complete: {loadStatus.complete}</span>
                      <span style={{ color: '#f59e0b' }}>⚡ Partial: {loadStatus.partial}</span>
                      <span style={{ color: '#ef4444' }}>❌ Missing: {loadStatus.missing}</span>
                    </div>
                  )}
                </div>
              )}
            </div>
          )}
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
          <button className="run-btn" onClick={runBacktest} disabled={loading}>
            {loading ? '⏳ ' + progress : '▶ Run Backtest'}
          </button>
          {loading && (
            <span style={{ fontSize: '13px', color: '#6b7280' }}>{progress}</span>
          )}
        </div>
      </div>

      {/* Error */}
      {error && (
        <div style={{ background: '#fee2e2', border: '1px solid #fca5a5', borderRadius: '12px',
          padding: '14px 18px', marginBottom: '20px', color: '#991b1b', fontSize: '13px' }}>
          ❌ {error}
        </div>
      )}

      {/* Results */}
      {r && (
        <>
          {/* Overview strip */}
          <div style={{ display: 'flex', gap: '12px', flexWrap: 'wrap', marginBottom: '16px', alignItems: 'center' }}>
            <span className="tag" style={{ background: '#e0e7ff', color: '#3730a3' }}>{r.strategy}</span>
            <span className="tag" style={{ background: '#f3f4f6', color: '#374151' }}>{r.universe}</span>
            <span className="tag" style={{ background: '#f3f4f6', color: '#374151' }}>{r.symbolsLoaded} stocks</span>
            <span className="tag" style={{ background: '#f3f4f6', color: '#374151' }}>1min · {r.candlesLoaded?.toLocaleString()} candles</span>
            <span style={{ fontSize: '12px', color: '#9ca3af' }}>
              {new Date(r.dateRange.start).toLocaleDateString()} → {new Date(r.dateRange.end).toLocaleDateString()}
            </span>
          </div>

          {/* Primary metrics */}
          <div className="card">
            <div style={{ fontWeight: 700, color: '#374151', marginBottom: '16px', fontSize: '15px' }}>
              Overall Performance
            </div>
            <div className="grid-4">
              <MetricCard label="Total Trades"    value={r.totalTrades} />
              <MetricCard label="Win Rate"        value={r.winRate?.toFixed(1) + '%'}
                color={r.winRate >= 40 ? '#10b981' : r.winRate >= 30 ? '#f59e0b' : '#ef4444'} />
              <MetricCard label="Total P&L"       value={'₹' + r.totalPnL?.toLocaleString('en-IN', {maximumFractionDigits:0})}
                color={pnlColor(r.totalPnL)} />
              <MetricCard label="Avg P&L / Trade" value={'₹' + r.avgPnL?.toFixed(0)}
                color={pnlColor(r.avgPnL)} />
              <MetricCard label="Profit Factor"   value={r.profitFactor?.toFixed(2)}
                color={r.profitFactor >= 1.5 ? '#10b981' : r.profitFactor >= 1.0 ? '#f59e0b' : '#ef4444'} />
              <MetricCard label="Max Drawdown"    value={r.maxDrawdown?.toFixed(1) + '%'}
                color={r.maxDrawdown <= 30 ? '#10b981' : r.maxDrawdown <= 60 ? '#f59e0b' : '#ef4444'} />
              <MetricCard label="Wins"            value={r.winCount}
                color="#10b981" sub={r.lossCount + ' losses'} />
              <MetricCard label="Capital / Trade" value={'₹' + Number(r.capitalPerTrade)?.toLocaleString('en-IN')} />
            </div>

            <div className="sep" />

            {/* Daily metrics */}
            <div style={{ fontWeight: 700, color: '#374151', marginBottom: '16px', fontSize: '15px' }}>
              Daily Breakdown
            </div>
            <div className="grid-4">
              <MetricCard label="Best Day"       value={'₹' + r.maxProfitDay?.toLocaleString('en-IN', {maximumFractionDigits:0})}
                color="#10b981" />
              <MetricCard label="Worst Day"      value={'₹' + r.maxLossDay?.toLocaleString('en-IN', {maximumFractionDigits:0})}
                color="#ef4444" />
              <MetricCard label="Avg Profit/Day" value={'₹' + r.avgProfitDay?.toLocaleString('en-IN', {maximumFractionDigits:0})}
                color={pnlColor(r.avgProfitDay)} />
              <MetricCard label="Profit Days"    value={r.profitDays}
                color="#10b981" sub={r.lossDays + ' loss days · ' + r.totalTradingDays + ' total'} />
            </div>
          </div>

          {/* Trade log */}
          {r.trades?.length > 0 && (
            <div className="card">
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '16px' }}>
                <div style={{ fontWeight: 700, color: '#374151', fontSize: '15px' }}>
                  Trade Log <span style={{ color: '#9ca3af', fontWeight: 400 }}>({r.trades.length} trades)</span>
                </div>
                <button
                  onClick={() => {
                    const headers = ['#','Symbol','Entry Time','Side','Entry Price','Stop Loss','Target','P&L','Exit Type'];
                    const rows = r.trades.map((t, i) => [
                      i + 1,
                      t.symbol,
                      t.entryTime ? new Date(t.entryTime).toLocaleString('en-IN') : '',
                      t.side || 'BUY',
                      Number(t.entryPrice).toFixed(2),
                      Number(t.stopLoss).toFixed(2),
                      Number(t.target).toFixed(2),
                      Number(t.pnl).toFixed(2),
                      t.exitType || ''
                    ]);
                    const csv = [headers, ...rows].map(r => r.map(v => `"${String(v).replace(/"/g,'""')}"`).join(',')).join('\n');
                    const blob = new Blob([csv], { type: 'text/csv' });
                    const url  = URL.createObjectURL(blob);
                    const a    = document.createElement('a');
                    a.href     = url;
                    a.download = `tradelog_${r.strategy || 'backtest'}_${new Date().toISOString().slice(0,10)}.csv`;
                    a.click();
                    URL.revokeObjectURL(url);
                  }}
                  style={{
                    display: 'flex', alignItems: 'center', gap: '6px',
                    padding: '6px 14px', borderRadius: '6px', border: 'none',
                    background: '#2563eb', color: '#fff', fontWeight: 600,
                    fontSize: '12px', cursor: 'pointer'
                  }}
                >
                  ↓ Download CSV
                </button>
              </div>
              <div style={{ overflowX: 'auto', maxHeight: '480px', overflowY: 'auto' }}>
                <table>
                  <thead style={{ position: 'sticky', top: 0 }}>
                    <tr>
                      <th>#</th><th>Symbol</th><th>Entry Time</th>
                      <th style={{textAlign:'right'}}>Entry ₹</th>
                      <th style={{textAlign:'right'}}>SL ₹</th>
                      <th style={{textAlign:'right'}}>Target ₹</th>
                      <th style={{textAlign:'right'}}>P&L ₹</th>
                      <th style={{textAlign:'center'}}>Exit</th>
                    </tr>
                  </thead>
                  <tbody>
                    {r.trades.map((t, i) => (
                      <tr key={i}>
                        <td style={{ color: '#9ca3af' }}>{i + 1}</td>
                        <td style={{ fontWeight: 700 }}>{t.symbol}</td>
                        <td style={{ color: '#6b7280', fontSize: '11px' }}>
                          {t.entryTime ? new Date(t.entryTime).toLocaleString('en-IN', {
                            day:'2-digit', month:'short', hour:'2-digit', minute:'2-digit' }) : '-'}
                        </td>
                        <td style={{ textAlign: 'right', fontFamily: 'monospace' }}>{Number(t.entryPrice).toFixed(2)}</td>
                        <td style={{ textAlign: 'right', fontFamily: 'monospace', color: '#ef4444' }}>{Number(t.stopLoss).toFixed(2)}</td>
                        <td style={{ textAlign: 'right', fontFamily: 'monospace', color: '#10b981' }}>{Number(t.target).toFixed(2)}</td>
                        <td style={{ textAlign: 'right', fontWeight: 700,
                          color: t.pnl > 0 ? '#10b981' : t.pnl < 0 ? '#ef4444' : '#6b7280' }}>
                          {t.pnl > 0 ? '+' : ''}{Number(t.pnl).toFixed(0)}
                        </td>
                        <td style={{ textAlign: 'center' }}>
                          <span style={{
                            padding: '2px 8px', borderRadius: '4px', fontSize: '10px', fontWeight: 700,
                            background: t.exitType === 'TARGET_HIT' ? '#d1fae5'
                              : t.exitType === 'SL_HIT' ? '#fee2e2'
                              : t.exitType === 'TRAIL_SL' ? (t.pnl > 0 ? '#dbeafe' : '#fee2e2')
                              : '#fef3c7',
                            color: t.exitType === 'TARGET_HIT' ? '#065f46'
                              : t.exitType === 'SL_HIT' ? '#991b1b'
                              : t.exitType === 'TRAIL_SL' ? (t.pnl > 0 ? '#1e40af' : '#991b1b')
                              : '#92400e',
                          }}>
                            {t.exitType === 'TARGET_HIT' ? '✓ TARGET'
                              : t.exitType === 'SL_HIT' ? '✗ SL'
                              : t.exitType === 'TRAIL_SL' ? (t.pnl > 0 ? '~ TRAIL+' : '~ TRAIL-')
                              : t.exitType === 'EOD_EXIT' ? (t.pnl > 0 ? '⏱ EOD+' : '⏱ EOD-')
                              : t.exitType}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
