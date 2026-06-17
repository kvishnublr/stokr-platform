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

  if (isLoading) return <LoadingSkeleton />;

  return (
    <div>
      {/* Header */}
      <div className="flex justify-between items-center mb-8 animate-fade-in-up">
        <div>
          <div className="flex items-center gap-3 mb-1">
            <div className="w-1 h-7 rounded-full bg-gradient-to-b from-indigo-500 to-violet-500" />
            <h1 className="text-3xl font-bold text-slate-900 tracking-tight">Strategy Universe Mappings</h1>
          </div>
          <p className="text-slate-400 text-sm ml-4">Link strategies to symbol universe groups</p>
        </div>
        <button onClick={() => setShowCreate(true)} className="btn-shimmer bg-gradient-to-r from-indigo-600 to-violet-600 text-white px-5 py-2.5 rounded-xl text-sm font-medium transition-all duration-200 hover:shadow-lg hover:shadow-indigo-500/20 hover:scale-[1.02]">
          + Create Mapping
        </button>
      </div>

      {/* Create Form */}
      {showCreate && (
        <div className="card-crystal p-6 mb-6 animate-scale-in border-l-4 border-l-indigo-500">
          <h3 className="font-semibold text-slate-800 mb-4 text-base flex items-center gap-2">
            <div className="w-2 h-2 rounded-full bg-indigo-500" />
            Map Strategy to Universe
          </h3>
          <div className="grid grid-cols-1 md:grid-cols-5 gap-3 mb-4">
            <div>
              <label className="text-xs font-medium text-slate-500 mb-1.5 block">Strategy</label>
              <select value={form.strategyId} onChange={e => setForm({...form, strategyId: e.target.value})} className="w-full bg-white border border-slate-200 rounded-xl px-4 py-2.5 text-sm text-slate-700 focus:outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100 transition-all input-crystal">
                <option value="">Select Strategy</option>
                {strategies?.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </div>
            <div>
              <label className="text-xs font-medium text-slate-500 mb-1.5 block">Universe Group</label>
              <select value={form.universeGroupId} onChange={e => setForm({...form, universeGroupId: e.target.value})} className="w-full bg-white border border-slate-200 rounded-xl px-4 py-2.5 text-sm text-slate-700 focus:outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100 transition-all input-crystal">
                <option value="">Select Group</option>
                {groups?.map(g => <option key={g.id} value={g.id}>{g.displayName}</option>)}
              </select>
            </div>
            <div>
              <label className="text-xs font-medium text-slate-500 mb-1.5 block">Max Positions</label>
              <input type="number" value={form.maxPositions} onChange={e => setForm({...form, maxPositions: parseInt(e.target.value)})} className="w-full bg-white border border-slate-200 rounded-xl px-4 py-2.5 text-sm text-slate-700 focus:outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100 transition-all input-crystal" />
            </div>
            <div>
              <label className="text-xs font-medium text-slate-500 mb-1.5 block">Scan Interval (s)</label>
              <input type="number" value={form.scanIntervalSeconds} onChange={e => setForm({...form, scanIntervalSeconds: parseInt(e.target.value)})} className="w-full bg-white border border-slate-200 rounded-xl px-4 py-2.5 text-sm text-slate-700 focus:outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100 transition-all input-crystal" />
            </div>
            <div>
              <label className="text-xs font-medium text-slate-500 mb-1.5 block">Risk Profile</label>
              <select value={form.riskProfile} onChange={e => setForm({...form, riskProfile: e.target.value})} className="w-full bg-white border border-slate-200 rounded-xl px-4 py-2.5 text-sm text-slate-700 focus:outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100 transition-all input-crystal">
                <option value="LOW">Low</option>
                <option value="MEDIUM">Medium</option>
                <option value="HIGH">High</option>
              </select>
            </div>
          </div>
          <div className="flex gap-3">
            <button onClick={() => createMutation.mutate({ ...form, strategyId: Number(form.strategyId), universeGroupId: Number(form.universeGroupId) })} className="btn-shimmer bg-gradient-to-r from-indigo-600 to-violet-600 text-white px-5 py-2.5 rounded-xl text-sm font-medium transition-all hover:shadow-lg hover:shadow-indigo-500/20">Create</button>
            <button onClick={() => setShowCreate(false)} className="px-5 py-2.5 rounded-xl text-sm text-slate-500 hover:text-slate-700 hover:bg-slate-50 transition-all">Cancel</button>
          </div>
        </div>
      )}

      {/* Table */}
      <div className="card-crystal overflow-hidden animate-fade-in-up delay-100">
        <div className="overflow-x-auto">
          <table className="w-full text-sm table-crystal">
            <thead>
              <tr>
                <th>Strategy</th>
                <th>Universe Group</th>
                <th>Max Pos</th>
                <th>Scan (s)</th>
                <th>Risk</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {mappings?.length === 0 && (
                <tr><td colSpan="7" className="p-16 text-center text-slate-400">
                  <div className="flex flex-col items-center gap-4">
                    <div className="w-14 h-14 rounded-2xl bg-slate-100 flex items-center justify-center">
                      <svg className="w-7 h-7 text-slate-300" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1}><path strokeLinecap="round" strokeLinejoin="round" d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1" /></svg>
                    </div>
                    <p className="text-sm">No strategy mappings yet</p>
                  </div>
                </td></tr>
              )}
              {mappings?.map((m, i) => (
                <tr key={m.id} className="animate-fade-in-up" style={{ animationDelay: `${i * 50}ms` }}>
                  <td className="font-medium text-slate-800">{getStrategyName(m.strategyId)}</td>
                  <td className="text-slate-600">{getGroupName(m.universeGroupId)}</td>
                  <td className="text-slate-600">{m.maxPositions}</td>
                  <td className="text-slate-600">{m.scanIntervalSeconds}</td>
                  <td>
                    <span className={`px-2 py-1 rounded-lg text-xs font-medium border ${
                      m.riskProfile === 'HIGH' ? 'bg-rose-50 text-rose-700 border-rose-200' :
                      m.riskProfile === 'LOW' ? 'bg-emerald-50 text-emerald-700 border-emerald-200' :
                      'bg-amber-50 text-amber-700 border-amber-200'
                    }`}>
                      {m.riskProfile}
                    </span>
                  </td>
                  <td>
                    <button onClick={() => updateMutation.mutate({ id: m.id, body: { runtimeEnabled: !m.runtimeEnabled } })} className={`px-3 py-1.5 rounded-lg text-xs font-medium border transition-all ${
                      m.runtimeEnabled
                        ? 'bg-emerald-50 text-emerald-700 border-emerald-200 hover:bg-emerald-100'
                        : 'bg-slate-100 text-slate-500 border-slate-200 hover:bg-slate-200'
                    }`}>
                      {m.runtimeEnabled ? 'Active' : 'Inactive'}
                    </button>
                  </td>
                  <td>
                    <button onClick={() => { if (confirm('Delete this mapping?')) deleteMutation.mutate(m.id); }} className="text-rose-500/70 hover:text-rose-600 text-xs font-medium transition-colors px-2 py-1 rounded-lg hover:bg-rose-50">Delete</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

function LoadingSkeleton() {
  return (
    <div className="space-y-3">
      <div className="flex justify-between mb-8">
        <div className="skeleton w-60 h-10" />
        <div className="skeleton w-28 h-10" />
      </div>
      <div className="card-crystal p-0 overflow-hidden">
        {[1,2,3].map(i => (
          <div key={i} className="p-5 border-b border-slate-100">
            <div className="skeleton w-full h-10" />
          </div>
        ))}
      </div>
    </div>
  );
}
