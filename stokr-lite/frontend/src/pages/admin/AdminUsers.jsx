import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import client from '../../api/client';

export default function AdminUsers() {
  const qc = useQueryClient();
  const [confirmId, setConfirmId] = useState(null); // user id pending role change confirm

  const { data: users, isLoading } = useQuery({
    queryKey: ['admin-users'],
    queryFn: () => client.get('/admin/users').then((r) => r.data),
  });

  const patch = useMutation({
    mutationFn: ({ id, body }) => client.patch(`/admin/users/${id}`, body).then(r => r.data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-users'] }),
  });

  const toggleStatus = (u) => patch.mutate({ id: u.id, body: { enabled: !u.enabled } });

  const toggleRole = (u) => {
    const newRole = u.role === 'ADMIN' ? 'TRADER' : 'ADMIN';
    patch.mutate({ id: u.id, body: { role: newRole } });
    setConfirmId(null);
  };

  if (isLoading) return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '300px' }}>
      <div className="animate-spin" style={{ width: '32px', height: '32px', border: '3px solid rgba(99,102,241,0.2)', borderTopColor: '#6366f1', borderRadius: '50%' }} />
    </div>
  );

  return (
    <div className="animate-fade-in-up">
      {/* Confirm dialog for role change */}
      {confirmId && (() => {
        const u = users?.find(x => x.id === confirmId);
        if (!u) return null;
        const newRole = u.role === 'ADMIN' ? 'TRADER' : 'ADMIN';
        return (
          <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)', zIndex: 1000, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <div className="card-crystal" style={{ maxWidth: '400px', width: '90%', padding: '28px' }}>
              <div style={{ fontSize: '28px', textAlign: 'center', marginBottom: '16px' }}>⚠️</div>
              <h3 style={{ fontSize: '18px', fontWeight: 800, color: '#0f172a', textAlign: 'center', marginBottom: '8px' }}>Change Role?</h3>
              <p style={{ fontSize: '13px', color: '#64748b', textAlign: 'center', marginBottom: '24px' }}>
                Change <strong>{u.email}</strong> from <strong>{u.role}</strong> to <strong>{newRole}</strong>?
                This affects their access immediately.
              </p>
              <div style={{ display: 'flex', gap: '12px' }}>
                <button onClick={() => setConfirmId(null)} style={{ flex: 1, padding: '10px', borderRadius: '10px', border: '2px solid rgba(148,163,184,0.2)', background: 'white', fontWeight: 700, cursor: 'pointer', fontSize: '14px' }}>Cancel</button>
                <button onClick={() => toggleRole(u)} style={{ flex: 1, padding: '10px', borderRadius: '10px', border: 'none', background: 'linear-gradient(135deg, #6366f1, #8b5cf6)', color: 'white', fontWeight: 700, cursor: 'pointer', fontSize: '14px' }}>Confirm</button>
              </div>
            </div>
          </div>
        );
      })()}

      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '32px', paddingBottom: '24px', borderBottom: '2px solid rgba(148,163,184,0.08)' }}>
        <div>
          <h1 style={{ fontSize: '32px', fontWeight: 900, background: 'linear-gradient(135deg, #0f172a 0%, #4f46e5 100%)', WebkitBackgroundClip: 'text', WebkitTextFillColor: 'transparent', letterSpacing: '-1px', marginBottom: '6px' }}>User Management</h1>
          <p style={{ fontSize: '13px', color: 'var(--text-muted)' }}>Manage platform users, roles, and account status</p>
        </div>
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', padding: '6px 14px', background: 'linear-gradient(135deg, rgba(99,102,241,0.1), rgba(167,139,250,0.06))', borderRadius: '10px', color: '#4f46e5', fontWeight: 700, fontSize: '13px', border: '2px solid rgba(99,102,241,0.1)' }}>
          👥 {users?.length || 0} Users
        </div>
      </div>

      <div className="card-crystal" style={{ padding: 0, overflow: 'hidden' }}>
        <table className="table-crystal" style={{ width: '100%' }}>
          <thead>
            <tr>
              <th style={{ padding: '16px 20px' }}>User</th>
              <th style={{ padding: '16px 20px' }}>Role</th>
              <th style={{ padding: '16px 20px' }}>Status</th>
              <th style={{ padding: '16px 20px' }}>Registered</th>
              <th style={{ padding: '16px 20px' }}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {(!users || users.length === 0) && (
              <tr>
                <td colSpan="5" style={{ padding: '48px 20px', textAlign: 'center', color: '#94a3b8', fontSize: '14px' }}>
                  <div style={{ fontSize: '32px', marginBottom: '8px' }}>👤</div>
                  No users found
                </td>
              </tr>
            )}
            {users?.map((u, i) => (
              <tr key={u.id} style={{ animationDelay: `${i * 50}ms`, animation: 'fadeInUp 0.3s ease forwards', opacity: 0 }}>
                <td style={{ padding: '14px 20px' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                    <div style={{ width: '36px', height: '36px', borderRadius: '10px', background: 'linear-gradient(135deg, #a78bfa, #60a5fa)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '13px', fontWeight: 700, color: 'white' }}>
                      {u.email.substring(0, 2).toUpperCase()}
                    </div>
                    <div>
                      <div style={{ fontWeight: 700, color: '#0f172a', fontSize: '14px' }}>{u.email}</div>
                      <div style={{ fontSize: '11px', color: '#94a3b8', marginTop: '1px' }}>ID: {u.id}</div>
                    </div>
                  </div>
                </td>
                <td style={{ padding: '14px 20px' }}>
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', padding: '4px 10px', borderRadius: '8px', fontSize: '11px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.5px', background: u.role === 'ADMIN' ? 'linear-gradient(135deg, rgba(99,102,241,0.12), rgba(139,92,246,0.08))' : 'linear-gradient(135deg, rgba(56,189,248,0.12), rgba(125,211,252,0.08))', color: u.role === 'ADMIN' ? '#4f46e5' : '#0284c7', border: '2px solid', borderColor: u.role === 'ADMIN' ? 'rgba(99,102,241,0.15)' : 'rgba(56,189,248,0.15)' }}>
                    {u.role === 'ADMIN' ? '👑' : '👤'} {u.role}
                  </span>
                </td>
                <td style={{ padding: '14px 20px' }}>
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', padding: '4px 10px', borderRadius: '8px', fontSize: '11px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.5px', background: u.enabled ? 'linear-gradient(135deg, rgba(16,185,129,0.12), rgba(52,211,153,0.08))' : 'linear-gradient(135deg, rgba(239,68,68,0.12), rgba(248,113,113,0.08))', color: u.enabled ? '#059669' : '#dc2626', border: '2px solid', borderColor: u.enabled ? 'rgba(16,185,129,0.15)' : 'rgba(239,68,68,0.15)' }}>
                    <span style={{ width: '6px', height: '6px', borderRadius: '50%', background: u.enabled ? '#10b981' : '#ef4444' }} />
                    {u.enabled ? 'Active' : 'Disabled'}
                  </span>
                </td>
                <td style={{ padding: '14px 20px', color: '#64748b', fontSize: '13px' }}>{new Date(u.createdAt).toLocaleDateString()}</td>
                <td style={{ padding: '14px 20px' }}>
                  <div style={{ display: 'flex', gap: '8px' }}>
                    {/* Toggle status */}
                    <button
                      onClick={() => toggleStatus(u)}
                      disabled={patch.isPending}
                      title={u.enabled ? 'Disable account' : 'Enable account'}
                      style={{ padding: '6px 12px', borderRadius: '8px', border: '2px solid', borderColor: u.enabled ? 'rgba(239,68,68,0.2)' : 'rgba(16,185,129,0.2)', background: u.enabled ? 'rgba(239,68,68,0.07)' : 'rgba(16,185,129,0.07)', color: u.enabled ? '#dc2626' : '#059669', fontWeight: 700, cursor: patch.isPending ? 'default' : 'pointer', fontSize: '11px', whiteSpace: 'nowrap' }}>
                      {u.enabled ? '🚫 Disable' : '✅ Enable'}
                    </button>
                    {/* Toggle role */}
                    <button
                      onClick={() => setConfirmId(u.id)}
                      disabled={patch.isPending}
                      title={u.role === 'ADMIN' ? 'Demote to Trader' : 'Promote to Admin'}
                      style={{ padding: '6px 12px', borderRadius: '8px', border: '2px solid rgba(99,102,241,0.2)', background: 'rgba(99,102,241,0.07)', color: '#4f46e5', fontWeight: 700, cursor: patch.isPending ? 'default' : 'pointer', fontSize: '11px', whiteSpace: 'nowrap' }}>
                      {u.role === 'ADMIN' ? '👤 Demote' : '👑 Promote'}
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
