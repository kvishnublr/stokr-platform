import { useQuery } from '@tanstack/react-query';
import client from '../api/client';

export default function Positions() {
  const { data: deployments } = useQuery({ queryKey: ['deployments'], queryFn: () => client.get('/deployments').then((r) => r.data) });

  if (!deployments) return <div className="text-slate-500">Loading...</div>;

  return (
    <div>
      <div className="mb-8">
        <h1 className="text-2xl font-bold text-slate-800">Positions & PnL</h1>
        <p className="text-slate-500 text-sm mt-1">Monitor your open positions and profit/loss</p>
      </div>
      {deployments.length === 0 ? (
        <div className="bg-white rounded-2xl border border-slate-200/60 shadow-sm p-12 text-center">
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
    <div className="bg-white rounded-2xl border border-slate-200/60 shadow-sm p-6 mb-5">
      <div className="flex justify-between items-center mb-5">
        <div>
          <h2 className="font-semibold text-slate-800">{deployment.strategyName || `Strategy #${deployment.strategyId}`}</h2>
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
        <table className="w-full text-sm">
          <thead><tr className="border-b border-slate-100 text-left text-slate-500">
            <th className="pb-3 text-xs font-semibold uppercase">Symbol</th>
            <th className="pb-3 text-xs font-semibold uppercase">Qty</th>
            <th className="pb-3 text-xs font-semibold uppercase">Avg Price</th>
            <th className="pb-3 text-xs font-semibold uppercase">Realized PnL</th>
            <th className="pb-3 text-xs font-semibold uppercase">Unrealized PnL</th>
          </tr></thead>
          <tbody>
            {positions?.map((p) => (
              <tr key={p.id} className="border-b border-slate-50">
                <td className="py-3 font-medium text-slate-800">{p.symbol}</td>
                <td className="py-3">{p.quantity}</td>
                <td className="py-3">₹{p.avgPrice?.toFixed(2)}</td>
                <td className={`py-3 font-medium ${(p.realizedPnl || 0) >= 0 ? 'text-emerald-600' : 'text-rose-600'}`}>₹{(p.realizedPnl || 0).toFixed(2)}</td>
                <td className={`py-3 font-medium ${(p.unrealizedPnl || 0) >= 0 ? 'text-emerald-600' : 'text-rose-600'}`}>₹{(p.unrealizedPnl || 0).toFixed(2)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
