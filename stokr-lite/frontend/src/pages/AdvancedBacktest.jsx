import { useState, useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import client from '../api/client';

export default function AdvancedBacktest() {
  const [dateStart, setDateStart] = useState('');
  const [dateEnd, setDateEnd] = useState('');
  const [selectedSymbols, setSelectedSymbols] = useState(['RELIANCE', 'TCS', 'WIPRO']);
  const [selectedTimeframe, setSelectedTimeframe] = useState('daily');
  const [selectedStrategy, setSelectedStrategy] = useState('ALL');
  const [backtestResults, setBacktestResults] = useState(null);
  const [backtestLoading, setBacktestLoading] = useState(false);
  const [error, setError] = useState(null);

  const { data: strategies = [] } = useQuery({
    queryKey: ['strategies'],
    queryFn: async () => {
      const res = await client.get('/signals?t=' + Date.now());
      const strategiesSet = new Set(res.data.map(s => s.reason).filter(Boolean));
      return Array.from(strategiesSet).sort();
    },
  });

  const timeframes = ['1min', '5min', '15min', 'hourly', 'daily'];
  const commonSymbols = ['RELIANCE', 'TCS', 'WIPRO', 'INFY', 'HDFC', 'ICICI', 'AXISBANK'];

  const handleSymbolToggle = (symbol) => {
    setSelectedSymbols(prev =>
      prev.includes(symbol)
        ? prev.filter(s => s !== symbol)
        : [...prev, symbol]
    );
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
      if (selectedSymbols.length > 0) params.append('symbols', selectedSymbols.join(','));
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

  return (
    <div style={{ animation: 'fadeIn 0.5s ease', padding: '24px' }}>
      <style>{`
        @keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
        .input-group { margin-bottom: '16px'; }
        .label-text { display: 'block'; fontSize: '12px'; fontWeight: '600'; color: '#6b7280'; marginBottom: '6px'; textTransform: 'uppercase'; }
        .input-field { width: '100%'; padding: '8px 12px'; borderRadius: '8px'; border: '1px solid #d1d5db'; fontSize: '13px'; }
        .multi-select { display: 'flex'; flexWrap: 'wrap'; gap: '8px'; }
        .tag { padding: '4px 12px'; borderRadius: '6px'; backgroundColor: '#e0e7ff'; color: '#4f46e5'; cursor: 'pointer'; fontSize: '12px'; border: '1px solid #c7d2fe'; transition: 'all 0.2s'; }
        .tag:hover { backgroundColor: '#c7d2fe'; }
        .tag.active { backgroundColor: '#4f46e5'; color: 'white'; }
        .button-primary { padding: '10px 20px'; background: 'linear-gradient(135deg, #6366f1, #8b5cf6)'; color: 'white'; border: 'none'; borderRadius: '8px'; cursor: 'pointer'; fontSize: '13px'; fontWeight: '700'; transition: 'all 0.2s'; }
        .button-primary:hover { transform: 'translateY(-2px)'; boxShadow: '0 8px 16px rgba(99, 102, 241, 0.3)'; }
        .button-primary:disabled { opacity: '0.5'; cursor: 'not-allowed'; }
      `}</style>

      {/* Header */}
      <div style={{ marginBottom: '32px' }}>
        <h1 style={{ fontSize: '32px', fontWeight: '800', color: '#1f2937', margin: 0, marginBottom: '8px' }}>
          📊 Advanced Backtest
        </h1>
        <p style={{ color: '#6b7280', fontSize: '15px', margin: 0 }}>
          Backtest your strategies against historical candle data with date range selection
        </p>
      </div>

      {/* Controls Panel */}
      <div style={{ background: 'white', borderRadius: '12px', padding: '24px', boxShadow: '0 2px 8px rgba(0,0,0,0.08)', marginBottom: '24px' }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(250px, 1fr))', gap: '20px', marginBottom: '24px' }}>
          {/* Date Start */}
          <div>
            <label style={eval('({ display: "block", fontSize: "12px", fontWeight: "600", color: "#6b7280", marginBottom: "6px", textTransform: "uppercase" })')}>
              Start Date
            </label>
            <input
              type="date"
              value={dateStart}
              onChange={(e) => setDateStart(e.target.value)}
              style={eval('({ width: "100%", padding: "8px 12px", borderRadius: "8px", border: "1px solid #d1d5db", fontSize: "13px" })')}
            />
          </div>

          {/* Date End */}
          <div>
            <label style={eval('({ display: "block", fontSize: "12px", fontWeight: "600", color: "#6b7280", marginBottom: "6px", textTransform: "uppercase" })')}>
              End Date
            </label>
            <input
              type="date"
              value={dateEnd}
              onChange={(e) => setDateEnd(e.target.value)}
              style={eval('({ width: "100%", padding: "8px 12px", borderRadius: "8px", border: "1px solid #d1d5db", fontSize: "13px" })')}
            />
          </div>

          {/* Timeframe */}
          <div>
            <label style={eval('({ display: "block", fontSize: "12px", fontWeight: "600", color: "#6b7280", marginBottom: "6px", textTransform: "uppercase" })')}>
              Timeframe
            </label>
            <select
              value={selectedTimeframe}
              onChange={(e) => setSelectedTimeframe(e.target.value)}
              style={eval('({ width: "100%", padding: "8px 12px", borderRadius: "8px", border: "1px solid #d1d5db", fontSize: "13px", cursor: "pointer" })')}
            >
              {timeframes.map(tf => <option key={tf} value={tf}>{tf}</option>)}
            </select>
          </div>

          {/* Strategy */}
          <div>
            <label style={eval('({ display: "block", fontSize: "12px", fontWeight: "600", color: "#6b7280", marginBottom: "6px", textTransform: "uppercase" })')}>
              Strategy
            </label>
            <select
              value={selectedStrategy}
              onChange={(e) => setSelectedStrategy(e.target.value)}
              style={eval('({ width: "100%", padding: "8px 12px", borderRadius: "8px", border: "1px solid #d1d5db", fontSize: "13px", cursor: "pointer" })')}
            >
              <option value="ALL">All Strategies</option>
              {strategies.map(s => <option key={s} value={s}>{s}</option>)}
            </select>
          </div>
        </div>

        {/* Symbols */}
        <div style={{ marginBottom: '24px' }}>
          <label style={eval('({ display: "block", fontSize: "12px", fontWeight: "600", color: "#6b7280", marginBottom: "12px", textTransform: "uppercase" })')}>
            Select Symbols ({selectedSymbols.length} selected)
          </label>
          <div style={eval('({ display: "flex", flexWrap: "wrap", gap: "8px" })')}>
            {commonSymbols.map(symbol => (
              <button
                key={symbol}
                onClick={() => handleSymbolToggle(symbol)}
                style={{
                  padding: '6px 16px',
                  borderRadius: '6px',
                  border: selectedSymbols.includes(symbol) ? '2px solid #4f46e5' : '1px solid #d1d5db',
                  backgroundColor: selectedSymbols.includes(symbol) ? '#eef2ff' : '#f9fafb',
                  color: selectedSymbols.includes(symbol) ? '#4f46e5' : '#6b7280',
                  cursor: 'pointer',
                  fontSize: '12px',
                  fontWeight: '600',
                  transition: 'all 0.2s ease'
                }}
              >
                {symbol}
              </button>
            ))}
          </div>
        </div>

        {/* Run Button */}
        <button
          onClick={runBacktest}
          disabled={backtestLoading}
          style={{
            padding: '12px 24px',
            background: backtestLoading ? '#ccc' : 'linear-gradient(135deg, #6366f1, #8b5cf6)',
            color: 'white',
            border: 'none',
            borderRadius: '8px',
            cursor: backtestLoading ? 'not-allowed' : 'pointer',
            fontSize: '14px',
            fontWeight: '700',
            transition: 'all 0.2s',
            opacity: backtestLoading ? 0.6 : 1
          }}
        >
          {backtestLoading ? '⏳ Running Backtest...' : '▶ Run Backtest'}
        </button>
      </div>

      {/* Error Message */}
      {error && (
        <div style={{ padding: '12px', background: '#fee2e2', color: '#dc2626', borderRadius: '8px', marginBottom: '24px', fontSize: '13px', fontWeight: '600' }}>
          ❌ {error}
        </div>
      )}

      {/* Results */}
      {backtestResults && (
        <div style={{ background: 'white', borderRadius: '12px', padding: '24px', boxShadow: '0 2px 8px rgba(0,0,0,0.08)' }}>
          <h2 style={{ fontSize: '18px', fontWeight: '700', color: '#1f2937', marginTop: 0, marginBottom: '16px' }}>
            📈 Backtest Results
          </h2>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: '12px' }}>
            <div style={{ background: '#f0f9ff', borderRadius: '8px', padding: '16px', borderLeft: '4px solid #3b82f6' }}>
              <div style={{ fontSize: '11px', fontWeight: '600', color: '#6b7280', marginBottom: '4px' }}>TOTAL TRADES</div>
              <div style={{ fontSize: '20px', fontWeight: '800', color: '#1f2937' }}>{backtestResults.totalTrades || 0}</div>
            </div>
            <div style={{ background: '#f0fdf4', borderRadius: '8px', padding: '16px', borderLeft: '4px solid #10b981' }}>
              <div style={{ fontSize: '11px', fontWeight: '600', color: '#6b7280', marginBottom: '4px' }}>WIN RATE</div>
              <div style={{ fontSize: '20px', fontWeight: '800', color: '#059669' }}>{backtestResults.winRate?.toFixed(1) || 0}%</div>
            </div>
            <div style={{ background: backtestResults.totalPnL >= 0 ? '#f0fdf4' : '#fef2f2', borderRadius: '8px', padding: '16px', borderLeft: backtestResults.totalPnL >= 0 ? '4px solid #10b981' : '4px solid #ef4444' }}>
              <div style={{ fontSize: '11px', fontWeight: '600', color: '#6b7280', marginBottom: '4px' }}>TOTAL P&L</div>
              <div style={{ fontSize: '20px', fontWeight: '800', color: backtestResults.totalPnL >= 0 ? '#059669' : '#dc2626' }}>
                ₹{Math.abs(backtestResults.totalPnL || 0).toLocaleString('en-IN')}
              </div>
            </div>
            <div style={{ background: '#f5f3ff', borderRadius: '8px', padding: '16px', borderLeft: '4px solid #7c3aed' }}>
              <div style={{ fontSize: '11px', fontWeight: '600', color: '#6b7280', marginBottom: '4px' }}>AVG P&L</div>
              <div style={{ fontSize: '20px', fontWeight: '800', color: '#7c3aed' }}>₹{backtestResults.avgPnL?.toLocaleString('en-IN') || 0}</div>
            </div>
            <div style={{ background: '#fef3c7', borderRadius: '8px', padding: '16px', borderLeft: '4px solid #f59e0b' }}>
              <div style={{ fontSize: '11px', fontWeight: '600', color: '#6b7280', marginBottom: '4px' }}>PROFIT FACTOR</div>
              <div style={{ fontSize: '20px', fontWeight: '800', color: '#b45309' }}>{backtestResults.profitFactor?.toFixed(2) || 0}</div>
            </div>
            <div style={{ background: '#fef2f2', borderRadius: '8px', padding: '16px', borderLeft: '4px solid #ef4444' }}>
              <div style={{ fontSize: '11px', fontWeight: '600', color: '#6b7280', marginBottom: '4px' }}>MAX DRAWDOWN</div>
              <div style={{ fontSize: '20px', fontWeight: '800', color: '#dc2626' }}>{backtestResults.maxDrawdown?.toFixed(2) || 0}%</div>
            </div>
          </div>

          {backtestResults.dateRange && (
            <div style={{ marginTop: '16px', fontSize: '12px', color: '#6b7280', padding: '12px', background: '#f9fafb', borderRadius: '8px' }}>
              📅 Tested: {new Date(backtestResults.dateRange.start).toLocaleDateString()} to {new Date(backtestResults.dateRange.end).toLocaleDateString()}
              {backtestResults.candlesLoaded && <div>📊 Candles loaded: {backtestResults.candlesLoaded}</div>}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
