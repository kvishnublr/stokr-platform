import { useQuery } from '@tanstack/react-query';
import client from '../../api/client';
import { useEffect, useState, useRef } from 'react';
import { Link } from 'react-router-dom';

function AnimatedCounter({ value, duration = 1200 }) {
  const [display, setDisplay] = useState(0);
  const startTime = useRef(null);
  const startVal = useRef(0);

  useEffect(() => {
    startVal.current = display;
    startTime.current = null;
    let raf;
    const animate = (timestamp) => {
      if (!startTime.current) startTime.current = timestamp;
      const progress = Math.min((timestamp - startTime.current) / duration, 1);
      const eased = 1 - Math.pow(1 - progress, 3);
      setDisplay(Math.floor(startVal.current + (value - startVal.current) * eased));
      if (progress < 1) raf = requestAnimationFrame(animate);
    };
    raf = requestAnimationFrame(animate);
    return () => cancelAnimationFrame(raf);
  }, [value, duration]);

  return <span>{display.toLocaleString()}</span>;
}

export default function AdminDashboard() {
  const { data: dashboard } = useQuery({ queryKey: ['admin-dashboard'], queryFn: () => client.get('/admin/dashboard').then((r) => r.data) });
  const { data: killSwitch } = useQuery({ queryKey: ['kill-switch'], queryFn: () => client.get('/admin/kill-switch').then((r) => r.data) });

  return (
    <div>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '32px', paddingBottom: '24px', borderBottom: '2px solid rgba(148,163,184,0.08)' }}>
        <div>
          <h1 style={{ fontSize: '32px', fontWeight: 900, background: 'linear-gradient(135deg, #0f172a 0%, #4f46e5 100%)', WebkitBackgroundClip: 'text', WebkitTextFillColor: 'transparent', letterSpacing: '-1px', marginBottom: '6px' }}>Admin Overview</h1>
          <p style={{ fontSize: '13px', color: 'var(--text-muted)' }}>Platform monitoring and controls</p>
        </div>
        <div className="live-indicator" style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', padding: '4px 10px', background: 'linear-gradient(135deg, rgba(16,185,129,0.15), rgba(52,211,153,0.1))', borderRadius: '8px', color: '#059669', fontWeight: 600, fontSize: '11px' }}>
          <div className="animate-pulse-dot" style={{ width: '6px', height: '6px', background: '#10b981', borderRadius: '50%' }} />
          System Healthy
        </div>
      </div>

      {/* Kill Switch Banner */}
      {killSwitch?.active && (
        <div className="card-crystal animate-fade-in-up" style={{ marginBottom: '32px', borderLeft: '4px solid #ef4444' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
            <div style={{ width: '48px', height: '48px', borderRadius: '14px', background: 'linear-gradient(135deg, #ef4444, #dc2626)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '24px', boxShadow: '0 8px 24px rgba(239,68,68,0.3)' }}>
              🛑
            </div>
            <div>
              <p style={{ color: '#dc2626', fontWeight: 700, fontSize: '15px' }}>Kill Switch Active</p>
              <p style={{ color: '#ef4444', fontSize: '13px', marginTop: '2px', opacity: 0.8 }}>All trading halted by {killSwitch.activatedBy} — {killSwitch.reason}</p>
            </div>
          </div>
        </div>
      )}

      {/* Stats Grid */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '18px', marginBottom: '32px' }}>
        <StatBox title="Total Users" value={dashboard?.totalUsers || 0} icon="👥" color="#4f46e5" gradient="linear-gradient(90deg, #6366f1, #a78bfa)" />
        <StatBox title="Active Deployments" value={dashboard?.activeDeployments || 0} icon="🚀" color="#059669" gradient="linear-gradient(90deg, #10b981, #34d399)" />
        <StatBox title="Orders Today" value={dashboard?.ordersToday || 0} icon="📋" color="#2563eb" gradient="linear-gradient(90deg, #3b82f6, #60a5fa)" />
        <StatBox title="Pending Orders" value={dashboard?.pendingOrders || 0} icon="⏳" color="#d97706" gradient="linear-gradient(90deg, #f59e0b, #fbbf24)" />
      </div>

      {/* Quick Actions */}
      <div style={{ marginBottom: '32px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '20px' }}>
          <div style={{ width: '4px', height: '20px', borderRadius: '999px', background: 'linear-gradient(180deg, #6366f1, #a78bfa)' }} />
          <h2 style={{ fontSize: '16px', fontWeight: 800, color: '#0f172a' }}>Quick Actions</h2>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '18px' }}>
          <QuickLink to="/admin/kill-switch" title="Kill Switch" desc="Emergency stop all trading" icon="🛑" color="linear-gradient(135deg, #ef4444, #f87171)" />
          <QuickLink to="/admin/deployments" title="Manage Deployments" desc="View and control all deployments" icon="🚀" color="linear-gradient(135deg, #6366f1, #8b5cf6)" />
          <QuickLink to="/admin/errors" title="Error Logs" desc="View recent system errors" icon="🐛" color="linear-gradient(135deg, #f59e0b, #fbbf24)" />
        </div>
      </div>

      {/* Configuration & Mappings */}
      <div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '20px' }}>
          <div style={{ width: '4px', height: '20px', borderRadius: '999px', background: 'linear-gradient(180deg, #10b981, #34d399)' }} />
          <h2 style={{ fontSize: '16px', fontWeight: 800, color: '#0f172a' }}>Configuration &amp; Mappings</h2>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '18px' }}>
          <QuickLink to="/admin/universe-groups" title="Universe Groups" desc="Manage symbol universes" icon="🌌" color="linear-gradient(135deg, #10b981, #34d399)" />
          <QuickLink to="/admin/strategy-mappings" title="Strategy Mappings" desc="Map strategies to groups" icon="🔗" color="linear-gradient(135deg, #3b82f6, #60a5fa)" />
          <QuickLink to="/admin/strategy-configs" title="Strategy Configs" desc="Capital, sizing, risk settings" icon="🔧" color="linear-gradient(135deg, #a78bfa, #c084fc)" />
        </div>
      </div>
    </div>
  );
}

function StatBox({ title, value, icon, color, gradient }) {
  return (
    <div className="stat-box-aurora animate-fade-in-up">
      <div style={{ position: 'absolute', top: 0, left: 0, right: 0, height: '4px', borderRadius: '18px 18px 0 0', background: gradient }} />
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '12px' }}>
        <span style={{ fontSize: '10px', fontWeight: 800, textTransform: 'uppercase', letterSpacing: '1px', color: '#94a3b8' }}>{title}</span>
        <div style={{ width: '36px', height: '36px', borderRadius: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '18px', background: `linear-gradient(135deg, ${color}15, ${color}0A)` }}>
          {icon}
        </div>
      </div>
      <div style={{ fontSize: '32px', fontWeight: 900, letterSpacing: '-1px', color }}>
        <AnimatedCounter value={value} />
      </div>
    </div>
  );
}

function QuickLink({ to, title, desc, icon, color }) {
  return (
    <Link to={to} className="card-crystal animate-fade-in-up" style={{ textDecoration: 'none', display: 'block' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '14px', marginBottom: '12px' }}>
        <div style={{ width: '44px', height: '44px', borderRadius: '12px', background: color, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '20px', boxShadow: '0 8px 24px rgba(99,102,241,0.2)' }}>
          {icon}
        </div>
        <h3 style={{ fontWeight: 700, color: '#0f172a', fontSize: '15px' }}>{title}</h3>
      </div>
      <p style={{ fontSize: '13px', color: '#64748b', lineHeight: 1.5 }}>{desc}</p>
    </Link>
  );
}
