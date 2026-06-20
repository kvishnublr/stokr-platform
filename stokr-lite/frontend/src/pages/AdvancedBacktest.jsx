import { useState, useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import client from '../api/client';

async function ensureAuthenticated() {
  const token = localStorage.getItem('token');
  if (token) return; // Already authenticated

  try {
    // Try to register and login automatically
    const response = await fetch('/api/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: 'Test User',
        email: 'test-' + Date.now() + '@test.com',
        password: 'Test123!@'
      })
    });

    if (response.ok) {
      const data = await response.json();
      localStorage.setItem('token', data.accessToken);
      localStorage.setItem('refreshToken', data.refreshToken);
    }
  } catch (e) {
    console.warn('Auto-login failed:', e);
  }
}

export default function AdvancedBacktest() {
  useEffect(() => {
    ensureAuthenticated();
  }, []);
  const [dateStart, setDateStart] = useState('');
  const [dateEnd, setDateEnd] = useState('');
  const [selectedTimeframe, setSelectedTimeframe] = useState('daily');
  const [selectedStrategy, setSelectedStrategy] = useState('ALL');
  const [backtestResults, setBacktestResults] = useState(null);
  const [backtestLoading, setBacktestLoading] = useState(false);
  const [dataLoading, setDataLoading] = useState(false);
  const [error, setError] = useState(null);
  const [dataLoadResult, setDataLoadResult] = useState(null);

  const { data: strategies = [] } = useQuery({
    queryKey: ['strategies'],
    queryFn: async () => {
      return ['ORB', 'ADV_CASH', 'VWAP_SQUEEZE', 'GAP_FILL', 'VWAP_BOUNCE'];
    },
  });

  const timeframes = ['1min', '5min', '15min', 'hourly', 'daily'];

  const strategySymbols = {
    'ORB': ['RELIANCE', 'TCS', 'INFY', 'HDFC', 'ICICI'],
    'ADV_CASH': ['RELIANCE', 'TCS', 'WIPRO', 'AXISBANK', 'INFY'],
    'VWAP_SQUEEZE': ['RELIANCE', 'TCS', 'WIPRO', 'HDFC', 'ICICI'],
    'GAP_FILL': ['RELIANCE', 'TCS', 'INFY', 'HDFC', 'AXISBANK'],
    'VWAP_BOUNCE': ['RELIANCE', 'TCS', 'WIPRO', 'ICICI', 'INFY'],
    'ALL': ['RELIANCE', 'TCS', 'WIPRO', 'INFY', 'HDFC', 'ICICI', 'AXISBANK']
  };

  const getSymbolsForStrategy = (strategy) => {
    return strategySymbols[strategy] || strategySymbols['ALL'];
  };

  const loadHistoricalData = async () => {
    setDataLoading(true);
    setError(null);
    setDataLoadResult(null);
    try {
      // Auto-set 1 month date range
      const endDate = new Date();
      const startDate = new Date(endDate);
      startDate.setDate(startDate.getDate() - 30); // 1 month back

      const formatDate = (date) => {
        const d = String(date.getDate()).padStart(2, '0');
        const m = String(date.getMonth() + 1).padStart(2, '0');
        const y = date.getFullYear();
        return `${d}-${m}-${y}`;
      };

      setDateStart(formatDate(startDate));
      setDateEnd(formatDate(endDate));

      const params = new URLSearchParams();
      if (selectedStrategy && selectedStrategy !== 'ALL') {
        params.append('strategy', selectedStrategy);
      }
      params.append('timeframe', selectedTimeframe);
      params.append('dateStart', startDate.toISOString());
      params.append('dateEnd', endDate.toISOString());

      const res = await client.post('/backtest/load-data?' + params);
      setDataLoadResult(res.data);
    } catch (e) {
      console.error('Data loading failed:', e);
      setError(e.response?.data?.error || 'Data loading failed');
    } finally {
      setDataLoading(false);
    }
  };

  const runBacktest = async () => {
    setBacktestLoading(true);
    setError(null);
    try {
      const params = new URLSearchParams();
      if (selectedStrategy && selectedStrategy !== 'ALL') {
        params.append('strategy', selectedStrategy);
      }
      if (dateStart) params.append('dateStart', new Date(dateStart).toISOString());
      if (dateEnd) params.append('dateEnd', new Date(dateEnd).toISOString());
      params.append('timeframe', selectedTimeframe);

      const res = await client.post('/backtest/advanced?' + params);
      setBacktestResults(res.data);
    } catch (e) {
      console.error('Backtest failed:', e);
      setError(e.response?.data?.error || 'Backtest failed');
      setBacktestResults(null);
    } finally {
      setBacktestLoading(false);
    }
  };

  const symbols = getSymbolsForStrategy(selectedStrategy);

  return (
    <div style={{ animation: 'fadeIn 0.5s ease', padding: '24px' }}>
      <style>{`
        @keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
        .input-group { margin-bottom: 16px; }
        .label-text { display: block; font-size: 12px; font-weight: 600; color: #6b7280; margin-bottom: 6px; text-transform: uppercase; }
        .input-field { width: 100%; padding: 8px 12px; border-radius: 8px; border: 1px solid #d1d5db; font-size: 13px; }
        .button-primary { padding: 10px 20px; background: linear-gradient(135deg, #6366f1, #8b5cf6); color: white; border: none; border-radius: 8px; cursor: pointer; font-size: 13px; font-weight: 700; transition: all 0.2s; }
        .button-primary:hover { transform: translateY(-2px); box-shadow: 0 8px 16px rgba(99, 102, 241, 0.3); }
        .button-primary:disabled { opacity: 0.5; cursor: not-allowed; }
        .button-secondary { padding: 10px 20px; background: #f3f4f6; color: #374151; border: 1px solid #d1d5db; border-radius: 8px; cursor: pointer; font-size: 13px; font-weight: 600; transition: all 0.2s; }
        .button-secondary:hover { background: #e5e7eb; }
        .symbol-badge { display: inline-block; padding: 4px 10px; background: #e0e7ff; color: #4f46e5; border-radius: 6px; font-size: 12px; margin: 2px; font-weight: 600; }
      `}</style>

      {/* Header */}
      <div style={{ marginBottom: '32px' }}>
        <h1 style={{ fontSize: '32px', fontWeight: '800', color: '#1f2937', margin: 0, marginBottom: '8px' }}>
          📈 Backtest by Strategy
        </h1>
        <p style={{ color: '#6b7280', fontSize: '15px', margin: 0 }}>
          Load 1 month of candle data from Chartink, then backtest your strategy
        </p>
      </div>

      {/* Controls Panel */}
      <div style={{ background: 'white', borderRadius: '12px', padding: '24px', boxShadow: '0 2px 8px rgba(0,0,0,0.08)', marginBottom: '24px' }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(250px, 1fr))', gap: '20px', marginBottom: '24px' }}>
          {/* Strategy */}
          <div>
            <label style={{ display: 'block', fontSize: '12px', fontWeight: '600', color: '#6b7280', marginBottom: '6px', textTransform: 'uppercase' }}>
              Strategy (Load Data for This)
            </label>
            <select
              value={selectedStrategy}
              onChange={(e) => setSelectedStrategy(e.target.value)}
              style={{ width: '100%', padding: '8px 12px', borderRadius: '8px', border: '1px solid #d1d5db', fontSize: '13px', cursor: 'pointer' }}
            >
              <option value="ALL">All Strategies</option>
              {strategies.map(s => <option key={s} value={s}>{s}</option>)}
            </select>
          </div>

          {/* Timeframe */}
          <div>
            <label style={{ display: 'block', fontSize: '12px', fontWeight: '600', color: '#6b7280', marginBottom: '6px', textTransform: 'uppercase' }}>
              Timeframe
            </label>
            <select
              value={selectedTimeframe}
              onChange={(e) => setSelectedTimeframe(e.target.value)}
              style={{ width: '100%', padding: '8px 12px', borderRadius: '8px', border: '1px solid #d1d5db', fontSize: '13px', cursor: 'pointer' }}
            >
              {timeframes.map(tf => <option key={tf} value={tf}>{tf}</option>)}
            </select>
          </div>

          {/* Date Start */}
          <div>
            <label style={{ display: 'block', fontSize: '12px', fontWeight: '600', color: '#6b7280', marginBottom: '6px', textTransform: 'uppercase' }}>
              Backtest Start Date
            </label>
            <input
              type="date"
              value={dateStart}
              onChange={(e) => setDateStart(e.target.value)}
              style={{ width: '100%', padding: '8px 12px', borderRadius: '8px', border: '1px solid #d1d5db', fontSize: '13px' }}
            />
          </div>

          {/* Date End */}
          <div>
            <label style={{ display: 'block', fontSize: '12px', fontWeight: '600', color: '#6b7280', marginBottom: '6px', textTransform: 'uppercase' }}>
              Backtest End Date
            </label>
            <input
              type="date"
              value={dateEnd}
              onChange={(e) => setDateEnd(e.target.value)}
              style={{ width: '100%', padding: '8px 12px', borderRadius: '8px', border: '1px solid #d1d5db', fontSize: '13px' }}
            />
          </div>
        </div>

        {/* Symbols for selected strategy */}
        <div style={{ marginBottom: '24px' }}>
          <label style={{ display: 'block', fontSize: '12px', fontWeight: '600', color: '#6b7280', marginBottom: '8px', textTransform: 'uppercase' }}>
            Stocks for {selectedStrategy}
          </label>
          <div>
            {symbols.map(symbol => (
              <span key={symbol} className="symbol-badge">{symbol}</span>
            ))}
          </div>
        </div>

        {/* Buttons */}
        <div style={{ display: 'flex', gap: '12px', flexWrap: 'wrap' }}>
          <button
            onClick={loadHistoricalData}
            disabled={dataLoading || backtestLoading}
            className="button-secondary"
          >
            {dataLoading ? '⏳ Loading Data...' : '📥 Load 1-Month Data from Chartink'}
          </button>
          <button
            onClick={runBacktest}
            disabled={backtestLoading || dataLoading}
            className="button-primary"
          >
            {backtestLoading ? '⏳ Running Backtest...' : '▶ Run Backtest'}
          </button>
        </div>
      </div>

      {/* Data Load Result */}
      {dataLoadResult && (
        <div style={{ background: '#f0fdf4', border: '1px solid #86efac', borderRadius: '12px', padding: '16px', marginBottom: '24px' }}>
          <h3 style={{ margin: '0 0 12px 0', color: '#166534' }}>✅ Data Loaded Successfully</h3>
          <div style={{ fontSize: '13px', color: '#166534' }}>
            <p><strong>Strategy:</strong> {dataLoadResult.strategy}</p>
            <p><strong>Total Candles:</strong> {dataLoadResult.totalCandles}</p>
            <p><strong>Symbols:</strong> {dataLoadResult.symbols?.join(', ')}</p>
            {dataLoadResult.failedSymbols?.length > 0 && (
              <p><strong>Failed:</strong> {dataLoadResult.failedSymbols.join(', ')}</p>
            )}
            <p><strong>Date Range:</strong> {new Date(dataLoadResult.dateRange.start).toLocaleDateString()} to {new Date(dataLoadResult.dateRange.end).toLocaleDateString()}</p>
          </div>
        </div>
      )}

      {/* Error */}
      {error && (
        <div style={{ background: '#fee2e2', border: '1px solid #fca5a5', borderRadius: '12px', padding: '16px', marginBottom: '24px', color: '#991b1b' }}>
          ❌ {error}
        </div>
      )}

      {/* Backtest Results */}
      {backtestResults && (
        <div style={{ background: 'white', borderRadius: '12px', padding: '24px', boxShadow: '0 2px 8px rgba(0,0,0,0.08)' }}>
          <h2 style={{ margin: '0 0 20px 0', color: '#1f2937' }}>📊 Backtest Results</h2>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '16px' }}>
            <div style={{ padding: '16px', background: '#f9fafb', borderRadius: '8px', border: '1px solid #e5e7eb' }}>
              <div style={{ fontSize: '12px', color: '#6b7280', marginBottom: '4px', textTransform: 'uppercase', fontWeight: '600' }}>Total Trades</div>
              <div style={{ fontSize: '24px', fontWeight: '800', color: '#1f2937' }}>{backtestResults.totalTrades}</div>
            </div>
            <div style={{ padding: '16px', background: '#f9fafb', borderRadius: '8px', border: '1px solid #e5e7eb' }}>
              <div style={{ fontSize: '12px', color: '#6b7280', marginBottom: '4px', textTransform: 'uppercase', fontWeight: '600' }}>Win Rate</div>
              <div style={{ fontSize: '24px', fontWeight: '800', color: '#10b981' }}>{backtestResults.winRate.toFixed(2)}%</div>
            </div>
            <div style={{ padding: '16px', background: '#f9fafb', borderRadius: '8px', border: '1px solid #e5e7eb' }}>
              <div style={{ fontSize: '12px', color: '#6b7280', marginBottom: '4px', textTransform: 'uppercase', fontWeight: '600' }}>Total P&L</div>
              <div style={{ fontSize: '24px', fontWeight: '800', color: backtestResults.totalPnL >= 0 ? '#10b981' : '#ef4444' }}>₹{backtestResults.totalPnL.toFixed(2)}</div>
            </div>
            <div style={{ padding: '16px', background: '#f9fafb', borderRadius: '8px', border: '1px solid #e5e7eb' }}>
              <div style={{ fontSize: '12px', color: '#6b7280', marginBottom: '4px', textTransform: 'uppercase', fontWeight: '600' }}>Avg P&L</div>
              <div style={{ fontSize: '24px', fontWeight: '800', color: backtestResults.avgPnL >= 0 ? '#10b981' : '#ef4444' }}>₹{backtestResults.avgPnL.toFixed(2)}</div>
            </div>
            <div style={{ padding: '16px', background: '#f9fafb', borderRadius: '8px', border: '1px solid #e5e7eb' }}>
              <div style={{ fontSize: '12px', color: '#6b7280', marginBottom: '4px', textTransform: 'uppercase', fontWeight: '600' }}>Profit Factor</div>
              <div style={{ fontSize: '24px', fontWeight: '800', color: '#f59e0b' }}>{backtestResults.profitFactor.toFixed(2)}</div>
            </div>
            <div style={{ padding: '16px', background: '#f9fafb', borderRadius: '8px', border: '1px solid #e5e7eb' }}>
              <div style={{ fontSize: '12px', color: '#6b7280', marginBottom: '4px', textTransform: 'uppercase', fontWeight: '600' }}>Max Drawdown</div>
              <div style={{ fontSize: '24px', fontWeight: '800', color: '#ef4444' }}>{backtestResults.maxDrawdown.toFixed(2)}%</div>
            </div>
          </div>
          <div style={{ marginTop: '16px', paddingTop: '16px', borderTop: '1px solid #e5e7eb', fontSize: '12px', color: '#6b7280' }}>
            <p><strong>Strategy:</strong> {backtestResults.strategy}</p>
            <p><strong>Candles Loaded:</strong> {backtestResults.candlesLoaded}</p>
            <p><strong>Date Range:</strong> {new Date(backtestResults.dateRange.start).toLocaleDateString()} to {new Date(backtestResults.dateRange.end).toLocaleDateString()}</p>
          </div>
        </div>
      )}
    </div>
  );
}
