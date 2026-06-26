import { useQuery } from '@tanstack/react-query';
import client from '../../api/client';
import { useEffect, useState, useRef } from 'react';
import { Link } from 'react-router-dom';

function AnimatedCounter({ value, duration = 1200 }) {
  const [display, setDisplay] = useState(0);
  const startTime = useRef(null);
  const startVal = useRef(0);

  useEffect(() => {
    startVal.current = display;
    startTime.current = null;
    let raf;
    const animate = (timestamp) => {
      if (!startTime.current) startTime.current = timestamp;
      const progress = Math.min((timestamp - startTime.current) / duration, 1);
      const eased = 1 - Math.pow(1 - progress, 3);
      setDisplay(Math.floor(startVal.current + (value - startVal.current) * eased));
      if (progress < 1) raf = requestAnimationFrame(animate);
    };
    raf = requestAnimationFrame(animate);
    return () => cancelAnimationFrame(raf);
  }, [value, duration]);

  return <span>{display.toLocaleString()}</span>;
}

export default function AdminDashboard() {
  const { data: dashboard } = useQuery({ queryKey: ['admin-dashboard'], queryFn: () => client.get('/admin/dashboard').then((r) => r.data) });
  const { data: killSwitch } = useQuery({ queryKey: ['kill-switch'], queryFn: () => client.get('/admin/kill-switch').then((r) => r.data) });
  const { data: marketStatus } = useQuery({ queryKey: ['market-status'], queryFn: () => client.get('/market/status').then((r) => r.data) });
  const { data: recentErrors } = useQuery({ queryKey: ['admin-errors-recent'], queryFn: () => client.get('/admin/errors', { params: { page: 0, size: 5 } }).then((r) => r.data?.content || r.data) });
  const { data: chartinkData, isLoading: chartinkLoading } = useQuery({
    queryKey: ['chartink-live-data'],
    queryFn: () => client.get('/admin/chartink/live-data').then((r) => r.data),
    refetchInterval: 5000
  });

  const userCount = dashboard?.userCount ?? 0;
  const activeDeployments = dashboard?.activeDeployments ?? 0;
  const orderStats = dashboard?.orderStats || {};
  const brokerStats = dashboard?.brokerStats || {};

  return (
    <div>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '32px', paddingBottom: '24px', borderBottom: '2px solid rgba(148,163,184,0.08)' }}>
        <div>
          <h1 style={{ fontSize: '32px', fontWeight: 900, background: 'linear-gradient(135deg, #0f172a 0%, #4f46e5 100%)', WebkitBackgroundClip: 'text', WebkitTextFillColor: 'transparent', letterSpacing: '-1px', marginBottom: '6px' }}>Admin Overview</h1>
          <p style={{ fontSize: '13px', color: 'var(--text-muted)' }}>Platform monitoring and controls</p>
        </div>
        <div className="live-indicator" style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', padding: '4px 10px', background: 'linear-gradient(135deg, rgba(16,185,129,0.15), rgba(52,211,153,0.1))', borderRadius: '8px', color: '#059669', fontWeight: 600, fontSize: '11px' }}>
          <div className="animate-pulse-dot" style={{ width: '6px', height: '6px', background: '#10b981', borderRadius: '50%' }} />
          System Healthy
        </div>
      </div>

      {/* Kill Switch Banner */}
      {killSwitch?.active && (
        <div className="card-crystal animate-fade-in-up" style={{ marginBottom: '32px', borderLeft: '4px solid #ef4444' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
            <div style={{ width: '48px', height: '48px', borderRadius: '14px', background: 'linear-gradient(135deg, #ef4444, #dc2626)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '24px', boxShadow: '0 8px 24px rgba(239,68,68,0.3)' }}>
              🛑
            </div>
            <div>
              <p style={{ color: '#dc2626', fontWeight: 700, fontSize: '15px' }}>Kill Switch Active</p>
              <p style={{ color: '#ef4444', fontSize: '13px', marginTop: '2px', opacity: 0.8 }}>All trading halted by {killSwitch.activatedBy} — {killSwitch.reason}</p>
            </div>
          </div>
        </div>
      )}

      {/* Stats Grid */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '18px', marginBottom: '32px' }}>
        <StatBox title="Total Users" value={userCount} icon="👥" color="#4f46e5" gradient="linear-gradient(90deg, #6366f1, #a78bfa)" />
        <StatBox title="Active Deployments" value={activeDeployments} icon="🚀" color="#059669" gradient="linear-gradient(90deg, #10b981, #34d399)" />
        <StatBox title="Orders Today" value={orderStats?.total || 0} icon="📋" color="#2563eb" gradient="linear-gradient(90deg, #3b82f6, #60a5fa)" />
        <StatBox title="Pending Orders" value={orderStats?.pending || 0} icon="⏳" color="#d97706" gradient="linear-gradient(90deg, #f59e0b, #fbbf24)" />
      </div>

      {/* Orders + Broker sub-stats */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '18px', marginBottom: '32px' }}>
        <div className="card-crystal" style={{ padding: '18px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '16px' }}>
            <div style={{ width: '32px', height: '32px', borderRadius: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '16px', background: 'linear-gradient(135deg, rgba(37,99,235,0.15), rgba(96,165,250,0.1))' }}>📋</div>
            <h3 style={{ fontSize: '14px', fontWeight: 700, color: '#0f172a' }}>Orders Breakdown</h3>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '10px' }}>
            <MiniStat label="Total" value={orderStats?.total || 0} color="#3b82f6" />
            <MiniStat label="Pending" value={orderStats?.pending || 0} color="#f59e0b" />
            <MiniStat label="Complete" value={orderStats?.complete || 0} color="#10b981" />
            <MiniStat label="Rejected" value={orderStats?.rejected || 0} color="#ef4444" />
          </div>
        </div>
        <div className="card-crystal" style={{ padding: '18px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '16px' }}>
            <div style={{ width: '32px', height: '32px', borderRadius: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '16px', background: 'linear-gradient(135deg, rgba(16,185,129,0.15), rgba(110,231,183,0.1))' }}>💓</div>
            <h3 style={{ fontSize: '14px', fontWeight: 700, color: '#0f172a' }}>Broker Health</h3>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '10px' }}>
            <MiniStat label="Total Active" value={brokerStats?.totalActive || 0} color="#64748b" />
            <MiniStat label="Healthy" value={brokerStats?.healthy || 0} color="#10b981" />
            <MiniStat label="Token Expired" value={brokerStats?.tokenExpired || 0} color="#ef4444" />
          </div>
        </div>
      </div>

      {/* Quick Actions */}
      <div style={{ marginBottom: '32px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '20px' }}>
          <div style={{ width: '4px', height: '20px', borderRadius: '999px', background: 'linear-gradient(180deg, #6366f1, #a78bfa)' }} />
          <h2 style={{ fontSize: '16px', fontWeight: 800, color: '#0f172a' }}>Quick Actions</h2>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '18px' }}>
          <QuickLink to="/admin/kill-switch" title="Kill Switch" desc="Emergency stop all trading" icon="🛑" color="linear-gradient(135deg, #ef4444, #f87171)" />
          <QuickLink to="/admin/deployments" title="Manage Deployments" desc="View and control all deployments" icon="🚀" color="linear-gradient(135deg, #6366f1, #8b5cf6)" />
          <QuickLink to="/admin/errors" title="Error Logs" desc="View recent system errors" icon="🐛" color="linear-gradient(135deg, #f59e0b, #fbbf24)" />
        </div>
      </div>

      {/* Market Status + Recent Errors */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '18px', marginBottom: '32px' }}>
        <div className="card-crystal" style={{ padding: '20px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '16px' }}>
            <div style={{ width: '32px', height: '32px', borderRadius: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '16px', background: 'linear-gradient(135deg, rgba(245,158,11,0.15), rgba(251,191,36,0.1))' }}>🏛️</div>
            <h3 style={{ fontSize: '14px', fontWeight: 700, color: '#0f172a' }}>Market Status</h3>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px', padding: '14px', borderRadius: '12px', background: 'linear-gradient(135deg, rgba(255,255,255,0.7), rgba(255,255,255,0.5))', border: '2px solid rgba(255,255,255,0.6)' }}>
            <div style={{ width: '10px', height: '10px', borderRadius: '50%', background: marketStatus?.isOpen ? '#10b981' : '#ef4444', animation: marketStatus?.isOpen ? 'pulse-dot 1.5s ease-in-out infinite' : 'none' }} />
            <div>
              <div style={{ fontSize: '14px', fontWeight: 700, color: '#0f172a' }}>{marketStatus?.isOpen ? 'Market is Open' : 'Market is Closed'}</div>
              <div style={{ fontSize: '11px', color: '#94a3b8', marginTop: '2px' }}>09:15 - 15:30 IST</div>
            </div>
          </div>
        </div>

        <div className="card-crystal" style={{ padding: '20px' }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '16px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
              <div style={{ width: '32px', height: '32px', borderRadius: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '16px', background: 'linear-gradient(135deg, rgba(239,68,68,0.15), rgba(248,113,113,0.1))' }}>🐛</div>
              <h3 style={{ fontSize: '14px', fontWeight: 700, color: '#0f172a' }}>Recent Errors</h3>
            </div>
            <Link to="/admin/errors" style={{ fontSize: '10px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.8px', padding: '4px 10px', borderRadius: '8px', background: 'rgba(99,102,241,0.08)', color: '#6366f1', textDecoration: 'none' }}>View All</Link>
          </div>
          {!recentErrors || recentErrors.length === 0 ? (
            <div style={{ textAlign: 'center', padding: '20px', color: '#94a3b8', fontSize: '13px' }}>
              <div style={{ fontSize: '24px', marginBottom: '6px' }}>✅</div>
              No recent errors
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
              {recentErrors.slice(0, 5).map((e) => (
                <div key={e.id} style={{ display: 'flex', alignItems: 'center', gap: '10px', padding: '8px 10px', borderRadius: '10px', background: 'rgba(255,255,255,0.6)', border: '1px solid rgba(148,163,184,0.1)' }}>
                  <div style={{ width: '6px', height: '6px', borderRadius: '50%', flexShrink: 0, background: e.severity === 'CRITICAL' ? '#ef4444' : e.severity === 'ERROR' ? '#f59e0b' : '#94a3b8' }} />
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontSize: '11px', fontWeight: 600, color: '#0f172a', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{e.message}</div>
                    <div style={{ fontSize: '10px', color: '#94a3b8', marginTop: '1px' }}>{e.errorType} · {new Date(e.createdAt).toLocaleTimeString()}</div>
                  </div>
                  <span style={{ fontSize: '9px', fontWeight: 700, padding: '2px 6px', borderRadius: '4px', flexShrink: 0, background: e.severity === 'CRITICAL' ? '#fef2f2' : e.severity === 'ERROR' ? '#fffbeb' : '#f8fafc', color: e.severity === 'CRITICAL' ? '#dc2626' : e.severity === 'ERROR' ? '#d97706' : '#64748b' }}>{e.severity}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Chartink Live Feed */}
      {chartinkData && (
        <div style={{ marginBottom: '32px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '20px' }}>
            <div style={{ width: '4px', height: '20px', borderRadius: '999px', background: 'linear-gradient(180deg, #f59e0b, #fbbf24)' }} />
            <h2 style={{ fontSize: '16px', fontWeight: 800, color: '#0f172a' }}>🔴 Chartink Live Feed (Premium)</h2>
            <div style={{ fontSize: '11px', color: '#10b981', fontWeight: 600, background: 'rgba(16,185,129,0.1)', padding: '2px 8px', borderRadius: '4px' }}>
              Live • {new Date().toLocaleTimeString()}
            </div>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '12px', marginBottom: '20px' }}>
            <ChartinkStatBox title="Total Hits (30m)" value={chartinkData.overall?.totalSignals} color="#f59e0b" />
            <ChartinkStatBox title="Executed" value={chartinkData.overall?.executed} color="#10b981" />
            <ChartinkStatBox title="Rejected" value={chartinkData.overall?.rejected} color="#ef4444" />
            <ChartinkStatBox title="Execution Rate" value={chartinkData.overall?.executionRate} color="#3b82f6" />
          </div>

          {chartinkData.topScanners && (
            <div style={{ marginBottom: '20px' }}>
              <h3 style={{ fontSize: '14px', fontWeight: 700, color: '#0f172a', marginBottom: '12px' }}>Top Scanners (Accuracy)</h3>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: '12px' }}>
                {chartinkData.topScanners.map((scanner, idx) => (
                  <div key={idx} className="card-crystal" style={{ padding: '12px', textAlign: 'center' }}>
                    <p style={{ fontSize: '12px', fontWeight: 600, color: '#64748b', marginBottom: '6px' }}>{scanner.scanner}</p>
                    <p style={{ fontSize: '18px', fontWeight: 800, color: '#4f46e5', marginBottom: '4px' }}>{scanner.hits}</p>
                    <p style={{ fontSize: '11px', color: '#10b981', fontWeight: 600 }}>✓ {scanner.accuracy}</p>
                  </div>
                ))}
              </div>
            </div>
          )}

          {chartinkData.scanners && (
            <div style={{ marginBottom: '20px' }}>
              <h3 style={{ fontSize: '14px', fontWeight: 700, color: '#0f172a', marginBottom: '12px' }}>Scanner Breakdown</h3>
              <div style={{ overflowX: 'auto', background: '#fff', borderRadius: '14px', padding: '14px' }}>
                <table style={{ width: '100%', fontSize: '12px', borderCollapse: 'collapse' }}>
                  <thead>
                    <tr style={{ borderBottom: '1px solid #e2e8f0' }}>
                      <th style={{ textAlign: 'left', padding: '8px', color: '#64748b', fontWeight: 600 }}>Scanner</th>
                      <th style={{ textAlign: 'center', padding: '8px', color: '#64748b', fontWeight: 600 }}>Hits</th>
                      <th style={{ textAlign: 'center', padding: '8px', color: '#64748b', fontWeight: 600 }}>Executed</th>
                      <th style={{ textAlign: 'center', padding: '8px', color: '#64748b', fontWeight: 600 }}>Rejected</th>
                      <th style={{ textAlign: 'center', padding: '8px', color: '#64748b', fontWeight: 600 }}>Target Hit</th>
                      <th style={{ textAlign: 'center', padding: '8px', color: '#64748b', fontWeight: 600 }}>Accuracy</th>
                      <th style={{ textAlign: 'center', padding: '8px', color: '#64748b', fontWeight: 600 }}>Hit Rate</th>
                    </tr>
                  </thead>
                  <tbody>
                    {Object.entries(chartinkData.scanners).slice(0, 10).map(([scanner, metrics], idx) => (
                      <tr key={idx} style={{ borderBottom: '1px solid #f1f5f9', background: idx % 2 === 0 ? '#fafbfc' : '#fff' }}>
                        <td style={{ padding: '8px', color: '#0f172a', fontWeight: 600 }}>{scanner}</td>
                        <td style={{ textAlign: 'center', padding: '8px', color: '#0f172a' }}>{metrics.totalHits}</td>
                        <td style={{ textAlign: 'center', padding: '8px', color: '#10b981', fontWeight: 600 }}>{metrics.executed}</td>
                        <td style={{ textAlign: 'center', padding: '8px', color: '#ef4444', fontWeight: 600 }}>{metrics.rejected}</td>
                        <td style={{ textAlign: 'center', padding: '8px', color: '#3b82f6', fontWeight: 600 }}>{metrics.targetHit}</td>
                        <td style={{ textAlign: 'center', padding: '8px', color: '#6366f1', fontWeight: 600 }}>{metrics.accuracy}</td>
                        <td style={{ textAlign: 'center', padding: '8px', color: '#8b5cf6', fontWeight: 600 }}>{metrics.hitRate}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {chartinkData.recentSignals && (
            <div>
              <h3 style={{ fontSize: '14px', fontWeight: 700, color: '#0f172a', marginBottom: '12px' }}>Recent Signal Stream</h3>
              <div style={{ maxHeight: '400px', overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '8px' }}>
                {chartinkData.recentSignals.map((signal, idx) => (
                  <div key={idx} className="card-crystal" style={{ padding: '10px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <div style={{ flex: 1 }}>
                      <p style={{ fontSize: '12px', fontWeight: 700, color: '#0f172a', marginBottom: '2px' }}>
                        <span style={{ color: signal.side === 'BUY' ? '#10b981' : '#ef4444', fontWeight: 800 }}>● {signal.side}</span>
                        {' '}{signal.symbol}{' '}
                        <span style={{ color: '#64748b', fontSize: '10px' }}>@{signal.scanner}</span>
                      </p>
                      <p style={{ fontSize: '10px', color: '#94a3b8' }}>{signal.reason}</p>
                    </div>
                    <div style={{ textAlign: 'right', marginLeft: '12px' }}>
                      <div style={{ fontSize: '11px', fontWeight: 600, color: getStatusColor(signal.status), marginBottom: '4px' }}>
                        {signal.status}
                      </div>
                      <div style={{ fontSize: '10px', color: '#94a3b8' }}>{new Date(signal.createdAt).toLocaleTimeString()}</div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      {/* Configuration & Mappings */}
      <div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '20px' }}>
          <div style={{ width: '4px', height: '20px', borderRadius: '999px', background: 'linear-gradient(180deg, #10b981, #34d399)' }} />
          <h2 style={{ fontSize: '16px', fontWeight: 800, color: '#0f172a' }}>Configuration &amp; Mappings</h2>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '18px' }}>
          <QuickLink to="/admin/universe-groups" title="Universe Groups" desc="Manage symbol universes" icon="🌌" color="linear-gradient(135deg, #10b981, #34d399)" />
          <QuickLink to="/admin/strategy-mappings" title="Strategy Mappings" desc="Map strategies to groups" icon="🔗" color="linear-gradient(135deg, #3b82f6, #60a5fa)" />
          <QuickLink to="/admin/strategy-configs" title="Strategy Configs" desc="Capital, sizing, risk settings" icon="🔧" color="linear-gradient(135deg, #a78bfa, #c084fc)" />
        </div>
      </div>
    </div>
  );
}

function ChartinkStatBox({ title, value, color }) {
  return (
    <div className="card-crystal animate-fade-in-up" style={{ padding: '12px', textAlign: 'center', borderTop: `3px solid ${color}` }}>
      <p style={{ fontSize: '11px', fontWeight: 600, color: '#64748b', marginBottom: '6px', textTransform: 'uppercase' }}>{title}</p>
      <p style={{ fontSize: '24px', fontWeight: 900, color: color }}>{value ?? '—'}</p>
    </div>
  );
}

function MiniStat({ label, value, color }) {
  return (
    <div style={{ textAlign: 'center', padding: '10px', borderRadius: '10px', background: 'rgba(255,255,255,0.6)', border: '1px solid rgba(148,163,184,0.1)' }}>
      <div style={{ fontSize: '20px', fontWeight: 900, color }}>{value}</div>
      <div style={{ fontSize: '9px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.5px', color: '#94a3b8', marginTop: '4px' }}>{label}</div>
    </div>
  );
}

function getStatusColor(status) {
  switch (status) {
    case 'EXECUTED': return '#10b981';
    case 'REJECTED': return '#ef4444';
    case 'GENERATED':
    case 'PENDING': return '#f59e0b';
    default: return '#64748b';
  }
}

function StatBox({ title, value, icon, color, gradient }) {
  return (
    <div className="stat-box-aurora animate-fade-in-up">
      <div style={{ position: 'absolute', top: 0, left: 0, right: 0, height: '4px', borderRadius: '18px 18px 0 0', background: gradient }} />
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '12px' }}>
        <span style={{ fontSize: '10px', fontWeight: 800, textTransform: 'uppercase', letterSpacing: '1px', color: '#94a3b8' }}>{title}</span>
        <div style={{ width: '36px', height: '36px', borderRadius: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '18px', background: `linear-gradient(135deg, ${color}15, ${color}0A)` }}>
          {icon}
        </div>
      </div>
      <div style={{ fontSize: '32px', fontWeight: 900, letterSpacing: '-1px', color }}>
        <AnimatedCounter value={value} />
      </div>
    </div>
  );
}

function QuickLink({ to, title, desc, icon, color }) {
  return (
    <Link to={to} className="card-crystal animate-fade-in-up" style={{ textDecoration: 'none', display: 'block' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '14px', marginBottom: '12px' }}>
        <div style={{ width: '44px', height: '44px', borderRadius: '12px', background: color, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '20px', boxShadow: '0 8px 24px rgba(99,102,241,0.2)' }}>
          {icon}
        </div>
        <h3 style={{ fontWeight: 700, color: '#0f172a', fontSize: '15px' }}>{title}</h3>
      </div>
      <p style={{ fontSize: '13px', color: '#64748b', lineHeight: 1.5 }}>{desc}</p>
    </Link>
  );
}
