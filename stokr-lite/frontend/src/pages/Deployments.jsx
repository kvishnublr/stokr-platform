import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import client from '../api/client';

const LIVE_STRATEGIES = [
  {
    id: 1,
    key: 'ORB',
    name: 'Opening Range Breakout (ORB)',
    description: 'Trend-following strategy using opening range breakouts with trailing stops',
    status: 'LIVE',
    mode: 'LIVE',
    capital: 5000,
    daily_loss_cap: 1000,
    symbols: ['RELIANCE', 'TCS', 'INFY', 'HDFC', 'ICICI'],
    timeframe: '1m',
    win_rate: 62.5,
    avg_profit: 145.50,
    total_trades: 48,
    edge: 'Trend momentum + trailing stop',
    last_signal: '2026-06-20 09:30:00'
  },
  {
    id: 2,
    key: 'ADV_CASH',
    name: 'ADV Cash Flow',
    description: 'Order flow analysis using advance/decline and cash metrics',
    status: 'LIVE',
    mode: 'LIVE',
    capital: 5000,
    daily_loss_cap: 1000,
    symbols: ['RELIANCE', 'TCS', 'WIPRO', 'AXISBANK', 'INFY'],
    timeframe: '5m',
    win_rate: 58.3,
    avg_profit: 112.75,
    total_trades: 36,
    edge: 'Order book pressure + ADV momentum',
    last_signal: '2026-06-20 10:15:00'
  },
  {
    id: 3,
    key: 'VWAP_SQUEEZE',
    name: 'VWAP Squeeze Breakout',
    description: 'Breakout strategy triggered by volatility compression around VWAP',
    status: 'LIVE',
    mode: 'LIVE',
    capital: 5000,
    daily_loss_cap: 1000,
    symbols: ['RELIANCE', 'TCS', 'WIPRO', 'HDFC', 'ICICI'],
    timeframe: '15m',
    win_rate: 55.2,
    avg_profit: 98.30,
    total_trades: 29,
    edge: 'Mean reversion + volatility squeeze',
    last_signal: '2026-06-20 11:45:00'
  }
];

export default function Deployments() {
  const [hoveredCard, setHoveredCard] = useState(null);

  const getStatusColor = (status) => {
    switch(status) {
      case 'LIVE': return 'bg-emerald-50 text-emerald-700 border border-emerald-200';
      case 'PAPER': return 'bg-blue-50 text-blue-700 border border-blue-200';
      case 'PAUSED': return 'bg-amber-50 text-amber-700 border border-amber-200';
      default: return 'bg-slate-50 text-slate-700 border border-slate-200';
    }
  };

  const getModeIcon = (mode) => {
    return mode === 'LIVE' ? '🔴' : '📄';
  };

  return (
    <div style={{ animation: 'fadeIn 0.5s ease', padding: '24px' }}>
      <style>{`
        @keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
        .strategy-card { transition: all 0.3s ease; cursor: pointer; }
        .strategy-card:hover { transform: translateY(-4px); box-shadow: 0 12px 24px rgba(0,0,0,0.12); }
        .metric-badge { display: inline-flex; align-items: center; gap: 6px; padding: 6px 12px; background: #f3f4f6; border-radius: 6px; font-size: 12px; font-weight: 600; color: #6b7280; }
        .metric-positive { color: #059669; background: #d1fae5; }
      `}</style>

      {/* Header */}
      <div style={{ marginBottom: '32px' }}>
        <h1 style={{ fontSize: '32px', fontWeight: '800', color: '#1f2937', margin: 0, marginBottom: '8px' }}>
          🚀 Live Deployments
        </h1>
        <p style={{ color: '#6b7280', fontSize: '15px', margin: 0 }}>
          3 strategies actively trading · ₹15,000 total capital · ₹3,000 daily loss cap
        </p>
      </div>

      {/* Strategy Cards */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(340px, 1fr))', gap: '20px', marginBottom: '40px' }}>
        {LIVE_STRATEGIES.map((strategy) => (
          <div
            key={strategy.id}
            className="strategy-card"
            onMouseEnter={() => setHoveredCard(strategy.id)}
            onMouseLeave={() => setHoveredCard(null)}
            style={{
              background: 'white',
              border: '1px solid #e5e7eb',
              borderRadius: '12px',
              padding: '20px',
              boxShadow: hoveredCard === strategy.id ? '0 12px 24px rgba(0,0,0,0.12)' : '0 2px 8px rgba(0,0,0,0.08)'
            }}
          >
            {/* Header */}
            <div style={{ marginBottom: '16px', paddingBottom: '12px', borderBottom: '1px solid #f3f4f6' }}>
              <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: '8px' }}>
                <div style={{ flex: 1 }}>
                  <h3 style={{ margin: 0, fontSize: '16px', fontWeight: '700', color: '#1f2937' }}>
                    {getModeIcon(strategy.mode)} {strategy.name}
                  </h3>
                  <p style={{ margin: '4px 0 0 0', fontSize: '12px', color: '#6b7280' }}>
                    {strategy.description}
                  </p>
                </div>
                <span className={`metric-badge ${getStatusColor(strategy.status)}`} style={{ padding: '6px 12px', borderRadius: '6px' }}>
                  {strategy.status}
                </span>
              </div>
            </div>

            {/* Metrics Grid */}
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px', marginBottom: '16px' }}>
              <div style={{ padding: '10px', background: '#f9fafb', borderRadius: '8px' }}>
                <div style={{ fontSize: '11px', color: '#6b7280', marginBottom: '4px', textTransform: 'uppercase', fontWeight: '600' }}>
                  Capital Risk
                </div>
                <div style={{ fontSize: '16px', fontWeight: '800', color: '#1f2937' }}>
                  ₹{strategy.capital}
                </div>
                <div style={{ fontSize: '11px', color: '#6b7280', marginTop: '2px' }}>
                  ₹{strategy.daily_loss_cap}/day cap
                </div>
              </div>

              <div style={{ padding: '10px', background: '#f9fafb', borderRadius: '8px' }}>
                <div style={{ fontSize: '11px', color: '#6b7280', marginBottom: '4px', textTransform: 'uppercase', fontWeight: '600' }}>
                  Win Rate
                </div>
                <div style={{ fontSize: '16px', fontWeight: '800', color: '#059669' }}>
                  {strategy.win_rate}%
                </div>
                <div style={{ fontSize: '11px', color: '#6b7280', marginTop: '2px' }}>
                  {strategy.total_trades} trades
                </div>
              </div>

              <div style={{ padding: '10px', background: '#f9fafb', borderRadius: '8px' }}>
                <div style={{ fontSize: '11px', color: '#6b7280', marginBottom: '4px', textTransform: 'uppercase', fontWeight: '600' }}>
                  Avg P&L
                </div>
                <div style={{ fontSize: '16px', fontWeight: '800', color: '#059669' }}>
                  ₹{strategy.avg_profit.toFixed(2)}
                </div>
                <div style={{ fontSize: '11px', color: '#6b7280', marginTop: '2px' }}>
                  per signal
                </div>
              </div>

              <div style={{ padding: '10px', background: '#f9fafb', borderRadius: '8px' }}>
                <div style={{ fontSize: '11px', color: '#6b7280', marginBottom: '4px', textTransform: 'uppercase', fontWeight: '600' }}>
                  Timeframe
                </div>
                <div style={{ fontSize: '16px', fontWeight: '800', color: '#1f2937' }}>
                  {strategy.timeframe}
                </div>
                <div style={{ fontSize: '11px', color: '#6b7280', marginTop: '2px' }}>
                  {strategy.symbols.length} symbols
                </div>
              </div>
            </div>

            {/* Edge */}
            <div style={{ padding: '10px', background: '#eef2ff', borderRadius: '8px', marginBottom: '12px', borderLeft: '3px solid #4f46e5' }}>
              <div style={{ fontSize: '11px', color: '#4f46e5', fontWeight: '600', marginBottom: '2px' }}>
                💡 Edge
              </div>
              <div style={{ fontSize: '13px', color: '#4f46e5', fontWeight: '500' }}>
                {strategy.edge}
              </div>
            </div>

            {/* Symbols */}
            <div style={{ marginBottom: '12px' }}>
              <div style={{ fontSize: '11px', fontWeight: '600', color: '#6b7280', marginBottom: '6px', textTransform: 'uppercase' }}>
                Symbols ({strategy.symbols.length})
              </div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px' }}>
                {strategy.symbols.map(sym => (
                  <span key={sym} style={{ display: 'inline-block', padding: '3px 8px', background: '#e0e7ff', color: '#4f46e5', borderRadius: '4px', fontSize: '11px', fontWeight: '600' }}>
                    {sym}
                  </span>
                ))}
              </div>
            </div>

            {/* Last Signal */}
            <div style={{ fontSize: '11px', color: '#6b7280', borderTop: '1px solid #f3f4f6', paddingTop: '12px', marginTop: '12px' }}>
              📡 Last signal: <strong>{strategy.last_signal}</strong>
            </div>

            {/* Action Buttons */}
            <div style={{ display: 'flex', gap: '8px', marginTop: '12px', paddingTop: '12px', borderTop: '1px solid #f3f4f6' }}>
              <button style={{
                flex: 1,
                padding: '8px 12px',
                background: '#f3f4f6',
                border: 'none',
                borderRadius: '6px',
                cursor: 'pointer',
                fontSize: '12px',
                fontWeight: '600',
                color: '#4b5563',
                transition: 'all 0.2s'
              }} onMouseEnter={(e) => e.target.style.background = '#e5e7eb'} onMouseLeave={(e) => e.target.style.background = '#f3f4f6'}>
                📊 View Stats
              </button>
              <button style={{
                flex: 1,
                padding: '8px 12px',
                background: '#fecaca',
                border: 'none',
                borderRadius: '6px',
                cursor: 'pointer',
                fontSize: '12px',
                fontWeight: '600',
                color: '#991b1b',
                transition: 'all 0.2s'
              }} onMouseEnter={(e) => e.target.style.background = '#fca5a5'} onMouseLeave={(e) => e.target.style.background = '#fecaca'}>
                🛑 Stop
              </button>
            </div>
          </div>
        ))}
      </div>

      {/* Summary Stats */}
      <div style={{ background: 'white', border: '1px solid #e5e7eb', borderRadius: '12px', padding: '24px' }}>
        <h2 style={{ fontSize: '16px', fontWeight: '700', color: '#1f2937', margin: '0 0 16px 0' }}>
          📈 Summary
        </h2>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '16px' }}>
          <div style={{ paddingBottom: '12px', borderBottom: '1px solid #f3f4f6' }}>
            <div style={{ fontSize: '12px', color: '#6b7280', fontWeight: '600', marginBottom: '4px', textTransform: 'uppercase' }}>
              Total Capital Deployed
            </div>
            <div style={{ fontSize: '20px', fontWeight: '800', color: '#1f2937' }}>
              ₹15,000
            </div>
          </div>
          <div style={{ paddingBottom: '12px', borderBottom: '1px solid #f3f4f6' }}>
            <div style={{ fontSize: '12px', color: '#6b7280', fontWeight: '600', marginBottom: '4px', textTransform: 'uppercase' }}>
              Daily Loss Cap
            </div>
            <div style={{ fontSize: '20px', fontWeight: '800', color: '#991b1b' }}>
              ₹3,000
            </div>
          </div>
          <div style={{ paddingBottom: '12px', borderBottom: '1px solid #f3f4f6' }}>
            <div style={{ fontSize: '12px', color: '#6b7280', fontWeight: '600', marginBottom: '4px', textTransform: 'uppercase' }}>
              Avg Win Rate
            </div>
            <div style={{ fontSize: '20px', fontWeight: '800', color: '#059669' }}>
              58.7%
            </div>
          </div>
          <div style={{ paddingBottom: '12px', borderBottom: '1px solid #f3f4f6' }}>
            <div style={{ fontSize: '12px', color: '#6b7280', fontWeight: '600', marginBottom: '4px', textTransform: 'uppercase' }}>
              Total Trades (Today)
            </div>
            <div style={{ fontSize: '20px', fontWeight: '800', color: '#1f2937' }}>
              113
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
