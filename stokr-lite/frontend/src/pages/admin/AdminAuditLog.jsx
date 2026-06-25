import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import client from '../../api/client';

const ACTION_COLORS = {
  KILL_SWITCH_ACTIVATE: { bg: 'rgba(239,68,68,0.12)', color: '#dc2626', border: 'rgba(239,68,68,0.2)' },
  KILL_SWITCH_DEACTIVATE: { bg: 'rgba(16,185,129,0.12)', color: '#059669', border: 'rgba(16,185,129,0.2)' },
  FORCE_STOP_DEPLOYMENT: { bg: 'rgba(245,158,11,0.12)', color: '#d97706', border: 'rgba(245,158,11,0.2)' },
  STOP_ALL_DEPLOYMENTS: { bg: 'rgba(239,68,68,0.12)', color: '#dc2626', border: 'rgba(239,68,68,0.2)' },
  UPDATE_USER: { bg: 'rgba(99,102,241,0.12)', color: '#4f46e5', border: 'rgba(99,102,241,0.2)' },
  UPDATE_STRATEGY_CONFIG: { bg: 'rgba(56,189,248,0.12)', color: '#0284c7', border: 'rgba(56,189,248,0.2)' },
};

function actionStyle(action) {
  return ACTION_COLORS[action] || { bg: 'rgba(100,116,139,0.1)', color: '#64748b', border: 'rgba(100,116,139,0.15)' };
}

export default function AdminAuditLog() {
  const [page, setPage] = useState(0);
  const PAGE_SIZE = 30;

  const { data, isLoading } = useQuery({
    queryKey: ['admin-audit-log', page],
    queryFn: () => client.get('/admin/audit-logs', { params: { page, size: PAGE_SIZE } }).then(r => r.data),
    keepPreviousData: true,
  });

  const entries = data?.content || [];
  const totalPages = data?.totalPages || 0;

  if (isLoading) return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '300px' }}>
      <div className="animate-spin" style={{ width: '32px', height: '32px', border: '3px solid rgba(99,102,241,0.2)', borderTopColor: '#6366f1', borderRadius: '50%' }} />
    </div>
  );

  return (
    <div className="animate-fade-in-up">
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '32px', paddingBottom: '24px', borderBottom: '2px solid rgba(148,163,184,0.08)' }}>
        <div>
          <h1 style={{ fontSize: '32px', fontWeight: 900, background: 'linear-gradient(135deg, #0f172a 0%, #4f46e5 100%)', WebkitBackgroundClip: 'text', WebkitTextFillColor: 'transparent', letterSpacing: '-1px', marginBottom: '6px' }}>Audit Log</h1>
          <p style={{ fontSize: '13px', color: 'var(--text-muted)' }}>Track all admin actions across the platform</p>
        </div>
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', padding: '6px 14px', background: 'linear-gradient(135deg, rgba(99,102,241,0.1), rgba(167,139,250,0.06))', borderRadius: '10px', color: '#4f46e5', fontWeight: 700, fontSize: '13px', border: '2px solid rgba(99,102,241,0.1)' }}>
          📋 {data?.totalElements || 0} entries
        </div>
      </div>

      <div className="card-crystal" style={{ padding: 0, overflow: 'hidden' }}>
        <table className="table-crystal" style={{ width: '100%' }}>
          <thead>
            <tr>
              <th style={{ padding: '16px 20px' }}>Time</th>
              <th style={{ padding: '16px 20px' }}>Admin</th>
              <th style={{ padding: '16px 20px' }}>Action</th>
              <th style={{ padding: '16px 20px' }}>Target</th>
              <th style={{ padding: '16px 20px' }}>Description</th>
            </tr>
          </thead>
          <tbody>
            {entries.length === 0 && (
              <tr>
                <td colSpan="5" style={{ padding: '48px 20px', textAlign: 'center', color: '#94a3b8', fontSize: '14px' }}>
                  <div style={{ fontSize: '32px', marginBottom: '8px' }}>📋</div>
                  No audit entries yet
                </td>
              </tr>
            )}
            {entries.map((e, i) => {
              const style = actionStyle(e.action);
              return (
                <tr key={e.id} style={{ animationDelay: `${i * 30}ms`, animation: 'fadeInUp 0.3s ease forwards', opacity: 0 }}>
                  <td style={{ padding: '14px 20px', color: '#64748b', fontSize: '12px', whiteSpace: 'nowrap' }}>
                    <div style={{ fontWeight: 600, color: '#0f172a', fontSize: '13px' }}>
                      {new Date(e.createdAt).toLocaleDateString('en-IN', { day: '2-digit', month: 'short' })}
                    </div>
                    <div>{new Date(e.createdAt).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })}</div>
                  </td>
                  <td style={{ padding: '14px 20px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                      <div style={{ width: '30px', height: '30px', borderRadius: '8px', background: 'linear-gradient(135deg, #a78bfa, #60a5fa)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '11px', fontWeight: 700, color: 'white', flexShrink: 0 }}>
                        {e.adminEmail.substring(0, 2).toUpperCase()}
                      </div>
                      <div style={{ fontSize: '13px', fontWeight: 600, color: '#0f172a' }}>{e.adminEmail.split('@')[0]}</div>
                    </div>
                  </td>
                  <td style={{ padding: '14px 20px' }}>
                    <span style={{ display: 'inline-flex', alignItems: 'center', padding: '4px 10px', borderRadius: '8px', fontSize: '11px', fontWeight: 700, letterSpacing: '0.3px', background: style.bg, color: style.color, border: `2px solid ${style.border}` }}>
                      {e.action.replace(/_/g, ' ')}
                    </span>
                  </td>
                  <td style={{ padding: '14px 20px', color: '#64748b', fontSize: '12px' }}>
                    {e.entityType && (
                      <span style={{ fontWeight: 600, color: '#0f172a' }}>{e.entityType}</span>
                    )}
                    {e.entityId && <span style={{ color: '#94a3b8' }}> #{e.entityId}</span>}
                  </td>
                  <td style={{ padding: '14px 20px', color: '#64748b', fontSize: '12px', maxWidth: '280px' }}>
                    {e.description || '—'}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      {totalPages > 1 && (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '8px', marginTop: '20px' }}>
          <button onClick={() => setPage(p => Math.max(0, p - 1))} disabled={page === 0}
            style={{ padding: '8px 16px', borderRadius: '10px', border: '2px solid rgba(148,163,184,0.15)', background: page === 0 ? 'rgba(148,163,184,0.05)' : 'white', color: page === 0 ? '#94a3b8' : '#4f46e5', fontWeight: 700, cursor: page === 0 ? 'default' : 'pointer', fontSize: '13px' }}>
            ← Prev
          </button>
          <span style={{ fontSize: '13px', color: '#64748b', fontWeight: 600 }}>Page {page + 1} / {totalPages}</span>
          <button onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))} disabled={page >= totalPages - 1}
            style={{ padding: '8px 16px', borderRadius: '10px', border: '2px solid rgba(148,163,184,0.15)', background: page >= totalPages - 1 ? 'rgba(148,163,184,0.05)' : 'white', color: page >= totalPages - 1 ? '#94a3b8' : '#4f46e5', fontWeight: 700, cursor: page >= totalPages - 1 ? 'default' : 'pointer', fontSize: '13px' }}>
            Next →
          </button>
        </div>
      )}
    </div>
  );
}
