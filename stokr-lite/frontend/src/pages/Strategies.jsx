import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import client from '../api/client';

const STRATEGY_ICONS = {
  ORB: 'M13 10V3L4 14h7v7l9-11h-7z',
  VWAP_BOUNCE: 'M13 7h8m0 0v8m0-8l-8 8-4-4-6 6',
  GAP_FILL: 'M7 12l3-3 3 3 4-4M8 21l4-4 4 4M3 4h18M4 4h16v12a1 1 0 01-1 1H5a1 1 0 01-1-1V4z',
  INSIDER_MOMENTUM: 'M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z',
  INSTITUTIONAL_FOOTPRINT: 'M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5M9 7h1m-1 4h1m4-4h1m-1 4h1m-5 10v-5a1 1 0 011-1h2a1 1 0 011 1v5m-4 0h4',
};

const PROFITABLE_STRATEGIES = new Set([
  'OVERSOLD_BOUNCE',
  'MORNING_SURGE_REVERSAL',
  'MICRO_V_REVERSAL',
  'EMA50_DISTANCE',
  'THREE_RED_DAYS',
  'INSIDER_MOMENTUM',
  'INSTITUTIONAL_FOOTPRINT',
]);

export default function Strategies() {
  const { data: strategies, isLoading } = useQuery({
    queryKey: ['strategies'],
    queryFn: () => client.get('/strategies').then((r) => r.data),
  });

  if (isLoading) return <div className="text-slate-500">Loading strategies...</div>;

  return (
    <div>
      <div className="mb-8 animate-fade-in-up">
        <div className="flex items-center gap-3 mb-1">
          <div className="w-1 h-7 rounded-full bg-gradient-to-b from-indigo-500 to-violet-500" />
          <h1 className="text-3xl font-bold text-slate-900 tracking-tight">Strategy Catalog</h1>
        </div>
        <p className="text-slate-400 text-sm ml-4">Browse and deploy algorithmic trading strategies</p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {strategies?.filter(s => PROFITABLE_STRATEGIES.has(s.strategyType)).map((s, i) => {
          const icon = STRATEGY_ICONS[s.strategyType] || STRATEGY_ICONS.ORB;
          return (
            <div key={s.id} className="card-crystal hover-lift hover-glow overflow-hidden animate-fade-in-up" style={{ animationDelay: `${i * 100}ms` }}>
              <div className="p-5">
                <div className="flex items-start justify-between mb-4">
                  <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-indigo-500 to-violet-600 flex items-center justify-center shadow-lg shadow-indigo-500/20">
                    <svg className="w-5 h-5 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                      <path strokeLinecap="round" strokeLinejoin="round" d={icon} />
                    </svg>
                  </div>
                  <span className={`flex items-center gap-1.5 px-2 py-1 rounded-lg text-xs font-semibold ${
                    s.enabled ? 'bg-emerald-50 text-emerald-600 border border-emerald-200' : 'bg-slate-100 text-slate-500 border border-slate-200'
                  }`}>
                    <span className={`w-1.5 h-1.5 rounded-full ${s.enabled ? 'bg-emerald-500' : 'bg-slate-400'}`} />
                    {s.enabled ? 'Enabled' : 'Disabled'}
                  </span>
                </div>

                <h3 className="text-base font-bold text-slate-800 mb-2">{s.name}</h3>
                <p className="text-sm text-slate-500 mb-4 leading-relaxed">{s.description}</p>

                <div className="flex items-center gap-2 mb-4">
                  <span className="px-2 py-1 bg-indigo-50 text-indigo-600 rounded-lg text-xs font-medium border border-indigo-100">{s.strategyType}</span>
                  <span className="px-2 py-1 bg-violet-50 text-violet-600 rounded-lg text-xs font-medium border border-violet-100">{s.assetClass}</span>
                </div>

                {s.paramsSchema && (
                  <details className="mb-4 text-xs">
                    <summary className="cursor-pointer text-slate-500 hover:text-slate-700 font-medium transition">View Parameters</summary>
                    <pre className="mt-2 bg-slate-50 p-3 rounded-xl overflow-x-auto text-slate-600 border border-slate-100">{JSON.stringify(JSON.parse(s.paramsSchema), null, 2)}</pre>
                  </details>
                )}

                {s.enabled && (
                  <Link to="/deployments"
                    className="block w-full text-center py-2.5 rounded-xl bg-gradient-to-r from-indigo-600 to-violet-600 text-white text-sm font-medium hover:from-indigo-700 hover:to-violet-700 transition shadow-lg shadow-indigo-500/20">
                    Deploy Strategy
                  </Link>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
