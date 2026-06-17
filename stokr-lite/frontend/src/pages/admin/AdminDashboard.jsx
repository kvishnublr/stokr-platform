import { useQuery } from '@tanstack/react-query';
import client from '../../api/client';
import { useEffect, useState, useRef } from 'react';

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
      <div className="mb-8 animate-fade-in-up">
        <h1 className="text-3xl font-bold text-white tracking-tight">Admin Overview</h1>
        <p className="text-slate-400 text-sm mt-2">Platform monitoring and controls</p>
      </div>

      {/* Kill Switch Banner */}
      {killSwitch?.active && (
        <div className="glass-card-strong rounded-2xl p-5 mb-8 flex items-center gap-4 animate-fade-in-up border-rose-500/30 animate-border-glow">
          <div className="w-12 h-12 rounded-xl bg-gradient-to-br from-rose-500 to-red-600 flex items-center justify-center shadow-lg shadow-rose-500/30 animate-pulse">
            <svg className="w-6 h-6 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
            </svg>
          </div>
          <div>
            <p className="text-rose-400 font-bold text-sm uppercase tracking-wide">Kill Switch Active</p>
            <p className="text-rose-300/70 text-xs mt-1">All trading halted by {killSwitch.activatedBy} — {killSwitch.reason}</p>
          </div>
        </div>
      )}

      {/* Stats Grid */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-5 mb-8">
        <StatCard title="Total Users" value={dashboard?.totalUsers || 0} icon="users" color="indigo" delay="delay-100" />
        <StatCard title="Active Deployments" value={dashboard?.activeDeployments || 0} icon="deploy" color="emerald" delay="delay-200" />
        <StatCard title="Orders Today" value={dashboard?.ordersToday || 0} icon="orders" color="sky" delay="delay-300" />
        <StatCard title="Pending Orders" value={dashboard?.pendingOrders || 0} icon="pending" color="amber" delay="delay-400" />
      </div>

      {/* Quick Links */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-5 mb-8">
        <QuickLink to="/admin/kill-switch" title="Kill Switch" desc="Emergency stop all trading" icon="M18.364 18.364A9 9 0 005.636 5.636m12.728 12.728A9 9 0 015.636 5.636m12.728 12.728L5.636 5.636" color="rose" delay="delay-100" />
        <QuickLink to="/admin/deployments" title="Manage Deployments" desc="View and control all deployments" icon="M4 6h16M4 10h16M4 14h16M4 18h16" color="indigo" delay="delay-200" />
        <QuickLink to="/admin/errors" title="Error Logs" desc="View recent system errors" icon="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" color="amber" delay="delay-300" />
      </div>

      {/* Config & Mappings Section */}
      <div className="animate-fade-in-up delay-500">
        <div className="flex items-center gap-3 mb-5">
          <div className="w-1 h-6 rounded-full bg-gradient-to-b from-indigo-500 to-violet-500" />
          <h2 className="text-lg font-semibold text-white">Configuration & Mappings</h2>
        </div>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-5">
          <QuickLink to="/admin/universe-groups" title="Universe Groups" desc="Manage symbol universes (Nifty 50, 100, custom)" icon="M4 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2V6zM14 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2V6zM4 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2v-2zM14 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2v-2z" color="emerald" delay="delay-100" />
          <QuickLink to="/admin/strategy-mappings" title="Strategy Mappings" desc="Map strategies to universe groups" icon="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1" color="sky" delay="delay-200" />
          <QuickLink to="/admin/strategy-configs" title="Strategy Configs" desc="Capital, sizing, risk, live/paper toggles" icon="M12 6V4m0 2a2 2 0 100 4m0-4a2 2 0 110 4m-6 8a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4m6 6v10m6-2a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4" color="violet" delay="delay-300" />
        </div>
      </div>
    </div>
  );
}

function StatCard({ title, value, icon, color, delay }) {
  const gradients = {
    indigo: 'from-indigo-500/20 to-violet-500/20 border-indigo-500/20 shadow-indigo-500/10',
    emerald: 'from-emerald-500/20 to-teal-500/20 border-emerald-500/20 shadow-emerald-500/10',
    sky: 'from-sky-500/20 to-cyan-500/20 border-sky-500/20 shadow-sky-500/10',
    amber: 'from-amber-500/20 to-orange-500/20 border-amber-500/20 shadow-amber-500/10',
  };
  const iconColors = {
    indigo: 'text-indigo-400',
    emerald: 'text-emerald-400',
    sky: 'text-sky-400',
    amber: 'text-amber-400',
  };
  const icons = {
    users: <path strokeLinecap="round" strokeLinejoin="round" d="M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z" />,
    deploy: <path strokeLinecap="round" strokeLinejoin="round" d="M19.428 15.428a2 2 0 00-1.022-.547l-2.387-.477a6 6 0 00-3.86.517l-.318.158a6 6 0 01-3.86.517L6.05 15.21a2 2 0 00-1.806.547M8 4h8l-1 1v5.172a2 2 0 00.586 1.414l5 5c1.26 1.26.367 3.414-1.415 3.414H4.828c-1.782 0-2.674-2.154-1.414-3.414l5-5A2 2 0 009 10.172V5L8 4z" />,
    orders: <path strokeLinecap="round" strokeLinejoin="round" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2" />,
    pending: <path strokeLinecap="round" strokeLinejoin="round" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />,
  };

  return (
    <div className={`glass-card rounded-2xl p-5 hover-lift hover-glow animate-fade-in-up ${delay} relative overflow-hidden group`}>
      {/* Gradient bg */}
      <div className={`absolute inset-0 bg-gradient-to-br ${gradients[color]} opacity-0 group-hover:opacity-100 transition-opacity duration-500`} />
      <div className="relative z-10">
        <div className="flex items-center justify-between mb-3">
          <p className="text-xs font-medium text-slate-400 uppercase tracking-wider">{title}</p>
          <svg className={`w-5 h-5 ${iconColors[color]}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
            {icons[icon]}
          </svg>
        </div>
        <p className="text-3xl font-bold text-white stat-number">
          <AnimatedCounter value={value} />
        </p>
      </div>
    </div>
  );
}

function QuickLink({ to, title, desc, icon, color, delay }) {
  const colors = {
    rose: 'from-rose-500 to-pink-600 shadow-rose-500/20',
    indigo: 'from-indigo-500 to-violet-600 shadow-indigo-500/20',
    amber: 'from-amber-400 to-orange-500 shadow-amber-500/20',
    emerald: 'from-emerald-500 to-teal-600 shadow-emerald-500/20',
    sky: 'from-sky-500 to-cyan-600 shadow-sky-500/20',
    violet: 'from-violet-500 to-purple-600 shadow-violet-500/20',
  };
  return (
    <a href={to} className={`glass-card rounded-2xl p-5 hover-lift hover-glow animate-fade-in-up ${delay} group block`}>
      <div className="flex items-center gap-3 mb-3">
        <div className={`w-10 h-10 rounded-xl bg-gradient-to-br ${colors[color]} flex items-center justify-center shadow-lg group-hover:scale-110 transition-transform duration-300`}>
          <svg className="w-5 h-5 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}><path strokeLinecap="round" strokeLinejoin="round" d={icon} /></svg>
        </div>
        <h3 className="font-semibold text-slate-100 group-hover:text-white transition-colors">{title}</h3>
      </div>
      <p className="text-sm text-slate-400">{desc}</p>
    </a>
  );
}
