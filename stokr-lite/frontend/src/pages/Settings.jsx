import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import client from '../api/client';

export default function Settings() {
  const queryClient = useQueryClient();
  const { data: profile, isLoading } = useQuery({
    queryKey: ['profile'],
    queryFn: () => client.get('/profile').then((r) => r.data),
  });

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

  if (isLoading) return <div className="text-gray-500">Loading...</div>;

  return (
    <div>
      <h1 className="text-2xl font-bold mb-6">Settings</h1>

      <div className="bg-white rounded-lg shadow p-6 max-w-2xl">
        <h2 className="text-lg font-semibold mb-4">Profile</h2>
        <div className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Name</label>
            <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })}
              className="w-full px-3 py-2 border rounded-lg" />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Phone</label>
            <input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })}
              className="w-full px-3 py-2 border rounded-lg" />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Total Capital (₹)</label>
            <input type="number" value={form.totalCapital} onChange={(e) => setForm({ ...form, totalCapital: Number(e.target.value) })}
              className="w-full px-3 py-2 border rounded-lg" />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Risk Profile</label>
            <select value={form.riskProfile} onChange={(e) => setForm({ ...form, riskProfile: e.target.value })}
              className="w-full px-3 py-2 border rounded-lg">
              <option value="CONSERVATIVE">Conservative</option>
              <option value="MODERATE">Moderate</option>
              <option value="AGGRESSIVE">Aggressive</option>
            </select>
          </div>
          <button onClick={() => updateMutation.mutate(form)} disabled={updateMutation.isPending}
            className="bg-blue-600 text-white px-6 py-2 rounded-lg hover:bg-blue-700 disabled:opacity-50 transition">
            {updateMutation.isPending ? 'Saving...' : 'Save Changes'}
          </button>
        </div>
      </div>
    </div>
  );
}
