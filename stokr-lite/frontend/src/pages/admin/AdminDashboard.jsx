import { useQuery } from '@tanstack/react-query';
import client from '../../api/client';

export default function AdminDashboard() {
  const { data: dashboard } = useQuery({
    queryKey: ['admin-dashboard'],
    queryFn: () => client.get('/admin/dashboard').then((r) => r.data),
  });

  const { data: killSwitch } = useQuery({
    queryKey: ['kill-switch'],
    queryFn: () => client.get('/admin/kill-switch').then((r) => r.data),
  });

  return (
    <div>
      <h1 className="text-2xl font-bold mb-6">Admin Dashboard</h1>

      {/* Kill Switch Warning */}
      {killSwitch?.active && (
        <div className="bg-red-50 border border-red-200 rounded-lg p-4 mb-6">
          <p className="text-red-700 font-semibold">KILL SWITCH IS ACTIVE - All trading is halted</p>
          <p className="text-red-600 text-sm mt-1">Activated by: {killSwitch.activatedBy} | Reason: {killSwitch.reason}</p>
        </div>
      )}

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4 mb-8">
        <StatCard title="Total Users" value={dashboard?.totalUsers || 0} />
        <StatCard title="Active Deployments" value={dashboard?.activeDeployments || 0} />
        <StatCard title="Total Orders Today" value={dashboard?.ordersToday || 0} />
        <StatCard title="Pending Orders" value={dashboard?.pendingOrders || 0} />
      </div>

      {/* Quick Links */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <QuickLink to="/admin/kill-switch" title="Kill Switch" desc="Emergency stop all trading" color="red" />
        <QuickLink to="/admin/deployments" title="Manage Deployments" desc="View and control all deployments" color="blue" />
        <QuickLink to="/admin/errors" title="Error Logs" desc="View recent system errors" color="yellow" />
      </div>
    </div>
  );
}

function StatCard({ title, value }) {
  return (
    <div className="bg-white rounded-lg shadow p-4">
      <p className="text-sm text-gray-500">{title}</p>
      <p className="text-2xl font-bold mt-1">{value}</p>
    </div>
  );
}

function QuickLink({ to, title, desc, color }) {
  const colors = { red: 'border-red-200 hover:bg-red-50', blue: 'border-blue-200 hover:bg-blue-50', yellow: 'border-yellow-200 hover:bg-yellow-50' };
  return (
    <a href={to} className={`block border rounded-lg p-4 transition ${colors[color]}`}>
      <h3 className="font-semibold">{title}</h3>
      <p className="text-sm text-gray-500 mt-1">{desc}</p>
    </a>
  );
}
