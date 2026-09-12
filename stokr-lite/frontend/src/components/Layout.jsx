import { Outlet, NavLink, useNavigate, useLocation } from 'react-router-dom';
import { useEffect, useState, useRef } from 'react';
import client from '../api/client';
import { ToastContainer, toast } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';
import GlobalExecutionBar from './GlobalExecutionBar';

const traderLinks = [
  { to: '/', label: 'Dashboard', icon: '📊', end: true },
  { to: '/signals', label: 'Signals', icon: '📡' },
  { to: '/backtest', label: 'Backtest', icon: '📈' },
  { to: '/strategies', label: 'Strategies', icon: '🎯' },
  { to: '/trader', label: 'Trader', icon: '🏪' },
  { to: '/deployments', label: 'Deployments', icon: '⚡' },
  { to: '/brokers', label: 'Brokers', icon: '🏦' },
  { to: '/orders', label: 'Orders', icon: '📋' },
  { to: '/positions', label: 'Positions', icon: '📈' },
  { to: '/settings', label: 'Settings', icon: '⚙️' },
  { to: '/option-arbitrage', label: 'Option Arb', icon: '🔀' },
  { to: '/smart-strategies', label: 'Smart Strategies', icon: '🧠' },
  { to: '/strategy-builder', label: 'Builder', icon: '🛠️' },
];

const adminLinks = [
  { to: '/admin', label: 'Overview', icon: '📊', end: true },
  { to: '/admin/users', label: 'Users', icon: '👥' },
  { to: '/admin/kill-switch', label: 'Kill Switch', icon: '🛑' },
  { to: '/admin/deployments', label: 'All Deploys', icon: '🚀' },
  { to: '/admin/brokers', label: 'Broker Health', icon: '💓' },
  { to: '/admin/errors', label: 'Error Logs', icon: '🐛' },
  { to: '/admin/orders', label: 'Orders', icon: '📋' },
  { to: '/admin/universe-groups', label: 'Universe Groups', icon: '🌌' },
  { to: '/admin/strategy-mappings', label: 'Strategy Mappings', icon: '🔗' },
  { to: '/admin/strategy-configs', label: 'Strategy Configs', icon: '🔧' },
  { to: '/admin/audit-log', label: 'Audit Log', icon: '📋' },
];

const POLL_INTERVAL_MS  = 2 * 60 * 1000;  // check every 2 min
const SNOOZE_MS         = 10 * 60 * 1000; // hide popup for 10 min after dismiss

function isMarketHours() {
  const now = new Date(new Date().toLocaleString('en-US', { timeZone: 'Asia/Kolkata' }));
  const h = now.getHours(), m = now.getMinutes(), total = h * 60 + m;
  return total >= 9 * 60 && total <= 15 * 60 + 30;
}

function BrokerAlert() {
  const [health, setHealth]     = useState(null); // null = loading, {ok,status,message}
  const [visible, setVisible]   = useState(false);
  const snoozeUntil             = useRef(0);
  const navigate                = useNavigate();

  const check = async () => {
    try {
      const r = await client.get('/brokers/health');
      const h = r.data;
      setHealth(h);
      if (!h.ok && Date.now() > snoozeUntil.current) {
        setVisible(true);
      } else if (h.ok) {
        setVisible(false);
      }
    } catch {
      // silently skip if unauthenticated
    }
  };

  useEffect(() => {
    check();
    const t = setInterval(check, POLL_INTERVAL_MS);
    return () => clearInterval(t);
  }, []);

  const dismiss = () => {
    snoozeUntil.current = Date.now() + SNOOZE_MS;
    setVisible(false);
  };

  if (!visible || !health || health.ok) return null;

  const urgent    = isMarketHours();
  const isExpired = health.status === 'TOKEN_EXPIRED';

  return (
    <>
      <style>{`
        @keyframes slideDown { from { transform: translateY(-120%); opacity: 0; } to { transform: translateY(0); opacity: 1; } }
        @keyframes pulse-border { 0%,100% { box-shadow: 0 0 0 0 rgba(239,68,68,0.5); } 50% { box-shadow: 0 0 0 8px rgba(239,68,68,0); } }
        .broker-alert-bar { animation: slideDown .4s cubic-bezier(.175,.885,.32,1.275); }
        ${urgent ? '.broker-alert-bar { animation: slideDown .4s cubic-bezier(.175,.885,.32,1.275), pulse-border 2s ease infinite; }' : ''}
      `}</style>

      {/* Full-width top banner */}
      <div className="broker-alert-bar" style={{
        position: 'fixed', top: 0, left: 0, right: 0, zIndex: 9999,
        background: urgent
          ? 'linear-gradient(135deg, #dc2626, #991b1b)'
          : 'linear-gradient(135deg, #b45309, #92400e)',
        color: 'white',
        padding: '0 24px',
        display: 'flex', alignItems: 'center', gap: '16px',
        minHeight: '52px',
        boxShadow: '0 4px 24px rgba(0,0,0,0.35)',
      }}>
        {/* Pulsing icon */}
        <div style={{ fontSize: '22px', flexShrink: 0 }}>
          {urgent ? '🚨' : '⚠️'}
        </div>

        {/* Text */}
        <div style={{ flex: 1, display: 'flex', alignItems: 'center', gap: '10px', flexWrap: 'wrap' }}>
          <span style={{ fontWeight: 800, fontSize: '14px', letterSpacing: '0.02em' }}>
            {isExpired ? 'ZERODHA SESSION EXPIRED' : 'ZERODHA NOT CONNECTED'}
          </span>
          <span style={{ fontSize: '13px', opacity: 0.85 }}>
            {isExpired
              ? '— Token expired. Live trading, SL/target monitoring and order placement are PAUSED.'
              : '— No broker connected. Live trading, SL/target monitoring and order placement are PAUSED.'}
          </span>
          {urgent && (
            <span style={{ background: 'rgba(255,255,255,0.2)', padding: '2px 10px', borderRadius: '20px', fontSize: '11px', fontWeight: 700, letterSpacing: '0.05em' }}>
              MARKET HOURS — ACTION NEEDED NOW
            </span>
          )}
        </div>

        {/* Connect button */}
        <button
          onClick={() => { navigate('/brokers'); dismiss(); }}
          style={{
            padding: '7px 20px', background: 'white', color: urgent ? '#dc2626' : '#92400e',
            border: 'none', borderRadius: '8px', fontWeight: 800, fontSize: '13px',
            cursor: 'pointer', flexShrink: 0, whiteSpace: 'nowrap',
            boxShadow: '0 2px 8px rgba(0,0,0,0.2)',
          }}
        >
          {isExpired ? 'Reconnect Now' : 'Connect Zerodha'}
        </button>

        {/* Dismiss */}
        <button
          onClick={dismiss}
          title="Dismiss for 10 minutes"
          style={{
            background: 'rgba(255,255,255,0.15)', border: '1px solid rgba(255,255,255,0.3)',
            color: 'white', borderRadius: '6px', padding: '4px 10px',
            cursor: 'pointer', fontSize: '13px', fontWeight: 700, flexShrink: 0,
          }}
        >
          ✕
        </button>
      </div>

      {/* Spacer handled by grid children via paddingTop, not here */}
    </>
  );
}

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
    <>
      {/* Global broker health alert — outside grid so it doesn't shift columns */}
      <BrokerAlert />

    
    {/* Hamburger menu for mobile */}
    <div className="md:hidden flex items-center justify-between p-4 bg-white/80 backdrop-blur-md sticky top-0 z-50 border-b border-gray-100 shadow-sm">
      <div className="flex items-center gap-3">
        <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-indigo-500 to-purple-500 text-white flex items-center justify-center font-bold">S</div>
        <div className="font-bold text-lg bg-clip-text text-transparent bg-gradient-to-r from-indigo-600 to-purple-600">Stokr</div>
      </div>
      <button onClick={() => document.getElementById('mobile-sidebar').classList.toggle('-translate-x-full')} className="p-2 bg-gray-100 rounded-lg text-gray-600">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><line x1="3" y1="12" x2="21" y2="12"></line><line x1="3" y1="6" x2="21" y2="6"></line><line x1="3" y1="18" x2="21" y2="18"></line></svg>
      </button>
    </div>

    <ToastContainer position="bottom-right" theme="colored" />
    <div className="app-wrapper flex flex-col md:grid md:grid-cols-[280px_1fr] min-h-screen relative">


      {/* Animated blob background */}
      <div className="blob-bg" style={{ position: 'fixed', inset: 0, zIndex: 0, overflow: 'hidden', pointerEvents: 'none' }}>
        <div className="blob animate-blob-drift" style={{ width: '500px', height: '500px', background: 'linear-gradient(135deg, #a78bfa, #60a5fa)', top: '-150px', left: '-150px', position: 'absolute', borderRadius: '50%', filter: 'blur(100px)', opacity: 0.3 }} />
        <div className="blob animate-blob-drift" style={{ width: '400px', height: '400px', background: 'linear-gradient(135deg, #22d3ee, #10b981)', top: '20%', right: '-100px', position: 'absolute', borderRadius: '50%', filter: 'blur(100px)', opacity: 0.3, animationDelay: '2s', animationDirection: 'reverse' }} />
        <div className="blob animate-blob-drift" style={{ width: '350px', height: '350px', background: 'linear-gradient(135deg, #f472b6, #f59e0b)', bottom: '-80px', left: '15%', position: 'absolute', borderRadius: '50%', filter: 'blur(100px)', opacity: 0.3, animationDelay: '4s' }} />
      </div>

      {/* Sidebar - Aurora Pro */}
      <aside id="mobile-sidebar" className="sidebar-aurora fixed inset-y-0 left-0 w-[280px] bg-white md:bg-transparent md:static transform -translate-x-full md:translate-x-0 transition-transform duration-300 ease-in-out z-40 border-r border-gray-200 md:border-none shadow-2xl md:shadow-none" style={{ padding: '32px 24px', display: 'flex', flexDirection: 'column', gap: '8px', height: '100vh', overflowY: 'auto' }}>
        <button onClick={() => document.getElementById('mobile-sidebar').classList.add('-translate-x-full')} className="md:hidden absolute top-4 right-4 p-2 text-gray-500 hover:bg-gray-100 rounded-lg">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
        </button>
        {/* Brand */}
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px', padding: '8px 12px 28px', marginBottom: '12px', background: isAdmin ? 'linear-gradient(135deg, rgba(239,68,68,0.08), rgba(220,38,38,0.05))' : 'linear-gradient(135deg, rgba(99,102,241,0.08), rgba(167,139,250,0.05))', borderRadius: '16px' }}>
          <div className="animate-brand-pop" style={{ width: '48px', height: '48px', borderRadius: '14px', background: isAdmin ? 'linear-gradient(135deg, #dc2626 0%, #ef4444 50%, #f97316 100%)' : 'linear-gradient(135deg, #6366f1 0%, #a78bfa 50%, #60a5fa 100%)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '24px', fontWeight: 900, color: 'white', boxShadow: isAdmin ? '0 8px 32px rgba(239,68,68,0.4)' : '0 8px 32px rgba(99,102,241,0.4)' }}>
            S
          </div>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: '20px', fontWeight: 800, background: isAdmin ? 'linear-gradient(135deg, #dc2626, #ef4444)' : 'linear-gradient(135deg, #4f46e5, #7c3aed)', WebkitBackgroundClip: 'text', WebkitTextFillColor: 'transparent', letterSpacing: '-0.5px' }}>Stokr</div>
            <div style={{ fontSize: '8px', fontWeight: 800, letterSpacing: '1.2px', background: isAdmin ? 'linear-gradient(135deg, #dc2626, #b91c1c)' : 'linear-gradient(135deg, #3b82f6, #0ea5e9)', color: 'white', padding: '3px 10px', borderRadius: '8px', textTransform: 'uppercase', display: 'inline-block', marginTop: '2px' }}>{isAdmin ? '🔴 Admin' : '🟢 Trader'}</div>
          </div>
        </div>

        {/* Nav - Trading */}
        <div>
          <div style={{ fontSize: '9px', fontWeight: 800, textTransform: 'uppercase', letterSpacing: '1.5px', color: isAdmin ? '#fca5a5' : '#94a3b8', padding: '16px 12px 8px' }}>Trading</div>
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
            <div style={{ fontSize: '9px', fontWeight: 800, textTransform: 'uppercase', letterSpacing: '1.5px', color: '#fca5a5', padding: '16px 12px 8px' }}>Administration</div>
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
            style={{ display: 'flex', alignItems: 'center', gap: '12px', padding: '12px 14px', borderRadius: '14px', background: isAdmin ? 'linear-gradient(135deg, rgba(239,68,68,0.08), rgba(220,38,38,0.05))' : 'linear-gradient(135deg, rgba(99,102,241,0.08), rgba(167,139,250,0.05))', cursor: 'pointer', transition: 'all 0.3s', border: 'none', width: '100%', textAlign: 'left' }}
            onMouseEnter={(e) => { e.currentTarget.style.background = isAdmin ? 'linear-gradient(135deg, rgba(239,68,68,0.12), rgba(220,38,38,0.08))' : 'linear-gradient(135deg, rgba(99,102,241,0.12), rgba(167,139,250,0.08))'; e.currentTarget.style.transform = 'translateY(-2px)'; }}
            onMouseLeave={(e) => { e.currentTarget.style.background = 'linear-gradient(135deg, rgba(99,102,241,0.08), rgba(167,139,250,0.05))'; e.currentTarget.style.transform = 'translateY(0)'; }}
          >
            <div style={{ width: '40px', height: '40px', borderRadius: '12px', background: isAdmin ? 'linear-gradient(135deg, #ef4444, #f97316)' : 'linear-gradient(135deg, #a78bfa, #60a5fa)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '16px', fontWeight: 700, color: 'white', boxShadow: isAdmin ? '0 4px 16px rgba(239,68,68,0.3)' : '0 4px 16px rgba(99,102,241,0.3)' }}>
              {initials}
            </div>
            <div style={{ lineHeight: 1.4, flex: 1, overflow: 'hidden' }}>
              <div style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>{email.split('@')[0]}</div>
              <div style={{ fontSize: '10px', fontWeight: 700, color: isAdmin ? '#dc2626' : '#059669', letterSpacing: '0.5px' }}>{isAdmin ? '🔴 ADMINISTRATOR' : '🟢 ACTIVE TRADER'}</div>
            </div>
            <span style={{ fontSize: '16px', opacity: 0.5 }}>→</span>
          </button>
        </div>
      </aside>

      {/* Main content - Aurora Pro */}
      <main className="bg-aurora flex-1 w-full" style={{ overflowX: 'hidden', overflowY: 'auto', position: 'relative', zIndex: 1 }}>
        <div className="p-4 md:p-8">
        <div key={pageKey} className="animate-fade-in-up" style={{ maxWidth: '1400px', margin: '0 auto' }}>
          <GlobalExecutionBar showToast={(msg, type) => toast(msg, { type: type || 'info' })} title="Global Execution Control" subtitle="Universal API Routing & Mode Selector" />
          <Outlet />
        </div>
      </div>
      </main>
    </div>
    </>
  );
}

