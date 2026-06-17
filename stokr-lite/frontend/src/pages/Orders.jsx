import { useQuery } from '@tanstack/react-query';
import client from '../api/client';

export default function Orders() {
  const { data: deployments } = useQuery({ queryKey: ['deployments'], queryFn: () => client.get('/deployments').then((r) => r.data) });

  if (!deployments) return <div className="text-slate-500">Loading orders...</div>;
  if (deployments.length === 0) return (
    <div>
      <div className="mb-8 animate-fade-in-up">
        <div className="flex items-center gap-3 mb-1">
          <div className="w-1 h-7 rounded-full bg-gradient-to-b from-indigo-500 to-violet-500" />
          <h1 className="text-3xl font-bold text-slate-900 tracking-tight">Orders & Trades</h1>
        </div>
        <p className="text-slate-400 text-sm ml-4">Track all your order executions</p>
      </div>
      <div className="card-crystal p-12 text-center">
        <svg className="w-10 h-10 text-slate-300 mx-auto mb-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1}><path strokeLinecap="round" strokeLinejoin="round" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2" /></svg>
        <p className="text-slate-400">No deployments yet. Create a deployment to see orders here.</p>
      </div>
    </div>
  );

  return (
    <div>
      <div className="mb-8 animate-fade-in-up">
        <div className="flex items-center gap-3 mb-1">
          <div className="w-1 h-7 rounded-full bg-gradient-to-b from-indigo-500 to-violet-500" />
          <h1 className="text-3xl font-bold text-slate-900 tracking-tight">Orders & Trades</h1>
        </div>
        <p className="text-slate-400 text-sm ml-4">Track all your order executions</p>
      </div>
      {deployments.map((d) => <DeploymentOrders key={d.id} deployment={d} />)}
    </div>
  );
}

function DeploymentOrders({ deployment }) {
  const { data: orders, isLoading } = useQuery({
    queryKey: ['orders', deployment.id],
    queryFn: () => client.get('/orders', { params: { deploymentId: deployment.id, page: 0, size: 20 } }).then((r) => r.data?.content || r.data),
  });

  return (
    <div className="card-crystal overflow-hidden mb-4">
      <div className="px-5 py-4 border-b border-slate-100 flex items-center justify-between">
        <div>
          <h2 className="font-semibold text-slate-800 text-sm">{deployment.strategyName || `Strategy #${deployment.strategyId}`}</h2>
          <p className="text-xs text-slate-400">{deployment.mode}</p>
        </div>
      </div>
      <table className="w-full text-sm table-crystal">
        <thead><tr>
          <th>Time</th>
          <th>Symbol</th>
          <th>Side</th>
          <th>Qty</th>
          <th>Price</th>
          <th>Status</th>
        </tr></thead>
        <tbody>
          {isLoading && <tr><td colSpan="6" className="p-6 text-center text-slate-400">Loading...</td></tr>}
          {!isLoading && (!orders || orders.length === 0) && <tr><td colSpan="6" className="p-8 text-center text-slate-400">No orders yet</td></tr>}
          {orders?.map((o) => (
            <tr key={o.id}>
              <td className="text-xs text-slate-500">{new Date(o.createdAt).toLocaleString()}</td>
              <td className="font-medium text-slate-800">{o.symbol}</td>
              <td><span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${o.side === 'BUY' ? 'bg-emerald-50 text-emerald-600 border border-emerald-200' : 'bg-rose-50 text-rose-600 border border-rose-200'}`}>{o.side}</span></td>
              <td>{o.quantity}</td>
              <td>{o.price ? `₹${o.price}` : '-'}</td>
              <td><span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${o.status === 'COMPLETE' ? 'bg-emerald-50 text-emerald-600 border border-emerald-200' : o.status === 'REJECTED' ? 'bg-rose-50 text-rose-600 border border-rose-200' : 'bg-amber-50 text-amber-600 border border-amber-200'}`}>{o.status}</span></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
