import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import client from '../api/client';

export default function Brokers() {
  const queryClient = useQueryClient();

  const { data: brokers, isLoading } = useQuery({
    queryKey: ['brokers'],
    queryFn: () => client.get('/brokers').then((r) => r.data),
  });

  const { data: supported } = useQuery({
    queryKey: ['supported-brokers'],
    queryFn: () => client.get('/brokers/supported').then((r) => r.data),
  });

  const disconnectMutation = useMutation({
    mutationFn: (id) => client.delete(`/brokers/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['brokers'] }),
  });

  const connectBroker = async (brokerName) => {
    try {
      const { data } = await client.get(`/brokers/${brokerName}/connect`);
      if (data.authUrl) window.open(data.authUrl, '_blank');
    } catch (err) {
      alert('Failed to get auth URL: ' + (err.response?.data?.error || err.message));
    }
  };

  if (isLoading) return <div className="text-gray-500">Loading...</div>;

  return (
    <div>
      <h1 className="text-2xl font-bold mb-6">Broker Connections</h1>

      {/* Connected Brokers */}
      <div className="bg-white rounded-lg shadow p-6 mb-6">
        <h2 className="text-lg font-semibold mb-4">Connected Brokers</h2>
        {brokers?.length === 0 ? (
          <p className="text-gray-500">No brokers connected yet. Connect one below.</p>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {brokers?.map((b) => (
              <div key={b.id} className="border rounded-lg p-4">
                <div className="flex justify-between items-start">
                  <div>
                    <h3 className="font-semibold text-lg">{b.brokerName}</h3>
                    <p className="text-sm text-gray-500">Client ID: {b.clientId}</p>
                  </div>
                  <span className={`px-2 py-1 rounded text-xs font-medium ${b.status === 'ACTIVE' ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'}`}>
                    {b.status}
                  </span>
                </div>
                <button onClick={() => disconnectMutation.mutate(b.id)}
                  className="mt-3 text-red-600 hover:text-red-800 text-sm font-medium">
                  Disconnect
                </button>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Available Brokers */}
      <div className="bg-white rounded-lg shadow p-6">
        <h2 className="text-lg font-semibold mb-4">Connect a Broker</h2>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {(supported || ['ZERODHA', 'DHAN', 'FYERS']).map((name) => {
            const brokerName = typeof name === 'string' ? name : name.name;
            const isConnected = brokers?.some((b) => b.brokerName === brokerName);
            return (
              <div key={brokerName} className="border rounded-lg p-4 text-center">
                <h3 className="font-semibold mb-2">{brokerName}</h3>
                {isConnected ? (
                  <span className="text-green-600 text-sm">Already connected</span>
                ) : (
                  <button onClick={() => connectBroker(brokerName)}
                    className="bg-blue-600 text-white px-4 py-2 rounded hover:bg-blue-700 text-sm transition">
                    Connect
                  </button>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
