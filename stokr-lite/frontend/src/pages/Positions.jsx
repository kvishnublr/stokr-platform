import { useState, useEffect, Fragment } from 'react';
import { useQuery } from '@tanstack/react-query';
import client from '../api/client';
import { LivePositionsSection, BrokerPositionsPanel, STRATEGY_LABELS } from './OptionArbitrage';

const TABS = [
  { id: 'mine', label: '📊 My Positions' },
  { id: 'broker', label: '🏦 Broker Positions (Ground Truth)' },
  { id: 'history', label: '🗂️ History' },
  { id: 'performance', label: '📈 Performance' },
];

function fmtTime(iso) {
  if (!iso) return '--';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '--';
  return d.toLocaleString('en-IN', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', hour12: true });
}

const STATUS_FILTERS = [
  { id: 'CLOSED', label: 'Closed' },
  { id: 'FAILED', label: 'Failed' },
];

const MODE_FILTERS = [
  { id: 'ALL', label: 'All' },
  { id: 'LIVE', label: '🔴 Live' },
  { id: 'PAPER', label: '📄 Paper' },
];

function pnlClass(v) {
  return Number(v) > 0 ? 'text-emerald-600' : Number(v) < 0 ? 'text-red-600' : 'text-slate-500';
}

function StrategyPerformancePanel() {
  const [mode, setMode] = useState('ALL');
  const [expanded, setExpanded] = useState(null);

  const { data, isLoading, isError, refetch, isFetching } = useQuery({
    queryKey: ['strategyPerformance', mode],
    queryFn: () => client.get('/option-arbitrage/performance', { params: { mode } }).then((r) => r.data),
    refetchInterval: 30000,
  });

  const strategies = data?.strategies || [];
  const overall = data?.overall;

  return (
    <div className="space-y-4">
      <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
        <div className="px-4 py-3 bg-gradient-to-r from-emerald-50 via-teal-50 to-white border-b border-emerald-100 flex items-center justify-between flex-wrap gap-3">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="text-lg">📈</span>
            <h3 className="text-sm font-black text-slate-800">Strategy Performance</h3>
            <span className="px-2 py-0.5 bg-emerald-100 text-emerald-700 text-[10px] font-bold rounded-full">
              {overall?.trades ?? 0} closed trades
            </span>
            <div className="flex items-center gap-0.5 bg-white border border-slate-200 rounded-full p-0.5">
              {MODE_FILTERS.map(f => (
                <button key={f.id} onClick={() => setMode(f.id)}
                  className={`px-2.5 py-1 rounded-full text-[10px] font-bold transition ${mode === f.id ? 'bg-emerald-600 text-white shadow-sm' : 'text-slate-500 hover:text-slate-800'}`}>
                  {f.label}
                </button>
              ))}
            </div>
          </div>
          <button onClick={() => refetch()} className="px-3 py-1.5 bg-white border border-slate-300 text-slate-600 text-xs font-bold rounded-lg hover:bg-slate-50">
            {isFetching ? '...' : '↻ Refresh'}
          </button>
        </div>

        {isLoading && <div className="p-12 text-center text-slate-400 text-sm font-semibold">Loading performance...</div>}
        {isError && <div className="p-12 text-center text-rose-500 text-sm font-semibold">Could not load performance</div>}
        {!isLoading && !isError && strategies.length === 0 && (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">
            No closed trades yet{mode !== 'ALL' ? ` in ${mode.toLowerCase()} mode` : ''} — performance appears once positions are actually entered and exited.
          </div>
        )}

        {!isLoading && !isError && overall && overall.trades > 0 && (
          <div className="grid grid-cols-2 md:grid-cols-5 gap-2 p-4 bg-slate-50/60 border-b border-slate-200">
            <div className="bg-white border border-slate-200 rounded-lg px-3 py-2">
              <div className="text-[9px] font-bold text-slate-500 uppercase">Total P&amp;L</div>
              <div className={`text-lg font-black ${pnlClass(overall.totalPnl)}`}>₹{Number(overall.totalPnl).toLocaleString('en-IN')}</div>
            </div>
            <div className="bg-white border border-slate-200 rounded-lg px-3 py-2">
              <div className="text-[9px] font-bold text-slate-500 uppercase">Win Rate</div>
              <div className="text-lg font-black text-slate-800">{overall.winRate}%</div>
              <div className="text-[9px] text-slate-400">{overall.wins}W / {overall.losses}L</div>
            </div>
            <div className="bg-white border border-slate-200 rounded-lg px-3 py-2" title="Average P&L per trade -- the number that decides whether repeating this makes money over time, unlike win rate alone.">
              <div className="text-[9px] font-bold text-slate-500 uppercase">Expectancy / Trade</div>
              <div className={`text-lg font-black ${pnlClass(overall.expectancy)}`}>₹{Number(overall.expectancy).toLocaleString('en-IN')}</div>
            </div>
            <div className="bg-white border border-slate-200 rounded-lg px-3 py-2">
              <div className="text-[9px] font-bold text-slate-500 uppercase">Avg Win / Loss</div>
              <div className="text-sm font-black">
                <span className="text-emerald-600">₹{Number(overall.avgWin).toLocaleString('en-IN')}</span>
                <span className="text-slate-400"> / </span>
                <span className="text-red-600">₹{Number(overall.avgLoss).toLocaleString('en-IN')}</span>
              </div>
            </div>
            <div className="bg-white border border-slate-200 rounded-lg px-3 py-2">
              <div className="text-[9px] font-bold text-slate-500 uppercase">Avg Hold</div>
              <div className="text-lg font-black text-slate-800">{overall.avgHoldMinutes} min</div>
            </div>
          </div>
        )}

        {!isLoading && !isError && strategies.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-[11px] text-left">
              <thead className="bg-slate-50 border-b border-slate-200 text-slate-600 uppercase tracking-tight font-bold">
                <tr>
                  <th className="px-3 py-2">Strategy</th>
                  <th className="px-3 py-2 text-right">Trades</th>
                  <th className="px-3 py-2 text-right">Win Rate</th>
                  <th className="px-3 py-2 text-right">Total P&amp;L</th>
                  <th className="px-3 py-2 text-right">Expectancy</th>
                  <th className="px-3 py-2 text-right">Avg Win</th>
                  <th className="px-3 py-2 text-right">Avg Loss</th>
                  <th className="px-3 py-2 text-right">Best</th>
                  <th className="px-3 py-2 text-right">Worst</th>
                  <th className="px-3 py-2 text-right">Avg Hold</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {strategies.map(s => (
                  <Fragment key={s.strategyType}>
                    <tr
                      onClick={() => setExpanded(expanded === s.strategyType ? null : s.strategyType)}
                      className={`hover:bg-slate-50 cursor-pointer ${expanded === s.strategyType ? 'bg-emerald-50/50' : ''}`}>
                      <td className="px-3 py-2">
                        <span className="px-2 py-0.5 rounded-full text-[9px] font-bold border bg-indigo-50 text-indigo-700 border-indigo-200">
                          {STRATEGY_LABELS[s.strategyType] || s.strategyType}
                        </span>
                      </td>
                      <td className="px-3 py-2 text-right font-mono font-bold">{s.trades}</td>
                      <td className="px-3 py-2 text-right font-mono font-bold text-slate-700">{s.winRate}%</td>
                      <td className={`px-3 py-2 text-right font-mono font-bold ${pnlClass(s.totalPnl)}`}>₹{Number(s.totalPnl).toLocaleString('en-IN')}</td>
                      <td className={`px-3 py-2 text-right font-mono font-bold ${pnlClass(s.expectancy)}`}>₹{Number(s.expectancy).toLocaleString('en-IN')}</td>
                      <td className="px-3 py-2 text-right font-mono text-emerald-600">₹{Number(s.avgWin).toLocaleString('en-IN')}</td>
                      <td className="px-3 py-2 text-right font-mono text-red-600">₹{Number(s.avgLoss).toLocaleString('en-IN')}</td>
                      <td className="px-3 py-2 text-right font-mono text-emerald-700">₹{Number(s.bestTrade).toLocaleString('en-IN')}</td>
                      <td className="px-3 py-2 text-right font-mono text-red-700">₹{Number(s.worstTrade).toLocaleString('en-IN')}</td>
                      <td className="px-3 py-2 text-right font-mono text-slate-500">{s.avgHoldMinutes}m</td>
                    </tr>
                    {expanded === s.strategyType && Array.isArray(s.underlyings) && s.underlyings.length > 0 && (
                      <tr className="bg-emerald-50/30">
                        <td colSpan={10} className="px-6 py-3">
                          <div className="text-[10px] font-black text-slate-600 uppercase mb-2">By Underlying</div>
                          <table className="w-full text-[11px]">
                            <thead className="text-slate-500 uppercase text-[9px] font-bold">
                              <tr>
                                <th className="text-left py-1">Underlying</th>
                                <th className="text-right py-1">Trades</th>
                                <th className="text-right py-1">Win Rate</th>
                                <th className="text-right py-1">Total P&amp;L</th>
                                <th className="text-right py-1">Avg P&amp;L</th>
                              </tr>
                            </thead>
                            <tbody>
                              {s.underlyings.map(u => (
                                <tr key={u.underlying} className="border-t border-emerald-100">
                                  <td className="py-1 font-bold text-slate-700">{u.underlying}</td>
                                  <td className="py-1 text-right font-mono">{u.trades}</td>
                                  <td className="py-1 text-right font-mono">{u.winRate}%</td>
                                  <td className={`py-1 text-right font-mono font-bold ${pnlClass(u.totalPnl)}`}>₹{Number(u.totalPnl).toLocaleString('en-IN')}</td>
                                  <td className={`py-1 text-right font-mono ${pnlClass(u.avgPnl)}`}>₹{Number(u.avgPnl).toLocaleString('en-IN')}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
            <p className="text-[10px] text-slate-400 px-4 py-2 border-t border-slate-100">
              {data?.note} Click a strategy row to break it down by underlying.
            </p>
          </div>
        )}
      </div>
    </div>
  );
}

function PositionsHistoryPanel() {
  const [statusFilter, setStatusFilter] = useState('CLOSED');
  const [brokerFilter, setBrokerFilter] = useState('ALL');

  const { data, isLoading, isError, refetch, isFetching } = useQuery({
    queryKey: ['positionsHistory', statusFilter],
    queryFn: () => client.get('/option-arbitrage/paper-trades', { params: { status: statusFilter } }).then((r) => r.data),
    refetchInterval: 15000,
  });

  // History intentionally still includes today's closed/failed trades (once a position isn't
  // OPEN anymore it's no longer on My Positions regardless of date, so there's no overlap) --
  // matches every other History tab in this app, which defaults to showing today's activity
  // too rather than hiding it until the day is over.
  const allPositions = data?.positions || [];
  const positions = brokerFilter === 'ALL' ? allPositions
    : brokerFilter === 'PAPER' ? allPositions.filter(p => !p.broker || p.broker === 'PAPER')
    : allPositions.filter(p => p.broker && p.broker !== 'PAPER');

  const totalPnl = positions.reduce((s, p) => s + (Number(p.currentPnl ?? p.pnl) || 0), 0);

  return (
    <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
      <div className="px-4 py-3 bg-gradient-to-r from-slate-50 via-slate-100 to-white border-b border-slate-200 flex items-center justify-between flex-wrap gap-3">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-lg">🗂️</span>
          <h3 className="text-sm font-black text-slate-800">Position History</h3>
          <span className="px-2 py-0.5 bg-slate-200 text-slate-600 text-[10px] font-bold rounded-full">{positions.length}</span>
          {statusFilter === 'CLOSED' && positions.length > 0 && (
            <span className={`px-2.5 py-0.5 text-[11px] font-black rounded-full border ${totalPnl >= 0 ? 'bg-emerald-50 text-emerald-700 border-emerald-200' : 'bg-red-50 text-red-700 border-red-200'}`}>
              Total P&amp;L: ₹{Math.round(totalPnl).toLocaleString('en-IN')}
            </span>
          )}
          <div className="flex items-center gap-0.5 bg-white border border-slate-200 rounded-full p-0.5">
            {STATUS_FILTERS.map(f => (
              <button key={f.id} onClick={() => setStatusFilter(f.id)}
                className={`px-2.5 py-1 rounded-full text-[10px] font-bold transition ${statusFilter === f.id ? 'bg-slate-700 text-white shadow-sm' : 'text-slate-500 hover:text-slate-800'}`}>
                {f.label}
              </button>
            ))}
          </div>
          <div className="flex items-center gap-0.5 bg-white border border-slate-200 rounded-full p-0.5">
            {['ALL', 'LIVE', 'PAPER'].map(b => (
              <button key={b} onClick={() => setBrokerFilter(b)}
                className={`px-2.5 py-1 rounded-full text-[10px] font-bold transition ${brokerFilter === b ? 'bg-indigo-600 text-white shadow-sm' : 'text-slate-500 hover:text-slate-800'}`}>
                {b === 'ALL' ? 'All' : b === 'LIVE' ? '🔴 Live' : '📄 Paper'}
              </button>
            ))}
          </div>
        </div>
        <button onClick={() => refetch()} className="px-3 py-1.5 bg-white border border-slate-300 text-slate-600 text-xs font-bold rounded-lg hover:bg-slate-50">
          {isFetching ? '...' : '↻ Refresh'}
        </button>
      </div>

      {isLoading && <div className="p-12 text-center text-slate-400 text-sm font-semibold">Loading history...</div>}
      {isError && <div className="p-12 text-center text-rose-500 text-sm font-semibold">Could not load history</div>}
      {!isLoading && !isError && positions.length === 0 && (
        <div className="p-12 text-center text-slate-400 text-sm font-semibold">No {statusFilter.toLowerCase()} positions</div>
      )}

      {!isLoading && !isError && positions.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-[11px] text-left">
            <thead className="bg-slate-50 border-b border-slate-200 text-slate-600 uppercase tracking-tight font-bold">
              <tr>
                <th className="px-3 py-2">Entered</th>
                <th className="px-3 py-2">Broker</th>
                <th className="px-3 py-2">Strategy</th>
                <th className="px-3 py-2">Underlying</th>
                <th className="px-3 py-2">Strike</th>
                <th className="px-3 py-2">Action</th>
                <th className="px-3 py-2">Exited</th>
                <th className="px-3 py-2 text-right">P&amp;L</th>
                <th className="px-3 py-2 text-center">Status</th>
                <th className="px-3 py-2">Error</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {positions.map(p => {
                const pnl = Number(p.currentPnl ?? p.pnl) || 0;
                return (
                  <tr key={p.id} className="hover:bg-slate-50">
                    <td className="px-3 py-2 font-mono text-[10px] text-slate-500">{fmtTime(p.enteredAt)}</td>
                    <td className="px-3 py-2">
                      <span className={`px-2 py-0.5 rounded-full text-[9px] font-bold border ${p.broker === 'PAPER' || !p.broker ? 'bg-slate-100 text-slate-500 border-slate-300' : 'bg-emerald-100 text-emerald-800 border-emerald-300'}`}>
                        {p.broker === 'PAPER' || !p.broker ? '📄 PAPER' : `🔴 ${p.broker}`}
                      </span>
                    </td>
                    <td className="px-3 py-2">
                      <span className="px-2 py-0.5 rounded-full text-[9px] font-bold border bg-indigo-50 text-indigo-700 border-indigo-200">
                        {STRATEGY_LABELS[p.strategyType] || p.strategyType || '—'}
                      </span>
                    </td>
                    <td className="px-3 py-2 font-bold text-slate-800">{p.underlying}</td>
                    <td className="px-3 py-2 font-bold text-slate-700">{p.strike}</td>
                    <td className="px-3 py-2 text-purple-700 font-bold text-[10px]">{p.action}</td>
                    <td className="px-3 py-2 font-mono text-[10px] text-slate-500">{fmtTime(p.exitedAt)}</td>
                    <td className={`px-3 py-2 text-right font-mono font-bold ${pnl >= 0 ? 'text-emerald-600' : 'text-red-600'}`}>
                      {p.status === 'CLOSED' ? `₹${Math.round(pnl).toLocaleString('en-IN')}` : '--'}
                    </td>
                    <td className="px-3 py-2 text-center">
                      <span className={`px-2 py-0.5 rounded-full text-[9px] font-bold border ${p.status === 'CLOSED' ? 'bg-slate-100 text-slate-600 border-slate-300' : 'bg-red-100 text-red-800 border-red-300'}`}>
                        {p.status}
                      </span>
                    </td>
                    <td className="px-3 py-2 text-[9px] text-red-600 max-w-[220px] truncate" title={p.errorMessage || ''}>{p.errorMessage || '--'}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

export default function Positions() {
  const [executionBroker, setExecutionBroker] = useState('PAPER');
  const [activeTab, setActiveTab] = useState('mine');

  useEffect(() => {
    client.get('/brokers/decoupled-routing')
      .then((res) => { if (res.data?.executionBroker) setExecutionBroker(res.data.executionBroker); })
      .catch(() => {});
  }, []);

  return (
    <div className="space-y-4">
      <div style={{ marginBottom: '8px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '4px' }}>
          <div style={{ width: '4px', height: '28px', borderRadius: '999px', background: 'linear-gradient(180deg, #6366f1, #7c3aed)' }} />
          <h1 style={{ fontSize: '28px', fontWeight: 800, color: '#0f172a', margin: 0, letterSpacing: '-0.5px' }}>Positions & P&L</h1>
        </div>
        <p style={{ color: '#94a3b8', fontSize: '14px', margin: 0, paddingLeft: '16px' }}>Live option-arbitrage positions across every strategy, plus real broker positions for reconciliation</p>
      </div>

      <div className="flex items-center gap-2 bg-white p-1.5 rounded-2xl border border-slate-200 shadow-sm w-fit">
        {TABS.map(t => (
          <button
            key={t.id}
            onClick={() => setActiveTab(t.id)}
            className={`px-4 py-2 rounded-xl text-sm font-bold transition ${
              activeTab === t.id
                ? 'bg-gradient-to-r from-indigo-600 to-violet-600 text-white shadow-md'
                : 'text-slate-500 hover:bg-slate-100 hover:text-slate-800'
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {activeTab === 'mine' && <LivePositionsSection executionBroker={executionBroker} defaultExpanded />}
      {activeTab === 'broker' && <BrokerPositionsPanel executionBroker={executionBroker} defaultExpanded />}
      {activeTab === 'history' && <PositionsHistoryPanel />}
      {activeTab === 'performance' && <StrategyPerformancePanel />}
    </div>
  );
}
