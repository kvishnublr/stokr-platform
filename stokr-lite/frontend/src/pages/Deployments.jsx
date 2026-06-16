import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import client from '../api/client';

export default function Deployments() {
  const queryClient = useQueryClient();
  const [showForm, setShowForm] = useState(false);

  const { data: deployments, isLoading } = useQuery({
    queryKey: ['deployments'],
    queryFn: () => client.get('/deployments').then((r) => r.data),
  });

  const { data: strategies } = useQuery({
    queryKey: ['strategies'],
    queryFn: () => client.get('/strategies/enabled').then((r) => r.data),
  });

  const { data: brokers } = useQuery({
    queryKey: ['brokers'],
    queryFn: () => client.get('/brokers').then((r) => r.data),
  });

  const deployMutation = useMutation({
    mutationFn: (payload) => client.post('/deployments', payload),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['deployments'] }); setShowForm(false); },
  });

  const stopMutation = useMutation({
    mutationFn: (id) => client.delete(`/deployments/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['deployments'] }),
  });

  const [form, setForm] = useState({ strategyId: '', brokerAccountId: '', mode: 'PAPER', capital: 100000 });

  return (
    <div>
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold">My Deployments</h1>
        <button onClick={() => setShowForm(!showForm)} className="bg-blue-600 text-white px-4 py-2 rounded-lg hover:bg-blue-700 text-sm transition">
          + New Deployment
        </button>
      </div>

      {/* Deploy Form */}
      {showForm && (
        <div className="bg-white rounded-lg shadow p-6 mb-6">
          <h2 className="text-lg font-semibold mb-4">Deploy Strategy</h2>
          {deployMutation.isError && (
            <div className="bg-red-50 text-red-600 text-sm p-3 rounded mb-4">{deployMutation.error?.response?.data?.message || 'Deploy failed'}</div>
          )}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Strategy</label>
              <select value={form.strategyId} onChange={(e) => setForm({ ...form, strategyId: e.target.value })}
                className="w-full px-3 py-2 border rounded-lg">
                <option value="">Select strategy...</option>
                {strategies?.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Broker Account</label>
              <select value={form.brokerAccountId} onChange={(e) => setForm({ ...form, brokerAccountId: e.target.value })}
                className="w-full px-3 py-2 border rounded-lg">
                <option value="">Select broker...</option>
                {brokers?.map((b) => <option key={b.id} value={b.id}>{b.brokerName} ({b.clientId})</option>)}
              </select>
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Mode</label>
              <select value={form.mode} onChange={(e) => setForm({ ...form, mode: e.target.value })}
                className="w-full px-3 py-2 border rounded-lg">
                <option value="PAPER">Paper Trading</option>
                <option value="LIVE">Live Trading</option>
              </select>
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Capital (&#8377;)</label>
              <input type="number" value={form.capital} onChange={(e) => setForm({ ...form, capital: Number(e.target.value) })}
                className="w-full px-3 py-2 border rounded-lg" />
            </div>
          </div>
          <div className="flex gap-3 mt-4">
            <button onClick={() => deployMutation.mutate(form)} disabled={deployMutation.isPending || !form.strategyId}
              className="bg-green-600 text-white px-6 py-2 rounded-lg hover:bg-green-700 disabled:opacity-50 text-sm transition">
              {deployMutation.isPending ? 'Deploying...' : 'Deploy'}
            </button>
            <button onClick={() => setShowForm(false)} className="text-gray-600 px-4 py-2 text-sm hover:text-gray-800">Cancel</button>
          </div>
        </div>
      )}

      {/* Deployment List */}
      {isLoading ? <p className="text-gray-500">Loading...</p> : (
        <div className="bg-white rounded-lg shadow overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-gray-50">
              <tr className="text-left text-gray-500">
                <th className="p-4">Strategy</th>
                <th className="p-4">Broker</th>
                <th className="p-4">Mode</th>
                <th className="p-4">Capital</th>
                <th className="p-4">Status</th>
                <th className="p-4">Created</th>
                <th className="p-4">Actions</th>
              </tr>
            </thead>
            <tbody>
              {deployments?.length === 0 && (
                <tr><td colSpan="7" className="p-8 text-center text-gray-400">No deployments yet</td></tr>
              )}
              {deployments?.map((d) => (
                <tr key={d.id} className="border-t">
                  <td className="p-4 font-medium">{d.strategyName || `Strategy #${d.strategyId}`}</td>
                  <td className="p-4">{d.brokerName || `Broker #${d.brokerAccountId}`}</td>
                  <td className="p-4">
                    <span className={`px-2 py-1 rounded text-xs font-medium ${d.mode === 'LIVE' ? 'bg-red-100 text-red-700' : 'bg-yellow-100 text-yellow-700'}`}>{d.mode}</span>
                  </td>
                  <td className="p-4">&#8377;{d.capital?.toLocaleString()}</td>
                  <td className="p-4">
                    <span className={`px-2 py-1 rounded text-xs font-medium ${d.status === 'ACTIVE' ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600'}`}>{d.status}</span>
                  </td>
                  <td className="p-4 text-gray-500">{new Date(d.createdAt).toLocaleDateString()}</td>
                  <td className="p-4">
                    {d.status === 'ACTIVE' && (
                      <button onClick={() => stopMutation.mutate(d.id)} className="text-red-600 hover:text-red-800 text-xs font-medium">Stop</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
