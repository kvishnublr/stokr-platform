import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import client from '../api/client';
import { useEffect, useState, useRef, useMemo } from 'react';

function AnimatedCounter({ value, duration = 1500, prefix = '', suffix = '' }) {
  const [display, setDisplay] = useState(0);
  const startTime = useRef(null);

  useEffect(() => {
    startTime.current = null;
    let raf;
    const animate = (timestamp) => {
      if (!startTime.current) startTime.current = timestamp;
      const progress = Math.min((timestamp - startTime.current) / duration, 1);
      const eased = 1 - Math.pow(1 - progress, 3);
      setDisplay(Math.floor(value * eased));
      if (progress < 1) raf = requestAnimationFrame(animate);
    };
    raf = requestAnimationFrame(animate);
    return () => cancelAnimationFrame(raf);
  }, [value, duration]);

  return <span>{prefix}{display.toLocaleString('en-IN')}{suffix}</span>;
}

export default function Dashboard() {
  const { data: deployments } = useQuery({
    queryKey: ['deployments'],
    queryFn: () => client.get('/deployments').then((r) => r.data),
    staleTime: 30000,
    refetchInterval: 60000,
  });

  const { data: marketStatus } = useQuery({
    queryKey: ['market-status'],
    queryFn: () => client.get('/market/status').then((r) => r.data),
    staleTime: 30000,
    refetchInterval: 60000,
  });

  const { data: tickerData = [] } = useQuery({
    queryKey: ['marketTicker'],
    queryFn: () => client.get('/market/ticker').then((r) => r.data),
    refetchInterval: 10000,
    staleTime: 5000,
  });

  const { data: signalStats } = useQuery({
    queryKey: ['signal-stats'],
    queryFn: () => client.get('/signals/stats').then((r) => r.data),
    staleTime: 30000,
    refetchInterval: 60000,
  });

  const { data: recentOrders } = useQuery({
    queryKey: ['recent-orders'],
    queryFn: () => client.get('/orders', { params: { page: 0, size: 5 } }).then((r) => r.data?.content || r.data),
    staleTime: 30000,
    refetchInterval: 60000,
  });

  const { data: openPositions } = useQuery({
    queryKey: ['open-positions'],
    queryFn: () => client.get('/signals/positions').then((r) => r.data),
    staleTime: 30000,
    refetchInterval: 60000,
  });

  const { data: brokerHealth } = useQuery({
    queryKey: ['broker-health'],
    queryFn: () => client.get('/brokers/health').then((r) => r.data),
    staleTime: 60000,
    refetchInterval: 120000,
  });

  const active = deployments?.filter((d) => d.status === 'ACTIVE') || [];
  const stopped = deployments?.filter((d) => d.status !== 'ACTIVE') || [];
  const paper = deployments?.filter((d) => d.mode === 'PAPER') || [];

  const orderList = Array.isArray(recentOrders) ? recentOrders : [];
  const positions = Array.isArray(openPositions) ? openPositions.filter((p) => p.status === 'OPEN') : [];

  const totalUnrealizedPnl = useMemo(() => {
    if (!positions || positions.length === 0) return 0;
    return positions.reduce((sum, p) => sum + (parseFloat(p.unrealizedPnl) || 0), 0);
  }, [positions]);

  const totalRealizedPnl = useMemo(() => {
    if (!positions || positions.length === 0) return 0;
    return positions.reduce((sum, p) => sum + (parseFloat(p.realizedPnl) || 0), 0);
  }, [positions]);

  const displayTickerItems = useMemo(() => {
    if (Array.isArray(tickerData) && tickerData.length > 0) return tickerData;
    return [
      { symbol: 'NIFTY50', label: 'NIFTY 50', price: 25340.50, change: 60.50, percentChange: 0.24, isUp: true },
      { symbol: 'BANKNIFTY', label: 'BANK NIFTY', price: 54210.00, change: -120.40, percentChange: -0.22, isUp: false },
      { symbol: 'FINNIFTY', label: 'FINNIFTY', price: 24850.00, change: 35.10, percentChange: 0.14, isUp: true },
      { symbol: 'RELIANCE', label: 'RELIANCE', price: 1241.90, change: 14.50, percentChange: 1.18, isUp: true },
      { symbol: 'HDFCBANK', label: 'HDFC BANK', price: 1716.45, change: 8.20, percentChange: 0.48, isUp: true },
      { symbol: 'TCS', label: 'TCS', price: 3820.00, change: -15.40, percentChange: -0.40, isUp: false },
      { symbol: 'INFY', label: 'INFOSYS', price: 1853.80, change: 22.10, percentChange: 1.21, isUp: true },
      { symbol: 'ICICIBANK', label: 'ICICI BANK', price: 1253.70, change: -4.30, percentChange: -0.34, isUp: false },
      { symbol: 'SBIN', label: 'SBIN', price: 816.90, change: 9.80, percentChange: 1.21, isUp: true },
      { symbol: 'BHARTIARTL', label: 'BHARTI AIRTEL', price: 1680.50, change: 11.20, percentChange: 0.67, isUp: true },
      { symbol: 'LT', label: 'L&T', price: 3640.00, change: 28.50, percentChange: 0.79, isUp: true },
    ];
  }, [tickerData]);

  return (
    <div>
      {/* Header */}
      <div className="header" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '32px', paddingBottom: '24px', borderBottom: '2px solid rgba(148,163,184,0.08)' }}>
        <div>
          <h1 style={{ fontSize: '32px', fontWeight: 900, background: 'linear-gradient(135deg, #0f172a 0%, #4f46e5 100%)', WebkitBackgroundClip: 'text', WebkitTextFillColor: 'transparent', letterSpacing: '-1px', marginBottom: '6px' }}>Trading Dashboard</h1>
          <div style={{ fontSize: '13px', color: 'var(--text-muted)', display: 'flex', alignItems: 'center', gap: '12px' }}>
            <div className="live-indicator" style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', padding: '4px 10px', background: 'linear-gradient(135deg, rgba(16,185,129,0.15), rgba(52,211,153,0.1))', borderRadius: '8px', color: '#059669', fontWeight: 600, fontSize: '11px' }}>
              <div className="animate-pulse-dot" style={{ width: '6px', height: '6px', background: marketStatus?.isOpen ? '#10b981' : '#f59e0b', borderRadius: '50%' }} />
              {marketStatus?.isOpen ? 'NSE Market Open' : 'NSE Market Closed'}
            </div>
            <span>•</span>
            <span>Broker: {brokerHealth?.ok ? '✅ Connected' : '⚠️ Disconnected'}</span>
          </div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '14px' }}>
          <Link to="/settings" className="header-btn" style={{ width: '44px', height: '44px', borderRadius: '12px', border: '2px solid rgba(148,163,184,0.15)', background: 'linear-gradient(135deg, rgba(255,255,255,0.8), rgba(255,255,255,0.6))', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '20px', cursor: 'pointer', transition: 'all 0.3s', textDecoration: 'none' }}>⚙️</Link>
        </div>
      </div>

      {/* Market Ticker */}
      <div className="ticker-container-aurora" style={{ marginBottom: '28px', position: 'relative', overflow: 'hidden' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '12px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', fontSize: '11px', fontWeight: 750, textTransform: 'uppercase', letterSpacing: '1.2px', color: 'var(--text-muted)' }}>
            <div className="animate-pulse-dot" style={{ width: '6px', height: '6px', background: marketStatus?.isOpen ? '#10b981' : '#f59e0b', borderRadius: '50%' }} />
            {marketStatus?.isOpen ? 'Real-time Market • Live Stream' : 'Market Snapshot • End of Day'}
          </div>
          <span style={{ fontSize: '10px', fontWeight: 600, color: '#94a3b8' }}>
            Auto-refresh 10s • Hover to pause
          </span>
        </div>

        <div style={{ position: 'relative', overflow: 'hidden', padding: '4px 0', maskImage: 'linear-gradient(to right, transparent, black 30px, black calc(100% - 30px), transparent)', WebkitMaskImage: 'linear-gradient(to right, transparent, black 30px, black calc(100% - 30px), transparent)' }}>
          <div
            className="ticker-track"
            style={{
              display: 'flex',
              gap: '16px',
              animation: 'scroll-infinite 45s linear infinite',
              width: 'max-content',
              paddingLeft: '16px',
            }}
            onMouseEnter={(e) => (e.currentTarget.style.animationPlayState = 'paused')}
            onMouseLeave={(e) => (e.currentTarget.style.animationPlayState = 'running')}
          >
            {[...displayTickerItems, ...displayTickerItems].map((item, idx) => {
              const isUp = item.isUp ?? (item.change >= 0);
              const formattedPrice = Number(item.price || 0).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
              const changeVal = Math.abs(Number(item.change || 0));
              const pctVal = Math.abs(Number(item.percentChange || 0));
              const changeStr = `${isUp ? '+' : '-'}${changeVal.toFixed(2)} (${isUp ? '+' : '-'}${pctVal.toFixed(2)}%)`;

              return (
                <div
                  key={idx}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '10px',
                    whiteSpace: 'nowrap',
                    padding: '7px 15px',
                    borderRadius: '10px',
                    background: 'linear-gradient(135deg, rgba(255,255,255,0.9), rgba(248,250,252,0.85))',
                    border: '1px solid rgba(226,232,240,0.9)',
                    boxShadow: '0 2px 6px rgba(15,23,42,0.03)',
                    cursor: 'pointer',
                    transition: 'all 0.2s ease',
                  }}
                >
                  <span style={{ fontSize: '12px', fontWeight: 800, color: '#0f172a', letterSpacing: '-0.2px' }}>
                    {item.label || item.symbol}
                  </span>
                  <span style={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '12px', fontWeight: 700, color: '#334155' }}>
                    ₹{formattedPrice}
                  </span>
                  <span
                    style={{
                      display: 'inline-flex',
                      alignItems: 'center',
                      gap: '4px',
                      fontSize: '11px',
                      fontWeight: 700,
                      padding: '3px 8px',
                      borderRadius: '6px',
                      background: isUp ? 'rgba(16,185,129,0.12)' : 'rgba(239,68,68,0.1)',
                      color: isUp ? '#059669' : '#dc2626',
                      border: `1px solid ${isUp ? 'rgba(16,185,129,0.25)' : 'rgba(239,68,68,0.25)'}`,
                    }}
                  >
                    {isUp ? '▲' : '▼'} {changeStr}
                  </span>
                </div>
              );
            })}
          </div>
        </div>
      </div>

      {/* Stats Grid */}
      <div className="stats-grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: '18px', marginBottom: '28px' }}>
        <StatBox icon="💰" label="Total Deploys" value={deployments?.length || 0} color="#4f46e5" gradient="linear-gradient(90deg, #6366f1, #a78bfa)" />
        <StatBox icon="✅" label="Active" value={active.length} color="#059669" gradient="linear-gradient(90deg, #10b981, #34d399)" />
        <StatBox icon="📄" label="Paper Mode" value={paper.length} color="#d97706" gradient="linear-gradient(90deg, #f59e0b, #fbbf24)" />
        <StatBox icon="⏹️" label="Stopped" value={stopped.length} color="#2563eb" gradient="linear-gradient(90deg, #3b82f6, #60a5fa)" />
        <StatBox icon="📊" label="Strategies" value={(signalStats?.total || 0) + (active.length + stopped.length)} color="#e11d48" gradient="linear-gradient(90deg, #f472b6, #fb7185)" />
      </div>

      {/* PnL + Signal Stats Row */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px', marginBottom: '24px' }}>
        <div className="card-crystal">
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px', paddingBottom: '16px', borderBottom: '2px solid rgba(148,163,184,0.08)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px', fontSize: '16px', fontWeight: 800, color: 'var(--text-primary)' }}>
              <div style={{ width: '32px', height: '32px', borderRadius: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '16px', background: 'linear-gradient(135deg, rgba(16,185,129,0.15), rgba(110,231,183,0.1))' }}>📈</div>
              P&L Overview
            </div>
            <Link to="/positions" style={{ fontSize: '10px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.8px', padding: '5px 12px', borderRadius: '8px', background: 'rgba(99,102,241,0.08)', color: '#6366f1', textDecoration: 'none' }}>Details</Link>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '12px' }}>
            <div style={{ textAlign: 'center', padding: '16px', borderRadius: '12px', background: 'linear-gradient(135deg, rgba(255,255,255,0.7), rgba(255,255,255,0.5))', border: '2px solid rgba(255,255,255,0.6)' }}>
              <div style={{ fontSize: '9px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.8px', color: '#94a3b8', marginBottom: '8px' }}>Unrealized P&L</div>
              <div style={{ fontSize: '24px', fontWeight: 900, color: totalUnrealizedPnl >= 0 ? '#059669' : '#dc2626' }}>
                <AnimatedCounter value={Math.abs(totalUnrealizedPnl)} prefix={totalUnrealizedPnl >= 0 ? '₹' : '-₹'} />
              </div>
            </div>
            <div style={{ textAlign: 'center', padding: '16px', borderRadius: '12px', background: 'linear-gradient(135deg, rgba(255,255,255,0.7), rgba(255,255,255,0.5))', border: '2px solid rgba(255,255,255,0.6)' }}>
              <div style={{ fontSize: '9px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.8px', color: '#94a3b8', marginBottom: '8px' }}>Realized P&L</div>
              <div style={{ fontSize: '24px', fontWeight: 900, color: totalRealizedPnl >= 0 ? '#059669' : '#dc2626' }}>
                <AnimatedCounter value={Math.abs(totalRealizedPnl)} prefix={totalRealizedPnl >= 0 ? '₹' : '-₹'} />
              </div>
            </div>
            <div style={{ textAlign: 'center', padding: '16px', borderRadius: '12px', background: 'linear-gradient(135deg, rgba(255,255,255,0.7), rgba(255,255,255,0.5))', border: '2px solid rgba(255,255,255,0.6)' }}>
              <div style={{ fontSize: '9px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.8px', color: '#94a3b8', marginBottom: '8px' }}>Total Positions</div>
              <div style={{ fontSize: '24px', fontWeight: 900, color: '#4f46e5' }}>
                {positions.length}
              </div>
            </div>
          </div>
        </div>

        {/* Signals Quick Stats */}
        <div className="card-crystal">
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px', paddingBottom: '16px', borderBottom: '2px solid rgba(148,163,184,0.08)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px', fontSize: '16px', fontWeight: 800, color: 'var(--text-primary)' }}>
              <div style={{ width: '32px', height: '32px', borderRadius: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '16px', background: 'linear-gradient(135deg, rgba(99,102,241,0.15), rgba(167,139,250,0.1))' }}>⚡</div>
              Signal Engine
            </div>
            <Link to="/signals" style={{ fontSize: '10px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.8px', padding: '5px 12px', borderRadius: '8px', background: 'rgba(99,102,241,0.08)', color: '#6366f1', textDecoration: 'none' }}>Signals</Link>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '12px' }}>
            <div style={{ textAlign: 'center', padding: '16px', borderRadius: '12px', background: 'linear-gradient(135deg, rgba(255,255,255,0.7), rgba(255,255,255,0.5))', border: '2px solid rgba(255,255,255,0.6)' }}>
              <div style={{ fontSize: '9px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.8px', color: '#94a3b8', marginBottom: '8px' }}>Today Signals</div>
              <div style={{ fontSize: '24px', fontWeight: 900, color: '#4f46e5' }}>{signalStats?.today || 0}</div>
            </div>
            <div style={{ textAlign: 'center', padding: '16px', borderRadius: '12px', background: 'linear-gradient(135deg, rgba(255,255,255,0.7), rgba(255,255,255,0.5))', border: '2px solid rgba(255,255,255,0.6)' }}>
              <div style={{ fontSize: '9px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.8px', color: '#94a3b8', marginBottom: '8px' }}>Executed</div>
              <div style={{ fontSize: '24px', fontWeight: 900, color: '#059669' }}>{signalStats?.executed || 0}</div>
            </div>
            <div style={{ textAlign: 'center', padding: '16px', borderRadius: '12px', background: 'linear-gradient(135deg, rgba(255,255,255,0.7), rgba(255,255,255,0.5))', border: '2px solid rgba(255,255,255,0.6)' }}>
              <div style={{ fontSize: '9px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.8px', color: '#94a3b8', marginBottom: '8px' }}>Win Rate</div>
              <div style={{ fontSize: '24px', fontWeight: 900, color: '#d97706' }}>{signalStats?.winRate ? `${signalStats.winRate.toFixed(0)}%` : '—'}</div>
            </div>
          </div>
        </div>
      </div>

      {/* Main Grid: Deployments + Equity Curve */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '24px', marginBottom: '24px' }}>
        {/* Active Deployments */}
        <div className="card-crystal">
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '20px', paddingBottom: '16px', borderBottom: '2px solid rgba(148,163,184,0.08)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px', fontSize: '16px', fontWeight: 800, color: 'var(--text-primary)' }}>
              <div style={{ width: '32px', height: '32px', borderRadius: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '16px', background: 'linear-gradient(135deg, rgba(99,102,241,0.15), rgba(167,139,250,0.1))' }}>🚀</div>
              Deployments ({deployments?.length || 0})
            </div>
            <Link to="/deployments" style={{ fontSize: '10px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.8px', padding: '5px 12px', borderRadius: '8px', background: 'rgba(99,102,241,0.08)', color: '#6366f1', textDecoration: 'none' }}>View All</Link>
          </div>
          {deployments && deployments.length > 0 ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              {deployments.slice(0, 5).map((d) => (
                <div key={d.id} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '12px 14px', borderRadius: '12px', background: 'linear-gradient(135deg, rgba(255,255,255,0.7), rgba(255,255,255,0.5))', border: '1px solid rgba(226,232,240,0.6)' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                    <div style={{ width: '8px', height: '8px', borderRadius: '50%', background: d.status === 'ACTIVE' ? '#10b981' : '#94a3b8' }} />
                    <div>
                      <div style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>{d.name || `Deploy #${d.id}`}</div>
                      <div style={{ fontSize: '11px', color: 'var(--text-muted)' }}>{d.symbols?.slice(0, 3).join(', ')} • {d.mode}</div>
                    </div>
                  </div>
                  <span style={{ fontSize: '10px', fontWeight: 700, padding: '3px 8px', borderRadius: '6px', background: d.status === 'ACTIVE' ? 'rgba(16,185,129,0.12)' : 'rgba(148,163,184,0.12)', color: d.status === 'ACTIVE' ? '#059669' : '#64748b' }}>
                    {d.status}
                  </span>
                </div>
              ))}
            </div>
          ) : (
            <div style={{ textAlign: 'center', padding: '32px 0', color: '#94a3b8', fontSize: '13px' }}>
              No deployments created yet.
            </div>
          )}
        </div>

        {/* Equity Curve Chart */}
        <EquityCurveCard />
      </div>

      {/* Quick Actions Grid */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '16px' }}>
        <QuickAction to="/smart-strategies" icon="🎯" title="Smart Strategies" desc="Options spreads & butterflies" color="linear-gradient(135deg, #6366f1, #a78bfa)" />
        <QuickAction to="/option-arbitrage" icon="⚡" title="Option Arbitrage" desc="Bid parity & conversion" color="linear-gradient(135deg, #10b981, #34d399)" />
        <QuickAction to="/trader-dashboard" icon="📈" title="Trader Engine" desc="Multi-strategy deployments" color="linear-gradient(135deg, #f59e0b, #fbbf24)" />
        <QuickAction to="/strategy-builder" icon="🛠️" title="Strategy Builder" desc="Custom rule-based strategies" color="linear-gradient(135deg, #ec4899, #f472b6)" />
      </div>
    </div>
  );
}

function StatBox({ icon, label, value, color, gradient }) {
  return (
    <div className="stat-box-aurora">
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '12px' }}>
        <div style={{ width: '38px', height: '38px', borderRadius: '12px', background: gradient, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '18px', color: '#fff', boxShadow: '0 4px 12px rgba(0,0,0,0.1)' }}>{icon}</div>
      </div>
      <div style={{ fontSize: '10px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.8px', color: '#94a3b8', marginBottom: '4px' }}>{label}</div>
      <div style={{ fontSize: '26px', fontWeight: 900, color: 'var(--text-primary)' }}>{value}</div>
    </div>
  );
}

function EquityCurveCard() {
  const [days, setDays] = useState(30);
  const { data: history, isLoading } = useQuery({
    queryKey: ['pnl-history', days],
    queryFn: () => client.get('/signals/pnl-history', { params: { days } }).then(r => r.data),
    staleTime: 60000,
    refetchInterval: 120000,
  });

  const points = history || [];
  const totalPnl = points.length > 0 ? points[points.length - 1].cumulative : 0;
  const isPositive = totalPnl >= 0;

  function buildPath(pts, W, H, pad) {
    if (pts.length < 2) return null;
    const values = pts.map(p => p.cumulative);
    const minV = Math.min(0, ...values);
    const maxV = Math.max(0, ...values);
    const range = maxV - minV || 1;
    const scaleY = v => H - pad - ((v - minV) / range) * (H - pad * 2);
    const scaleX = (i) => pad + (i / (pts.length - 1)) * (W - pad * 2);
    const coords = pts.map((p, i) => `${scaleX(i).toFixed(1)},${scaleY(p.cumulative).toFixed(1)}`);
    const zeroY = scaleY(0).toFixed(1);
    return { line: `M${coords.join(' L')}`, fill: `M${coords.join(' L')} L${scaleX(pts.length - 1).toFixed(1)},${zeroY} L${pad},${zeroY}Z`, zeroY };
  }

  const W = 700, H = 280, pad = 24;
  const pathData = points.length >= 2 ? buildPath(points, W, H, pad) : null;

  return (
    <div className="card-crystal">
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px', paddingBottom: '16px', borderBottom: '2px solid rgba(148,163,184,0.08)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <div style={{ width: '32px', height: '32px', borderRadius: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '16px', background: 'linear-gradient(135deg, rgba(99,102,241,0.15), rgba(167,139,250,0.1))' }}>📈</div>
          <div>
            <div style={{ fontSize: '16px', fontWeight: 800, color: 'var(--text-primary)' }}>Equity Curve</div>
            {points.length > 0 && (
              <div style={{ fontSize: '12px', fontWeight: 700, color: isPositive ? '#059669' : '#dc2626' }}>
                {isPositive ? '+' : ''}₹{totalPnl.toLocaleString('en-IN', { maximumFractionDigits: 0 })} cumulative
              </div>
            )}
          </div>
        </div>
        <div style={{ display: 'flex', gap: '6px' }}>
          {[7, 30, 90].map(d => (
            <button key={d} onClick={() => setDays(d)} style={{ fontSize: '10px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.8px', padding: '5px 10px', borderRadius: '8px', border: 'none', background: days === d ? 'rgba(99,102,241,0.15)' : 'rgba(99,102,241,0.06)', color: '#6366f1', cursor: 'pointer' }}>
              {d}D
            </button>
          ))}
        </div>
      </div>
      <div style={{ width: '100%', height: '220px', borderRadius: '16px', background: 'linear-gradient(180deg, rgba(99,102,241,0.03), rgba(139,92,246,0.02))', position: 'relative', overflow: 'hidden' }}>
        {isLoading ? (
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%', color: '#94a3b8', fontSize: '13px' }}>Loading...</div>
        ) : points.length < 2 ? (
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: '100%', color: '#94a3b8', fontSize: '13px', gap: '8px' }}>
            <div style={{ fontSize: '28px' }}>📊</div>
            No closed trades in last {days} days
          </div>
        ) : (
          <svg style={{ width: '100%', height: '100%' }} viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none">
            <defs>
              <linearGradient id="eqGrad" x1="0" y1="0" x2="1" y2="0">
                <stop offset="0%" stopColor={isPositive ? '#6366f1' : '#ef4444'}/>
                <stop offset="100%" stopColor={isPositive ? '#60a5fa' : '#f87171'}/>
              </linearGradient>
              <linearGradient id="eqFill" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor={isPositive ? 'rgba(99,102,241,0.25)' : 'rgba(239,68,68,0.2)'}/>
                <stop offset="100%" stopColor="rgba(99,102,241,0)"/>
              </linearGradient>
            </defs>
            <line x1={pad} y1={pathData.zeroY} x2={W - pad} y2={pathData.zeroY} stroke="rgba(148,163,184,0.2)" strokeWidth="1" strokeDasharray="4 3" />
            <g stroke="rgba(99,102,241,0.05)" strokeWidth="1" strokeDasharray="6 4">
              <line x1="0" y1={H * 0.25} x2={W} y2={H * 0.25}/>
              <line x1="0" y1={H * 0.5} x2={W} y2={H * 0.5}/>
              <line x1="0" y1={H * 0.75} x2={W} y2={H * 0.75}/>
            </g>
            {pathData && <>
              <path d={pathData.fill} fill="url(#eqFill)" />
              <path d={pathData.line} fill="none" stroke="url(#eqGrad)" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
            </>}
            {points.filter((_, i) => i === 0 || i === points.length - 1 || i === Math.floor(points.length / 2)).map((p, idx, arr) => {
              const x = pad + (points.indexOf(p) / (points.length - 1)) * (W - pad * 2);
              return (
                <text key={idx} x={x} y={H - 4} textAnchor="middle" fontSize="18" fill="#94a3b8" style={{ fontSize: '18px' }}>
                  {new Date(p.date).toLocaleDateString('en-IN', { day: '2-digit', month: 'short' })}
                </text>
              );
            })}
          </svg>
        )}
      </div>
      {points.length > 0 && (
        <div style={{ marginTop: '16px', display: 'flex', gap: '6px', overflowX: 'auto', paddingBottom: '4px' }}>
          {points.slice(-7).map((p) => (
            <div key={p.date} style={{ flexShrink: 0, textAlign: 'center', padding: '8px 10px', borderRadius: '10px', background: p.daily >= 0 ? 'rgba(16,185,129,0.08)' : 'rgba(239,68,68,0.07)', border: '1px solid', borderColor: p.daily >= 0 ? 'rgba(16,185,129,0.15)' : 'rgba(239,68,68,0.12)', minWidth: '64px' }}>
              <div style={{ fontSize: '9px', color: '#94a3b8', fontWeight: 600, marginBottom: '3px' }}>
                {new Date(p.date).toLocaleDateString('en-IN', { day: '2-digit', month: 'short' })}
              </div>
              <div style={{ fontSize: '12px', fontWeight: 800, color: p.daily >= 0 ? '#059669' : '#dc2626' }}>
                {p.daily >= 0 ? '+' : ''}₹{Math.abs(p.daily).toFixed(0)}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function QuickAction({ to, icon, title, desc, color }) {
  return (
    <Link to={to} style={{ display: 'flex', alignItems: 'center', gap: '12px', padding: '12px 14px', borderRadius: '12px', background: 'linear-gradient(135deg, rgba(255,255,255,0.7), rgba(255,255,255,0.5))', border: '2px solid rgba(255,255,255,0.6)', transition: 'all 0.3s', cursor: 'pointer', textDecoration: 'none' }}
      onMouseEnter={(e) => { e.currentTarget.style.background = 'linear-gradient(135deg, rgba(255,255,255,0.85), rgba(255,255,255,0.75))'; e.currentTarget.style.borderColor = 'rgba(99,102,241,0.2)'; e.currentTarget.style.transform = 'translateX(4px)'; }}
      onMouseLeave={(e) => { e.currentTarget.style.background = 'linear-gradient(135deg, rgba(255,255,255,0.7), rgba(255,255,255,0.5))'; e.currentTarget.style.borderColor = 'rgba(255,255,255,0.6)'; e.currentTarget.style.transform = 'translateX(0)'; }}
    >
      <div style={{ width: '36px', height: '36px', borderRadius: '10px', background: color, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '16px', flexShrink: 0 }}>{icon}</div>
      <div>
        <div style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>{title}</div>
        <div style={{ fontSize: '11px', color: 'var(--text-muted)' }}>{desc}</div>
      </div>
    </Link>
  );
}
