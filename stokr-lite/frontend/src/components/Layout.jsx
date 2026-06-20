import { Outlet, NavLink, useNavigate, useLocation } from 'react-router-dom';
import { useEffect, useState } from 'react';

const traderLinks = [
  { to: '/', label: 'Dashboard', icon: '📊', end: true },
  { to: '/signals', label: 'Signals', icon: '📡' },
  { to: '/backtest', label: 'Backtest', icon: '📈' },
  { to: '/strategies', label: 'Strategies', icon: '🎯' },
  { to: '/deployments', label: 'Deployments', icon: '⚡' },
  { to: '/brokers', label: 'Brokers', icon: '🏦' },
  { to: '/orders', label: 'Orders', icon: '📋' },
  { to: '/positions', label: 'Positions', icon: '📈' },
  { to: '/settings', label: 'Settings', icon: '⚙️' },
];

const adminLinks = [
  { to: '/admin', label: 'Overview', icon: '📊', end: true },
  { to: '/admin/users', label: 'Users', icon: '👥' },
  { to: '/admin/kill-switch', label: 'Kill Switch', icon: '🛑' },
  { to: '/admin/deployments', label: 'All Deploys', icon: '🚀' },
  { to: '/admin/brokers', label: 'Broker Health', icon: '💓' },
  { to: '/admin/errors', label: 'Error Logs', icon: '🐛' },
  { to: '/admin/universe-groups', label: 'Universe Groups', icon: '🌌' },
  { to: '/admin/strategy-mappings', label: 'Strategy Mappings', icon: '🔗' },
  { to: '/admin/strategy-configs', label: 'Strategy Configs', icon: '🔧' },
];

function getUserRole() {
  try {
    const token = localStorage.getItem('token');
    const payload = JSON.parse(atob(token.split('.')[1]));
    return payload.role;
  } catch { return null; }
}

function getUserEmail() {
  try {
    const token = localStorage.getItem('token');
    const payload = JSON.parse(atob(token.split('.')[1]));
    return payload.sub || payload.email || 'User';
  } catch { return 'User'; }
}

function getUserInitials(email) {
  if (!email || email === 'User') return 'U';
  const parts = email.split('@')[0].split(/[._-]/);
  if (parts.length >= 2) {
    return (parts[0][0] + parts[1][0]).toUpperCase();
  }
  return email.substring(0, 2).toUpperCase();
}

export default function Layout() {
  const navigate = useNavigate();
  const location = useLocation();
  const role = getUserRole();
  const isAdmin = role === 'ADMIN';
  const [pageKey, setPageKey] = useState(location.pathname);
  const email = getUserEmail();
  const initials = getUserInitials(email);

  useEffect(() => {
    setPageKey(location.pathname);
  }, [location.pathname]);

  const handleLogout = () => {
    localStorage.clear();
    navigate('/login');
  };

  return (
    <div className="app-wrapper" style={{ display: 'grid', gridTemplateColumns: '280px 1fr', minHeight: '100vh' }}>
      {/* Animated blob background */}
      <div className="blob-bg" style={{ position: 'fixed', inset: 0, zIndex: 0, overflow: 'hidden', pointerEvents: 'none' }}>
        <div className="blob animate-blob-drift" style={{ width: '500px', height: '500px', background: 'linear-gradient(135deg, #a78bfa, #60a5fa)', top: '-150px', left: '-150px', position: 'absolute', borderRadius: '50%', filter: 'blur(100px)', opacity: 0.3 }} />
        <div className="blob animate-blob-drift" style={{ width: '400px', height: '400px', background: 'linear-gradient(135deg, #22d3ee, #10b981)', top: '20%', right: '-100px', position: 'absolute', borderRadius: '50%', filter: 'blur(100px)', opacity: 0.3, animationDelay: '2s', animationDirection: 'reverse' }} />
        <div className="blob animate-blob-drift" style={{ width: '350px', height: '350px', background: 'linear-gradient(135deg, #f472b6, #f59e0b)', bottom: '-80px', left: '15%', position: 'absolute', borderRadius: '50%', filter: 'blur(100px)', opacity: 0.3, animationDelay: '4s' }} />
      </div>

      {/* Sidebar - Aurora Pro */}
      <aside className="sidebar-aurora" style={{ padding: '32px 24px', display: 'flex', flexDirection: 'column', gap: '8px', position: 'sticky', top: 0, height: '100vh', overflowY: 'auto', zIndex: 10 }}>
        {/* Brand */}
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px', padding: '8px 12px 28px', marginBottom: '12px', background: 'linear-gradient(135deg, rgba(99,102,241,0.08), rgba(167,139,250,0.05))', borderRadius: '16px' }}>
          <div className="animate-brand-pop" style={{ width: '48px', height: '48px', borderRadius: '14px', background: 'linear-gradient(135deg, #6366f1 0%, #a78bfa 50%, #60a5fa 100%)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '24px', fontWeight: 900, color: 'white', boxShadow: '0 8px 32px rgba(99,102,241,0.4)' }}>
            S
          </div>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: '20px', fontWeight: 800, background: 'linear-gradient(135deg, #4f46e5, #7c3aed)', WebkitBackgroundClip: 'text', WebkitTextFillColor: 'transparent', letterSpacing: '-0.5px' }}>Stokr</div>
            <div style={{ fontSize: '8px', fontWeight: 800, letterSpacing: '1.2px', background: 'linear-gradient(135deg, #3b82f6, #0ea5e9)', color: 'white', padding: '3px 10px', borderRadius: '8px', textTransform: 'uppercase', display: 'inline-block', marginTop: '2px' }}>Aurora Pro</div>
          </div>
        </div>

        {/* Nav - Trading */}
        <div>
          <div style={{ fontSize: '9px', fontWeight: 800, textTransform: 'uppercase', letterSpacing: '1.5px', color: '#94a3b8', padding: '16px 12px 8px' }}>Trading</div>
          {traderLinks.map((link) => (
            <NavLink
              key={link.to}
              to={link.to}
              end={link.end}
              className={({ isActive }) => `nav-item-aurora ${isActive ? 'active' : ''}`}
              style={{ textDecoration: 'none' }}
            >
              <span style={{ fontSize: '20px', width: '28px', textAlign: 'center' }}>{link.icon}</span>
              <span>{link.label}</span>
            </NavLink>
          ))}
        </div>

        {/* Nav - Admin */}
        {isAdmin && (
          <div>
            <div style={{ fontSize: '9px', fontWeight: 800, textTransform: 'uppercase', letterSpacing: '1.5px', color: '#94a3b8', padding: '16px 12px 8px' }}>Administration</div>
            {adminLinks.map((link) => (
              <NavLink
                key={link.to}
                to={link.to}
                end={link.end}
                className={({ isActive }) => `nav-item-aurora ${isActive ? 'active' : ''}`}
                style={{ textDecoration: 'none' }}
              >
                <span style={{ fontSize: '20px', width: '28px', textAlign: 'center' }}>{link.icon}</span>
                <span>{link.label}</span>
              </NavLink>
            ))}
          </div>
        )}

        {/* Footer - User Card */}
        <div style={{ marginTop: 'auto', paddingTop: '20px', borderTop: '2px solid rgba(148,163,184,0.15)' }}>
          <button
            onClick={handleLogout}
            style={{ display: 'flex', alignItems: 'center', gap: '12px', padding: '12px 14px', borderRadius: '14px', background: 'linear-gradient(135deg, rgba(99,102,241,0.08), rgba(167,139,250,0.05))', cursor: 'pointer', transition: 'all 0.3s', border: 'none', width: '100%', textAlign: 'left' }}
            onMouseEnter={(e) => { e.currentTarget.style.background = 'linear-gradient(135deg, rgba(99,102,241,0.12), rgba(167,139,250,0.08))'; e.currentTarget.style.transform = 'translateY(-2px)'; }}
            onMouseLeave={(e) => { e.currentTarget.style.background = 'linear-gradient(135deg, rgba(99,102,241,0.08), rgba(167,139,250,0.05))'; e.currentTarget.style.transform = 'translateY(0)'; }}
          >
            <div style={{ width: '40px', height: '40px', borderRadius: '12px', background: 'linear-gradient(135deg, #a78bfa, #60a5fa)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '16px', fontWeight: 700, color: 'white', boxShadow: '0 4px 16px rgba(99,102,241,0.3)' }}>
              {initials}
            </div>
            <div style={{ lineHeight: 1.4, flex: 1, overflow: 'hidden' }}>
              <div style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>{email.split('@')[0]}</div>
              <div style={{ fontSize: '10px', color: 'var(--text-muted)' }}>{isAdmin ? 'Administrator' : 'Active Trading'}</div>
            </div>
            <span style={{ fontSize: '16px', opacity: 0.5 }}>→</span>
          </button>
        </div>
      </aside>

      {/* Main content - Aurora Pro */}
      <main className="bg-aurora" style={{ overflowY: 'auto', padding: '32px 40px', position: 'relative', zIndex: 1 }}>
        <div key={pageKey} className="animate-fade-in-up" style={{ maxWidth: '1400px' }}>
          <Outlet />
        </div>
      </main>
    </div>
  );
}

