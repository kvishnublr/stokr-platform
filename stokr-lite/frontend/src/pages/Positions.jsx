import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import client from '../api/client';

export default function Positions() {
  const navigate = useNavigate();
  const { data: deployments, isLoading, isError } = useQuery({
    queryKey: ['deployments'],
    queryFn: () => client.get('/deployments').then((r) => r.data),
  });

  return (
    <div>
      <div style={{ marginBottom: '32px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '4px' }}>
          <div style={{ width: '4px', height: '28px', borderRadius: '999px', background: 'linear-gradient(180deg, #6366f1, #7c3aed)' }} />
          <h1 style={{ fontSize: '28px', fontWeight: 800, color: '#0f172a', margin: 0, letterSpacing: '-0.5px' }}>Positions & P&L</h1>
        </div>
        <p style={{ color: '#94a3b8', fontSize: '14px', margin: 0, paddingLeft: '16px' }}>Monitor your open positions and profit/loss</p>
      </div>

      {isLoading && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
          {[1,2].map(i => (
            <div key={i} className="skeleton" style={{ height: '120px', borderRadius: '16px' }} />
          ))}
        </div>
      )}

      {isError && (
        <div style={{ background: '#fef2f2', border: '1px solid #fca5a5', borderRadius: '16px', padding: '32px', textAlign: 'center' }}>
          <div style={{ fontSize: '32px', marginBottom: '12px' }}>⚠️</div>
          <div style={{ fontWeight: 700, color: '#dc2626', marginBottom: '6px' }}>Could not connect to backend</div>
          <div style={{ color: '#6b7280', fontSize: '13px' }}>Make sure the backend server is running at localhost:8080</div>
        </div>
      )}

      {!isLoading && !isError && deployments?.length === 0 && (
        <div style={{ background: 'white', border: '1px solid #e5e7eb', borderRadius: '16px', padding: '64px 32px', textAlign: 'center' }}>
          <div style={{ fontSize: '40px', marginBottom: '16px' }}>📈</div>
          <div style={{ fontWeight: 700, color: '#374151', fontSize: '16px', marginBottom: '8px' }}>No active deployments</div>
          <div style={{ color: '#9ca3af', fontSize: '13px', marginBottom: '24px' }}>Create a deployment to start tracking positions and P&L</div>
          <button
            onClick={() => navigate('/deployments')}
            style={{ padding: '10px 24px', background: 'linear-gradient(135deg, #6366f1, #7c3aed)', color: 'white', border: 'none', borderRadius: '10px', fontWeight: 700, fontSize: '14px', cursor: 'pointer' }}
          >
            Go to Deployments
          </button>
        </div>
      )}

      {!isLoading && !isError && deployments?.map((d) => (
        <DeploymentPositions key={d.id} deployment={d} />
      ))}
    </div>
  );
}

function DeploymentPositions({ deployment }) {
  const { data: positions } = useQuery({
    queryKey: ['positions', deployment.id],
    queryFn: () => client.get(`/positions/${deployment.id}/open`).then((r) => r.data),
  });
  const todayPnl = deployment.todayPnl ?? 0;

  return (
    <div style={{ background: 'white', border: '1px solid #e5e7eb', borderRadius: '16px', padding: '20px', marginBottom: '16px', boxShadow: '0 1px 4px rgba(0,0,0,0.04)' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px', paddingBottom: '12px', borderBottom: '1px solid #f3f4f6' }}>
        <div>
          <div style={{ fontWeight: 700, fontSize: '15px', color: '#111827' }}>{deployment.strategyName || `Strategy #${deployment.strategyId}`}</div>
          <div style={{ fontSize: '12px', color: '#9ca3af', marginTop: '2px' }}>
            {deployment.mode === 'LIVE' ? '🔴' : '📄'} {deployment.mode} · {deployment.status}
          </div>
        </div>
        <div style={{ textAlign: 'right' }}>
          <div style={{ fontSize: '11px', color: '#9ca3af', marginBottom: '2px' }}>Today P&L</div>
          <div style={{ fontSize: '22px', fontWeight: 800, color: todayPnl >= 0 ? '#059669' : '#dc2626' }}>
            {todayPnl >= 0 ? '+' : ''}₹{Number(todayPnl).toFixed(0)}
          </div>
        </div>
      </div>

      {!positions || positions.length === 0 ? (
        <div style={{ color: '#9ca3af', fontSize: '13px', padding: '12px 0' }}>No open positions</div>
      ) : (
        <table style={{ width: '100%', fontSize: '13px', borderCollapse: 'collapse' }}>
          <thead>
            <tr>
              {['Symbol', 'Qty', 'Avg Price', 'Realized P&L', 'Unrealized P&L'].map(h => (
                <th key={h} style={{ padding: '8px 10px', textAlign: 'left', fontWeight: 700, fontSize: '10px', textTransform: 'uppercase', letterSpacing: '0.05em', color: '#9ca3af', borderBottom: '1px solid #f3f4f6' }}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {positions.map((p) => {
              const rpnl = p.realizedPnl || 0;
              const upnl = p.unrealizedPnl || 0;
              return (
                <tr key={p.id} style={{ borderBottom: '1px solid #f9fafb' }}>
                  <td style={{ padding: '12px 10px', fontWeight: 700, color: '#111827' }}>{p.symbol}</td>
                  <td style={{ padding: '12px 10px', color: '#374151' }}>{p.quantity}</td>
                  <td style={{ padding: '12px 10px', color: '#374151' }}>₹{p.avgPrice?.toFixed(2)}</td>
                  <td style={{ padding: '12px 10px', fontWeight: 700, color: rpnl >= 0 ? '#059669' : '#dc2626' }}>
                    {rpnl >= 0 ? '+' : ''}₹{rpnl.toFixed(2)}
                  </td>
                  <td style={{ padding: '12px 10px', fontWeight: 700, color: upnl >= 0 ? '#059669' : '#dc2626' }}>
                    {upnl >= 0 ? '+' : ''}₹{upnl.toFixed(2)}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </div>
  );
}
