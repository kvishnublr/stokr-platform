import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import client from '../api/client';

export default function Settings() {
  const queryClient = useQueryClient();
  const { data: profile, isLoading } = useQuery({ queryKey: ['profile'], queryFn: () => client.get('/profile').then((r) => r.data) });
  const [form, setForm] = useState({ name: '', phone: '', totalCapital: '', riskProfile: 'MODERATE' });
  const [initialized, setInitialized] = useState(false);

  if (profile && !initialized) {
    setForm({ name: profile.name || '', phone: profile.phone || '', totalCapital: profile.totalCapital || '', riskProfile: profile.riskProfile || 'MODERATE' });
    setInitialized(true);
  }

  const updateMutation = useMutation({
    mutationFn: (data) => client.put('/profile', data),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['profile'] }); alert('Profile updated!'); },
  });

  if (isLoading) return <div className="text-slate-500">Loading...</div>;

  const inputCls = 'w-full px-4 py-2.5 bg-white border border-slate-200 rounded-xl text-slate-800 focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition outline-none text-sm';

  return (
    <div>
      <div className="mb-8"><h1 className="text-2xl font-bold text-slate-800">Settings</h1><p className="text-slate-500 text-sm mt-1">Manage your profile and preferences</p></div>
      <div className="bg-white rounded-2xl border border-slate-200/60 shadow-sm p-6 max-w-2xl">
        <h2 className="text-lg font-semibold text-slate-800 mb-5">Profile</h2>
        <div className="space-y-5">
          <div><label className="block text-sm font-medium text-slate-700 mb-1.5">Name</label><input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} className={inputCls} /></div>
          <div><label className="block text-sm font-medium text-slate-700 mb-1.5">Phone</label><input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} className={inputCls} /></div>
          <div><label className="block text-sm font-medium text-slate-700 mb-1.5">Total Capital (₹)</label><input type="number" value={form.totalCapital} onChange={(e) => setForm({ ...form, totalCapital: Number(e.target.value) })} className={inputCls} /></div>
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1.5">Risk Profile</label>
            <select value={form.riskProfile} onChange={(e) => setForm({ ...form, riskProfile: e.target.value })} className={inputCls}>
              <option value="CONSERVATIVE">Conservative</option><option value="MODERATE">Moderate</option><option value="AGGRESSIVE">Aggressive</option>
            </select>
          </div>
          <button onClick={() => updateMutation.mutate(form)} disabled={updateMutation.isPending}
            className="bg-gradient-to-r from-indigo-600 to-violet-600 text-white px-6 py-2.5 rounded-xl text-sm font-medium hover:from-indigo-700 hover:to-violet-700 disabled:opacity-50 transition shadow-lg shadow-indigo-500/20">
            {updateMutation.isPending ? 'Saving...' : 'Save Changes'}
          </button>
        </div>
      </div>
    </div>
  );
}
