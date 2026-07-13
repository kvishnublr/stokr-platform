import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import client from '../api/client';

const fetchDeployments = () => client.get('/deployments').then(r => r.data);
const fetchStrategies  = () => client.get('/strategies').then(r => r.data);
const fetchBrokers     = () => client.get('/brokers').then(r => r.data);

const MODE_COLORS = {
  LIVE:  { bg: '#fef2f2', text: '#991b1b', border: '#fca5a5' },
  PAPER: { bg: '#eff6ff', text: '#1d4ed8', border: '#93c5fd' },
};
const STATUS_COLORS = {
  ACTIVE:  { bg: '#f0fdf4', text: '#15803d', border: '#86efac' },
  STOPPED: { bg: '#f9fafb', text: '#6b7280', border: '#d1d5db' },
  PAUSED:  { bg: '#fffbeb', text: '#92400e', border: '#fcd34d' },
};

export default function Deployments() {
  const qc = useQueryClient();
  const [showCreate, setShowCreate] = useState(false);
  const [confirmStop, setConfirmStop] = useState(null);
  const [editForm, setEditForm] = useState(null);
  const [form, setForm]   = useState({ strategyId: '', brokerAccountId: '', mode: 'PAPER', capital: 5000 });

  const { data: deployments = [], isLoading, isError } = useQuery({ queryKey: ['deployments'], queryFn: fetchDeployments, refetchInterval: 60000, retry: 1 });
  const { data: strategies  = [] }                    = useQuery({ queryKey: ['strategies'],  queryFn: fetchStrategies,  retry: 1 });
  const { data: brokers     = [] }                    = useQuery({ queryKey: ['brokers'],     queryFn: fetchBrokers,     retry: 1 });

  const createMut = useMutation({
    mutationFn: (body) => client.post('/deployments', body).then(r => r.data),
    onSuccess: () => { qc.invalidateQueries(['deployments']); setShowCreate(false); setForm({ strategyId: '', brokerAccountId: '', mode: 'PAPER', capital: 5000 }); },
  });
  const updateMut = useMutation({
    mutationFn: ({ id, ...body }) => client.patch(`/deployments/${id}`, body).then(r => r.data),
    onSuccess: () => { qc.invalidateQueries(['deployments']); setEditForm(null); },
  });
  const stopMut = useMutation({
    mutationFn: (id) => client.delete(`/deployments/${id}`).then(r => r.data),
    onSuccess: () => { qc.invalidateQueries(['deployments']); setConfirmStop(null); },
  });

  const active   = deployments.filter(d => d.status === 'ACTIVE');
  const totalCap = active.reduce((s, d) => s + (d.capital || 0), 0);
  const totalPnl = deployments.reduce((s, d) => s + (d.todayPnl || 0), 0);

  return (
    <div style={{ padding: '24px', animation: 'fadeIn 0.4s ease' }}>
      <style>{`
        @keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
        .dep-card { background: white; border: 1px solid #e5e7eb; border-radius: 12px; padding: 20px; transition: box-shadow 0.2s; }
        .dep-card:hover { box-shadow: 0 8px 24px rgba(0,0,0,0.1); }
        .metric-box { padding: 10px; background: #f9fafb; border-radius: 8px; }
        .btn { padding: 8px 14px; border: none; border-radius: 6px; font-size: 12px; font-weight: 600; cursor: pointer; transition: opacity 0.15s; }
        .btn:hover { opacity: 0.85; }
        .btn-primary { background: #4f46e5; color: white; }
        .btn-danger  { background: #fecaca; color: #991b1b; }
        .btn-ghost   { background: #f3f4f6; color: #374151; }
        .overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; z-index: 1000; }
        .modal { background: white; border-radius: 12px; padding: 28px; width: 440px; max-width: 95vw; }
        .form-row { margin-bottom: 16px; }
        .form-row label { display: block; font-size: 12px; font-weight: 600; color: #374151; margin-bottom: 6px; text-transform: uppercase; }
        .form-row select, .form-row input { width: 100%; padding: 9px 12px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 14px; box-sizing: border-box; }
        .form-row select:focus, .form-row input:focus { outline: 2px solid #4f46e5; border-color: transparent; }
        .badge { display: inline-block; padding: 3px 9px; border-radius: 20px; font-size: 11px; font-weight: 700; border-width: 1px; border-style: solid; }
      `}</style>

      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: '28px' }}>
        <div>
          <h1 style={{ fontSize: '28px', fontWeight: '800', color: '#1f2937', margin: 0, marginBottom: '6px' }}>
            Deployments
          </h1>
          <p style={{ color: '#6b7280', fontSize: '14px', margin: 0 }}>
            {active.length} active · ₹{totalCap.toLocaleString('en-IN')} deployed
            {totalPnl !== 0 && (
              <span style={{ marginLeft: '12px', color: totalPnl >= 0 ? '#059669' : '#dc2626', fontWeight: 700 }}>
                {totalPnl >= 0 ? '+' : ''}₹{totalPnl.toFixed(0)} today
              </span>
            )}
          </p>
        </div>
        <button className="btn btn-primary" onClick={() => setShowCreate(true)}>
          + New Deployment
        </button>
      </div>

      {/* Loading */}
      {isLoading && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
          {[1,2,3].map(i => (
            <div key={i} style={{ height: '180px', borderRadius: '12px', background: 'linear-gradient(90deg,#f3f4f6 25%,#f9fafb 50%,#f3f4f6 75%)', backgroundSize: '200% 100%', animation: 'shimmer 1.4s ease-in-out infinite' }} />
          ))}
        </div>
      )}

      {/* Error — backend not reachable */}
      {isError && (
        <div style={{ background: '#fef2f2', border: '1px solid #fca5a5', borderRadius: '14px', padding: '40px 32px', textAlign: 'center' }}>
          <div style={{ fontSize: '36px', marginBottom: '12px' }}>⚠️</div>
          <div style={{ fontWeight: 700, fontSize: '16px', color: '#dc2626', marginBottom: '6px' }}>Backend not reachable</div>
          <div style={{ color: '#6b7280', fontSize: '13px' }}>Make sure the Spring Boot server is running at localhost:8080</div>
        </div>
      )}

      {/* Empty */}
      {!isLoading && !isError && deployments.length === 0 && (
        <div style={{ textAlign: 'center', padding: '80px 0', color: '#9ca3af' }}>
          <div style={{ fontSize: '40px', marginBottom: '12px' }}>📋</div>
          <div style={{ fontSize: '16px', fontWeight: '600', color: '#374151' }}>No deployments yet</div>
          <div style={{ fontSize: '13px', marginTop: '6px' }}>Create one to start live or paper trading.</div>
        </div>
      )}

      {/* Deployment cards */}
      {!isLoading && !isError && deployments.length > 0 && <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(340px, 1fr))', gap: '18px', marginBottom: '32px' }}>
        {deployments.map(d => {
          const modeC   = MODE_COLORS[d.mode]   || MODE_COLORS.PAPER;
          const statusC = STATUS_COLORS[d.status] || STATUS_COLORS.STOPPED;
          const pnl = d.todayPnl || 0;
          const isActive = d.status === 'ACTIVE';

          return (
            <div key={d.id} className="dep-card">
              {/* Top */}
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '14px', paddingBottom: '12px', borderBottom: '1px solid #f3f4f6' }}>
                <div>
                  <div style={{ fontSize: '15px', fontWeight: '700', color: '#111827', marginBottom: '4px' }}>
                    {d.strategyName || `Strategy #${d.strategyId}`}
                  </div>
                  <div style={{ fontSize: '12px', color: '#6b7280' }}>{d.brokerName || 'Paper'} · ID #{d.id}</div>
                </div>
                <div style={{ display: 'flex', gap: '6px', flexDirection: 'column', alignItems: 'flex-end' }}>
                  <span className="badge" style={{ background: modeC.bg, color: modeC.text, borderColor: modeC.border }}>
                    {d.mode === 'LIVE' ? '🔴' : '📄'} {d.mode}
                  </span>
                  <span className="badge" style={{ background: statusC.bg, color: statusC.text, borderColor: statusC.border }}>
                    {d.status}
                  </span>
                </div>
              </div>

              {/* Metrics */}
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '10px', marginBottom: '14px' }}>
                <div className="metric-box">
                  <div style={{ fontSize: '11px', color: '#6b7280', fontWeight: '600', textTransform: 'uppercase', marginBottom: '3px' }}>Capital</div>
                  <div style={{ fontSize: '18px', fontWeight: '800', color: '#111827' }}>₹{(d.capital || 0).toLocaleString('en-IN')}</div>
                </div>
                <div className="metric-box">
                  <div style={{ fontSize: '11px', color: '#6b7280', fontWeight: '600', textTransform: 'uppercase', marginBottom: '3px' }}>Today P&L</div>
                  <div style={{ fontSize: '18px', fontWeight: '800', color: pnl >= 0 ? '#059669' : '#dc2626' }}>
                    {pnl >= 0 ? '+' : ''}₹{pnl.toFixed(0)}
                  </div>
                </div>
                <div className="metric-box">
                  <div style={{ fontSize: '11px', color: '#6b7280', fontWeight: '600', textTransform: 'uppercase', marginBottom: '3px' }}>Open Positions</div>
                  <div style={{ fontSize: '18px', fontWeight: '800', color: '#111827' }}>{d.openPositions ?? 0}</div>
                </div>
                <div className="metric-box">
                  <div style={{ fontSize: '11px', color: '#6b7280', fontWeight: '600', textTransform: 'uppercase', marginBottom: '3px' }}>Signals Today</div>
                  <div style={{ fontSize: '18px', fontWeight: '800', color: '#111827' }}>{d.signalsToday ?? 0}</div>
                </div>
              </div>

              {/* Last signal */}
              {d.lastSignalAt && (
                <div style={{ fontSize: '11px', color: '#9ca3af', marginBottom: '12px' }}>
                  Last signal: {new Date(d.lastSignalAt).toLocaleString('en-IN')}
                </div>
              )}

              {/* Actions */}
              <div style={{ display: 'flex', gap: '8px', paddingTop: '12px', borderTop: '1px solid #f3f4f6' }}>
                <button className="btn btn-ghost" style={{ flex: 1, background: '#eef2ff', color: '#4338ca' }}
                  onClick={() => setEditForm({ id: d.id, capital: d.capital || 100000, mode: d.mode || 'PAPER', brokerAccountId: d.brokerAccountId || '' })}>
                  Edit
                </button>
                {isActive && (
                  <button className="btn btn-danger" style={{ flex: 1 }}
                    onClick={() => setConfirmStop(d)}>
                    Stop
                  </button>
                )}
                {!isActive && (
                  <span style={{ flex: 1, textAlign: 'center', fontSize: '12px', color: '#9ca3af', padding: '8px' }}>Inactive</span>
                )}
              </div>
            </div>
          );
        })}
      </div>}

      {/* Create modal */}
      {showCreate && (
        <div className="overlay" onClick={() => setShowCreate(false)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <h2 style={{ margin: '0 0 20px 0', fontSize: '18px', fontWeight: '700' }}>New Deployment</h2>

            <div className="form-row">
              <label>Strategy</label>
              <select value={form.strategyId} onChange={e => setForm(f => ({ ...f, strategyId: e.target.value }))}>
                <option value="">Select strategy...</option>
                {strategies.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </div>

            <div className="form-row">
              <label>Mode</label>
              <select value={form.mode} onChange={e => setForm(f => ({ ...f, mode: e.target.value }))}>
                <option value="PAPER">Paper (simulated)</option>
                <option value="LIVE">Live (real money)</option>
              </select>
            </div>

            {form.mode === 'LIVE' && (
              <div className="form-row">
                <label>Broker Account</label>
                <select value={form.brokerAccountId} onChange={e => setForm(f => ({ ...f, brokerAccountId: e.target.value }))}>
                  <option value="">Select broker...</option>
                  {brokers.map(b => <option key={b.id} value={b.id}>{b.brokerName} — {b.clientId}</option>)}
                </select>
              </div>
            )}

            <div className="form-row">
              <label>Capital (₹)</label>
              <input type="number" min="1000" step="500" value={form.capital}
                onChange={e => setForm(f => ({ ...f, capital: Number(e.target.value) }))} />
            </div>

            {createMut.isError && (
              <div style={{ background: '#fef2f2', color: '#991b1b', padding: '10px 12px', borderRadius: '6px', fontSize: '13px', marginBottom: '14px' }}>
                {createMut.error?.response?.data?.message || 'Failed to create deployment'}
              </div>
            )}

            <div style={{ display: 'flex', gap: '10px', justifyContent: 'flex-end' }}>
              <button className="btn btn-ghost" onClick={() => setShowCreate(false)}>Cancel</button>
              <button className="btn btn-primary"
                disabled={!form.strategyId || (form.mode === 'LIVE' && !form.brokerAccountId) || createMut.isPending}
                onClick={() => createMut.mutate({
                  strategyId: Number(form.strategyId),
                  brokerAccountId: form.brokerAccountId ? Number(form.brokerAccountId) : null,
                  mode: form.mode,
                  capital: form.capital,
                })}>
                {createMut.isPending ? 'Creating...' : 'Deploy'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Confirm stop modal */}
      {confirmStop && (
        <div className="overlay" onClick={() => setConfirmStop(null)}>
          <div className="modal" onClick={e => e.stopPropagation()} style={{ width: '360px' }}>
            <h2 style={{ margin: '0 0 12px 0', fontSize: '18px', fontWeight: '700', color: '#dc2626' }}>Stop Deployment?</h2>
            <p style={{ color: '#6b7280', fontSize: '14px', margin: '0 0 20px 0' }}>
              This will halt <strong>{confirmStop.strategyName || `Deployment #${confirmStop.id}`}</strong> and stop all new signals.
              Open positions will NOT be closed automatically.
            </p>
            <div style={{ display: 'flex', gap: '10px', justifyContent: 'flex-end' }}>
              <button className="btn btn-ghost" onClick={() => setConfirmStop(null)}>Cancel</button>
              <button className="btn btn-danger"
                disabled={stopMut.isPending}
                onClick={() => stopMut.mutate(confirmStop.id)}>
                {stopMut.isPending ? 'Stopping...' : 'Stop Deployment'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Edit modal */}
      {editForm && (
        <div className="overlay" onClick={() => setEditForm(null)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <h2 style={{ margin: '0 0 20px 0', fontSize: '18px', fontWeight: '700' }}>Edit Deployment #{editForm.id}</h2>

            <div className="form-row">
              <label>Capital (₹)</label>
              <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap', marginBottom: '8px' }}>
                {[25000, 50000, 100000, 200000, 500000].map(v => (
                  <button key={v} className="btn" type="button"
                    style={{ background: editForm.capital === v ? '#4f46e5' : '#f3f4f6', color: editForm.capital === v ? 'white' : '#374151', border: '1px solid #d1d5db' }}
                    onClick={() => setEditForm(f => ({ ...f, capital: v }))}>
                    ₹{v >= 100000 ? `${v / 100000}L` : `${v / 1000}K`}
                  </button>
                ))}
              </div>
              <input type="number" min="1000" step="500" value={editForm.capital}
                onChange={e => setEditForm(f => ({ ...f, capital: Number(e.target.value) }))} />
            </div>

            <div className="form-row">
              <label>Mode</label>
              <div style={{ display: 'flex', gap: '8px' }}>
                {['PAPER', 'LIVE'].map(m => (
                  <button key={m} className="btn" type="button" style={{ flex: 1, padding: '10px', background: editForm.mode === m ? (m === 'LIVE' ? '#fecaca' : '#dbeafe') : '#f3f4f6', color: editForm.mode === m ? (m === 'LIVE' ? '#991b1b' : '#1d4ed8') : '#6b7280', fontWeight: 700, border: '1px solid #d1d5db' }}
                    onClick={() => setEditForm(f => ({ ...f, mode: m, brokerAccountId: m === 'PAPER' ? '' : f.brokerAccountId }))}>
                    {m === 'LIVE' ? '🔴' : '📄'} {m}
                  </button>
                ))}
              </div>
            </div>

            {editForm.mode === 'LIVE' && (
              <div className="form-row">
                <label>Broker Account</label>
                <select value={editForm.brokerAccountId} onChange={e => setEditForm(f => ({ ...f, brokerAccountId: e.target.value }))}>
                  <option value="">Select broker...</option>
                  {brokers.map(b => <option key={b.id} value={b.id}>{b.brokerName} — {b.clientId}</option>)}
                </select>
              </div>
            )}

            {editForm.mode === 'LIVE' && (
              <div style={{ background: '#fef3c7', border: '1px solid #fbbf24', borderRadius: '8px', padding: '12px', fontSize: '13px', color: '#92400e', marginBottom: '16px' }}>
                ⚠️ Switching to live mode will execute real trades with real money. Make sure you have tested this strategy thoroughly in paper mode first.
              </div>
            )}

            {updateMut.isError && (
              <div style={{ background: '#fef2f2', color: '#991b1b', padding: '10px 12px', borderRadius: '6px', fontSize: '13px', marginBottom: '14px' }}>
                {updateMut.error?.response?.data?.error || 'Failed to update deployment'}
              </div>
            )}

            <div style={{ display: 'flex', gap: '10px', justifyContent: 'flex-end' }}>
              <button className="btn btn-ghost" onClick={() => setEditForm(null)}>Cancel</button>
              <button className="btn btn-primary"
                disabled={editForm.mode === 'LIVE' && !editForm.brokerAccountId || updateMut.isPending}
                onClick={() => updateMut.mutate({ id: editForm.id, capital: editForm.capital, mode: editForm.mode, brokerAccountId: editForm.brokerAccountId ? Number(editForm.brokerAccountId) : null })}>
                {updateMut.isPending ? 'Saving...' : 'Save Changes'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
