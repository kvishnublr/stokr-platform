import { useQuery } from '@tanstack/react-query';
import client from '../api/client';

export default function Positions() {
  const { data: deployments } = useQuery({ queryKey: ['deployments'], queryFn: () => client.get('/deployments').then((r) => r.data) });

  if (!deployments) return <div className="text-slate-500">Loading...</div>;

  return (
    <div>
      <div className="mb-8 animate-fade-in-up">
        <div className="flex items-center gap-3 mb-1">
          <div className="w-1 h-7 rounded-full bg-gradient-to-b from-indigo-500 to-violet-500" />
          <h1 className="text-3xl font-bold text-slate-900 tracking-tight">Positions & PnL</h1>
        </div>
        <p className="text-slate-400 text-sm ml-4">Monitor your open positions and profit/loss</p>
      </div>
      {deployments.length === 0 ? (
        <div className="card-crystal p-12 text-center">
          <svg className="w-10 h-10 text-slate-300 mx-auto mb-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1}><path strokeLinecap="round" strokeLinejoin="round" d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" /></svg>
          <p className="text-slate-400">No deployments yet</p>
        </div>
      ) : (
        deployments.map((d) => <DeploymentPositions key={d.id} deployment={d} />)
      )}
    </div>
  );
}

function DeploymentPositions({ deployment }) {
  const { data: positions } = useQuery({ queryKey: ['positions', deployment.id], queryFn: () => client.get(`/positions/${deployment.id}/open`).then((r) => r.data) });
  const { data: pnl } = useQuery({ queryKey: ['pnl', deployment.id], queryFn: () => client.get(`/pnl/${deployment.id}`).then((r) => r.data) });

  return (
    <div className="card-crystal p-5 mb-4">
      <div className="flex justify-between items-center mb-5">
        <div>
          <h2 className="font-semibold text-slate-800 text-sm">{deployment.strategyName || `Strategy #${deployment.strategyId}`}</h2>
          <p className="text-xs text-slate-400 mt-0.5">{deployment.mode} | {deployment.status}</p>
        </div>
        {pnl && (
          <div className="text-right">
            <p className="text-xs text-slate-500">Realized PnL</p>
            <p className={`text-xl font-bold ${(pnl.realizedPnl || 0) >= 0 ? 'text-emerald-600' : 'text-rose-600'}`}>₹{(pnl.realizedPnl || 0).toLocaleString()}</p>
          </div>
        )}
      </div>
      {positions?.length === 0 ? (
        <p className="text-slate-400 text-sm">No open positions</p>
      ) : (
        <table className="w-full text-sm table-crystal">
          <thead><tr>
            <th>Symbol</th>
            <th>Qty</th>
            <th>Avg Price</th>
            <th>Realized PnL</th>
            <th>Unrealized PnL</th>
          </tr></thead>
          <tbody>
            {positions?.map((p) => (
              <tr key={p.id}>
                <td className="font-medium text-slate-800">{p.symbol}</td>
                <td>{p.quantity}</td>
                <td>₹{p.avgPrice?.toFixed(2)}</td>
                <td className={`font-medium ${(p.realizedPnl || 0) >= 0 ? 'text-emerald-600' : 'text-rose-600'}`}>₹{(p.realizedPnl || 0).toFixed(2)}</td>
                <td className={`font-medium ${(p.unrealizedPnl || 0) >= 0 ? 'text-emerald-600' : 'text-rose-600'}`}>₹{(p.unrealizedPnl || 0).toFixed(2)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
