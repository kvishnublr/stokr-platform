import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import client from '../../api/client';

export default function AdminDeployments() {
  const queryClient = useQueryClient();

  const { data: deployments, isLoading } = useQuery({
    queryKey: ['admin-deployments'],
    queryFn: () => client.get('/admin/deployments').then((r) => r.data),
  });

  const forceStopMutation = useMutation({
    mutationFn: (id) => client.post(`/admin/deployments/${id}/force-stop`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin-deployments'] }),
  });

  const stopAllMutation = useMutation({
    mutationFn: () => client.post('/admin/deployments/stop-all'),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin-deployments'] }),
  });

  if (isLoading) return <div className="text-gray-500">Loading...</div>;

  return (
    <div>
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold">All Deployments</h1>
        <button onClick={() => { if (confirm('Stop ALL deployments?')) stopAllMutation.mutate(); }}
          className="bg-red-600 text-white px-4 py-2 rounded-lg hover:bg-red-700 text-sm transition">
          Stop All Deployments
        </button>
      </div>
      <div className="bg-white rounded-lg shadow overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50">
            <tr className="text-left text-gray-500">
              <th className="p-4">User</th>
              <th className="p-4">Strategy</th>
              <th className="p-4">Broker</th>
              <th className="p-4">Mode</th>
              <th className="p-4">Capital</th>
              <th className="p-4">Status</th>
              <th className="p-4">Actions</th>
            </tr>
          </thead>
          <tbody>
            {deployments?.length === 0 && (
              <tr><td colSpan="7" className="p-8 text-center text-gray-400">No deployments</td></tr>
            )}
            {deployments?.map((d) => (
              <tr key={d.id} className="border-t">
                <td className="p-4">{d.userEmail || `User #${d.userId}`}</td>
                <td className="p-4 font-medium">{d.strategyName || `#${d.strategyId}`}</td>
                <td className="p-4">{d.brokerName || `#${d.brokerAccountId}`}</td>
                <td className="p-4">
                  <span className={`px-2 py-1 rounded text-xs font-medium ${d.mode === 'LIVE' ? 'bg-red-100 text-red-700' : 'bg-yellow-100 text-yellow-700'}`}>{d.mode}</span>
                </td>
                <td className="p-4">₹{d.capital?.toLocaleString()}</td>
                <td className="p-4">
                  <span className={`px-2 py-1 rounded text-xs font-medium ${d.status === 'ACTIVE' ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600'}`}>{d.status}</span>
                </td>
                <td className="p-4">
                  {d.status === 'ACTIVE' && (
                    <button onClick={() => forceStopMutation.mutate(d.id)} className="text-red-600 hover:text-red-800 text-xs font-medium">Force Stop</button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
