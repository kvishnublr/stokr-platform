import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import client from '../../api/client';

export default function AdminStrategyConfigs() {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(null);

  const { data: configs, isLoading } = useQuery({
    queryKey: ['strategy-configs'],
    queryFn: () => client.get('/admin/strategy-configs').then((r) => r.data),
  });

  const { data: strategies } = useQuery({
    queryKey: ['strategies'],
    queryFn: () => client.get('/strategies').then((r) => r.data),
  });

  const updateMutation = useMutation({
    mutationFn: ({ strategyId, body }) => client.put(`/admin/strategy-configs/${strategyId}`, body),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['strategy-configs'] }); setEditing(null); },
  });

  const getStrategyName = (id) => strategies?.find(s => s.id === id)?.name || `#${id}`;

  if (isLoading) return <div className="text-gray-500">Loading...</div>;

  return (
    <div>
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold">Strategy Execution Configs</h1>
      </div>

      <div className="bg-white rounded-lg shadow overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50">
            <tr className="text-left text-gray-500">
              <th className="p-4">Strategy</th>
              <th className="p-4">Capital</th>
              <th className="p-4">Max Pos</th>
              <th className="p-4">Fixed Qty</th>
              <th className="p-4">Sizing Mode</th>
              <th className="p-4">Live</th>
              <th className="p-4">Paper</th>
              <th className="p-4">Actions</th>
            </tr>
          </thead>
          <tbody>
            {configs?.length === 0 && (
              <tr><td colSpan="8" className="p-8 text-center text-gray-400">No configs</td></tr>
            )}
            {configs?.map((c) => (
              <tr key={c.id} className="border-t">
                <td className="p-4 font-medium">{getStrategyName(c.strategyId)}</td>
                <td className="p-4">{c.allocatedCapital?.toLocaleString()}</td>
                <td className="p-4">{c.maxPositions}</td>
                <td className="p-4">{c.forceFixedQty ? c.fixedQty : 'Off'}</td>
                <td className="p-4">{c.sizingMode}</td>
                <td className="p-4"><span className={`px-2 py-0.5 rounded text-xs ${c.liveEnabled ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600'}`}>{c.liveEnabled ? 'Yes' : 'No'}</span></td>
                <td className="p-4"><span className={`px-2 py-0.5 rounded text-xs ${c.paperEnabled ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600'}`}>{c.paperEnabled ? 'Yes' : 'No'}</span></td>
                <td className="p-4">
                  <button onClick={() => setEditing(c)} className="text-indigo-600 hover:text-indigo-800 text-xs font-medium">Edit</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {editing && (
        <ConfigEditModal config={editing} strategyName={getStrategyName(editing.strategyId)} onClose={() => setEditing(null)} onSave={(body) => updateMutation.mutate({ strategyId: editing.strategyId, body })} />
      )}
    </div>
  );
}

function ConfigEditModal({ config, strategyName, onClose, onSave }) {
  const [form, setForm] = useState({ ...config });

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-white rounded-xl shadow-xl w-full max-w-lg mx-4 max-h-[90vh] overflow-y-auto">
        <div className="p-5 border-b border-slate-100">
          <h3 className="font-bold text-lg">Edit Config: {strategyName}</h3>
        </div>
        <div className="p-5 grid grid-cols-1 md:grid-cols-2 gap-4">
          <div>
            <label className="text-xs font-medium text-slate-500">Allocated Capital</label>
            <input type="number" value={form.allocatedCapital} onChange={e => setForm({...form, allocatedCapital: Number(e.target.value)})} className="w-full border rounded px-3 py-2 text-sm mt-1" />
          </div>
          <div>
            <label className="text-xs font-medium text-slate-500">Max Positions</label>
            <input type="number" value={form.maxPositions} onChange={e => setForm({...form, maxPositions: Number(e.target.value)})} className="w-full border rounded px-3 py-2 text-sm mt-1" />
          </div>
          <div>
            <label className="text-xs font-medium text-slate-500">Max Trade Qty</label>
            <input type="number" value={form.maxTradeQuantity} onChange={e => setForm({...form, maxTradeQuantity: Number(e.target.value)})} className="w-full border rounded px-3 py-2 text-sm mt-1" />
          </div>
          <div>
            <label className="text-xs font-medium text-slate-500">Fixed Qty</label>
            <input type="number" value={form.fixedQty} onChange={e => setForm({...form, fixedQty: Number(e.target.value)})} className="w-full border rounded px-3 py-2 text-sm mt-1" />
          </div>
          <div>
            <label className="text-xs font-medium text-slate-500">Sizing Mode</label>
            <select value={form.sizingMode} onChange={e => setForm({...form, sizingMode: e.target.value})} className="w-full border rounded px-3 py-2 text-sm mt-1">
              <option value="FIXED_QUANTITY">Fixed Quantity</option>
              <option value="FIXED_CAPITAL">Fixed Capital</option>
              <option value="RISK_BASED">Risk Based</option>
            </select>
          </div>
          <div>
            <label className="text-xs font-medium text-slate-500">Daily Loss Limit</label>
            <input type="number" value={form.dailyLossLimit} onChange={e => setForm({...form, dailyLossLimit: Number(e.target.value)})} className="w-full border rounded px-3 py-2 text-sm mt-1" />
          </div>
          <div>
            <label className="text-xs font-medium text-slate-500">Cooldown (min)</label>
            <input type="number" value={form.cooldownMinutes} onChange={e => setForm({...form, cooldownMinutes: Number(e.target.value)})} className="w-full border rounded px-3 py-2 text-sm mt-1" />
          </div>
          <div className="flex items-center gap-4 mt-4">
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={form.liveEnabled} onChange={e => setForm({...form, liveEnabled: e.target.checked})} />
              Live Enabled
            </label>
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={form.paperEnabled} onChange={e => setForm({...form, paperEnabled: e.target.checked})} />
              Paper Enabled
            </label>
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={form.forceFixedQty} onChange={e => setForm({...form, forceFixedQty: e.target.checked})} />
              Force Fixed Qty
            </label>
          </div>
        </div>
        <div className="p-5 border-t border-slate-100 flex justify-end gap-2">
          <button onClick={onClose} className="px-4 py-2 rounded-lg text-sm text-slate-600 hover:bg-slate-50">Cancel</button>
          <button onClick={() => onSave(form)} className="px-4 py-2 rounded-lg text-sm bg-indigo-600 text-white hover:bg-indigo-700">Save</button>
        </div>
      </div>
    </div>
  );
}
