import { useQuery } from '@tanstack/react-query';
import client from '../api/client';

export default function Orders() {
  const { data: deployments } = useQuery({
    queryKey: ['deployments'],
    queryFn: () => client.get('/deployments').then((r) => r.data),
  });

  if (!deployments) return <div className="text-gray-500">Loading orders...</div>;

  if (deployments.length === 0) {
    return (
      <div>
        <h1 className="text-2xl font-bold mb-6">Orders & Trades</h1>
        <div className="bg-white rounded-lg shadow p-8 text-center text-gray-400">
          No deployments yet. Create a deployment to see orders here.
        </div>
      </div>
    );
  }

  return (
    <div>
      <h1 className="text-2xl font-bold mb-6">Orders & Trades</h1>
      {deployments.map((d) => (
        <DeploymentOrders key={d.id} deployment={d} />
      ))}
    </div>
  );
}

function DeploymentOrders({ deployment }) {
  const { data: orders, isLoading } = useQuery({
    queryKey: ['orders', deployment.id],
    queryFn: () =>
      client.get('/orders', { params: { deploymentId: deployment.id, page: 0, size: 20 } })
        .then((r) => r.data?.content || r.data),
  });

  return (
    <div className="bg-white rounded-lg shadow overflow-hidden mb-4">
      <div className="px-6 py-4 border-b">
        <h2 className="text-lg font-semibold">{deployment.strategyName || `Strategy #${deployment.strategyId}`}</h2>
        <p className="text-sm text-gray-500">{deployment.mode} | <span className={deployment.status === 'ACTIVE' ? 'text-green-600' : 'text-gray-500'}>{deployment.status}</span></p>
      </div>
      <table className="w-full text-sm">
        <thead className="bg-gray-50">
          <tr className="text-left text-gray-500">
            <th className="p-4">Time</th>
            <th className="p-4">Symbol</th>
            <th className="p-4">Side</th>
            <th className="p-4">Qty</th>
            <th className="p-4">Price</th>
            <th className="p-4">Type</th>
            <th className="p-4">Status</th>
            <th className="p-4">Broker Order ID</th>
          </tr>
        </thead>
        <tbody>
          {isLoading && (
            <tr><td colSpan="8" className="p-4 text-center text-gray-400">Loading...</td></tr>
          )}
          {!isLoading && (!orders || orders.length === 0) && (
            <tr><td colSpan="8" className="p-8 text-center text-gray-400">No orders yet</td></tr>
          )}
          {orders?.map((o) => (
            <tr key={o.id} className="border-t hover:bg-gray-50">
              <td className="p-4 text-gray-500 text-xs">{new Date(o.createdAt).toLocaleString()}</td>
              <td className="p-4 font-medium">{o.symbol}</td>
              <td className="p-4">
                <span className={`px-2 py-1 rounded text-xs font-medium ${o.side === 'BUY' ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'}`}>{o.side}</span>
              </td>
              <td className="p-4">{o.quantity}</td>
              <td className="p-4">{o.price ? `₹${o.price}` : '-'}</td>
              <td className="p-4 text-xs">{o.orderType}</td>
              <td className="p-4">
                <span className={`px-2 py-1 rounded text-xs font-medium ${
                  o.status === 'COMPLETE' ? 'bg-green-100 text-green-700' :
                  o.status === 'REJECTED' ? 'bg-red-100 text-red-700' :
                  o.status === 'CANCELLED' ? 'bg-gray-100 text-gray-600' :
                  'bg-yellow-100 text-yellow-700'
                }`}>{o.status}</span>
              </td>
              <td className="p-4 text-xs text-gray-400">{o.brokerOrderId || '-'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
