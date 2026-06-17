import { useQuery } from '@tanstack/react-query';
import client from '../../api/client';

export default function AdminBrokerHealth() {
  const { data: health, isLoading } = useQuery({
    queryKey: ['admin-broker-health'],
    queryFn: () => client.get('/admin/broker-health').then((r) => r.data),
    refetchInterval: 30000,
  });

  if (isLoading) return (
    <div className="flex items-center justify-center h-64">
      <div className="animate-spin w-6 h-6 border-2 border-indigo-500 border-t-transparent rounded-full" />
    </div>
  );

  return (
    <div className="animate-fade-in-up">
      <div className="flex items-center gap-3 mb-6">
        <div className="w-1 h-7 rounded-full bg-gradient-to-b from-indigo-500 to-violet-500" />
        <h1 className="text-3xl font-bold text-slate-800">Broker Health</h1>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {health?.map((entry, idx) => (
          <div key={idx} className="card-crystal p-5 hover-lift hover-glow" style={{ animationDelay: `${idx * 100}ms` }}>
            <div className="flex justify-between items-start mb-3">
              <div className="flex items-center gap-3">
                <div className={`w-9 h-9 rounded-xl flex items-center justify-center ${
                  entry.status === 'ACTIVE' || entry.status === 'HEALTHY'
                    ? 'bg-emerald-50 text-emerald-600'
                    : 'bg-rose-50 text-rose-600'
                }`}>
                  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                    {entry.status === 'ACTIVE' || entry.status === 'HEALTHY' ? (
                      <path strokeLinecap="round" strokeLinejoin="round" d="M9 12.75L11.25 15 15 9.75M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                    ) : (
                      <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m9-.75a9 9 0 11-18 0 9 9 0 0118 0zm-9 3.75h.008v.008H12v-.008z" />
                    )}
                  </svg>
                </div>
                <div>
                  <h3 className="font-semibold text-slate-800 text-sm">{entry.brokerName || entry.broker}</h3>
                  <span className={`inline-flex mt-1 px-2 py-0.5 rounded-md text-[10px] font-semibold tracking-wide uppercase ${
                    entry.status === 'ACTIVE' || entry.status === 'HEALTHY'
                      ? 'bg-emerald-50 text-emerald-600'
                      : 'bg-rose-50 text-rose-600'
                  }`}>{entry.status}</span>
                </div>
              </div>
            </div>
            <div className="space-y-1.5 pt-2 border-t border-slate-50">
              {entry.userEmail && <p className="text-xs text-slate-500 flex items-center gap-1.5"><span className="text-slate-400">User:</span> {entry.userEmail}</p>}
              {entry.clientId && <p className="text-xs text-slate-500 flex items-center gap-1.5"><span className="text-slate-400">Client:</span> {entry.clientId}</p>}
              {entry.tokenExpiry && <p className="text-xs text-slate-500 flex items-center gap-1.5"><span className="text-slate-400">Expires:</span> {new Date(entry.tokenExpiry).toLocaleString()}</p>}
              {entry.connections !== undefined && <p className="text-xs text-slate-500 flex items-center gap-1.5"><span className="text-slate-400">Connections:</span> {entry.connections}</p>}
            </div>
          </div>
        ))}
        {(!health || health.length === 0) && (
          <div className="col-span-3 py-16 text-center">
            <div className="w-16 h-16 rounded-2xl bg-slate-50 flex items-center justify-center mx-auto mb-4">
              <svg className="w-8 h-8 text-slate-300" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M9 12.75L11.25 15 15 9.75M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
            </div>
            <p className="text-slate-400 text-sm">No broker connections found</p>
          </div>
        )}
      </div>
    </div>
  );
}
