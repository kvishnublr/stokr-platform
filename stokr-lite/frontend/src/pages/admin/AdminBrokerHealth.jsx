import { useQuery } from '@tanstack/react-query';
import client from '../../api/client';

export default function AdminBrokerHealth() {
  const { data: health, isLoading } = useQuery({
    queryKey: ['admin-broker-health'],
    queryFn: () => client.get('/admin/broker-health').then((r) => r.data),
    refetchInterval: 30000,
  });

  if (isLoading) return <div className="text-gray-500">Loading...</div>;

  return (
    <div>
      <h1 className="text-2xl font-bold mb-6">Broker Health</h1>
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {health?.map((entry, idx) => (
          <div key={idx} className="bg-white rounded-lg shadow p-4">
            <div className="flex justify-between items-start mb-2">
              <h3 className="font-semibold">{entry.brokerName || entry.broker}</h3>
              <span className={`px-2 py-1 rounded text-xs font-medium ${
                entry.status === 'ACTIVE' || entry.status === 'HEALTHY' ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'
              }`}>{entry.status}</span>
            </div>
            {entry.userEmail && <p className="text-sm text-gray-500">User: {entry.userEmail}</p>}
            {entry.clientId && <p className="text-sm text-gray-500">Client: {entry.clientId}</p>}
            {entry.tokenExpiry && <p className="text-sm text-gray-500">Token expires: {new Date(entry.tokenExpiry).toLocaleString()}</p>}
            {entry.connections !== undefined && <p className="text-sm text-gray-500">Active connections: {entry.connections}</p>}
          </div>
        ))}
        {(!health || health.length === 0) && (
          <p className="text-gray-400 col-span-3 text-center py-8">No broker connections found</p>
        )}
      </div>
    </div>
  );
}
