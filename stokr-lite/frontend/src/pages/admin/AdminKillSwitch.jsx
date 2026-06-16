import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import client from '../../api/client';

export default function AdminKillSwitch() {
  const queryClient = useQueryClient();
  const [reason, setReason] = useState('');

  const { data: status } = useQuery({
    queryKey: ['kill-switch'],
    queryFn: () => client.get('/admin/kill-switch').then((r) => r.data),
  });

  const activateMutation = useMutation({
    mutationFn: () => client.post('/admin/kill-switch/activate', { reason }),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['kill-switch'] }); setReason(''); },
  });

  const deactivateMutation = useMutation({
    mutationFn: () => client.post('/admin/kill-switch/deactivate'),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['kill-switch'] }),
  });

  return (
    <div>
      <h1 className="text-2xl font-bold mb-6">Kill Switch</h1>

      <div className="bg-white rounded-lg shadow p-8 max-w-lg">
        {/* Current Status */}
        <div className="text-center mb-8">
          <div className={`w-32 h-32 rounded-full mx-auto flex items-center justify-center ${status?.active ? 'bg-red-100' : 'bg-green-100'}`}>
            <span className="text-4xl">{status?.active ? '🛑' : '✅'}</span>
          </div>
          <h2 className={`text-xl font-bold mt-4 ${status?.active ? 'text-red-600' : 'text-green-600'}`}>
            {status?.active ? 'KILL SWITCH ACTIVE' : 'ALL SYSTEMS NORMAL'}
          </h2>
          {status?.active && status.reason && (
            <p className="text-sm text-gray-500 mt-2">Reason: {status.reason}</p>
          )}
          {status?.active && status.activatedBy && (
            <p className="text-sm text-gray-500">Activated by: {status.activatedBy}</p>
          )}
        </div>

        {/* Controls */}
        {!status?.active ? (
          <div>
            <div className="mb-4">
              <label className="block text-sm font-medium text-gray-700 mb-1">Reason for activation</label>
              <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={3} placeholder="Describe why you're activating the kill switch..."
                className="w-full px-3 py-2 border rounded-lg" />
            </div>
            <button onClick={() => activateMutation.mutate()} disabled={!reason.trim() || activateMutation.isPending}
              className="w-full bg-red-600 text-white py-3 rounded-lg hover:bg-red-700 disabled:opacity-50 font-semibold transition">
              ACTIVATE KILL SWITCH
            </button>
          </div>
        ) : (
          <button onClick={() => deactivateMutation.mutate()} disabled={deactivateMutation.isPending}
            className="w-full bg-green-600 text-white py-3 rounded-lg hover:bg-green-700 font-semibold transition">
            DEACTIVATE KILL SWITCH
          </button>
        )}
      </div>
    </div>
  );
}
