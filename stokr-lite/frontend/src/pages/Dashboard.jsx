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

  return (
    <div>
      {/* Header */}
      <div className="header" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '32px', paddingBottom: '24px', borderBottom: '2px solid rgba(148,163,184,0.08)' }}>
        <div>
          <h1 style={{ fontSize: '32px', fontWeight: 900, background: 'linear-gradient(135deg, #0f172a 0%, #4f46e5 100%)', WebkitBackgroundClip: 'text', WebkitTextFillColor: 'transparent', letterSpacing: '-1px', marginBottom: '6px' }}>Trading Dashboard</h1>
          <div style={{ fontSize: '13px', color: 'var(--text-muted)', display: 'flex', alignItems: 'center', gap: '12px' }}>
            <div className="live-indicator" style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', padding: '4px 10px', background: 'linear-gradient(135deg, rgba(16,185,129,0.15), rgba(52,211,153,0.1))', borderRadius: '8px', color: '#059669', fontWeight: 600, fontSize: '11px' }}>
              <div className="animate-pulse-dot" style={{ width: '6px', height: '6px', background: '#10b981', borderRadius: '50%' }} />
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
      <div className="ticker-container-aurora" style={{ marginBottom: '28px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px', fontSize: '11px', fontWeight: 750, textTransform: 'uppercase', letterSpacing: '1.2px', color: 'var(--text-muted)', marginBottom: '12px' }}>
          <div className="animate-pulse-dot" style={{ width: '6px', height: '6px', background: '#10b981', borderRadius: '50%' }} />
          Real-time Market
        </div>
        <div style={{ display: 'flex', gap: '32px', animation: 'scroll-infinite 35s linear infinite', padding: '4px 0' }}>
          {['NIFTY50|23,847|↑1.24%','RELIANCE|2,847|↑2.18%','TCS|3,612|↓0.45%','HDFCBANK|1,687|↑0.92%','INFY|1,524|↑1.67%','ICICIBANK|1,198|↓0.31%','SBIN|842|↑1.85%'].map((item, i) => {
            const [sym, price, change] = item.split('|');
            const isUp = change.startsWith('↑');
            return (
              <div key={i} style={{ display: 'flex', alignItems: 'center', gap: '12px', whiteSpace: 'nowrap', padding: '6px 14px', borderRadius: '10px', background: 'rgba(99,102,241,0.04)' }}>
                <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>{sym}</span>
                <span style={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '12px', fontWeight: 600, color: '#64748b' }}>{price}</span>
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', fontSize: '11px', fontWeight: 700, padding: '3px 8px', borderRadius: '6px', background: isUp ? 'rgba(16,185,129,0.12)' : 'rgba(239,68,68,0.1)', color: isUp ? '#059669' : '#dc2626' }}>{change}</span>
              </div>
            );
          })}
          {['NIFTY50|23,847|↑1.24%','RELIANCE|2,847|↑2.18%','TCS|3,612|↓0.45%','HDFCBANK|1,687|↑0.92%','INFY|1,524|↑1.67%','ICICIBANK|1,198|↓0.31%','SBIN|842|↑1.85%'].map((item, i) => {
            const [sym, price, change] = item.split('|');
            const isUp = change.startsWith('↑');
            return (
              <div key={`dup-${i}`} style={{ display: 'flex', alignItems: 'center', gap: '12px', whiteSpace: 'nowrap', padding: '6px 14px', borderRadius: '10px', background: 'rgba(99,102,241,0.04)' }}>
                <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>{sym}</span>
                <span style={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '12px', fontWeight: 600, color: '#64748b' }}>{price}</span>
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', fontSize: '11px', fontWeight: 700, padding: '3px 8px', borderRadius: '6px', background: isUp ? 'rgba(16,185,129,0.12)' : 'rgba(239,68,68,0.1)', color: isUp ? '#059669' : '#dc2626' }}>{change}</span>
              </div>
            );
          })}
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
              <div style={{ fontSize: '9px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.8px', color: '#94a3b8', marginBottom: '8px' }}>Open Positions</div>
              <div style={{ fontSize: '24px', fontWeight: 900, color: '#4f46e5' }}>{positions.length}</div>
            </div>
          </div>
        </div>

        <div className="card-crystal">
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px', paddingBottom: '16px', borderBottom: '2px solid rgba(148,163,184,0.08)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px', fontSize: '16px', fontWeight: 800, color: 'var(--text-primary)' }}>
              <div style={{ width: '32px', height: '32px', borderRadius: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '16px', background: 'linear-gradient(135deg, rgba(245,158,11,0.15), rgba(251,191,36,0.1))' }}>🎯</div>
              Signal Stats
            </div>
            <Link to="/signals" style={{ fontSize: '10px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.8px', padding: '5px 12px', borderRadius: '8px', background: 'rgba(99,102,241,0.08)', color: '#6366f1', textDecoration: 'none' }}>View All</Link>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '12px' }}>
            <MiniStat label="Today" value={signalStats?.today || 0} color="#f59e0b" />
            <MiniStat label="Active" value={signalStats?.active || 0} color="#10b981" />
            <MiniStat label="Total" value={signalStats?.total || 0} color="#3b82f6" />
          </div>
          {positions.length > 0 && (
            <div style={{ marginTop: '16px', display: 'flex', flexDirection: 'column', gap: '8px' }}>
              <div style={{ fontSize: '11px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.6px', color: '#94a3b8', marginBottom: '4px' }}>Open Positions</div>
              {positions.slice(0, 4).map((p) => (
                <div key={p.id} style={{ display: 'flex', alignItems: 'center', gap: '10px', padding: '8px 10px', borderRadius: '10px', background: 'rgba(255,255,255,0.6)', border: '1px solid rgba(148,163,184,0.1)' }}>
                  <div style={{ width: '6px', height: '6px', borderRadius: '50%', flexShrink: 0, background: p.side === 'BUY' ? '#10b981' : '#ef4444' }} />
                  <span style={{ fontSize: '12px', fontWeight: 700, color: '#0f172a', flex: 1 }}>{p.symbol}</span>
                  <span style={{ fontSize: '11px', fontWeight: 600, color: '#64748b' }}>{p.quantity} @ {parseFloat(p.entryPrice).toFixed(1)}</span>
                  <span style={{ fontSize: '11px', fontWeight: 700, color: parseFloat(p.unrealizedPnl) >= 0 ? '#059669' : '#dc2626' }}>
                    {parseFloat(p.unrealizedPnl) >= 0 ? '+' : ''}₹{parseFloat(p.unrealizedPnl || 0).toFixed(0)}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Content Grid: Chart + Active Deployments */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px', marginBottom: '24px' }}>
        {/* Portfolio Equity Curve */}
        <PnlChart />


        {/* Active Deployments */}
        <div className="card-crystal">
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px', paddingBottom: '16px', borderBottom: '2px solid rgba(148,163,184,0.08)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px', fontSize: '16px', fontWeight: 800, color: 'var(--text-primary)' }}>
              <div style={{ width: '32px', height: '32px', borderRadius: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '16px', background: 'linear-gradient(135deg, rgba(16,185,129,0.15), rgba(110,231,183,0.1))' }}>⚡</div>
              Active Deployments
            </div>
            <Link to="/deployments" style={{ fontSize: '10px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.8px', padding: '5px 12px', borderRadius: '8px', background: 'rgba(99,102,241,0.08)', color: '#6366f1', textDecoration: 'none' }}>View All</Link>
          </div>
          {active.length === 0 ? (
            <div style={{ textAlign: 'center', padding: '40px 0' }}>
              <div style={{ fontSize: '40px', marginBottom: '12px' }}>📭</div>
              <p style={{ color: '#94a3b8', fontSize: '14px' }}>No active deployments</p>
              <Link to="/deployments" style={{ color: '#6366f1', fontSize: '13px', fontWeight: 600, marginTop: '8px', display: 'inline-block', textDecoration: 'none' }}>Deploy a strategy →</Link>
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
              {active.slice(0, 5).map((d, i) => (
                <div key={d.id} className="signal-row-aurora" style={{ animationDelay: `${i * 100}ms` }}>
                  <div style={{ width: '36px', height: '36px', borderRadius: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '16px', fontWeight: 800, flexShrink: 0, background: 'rgba(16,185,129,0.15)', color: '#059669' }}>▲</div>
                  <div style={{ flex: 1 }}>
                    <div style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>{d.strategyName || `Strategy #${d.strategyId}`}</div>
                    <div style={{ fontSize: '11px', color: 'var(--text-muted)' }}>{new Date(d.createdAt).toLocaleDateString()}</div>
                  </div>
                  <div style={{ textAlign: 'right' }}>
                    <div style={{ fontFamily: "'JetBrains Mono', monospace", fontSize: '13px', fontWeight: 700, color: '#4f46e5' }}>₹{d.capital?.toLocaleString() || 0}</div>
                    <div style={{ fontSize: '10px', color: 'var(--text-muted)' }}>{d.mode}</div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Strategies Section */}
      <div className="card-crystal" style={{ marginBottom: '24px' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px', paddingBottom: '16px', borderBottom: '2px solid rgba(148,163,184,0.08)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', fontSize: '16px', fontWeight: 800, color: 'var(--text-primary)' }}>
            <div style={{ width: '32px', height: '32px', borderRadius: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '16px', background: 'linear-gradient(135deg, rgba(139,92,246,0.15), rgba(167,139,250,0.1))' }}>🎯</div>
            Active Strategies
          </div>
          <div style={{ display: 'flex', gap: '8px' }}>
            <span style={{ fontSize: '10px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.8px', padding: '5px 12px', borderRadius: '8px', background: 'rgba(16,185,129,0.12)', color: '#059669' }}>{active.length} Live</span>
            <span style={{ fontSize: '10px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.8px', padding: '5px 12px', borderRadius: '8px', background: 'rgba(245,158,11,0.12)', color: '#d97706' }}>{paper.length} Paper</span>
          </div>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
          {active.slice(0, 3).map((d, i) => (
            <StrategyCard key={d.id} name={d.strategyName || `Strategy #${d.strategyId}`} desc={`${d.mode} mode • Capital: ₹${d.capital?.toLocaleString() || 0}`} mode={d.mode} delay={i} />
          ))}
          {active.length === 0 && (
            <div style={{ textAlign: 'center', padding: '20px', color: '#94a3b8', fontSize: '14px' }}>
              No active strategies. <Link to="/deployments" style={{ color: '#6366f1', textDecoration: 'none' }}>Deploy one now →</Link>
            </div>
          )}
        </div>
      </div>

      {/* Bottom Grid: Market Status + Recent Orders + Activity */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '20px', marginBottom: '24px' }}>
        {/* Market Status */}
        <div className="card-crystal">
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px', paddingBottom: '16px', borderBottom: '2px solid rgba(148,163,184,0.08)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px', fontSize: '16px', fontWeight: 800, color: 'var(--text-primary)' }}>
              <div style={{ width: '32px', height: '32px', borderRadius: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '16px', background: 'linear-gradient(135deg, rgba(245,158,11,0.15), rgba(251,191,36,0.1))' }}>🏛️</div>
              Market Status
            </div>
            <span style={{ fontSize: '10px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.8px', padding: '5px 12px', borderRadius: '8px', background: 'rgba(99,102,241,0.08)', color: '#6366f1' }}>NSE</span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px', padding: '16px', borderRadius: '14px', background: 'linear-gradient(135deg, rgba(255,255,255,0.7), rgba(255,255,255,0.5))', border: '2px solid rgba(255,255,255,0.6)' }}>
            <div style={{ width: '12px', height: '12px', borderRadius: '50%', background: marketStatus?.isOpen ? '#10b981' : '#ef4444', animation: marketStatus?.isOpen ? 'pulse-dot 1.5s ease-in-out infinite' : 'none' }} />
            <div>
              <div style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)' }}>{marketStatus?.isOpen ? 'Market is Open' : 'Market is Closed'}</div>
              <div style={{ fontSize: '11px', color: 'var(--text-muted)', marginTop: '2px' }}>NSE/BSE • 09:15 - 15:30 IST</div>
            </div>
          </div>
          <div style={{ marginTop: '16px', display: 'flex', flexDirection: 'column', gap: '10px' }}>
            <InfoRow label="Session" value="09:15 - 15:30 IST" />
            <InfoRow label="EOD Square-off" value="15:15 IST" />
            <InfoRow label="Broker Status" value={brokerHealth?.ok ? '✅ Connected' : '⚠️ Disconnected'} />
          </div>
        </div>

        {/* Quick Actions */}
        <div className="card-crystal">
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px', paddingBottom: '16px', borderBottom: '2px solid rgba(148,163,184,0.08)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px', fontSize: '16px', fontWeight: 800, color: 'var(--text-primary)' }}>
              <div style={{ width: '32px', height: '32px', borderRadius: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '16px', background: 'linear-gradient(135deg, rgba(56,189,248,0.15), rgba(125,211,252,0.1))' }}>🚀</div>
              Quick Actions
            </div>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
            <QuickAction to="/deployments" icon="⚡" title="New Deployment" desc="Deploy a trading strategy" color="linear-gradient(135deg, #6366f1, #8b5cf6)" />
            <QuickAction to="/brokers" icon="🏦" title="Connect Broker" desc="Link your trading account" color="linear-gradient(135deg, #10b981, #34d399)" />
            <QuickAction to="/strategies" icon="🎯" title="View Strategies" desc="Browse available strategies" color="linear-gradient(135deg, #f59e0b, #fbbf24)" />
          </div>
        </div>

        {/* Recent Orders */}
        <div className="card-crystal">
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px', paddingBottom: '16px', borderBottom: '2px solid rgba(148,163,184,0.08)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px', fontSize: '16px', fontWeight: 800, color: 'var(--text-primary)' }}>
              <div style={{ width: '32px', height: '32px', borderRadius: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '16px', background: 'linear-gradient(135deg, rgba(99,102,241,0.15), rgba(167,139,250,0.1))' }}>🕐</div>
              Recent Orders
            </div>
            <Link to="/orders" style={{ fontSize: '10px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.8px', padding: '5px 12px', borderRadius: '8px', background: 'rgba(99,102,241,0.08)', color: '#6366f1', textDecoration: 'none' }}>View All</Link>
          </div>
          {orderList.length === 0 ? (
            <div style={{ textAlign: 'center', padding: '20px', color: '#94a3b8', fontSize: '13px' }}>
              <div style={{ fontSize: '24px', marginBottom: '6px' }}>📭</div>
              No orders yet
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '2px' }}>
              {orderList.slice(0, 5).map((o) => (
                <div key={o.id} style={{ display: 'flex', alignItems: 'center', gap: '10px', padding: '8px 6px', borderBottom: '1px solid rgba(148,163,184,0.06)' }}>
                  <div style={{ width: '28px', height: '28px', borderRadius: '8px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '12px', flexShrink: 0, background: o.side === 'BUY' ? 'linear-gradient(135deg, rgba(16,185,129,0.2), rgba(110,231,183,0.15))' : 'linear-gradient(135deg, rgba(239,68,68,0.2), rgba(248,113,113,0.15))' }}>
                    {o.side === 'BUY' ? '▲' : '▼'}
                  </div>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-primary)', display: 'flex', alignItems: 'center', gap: '6px' }}>
                      {o.symbol}
                      <span style={{ fontSize: '10px', fontWeight: 600, color: getStatusColor(o.status) }}>{o.status}</span>
                    </div>
                    <div style={{ fontSize: '10px', color: 'var(--text-muted)' }}>{o.quantity} × ₹{parseFloat(o.price || 0).toFixed(1)} · {new Date(o.createdAt).toLocaleTimeString()}</div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function MiniStat({ label, value, color }) {
  return (
    <div style={{ textAlign: 'center', padding: '14px', borderRadius: '12px', background: 'linear-gradient(135deg, rgba(255,255,255,0.7), rgba(255,255,255,0.5))', border: '2px solid rgba(255,255,255,0.6)' }}>
      <div style={{ fontSize: '24px', fontWeight: 900, color }}>{value ?? '—'}</div>
      <div style={{ fontSize: '9px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.7px', color: '#94a3b8', marginTop: '4px' }}>{label}</div>
    </div>
  );
}

function getStatusColor(status) {
  switch (status) {
    case 'COMPLETE': return '#10b981';
    case 'REJECTED': return '#ef4444';
    case 'PENDING':
    case 'OPEN': return '#f59e0b';
    case 'CANCELLED': return '#64748b';
    default: return '#64748b';
  }
}

function StatBox({ icon, label, value, color, gradient }) {
  return (
    <div className="stat-box-aurora">
      <div style={{ position: 'absolute', top: 0, left: 0, right: 0, height: '4px', borderRadius: '18px 18px 0 0', background: gradient }} />
      <div style={{ width: '50px', height: '50px', borderRadius: '14px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '24px', marginBottom: '14px', background: `linear-gradient(135deg, ${color}15, ${color}0A)` }}>
        {icon}
      </div>
      <div style={{ fontSize: '10px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '1px', color: 'var(--text-muted)', marginBottom: '8px' }}>{label}</div>
      <div style={{ fontSize: '32px', fontWeight: 900, letterSpacing: '-1px', lineHeight: 1, marginBottom: '10px', color }}>
        <AnimatedCounter value={value} />
      </div>
    </div>
  );
}

function StrategyCard({ name, desc, mode, delay }) {
  return (
    <div className="strategy-card-aurora" style={{ animationDelay: `${delay * 100}ms` }}>
      <div style={{ width: '48px', height: '48px', borderRadius: '12px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '22px', flexShrink: 0, background: mode === 'LIVE' ? 'linear-gradient(135deg, rgba(99,102,241,0.2), rgba(129,140,248,0.15))' : 'linear-gradient(135deg, rgba(245,158,11,0.2), rgba(251,191,36,0.15))' }}>
        {mode === 'LIVE' ? '🌅' : '📄'}
      </div>
      <div style={{ flex: 1 }}>
        <div style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-primary)', marginBottom: '2px' }}>{name}</div>
        <div style={{ fontSize: '11px', color: 'var(--text-muted)' }}>{desc}</div>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
        <span style={{ fontSize: '9px', fontWeight: 800, textTransform: 'uppercase', letterSpacing: '0.6px', padding: '4px 10px', borderRadius: '8px', background: mode === 'LIVE' ? 'rgba(16,185,129,0.12)' : 'rgba(245,158,11,0.12)', color: mode === 'LIVE' ? '#059669' : '#d97706' }}>{mode}</span>
      </div>
    </div>
  );
}

function PnlChart() {
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

  // Build SVG path from cumulative values
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
            {/* Zero line */}
            {pathData && <line x1={pad} y1={pathData.zeroY} x2={W - pad} y2={pathData.zeroY} stroke="rgba(148,163,184,0.2)" strokeWidth="1" strokeDasharray="4 3" />}
            {/* Grid */}
            <g stroke="rgba(99,102,241,0.05)" strokeWidth="1" strokeDasharray="6 4">
              <line x1="0" y1={H * 0.25} x2={W} y2={H * 0.25}/>
              <line x1="0" y1={H * 0.5} x2={W} y2={H * 0.5}/>
              <line x1="0" y1={H * 0.75} x2={W} y2={H * 0.75}/>
            </g>
            {pathData && <>
              <path d={pathData.fill} fill="url(#eqFill)" />
              <path d={pathData.line} fill="none" stroke="url(#eqGrad)" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
            </>}
            {/* Day labels */}
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
      {/* Daily breakdown mini list */}
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

function InfoRow({ label, value }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '13px' }}>
      <span style={{ color: '#94a3b8' }}>{label}</span>
      <span style={{ color: '#0f172a', fontWeight: 600 }}>{value}</span>
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
