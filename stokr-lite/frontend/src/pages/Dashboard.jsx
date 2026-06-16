import { useQuery } from '@tanstack/react-query';
import client from '../api/client';

export default function Dashboard() {
  const { data: deployments } = useQuery({
    queryKey: ['deployments'],
    queryFn: () => client.get('/deployments').then((r) => r.data),
  });

  const activeDeployments = deployments?.filter((d) => d.status === 'ACTIVE') || [];
  const stoppedDeployments = deployments?.filter((d) => d.status !== 'ACTIVE') || [];

  return (
    <div>
      <h1 className="text-2xl font-bold mb-6">Dashboard</h1>

      {/* Stats Cards */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4 mb-8">
        <StatCard title="Total Deployments" value={deployments?.length || 0} color="blue" />
        <StatCard title="Active" value={activeDeployments.length} color="green" />
        <StatCard title="Stopped" value={stoppedDeployments.length} color="gray" />
        <StatCard title="Paper Mode" value={deployments?.filter((d) => d.mode === 'PAPER').length || 0} color="yellow" />
      </div>

      {/* Active Deployments */}
      <div className="bg-white rounded-lg shadow p-6 mb-6">
        <h2 className="text-lg font-semibold mb-4">Active Deployments</h2>
        {activeDeployments.length === 0 ? (
          <p className="text-gray-500">No active deployments. Deploy a strategy to get started.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left text-gray-500">
                  <th className="pb-2">Strategy</th>
                  <th className="pb-2">Mode</th>
                  <th className="pb-2">Capital</th>
                  <th className="pb-2">Status</th>
                  <th className="pb-2">Created</th>
                </tr>
              </thead>
              <tbody>
                {activeDeployments.map((d) => (
                  <tr key={d.id} className="border-b">
                    <td className="py-3 font-medium">{d.strategyName || `Strategy #${d.strategyId}`}</td>
                    <td className="py-3">
                      <span className={`px-2 py-1 rounded text-xs font-medium ${d.mode === 'LIVE' ? 'bg-red-100 text-red-700' : 'bg-yellow-100 text-yellow-700'}`}>
                        {d.mode}
                      </span>
                    </td>
                    <td className="py-3">&#8377;{d.capital?.toLocaleString()}</td>
                    <td className="py-3">
                      <span className="px-2 py-1 rounded text-xs bg-green-100 text-green-700">{d.status}</span>
                    </td>
                    <td className="py-3 text-gray-500">{new Date(d.createdAt).toLocaleDateString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Market Status */}
      <div className="bg-white rounded-lg shadow p-6">
        <h2 className="text-lg font-semibold mb-4">Market Status</h2>
        <MarketStatus />
      </div>
    </div>
  );
}

function StatCard({ title, value, color }) {
  const colors = {
    blue: 'bg-blue-50 text-blue-700 border-blue-200',
    green: 'bg-green-50 text-green-700 border-green-200',
    gray: 'bg-gray-50 text-gray-700 border-gray-200',
    yellow: 'bg-yellow-50 text-yellow-700 border-yellow-200',
  };
  return (
    <div className={`rounded-lg border p-4 ${colors[color]}`}>
      <p className="text-sm opacity-70">{title}</p>
      <p className="text-2xl font-bold mt-1">{value}</p>
    </div>
  );
}

function MarketStatus() {
  const { data } = useQuery({
    queryKey: ['market-status'],
    queryFn: () => client.get('/market/status').then((r) => r.data),
  });
  return (
    <div className="flex items-center gap-3">
      <div className={`w-3 h-3 rounded-full ${data?.open ? 'bg-green-500 animate-pulse' : 'bg-red-500'}`} />
      <span className="text-sm">{data?.open ? 'Market is Open' : 'Market is Closed'}</span>
    </div>
  );
}
