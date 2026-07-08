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
  { value: 'OVERSOLD_BOUNCE',          label: 'Oversold Bounce',              desc: 'Daily. Buy >3% drops, hold 1-7d. 76.9% WR, PF 1.99, ₹40K/yr on ₹1L.' },
  { value: 'EMA50_DISTANCE',           label: 'EMA50 Distance v2',            desc: 'Daily. Buy >3% below 50EMA, SL 3%, target = EMA50. ₹105K/yr, 189 trades, PF 1.90.' },
  { value: 'THREE_RED_DAYS',           label: '3 Red Days',                   desc: 'Daily. Buy after 3 red days + vol surge, SL 3%, target +3%. 88.9% WR, PF 4.78.' },
  { value: 'SURGE_REV',                label: 'Morning Surge Reversal',       desc: 'Intraday SHORT. ORB false breakout fade. 58.8% WR, PF 2.66, ₹3.8K/yr.' },
  { value: 'MICRO_V_REVERSAL',         label: 'Micro V-Reversal',            desc: 'Intraday LONG. 3-bar V-bottom. 76.5% WR, PF 1.51.' },

];

const ALL_PAIRS = [
  { key: 'HDFCBANK:ICICIBANK',     labelA: 'HDFCBANK',   labelB: 'ICICIBANK',   sector: 'Banking',   corr: '~74%' },
  { key: 'KOTAKBANK:AXISBANK',     labelA: 'KOTAKBANK',  labelB: 'AXISBANK',    sector: 'Banking',   corr: '~88%' },
  { key: 'TCS:INFY',               labelA: 'TCS',        labelB: 'INFY',        sector: 'IT',        corr: '~64%' },
  { key: 'WIPRO:HCLTECH',          labelA: 'WIPRO',      labelB: 'HCLTECH',     sector: 'IT',        corr: '~85%' },
  { key: 'INFY:HCLTECH',           labelA: 'INFY',       labelB: 'HCLTECH',     sector: 'IT',        corr: '~80%' },
  { key: 'SBIN:BANKBARODA',        labelA: 'SBIN',       labelB: 'BANKBARODA',  sector: 'PSU Bank',  corr: '~47%' },
  { key: 'SUNPHARMA:DRREDDY',      labelA: 'SUNPHARMA',  labelB: 'DRREDDY',     sector: 'Pharma',    corr: '~67%' },
  { key: 'CIPLA:LUPIN',            labelA: 'CIPLA',      labelB: 'LUPIN',       sector: 'Pharma',    corr: '~10%' },
  { key: 'NTPC:POWERGRID',         labelA: 'NTPC',       labelB: 'POWERGRID',   sector: 'PSU Power', corr: '~88%' },
  { key: 'RELIANCE:ONGC',          labelA: 'RELIANCE',   labelB: 'ONGC',        sector: 'Energy',    corr: '~59%' },
  { key: 'HINDALCO:TATASTEEL',     labelA: 'HINDALCO',   labelB: 'TATASTEEL',   sector: 'Metals',    corr: '~71%' },
  { key: 'BAJFINANCE:BAJAJFINSV',  labelA: 'BAJFINANCE', labelB: 'BAJAJFINSV',  sector: 'Finance',   corr: '~20%' },
  { key: 'HDFCLIFE:SBILIFE',       labelA: 'HDFCLIFE',   labelB: 'SBILIFE',     sector: 'Insurance', corr: '~91%' },
  { key: 'ASIANPAINT:BERGEPAINT',  labelA: 'ASIANPAINT', labelB: 'BERGEPAINT',  sector: 'Paints',    corr: '~65%' },
  { key: 'HINDUNILVR:ITC',         labelA: 'HINDUNILVR', labelB: 'ITC',         sector: 'FMCG',      corr: '~78%' },
  { key: 'BAJAJ-AUTO:HEROMOTOCO',  labelA: 'BAJAJ-AUTO', labelB: 'HEROMOTOCO',  sector: '2-Wheelers',corr: '~35%' },
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

  // Mode toggle: 'strategy' | 'pairs'
  const [mode, setMode] = useState('strategy');

  // Strategy mode state
  const [universes, setUniverses]   = useState([]);
  const [strategy, setStrategy]     = useState('SURGE_REV');
  const [universe, setUniverse]     = useState('NIFTY_100');
  const [chartinkInfo, setChartinkInfo] = useState(null);
  const [brokerage, setBrokerage]   = useState(40);
  const [dateStart, setDateStart]   = useState(() => {
    const d = new Date(); d.setMonth(d.getMonth() - 2); d.setDate(1);
    return d.toISOString().slice(0, 10);
  });
  const [dateEnd, setDateEnd]       = useState(new Date().toISOString().slice(0, 10));
  const [results, setResults]       = useState(null);
  const [loading, setLoading]       = useState(false);
  const [error, setError]           = useState(null);
  const [progress, setProgress]     = useState('');
  const [loadOpen, setLoadOpen]     = useState(false);
  const [loadStatus, setLoadStatus] = useState(null);
  const [loadRunning, setLoadRunning] = useState(false);

  // Pairs Arb mode state (pair selection shared with Opening-Drift tab)
  const [selectedPairs, setSelectedPairs] = useState(
    ALL_PAIRS.reduce((acc, p) => ({ ...acc, [p.key]: true }), {})
  );
  const [expandedPair, setExpandedPair] = useState(null);

  // Opening-Drift mode state
  const [driftDailyWindow, setDriftDailyWindow] = useState(20);
  const [driftZEntry,    setDriftZEntry]    = useState(1.5);
  const [driftZEntryMax, setDriftZEntryMax] = useState(2.5);
  const [driftZExit,     setDriftZExit]     = useState(0.5);
  const [driftZStop,     setDriftZStop]     = useState(3.0);
  const [driftResults,   setDriftResults]   = useState(null);

  // Pairs Live Dashboard state
  const [pairsLiveData,    setPairsLiveData]    = useState(null);
  const [pairsLiveHistory, setPairsLiveHistory] = useState(null);
  const [pairsLiveLoading, setPairsLiveLoading] = useState(false);
  const [pairsLiveMode,    setPairsLiveMode]    = useState('PAPER');

  const loadPairsLiveStatus = async () => {
    setPairsLiveLoading(true);
    try {
      const [status, history] = await Promise.all([
        client.get('/pairs/live/status'),
        client.get('/pairs/live/history?days=30'),
      ]);
      setPairsLiveData(status.data);
      setPairsLiveHistory(history.data);
      setPairsLiveMode(status.data.mode || 'PAPER');
    } catch (e) {
      console.error('Pairs live load error:', e);
    } finally {
      setPairsLiveLoading(false);
    }
  };

  const togglePairsMode = async (newMode) => {
    try {
      await client.post(`/pairs/live/mode?mode=${newMode}`);
      setPairsLiveMode(newMode);
      await loadPairsLiveStatus();
    } catch (e) {
      console.error('Mode switch error:', e);
    }
  };

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
        brokerage,
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

  const runDriftBacktest = async () => {
    setLoading(true);
    setError(null);
    setDriftResults(null);
    setExpandedPair(null);
    const activePairs = ALL_PAIRS.filter(p => selectedPairs[p.key]).map(p => p.key).join(',');
    setProgress('Loading candles for all pairs…');
    try {
      const params = new URLSearchParams({
        pairs:       activePairs,
        dateStart:   new Date(dateStart).toISOString(),
        dateEnd:     new Date(dateEnd + 'T23:59:59').toISOString(),
        dailyWindow: driftDailyWindow,
        zEntry:      driftZEntry,
        zEntryMax:   driftZEntryMax,
        zExit:       driftZExit,
        zStop:       driftZStop,
        brokerage:   80,
      });
      setProgress('Running opening-drift simulation…');
      const res = await client.post('/backtest/pairs-drift?' + params);
      setDriftResults(res.data);
    } catch (e) {
      setError(e.response?.data?.error || e.message || 'Opening-drift backtest failed');
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
        .run-btn.pairs { background: linear-gradient(135deg,#059669,#10b981); }
        .run-btn:hover:not(:disabled) { transform: translateY(-2px); box-shadow: 0 8px 20px rgba(99,102,241,.35); }
        .run-btn.pairs:hover:not(:disabled) { box-shadow: 0 8px 20px rgba(5,150,105,.35); }
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
        .mode-tab { padding: 10px 24px; border-radius: 8px; border: 2px solid #e5e7eb;
          background: white; font-size: 13px; font-weight: 700; cursor: pointer; color: #6b7280; transition: all .15s; }
        .mode-tab.active-strat { border-color: #6366f1; background: #6366f1; color: white; }
        .mode-tab.active-pairs { border-color: #059669; background: #059669; color: white; }
        .pair-checkbox { display: flex; align-items: center; gap: 10px; padding: 10px 14px;
          border-radius: 10px; border: 1.5px solid #e5e7eb; cursor: pointer; transition: all .15s; }
        .pair-checkbox.checked { border-color: #059669; background: #f0fdf4; }
        .z-input { width: 80px; padding: 7px 10px; border-radius: 8px; border: 1px solid #d1d5db;
          font-size: 13px; font-weight: 700; text-align: center; }
      `}</style>

      {/* Header */}
      <div style={{ marginBottom: '20px' }}>
        <h1 style={{ fontSize: '28px', fontWeight: 800, color: '#1f2937', margin: 0 }}>
          📈 Strategy Backtest
        </h1>
        <p style={{ color: '#6b7280', marginTop: '6px', fontSize: '14px' }}>
          Real 1-min candle data · ₹25,000/trade · Intraday simulation
        </p>
      </div>

      {/* Mode toggle */}
      <div style={{ display: 'flex', gap: '10px', marginBottom: '24px', flexWrap: 'wrap' }}>
        <button
          className={`mode-tab${mode === 'strategy' ? ' active-strat' : ''}`}
          onClick={() => { setMode('strategy'); setResults(null); setError(null); }}>
          📊 Strategy Backtest
        </button>
        <button
          className={`mode-tab${mode === 'drift' ? ' active-pairs' : ''}`}
          style={mode === 'drift' ? { borderColor: '#7c3aed', background: '#7c3aed' } : { borderColor: '#7c3aed', color: '#7c3aed' }}
          onClick={() => { setMode('drift'); setDriftResults(null); setError(null); }}>
          🌅 Opening Drift (Daily Z-Score)
        </button>
        <button
          className="mode-tab"
          style={mode === 'live-pairs'
            ? { borderColor: '#dc2626', background: '#dc2626', color: 'white' }
            : { borderColor: '#dc2626', color: '#dc2626' }}
          onClick={() => { setMode('live-pairs'); setError(null); loadPairsLiveStatus(); }}>
          🔴 Pairs Live Dashboard
        </button>
      </div>

      {/* ============ OPENING DRIFT CONTROLS ============ */}
      {mode === 'drift' && (
        <>
          <div className="card">
            <div style={{ fontWeight: 800, fontSize: '15px', marginBottom: '8px', color: '#6d28d9' }}>
              🌅 Opening-Drift Pairs Strategy
            </div>
            <div style={{ background: '#f5f3ff', border: '1px solid #c4b5fd', borderRadius: '10px',
              padding: '12px 16px', marginBottom: '20px', fontSize: '12px', color: '#4c1d95', lineHeight: '1.7' }}>
              <strong>How it works:</strong> Computes {driftDailyWindow}-day rolling mean/std of daily close log-ratios.
              At 9:30 each morning, if opening ratio z is between <strong>{driftZEntry} and {driftZEntryMax}</strong>,
              the pair has drifted overnight (but not too much — extreme z={driftZEntryMax}+ = real event, skip).
              Enter opposite direction, exit when spread reverts to z &lt; {driftZExit}, stop at z &gt; {driftZStop}.
              <br />
              <strong>Key filter:</strong> Moderate z ({driftZEntry}–{driftZEntryMax}) = random drift → reverts.
              Extreme z ({driftZEntryMax}+) = real event (earnings/news) → does NOT revert → skip.
            </div>

            {/* Pair selection (reuse same checkboxes) */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))', gap: '10px', marginBottom: '20px' }}>
              {ALL_PAIRS.map(p => (
                <div key={p.key}
                  className={`pair-checkbox${selectedPairs[p.key] ? ' checked' : ''}`}
                  style={selectedPairs[p.key] ? { borderColor: '#7c3aed', background: '#f5f3ff' } : {}}
                  onClick={() => setSelectedPairs(prev => ({ ...prev, [p.key]: !prev[p.key] }))}>
                  <input type="checkbox" checked={!!selectedPairs[p.key]} readOnly
                    style={{ width: '16px', height: '16px', accentColor: '#7c3aed', flexShrink: 0 }} />
                  <div>
                    <div style={{ fontWeight: 800, fontSize: '13px' }}>
                      <span style={{ color: '#1d4ed8' }}>{p.labelA}</span>
                      <span style={{ color: '#9ca3af', margin: '0 6px' }}>/</span>
                      <span style={{ color: '#7c3aed' }}>{p.labelB}</span>
                    </div>
                    <div style={{ fontSize: '10px', color: '#9ca3af', marginTop: '2px' }}>{p.sector} · corr {p.corr}</div>
                  </div>
                </div>
              ))}
            </div>

            {/* Parameters */}
            <div style={{ display: 'flex', alignItems: 'flex-end', gap: '20px', flexWrap: 'wrap', marginBottom: '20px' }}>
              <div>
                <label className="label">Date From</label>
                <input type="date" value={dateStart} onChange={e => setDateStart(e.target.value)}
                  style={{ width: '160px', padding: '9px 12px', borderRadius: '8px', border: '1px solid #d1d5db', fontSize: '13px' }} />
              </div>
              <div>
                <label className="label">Date To</label>
                <input type="date" value={dateEnd} onChange={e => setDateEnd(e.target.value)}
                  style={{ width: '160px', padding: '9px 12px', borderRadius: '8px', border: '1px solid #d1d5db', fontSize: '13px' }} />
              </div>
              <div>
                <label className="label">Daily Lookback <span style={{ color: '#c4b5fd' }}>(days)</span></label>
                <input className="z-input" type="number" min={10} max={60} step={5} value={driftDailyWindow}
                  onChange={e => setDriftDailyWindow(Number(e.target.value))} />
              </div>
              <div>
                <label className="label">Entry Z Min</label>
                <input className="z-input" type="number" min={1.0} max={4} step={0.25} value={driftZEntry}
                  onChange={e => setDriftZEntry(Number(e.target.value))} />
              </div>
              <div>
                <label className="label">Entry Z Max <span style={{ color: '#c4b5fd', fontSize: '10px' }}>cap</span></label>
                <input className="z-input" type="number" min={1.5} max={6} step={0.25} value={driftZEntryMax}
                  onChange={e => setDriftZEntryMax(Number(e.target.value))} />
              </div>
              <div>
                <label className="label">Exit Z-Score</label>
                <input className="z-input" type="number" min={0} max={2} step={0.25} value={driftZExit}
                  onChange={e => setDriftZExit(Number(e.target.value))} />
              </div>
              <div>
                <label className="label">Stop Z-Score</label>
                <input className="z-input" type="number" min={2} max={6} step={0.5} value={driftZStop}
                  onChange={e => setDriftZStop(Number(e.target.value))} />
              </div>
            </div>

            <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
              <button
                className="run-btn"
                style={{ background: 'linear-gradient(135deg,#7c3aed,#a78bfa)' }}
                onClick={runDriftBacktest} disabled={loading}>
                {loading ? '⏳ ' + progress : '▶ Run Opening-Drift Backtest'}
              </button>
              <span style={{ fontSize: '12px', color: '#6b7280' }}>
                {ALL_PAIRS.filter(p => selectedPairs[p.key]).length} pairs · ₹25k/leg · ₹80 brokerage/trade
              </span>
            </div>
          </div>

          {/* Drift results */}
          {driftResults && (() => {
            const dr = driftResults;
            const totalPnl = dr.pairs?.reduce((s, p) => s + (p.totalPnl || 0), 0) || 0;
            return (
              <>
                <div className="card">
                  <div style={{ fontWeight: 800, fontSize: '15px', marginBottom: '16px' }}>Opening-Drift Performance</div>
                  <div style={{ display: 'flex', gap: '10px', flexWrap: 'wrap', marginBottom: '16px' }}>
                    <span className="tag" style={{ background: '#ede9fe', color: '#4c1d95' }}>OPENING DRIFT</span>
                    <span className="tag" style={{ background: '#f3f4f6', color: '#374151' }}>{dr.pairsCount} pairs</span>
                    <span className="tag" style={{ background: '#f3f4f6', color: '#374151' }}>window={dr.dailyWindow}d  entry z={dr.zEntry}</span>
                  </div>
                  <div className="grid-4">
                    <MetricCard label="Total Trades"    value={dr.totalTrades} />
                    <MetricCard label="Win Rate"        value={dr.winRate?.toFixed(1) + '%'}
                      color={dr.winRate >= 60 ? '#10b981' : dr.winRate >= 45 ? '#f59e0b' : '#ef4444'} />
                    <MetricCard label="Net P&L"         value={'₹' + Math.round(totalPnl).toLocaleString('en-IN')}
                      color={pnlColor(totalPnl)} />
                    <MetricCard label="Profit Factor"   value={dr.profitFactor?.toFixed(2)}
                      color={dr.profitFactor >= 1.5 ? '#10b981' : dr.profitFactor >= 1.0 ? '#f59e0b' : '#ef4444'} />
                  </div>
                </div>

                <div className="card">
                  <div style={{ fontWeight: 800, fontSize: '15px', marginBottom: '16px' }}>Per-Pair Breakdown</div>
                  {[...dr.pairs].sort((a, b) => b.totalPnl - a.totalPnl).map(pair => (
                    <div key={pair.pair} style={{ marginBottom: '12px', border: '1px solid #e5e7eb', borderRadius: '12px', overflow: 'hidden' }}>
                      <div
                        onClick={() => setExpandedPair(expandedPair === pair.pair ? null : pair.pair)}
                        style={{ display: 'flex', alignItems: 'center', gap: '16px', padding: '14px 16px',
                          background: '#fafafa', cursor: 'pointer', flexWrap: 'wrap' }}>
                        <div style={{ fontWeight: 800, fontSize: '14px', minWidth: '200px' }}>
                          <span style={{ color: '#1d4ed8' }}>{pair.symbolA}</span>
                          <span style={{ color: '#9ca3af', margin: '0 8px' }}>/</span>
                          <span style={{ color: '#7c3aed' }}>{pair.symbolB}</span>
                        </div>
                        <span className="tag" style={{ background: '#dbeafe', color: '#1e40af' }}>
                          corr {(pair.correlation * 100).toFixed(0)}%
                        </span>
                        <span style={{ fontSize: '12px', color: '#6b7280' }}>{pair.trades} trades</span>
                        <span style={{ fontSize: '13px', fontWeight: 800,
                          color: pair.winRate >= 60 ? '#059669' : pair.winRate >= 45 ? '#d97706' : '#dc2626' }}>
                          {pair.winRate?.toFixed(1)}% win
                        </span>
                        <span style={{ fontSize: '14px', fontWeight: 800, marginLeft: 'auto',
                          color: pair.totalPnl > 0 ? '#059669' : '#dc2626' }}>
                          {pair.totalPnl > 0 ? '+' : ''}₹{Math.round(pair.totalPnl).toLocaleString('en-IN')}
                        </span>
                        <span style={{ color: '#9ca3af', fontSize: '12px' }}>{expandedPair === pair.pair ? '▲' : '▼'}</span>
                      </div>
                      {expandedPair === pair.pair && pair.tradeList?.length > 0 && (
                        <div style={{ overflowX: 'auto', maxHeight: '320px', overflowY: 'auto' }}>
                          <table>
                            <thead style={{ position: 'sticky', top: 0 }}>
                              <tr>
                                <th>Date</th><th>Direction</th><th>Entry Z</th><th>Exit Z</th>
                                <th>Leg A P&L</th><th>Leg B P&L</th><th>Net P&L</th><th>Exit Reason</th>
                              </tr>
                            </thead>
                            <tbody>
                              {pair.tradeList.map((t, i) => (
                                <tr key={i}>
                                  <td>{t.entryTime?.slice(0, 16)}</td>
                                  <td><span className="tag" style={{ background: t.direction === 'SHORT_A_LONG_B' ? '#fee2e2' : '#dcfce7',
                                    color: t.direction === 'SHORT_A_LONG_B' ? '#991b1b' : '#166534' }}>
                                    {t.direction === 'SHORT_A_LONG_B' ? `↓${pair.symbolA} ↑${pair.symbolB}` : `↑${pair.symbolA} ↓${pair.symbolB}`}
                                  </span></td>
                                  <td style={{ fontWeight: 700, color: Math.abs(t.entryZScore) >= driftZEntry ? '#7c3aed' : '#374151' }}>
                                    {t.entryZScore?.toFixed(2)}
                                  </td>
                                  <td style={{ color: '#6b7280' }}>{t.exitZScore?.toFixed(2)}</td>
                                  <td style={{ color: t.legAGross > 0 ? '#059669' : '#dc2626', fontWeight: 700 }}>
                                    {t.legAGross > 0 ? '+' : ''}₹{Math.round(t.legAGross)}
                                  </td>
                                  <td style={{ color: t.legBGross > 0 ? '#059669' : '#dc2626', fontWeight: 700 }}>
                                    {t.legBGross > 0 ? '+' : ''}₹{Math.round(t.legBGross)}
                                  </td>
                                  <td style={{ color: t.netPnl > 0 ? '#059669' : '#dc2626', fontWeight: 800, fontSize: '13px' }}>
                                    {t.netPnl > 0 ? '+' : ''}₹{Math.round(t.netPnl)}
                                  </td>
                                  <td><span className="tag" style={{
                                    background: t.exitReason === 'SPREAD_REVERSION' ? '#dcfce7' :
                                               t.exitReason === 'SPREAD_STOP' ? '#fee2e2' : '#fef3c7',
                                    color: t.exitReason === 'SPREAD_REVERSION' ? '#166534' :
                                           t.exitReason === 'SPREAD_STOP' ? '#991b1b' : '#92400e' }}>
                                    {t.exitReason}
                                  </span></td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              </>
            );
          })()}
        </>
      )}

      {/* ============ STRATEGY CONTROLS ============ */}
      {mode === 'strategy' && <div className="card">
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

        <div style={{ display: 'flex', alignItems: 'center', gap: '16px', flexWrap: 'wrap' }}>
          <button className="run-btn" onClick={runBacktest} disabled={loading}>
            {loading ? '⏳ ' + progress : '▶ Run Backtest'}
          </button>
          <button onClick={async () => {
            try {
              await client.post('/backtest/clear-cache');
              setResults(null);
              setError('Cache cleared. Run backtest again for fresh results.');
            } catch (e) {
              setError('Failed to clear cache: ' + (e.response?.data?.error || e.message));
            }
          }} disabled={loading}
            style={{ padding: '12px 18px', borderRadius: '10px', border: '1px solid #d1d5db',
              background: 'white', fontSize: '13px', fontWeight: 700, cursor: loading ? 'not-allowed' : 'pointer',
              color: '#6b7280', opacity: loading ? 0.55 : 1 }}>
            🗑 Clear Cache
          </button>
          {loading && (
            <span style={{ fontSize: '13px', color: '#6b7280' }}>{progress}</span>
          )}
        </div>
      </div>}

      {/* Error */}
      {error && (
        <div style={{ background: '#fee2e2', border: '1px solid #fca5a5', borderRadius: '12px',
          padding: '14px 18px', marginBottom: '20px', color: '#991b1b', fontSize: '13px' }}>
          ❌ {error}
        </div>
      )}

      {/* Strategy Results */}
      {mode === 'strategy' && r && (
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
            {r.cached && <span className="tag" style={{ background: '#fef3c7', color: '#92400e' }}>📦 Cached</span>}
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
              <MetricCard label="Gross P&L"       value={'₹' + r.totalPnL?.toLocaleString('en-IN', {maximumFractionDigits:0})}
                color={pnlColor(r.totalPnL)} />
              <MetricCard label="Net P&L (post-brokerage)" value={'₹' + (r.netPnL || 0)?.toLocaleString('en-IN', {maximumFractionDigits:0})}
                color={pnlColor(r.netPnL)}
                sub={'Zerodha brokerage: ₹' + (r.totalBrokerage || 0)?.toLocaleString('en-IN', {maximumFractionDigits:0})} />
              <MetricCard label="Avg P&L / Trade" value={'₹' + r.avgPnL?.toFixed(0)}
                color={pnlColor(r.avgPnL)} />
              <MetricCard label="Profit Factor"   value={r.profitFactor?.toFixed(2)}
                color={r.profitFactor >= 1.5 ? '#10b981' : r.profitFactor >= 1.0 ? '#f59e0b' : '#ef4444'} />
              <MetricCard label="Max Drawdown"    value={'₹' + (r.maxDrawdown?.toFixed(0) || 0)}
                sub={(r.maxDrawdown && r.capitalPerTrade ? (r.maxDrawdown / r.capitalPerTrade * 100).toFixed(1) : 0) + '% of capital'}
                color={!r.capitalPerTrade || !r.maxDrawdown ? '#10b981' : (r.maxDrawdown / r.capitalPerTrade) <= 0.1 ? '#10b981' : (r.maxDrawdown / r.capitalPerTrade) <= 0.3 ? '#f59e0b' : '#ef4444'} />
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

          {/* Per-symbol breakdown */}
          {r.trades?.length > 0 && (() => {
            const bySymbol = {};
            r.trades.forEach(t => {
              if (!bySymbol[t.symbol]) bySymbol[t.symbol] = { trades: 0, wins: 0, pnl: 0 };
              bySymbol[t.symbol].trades++;
              if (t.pnl > 0) bySymbol[t.symbol].wins++;
              bySymbol[t.symbol].pnl += t.pnl;
            });
            const sorted = Object.entries(bySymbol).sort((a, b) => b[1].pnl - a[1].pnl);
            return (
              <div className="card">
                <div style={{ fontWeight: 700, color: '#374151', marginBottom: '16px', fontSize: '15px' }}>
                  Per-Symbol Breakdown <span style={{ color: '#9ca3af', fontWeight: 400 }}>({sorted.length} symbols)</span>
                </div>
                <div style={{ overflowX: 'auto', maxHeight: '280px', overflowY: 'auto' }}>
                  <table>
                    <thead style={{ position: 'sticky', top: 0 }}>
                      <tr>
                        <th>Symbol</th>
                        <th style={{ textAlign: 'right' }}>Trades</th>
                        <th style={{ textAlign: 'right' }}>Wins</th>
                        <th style={{ textAlign: 'right' }}>Losses</th>
                        <th style={{ textAlign: 'right' }}>Win Rate</th>
                        <th style={{ textAlign: 'right' }}>Total P&L</th>
                        <th style={{ textAlign: 'right' }}>Avg P&L</th>
                      </tr>
                    </thead>
                    <tbody>
                      {sorted.map(([sym, d]) => (
                        <tr key={sym}>
                          <td style={{ fontWeight: 700 }}>{sym}</td>
                          <td style={{ textAlign: 'right' }}>{d.trades}</td>
                          <td style={{ textAlign: 'right', color: '#10b981' }}>{d.wins}</td>
                          <td style={{ textAlign: 'right', color: '#ef4444' }}>{d.trades - d.wins}</td>
                          <td style={{ textAlign: 'right', fontWeight: 600,
                            color: (d.wins / d.trades) >= 0.5 ? '#10b981' : '#ef4444' }}>
                            {(d.wins / d.trades * 100).toFixed(1)}%
                          </td>
                          <td style={{ textAlign: 'right', fontWeight: 700,
                            color: d.pnl > 0 ? '#10b981' : '#ef4444' }}>
                            {d.pnl > 0 ? '+' : ''}₹{d.pnl.toFixed(0)}
                          </td>
                          <td style={{ textAlign: 'right', color: '#6b7280' }}>
                            ₹{(d.pnl / d.trades).toFixed(0)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            );
          })()}

          {/* Daily P&L table */}
          {r.trades?.length > 0 && (() => {
            const byDay = {};
            r.trades.forEach(t => {
              if (!t.entryTime) return;
              const d = new Date(t.entryTime).toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' });
              if (!byDay[d]) byDay[d] = { trades: 0, pnl: 0 };
              byDay[d].trades++;
              byDay[d].pnl += t.pnl;
            });
            const days = Object.entries(byDay);
            let running = 0;
            return (
              <div className="card">
                <div style={{ fontWeight: 700, color: '#374151', marginBottom: '16px', fontSize: '15px' }}>
                  Daily P&L <span style={{ color: '#9ca3af', fontWeight: 400 }}>({days.length} trading days)</span>
                </div>
                <div style={{ overflowX: 'auto', maxHeight: '280px', overflowY: 'auto' }}>
                  <table>
                    <thead style={{ position: 'sticky', top: 0 }}>
                      <tr>
                        <th>Date</th>
                        <th style={{ textAlign: 'right' }}>Trades</th>
                        <th style={{ textAlign: 'right' }}>Day P&L</th>
                        <th style={{ textAlign: 'right' }}>Running P&L</th>
                      </tr>
                    </thead>
                    <tbody>
                      {days.map(([date, d]) => {
                        running += d.pnl;
                        return (
                          <tr key={date}>
                            <td style={{ color: '#6b7280', fontSize: '11px' }}>{date}</td>
                            <td style={{ textAlign: 'right' }}>{d.trades}</td>
                            <td style={{ textAlign: 'right', fontWeight: 700,
                              color: d.pnl > 0 ? '#10b981' : d.pnl < 0 ? '#ef4444' : '#6b7280' }}>
                              {d.pnl > 0 ? '+' : ''}₹{d.pnl.toFixed(0)}
                            </td>
                            <td style={{ textAlign: 'right', fontWeight: 700,
                              color: running > 0 ? '#10b981' : running < 0 ? '#ef4444' : '#6b7280' }}>
                              {running > 0 ? '+' : ''}₹{running.toFixed(0)}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </div>
            );
          })()}

          {/* Trade log */}
          {r.trades?.length > 0 && (
            <div className="card">
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '16px' }}>
                <div style={{ fontWeight: 700, color: '#374151', fontSize: '15px' }}>
                  Trade Log <span style={{ color: '#9ca3af', fontWeight: 400 }}>({r.trades.length} trades)</span>
                </div>
                <button
                  onClick={() => {
                    const headers = ['#','Symbol','Entry Time','Side','Qty','Entry Price','Capital','Stop Loss','Target','Gross P&L','Brokerage','Net P&L','Exit Type'];
                    const rows = r.trades.map((t, i) => [
                      i + 1,
                      t.symbol,
                      t.entryTime ? new Date(t.entryTime).toLocaleString('en-IN') : '',
                      t.side || 'SELL',
                      t.qty || '',
                      Number(t.entryPrice).toFixed(2),
                      Number(t.tradeCapital || 0).toFixed(0),
                      Number(t.stopLoss).toFixed(2),
                      Number(t.target).toFixed(2),
                      Number(t.pnl).toFixed(2),
                      Number(t.brokerage || 0).toFixed(2),
                      Number(t.netPnl ?? t.pnl).toFixed(2),
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
                      <th style={{textAlign:'right'}}>Qty</th>
                      <th style={{textAlign:'right'}}>Entry ₹</th>
                      <th style={{textAlign:'right'}}>Capital ₹</th>
                      <th style={{textAlign:'right'}}>SL ₹</th>
                      <th style={{textAlign:'right'}}>Target ₹</th>
                      <th style={{textAlign:'right'}}>Gross ₹</th>
                      <th style={{textAlign:'right'}}>Brok ₹</th>
                      <th style={{textAlign:'right'}}>Net ₹</th>
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
                        <td style={{ textAlign: 'right', fontFamily: 'monospace' }}>{t.qty}</td>
                        <td style={{ textAlign: 'right', fontFamily: 'monospace' }}>{Number(t.entryPrice).toFixed(2)}</td>
                        <td style={{ textAlign: 'right', fontFamily: 'monospace', color: '#6366f1' }}>
                          {Number(t.tradeCapital || 0).toLocaleString('en-IN', {maximumFractionDigits:0})}
                        </td>
                        <td style={{ textAlign: 'right', fontFamily: 'monospace', color: '#ef4444' }}>{Number(t.stopLoss).toFixed(2)}</td>
                        <td style={{ textAlign: 'right', fontFamily: 'monospace', color: '#10b981' }}>{Number(t.target).toFixed(2)}</td>
                        <td style={{ textAlign: 'right', fontWeight: 600,
                          color: t.pnl > 0 ? '#10b981' : t.pnl < 0 ? '#ef4444' : '#6b7280' }}>
                          {t.pnl > 0 ? '+' : ''}{Number(t.pnl).toFixed(0)}
                        </td>
                        <td style={{ textAlign: 'right', color: '#9ca3af', fontSize: '11px' }}>
                          {Number(t.brokerage || 0).toFixed(0)}
                        </td>
                        <td style={{ textAlign: 'right', fontWeight: 700,
                          color: (t.netPnl ?? t.pnl) > 0 ? '#10b981' : (t.netPnl ?? t.pnl) < 0 ? '#ef4444' : '#6b7280' }}>
                          {(t.netPnl ?? t.pnl) > 0 ? '+' : ''}{Number(t.netPnl ?? t.pnl).toFixed(0)}
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

          {/* Daily PnL Table */}
          {r.dailyPnl && Object.keys(r.dailyPnl).length > 0 && (
            <div className="card-crystal" style={{ padding: '24px' }}>
              <h3 style={{ fontSize: '15px', fontWeight: 700, marginBottom: '16px', color: '#1e293b' }}>
                Daily P&L Breakdown
              </h3>
              <div style={{ overflowX: 'auto', maxHeight: '360px', overflowY: 'auto' }}>
                <table>
                  <thead style={{ position: 'sticky', top: 0 }}>
                    <tr>
                      <th>Date</th>
                      <th style={{textAlign:'right'}}>Net P&L ₹</th>
                      <th style={{textAlign:'right'}}>Cumulative ₹</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(() => {
                      let cum = 0;
                      return Object.entries(r.dailyPnl).map(([date, pnl]) => {
                        cum += pnl;
                        return (
                          <tr key={date}>
                            <td style={{ fontFamily: 'monospace', color: '#6b7280', fontSize: '12px' }}>{date}</td>
                            <td style={{ textAlign: 'right', fontWeight: 700,
                              color: pnl > 0 ? '#10b981' : pnl < 0 ? '#ef4444' : '#6b7280' }}>
                              {pnl > 0 ? '+' : ''}₹{Number(pnl).toFixed(0)}
                            </td>
                            <td style={{ textAlign: 'right', fontFamily: 'monospace',
                              color: cum > 0 ? '#10b981' : cum < 0 ? '#ef4444' : '#6b7280' }}>
                              {cum > 0 ? '+' : ''}₹{Math.round(cum).toLocaleString('en-IN')}
                            </td>
                          </tr>
                        );
                      });
                    })()}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </>
      )}

      {/* ============ PAIRS LIVE DASHBOARD ============ */}
      {mode === 'live-pairs' && (
        <div>
          {/* Mode control + refresh */}
          <div className="card" style={{ display: 'flex', alignItems: 'center', gap: '16px', flexWrap: 'wrap' }}>
            <div style={{ fontWeight: 800, fontSize: '15px', color: '#1f2937' }}>🔴 Pairs Live Engine</div>
            <div style={{ marginLeft: 'auto', display: 'flex', gap: '10px', alignItems: 'center' }}>
              <span style={{ fontSize: '12px', color: '#6b7280' }}>Mode:</span>
              <button
                onClick={() => togglePairsMode('PAPER')}
                style={{
                  padding: '6px 16px', borderRadius: '6px', border: '2px solid',
                  fontWeight: 700, fontSize: '12px', cursor: 'pointer',
                  borderColor: pairsLiveMode === 'PAPER' ? '#6366f1' : '#d1d5db',
                  background: pairsLiveMode === 'PAPER' ? '#6366f1' : 'white',
                  color: pairsLiveMode === 'PAPER' ? 'white' : '#6b7280',
                }}>PAPER</button>
              <button
                onClick={() => togglePairsMode('LIVE')}
                style={{
                  padding: '6px 16px', borderRadius: '6px', border: '2px solid',
                  fontWeight: 700, fontSize: '12px', cursor: 'pointer',
                  borderColor: pairsLiveMode === 'LIVE' ? '#dc2626' : '#d1d5db',
                  background: pairsLiveMode === 'LIVE' ? '#dc2626' : 'white',
                  color: pairsLiveMode === 'LIVE' ? 'white' : '#6b7280',
                }}>LIVE</button>
              <button
                onClick={loadPairsLiveStatus}
                disabled={pairsLiveLoading}
                style={{ padding: '6px 16px', borderRadius: '6px', background: '#f3f4f6',
                  border: '1px solid #e5e7eb', fontWeight: 700, fontSize: '12px', cursor: 'pointer' }}>
                {pairsLiveLoading ? '⏳' : '🔄 Refresh'}
              </button>
            </div>
          </div>

          {pairsLiveLoading && (
            <div className="card" style={{ textAlign: 'center', color: '#6b7280', padding: '40px' }}>
              Loading pairs live data…
            </div>
          )}

          {pairsLiveData && (
            <>
              {/* Today summary */}
              <div className="card">
                <div style={{ fontWeight: 800, fontSize: '15px', marginBottom: '16px', color: '#1f2937' }}>
                  Today's Pairs Trades
                </div>
                <div className="grid-4" style={{ marginBottom: '20px' }}>
                  <MetricCard label="Open Trades" value={pairsLiveData.openCount} />
                  <MetricCard label="Closed Today" value={pairsLiveData.closedCount} />
                  <MetricCard label="Today P&L"
                    value={`${pairsLiveData.todayPnl >= 0 ? '+' : ''}₹${Number(pairsLiveData.todayPnl).toFixed(0)}`}
                    color={pairsLiveData.todayPnl >= 0 ? '#10b981' : '#ef4444'} />
                  <MetricCard label="Mode" value={pairsLiveData.mode}
                    color={pairsLiveData.mode === 'LIVE' ? '#dc2626' : '#6366f1'} />
                </div>

                {pairsLiveData.today && pairsLiveData.today.length > 0 ? (
                  <div style={{ overflowX: 'auto' }}>
                    <table>
                      <thead>
                        <tr>
                          <th>Pair</th>
                          <th>Direction</th>
                          <th>Entry Time</th>
                          <th style={{textAlign:'right'}}>Entry Z</th>
                          <th style={{textAlign:'right'}}>Entry A</th>
                          <th style={{textAlign:'right'}}>Entry B</th>
                          <th style={{textAlign:'right'}}>Exit Z</th>
                          <th style={{textAlign:'right'}}>Net P&L</th>
                          <th>Exit Reason</th>
                          <th>Status</th>
                        </tr>
                      </thead>
                      <tbody>
                        {pairsLiveData.today.map(t => (
                          <tr key={t.id}>
                            <td style={{ fontWeight: 700 }}>{t.pairKey}</td>
                            <td style={{ fontSize: '11px', color: t.direction === 'SHORT_A_LONG_B' ? '#ef4444' : '#10b981' }}>
                              {t.direction === 'SHORT_A_LONG_B' ? '↓A ↑B' : '↑A ↓B'}
                            </td>
                            <td style={{ fontFamily: 'monospace', fontSize: '11px', color: '#6b7280' }}>
                              {t.entryTime ? new Date(t.entryTime).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' }) : '-'}
                            </td>
                            <td style={{ textAlign: 'right', fontFamily: 'monospace' }}>{t.entryZ}</td>
                            <td style={{ textAlign: 'right', fontFamily: 'monospace' }}>₹{t.entryPriceA?.toFixed(1)}</td>
                            <td style={{ textAlign: 'right', fontFamily: 'monospace' }}>₹{t.entryPriceB?.toFixed(1)}</td>
                            <td style={{ textAlign: 'right', fontFamily: 'monospace', color: '#6b7280' }}>{t.exitZ ?? '—'}</td>
                            <td style={{ textAlign: 'right', fontWeight: 700,
                              color: t.netPnl > 0 ? '#10b981' : t.netPnl < 0 ? '#ef4444' : '#6b7280' }}>
                              {t.netPnl != null ? `${t.netPnl >= 0 ? '+' : ''}₹${t.netPnl?.toFixed(0)}` : '—'}
                            </td>
                            <td style={{ fontSize: '11px', color: '#6b7280' }}>{t.exitReason ?? '—'}</td>
                            <td>
                              <span className="tag" style={{
                                background: t.status === 'OPEN' ? '#fef3c7' : t.netPnl > 0 ? '#d1fae5' : '#fee2e2',
                                color: t.status === 'OPEN' ? '#92400e' : t.netPnl > 0 ? '#065f46' : '#991b1b',
                              }}>{t.status}</span>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <div style={{ textAlign: 'center', color: '#9ca3af', padding: '24px', fontSize: '14px' }}>
                    No pair trades today. Engine enters at 9:30 AM when z ∈ [1.5, 2.5].
                  </div>
                )}
              </div>

              {/* 30-day history summary */}
              {pairsLiveHistory && (
                <div className="card">
                  <div style={{ fontWeight: 800, fontSize: '15px', marginBottom: '16px', color: '#1f2937' }}>
                    30-Day Performance
                  </div>
                  <div className="grid-4" style={{ marginBottom: '20px' }}>
                    <MetricCard label="Total Trades" value={pairsLiveHistory.totalTrades} />
                    <MetricCard label="Win Rate" value={`${pairsLiveHistory.winRate}%`}
                      color={pairsLiveHistory.winRate >= 60 ? '#10b981' : pairsLiveHistory.winRate >= 50 ? '#f59e0b' : '#ef4444'} />
                    <MetricCard label="Total P&L"
                      value={`${pairsLiveHistory.totalPnl >= 0 ? '+' : ''}₹${Number(pairsLiveHistory.totalPnl).toFixed(0)}`}
                      color={pairsLiveHistory.totalPnl >= 0 ? '#10b981' : '#ef4444'} />
                    <MetricCard label="Avg / Trade"
                      value={`${pairsLiveHistory.avgPnlPerTrade >= 0 ? '+' : ''}₹${Number(pairsLiveHistory.avgPnlPerTrade).toFixed(0)}`}
                      color={pairsLiveHistory.avgPnlPerTrade >= 0 ? '#10b981' : '#ef4444'} />
                  </div>

                  {pairsLiveHistory.trades && pairsLiveHistory.trades.filter(t => t.status === 'CLOSED').length > 0 && (
                    <div style={{ overflowX: 'auto', maxHeight: '400px', overflowY: 'auto' }}>
                      <table>
                        <thead style={{ position: 'sticky', top: 0 }}>
                          <tr>
                            <th>Date</th>
                            <th>Pair</th>
                            <th>Dir</th>
                            <th style={{textAlign:'right'}}>Entry Z</th>
                            <th style={{textAlign:'right'}}>Exit Z</th>
                            <th style={{textAlign:'right'}}>Leg A ₹</th>
                            <th style={{textAlign:'right'}}>Leg B ₹</th>
                            <th style={{textAlign:'right'}}>Net ₹</th>
                            <th>Reason</th>
                            <th>Mode</th>
                          </tr>
                        </thead>
                        <tbody>
                          {pairsLiveHistory.trades.filter(t => t.status === 'CLOSED').map(t => (
                            <tr key={t.id}>
                              <td style={{ fontFamily: 'monospace', fontSize: '11px', color: '#6b7280' }}>
                                {t.entryTime ? new Date(t.entryTime).toLocaleDateString('en-IN', { day: '2-digit', month: 'short' }) : '-'}
                              </td>
                              <td style={{ fontWeight: 700, fontSize: '12px' }}>{t.pairKey}</td>
                              <td style={{ fontSize: '11px', color: t.direction === 'SHORT_A_LONG_B' ? '#ef4444' : '#10b981' }}>
                                {t.direction === 'SHORT_A_LONG_B' ? '↓A↑B' : '↑A↓B'}
                              </td>
                              <td style={{ textAlign: 'right', fontFamily: 'monospace' }}>{t.entryZ}</td>
                              <td style={{ textAlign: 'right', fontFamily: 'monospace', color: '#6b7280' }}>{t.exitZ ?? '—'}</td>
                              <td style={{ textAlign: 'right', fontFamily: 'monospace',
                                color: t.legAPnl > 0 ? '#10b981' : '#ef4444' }}>
                                {t.legAPnl != null ? `${t.legAPnl >= 0 ? '+' : ''}₹${Number(t.legAPnl).toFixed(0)}` : '—'}
                              </td>
                              <td style={{ textAlign: 'right', fontFamily: 'monospace',
                                color: t.legBPnl > 0 ? '#10b981' : '#ef4444' }}>
                                {t.legBPnl != null ? `${t.legBPnl >= 0 ? '+' : ''}₹${Number(t.legBPnl).toFixed(0)}` : '—'}
                              </td>
                              <td style={{ textAlign: 'right', fontWeight: 700,
                                color: t.netPnl > 0 ? '#10b981' : t.netPnl < 0 ? '#ef4444' : '#6b7280' }}>
                                {t.netPnl != null ? `${t.netPnl >= 0 ? '+' : ''}₹${Number(t.netPnl).toFixed(0)}` : '—'}
                              </td>
                              <td style={{ fontSize: '11px', color: '#6b7280' }}>{t.exitReason}</td>
                              <td>
                                <span className="tag" style={{
                                  background: t.mode === 'LIVE' ? '#fee2e2' : '#ede9fe',
                                  color: t.mode === 'LIVE' ? '#991b1b' : '#5b21b6',
                                }}>{t.mode}</span>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </div>
              )}

              {/* Configured pairs info */}
              <div className="card">
                <div style={{ fontWeight: 800, fontSize: '14px', marginBottom: '12px', color: '#1f2937' }}>
                  Active Pairs (exact-segment, validated Jun 2026: 68% WR, PF 2.37)
                </div>
                <div style={{ display: 'flex', gap: '10px', flexWrap: 'wrap' }}>
                  {[
                    'BAJAJ-AUTO / HEROMOTOCO — 2-Wheeler',
                    'KOTAKBANK / AXISBANK — Private Banking',
                    'HINDALCO / TATASTEEL — Metals',
                    'ASIANPAINT / BERGEPAINT — Paints',
                    'CIPLA / LUPIN — Pharma',
                  ].map(p => (
                    <span key={p} style={{
                      padding: '6px 14px', borderRadius: '20px', fontSize: '12px', fontWeight: 700,
                      background: '#f0fdf4', border: '1px solid #6ee7b7', color: '#065f46',
                    }}>{p}</span>
                  ))}
                </div>
                <div style={{ marginTop: '12px', fontSize: '11px', color: '#9ca3af', lineHeight: '1.6' }}>
                  Entry: z ∈ [1.5, 2.5] at 9:30 AM · Exit: z ≤ 0.5 (reversion) or z ≥ 4.0 (stop) · EOD close: 15:05
                </div>
              </div>
            </>
          )}

          {!pairsLiveLoading && !pairsLiveData && (
            <div className="card" style={{ textAlign: 'center', padding: '40px' }}>
              <div style={{ fontSize: '40px', marginBottom: '12px' }}>🔴</div>
              <div style={{ fontWeight: 700, fontSize: '16px', color: '#1f2937', marginBottom: '8px' }}>
                Pairs Live Engine
              </div>
              <div style={{ color: '#6b7280', fontSize: '14px', marginBottom: '20px' }}>
                Click Refresh to load today's pairs activity
              </div>
              <button onClick={loadPairsLiveStatus} style={{
                padding: '10px 24px', borderRadius: '8px', background: '#dc2626', color: 'white',
                border: 'none', fontWeight: 700, cursor: 'pointer',
              }}>Load Dashboard</button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
