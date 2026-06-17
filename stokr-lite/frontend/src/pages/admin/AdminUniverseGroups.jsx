import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import client from '../../api/client';

export default function AdminUniverseGroups() {
  const queryClient = useQueryClient();
  const [expanded, setExpanded] = useState(null);
  const [showCreate, setShowCreate] = useState(false);
  const [bulkSymbols, setBulkSymbols] = useState('');
  const [showBulk, setShowBulk] = useState(false);
  const [form, setForm] = useState({ groupKey: '', displayName: '', universeType: 'CUSTOM' });

  const { data: groups, isLoading } = useQuery({
    queryKey: ['universe-groups'],
    queryFn: () => client.get('/universe-groups').then((r) => r.data),
  });

  const { data: availableKeys } = useQuery({
    queryKey: ['universe-available-keys'],
    queryFn: () => client.get('/universe-groups/available-keys').then((r) => r.data),
  });

  const createMutation = useMutation({
    mutationFn: (body) => client.post('/universe-groups', body),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['universe-groups'] }); setShowCreate(false); setForm({ groupKey: '', displayName: '', universeType: 'CUSTOM' }); },
  });

  const toggleMutation = useMutation({
    mutationFn: ({ id, enabled }) => client.patch(`/universe-groups/${id}`, { enabled }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['universe-groups'] }),
  });

  const deleteMutation = useMutation({
    mutationFn: (id) => client.delete(`/universe-groups/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['universe-groups'] }),
  });

  const syncMutation = useMutation({
    mutationFn: (id) => client.post(`/universe-groups/${id}/sync`),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['universe-groups'] }); queryClient.invalidateQueries({ queryKey: ['universe-symbols'] }); },
  });

  const bulkMutation = useMutation({
    mutationFn: ({ id, symbols, replace }) => client.post(`/universe-groups/${id}/symbols/bulk`, { symbols, replaceExisting: replace }),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['universe-symbols'] }); setShowBulk(false); setBulkSymbols(''); },
  });

  const addSymbolMutation = useMutation({
    mutationFn: ({ id, symbol }) => client.post(`/universe-groups/${id}/symbols`, { symbol }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['universe-symbols'] }),
  });

  const removeSymbolMutation = useMutation({
    mutationFn: ({ groupId, symbolId }) => client.delete(`/universe-groups/${groupId}/symbols/${symbolId}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['universe-symbols'] }),
  });

  if (isLoading) return <LoadingSkeleton />;

  return (
    <div>
      {/* Header */}
      <div className="flex justify-between items-center mb-8 animate-fade-in-up">
        <div>
          <div className="flex items-center gap-3 mb-1">
            <div className="w-1 h-7 rounded-full bg-gradient-to-b from-indigo-500 to-violet-500" />
            <h1 className="text-3xl font-bold text-slate-900 tracking-tight">Universe Groups</h1>
          </div>
          <p className="text-slate-400 text-sm ml-4">Manage symbol universes and index constituents</p>
        </div>
        <button onClick={() => setShowCreate(true)} className="btn-shimmer bg-gradient-to-r from-indigo-600 to-violet-600 text-white px-5 py-2.5 rounded-xl text-sm font-medium transition-all duration-200 hover:shadow-lg hover:shadow-indigo-500/20 hover:scale-[1.02]">
          + Create Group
        </button>
      </div>

      {/* Create Form */}
      {showCreate && (
        <div className="card-crystal p-6 mb-6 animate-scale-in border-l-4 border-l-indigo-500">
          <h3 className="font-semibold text-slate-800 mb-4 flex items-center gap-2 text-base">
            <div className="w-2 h-2 rounded-full bg-indigo-500" />
            Create Universe Group
          </h3>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-3 mb-4">
            <div>
              <label className="text-xs font-medium text-slate-500 mb-1.5 block">Group Key</label>
              <input placeholder="MY_CUSTOM" value={form.groupKey} onChange={e => setForm({...form, groupKey: e.target.value})} className="w-full bg-white border border-slate-200 rounded-xl px-4 py-2.5 text-sm text-slate-700 placeholder-slate-400 focus:outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100 transition-all input-crystal" />
            </div>
            <div>
              <label className="text-xs font-medium text-slate-500 mb-1.5 block">Display Name</label>
              <input placeholder="My Custom Group" value={form.displayName} onChange={e => setForm({...form, displayName: e.target.value})} className="w-full bg-white border border-slate-200 rounded-xl px-4 py-2.5 text-sm text-slate-700 placeholder-slate-400 focus:outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100 transition-all input-crystal" />
            </div>
            <div>
              <label className="text-xs font-medium text-slate-500 mb-1.5 block">Type</label>
              <select value={form.universeType} onChange={e => setForm({...form, universeType: e.target.value})} className="w-full bg-white border border-slate-200 rounded-xl px-4 py-2.5 text-sm text-slate-700 focus:outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100 transition-all input-crystal">
                <option value="CUSTOM">Custom</option>
                <option value="INDEX_CONSTITUENTS">Index Constituents</option>
                <option value="SECTOR">Sector</option>
              </select>
            </div>
          </div>
          <div className="flex gap-3">
            <button onClick={() => createMutation.mutate(form)} className="btn-shimmer bg-gradient-to-r from-indigo-600 to-violet-600 text-white px-5 py-2.5 rounded-xl text-sm font-medium transition-all hover:shadow-lg hover:shadow-indigo-500/20">Create</button>
            <button onClick={() => setShowCreate(false)} className="px-5 py-2.5 rounded-xl text-sm text-slate-500 hover:text-slate-700 hover:bg-slate-50 transition-all">Cancel</button>
          </div>
        </div>
      )}

      {/* Groups List */}
      <div className="space-y-3">
        {groups?.map((g, i) => (
          <UniverseGroupCard
            key={g.id}
            group={g}
            expanded={expanded === g.id}
            onToggleExpand={() => setExpanded(expanded === g.id ? null : g.id)}
            onToggleEnabled={(enabled) => toggleMutation.mutate({ id: g.id, enabled })}
            onDelete={() => { if (confirm('Delete this group?')) deleteMutation.mutate(g.id); }}
            onSync={() => syncMutation.mutate(g.id)}
            showBulk={showBulk === g.id}
            onToggleBulk={() => setShowBulk(showBulk === g.id ? null : g.id)}
            bulkSymbols={bulkSymbols}
            onBulkSymbolsChange={setBulkSymbols}
            onBulkImport={() => {
              const syms = bulkSymbols.split(/[\n,]/).map(s => s.trim()).filter(Boolean);
              bulkMutation.mutate({ id: g.id, symbols: syms, replace: true });
            }}
            onAddSymbol={(symbol) => addSymbolMutation.mutate({ id: g.id, symbol })}
            onRemoveSymbol={(symbolId) => removeSymbolMutation.mutate({ groupId: g.id, symbolId })}
            availableKeys={availableKeys}
            delay={`delay-${Math.min((i + 1) * 100, 800)}`}
          />
        ))}
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
      {[1,2,3,4].map(i => (
        <div key={i} className="card-crystal p-5">
          <div className="skeleton w-full h-12" />
        </div>
      ))}
    </div>
  );
}

function UniverseGroupCard({ group, expanded, onToggleExpand, onToggleEnabled, onDelete, onSync, showBulk, onToggleBulk, bulkSymbols, onBulkSymbolsChange, onBulkImport, onAddSymbol, onRemoveSymbol, availableKeys, delay }) {
  const [newSymbol, setNewSymbol] = useState('');
  const queryClient = useQueryClient();

  const { data: symbols } = useQuery({
    queryKey: ['universe-symbols', group.id],
    queryFn: () => client.get(`/universe-groups/${group.id}/symbols`).then((r) => r.data),
    enabled: expanded,
  });

  return (
    <div className={`card-crystal overflow-hidden animate-fade-in-up ${delay} hover-glow transition-all duration-200 ${expanded ? 'border-indigo-200/60 shadow-lg' : ''}`}>
      {/* Header Row */}
      <div className="p-5 flex items-center justify-between cursor-pointer hover:bg-slate-50/60 transition-colors" onClick={onToggleExpand}>
        <div className="flex items-center gap-3">
          <div className={`px-2.5 py-1 rounded-lg text-[11px] font-semibold uppercase tracking-wider ${
            group.autoManaged
              ? 'bg-emerald-50 text-emerald-700 border border-emerald-200'
              : 'bg-slate-100 text-slate-600 border border-slate-200'
          }`}>
            {group.autoManaged ? 'Auto' : 'Custom'}
          </div>
          <div>
            <p className="font-semibold text-sm text-slate-800">{group.displayName}</p>
            <p className="text-xs text-slate-400 mt-0.5">{group.groupKey} · {group.assetClass} · {group.exchange}</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {group.autoManaged && availableKeys?.includes(group.groupKey) && (
            <button onClick={(e) => { e.stopPropagation(); onSync(); }} className="text-xs font-medium bg-indigo-50 text-indigo-600 px-3 py-1.5 rounded-lg border border-indigo-200 hover:bg-indigo-100 transition-all">
              Sync
            </button>
          )}
          <button onClick={(e) => { e.stopPropagation(); onToggleEnabled(!group.enabled); }} className={`text-xs font-medium px-3 py-1.5 rounded-lg border transition-all ${group.enabled ? 'bg-emerald-50 text-emerald-700 border-emerald-200 hover:bg-emerald-100' : 'bg-slate-100 text-slate-500 border-slate-200 hover:bg-slate-200'}`}>
            {group.enabled ? 'Enabled' : 'Disabled'}
          </button>
          {!group.autoManaged && (
            <button onClick={(e) => { e.stopPropagation(); onDelete(); }} className="text-xs text-rose-500/70 hover:text-rose-600 px-2 py-1.5 transition-colors">
              Delete
            </button>
          )}
          <svg className={`w-5 h-5 text-slate-400 transition-transform duration-300 ${expanded ? 'rotate-180' : ''}`} fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" /></svg>
        </div>
      </div>

      {/* Expanded Content */}
      {expanded && (
        <div className="px-5 pb-5 border-t border-slate-100 animate-expand-down">
          <div className="mt-4 flex items-center gap-2">
            <input placeholder="Add symbol..." value={newSymbol} onChange={e => setNewSymbol(e.target.value)} className="bg-white border border-slate-200 rounded-xl px-4 py-2.5 text-sm text-slate-700 placeholder-slate-400 focus:outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100 transition-all flex-1 input-crystal" onKeyDown={e => { if (e.key === 'Enter') { onAddSymbol(newSymbol); setNewSymbol(''); } }} />
            <button onClick={() => { onAddSymbol(newSymbol); setNewSymbol(''); }} className="btn-shimmer bg-gradient-to-r from-indigo-600 to-violet-600 text-white px-5 py-2.5 rounded-xl text-sm font-medium transition-all hover:shadow-lg hover:shadow-indigo-500/20">Add</button>
            <button onClick={onToggleBulk} className="bg-white text-slate-600 px-5 py-2.5 rounded-xl text-sm font-medium border border-slate-200 hover:bg-slate-50 transition-all">Bulk</button>
          </div>

          {showBulk && (
            <div className="mt-3 animate-scale-in">
              <textarea placeholder="Paste symbols separated by comma or newline" value={bulkSymbols} onChange={e => onBulkSymbolsChange(e.target.value)} className="w-full bg-white border border-slate-200 rounded-xl px-4 py-3 text-sm text-slate-700 placeholder-slate-400 focus:outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100 transition-all h-28 resize-none input-crystal" />
              <button onClick={onBulkImport} className="mt-3 btn-shimmer bg-gradient-to-r from-indigo-600 to-violet-600 text-white px-5 py-2.5 rounded-xl text-sm font-medium transition-all hover:shadow-lg hover:shadow-indigo-500/20">Import &amp; Replace</button>
            </div>
          )}

          <div className="mt-4 flex flex-wrap gap-2">
            {symbols?.map((s, i) => (
              <span key={s.id} className="inline-flex items-center gap-1.5 bg-slate-100 text-slate-600 px-3 py-1.5 rounded-lg text-xs border border-slate-200 hover:border-rose-300 hover:bg-rose-50 hover:text-rose-600 transition-all cursor-default animate-fade-in-up" style={{ animationDelay: `${i * 30}ms` }}>
                {s.symbol}
                <button onClick={() => onRemoveSymbol(s.id)} className="text-slate-400 hover:text-rose-500 transition-colors text-lg leading-none">&times;</button>
              </span>
            ))}
            {symbols?.length === 0 && <span className="text-xs text-slate-400 italic">No symbols in this group</span>}
          </div>
        </div>
      )}
    </div>
  );
}
