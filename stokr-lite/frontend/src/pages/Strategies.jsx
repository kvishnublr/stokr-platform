import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import client from '../api/client';

const STRATEGY_ICONS = {
  OVERSOLD_BOUNCE: 'M13 10V3L4 14h7v7l9-11h-7z',
  EMA50_DISTANCE: 'M13 7h8m0 0v8m0-8l-8 8-4-4-6 6',
  THREE_RED_DAYS: 'M7 12l3-3 3 3 4-4M8 21l4-4 4 4M3 4h18M4 4h16v12a1 1 0 01-1 1H5a1 1 0 01-1-1V4z',
  RSI_OVERSOLD: 'M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z',
  MORNING_SURGE_REVERSAL: 'M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5M9 7h1m-1 4h1m4-4h1m-1 4h1m-5 10v-5a1 1 0 011-1h2a1 1 0 011 1v5m-4 0h4',
};

const TABS = [
  { id: 'POSITIONAL', label: 'Positional', desc: 'Daily candle strategies — hold overnight', icon: '📅', color: 'from-indigo-500 to-violet-500' },
  { id: 'INTRA', label: 'Intraday', desc: '1-min candle strategies — same-day exit', icon: '⚡', color: 'from-amber-500 to-orange-500' },
];

function StrategyCard({ strategy, index }) {
  const icon = STRATEGY_ICONS[strategy.strategyType] || 'M13 10V3L4 14h7v7l9-11h-7z';
  const isPositional = strategy.timeframe === 'DAILY' || strategy.timeframe === 'POSITIONAL';

  return (
    <div className="card-crystal hover-lift hover-glow overflow-hidden animate-fade-in-up" style={{ animationDelay: `${index * 80}ms` }}>
      <div className="p-5">
        <div className="flex items-start justify-between mb-4">
          <div className={`w-10 h-10 rounded-xl bg-gradient-to-br ${isPositional ? 'from-indigo-500 to-violet-600' : 'from-amber-500 to-orange-600'} flex items-center justify-center shadow-lg`}
            style={{ boxShadow: isPositional ? '0 8px 24px rgba(99,102,241,0.2)' : '0 8px 24px rgba(245,158,11,0.2)' }}>
            <svg className="w-5 h-5 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
              <path strokeLinecap="round" strokeLinejoin="round" d={icon} />
            </svg>
          </div>
          <div className="flex items-center gap-2">
            <span className={`px-2 py-0.5 rounded-md text-[10px] font-bold uppercase tracking-wider ${
              isPositional ? 'bg-indigo-50 text-indigo-600 border border-indigo-200' : 'bg-amber-50 text-amber-600 border border-amber-200'
            }`}>
              {strategy.timeframe || 'DAILY'}
            </span>
            <span className={`flex items-center gap-1.5 px-2 py-1 rounded-lg text-xs font-semibold ${
              strategy.enabled ? 'bg-emerald-50 text-emerald-600 border border-emerald-200' : 'bg-slate-100 text-slate-500 border border-slate-200'
            }`}>
              <span className={`w-1.5 h-1.5 rounded-full ${strategy.enabled ? 'bg-emerald-500' : 'bg-slate-400'}`} />
              {strategy.enabled ? 'Active' : 'Off'}
            </span>
          </div>
        </div>

        <h3 className="text-base font-bold text-slate-800 mb-1">{strategy.name}</h3>
        <p className="text-xs text-slate-400 mb-3">{strategy.description}</p>

        {strategy.paramsSchema && (
          <details className="mb-4 text-xs">
            <summary className="cursor-pointer text-slate-400 hover:text-slate-600 font-medium transition">Parameters</summary>
            <pre className="mt-2 bg-slate-50 p-3 rounded-xl overflow-x-auto text-slate-600 border border-slate-100 text-[11px]">{JSON.stringify(JSON.parse(strategy.paramsSchema), null, 2)}</pre>
          </details>
        )}

        {strategy.enabled && (
          <Link to="/deployments"
            className="block w-full text-center py-2 rounded-xl bg-gradient-to-r from-slate-800 to-slate-900 text-white text-sm font-medium hover:from-slate-700 hover:to-slate-800 transition shadow-md">
            Deploy
          </Link>
        )}
      </div>
    </div>
  );
}

export default function Strategies() {
  const [activeTab, setActiveTab] = useState('POSITIONAL');
  const { data: strategies, isLoading } = useQuery({
    queryKey: ['strategies'],
    queryFn: () => client.get('/strategies').then((r) => r.data),
  });

  if (isLoading) return <div className="text-slate-500">Loading strategies...</div>;

  const filtered = (strategies || []).filter(s => {
    if (activeTab === 'POSITIONAL') {
      return s.timeframe === 'DAILY' || s.timeframe === 'POSITIONAL';
    }
    return s.timeframe === 'INTRA' || s.timeframe === 'INTRADAY';
  });

  return (
    <div>
      <div className="mb-6 animate-fade-in-up">
        <div className="flex items-center gap-3 mb-1">
          <div className="w-1 h-7 rounded-full bg-gradient-to-b from-indigo-500 to-violet-500" />
          <h1 className="text-3xl font-bold text-slate-900 tracking-tight">Strategy Catalog</h1>
        </div>
        <p className="text-slate-400 text-sm ml-4">Browse and deploy algorithmic trading strategies</p>
      </div>

      {/* Horizontal Tabs */}
      <div className="flex gap-2 mb-6">
        {TABS.map(tab => {
          const count = (strategies || []).filter(s =>
            tab.id === 'POSITIONAL' ? (s.timeframe === 'DAILY' || s.timeframe === 'POSITIONAL') : (s.timeframe === 'INTRA' || s.timeframe === 'INTRADAY')
          ).length;
          const isActive = activeTab === tab.id;
          return (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`flex items-center gap-2 px-5 py-3 rounded-2xl text-sm font-semibold transition-all duration-200 border ${
                isActive
                  ? 'bg-white border-slate-200 shadow-lg shadow-slate-200/50 text-slate-900'
                  : 'bg-white/50 border-slate-100 text-slate-500 hover:bg-white hover:border-slate-200 hover:text-slate-700'
              }`}
            >
              <span className="text-base">{tab.icon}</span>
              <span>{tab.label}</span>
              <span className={`ml-1 px-2 py-0.5 rounded-lg text-[11px] font-bold ${
                isActive ? 'bg-indigo-100 text-indigo-600' : 'bg-slate-100 text-slate-400'
              }`}>{count}</span>
            </button>
          );
        })}
      </div>

      {filtered.length === 0 ? (
        <div className="text-center py-20 text-slate-400">
          <div className="text-4xl mb-3">{activeTab === 'INTRA' ? '⚡' : '📅'}</div>
          <p className="text-sm">No {activeTab === 'INTRA' ? 'intraday' : 'positional'} strategies yet</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {filtered.map((s, i) => (
            <StrategyCard key={s.id} strategy={s} index={i} />
          ))}
        </div>
      )}
    </div>
  );
}
