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
    <div className="animate-fade-in-up">
      <div className="flex items-center gap-3 mb-6">
        <div className="w-1 h-7 rounded-full bg-gradient-to-b from-rose-500 to-orange-500" />
        <h1 className="text-3xl font-bold text-slate-800">Kill Switch</h1>
      </div>

      <div className="card-crystal p-8 max-w-lg mx-auto">
        {/* Status Indicator */}
        <div className="text-center mb-8">
          <div className={`w-32 h-32 rounded-full mx-auto flex items-center justify-center transition-all duration-500 ${
            status?.active
              ? 'bg-rose-50 shadow-lg shadow-rose-500/10'
              : 'bg-emerald-50 shadow-lg shadow-emerald-500/10'
          }`}>
            <div className={`w-16 h-16 rounded-full flex items-center justify-center ${
              status?.active ? 'bg-rose-100' : 'bg-emerald-100'
            }`}>
              <svg className={`w-8 h-8 ${status?.active ? 'text-rose-600' : 'text-emerald-600'}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                {status?.active ? (
                  <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.03 3.855c-.768-1.333-2.24-1.333-3.008 0L2.697 16.124zM12 15.75a.75.75 0 100-1.5.75.75 0 000 1.5z" />
                ) : (
                  <path strokeLinecap="round" strokeLinejoin="round" d="M9 12.75L11.25 15 15 9.75M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                )}
              </svg>
            </div>
          </div>
          <h2 className={`text-xl font-bold mt-4 ${status?.active ? 'text-rose-600' : 'text-emerald-600'}`}>
            {status?.active ? 'KILL SWITCH ACTIVE' : 'ALL SYSTEMS NORMAL'}
          </h2>
          {status?.active && status.reason && (
            <p className="text-sm text-slate-500 mt-2">Reason: {status.reason}</p>
          )}
          {status?.active && status.activatedBy && (
            <p className="text-sm text-slate-500">Activated by: {status.activatedBy}</p>
          )}
        </div>

        {/* Controls */}
        {!status?.active ? (
          <div className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1.5">Reason for activation</label>
              <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={3} placeholder="Describe why you're activating the kill switch..."
                className="w-full px-4 py-3 bg-white border border-slate-200 rounded-xl text-slate-800 placeholder-slate-400 focus:ring-2 focus:ring-rose-500/20 focus:border-rose-500 transition outline-none resize-none input-crystal" />
            </div>
            <button onClick={() => activateMutation.mutate()} disabled={!reason.trim() || activateMutation.isPending}
              className="w-full bg-gradient-to-r from-rose-600 to-orange-600 text-white py-3 rounded-xl font-medium hover:from-rose-700 hover:to-orange-700 disabled:opacity-50 transition-all duration-200 shadow-lg shadow-rose-500/25">
              {activateMutation.isPending ? (
                <span className="flex items-center justify-center gap-2">
                  <svg className="animate-spin w-4 h-4" fill="none" viewBox="0 0 24 24"><circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" /><path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" /></svg>
                  Activating...
                </span>
              ) : 'ACTIVATE KILL SWITCH'}
            </button>
          </div>
        ) : (
          <button onClick={() => deactivateMutation.mutate()} disabled={deactivateMutation.isPending}
            className="w-full bg-gradient-to-r from-emerald-600 to-teal-600 text-white py-3 rounded-xl font-medium hover:from-emerald-700 hover:to-teal-700 transition-all duration-200 shadow-lg shadow-emerald-500/25">
            {deactivateMutation.isPending ? (
              <span className="flex items-center justify-center gap-2">
                <svg className="animate-spin w-4 h-4" fill="none" viewBox="0 0 24 24"><circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" /><path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" /></svg>
                Deactivating...
              </span>
            ) : 'DEACTIVATE KILL SWITCH'}
          </button>
        )}
      </div>
    </div>
  );
}
