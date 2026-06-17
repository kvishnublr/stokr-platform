import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import client from '../api/client';

export default function Deployments() {
  const queryClient = useQueryClient();
  const [showForm, setShowForm] = useState(false);

  const { data: deployments, isLoading } = useQuery({ queryKey: ['deployments'], queryFn: () => client.get('/deployments').then((r) => r.data) });
  const { data: strategies } = useQuery({ queryKey: ['strategies'], queryFn: () => client.get('/strategies/enabled').then((r) => r.data) });
  const { data: brokers } = useQuery({ queryKey: ['brokers'], queryFn: () => client.get('/brokers').then((r) => r.data) });

  const deployMutation = useMutation({
    mutationFn: (payload) => client.post('/deployments', payload),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['deployments'] }); setShowForm(false); },
  });
  const stopMutation = useMutation({
    mutationFn: (id) => client.delete(`/deployments/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['deployments'] }),
  });

  const [form, setForm] = useState({ strategyId: '', brokerAccountId: '', mode: 'PAPER', capital: 100000 });

  const inputCls = 'w-full px-4 py-2.5 bg-white border border-slate-200 rounded-xl text-slate-800 focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition outline-none text-sm input-crystal';

  return (
    <div>
      <div className="flex items-center justify-between mb-8 animate-fade-in-up">
        <div>
          <div className="flex items-center gap-3 mb-1">
            <div className="w-1 h-7 rounded-full bg-gradient-to-b from-indigo-500 to-violet-500" />
            <h1 className="text-3xl font-bold text-slate-900 tracking-tight">Deployments</h1>
          </div>
          <p className="text-slate-400 text-sm ml-4">Deploy and manage your trading strategies</p>
        </div>
        <button onClick={() => setShowForm(!showForm)}
          className="btn-shimmer bg-gradient-to-r from-indigo-600 to-violet-600 text-white px-5 py-2.5 rounded-xl text-sm font-medium hover:from-indigo-700 hover:to-violet-700 transition shadow-lg shadow-indigo-500/20 flex items-center gap-2">
          <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}><path strokeLinecap="round" strokeLinejoin="round" d="M12 4v16m8-8H4" /></svg>
          New Deployment
        </button>
      </div>

      {/* Deploy Form */}
      {showForm && (
        <div className="card-crystal p-6 mb-6 animate-scale-in">
          <h2 className="text-base font-semibold text-slate-800 mb-4">Deploy Strategy</h2>
          {deployMutation.isError && (
            <div className="bg-rose-50 border border-rose-200 text-rose-600 text-sm p-3.5 rounded-xl mb-5 flex items-center gap-2">
              <svg className="w-4 h-4 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}><path strokeLinecap="round" strokeLinejoin="round" d="M12 9v2m0 4h.01" /></svg>
              {deployMutation.error?.response?.data?.message || deployMutation.error?.response?.data?.error || 'Deploy failed'}
            </div>
          )}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1.5">Strategy</label>
              <select value={form.strategyId} onChange={(e) => setForm({ ...form, strategyId: e.target.value })} className={inputCls}>
                <option value="">Select strategy...</option>
                {strategies?.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </div>
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1.5">Broker Account</label>
              <select value={form.brokerAccountId} onChange={(e) => setForm({ ...form, brokerAccountId: e.target.value })} className={inputCls}>
                <option value="">Select broker...</option>
                {brokers?.map((b) => <option key={b.id} value={b.id}>{b.brokerName} ({b.clientId})</option>)}
              </select>
              {(!brokers || brokers.length === 0) && <p className="text-xs text-amber-600 mt-1">No brokers connected. <a href="/brokers" className="underline">Connect one first</a></p>}
            </div>
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1.5">Mode</label>
              <select value={form.mode} onChange={(e) => setForm({ ...form, mode: e.target.value })} className={inputCls}>
                <option value="PAPER">Paper Trading</option>
                <option value="LIVE">Live Trading</option>
              </select>
            </div>
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1.5">Capital (₹)</label>
              <input type="number" value={form.capital} onChange={(e) => setForm({ ...form, capital: Number(e.target.value) })} className={inputCls} />
            </div>
          </div>
          <div className="flex gap-3 mt-5">
            <button onClick={() => deployMutation.mutate(form)} disabled={deployMutation.isPending || !form.strategyId}
              className="bg-gradient-to-r from-emerald-500 to-teal-600 text-white px-5 py-2.5 rounded-xl text-sm font-medium hover:from-emerald-600 hover:to-teal-700 disabled:opacity-50 transition shadow-lg shadow-emerald-500/20">
              {deployMutation.isPending ? 'Deploying...' : 'Deploy'}
            </button>
            <button onClick={() => setShowForm(false)} className="text-slate-500 px-4 py-2.5 text-sm hover:text-slate-700 font-medium transition">Cancel</button>
          </div>
        </div>
      )}

      {/* Deployment List */}
      {isLoading ? <p className="text-slate-500">Loading...</p> : (
        <div className="card-crystal overflow-hidden">
          <table className="w-full text-sm table-crystal">
            <thead>
              <tr>
                <th>Strategy</th>
                <th>Broker</th>
                <th>Mode</th>
                <th>Capital</th>
                <th>Status</th>
                <th>Created</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {deployments?.length === 0 && (
                <tr><td colSpan="7" className="p-12 text-center text-slate-400">
                  <div className="flex flex-col items-center">
                    <svg className="w-10 h-10 text-slate-300 mb-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1}><path strokeLinecap="round" strokeLinejoin="round" d="M13 10V3L4 14h7v7l9-11h-7z" /></svg>
                    <p>No deployments yet</p>
                    <button onClick={() => setShowForm(true)} className="text-indigo-600 text-sm font-medium mt-2 hover:text-indigo-700">Create your first deployment</button>
                  </div>
                </td></tr>
              )}
              {deployments?.map((d) => (
                <tr key={d.id}>
                  <td className="font-medium text-slate-800">{d.strategyName || `Strategy #${d.strategyId}`}</td>
                  <td className="text-slate-600">{d.brokerName || `Broker #${d.brokerAccountId}`}</td>
                  <td>
                    <span className={`px-2 py-1 rounded-full text-xs font-semibold ${d.mode === 'LIVE' ? 'bg-rose-50 text-rose-600 border border-rose-200' : 'bg-amber-50 text-amber-600 border border-amber-200'}`}>{d.mode}</span>
                  </td>
                  <td className="text-slate-700 font-medium">₹{d.capital?.toLocaleString()}</td>
                  <td>
                    <span className={`px-2 py-1 rounded-full text-xs font-semibold ${d.status === 'ACTIVE' ? 'bg-emerald-50 text-emerald-600 border border-emerald-200' : 'bg-slate-100 text-slate-500 border border-slate-200'}`}>{d.status}</span>
                  </td>
                  <td className="text-slate-500 text-xs">{new Date(d.createdAt).toLocaleDateString()}</td>
                  <td>
                    {d.status === 'ACTIVE' && (
                      <button onClick={() => stopMutation.mutate(d.id)} className="text-rose-500 hover:text-rose-700 text-xs font-semibold transition">Stop</button>
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
