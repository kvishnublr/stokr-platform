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

const STRATEGIES = ['ORB', 'ADV_CASH', 'VWAP_SQUEEZE', 'GAP_FILL', 'VWAP_BOUNCE'];

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
  const [strategy, setStrategy]     = useState('VWAP_SQUEEZE');
  const [universe, setUniverse]     = useState('NIFTY_100');
  const [dateStart, setDateStart]   = useState('2026-06-01');
  const [dateEnd, setDateEnd]       = useState(new Date().toISOString().slice(0, 10));
  const [results, setResults]       = useState(null);
  const [loading, setLoading]       = useState(false);
  const [error, setError]           = useState(null);
  const [progress, setProgress]     = useState('');

  // Load universe groups from API
  useEffect(() => {
    client.get('/universe-groups')
      .then(r => {
        const groups = r.data || [];
        setUniverses(groups);
        if (groups.length > 0 && !groups.find(g => g.groupKey === universe)) {
          setUniverse(groups[0].groupKey);
        }
      })
      .catch(() => {});
  }, []);

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
          Simulate on real 1-min candle data · ₹25,000 per trade
        </p>
      </div>

      {/* Controls */}
      <div className="card">
          {/* Universe toggle */}
          <div style={{ marginBottom: '20px' }}>
            <span className="label">Universe</span>
            <div style={{ display: 'flex', gap: '10px', flexWrap: 'wrap' }}>
              {universes.map(u => (
                <button key={u.groupKey} className={`univ-btn${universe === u.groupKey ? ' active' : ''}`}
                  onClick={() => setUniverse(u.groupKey)}
                  title={u.displayName}>
                  {u.displayName}
                </button>
              ))}
              {universes.length === 0 && (
                <span style={{ fontSize: '13px', color: '#9ca3af' }}>Loading universes...</span>
              )}
            </div>
          </div>

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '16px', marginBottom: '20px' }}>
          {/* Strategy */}
          <div>
            <label className="label">Strategy</label>
            <select value={strategy} onChange={e => setStrategy(e.target.value)}>
              {STRATEGIES.map(s => <option key={s}>{s}</option>)}
            </select>
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
              <div style={{ fontWeight: 700, color: '#374151', marginBottom: '16px', fontSize: '15px' }}>
                Trade Log <span style={{ color: '#9ca3af', fontWeight: 400 }}>({r.trades.length} trades)</span>
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
                            background: t.exitType === 'TARGET_HIT' ? '#d1fae5' : t.exitType === 'SL_HIT' ? '#fee2e2' : '#fef3c7',
                            color:      t.exitType === 'TARGET_HIT' ? '#065f46' : t.exitType === 'SL_HIT' ? '#991b1b' : '#92400e',
                          }}>
                            {t.exitType === 'TARGET_HIT' ? 'WIN' : t.exitType === 'SL_HIT' ? 'LOSS' : t.exitType}
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
