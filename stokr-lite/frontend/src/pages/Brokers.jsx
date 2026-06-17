import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import client from '../api/client';

const BROKER_META = {
  ZERODHA: { color: 'from-rose-500 to-red-600', letter: 'Z', desc: 'India\'s largest stock broker' },
  DHAN: { color: 'from-cyan-500 to-blue-600', letter: 'D', desc: 'Modern trading platform' },
  FYERS: { color: 'from-emerald-500 to-green-600', letter: 'F', desc: 'Commission-free trading' },
};

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

  if (isLoading) return <div className="text-slate-500">Loading...</div>;

  return (
    <div>
      <div className="mb-8">
        <h1 className="text-2xl font-bold text-slate-800">Broker Connections</h1>
        <p className="text-slate-500 text-sm mt-1">Connect your trading accounts to enable live execution</p>
      </div>

      {/* Connected Brokers */}
      {brokers?.length > 0 && (
        <div className="mb-8">
          <h2 className="text-sm font-semibold text-slate-500 uppercase tracking-wide mb-4">Connected Accounts</h2>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {brokers?.map((b) => {
              const meta = BROKER_META[b.brokerName] || { color: 'from-slate-500 to-slate-600', letter: b.brokerName[0] };
              return (
                <div key={b.id} className="bg-white rounded-2xl border border-slate-200/60 shadow-sm p-5">
                  <div className="flex items-start justify-between mb-3">
                    <div className="flex items-center gap-3">
                      <div className={`w-10 h-10 rounded-xl bg-gradient-to-br ${meta.color} flex items-center justify-center text-white font-bold text-sm shadow-lg`}>
                        {meta.letter}
                      </div>
                      <div>
                        <h3 className="font-semibold text-slate-800 text-sm">{b.brokerName}</h3>
                        <p className="text-xs text-slate-400">{b.clientId}</p>
                      </div>
                    </div>
                    <span className="flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-semibold bg-emerald-50 text-emerald-600 ring-1 ring-emerald-200">
                      <span className="w-1.5 h-1.5 rounded-full bg-emerald-500" />
                      Active
                    </span>
                  </div>
                  <button onClick={() => disconnectMutation.mutate(b.id)}
                    className="text-rose-500 hover:text-rose-700 text-xs font-medium transition mt-2">
                    Disconnect
                  </button>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* Available Brokers */}
      <div>
        <h2 className="text-sm font-semibold text-slate-500 uppercase tracking-wide mb-4">Available Brokers</h2>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-5">
          {(supported || ['ZERODHA', 'DHAN', 'FYERS']).map((name) => {
            const brokerName = typeof name === 'string' ? name : name.name;
            const meta = BROKER_META[brokerName] || { color: 'from-slate-500 to-slate-600', letter: brokerName[0], desc: 'Trading broker' };
            const isConnected = brokers?.some((b) => b.brokerName === brokerName);
            return (
              <div key={brokerName} className="bg-white rounded-2xl border border-slate-200/60 shadow-sm hover:shadow-md transition-all duration-200 overflow-hidden">
                <div className={`h-2 bg-gradient-to-r ${meta.color}`} />
                <div className="p-6">
                  <div className="flex items-center gap-3 mb-3">
                    <div className={`w-12 h-12 rounded-xl bg-gradient-to-br ${meta.color} flex items-center justify-center text-white font-bold text-lg shadow-lg`}>
                      {meta.letter}
                    </div>
                    <div>
                      <h3 className="font-bold text-slate-800">{brokerName}</h3>
                      <p className="text-xs text-slate-400">{meta.desc}</p>
                    </div>
                  </div>
                  {isConnected ? (
                    <div className="flex items-center gap-2 py-2.5 px-4 rounded-xl bg-emerald-50 text-emerald-600 text-sm font-medium">
                      <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                        <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                      </svg>
                      Connected
                    </div>
                  ) : (
                    <button onClick={() => connectBroker(brokerName)}
                      className={`w-full py-2.5 rounded-xl bg-gradient-to-r ${meta.color} text-white text-sm font-medium hover:opacity-90 transition shadow-lg shadow-${meta.color.split('-')[1]}-500/20`}>
                      Connect
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
