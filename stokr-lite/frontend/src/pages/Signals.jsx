import { useState, useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import client from '../api/client';

const RISK_PER_SIGNAL = 5000; // ₹5000 per signal

function getExitStatus(signal) {
  if (!signal.exitType) return { label: 'PENDING', color: '#9ca3af', bg: '#f3f4f6' };
  if (signal.exitType === 'TARGET_HIT') return { label: '✓ TARGET HIT', color: '#059669', bg: '#dcfce7' };
  if (signal.exitType === 'SL_HIT') return { label: '✗ SL HIT', color: '#dc2626', bg: '#fee2e2' };
  if (signal.exitType === 'IN_BETWEEN') return { label: '→ IN BETWEEN', color: '#ca8a04', bg: '#fef3c7' };
  return { label: 'UNKNOWN', color: '#6b7280', bg: '#e5e7eb' };
}

function calculatePnL(signal) {
  if (!signal.entryPrice) return 0;

  const entry = Number(signal.entryPrice);
  if (!entry || entry === 0) return 0;

  const isBuy = signal.side === 'BUY';
  let exitPrice = 0;

  // Determine exit price based on exit type
  if (signal.exitType === 'TARGET_HIT' && signal.target) {
    exitPrice = Number(signal.target) || 0;
  } else if (signal.exitType === 'SL_HIT' && signal.stopLoss) {
    exitPrice = Number(signal.stopLoss) || 0;
  } else if (signal.exitType === 'IN_BETWEEN') {
    const target = Number(signal.target) || entry;
    const sl = Number(signal.stopLoss) || entry;
    exitPrice = (target + sl) / 2;
  } else {
    // Fallback: use target for profit, SL for loss
    if (!signal.exitType) {
      exitPrice = Number(signal.target) || entry;
    }
  }

  if (!exitPrice || exitPrice === 0) return 0;

  let profit = 0;
  if (isBuy) {
    profit = (exitPrice - entry) / entry * RISK_PER_SIGNAL;
  } else {
    profit = (entry - exitPrice) / entry * RISK_PER_SIGNAL;
  }

  // Round to nearest rupee for display
  return Math.round(profit);
}

function AnimatedCounter({ value, duration = 1200 }) {
  const [display, setDisplay] = useState(0);
  useEffect(() => {
    let start = null;
    const animate = (ts) => {
      if (!start) start = ts;
      const p = Math.min((ts - start) / duration, 1);
      const eased = 1 - Math.pow(1 - p, 3);
      setDisplay(Math.floor(value * eased));
      if (p < 1) requestAnimationFrame(animate);
    };
    requestAnimationFrame(animate);
  }, [value, duration]);
  return <span>{display.toLocaleString('en-IN')}</span>;
}

function StrategyModal({ strategy, signals, onClose }) {
  if (!strategy) return null;

  const strategySignals = signals.filter(s => s.reason === strategy);
  const total = strategySignals.length;
  const generated = strategySignals.filter(s => s.status === 'GENERATED').length;
  const executed = strategySignals.filter(s => s.status === 'EXECUTED').length;
  const rejected = strategySignals.filter(s => s.status === 'REJECTED').length;

  // Calculate profit metrics (assuming target achieved = profit, stop loss hit = loss)
  const profitableSignals = strategySignals.filter(s => s.status === 'EXECUTED').length;
  const winRate = total > 0 ? ((profitableSignals / total) * 100).toFixed(1) : 0;

  return (
    <div style={{ position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.7)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000, animation: 'fadeIn 0.3s ease' }}>
      <div style={{ backgroundColor: 'white', borderRadius: '16px', padding: '32px', maxWidth: '600px', width: '90%', maxHeight: '80vh', overflow: 'auto', boxShadow: '0 20px 60px rgba(0,0,0,0.3)', animation: 'slideUp 0.3s ease' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '24px' }}>
          <h2 style={{ fontSize: '24px', fontWeight: 800, color: '#1f2937', margin: 0 }}>📊 {strategy} Strategy</h2>
          <button onClick={onClose} style={{ background: 'none', border: 'none', fontSize: '24px', cursor: 'pointer', color: '#6b7280' }}>✕</button>
        </div>

        {/* Stats Grid */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px', marginBottom: '24px' }}>
          <div style={{ padding: '16px', backgroundColor: 'rgba(99,102,241,0.1)', borderRadius: '12px', borderLeft: '4px solid #6366f1' }}>
            <div style={{ fontSize: '12px', color: '#6b7280', fontWeight: 600, marginBottom: '8px' }}>TOTAL SIGNALS</div>
            <div style={{ fontSize: '28px', fontWeight: 800, color: '#1f2937' }}>{total}</div>
          </div>
          <div style={{ padding: '16px', backgroundColor: 'rgba(16,185,129,0.1)', borderRadius: '12px', borderLeft: '4px solid #10b981' }}>
            <div style={{ fontSize: '12px', color: '#6b7280', fontWeight: 600, marginBottom: '8px' }}>WIN RATE</div>
            <div style={{ fontSize: '28px', fontWeight: 800, color: '#059669' }}>{winRate}%</div>
          </div>
          <div style={{ padding: '16px', backgroundColor: 'rgba(59,130,246,0.1)', borderRadius: '12px', borderLeft: '4px solid #3b82f6' }}>
            <div style={{ fontSize: '12px', color: '#6b7280', fontWeight: 600, marginBottom: '8px' }}>EXECUTED</div>
            <div style={{ fontSize: '28px', fontWeight: 800, color: '#2563eb' }}>{executed}</div>
          </div>
          <div style={{ padding: '16px', backgroundColor: 'rgba(244,63,94,0.1)', borderRadius: '12px', borderLeft: '4px solid #ef4444' }}>
            <div style={{ fontSize: '12px', color: '#6b7280', fontWeight: 600, marginBottom: '8px' }}>REJECTED</div>
            <div style={{ fontSize: '28px', fontWeight: 800, color: '#e11d48' }}>{rejected}</div>
          </div>
        </div>

        {/* Performance Bar */}
        <div style={{ marginBottom: '24px' }}>
          <div style={{ fontSize: '12px', fontWeight: 600, color: '#6b7280', marginBottom: '8px' }}>SIGNAL DISTRIBUTION</div>
          <div style={{ display: 'flex', height: '24px', borderRadius: '12px', overflow: 'hidden', backgroundColor: '#e5e7eb' }}>
            <div style={{ width: `${(generated/total)*100}%`, backgroundColor: '#6366f1', display: generated > 0 ? 'block' : 'none' }} title={`Generated: ${generated}`} />
            <div style={{ width: `${(executed/total)*100}%`, backgroundColor: '#10b981', display: executed > 0 ? 'block' : 'none' }} title={`Executed: ${executed}`} />
            <div style={{ width: `${(rejected/total)*100}%`, backgroundColor: '#ef4444', display: rejected > 0 ? 'block' : 'none' }} title={`Rejected: ${rejected}`} />
          </div>
          <div style={{ display: 'flex', gap: '16px', marginTop: '8px', fontSize: '12px' }}>
            <span><span style={{ display: 'inline-block', width: '12px', height: '12px', borderRadius: '2px', backgroundColor: '#6366f1', marginRight: '4px' }} />Generated</span>
            <span><span style={{ display: 'inline-block', width: '12px', height: '12px', borderRadius: '2px', backgroundColor: '#10b981', marginRight: '4px' }} />Executed</span>
            <span><span style={{ display: 'inline-block', width: '12px', height: '12px', borderRadius: '2px', backgroundColor: '#ef4444', marginRight: '4px' }} />Rejected</span>
          </div>
        </div>

        {/* Recent Signals */}
        <div>
          <div style={{ fontSize: '12px', fontWeight: 600, color: '#6b7280', marginBottom: '8px' }}>RECENT SIGNALS</div>
          <div style={{ maxHeight: '300px', overflowY: 'auto' }}>
            {strategySignals.slice(0, 10).map((s, i) => (
              <div key={i} style={{ padding: '8px 12px', borderBottom: '1px solid #e5e7eb', display: 'flex', justifyContent: 'space-between', fontSize: '13px' }}>
                <span style={{ fontWeight: 600 }}>{s.symbol}</span>
                <span style={{ color: s.status === 'EXECUTED' ? '#059669' : s.status === 'GENERATED' ? '#6366f1' : '#ef4444' }}>
                  {s.status === 'EXECUTED' ? '✓' : s.status === 'GENERATED' ? '→' : '✕'} {s.status}
                </span>
              </div>
            ))}
          </div>
        </div>
      </div>

      <style>{`
        @keyframes fadeIn {
          from { opacity: 0; }
          to { opacity: 1; }
        }
        @keyframes slideUp {
          from { transform: translateY(20px); opacity: 0; }
          to { transform: translateY(0); opacity: 1; }
        }
      `}</style>
    </div>
  );
}

export default function Signals() {
  const [filter, setFilter] = useState('ALL');
  const [timeFilter, setTimeFilter] = useState('ALL');
  const [searchSymbol, setSearchSymbol] = useState('');
  const [dateStart, setDateStart] = useState('');
  const [dateEnd, setDateEnd] = useState('');
  const [selectedStrategy, setSelectedStrategy] = useState(null);

  const { data: signals = [], isLoading: sigLoading } = useQuery({
    queryKey: ['signals'],
    queryFn: async () => {
      const res = await client.get('/signals?t=' + Date.now());
      return res.data;
    },
    staleTime: 0,
    gcTime: 0,
    refetchOnWindowFocus: true,
  });

  const { data: stats = { total: 0, today: 0, active: 0 } } = useQuery({
    queryKey: ['signal-stats'],
    queryFn: async () => {
      const res = await client.get('/signals/stats');
      return res.data;
    },
  });

  const todayStart = new Date();
  todayStart.setHours(0, 0, 0, 0);

  const filtered = signals
    .filter(s => {
      if (filter !== 'ALL' && s.status !== filter) return false;
      if (searchSymbol && !s.symbol.toUpperCase().includes(searchSymbol.toUpperCase())) return false;

      const signalDate = new Date(s.createdAt);
      signalDate.setHours(0, 0, 0, 0);

      if (timeFilter === 'TODAY' && signalDate.getTime() !== todayStart.getTime()) return false;
      if (timeFilter === 'HISTORY') {
        if (signalDate.getTime() === todayStart.getTime()) return false;
        if (dateStart) {
          const start = new Date(dateStart);
          start.setHours(0, 0, 0, 0);
          if (signalDate.getTime() < start.getTime()) return false;
        }
        if (dateEnd) {
          const end = new Date(dateEnd);
          end.setHours(23, 59, 59, 999);
          if (signalDate.getTime() > end.getTime()) return false;
        }
      }

      return true;
    })
    .sort((a, b) => {
      const timeA = new Date(a.createdAt).getTime();
      const timeB = new Date(b.createdAt).getTime();
      if (timeB !== timeA) return timeB - timeA; // Descending (latest first)
      return b.id - a.id; // Fallback: sort by ID if timestamps are same
    });

  // Calculate strategy stats based on current filter
  const strategyStats = {};
  filtered.forEach(s => {
    const strat = s.reason || 'Unknown';
    if (!strategyStats[strat]) {
      strategyStats[strat] = { total: 0, generated: 0, executed: 0, rejected: 0 };
    }
    strategyStats[strat].total++;
    if (s.status === 'GENERATED') strategyStats[strat].generated++;
    if (s.status === 'EXECUTED') strategyStats[strat].executed++;
    if (s.status === 'REJECTED') strategyStats[strat].rejected++;
  });

  const statCards = [
    { label: 'Total Signals', value: stats.total, icon: '📡', gradient: 'linear-gradient(135deg, #6366f1, #8b5cf6)', color: '#6366f1' },
    { label: 'Today', value: stats.today, icon: '📅', gradient: 'linear-gradient(135deg, #06b6d4, #3b82f6)', color: '#3b82f6' },
    { label: 'Active', value: stats.active, icon: '⚡', gradient: 'linear-gradient(135deg, #10b981, #059669)', color: '#10b981' },
  ];

  const statusColors = {
    GENERATED: { bg: 'rgba(99,102,241,0.12)', text: '#4f46e5', border: 'rgba(99,102,241,0.25)' },
    EXECUTED: { bg: 'rgba(16,185,129,0.12)', text: '#059669', border: 'rgba(16,185,129,0.25)' },
    REJECTED: { bg: 'rgba(244,63,94,0.12)', text: '#e11d48', border: 'rgba(244,63,94,0.25)' },
    EXPIRED: { bg: 'rgba(148,163,184,0.12)', text: '#64748b', border: 'rgba(148,163,184,0.25)' },
    ENSEMBLE_FILTERED: { bg: 'rgba(217,119,6,0.12)', text: '#b45309', border: 'rgba(217,119,6,0.25)' },
  };

  const sideColors = {
    BUY: { bg: 'rgba(16,185,129,0.12)', text: '#059669' },
    SELL: { bg: 'rgba(244,63,94,0.12)', text: '#e11d48' },
  };

  const sourceColors = {
    CHARTINK: { bg: 'rgba(245,158,11,0.12)', text: '#d97706', border: 'rgba(245,158,11,0.25)' },
    INTERNAL: { bg: 'rgba(99,102,241,0.12)', text: '#4f46e5', border: 'rgba(99,102,241,0.25)' },
  };

  return (
    <div style={{ animation: 'fadeIn 0.5s ease' }}>
      <style>{`
        @keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
        @keyframes slideDown { from { transform: translateY(-20px); opacity: 0; } to { transform: translateY(0); opacity: 1; } }
        @keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.7; } }
        .stat-card { transition: all 0.3s ease; }
        .stat-card:hover { transform: translateY(-4px); box-shadow: 0 12px 24px rgba(0,0,0,0.15); }
        .filter-btn { transition: all 0.2s ease; }
        .filter-btn:hover { transform: scale(1.05); }
        .strategy-link { cursor: pointer; transition: all 0.2s ease; text-decoration: underline; color: #6366f1; }
        .strategy-link:hover { color: #4f46e5; transform: scale(1.05); }
      `}</style>

      {/* Animated Background */}
      <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, background: 'linear-gradient(135deg, rgba(99,102,241,0.03) 0%, rgba(16,185,129,0.03) 100%)', pointerEvents: 'none', zIndex: -1 }} />

      {/* Header */}
      <div style={{ marginBottom: '32px', animation: 'slideDown 0.6s ease' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '8px' }}>
          <div style={{ fontSize: '32px' }}>📡</div>
          <h1 style={{ fontSize: '32px', fontWeight: 800, color: '#1f2937', margin: 0 }}>Trading Signals</h1>
        </div>
        <p style={{ color: '#6b7280', fontSize: '15px', margin: 0 }}>Real-time strategy signals with detailed performance analytics</p>
      </div>

      {/* Risk Deployment Banner */}
      <div style={{ background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)', borderRadius: '12px', padding: '16px 20px', marginBottom: '24px', display: 'flex', alignItems: 'center', gap: '16px', animation: 'slideDown 0.6s ease 0.15s both', boxShadow: '0 4px 12px rgba(102, 126, 234, 0.3)' }}>
        <div style={{ fontSize: '24px' }}>💰</div>
        <div>
          <div style={{ fontSize: '14px', fontWeight: 600, color: 'rgba(255,255,255,0.9)' }}>Risk Deployment per Signal</div>
          <div style={{ fontSize: '24px', fontWeight: 800, color: 'white' }}>₹{RISK_PER_SIGNAL.toLocaleString('en-IN')}</div>
        </div>
        <div style={{ marginLeft: 'auto', fontSize: '12px', color: 'rgba(255,255,255,0.8)', textAlign: 'right' }}>
          <div>Total Signals: {filtered.length}</div>
          <div style={{ fontSize: '14px', fontWeight: 700, color: 'white' }}>Total Risk: ₹{(RISK_PER_SIGNAL * filtered.length).toLocaleString('en-IN')}</div>
        </div>
      </div>

      {/* Stats Cards */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '20px', marginBottom: '32px' }}>
        {statCards.map((card, i) => (
          <div key={i} className="stat-card" style={{ padding: '24px', borderRadius: '16px', background: 'white', boxShadow: '0 4px 12px rgba(0,0,0,0.08)', border: `1px solid rgba(${card.color === '#6366f1' ? '99,102,241' : card.color === '#3b82f6' ? '59,130,246' : '16,185,129'},0.1)`, animation: `slideDown 0.6s ease ${i * 100}ms both` }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '12px' }}>
              <span style={{ fontSize: '28px' }}>{card.icon}</span>
              <div style={{ width: '8px', height: '8px', borderRadius: '50%', background: card.gradient, boxShadow: `0 0 12px ${card.color}80` }} />
            </div>
            <div style={{ fontSize: '28px', fontWeight: 800, color: '#1f2937', marginBottom: '4px' }}>
              <AnimatedCounter value={card.value} />
            </div>
            <div style={{ fontSize: '12px', fontWeight: 600, color: '#9ca3af', textTransform: 'uppercase', letterSpacing: '0.8px' }}>{card.label}</div>
          </div>
        ))}
      </div>

      {/* Search and Filters */}
      <div style={{ marginBottom: '24px' }}>
        <div style={{ marginBottom: '16px', animation: 'slideDown 0.6s ease 0.3s both' }}>
          <input
            type="text"
            placeholder="🔍 Search by symbol (WIPRO, RELIANCE, TCS...)..."
            value={searchSymbol}
            onChange={(e) => setSearchSymbol(e.target.value)}
            style={{
              width: '100%',
              padding: '14px 16px',
              borderRadius: '12px',
              border: '2px solid #e5e7eb',
              fontSize: '13px',
              color: '#1f2937',
              background: 'white',
              outline: 'none',
              transition: 'all 0.2s ease',
              boxShadow: '0 2px 8px rgba(0,0,0,0.04)',
            }}
            onFocus={(e) => {
              e.target.style.borderColor = '#6366f1';
              e.target.style.boxShadow = '0 0 0 3px rgba(99,102,241,0.1)';
            }}
            onBlur={(e) => {
              e.target.style.borderColor = '#e5e7eb';
              e.target.style.boxShadow = '0 2px 8px rgba(0,0,0,0.04)';
            }}
          />
        </div>

        {/* Time and Status Filters */}
        <div style={{ background: 'white', padding: '16px 20px', borderRadius: '12px', boxShadow: '0 2px 8px rgba(0,0,0,0.04)', animation: 'slideDown 0.6s ease 0.35s both' }}>
          <div style={{ marginBottom: '12px' }}>
            <span style={{ fontSize: '13px', fontWeight: 700, color: '#6b7280', marginRight: '12px' }}>TIME:</span>
            {['ALL', 'TODAY', 'HISTORY'].map((tf) => (
              <button
                key={tf}
                onClick={() => {
                  setTimeFilter(tf);
                  if (tf !== 'HISTORY') {
                    setDateStart('');
                    setDateEnd('');
                  }
                }}
                className="filter-btn"
                style={{
                  marginRight: '8px',
                  padding: '6px 12px',
                  borderRadius: '8px',
                  border: 'none',
                  fontSize: '11px',
                  fontWeight: 700,
                  cursor: 'pointer',
                  textTransform: 'uppercase',
                  letterSpacing: '0.4px',
                  background: timeFilter === tf ? 'linear-gradient(135deg, #06b6d4, #3b82f6)' : '#f3f4f6',
                  color: timeFilter === tf ? 'white' : '#6b7280',
                  transition: 'all 0.2s ease',
                  boxShadow: timeFilter === tf ? '0 4px 12px rgba(6,182,212,0.3)' : 'none',
                }}
              >
                {tf}
              </button>
            ))}
          </div>

          {/* Date Range for History */}
          {timeFilter === 'HISTORY' && (
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr auto', gap: '8px', marginBottom: '12px', paddingTop: '12px', borderTop: '1px solid #e5e7eb', animation: 'slideDown 0.3s ease' }}>
              <input
                type="date"
                value={dateStart}
                onChange={(e) => setDateStart(e.target.value)}
                style={{ padding: '8px 12px', borderRadius: '8px', border: '1px solid #d1d5db', fontSize: '12px' }}
              />
              <input
                type="date"
                value={dateEnd}
                onChange={(e) => setDateEnd(e.target.value)}
                style={{ padding: '8px 12px', borderRadius: '8px', border: '1px solid #d1d5db', fontSize: '12px' }}
              />
              <button onClick={() => { setDateStart(''); setDateEnd(''); }} style={{ padding: '8px 12px', background: '#fee2e2', color: '#dc2626', borderRadius: '8px', border: 'none', cursor: 'pointer', fontSize: '11px', fontWeight: 600 }}>CLEAR</button>
            </div>
          )}

          {/* Status Filters */}
          <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', alignItems: 'center' }}>
            <span style={{ fontSize: '13px', fontWeight: 700, color: '#6b7280', marginRight: '8px' }}>STATUS:</span>
            {['ALL', 'GENERATED', 'EXECUTED', 'REJECTED', 'EXPIRED'].map((status) => (
              <button
                key={status}
                onClick={() => setFilter(status)}
                className="filter-btn"
                style={{
                  padding: '8px 16px',
                  borderRadius: '10px',
                  border: 'none',
                  fontSize: '12px',
                  fontWeight: 700,
                  cursor: 'pointer',
                  textTransform: 'uppercase',
                  letterSpacing: '0.5px',
                  background: filter === status ? 'linear-gradient(135deg, #6366f1, #8b5cf6)' : '#f3f4f6',
                  color: filter === status ? 'white' : '#6b7280',
                  transition: 'all 0.2s ease',
                  boxShadow: filter === status ? '0 4px 12px rgba(99,102,241,0.3)' : 'none',
                }}
              >
                {status === 'ALL' ? 'All Signals' : status}
              </button>
            ))}
            <span style={{ marginLeft: 'auto', fontSize: '13px', color: '#6b7280' }}>
              Showing <strong style={{ color: '#1f2937' }}>{filtered.length}</strong> of {signals.length}
            </span>
          </div>
        </div>
      </div>

      {/* Strategy Stats Cards */}
      {Object.keys(strategyStats).length > 0 && (
        <div style={{ marginBottom: '24px', animation: 'slideDown 0.6s ease 0.4s both' }}>
          <h3 style={{ fontSize: '14px', fontWeight: 700, color: '#6b7280', textTransform: 'uppercase', letterSpacing: '0.8px', marginBottom: '12px' }}>STRATEGY PERFORMANCE</h3>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '12px' }}>
            {Object.entries(strategyStats).map(([strategy, stats], i) => (
              <div
                key={i}
                onClick={() => setSelectedStrategy(strategy)}
                style={{
                  padding: '16px',
                  background: 'white',
                  borderRadius: '12px',
                  boxShadow: '0 2px 8px rgba(0,0,0,0.04)',
                  border: '1px solid #e5e7eb',
                  cursor: 'pointer',
                  transition: 'all 0.2s ease',
                  animation: `slideDown 0.6s ease ${0.4 + i * 50}ms both`,
                }}
                onMouseEnter={(e) => {
                  e.currentTarget.style.transform = 'translateY(-4px)';
                  e.currentTarget.style.boxShadow = '0 8px 16px rgba(0,0,0,0.1)';
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.transform = 'translateY(0)';
                  e.currentTarget.style.boxShadow = '0 2px 8px rgba(0,0,0,0.04)';
                }}
              >
                <div style={{ fontSize: '14px', fontWeight: 700, color: '#1f2937', marginBottom: '8px', cursor: 'pointer' }}>
                  <span className="strategy-link">{strategy}</span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '12px', marginBottom: '8px' }}>
                  <span style={{ color: '#6b7280' }}>Total: <strong style={{ color: '#1f2937' }}>{stats.total}</strong></span>
                  <span style={{ color: '#059669' }}>✓ {stats.executed}</span>
                </div>
                <div style={{ height: '4px', backgroundColor: '#e5e7eb', borderRadius: '2px', overflow: 'hidden' }}>
                  <div style={{ height: '100%', width: `${(stats.executed/stats.total)*100}%`, backgroundColor: '#10b981', transition: 'width 0.3s ease' }} />
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Signals Table */}
      <div style={{ background: 'white', borderRadius: '16px', boxShadow: '0 4px 12px rgba(0,0,0,0.08)', overflow: 'hidden', animation: 'slideDown 0.6s ease 0.45s both' }}>
        {sigLoading ? (
          <div style={{ textAlign: 'center', padding: '60px', color: '#6b7280' }}>
            <div style={{ fontSize: '32px', marginBottom: '12px', animation: 'pulse 1.5s infinite' }}>📡</div>
            Loading signals...
          </div>
        ) : filtered.length === 0 ? (
          <div style={{ textAlign: 'center', padding: '60px', color: '#6b7280' }}>
            <div style={{ fontSize: '48px', marginBottom: '16px' }}>📭</div>
            <div style={{ fontSize: '18px', fontWeight: 700, color: '#1f2937', marginBottom: '8px' }}>No signals found</div>
            <div>Try adjusting your filters or search criteria</div>
          </div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '13px', tableLayout: 'fixed' }}>
              <colgroup>
                <col style={{ width: '8%' }} />
                <col style={{ width: '5%' }} />
                <col style={{ width: '7%' }} />
                <col style={{ width: '7%' }} />
                <col style={{ width: '7%' }} />
                <col style={{ width: '6%' }} />
                <col style={{ width: '10%' }} />
                <col style={{ width: '8%' }} />
                <col style={{ width: '7%' }} />
                <col style={{ width: '9%' }} />
                <col style={{ width: '9%' }} />
                <col style={{ width: '11%' }} />
                <col style={{ width: '8%' }} />
              </colgroup>
              <thead style={{ backgroundColor: '#f9fafb', borderBottom: '2px solid #e5e7eb', position: 'sticky', top: 0, zIndex: 10 }}>
                <tr>
                  {['SYMBOL', 'SIDE', 'ENTRY', 'SL', 'TARGET', 'SCORE', 'STRATEGY', 'STATUS', 'SOURCE', 'SIGNAL TIME', 'ENTRY TIME', 'EXIT STATUS', 'P&L'].map((h) => (
                    <th key={h} style={{ textAlign: h === 'SYMBOL' ? 'left' : 'center', padding: '12px', color: '#6b7280', fontWeight: 700, fontSize: '11px', textTransform: 'uppercase', letterSpacing: '0.8px', whiteSpace: 'nowrap', background: h === 'P&L' ? 'rgba(99,102,241,0.05)' : 'transparent' }}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {filtered.map((s, i) => (
                  <tr key={`${s.id}-${i}`} style={{ borderBottom: '1px solid #e5e7eb', animation: `slideDown 0.3s ease ${(i * 20)}ms`, transition: 'background 0.2s ease' }} onMouseEnter={(e) => e.currentTarget.style.backgroundColor = 'rgba(99,102,241,0.02)'} onMouseLeave={(e) => e.currentTarget.style.backgroundColor = 'transparent'}>
                    <td style={{ padding: '12px', fontWeight: 700, color: '#1f2937', fontFamily: 'JetBrains Mono, monospace', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{s.symbol}</td>
                    <td style={{ padding: '12px', textAlign: 'center' }}>
                      <span style={{ padding: '3px 8px', borderRadius: '6px', fontSize: '10px', fontWeight: 800, textTransform: 'uppercase', letterSpacing: '0.3px', background: sideColors[s.side]?.bg, color: sideColors[s.side]?.text, display: 'inline-block', whiteSpace: 'nowrap' }}>{s.side}</span>
                    </td>
                    <td style={{ padding: '12px', textAlign: 'right', fontFamily: 'JetBrains Mono, monospace', fontWeight: 600, color: '#1f2937', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      {s.entryPrice ? Number(s.entryPrice).toFixed(2) : '—'}
                    </td>
                    <td style={{ padding: '12px', textAlign: 'right', fontFamily: 'JetBrains Mono, monospace', fontWeight: 600, color: '#e11d48', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      {s.stopLoss ? Number(s.stopLoss).toFixed(2) : '—'}
                    </td>
                    <td style={{ padding: '12px', textAlign: 'right', fontFamily: 'JetBrains Mono, monospace', fontWeight: 600, color: '#059669', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      {s.target ? Number(s.target).toFixed(2) : '—'}
                    </td>
                    <td style={{ padding: '12px', textAlign: 'center' }}>
                      {s.confidence != null ? (
                        <span style={{ fontFamily: 'JetBrains Mono, monospace', fontWeight: 700, fontSize: '12px', color: Number(s.confidence) >= 80 ? '#10b981' : Number(s.confidence) >= 60 ? '#f59e0b' : '#ef4444' }}>
                          {Number(s.confidence).toFixed(0)}%
                        </span>
                      ) : (
                        <span style={{ fontSize: '11px', color: '#d1d5db' }}>—</span>
                      )}
                    </td>
                    <td style={{ padding: '12px', textAlign: 'center', color: '#6366f1', fontSize: '11px', overflow: 'hidden', textOverflow: 'ellipsis', fontWeight: 600, cursor: 'pointer' }} onClick={() => setSelectedStrategy(s.reason)} title={s.reason}>
                      <span style={{ color: '#6366f1', textDecoration: 'underline', cursor: 'pointer' }}>{s.reason || '—'}</span>
                    </td>
                    <td style={{ padding: '12px', textAlign: 'center' }}>
                      <span style={{ padding: '3px 8px', borderRadius: '6px', fontSize: '9px', fontWeight: 800, textTransform: 'uppercase', letterSpacing: '0.3px', background: statusColors[s.status]?.bg, color: statusColors[s.status]?.text, display: 'inline-block', whiteSpace: 'nowrap' }}>{s.status}</span>
                    </td>
                    <td style={{ padding: '12px', textAlign: 'center' }}>
                      <span style={{ padding: '3px 8px', borderRadius: '6px', fontSize: '9px', fontWeight: 800, textTransform: 'uppercase', letterSpacing: '0.3px', background: sourceColors[s.source]?.bg, color: sourceColors[s.source]?.text, display: 'inline-block', whiteSpace: 'nowrap' }}>{s.source || 'INTERNAL'}</span>
                    </td>
                    <td style={{ padding: '12px', textAlign: 'center', color: '#6b7280', fontSize: '11px', fontFamily: 'JetBrains Mono, monospace', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      {(() => {
                        const date = new Date(s.createdAt);
                        const day = String(date.getDate()).padStart(2, '0');
                        const month = String(date.getMonth() + 1).padStart(2, '0');
                        const time = date.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', hour12: true });
                        return `${day}/${month} ${time}`;
                      })()}
                    </td>
                    <td style={{ padding: '12px', textAlign: 'center', color: '#6b7280', fontSize: '11px', fontFamily: 'JetBrains Mono, monospace', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      {s.entryTime ? (() => {
                        const date = new Date(s.entryTime);
                        const day = String(date.getDate()).padStart(2, '0');
                        const month = String(date.getMonth() + 1).padStart(2, '0');
                        const time = date.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', hour12: true });
                        return `${day}/${month} ${time}`;
                      })() : '—'}
                    </td>
                    <td style={{ padding: '12px', textAlign: 'center', fontSize: '11px' }}>
                      {(() => {
                        const status = getExitStatus(s);
                        const exitTime = s.exitTime ? (() => {
                          const date = new Date(s.exitTime);
                          const day = String(date.getDate()).padStart(2, '0');
                          const month = String(date.getMonth() + 1).padStart(2, '0');
                          const time = date.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', hour12: true });
                          return `${day}/${month} ${time}`;
                        })() : null;
                        return (
                          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '4px' }}>
                            {exitTime && <span style={{ fontSize: '10px', color: '#6b7280', fontFamily: 'JetBrains Mono', fontWeight: 500 }}>{exitTime}</span>}
                            <span style={{
                              padding: '4px 10px',
                              borderRadius: '6px',
                              fontSize: '10px',
                              fontWeight: 700,
                              textTransform: 'uppercase',
                              letterSpacing: '0.3px',
                              background: status.bg,
                              color: status.color,
                              whiteSpace: 'nowrap'
                            }}>
                              {status.label}
                            </span>
                          </div>
                        );
                      })()}
                    </td>
                    <td style={{ padding: '12px', textAlign: 'center', fontFamily: 'JetBrains Mono, monospace', fontWeight: 700, fontSize: '12px' }}>
                      {(() => {
                        const pnl = calculatePnL(s);
                        return (
                          <span style={{
                            color: pnl > 0 ? '#059669' : pnl < 0 ? '#dc2626' : '#6b7280',
                            fontSize: '12px',
                            fontWeight: 800
                          }}>
                            {pnl > 0 ? '+' : ''}{pnl.toLocaleString('en-IN', { minimumFractionDigits: 0, maximumFractionDigits: 0 })}
                          </span>
                        );
                      })()}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Summary Footer */}
        {filtered.length > 0 && (
          <div style={{ marginTop: '24px', background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)', borderRadius: '16px', padding: '32px', boxShadow: '0 8px 32px rgba(102, 126, 234, 0.3)', animation: 'slideDown 0.6s ease' }}>
            <div style={{ color: 'white', marginBottom: '24px' }}>
              <div style={{ fontSize: '16px', fontWeight: 600, marginBottom: '4px', opacity: 0.9 }}>Trading Summary</div>
              <div style={{ fontSize: '28px', fontWeight: 800 }}>
                Total P&L: ₹{(() => {
                  let total = 0;
                  filtered.forEach(s => { total += calculatePnL(s); });
                  return total.toLocaleString('en-IN', { minimumFractionDigits: 0 });
                })()}
              </div>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: '16px' }}>
              {/* Total Capital */}
              <div style={{ background: 'rgba(255,255,255,0.1)', borderRadius: '12px', padding: '16px', backdropFilter: 'blur(10px)' }}>
                <div style={{ fontSize: '12px', color: 'rgba(255,255,255,0.8)', marginBottom: '8px', fontWeight: 600, textTransform: 'uppercase' }}>Total Capital</div>
                <div style={{ fontSize: '22px', fontWeight: 800, color: 'white' }}>₹{(RISK_PER_SIGNAL * filtered.length).toLocaleString('en-IN')}</div>
              </div>

              {/* ROI */}
              <div style={{ background: 'rgba(255,255,255,0.1)', borderRadius: '12px', padding: '16px', backdropFilter: 'blur(10px)' }}>
                <div style={{ fontSize: '12px', color: 'rgba(255,255,255,0.8)', marginBottom: '8px', fontWeight: 600, textTransform: 'uppercase' }}>ROI</div>
                <div style={{ fontSize: '22px', fontWeight: 800, color: 'white' }}>
                  {(() => {
                    let total = 0;
                    filtered.forEach(s => { total += calculatePnL(s); });
                    const roi = ((total / (RISK_PER_SIGNAL * filtered.length)) * 100).toFixed(2);
                    return roi + '%';
                  })()}
                </div>
              </div>

              {/* Profitable Trades */}
              <div style={{ background: 'rgba(16,185,129,0.2)', borderRadius: '12px', padding: '16px', backdropFilter: 'blur(10px)', borderLeft: '4px solid #10b981' }}>
                <div style={{ fontSize: '12px', color: 'rgba(255,255,255,0.8)', marginBottom: '8px', fontWeight: 600, textTransform: 'uppercase' }}>Profitable</div>
                <div style={{ fontSize: '22px', fontWeight: 800, color: '#10b981' }}>
                  {(() => {
                    let count = 0;
                    filtered.forEach(s => { if (calculatePnL(s) > 0) count++; });
                    return count;
                  })()} / {filtered.length}
                </div>
              </div>

              {/* Loss Trades */}
              <div style={{ background: 'rgba(239,68,68,0.2)', borderRadius: '12px', padding: '16px', backdropFilter: 'blur(10px)', borderLeft: '4px solid #ef4444' }}>
                <div style={{ fontSize: '12px', color: 'rgba(255,255,255,0.8)', marginBottom: '8px', fontWeight: 600, textTransform: 'uppercase' }}>Loss Trades</div>
                <div style={{ fontSize: '22px', fontWeight: 800, color: '#fca5a5' }}>
                  {(() => {
                    let count = 0;
                    filtered.forEach(s => { if (calculatePnL(s) < 0) count++; });
                    return count;
                  })()} / {filtered.length}
                </div>
              </div>

              {/* Win Rate */}
              <div style={{ background: 'rgba(255,255,255,0.1)', borderRadius: '12px', padding: '16px', backdropFilter: 'blur(10px)' }}>
                <div style={{ fontSize: '12px', color: 'rgba(255,255,255,0.8)', marginBottom: '8px', fontWeight: 600, textTransform: 'uppercase' }}>Win Rate</div>
                <div style={{ fontSize: '22px', fontWeight: 800, color: 'white' }}>
                  {(() => {
                    let profitable = 0;
                    filtered.forEach(s => { if (calculatePnL(s) > 0) profitable++; });
                    const rate = filtered.length > 0 ? ((profitable / filtered.length) * 100).toFixed(1) : 0;
                    return rate + '%';
                  })()}
                </div>
              </div>

              {/* Avg P&L */}
              <div style={{ background: 'rgba(255,255,255,0.1)', borderRadius: '12px', padding: '16px', backdropFilter: 'blur(10px)' }}>
                <div style={{ fontSize: '12px', color: 'rgba(255,255,255,0.8)', marginBottom: '8px', fontWeight: 600, textTransform: 'uppercase' }}>Avg P&L/Trade</div>
                <div style={{ fontSize: '22px', fontWeight: 800, color: 'white' }}>
                  ₹{(() => {
                    let total = 0;
                    filtered.forEach(s => { total += calculatePnL(s); });
                    const avg = filtered.length > 0 ? (total / filtered.length).toFixed(0) : 0;
                    return parseInt(avg).toLocaleString('en-IN');
                  })()}
                </div>
              </div>
            </div>
          </div>
        )}
      </div>

      {/* Strategy Modal */}
      <StrategyModal strategy={selectedStrategy} signals={signals} onClose={() => setSelectedStrategy(null)} />
    </div>
  );
}
