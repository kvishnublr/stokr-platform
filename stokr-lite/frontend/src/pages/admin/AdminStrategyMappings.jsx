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
          <h1 className="text-3xl font-bold text-slate-800 tracking-tight">Strategy Universe Mappings</h1>
          <p className="text-slate-500 text-sm mt-2">Link strategies to symbol universe groups</p>
        </div>
        <button onClick={() => setShowCreate(true)} className="btn-shimmer bg-gradient-to-r from-indigo-600 to-violet-600 text-white px-5 py-2.5 rounded-xl text-sm font-medium transition-all hover:shadow-lg hover:shadow-indigo-500/25 hover:scale-[1.02]">
          + Create Mapping
        </button>
      </div>

      {/* Create Form */}
      {showCreate && (
        <div className="card-light-strong rounded-2xl p-6 mb-6 animate-scale-in border-indigo-200/50">
          <h3 className="font-semibold text-slate-800 mb-4 flex items-center gap-2">
            <div className="w-2 h-2 rounded-full bg-indigo-500 shadow-[0_0_8px_rgba(99,102,241,0.4)]" />
            Map Strategy to Universe
          </h3>
          <div className="grid grid-cols-1 md:grid-cols-5 gap-4 mb-4">
            <div>
              <label className="text-xs text-slate-500 mb-1 block">Strategy</label>
              <select value={form.strategyId} onChange={e => setForm({...form, strategyId: e.target.value})} className="w-full bg-white border border-slate-200 rounded-xl px-4 py-2.5 text-sm text-slate-700 focus:outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100 transition-all input-light">
                <option value="">Select Strategy</option>
                {strategies?.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </div>
            <div>
              <label className="text-xs text-slate-500 mb-1 block">Universe Group</label>
              <select value={form.universeGroupId} onChange={e => setForm({...form, universeGroupId: e.target.value})} className="w-full bg-white border border-slate-200 rounded-xl px-4 py-2.5 text-sm text-slate-700 focus:outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100 transition-all input-light">
                <option value="">Select Group</option>
                {groups?.map(g => <option key={g.id} value={g.id}>{g.displayName}</option>)}
              </select>
            </div>
            <div>
              <label className="text-xs text-slate-500 mb-1 block">Max Positions</label>
              <input type="number" value={form.maxPositions} onChange={e => setForm({...form, maxPositions: parseInt(e.target.value)})} className="w-full bg-white border border-slate-200 rounded-xl px-4 py-2.5 text-sm text-slate-700 focus:outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100 transition-all input-light" />
            </div>
            <div>
              <label className="text-xs text-slate-500 mb-1 block">Scan Interval (s)</label>
              <input type="number" value={form.scanIntervalSeconds} onChange={e => setForm({...form, scanIntervalSeconds: parseInt(e.target.value)})} className="w-full bg-white border border-slate-200 rounded-xl px-4 py-2.5 text-sm text-slate-700 focus:outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100 transition-all input-light" />
            </div>
            <div>
              <label className="text-xs text-slate-500 mb-1 block">Risk Profile</label>
              <select value={form.riskProfile} onChange={e => setForm({...form, riskProfile: e.target.value})} className="w-full bg-white border border-slate-200 rounded-xl px-4 py-2.5 text-sm text-slate-700 focus:outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100 transition-all input-light">
                <option value="LOW">Low</option>
                <option value="MEDIUM">Medium</option>
                <option value="HIGH">High</option>
              </select>
            </div>
          </div>
          <div className="flex gap-3">
            <button onClick={() => createMutation.mutate({ ...form, strategyId: Number(form.strategyId), universeGroupId: Number(form.universeGroupId) })} className="btn-shimmer bg-gradient-to-r from-indigo-600 to-violet-600 text-white px-5 py-2 rounded-xl text-sm font-medium transition-all hover:shadow-lg hover:shadow-indigo-500/25">Create</button>
            <button onClick={() => setShowCreate(false)} className="px-5 py-2 rounded-xl text-sm text-slate-500 hover:text-slate-700 hover:bg-slate-100 transition-all">Cancel</button>
          </div>
        </div>
      )}

      {/* Table */}
      <div className="card-light overflow-hidden animate-fade-in-up delay-100">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-slate-500 border-b border-slate-100">
                <th className="p-4 font-medium text-xs uppercase tracking-wider">Strategy</th>
                <th className="p-4 font-medium text-xs uppercase tracking-wider">Universe Group</th>
                <th className="p-4 font-medium text-xs uppercase tracking-wider">Max Pos</th>
                <th className="p-4 font-medium text-xs uppercase tracking-wider">Scan (s)</th>
                <th className="p-4 font-medium text-xs uppercase tracking-wider">Risk</th>
                <th className="p-4 font-medium text-xs uppercase tracking-wider">Status</th>
                <th className="p-4 font-medium text-xs uppercase tracking-wider">Actions</th>
              </tr>
            </thead>
            <tbody>
              {mappings?.length === 0 && (
                <tr><td colSpan="7" className="p-12 text-center text-slate-400">
                  <div className="flex flex-col items-center gap-3">
                    <svg className="w-10 h-10 text-slate-300" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1}><path strokeLinecap="round" strokeLinejoin="round" d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1" /></svg>
                    <p>No strategy mappings yet</p>
                  </div>
                </td></tr>
              )}
              {mappings?.map((m, i) => (
                <tr key={m.id} className="border-t border-slate-100 hover:bg-slate-50/80 transition-colors animate-fade-in-up" style={{ animationDelay: `${i * 50}ms` }}>
                  <td className="p-4 font-medium text-slate-700">{getStrategyName(m.strategyId)}</td>
                  <td className="p-4 text-slate-600">{getGroupName(m.universeGroupId)}</td>
                  <td className="p-4 text-slate-600">{m.maxPositions}</td>
                  <td className="p-4 text-slate-600">{m.scanIntervalSeconds}</td>
                  <td className="p-4">
                    <span className={`px-2.5 py-1 rounded-lg text-xs font-medium border ${
                      m.riskProfile === 'HIGH' ? 'bg-rose-50 text-rose-700 border-rose-200' :
                      m.riskProfile === 'LOW' ? 'bg-emerald-50 text-emerald-700 border-emerald-200' :
                      'bg-amber-50 text-amber-700 border-amber-200'
                    }`}>
                      {m.riskProfile}
                    </span>
                  </td>
                  <td className="p-4">
                    <button onClick={() => updateMutation.mutate({ id: m.id, body: { runtimeEnabled: !m.runtimeEnabled } })} className={`px-3 py-1 rounded-lg text-xs font-medium border transition-all ${
                      m.runtimeEnabled
                        ? 'bg-emerald-50 text-emerald-700 border-emerald-200 hover:bg-emerald-100'
                        : 'bg-slate-100 text-slate-500 border-slate-200 hover:bg-slate-200'
                    }`}>
                      {m.runtimeEnabled ? 'Active' : 'Inactive'}
                    </button>
                  </td>
                  <td className="p-4">
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
    <div className="space-y-4">
      <div className="flex justify-between mb-8">
        <div className="skeleton w-72 h-10" />
        <div className="skeleton w-36 h-10" />
      </div>
      <div className="card-light p-0 overflow-hidden">
        {[1,2,3].map(i => (
          <div key={i} className="p-4 border-b border-slate-100">
            <div className="skeleton w-full h-10" />
          </div>
        ))}
      </div>
    </div>
  );
}
