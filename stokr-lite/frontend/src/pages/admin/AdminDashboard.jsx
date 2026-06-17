import { useQuery } from '@tanstack/react-query';
import client from '../../api/client';

export default function AdminDashboard() {
  const { data: dashboard } = useQuery({ queryKey: ['admin-dashboard'], queryFn: () => client.get('/admin/dashboard').then((r) => r.data) });
  const { data: killSwitch } = useQuery({ queryKey: ['kill-switch'], queryFn: () => client.get('/admin/kill-switch').then((r) => r.data) });

  return (
    <div>
      <div className="mb-8"><h1 className="text-2xl font-bold text-slate-800">Admin Overview</h1><p className="text-slate-500 text-sm mt-1">Platform monitoring and controls</p></div>

      {killSwitch?.active && (
        <div className="bg-rose-50 border border-rose-200 rounded-2xl p-5 mb-6 flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-rose-100 flex items-center justify-center"><svg className="w-5 h-5 text-rose-600" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}><path strokeLinecap="round" strokeLinejoin="round" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" /></svg></div>
          <div><p className="text-rose-700 font-semibold text-sm">KILL SWITCH ACTIVE - All trading halted</p><p className="text-rose-600 text-xs mt-0.5">By: {killSwitch.activatedBy} | Reason: {killSwitch.reason}</p></div>
        </div>
      )}

      <div className="grid grid-cols-1 md:grid-cols-4 gap-5 mb-8">
        <StatCard title="Total Users" value={dashboard?.totalUsers || 0} />
        <StatCard title="Active Deployments" value={dashboard?.activeDeployments || 0} />
        <StatCard title="Orders Today" value={dashboard?.ordersToday || 0} />
        <StatCard title="Pending Orders" value={dashboard?.pendingOrders || 0} />
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-5">
        <QuickLink to="/admin/kill-switch" title="Kill Switch" desc="Emergency stop all trading" icon="M18.364 18.364A9 9 0 005.636 5.636m12.728 12.728A9 9 0 015.636 5.636m12.728 12.728L5.636 5.636" color="rose" />
        <QuickLink to="/admin/deployments" title="Manage Deployments" desc="View and control all deployments" icon="M4 6h16M4 10h16M4 14h16M4 18h16" color="indigo" />
        <QuickLink to="/admin/errors" title="Error Logs" desc="View recent system errors" icon="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" color="amber" />
      </div>

      <div className="mt-8">
        <h2 className="text-lg font-semibold text-slate-800 mb-4">Configuration & Mappings</h2>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-5">
          <QuickLink to="/admin/universe-groups" title="Universe Groups" desc="Manage symbol universes (Nifty 50, 100, custom)" icon="M4 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2V6zM14 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2V6zM4 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2v-2zM14 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2v-2z" color="emerald" />
          <QuickLink to="/admin/strategy-mappings" title="Strategy Mappings" desc="Map strategies to universe groups" icon="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1" color="sky" />
          <QuickLink to="/admin/strategy-configs" title="Strategy Configs" desc="Capital, sizing, risk, live/paper toggles" icon="M12 6V4m0 2a2 2 0 100 4m0-4a2 2 0 110 4m-6 8a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4m6 6v10m6-2a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4" color="violet" />
        </div>
      </div>
    </div>
  );
}

function StatCard({ title, value }) {
  return (
    <div className="bg-white rounded-2xl border border-slate-200/60 shadow-sm p-5">
      <p className="text-xs font-medium text-slate-500 uppercase tracking-wide">{title}</p>
      <p className="text-3xl font-bold text-slate-800 mt-1">{value}</p>
    </div>
  );
}

function QuickLink({ to, title, desc, icon, color }) {
  const colors = { rose: 'from-rose-500 to-pink-600 shadow-rose-500/20', indigo: 'from-indigo-500 to-violet-600 shadow-indigo-500/20', amber: 'from-amber-400 to-orange-500 shadow-amber-500/20', emerald: 'from-emerald-500 to-teal-600 shadow-emerald-500/20', sky: 'from-sky-500 to-cyan-600 shadow-sky-500/20', violet: 'from-violet-500 to-purple-600 shadow-violet-500/20' };
  return (
    <a href={to} className="bg-white rounded-2xl border border-slate-200/60 shadow-sm p-5 hover:shadow-md transition-all duration-200 group">
      <div className="flex items-center gap-3 mb-3">
        <div className={`w-9 h-9 rounded-lg bg-gradient-to-br ${colors[color]} flex items-center justify-center shadow-lg`}>
          <svg className="w-4 h-4 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}><path strokeLinecap="round" strokeLinejoin="round" d={icon} /></svg>
        </div>
        <h3 className="font-semibold text-slate-800 group-hover:text-indigo-600 transition">{title}</h3>
      </div>
      <p className="text-sm text-slate-500">{desc}</p>
    </a>
  );
}
