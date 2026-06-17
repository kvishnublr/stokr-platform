import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import client from '../../api/client';

export default function AdminStrategyMappings() {
  const queryClient = useQueryClient();
  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState({ strategyId: '', universeGroupId: '', maxPositions: 2, scanIntervalSeconds: 60, riskProfile: 'MEDIUM' });

  const { data: mappings, isLoading } = useQuery({
    queryKey: ['strategy-mappings'],
    queryFn: () => client.get('/admin/strategy-mappings').then((r) => r.data),
  });

  const { data: strategies } = useQuery({
    queryKey: ['strategies'],
    queryFn: () => client.get('/strategies').then((r) => r.data),
  });

  const { data: groups } = useQuery({
    queryKey: ['universe-groups'],
    queryFn: () => client.get('/universe-groups').then((r) => r.data),
  });

  const createMutation = useMutation({
    mutationFn: (body) => client.post('/admin/strategy-mappings', body),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['strategy-mappings'] }); setShowCreate(false); setForm({ strategyId: '', universeGroupId: '', maxPositions: 2, scanIntervalSeconds: 60, riskProfile: 'MEDIUM' }); },
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, body }) => client.patch(`/admin/strategy-mappings/${id}`, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['strategy-mappings'] }),
  });

  const deleteMutation = useMutation({
    mutationFn: (id) => client.delete(`/admin/strategy-mappings/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['strategy-mappings'] }),
  });

  const getStrategyName = (id) => strategies?.find(s => s.id === id)?.name || `#${id}`;
  const getGroupName = (id) => groups?.find(g => g.id === id)?.displayName || `#${id}`;

  if (isLoading) return <div className="text-gray-500">Loading...</div>;

  return (
    <div>
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold">Strategy Universe Mappings</h1>
        <button onClick={() => setShowCreate(true)} className="bg-indigo-600 text-white px-4 py-2 rounded-lg hover:bg-indigo-700 text-sm transition">Create Mapping</button>
      </div>

      {showCreate && (
        <div className="bg-white rounded-lg shadow p-5 mb-6 border border-slate-200">
          <h3 className="font-semibold mb-3">Map Strategy to Universe Group</h3>
          <div className="grid grid-cols-1 md:grid-cols-5 gap-3 mb-3">
            <select value={form.strategyId} onChange={e => setForm({...form, strategyId: e.target.value})} className="border rounded px-3 py-2 text-sm">
              <option value="">Select Strategy</option>
              {strategies?.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
            <select value={form.universeGroupId} onChange={e => setForm({...form, universeGroupId: e.target.value})} className="border rounded px-3 py-2 text-sm">
              <option value="">Select Universe Group</option>
              {groups?.map(g => <option key={g.id} value={g.id}>{g.displayName}</option>)}
            </select>
            <input type="number" placeholder="Max Positions" value={form.maxPositions} onChange={e => setForm({...form, maxPositions: parseInt(e.target.value)})} className="border rounded px-3 py-2 text-sm" />
            <input type="number" placeholder="Scan Interval (sec)" value={form.scanIntervalSeconds} onChange={e => setForm({...form, scanIntervalSeconds: parseInt(e.target.value)})} className="border rounded px-3 py-2 text-sm" />
            <select value={form.riskProfile} onChange={e => setForm({...form, riskProfile: e.target.value})} className="border rounded px-3 py-2 text-sm">
              <option value="LOW">Low</option>
              <option value="MEDIUM">Medium</option>
              <option value="HIGH">High</option>
            </select>
          </div>
          <div className="flex gap-2">
            <button onClick={() => createMutation.mutate({ ...form, strategyId: Number(form.strategyId), universeGroupId: Number(form.universeGroupId) })} className="bg-indigo-600 text-white px-3 py-1.5 rounded text-sm hover:bg-indigo-700">Create</button>
            <button onClick={() => setShowCreate(false)} className="text-gray-500 px-3 py-1.5 text-sm">Cancel</button>
          </div>
        </div>
      )}

      <div className="bg-white rounded-lg shadow overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50">
            <tr className="text-left text-gray-500">
              <th className="p-4">Strategy</th>
              <th className="p-4">Universe Group</th>
              <th className="p-4">Max Pos</th>
              <th className="p-4">Scan (s)</th>
              <th className="p-4">Risk</th>
              <th className="p-4">Status</th>
              <th className="p-4">Actions</th>
            </tr>
          </thead>
          <tbody>
            {mappings?.length === 0 && (
              <tr><td colSpan="7" className="p-8 text-center text-gray-400">No mappings</td></tr>
            )}
            {mappings?.map((m) => (
              <tr key={m.id} className="border-t">
                <td className="p-4 font-medium">{getStrategyName(m.strategyId)}</td>
                <td className="p-4">{getGroupName(m.universeGroupId)}</td>
                <td className="p-4">{m.maxPositions}</td>
                <td className="p-4">{m.scanIntervalSeconds}</td>
                <td className="p-4"><span className={`px-2 py-0.5 rounded text-xs ${m.riskProfile === 'HIGH' ? 'bg-red-100 text-red-700' : m.riskProfile === 'LOW' ? 'bg-green-100 text-green-700' : 'bg-yellow-100 text-yellow-700'}`}>{m.riskProfile}</span></td>
                <td className="p-4">
                  <button onClick={() => updateMutation.mutate({ id: m.id, body: { runtimeEnabled: !m.runtimeEnabled } })} className={`px-2 py-1 rounded text-xs ${m.runtimeEnabled ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600'}`}>
                    {m.runtimeEnabled ? 'Active' : 'Inactive'}
                  </button>
                </td>
                <td className="p-4">
                  <button onClick={() => { if (confirm('Delete this mapping?')) deleteMutation.mutate(m.id); }} className="text-red-600 hover:text-red-800 text-xs font-medium">Delete</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
