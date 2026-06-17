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

  if (isLoading) return <LoadingSkeleton />;

  return (
    <div>
      {/* Header */}
      <div className="mb-8 animate-fade-in-up">
        <h1 className="text-3xl font-bold text-slate-800 tracking-tight">Strategy Execution Configs</h1>
        <p className="text-slate-500 text-sm mt-2">Capital allocation, sizing, risk limits and execution mode</p>
      </div>

      {/* Config Cards Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
        {configs?.length === 0 && (
          <div className="col-span-full card-light p-12 text-center text-slate-400 animate-fade-in-up">
            <svg className="w-12 h-12 text-slate-300 mx-auto mb-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M12 6V4m0 2a2 2 0 100 4m0-4a2 2 0 110 4m-6 8a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4m6 6v10m6-2a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4" />
            </svg>
            <p>No strategy configs yet</p>
          </div>
        )}
        {configs?.map((c, i) => (
          <div key={c.id} className={`card-light p-5 hover-lift hover-glow animate-fade-in-up delay-${Math.min((i+1)*100, 600)} group`}>
            {/* Card Header */}
            <div className="flex items-center justify-between mb-4">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-indigo-50 to-violet-50 border border-indigo-100 flex items-center justify-center">
                  <span className="text-indigo-600 font-bold text-sm">{getStrategyName(c.strategyId).charAt(0)}</span>
                </div>
                <div>
                  <h3 className="font-semibold text-slate-700">{getStrategyName(c.strategyId)}</h3>
                  <p className="text-xs text-slate-400">Strategy #{c.strategyId}</p>
                </div>
              </div>
              <button onClick={() => setEditing(c)} className="text-indigo-500/70 hover:text-indigo-600 text-xs font-medium transition-all px-3 py-1.5 rounded-lg hover:bg-indigo-50 border border-transparent hover:border-indigo-200">
                Edit Config
              </button>
            </div>

            {/* Config Grid */}
            <div className="grid grid-cols-2 gap-3">
              <ConfigItem label="Capital" value={`Rs.${(c.allocatedCapital || 0).toLocaleString()}`} color="indigo" />
              <ConfigItem label="Max Positions" value={c.maxPositions} color="emerald" />
              <ConfigItem label="Fixed Qty" value={c.forceFixedQty ? c.fixedQty : 'Off'} color="sky" />
              <ConfigItem label="Sizing Mode" value={c.sizingMode?.replace(/_/g, ' ')} color="violet" />
              <ConfigItem label="Daily Loss Limit" value={`Rs.${(c.dailyLossLimit || 0).toLocaleString()}`} color="rose" />
              <ConfigItem label="Cooldown" value={`${c.cooldownMinutes} min`} color="amber" />
            </div>

            {/* Toggles Row */}
            <div className="flex gap-3 mt-4 pt-4 border-t border-slate-100">
              <StatusBadge active={c.liveEnabled} label="Live" activeColor="emerald" />
              <StatusBadge active={c.paperEnabled} label="Paper" activeColor="sky" />
              <StatusBadge active={c.forceFixedQty} label="Fixed Qty" activeColor="indigo" />
              <StatusBadge active={c.enabled} label="Enabled" activeColor="violet" />
            </div>
          </div>
        ))}
      </div>

      {/* Edit Modal */}
      {editing && (
        <ConfigEditModal config={editing} strategyName={getStrategyName(editing.strategyId)} onClose={() => setEditing(null)} onSave={(body) => updateMutation.mutate({ strategyId: editing.strategyId, body })} />
      )}
    </div>
  );
}

function ConfigItem({ label, value, color }) {
  const colors = {
    indigo: 'text-indigo-600',
    emerald: 'text-emerald-600',
    sky: 'text-sky-600',
    violet: 'text-violet-600',
    rose: 'text-rose-600',
    amber: 'text-amber-600',
  };
  return (
    <div className="bg-slate-50 rounded-xl p-3 border border-slate-100">
      <p className="text-[10px] uppercase tracking-wider text-slate-400 mb-1">{label}</p>
      <p className={`text-sm font-semibold ${colors[color] || 'text-slate-700'}`}>{value}</p>
    </div>
  );
}

function StatusBadge({ active, label, activeColor }) {
  const colors = {
    emerald: 'bg-emerald-50 text-emerald-700 border-emerald-200',
    sky: 'bg-sky-50 text-sky-700 border-sky-200',
    indigo: 'bg-indigo-50 text-indigo-700 border-indigo-200',
    violet: 'bg-violet-50 text-violet-700 border-violet-200',
  };
  const inactive = 'bg-slate-100 text-slate-500 border-slate-200';
  return (
    <span className={`px-2.5 py-1 rounded-lg text-[10px] font-bold uppercase tracking-wider border ${active ? colors[activeColor] : inactive}`}>
      {active ? label : `${label} Off`}
    </span>
  );
}

function ConfigEditModal({ config, strategyName, onClose, onSave }) {
  const [form, setForm] = useState({ ...config });

  return (
    <div className="fixed inset-0 modal-backdrop flex items-center justify-center z-50 animate-fade-in">
      <div className="glass-card-strong rounded-2xl w-full max-w-2xl mx-4 max-h-[90vh] overflow-y-auto animate-scale-in border-indigo-500/20 shadow-2xl shadow-indigo-500/10">
        {/* Modal Header */}
        <div className="p-6 border-b border-white/5 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-indigo-500 to-violet-600 flex items-center justify-center shadow-lg">
              <svg className="w-5 h-5 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 6V4m0 2a2 2 0 100 4m0-4a2 2 0 110 4m-6 8a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4m6 6v10m6-2a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4" />
              </svg>
            </div>
            <div>
              <h3 className="font-bold text-lg text-white">Edit Config</h3>
              <p className="text-xs text-slate-400">{strategyName}</p>
            </div>
          </div>
          <button onClick={onClose} className="text-slate-400 hover:text-white transition-colors p-1 rounded-lg hover:bg-white/5">
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}><path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" /></svg>
          </button>
        </div>

        {/* Modal Body */}
        <div className="p-6 grid grid-cols-1 md:grid-cols-2 gap-5">
          <FormField label="Allocated Capital" type="number" value={form.allocatedCapital} onChange={v => setForm({...form, allocatedCapital: Number(v)})} />
          <FormField label="Max Positions" type="number" value={form.maxPositions} onChange={v => setForm({...form, maxPositions: Number(v)})} />
          <FormField label="Max Trade Qty" type="number" value={form.maxTradeQuantity} onChange={v => setForm({...form, maxTradeQuantity: Number(v)})} />
          <FormField label="Fixed Qty" type="number" value={form.fixedQty} onChange={v => setForm({...form, fixedQty: Number(v)})} />
          <div>
            <label className="text-xs text-slate-400 mb-1.5 block">Sizing Mode</label>
            <select value={form.sizingMode} onChange={e => setForm({...form, sizingMode: e.target.value})} className="w-full bg-slate-800/50 border border-white/10 rounded-xl px-4 py-2.5 text-sm text-white focus:outline-none focus:border-indigo-500/50 transition-all">
              <option value="FIXED_QUANTITY" className="bg-slate-800">Fixed Quantity</option>
              <option value="FIXED_CAPITAL" className="bg-slate-800">Fixed Capital</option>
              <option value="RISK_BASED" className="bg-slate-800">Risk Based</option>
            </select>
          </div>
          <FormField label="Daily Loss Limit" type="number" value={form.dailyLossLimit} onChange={v => setForm({...form, dailyLossLimit: Number(v)})} />
          <FormField label="Cooldown (min)" type="number" value={form.cooldownMinutes} onChange={v => setForm({...form, cooldownMinutes: Number(v)})} />

          {/* Toggles */}
          <div className="col-span-full flex flex-wrap gap-6 mt-2">
            <Toggle label="Live Enabled" checked={form.liveEnabled} onChange={v => setForm({...form, liveEnabled: v})} color="emerald" />
            <Toggle label="Paper Enabled" checked={form.paperEnabled} onChange={v => setForm({...form, paperEnabled: v})} color="sky" />
            <Toggle label="Force Fixed Qty" checked={form.forceFixedQty} onChange={v => setForm({...form, forceFixedQty: v})} color="indigo" />
            <Toggle label="Enabled" checked={form.enabled} onChange={v => setForm({...form, enabled: v})} color="violet" />
          </div>
        </div>

        {/* Modal Footer */}
        <div className="p-6 border-t border-white/5 flex justify-end gap-3">
          <button onClick={onClose} className="px-5 py-2.5 rounded-xl text-sm text-slate-400 hover:text-white hover:bg-white/5 transition-all">Cancel</button>
          <button onClick={() => onSave(form)} className="btn-shimmer bg-gradient-to-r from-indigo-600 to-violet-600 text-white px-6 py-2.5 rounded-xl text-sm font-medium transition-all hover:shadow-lg hover:shadow-indigo-500/25 hover:scale-[1.02]">Save Changes</button>
        </div>
      </div>
    </div>
  );
}

function FormField({ label, type, value, onChange }) {
  return (
    <div>
      <label className="text-xs text-slate-400 mb-1.5 block">{label}</label>
      <input type={type} value={value} onChange={e => onChange(e.target.value)} className="w-full bg-slate-800/50 border border-white/10 rounded-xl px-4 py-2.5 text-sm text-white placeholder-slate-500 focus:outline-none focus:border-indigo-500/50 focus:ring-1 focus:ring-indigo-500/30 transition-all" />
    </div>
  );
}

function Toggle({ label, checked, onChange, color }) {
  const trackColors = {
    emerald: 'bg-emerald-500',
    sky: 'bg-sky-500',
    indigo: 'bg-indigo-500',
    violet: 'bg-violet-500',
  };
  return (
    <label className="flex items-center gap-3 cursor-pointer group">
      <div className={`relative w-11 h-6 rounded-full transition-colors duration-300 ${checked ? trackColors[color] : 'bg-slate-700'}`}>
        <div className={`absolute top-0.5 left-0.5 w-5 h-5 rounded-full bg-white shadow-md transition-transform duration-300 ${checked ? 'translate-x-5' : 'translate-x-0'}`} />
      </div>
      <span className="text-sm text-slate-300 group-hover:text-white transition-colors">{label}</span>
      <input type="checkbox" checked={checked} onChange={e => onChange(e.target.checked)} className="sr-only" />
    </label>
  );
}

function LoadingSkeleton() {
  return (
    <div className="space-y-4">
      <div className="skeleton w-80 h-10 mb-8" />
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
        {[1,2,3,4].map(i => (
          <div key={i} className="card-light p-5">
            <div className="skeleton w-full h-32" />
          </div>
        ))}
      </div>
    </div>
  );
}
