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

  if (isLoading) return <div className="text-gray-500">Loading...</div>;

  return (
    <div>
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold">Universe Groups</h1>
        <button onClick={() => setShowCreate(true)} className="bg-indigo-600 text-white px-4 py-2 rounded-lg hover:bg-indigo-700 text-sm transition">Create Group</button>
      </div>

      {showCreate && (
        <div className="bg-white rounded-lg shadow p-5 mb-6 border border-slate-200">
          <h3 className="font-semibold mb-3">Create Universe Group</h3>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-3 mb-3">
            <input placeholder="Group Key (e.g. MY_CUSTOM)" value={form.groupKey} onChange={e => setForm({...form, groupKey: e.target.value})} className="border rounded px-3 py-2 text-sm" />
            <input placeholder="Display Name" value={form.displayName} onChange={e => setForm({...form, displayName: e.target.value})} className="border rounded px-3 py-2 text-sm" />
            <select value={form.universeType} onChange={e => setForm({...form, universeType: e.target.value})} className="border rounded px-3 py-2 text-sm">
              <option value="CUSTOM">Custom</option>
              <option value="INDEX_CONSTITUENTS">Index Constituents</option>
              <option value="SECTOR">Sector</option>
            </select>
          </div>
          <div className="flex gap-2">
            <button onClick={() => createMutation.mutate(form)} className="bg-indigo-600 text-white px-3 py-1.5 rounded text-sm hover:bg-indigo-700">Create</button>
            <button onClick={() => setShowCreate(false)} className="text-gray-500 px-3 py-1.5 text-sm">Cancel</button>
          </div>
        </div>
      )}

      <div className="space-y-3">
        {groups?.map((g) => (
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
          />
        ))}
      </div>
    </div>
  );
}

function UniverseGroupCard({ group, expanded, onToggleExpand, onToggleEnabled, onDelete, onSync, showBulk, onToggleBulk, bulkSymbols, onBulkSymbolsChange, onBulkImport, onAddSymbol, onRemoveSymbol, availableKeys }) {
  const [newSymbol, setNewSymbol] = useState('');
  const queryClient = useQueryClient();

  const { data: symbols } = useQuery({
    queryKey: ['universe-symbols', group.id],
    queryFn: () => client.get(`/universe-groups/${group.id}/symbols`).then((r) => r.data),
    enabled: expanded,
  });

  return (
    <div className="bg-white rounded-lg shadow border border-slate-200 overflow-hidden">
      <div className="p-4 flex items-center justify-between cursor-pointer hover:bg-slate-50" onClick={onToggleExpand}>
        <div className="flex items-center gap-3">
          <span className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase ${group.autoManaged ? 'bg-emerald-100 text-emerald-700' : 'bg-slate-100 text-slate-600'}`}>
            {group.autoManaged ? 'Auto' : 'Custom'}
          </span>
          <div>
            <p className="font-semibold text-sm">{group.displayName}</p>
            <p className="text-xs text-slate-500">{group.groupKey} · {group.assetClass} · {group.exchange}</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {group.autoManaged && availableKeys?.includes(group.groupKey) && (
            <button onClick={(e) => { e.stopPropagation(); onSync(); }} className="text-xs bg-indigo-50 text-indigo-600 px-2 py-1 rounded hover:bg-indigo-100">Sync</button>
          )}
          <button onClick={(e) => { e.stopPropagation(); onToggleEnabled(!group.enabled); }} className={`text-xs px-2 py-1 rounded ${group.enabled ? 'bg-green-50 text-green-600' : 'bg-gray-100 text-gray-500'}`}>
            {group.enabled ? 'Enabled' : 'Disabled'}
          </button>
          {!group.autoManaged && (
            <button onClick={(e) => { e.stopPropagation(); onDelete(); }} className="text-xs text-red-500 hover:text-red-700 px-2 py-1">Delete</button>
          )}
          <svg className={`w-4 h-4 text-slate-400 transition-transform ${expanded ? 'rotate-180' : ''}`} fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" /></svg>
        </div>
      </div>

      {expanded && (
        <div className="px-4 pb-4 border-t border-slate-100">
          <div className="mt-3 flex items-center gap-2">
            <input placeholder="Add symbol..." value={newSymbol} onChange={e => setNewSymbol(e.target.value)} className="border rounded px-3 py-1.5 text-sm flex-1" onKeyDown={e => { if (e.key === 'Enter') { onAddSymbol(newSymbol); setNewSymbol(''); } }} />
            <button onClick={() => { onAddSymbol(newSymbol); setNewSymbol(''); }} className="bg-indigo-600 text-white px-3 py-1.5 rounded text-sm hover:bg-indigo-700">Add</button>
            <button onClick={onToggleBulk} className="bg-slate-100 text-slate-700 px-3 py-1.5 rounded text-sm hover:bg-slate-200">Bulk</button>
          </div>

          {showBulk && (
            <div className="mt-2">
              <textarea placeholder="Paste symbols separated by comma or newline" value={bulkSymbols} onChange={e => onBulkSymbolsChange(e.target.value)} className="w-full border rounded px-3 py-2 text-sm h-24" />
              <button onClick={onBulkImport} className="mt-1 bg-indigo-600 text-white px-3 py-1 rounded text-sm hover:bg-indigo-700">Import & Replace</button>
            </div>
          )}

          <div className="mt-3 flex flex-wrap gap-1.5">
            {symbols?.map((s) => (
              <span key={s.id} className="inline-flex items-center gap-1 bg-slate-100 text-slate-700 px-2 py-0.5 rounded text-xs">
                {s.symbol}
                <button onClick={() => onRemoveSymbol(s.id)} className="text-slate-400 hover:text-red-500">&times;</button>
              </span>
            ))}
            {symbols?.length === 0 && <span className="text-xs text-slate-400">No symbols</span>}
          </div>
        </div>
      )}
    </div>
  );
}
