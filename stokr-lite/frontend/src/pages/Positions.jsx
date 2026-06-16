import { useQuery } from '@tanstack/react-query';
import client from '../api/client';

export default function Positions() {
  const { data: deployments } = useQuery({
    queryKey: ['deployments'],
    queryFn: () => client.get('/deployments').then((r) => r.data),
  });

  if (!deployments) return <div className="text-gray-500">Loading...</div>;

  return (
    <div>
      <h1 className="text-2xl font-bold mb-6">Positions & PnL</h1>
      {deployments.map((d) => (
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

  const { data: pnl } = useQuery({
    queryKey: ['pnl', deployment.id],
    queryFn: () => client.get(`/pnl/${deployment.id}`).then((r) => r.data),
  });

  return (
    <div className="bg-white rounded-lg shadow p-6 mb-4">
      <div className="flex justify-between items-center mb-4">
        <div>
          <h2 className="text-lg font-semibold">{deployment.strategyName || `Strategy #${deployment.strategyId}`}</h2>
          <p className="text-sm text-gray-500">{deployment.mode} | <span className={deployment.status === 'ACTIVE' ? 'text-green-600' : 'text-gray-500'}>{deployment.status}</span></p>
        </div>
        {pnl && (
          <div className="text-right">
            <p className="text-sm text-gray-500">Realized PnL</p>
            <p className={`text-xl font-bold ${(pnl.realizedPnl || 0) >= 0 ? 'text-green-600' : 'text-red-600'}`}>
              ₹{(pnl.realizedPnl || 0).toLocaleString()}
            </p>
          </div>
        )}
      </div>

      {positions?.length === 0 ? (
        <p className="text-gray-400 text-sm">No open positions</p>
      ) : (
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b text-left text-gray-500">
              <th className="pb-2">Symbol</th>
              <th className="pb-2">Qty</th>
              <th className="pb-2">Avg Price</th>
              <th className="pb-2">Realized PnL</th>
              <th className="pb-2">Unrealized PnL</th>
            </tr>
          </thead>
          <tbody>
            {positions?.map((p) => (
              <tr key={p.id} className="border-b">
                <td className="py-3 font-medium">{p.symbol}</td>
                <td className="py-3">{p.quantity}</td>
                <td className="py-3">₹{p.avgPrice?.toFixed(2)}</td>
                <td className={`py-3 ${(p.realizedPnl || 0) >= 0 ? 'text-green-600' : 'text-red-600'}`}>₹{(p.realizedPnl || 0).toFixed(2)}</td>
                <td className={`py-3 ${(p.unrealizedPnl || 0) >= 0 ? 'text-green-600' : 'text-red-600'}`}>₹{(p.unrealizedPnl || 0).toFixed(2)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
