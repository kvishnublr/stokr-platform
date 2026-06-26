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

  const squareOffMutation = useMutation({
    mutationFn: (id) => client.post(`/admin/square-off/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin-deployments'] }),
  });

  if (isLoading) return (
    <div className="flex items-center justify-center h-64">
      <div className="animate-spin w-6 h-6 border-2 border-indigo-500 border-t-transparent rounded-full" />
    </div>
  );

  return (
    <div className="animate-fade-in-up">
      <div className="flex justify-between items-center mb-6">
        <div className="flex items-center gap-3">
          <div className="w-1 h-7 rounded-full bg-gradient-to-b from-indigo-500 to-violet-500" />
          <h1 className="text-3xl font-bold text-slate-800">All Deployments</h1>
        </div>
        <button onClick={() => { if (confirm('Stop ALL deployments?')) stopAllMutation.mutate(); }}
          className="bg-rose-50 hover:bg-rose-100 text-rose-600 px-4 py-2 rounded-xl text-sm font-medium transition-all duration-200 border border-rose-200 hover:border-rose-300">
          Stop All
        </button>
      </div>

      <div className="card-crystal overflow-hidden">
        <table className="w-full text-sm table-crystal">
          <thead>
            <tr>
              <th>User</th>
              <th>Strategy</th>
              <th>Broker</th>
              <th>Mode</th>
              <th>Capital</th>
              <th>Status</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {(!deployments || deployments.length === 0) && (
              <tr><td colSpan="7" className="p-8 text-center text-slate-400">No deployments</td></tr>
            )}
            {deployments?.map((d) => (
              <tr key={d.id} className="border-t border-slate-50">
                <td className="p-4 text-slate-700">{d.userEmail || `User #${d.userId}`}</td>
                <td className="p-4 font-medium text-slate-800">{d.strategyName || `#${d.strategyId}`}</td>
                <td className="p-4 text-slate-700">{d.brokerName || `#${d.brokerAccountId}`}</td>
                <td className="p-4">
                  <span className={`inline-flex px-2 py-0.5 rounded-md text-[10px] font-semibold tracking-wide uppercase ${d.mode === 'LIVE' ? 'bg-rose-50 text-rose-600' : 'bg-amber-50 text-amber-600'}`}>{d.mode}</span>
                </td>
                <td className="p-4 font-medium text-slate-800">₹{d.capital?.toLocaleString()}</td>
                <td className="p-4">
                  <span className={`inline-flex px-2 py-0.5 rounded-md text-[10px] font-semibold tracking-wide uppercase ${d.status === 'ACTIVE' ? 'bg-emerald-50 text-emerald-600' : 'bg-slate-100 text-slate-600'}`}>{d.status}</span>
                </td>
                <td className="p-4">
                  <div className="flex gap-2">
                    {d.status === 'ACTIVE' && (
                      <>
                        <button onClick={() => { if (confirm(`Square off all positions for deployment #${d.id}?`)) squareOffMutation.mutate(d.id); }}
                          className="text-amber-600 hover:text-amber-700 text-xs font-medium transition-colors">Square Off</button>
                        <button onClick={() => forceStopMutation.mutate(d.id)} className="text-rose-600 hover:text-rose-700 text-xs font-medium transition-colors">Force Stop</button>
                      </>
                    )}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
