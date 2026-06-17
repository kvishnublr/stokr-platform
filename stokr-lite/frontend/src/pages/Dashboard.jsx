import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import client from '../api/client';
import { useEffect, useState, useRef } from 'react';

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
  });

  const { data: marketStatus } = useQuery({
    queryKey: ['market-status'],
    queryFn: () => client.get('/market/status').then((r) => r.data),
  });

  const active = deployments?.filter((d) => d.status === 'ACTIVE') || [];
  const stopped = deployments?.filter((d) => d.status !== 'ACTIVE') || [];
  const paper = deployments?.filter((d) => d.mode === 'PAPER') || [];

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
            <span>Nifty 50: 23,847.20 <span style={{ color: '#059669' }}>↑1.24%</span></span>
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
          {/* Duplicate for seamless loop */}
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
        <StatBox icon="📊" label="Strategies" value={active.length + stopped.length} color="#e11d48" gradient="linear-gradient(90deg, #f472b6, #fb7185)" />
      </div>

      {/* Content Grid: Chart + Active Deployments */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px', marginBottom: '24px' }}>
        {/* Portfolio Chart */}
        <div className="card-crystal">
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px', paddingBottom: '16px', borderBottom: '2px solid rgba(148,163,184,0.08)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px', fontSize: '16px', fontWeight: 800, color: 'var(--text-primary)' }}>
              <div style={{ width: '32px', height: '32px', borderRadius: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '16px', background: 'linear-gradient(135deg, rgba(99,102,241,0.15), rgba(167,139,250,0.1))' }}>📈</div>
              Portfolio Performance
            </div>
            <div style={{ display: 'flex', gap: '8px' }}>
              <span style={{ fontSize: '10px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.8px', padding: '5px 12px', borderRadius: '8px', background: 'rgba(99,102,241,0.08)', color: '#6366f1' }}>1D</span>
              <span style={{ fontSize: '10px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.8px', padding: '5px 12px', borderRadius: '8px', background: 'rgba(99,102,241,0.15)', color: '#6366f1' }}>1W</span>
              <span style={{ fontSize: '10px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.8px', padding: '5px 12px', borderRadius: '8px', background: 'rgba(99,102,241,0.08)', color: '#6366f1' }}>1M</span>
            </div>
          </div>
          <div style={{ width: '100%', height: '280px', borderRadius: '16px', background: 'linear-gradient(180deg, rgba(99,102,241,0.03), rgba(139,92,246,0.02))', position: 'relative', overflow: 'hidden' }}>
            <svg style={{ width: '100%', height: '100%' }} viewBox="0 0 700 280" preserveAspectRatio="none">
              <defs>
                <linearGradient id="chartGrad" x1="0" y1="0" x2="1" y2="0">
                  <stop offset="0%" stopColor="#6366f1"/>
                  <stop offset="50%" stopColor="#a78bfa"/>
                  <stop offset="100%" stopColor="#60a5fa"/>
                </linearGradient>
                <linearGradient id="chartFill" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="rgba(99,102,241,0.2)"/>
                  <stop offset="100%" stopColor="rgba(99,102,241,0)"/>
                </linearGradient>
              </defs>
              <g style={{ stroke: 'rgba(99,102,241,0.05)', strokeWidth: 1, strokeDasharray: '6 4' }}>
                <line x1="0" y1="70" x2="700" y2="70"/>
                <line x1="0" y1="140" x2="700" y2="140"/>
                <line x1="0" y1="210" x2="700" y2="210"/>
              </g>
              <path className="animate-fill-fade" d="M0,220 C80,210 120,190 160,185 C200,180 240,195 280,175 C320,155 360,160 400,140 C440,120 480,130 520,110 C560,90 600,95 640,75 C680,55 700,60 700,40 L700,280 L0,280Z" fill="url(#chartFill)" opacity="0"/>
              <path className="animate-path-draw" d="M0,220 C80,210 120,190 160,185 C200,180 240,195 280,175 C320,155 360,160 400,140 C440,120 480,130 520,110 C560,90 600,95 640,75 C680,55 700,60 700,40" fill="none" stroke="url(#chartGrad)" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" style={{ strokeDasharray: 1000, strokeDashoffset: 1000 }} />
            </svg>
          </div>
        </div>

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

      {/* Bottom Grid: Market Status + Quick Links + Timeline */}
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
            <InfoRow label="Scan Interval" value="60 seconds" />
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

        {/* Activity Timeline */}
        <div className="card-crystal">
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px', paddingBottom: '16px', borderBottom: '2px solid rgba(148,163,184,0.08)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px', fontSize: '16px', fontWeight: 800, color: 'var(--text-primary)' }}>
              <div style={{ width: '32px', height: '32px', borderRadius: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '16px', background: 'linear-gradient(135deg, rgba(99,102,241,0.15), rgba(167,139,250,0.1))' }}>🕐</div>
              Recent Activity
            </div>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '2px' }}>
            <TimelineEvent icon="⚡" iconBg="linear-gradient(135deg, rgba(99,102,241,0.2), rgba(167,139,250,0.15))" text={<>New <strong>BUY</strong> signal for RELIANCE</>} time="10:41 AM" />
            <TimelineEvent icon="✅" iconBg="linear-gradient(135deg, rgba(16,185,129,0.2), rgba(110,231,183,0.15))" text={<>Order filled: HDFCBANK x3</>} time="10:28 AM" />
            <TimelineEvent icon="🔗" iconBg="linear-gradient(135deg, rgba(56,189,248,0.2), rgba(125,211,252,0.15))" text={<>Zerodha connected successfully</>} time="09:15 AM" />
            <TimelineEvent icon="🚀" iconBg="linear-gradient(135deg, rgba(245,158,11,0.2), rgba(251,191,36,0.15))" text={<>Market session started</>} time="09:00 AM" />
          </div>
        </div>
      </div>
    </div>
  );
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

function TimelineEvent({ icon, iconBg, text, time }) {
  return (
    <div className="timeline-event-aurora">
      <div style={{ width: '36px', height: '36px', borderRadius: '12px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '16px', flexShrink: 0, position: 'relative', zIndex: 1, background: iconBg }}>
        {icon}
      </div>
      <div>
        <div style={{ fontSize: '13px', fontWeight: 500, color: 'var(--text-primary)', lineHeight: 1.5, paddingTop: '4px' }}>{text}</div>
        <div style={{ fontSize: '10px', color: 'var(--text-muted)', marginTop: '3px' }}>{time}</div>
      </div>
    </div>
  );
}
