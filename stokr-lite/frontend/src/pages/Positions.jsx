import React, { useState, useMemo, useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import client from '../api/client';
import { LivePositionsSection, BrokerPositionsPanel, CashPositionsSection, STRATEGY_LABELS } from './OptionArbitrage';

// Fallback format time
function fmtTime(ts) {
  if (!ts) return '--';
  const d = new Date(ts);
  if (isNaN(d.getTime())) return ts;
  return d.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function UnifiedPerformanceAndHistory({ fnoHistory, cashHistory, assetFilter, modeFilter, dateRange }) {
  // Normalize and merge
  const allHistory = useMemo(() => {
    const fno = (fnoHistory || []).map(p => ({
      ...p,
      assetClass: 'FNO',
      mode: (!p.broker || p.broker === 'PAPER') ? 'PAPER' : 'LIVE',
      realPnl: Number(p.currentPnl ?? p.pnl) || 0,
      displaySymbol: `${p.underlying} ${p.strike || ''} ${p.action || ''}`.trim(),
      qty: '--'
    }));

    const cash = (cashHistory || []).map(p => ({
      ...p,
      assetClass: 'CASH',
      mode: (!p.broker || p.broker === 'PAPER') ? 'PAPER' : 'LIVE',
      realPnl: Number(p.currentPnl) || 0,
      displaySymbol: p.symbol,
      qty: p.quantity || '--'
    }));

    return [...fno, ...cash].sort((a, b) => new Date(b.exitedAt || b.enteredAt || 0) - new Date(a.exitedAt || a.enteredAt || 0));
  }, [fnoHistory, cashHistory]);

  // Apply Filters
  const filteredHistory = useMemo(() => {
    return allHistory.filter(p => {
      if (assetFilter !== 'ALL' && p.assetClass !== assetFilter) return false;
      if (modeFilter !== 'ALL' && p.mode !== modeFilter) return false;

      if (dateRange !== 'ALL') {
        const exited = p.exitedAt ? new Date(p.exitedAt) : new Date(p.enteredAt);
        const now = new Date();
        if (dateRange === 'TODAY') {
          if (exited.toDateString() !== now.toDateString()) return false;
        } else if (dateRange === 'WEEK') {
          const weekAgo = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000);
          if (exited < weekAgo) return false;
        } else if (dateRange === 'MONTH') {
          if (exited.getMonth() !== now.getMonth() || exited.getFullYear() !== now.getFullYear()) return false;
        }
      }
      return true;
    });
  }, [allHistory, assetFilter, modeFilter, dateRange]);

  // Compute Metrics
  const metrics = useMemo(() => {
    let totalPnl = 0;
    let wins = 0;
    let losses = 0;
    let totalHoldMins = 0;
    let validHoldCount = 0;

    const byStrategy = {};

    filteredHistory.forEach(p => {
      const pnl = p.realPnl;
      totalPnl += pnl;
      if (pnl > 0) wins++;
      if (pnl < 0) losses++;

      if (p.enteredAt && p.exitedAt) {
        const ms = new Date(p.exitedAt) - new Date(p.enteredAt);
        if (ms > 0) {
          totalHoldMins += (ms / 60000);
          validHoldCount++;
        }
      }

      const strat = p.strategyType || 'UNKNOWN';
      if (!byStrategy[strat]) byStrategy[strat] = { trades: 0, wins: 0, losses: 0, pnl: 0, best: -Infinity, worst: Infinity, holdMins: 0, holdCount: 0 };
      
      const st = byStrategy[strat];
      st.trades++;
      st.pnl += pnl;
      if (pnl > 0) st.wins++;
      if (pnl < 0) st.losses++;
      if (pnl > st.best) st.best = pnl;
      if (pnl < st.worst) st.worst = pnl;
      if (p.enteredAt && p.exitedAt) {
         const ms = new Date(p.exitedAt) - new Date(p.enteredAt);
         if (ms > 0) { st.holdMins += (ms/60000); st.holdCount++; }
      }
    });

    const trades = filteredHistory.length;
    const winRate = trades > 0 ? (wins / trades) * 100 : 0;
    const expectancy = trades > 0 ? totalPnl / trades : 0;
    const avgHold = validHoldCount > 0 ? totalHoldMins / validHoldCount : 0;

    const strategyList = Object.entries(byStrategy).map(([name, s]) => ({
      name,
      trades: s.trades,
      winRate: (s.wins / s.trades) * 100,
      pnl: s.pnl,
      expectancy: s.pnl / s.trades,
      avgWin: s.wins > 0 ? (s.pnl > 0 ? s.pnl / s.wins : 0) : 0, // Simplified
      avgLoss: s.losses > 0 ? (s.pnl < 0 ? s.pnl / s.losses : 0) : 0, // Simplified
      best: s.best === -Infinity ? 0 : s.best,
      worst: s.worst === Infinity ? 0 : s.worst,
      avgHold: s.holdCount > 0 ? s.holdMins / s.holdCount : 0
    })).sort((a,b) => b.pnl - a.pnl);

    return { totalPnl, winRate, expectancy, avgHold, trades, strategyList };
  }, [filteredHistory]);

  return (
    <div className="space-y-6 mt-6">
      {/* KPI Cards */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <div className="bg-white rounded-2xl border border-slate-200 p-5 shadow-sm">
          <div className="text-xs font-bold text-slate-500 uppercase tracking-wider mb-1">Total P&L</div>
          <div className={`text-2xl font-black ${metrics.totalPnl >= 0 ? 'text-emerald-600' : 'text-rose-600'}`}>
            {metrics.totalPnl >= 0 ? '+' : '-'}₹{Math.abs(Math.round(metrics.totalPnl)).toLocaleString('en-IN')}
          </div>
          <div className="text-xs text-slate-400 mt-1">{metrics.trades} closed trades</div>
        </div>
        <div className="bg-white rounded-2xl border border-slate-200 p-5 shadow-sm">
          <div className="text-xs font-bold text-slate-500 uppercase tracking-wider mb-1">Win Rate</div>
          <div className="text-2xl font-black text-slate-800">{metrics.winRate.toFixed(1)}%</div>
          <div className="text-xs text-slate-400 mt-1">Accuracy</div>
        </div>
        <div className="bg-white rounded-2xl border border-slate-200 p-5 shadow-sm">
          <div className="text-xs font-bold text-slate-500 uppercase tracking-wider mb-1">Expectancy / Trade</div>
          <div className={`text-2xl font-black ${metrics.expectancy >= 0 ? 'text-emerald-600' : 'text-rose-600'}`}>
            ₹{Math.round(metrics.expectancy).toLocaleString('en-IN')}
          </div>
          <div className="text-xs text-slate-400 mt-1">Average Edge</div>
        </div>
        <div className="bg-white rounded-2xl border border-slate-200 p-5 shadow-sm">
          <div className="text-xs font-bold text-slate-500 uppercase tracking-wider mb-1">Avg Hold Time</div>
          <div className="text-2xl font-black text-slate-800">{metrics.avgHold > 60 ? (metrics.avgHold/60).toFixed(1) + ' hr' : metrics.avgHold.toFixed(1) + ' min'}</div>
          <div className="text-xs text-slate-400 mt-1">Per Trade</div>
        </div>
      </div>

      {/* Strategy Breakdown */}
      {metrics.strategyList.length > 0 && (
        <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
          <div className="px-5 py-4 border-b border-slate-100 bg-slate-50/50">
            <h3 className="font-bold text-slate-800 text-sm">Strategy Breakdown</h3>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-xs text-left">
              <thead className="bg-slate-50 text-slate-500 font-bold uppercase tracking-wider border-b border-slate-100">
                <tr>
                  <th className="px-5 py-3">Strategy</th>
                  <th className="px-5 py-3 text-right">Trades</th>
                  <th className="px-5 py-3 text-right">Win Rate</th>
                  <th className="px-5 py-3 text-right">Total P&L</th>
                  <th className="px-5 py-3 text-right">Expectancy</th>
                  <th className="px-5 py-3 text-right">Best</th>
                  <th className="px-5 py-3 text-right">Worst</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {metrics.strategyList.map(s => (
                  <tr key={s.name} className="hover:bg-slate-50">
                    <td className="px-5 py-3 font-bold text-slate-700">{STRATEGY_LABELS[s.name] || s.name}</td>
                    <td className="px-5 py-3 text-right font-medium text-slate-600">{s.trades}</td>
                    <td className="px-5 py-3 text-right font-medium text-slate-600">{s.winRate.toFixed(1)}%</td>
                    <td className={`px-5 py-3 text-right font-black ${s.pnl >= 0 ? 'text-emerald-600' : 'text-rose-600'}`}>₹{Math.round(s.pnl).toLocaleString('en-IN')}</td>
                    <td className={`px-5 py-3 text-right font-medium ${s.expectancy >= 0 ? 'text-emerald-600' : 'text-rose-600'}`}>₹{Math.round(s.expectancy).toLocaleString('en-IN')}</td>
                    <td className="px-5 py-3 text-right font-mono text-emerald-600">₹{Math.round(s.best)}</td>
                    <td className="px-5 py-3 text-right font-mono text-rose-600">₹{Math.round(s.worst)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Trade Ledger */}
      <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
        <div className="px-5 py-4 border-b border-slate-100 bg-slate-50/50 flex justify-between items-center">
          <h3 className="font-bold text-slate-800 text-sm">Trade Ledger</h3>
          <span className="text-xs font-bold text-slate-500">{filteredHistory.length} trades</span>
        </div>
        {filteredHistory.length === 0 ? (
          <div className="p-10 text-center text-slate-400 text-sm font-medium">No closed trades found for this filter.</div>
        ) : (
          <div className="overflow-x-auto max-h-[600px] overflow-y-auto">
            <table className="w-full text-[11px] text-left">
              <thead className="bg-slate-50 border-b border-slate-200 text-slate-600 uppercase tracking-tight font-bold sticky top-0 shadow-sm">
                <tr>
                  <th className="px-4 py-3">Exit Time</th>
                  <th className="px-4 py-3">Asset</th>
                  <th className="px-4 py-3">Strategy</th>
                  <th className="px-4 py-3">Symbol / Legs</th>
                  <th className="px-4 py-3 text-right">Qty</th>
                  <th className="px-4 py-3 text-right">Realized P&amp;L</th>
                  <th className="px-4 py-3 text-center">Mode</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {filteredHistory.map(p => (
                  <tr key={p.id + p.assetClass} className="hover:bg-slate-50">
                    <td className="px-4 py-2 font-mono text-slate-500 whitespace-nowrap">{fmtTime(p.exitedAt || p.enteredAt)}</td>
                    <td className="px-4 py-2">
                      <span className={`px-2 py-0.5 rounded text-[9px] font-bold ${p.assetClass==='FNO'?'bg-indigo-100 text-indigo-700':'bg-orange-100 text-orange-700'}`}>{p.assetClass}</span>
                    </td>
                    <td className="px-4 py-2 text-slate-600 font-medium">{STRATEGY_LABELS[p.strategyType] || p.strategyType}</td>
                    <td className="px-4 py-2 font-bold text-slate-800">{p.displaySymbol}</td>
                    <td className="px-4 py-2 text-right font-medium text-slate-600">{p.qty}</td>
                    <td className={`px-4 py-2 text-right font-mono font-bold ${p.realPnl >= 0 ? 'text-emerald-600' : 'text-rose-600'}`}>
                      ₹{Math.round(p.realPnl).toLocaleString('en-IN')}
                    </td>
                    <td className="px-4 py-2 text-center">
                      <span className={`px-2 py-0.5 rounded-full text-[9px] font-bold border ${p.mode === 'PAPER' ? 'bg-slate-100 text-slate-500 border-slate-300' : 'bg-emerald-100 text-emerald-800 border-emerald-300'}`}>
                        {p.mode}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}


export default function Positions() {
  const [executionBroker, setExecutionBroker] = useState('PAPER');
  
  // Dashboard Filters
  const [viewState, setViewState] = useState('ACTIVE'); // ACTIVE | HISTORY
  const [assetFilter, setAssetFilter] = useState('ALL'); // ALL | FNO | CASH
  const [modeFilter, setModeFilter] = useState('ALL'); // ALL | LIVE | PAPER
  const [dateRange, setDateRange] = useState('TODAY'); // TODAY | WEEK | MONTH | ALL
  
  useEffect(() => {
    client.get('/brokers/decoupled-routing')
      .then((res) => { if (res.data?.executionBroker) setExecutionBroker(res.data.executionBroker); })
      .catch(() => {});
  }, []);

  // Fetch Histories unconditionally so they are instantly ready when switching tabs
  const { data: fnoHistoryData } = useQuery({
    queryKey: ['fnoHistoryClosed'],
    queryFn: () => client.get('/option-arbitrage/paper-trades', { params: { status: 'CLOSED' } }).then(r => r.data),
    refetchInterval: 15000,
  });
  
  const { data: cashHistoryData } = useQuery({
    queryKey: ['cashHistoryData'],
    queryFn: () => client.get('/option-arbitrage/cash-history').then(r => r.data),
    refetchInterval: 15000,
  });

  return (
    <div className="space-y-6 pb-20">
      {/* Header */}
      <div>
        <div className="flex items-center gap-3 mb-1">
          <div className="w-1.5 h-7 rounded-full bg-gradient-to-b from-indigo-500 to-purple-600" />
          <h1 className="text-[28px] font-black text-slate-900 tracking-tight">Portfolio & Performance</h1>
        </div>
        <p className="text-slate-500 text-sm ml-4 font-medium">Unified command center for Live, Paper, Cash, and Options</p>
      </div>

      {/* Unified Master Control Bar */}
      <div className="bg-white p-2 rounded-2xl border border-slate-200 shadow-sm flex flex-col md:flex-row gap-4 justify-between items-center">
        
        {/* State Toggle */}
        <div className="flex bg-slate-100 p-1 rounded-xl w-full md:w-auto">
          <button onClick={() => setViewState('ACTIVE')} className={`flex-1 md:px-6 py-2 rounded-lg text-sm font-bold transition ${viewState === 'ACTIVE' ? 'bg-white text-indigo-700 shadow-sm' : 'text-slate-500 hover:text-slate-700'}`}>Active Trades</button>
          <button onClick={() => setViewState('HISTORY')} className={`flex-1 md:px-6 py-2 rounded-lg text-sm font-bold transition ${viewState === 'HISTORY' ? 'bg-white text-indigo-700 shadow-sm' : 'text-slate-500 hover:text-slate-700'}`}>History & Performance</button>
        </div>

        {/* Filters */}
        <div className="flex flex-wrap gap-2 md:gap-4 items-center justify-center w-full md:w-auto px-2">
          
          <div className="flex items-center gap-1 bg-slate-50 border border-slate-200 rounded-lg p-1">
            {['ALL', 'FNO', 'CASH'].map(a => (
              <button key={a} onClick={() => setAssetFilter(a)} className={`px-3 py-1 rounded-md text-[11px] font-bold transition ${assetFilter === a ? 'bg-indigo-600 text-white' : 'text-slate-500 hover:bg-slate-200'}`}>{a === 'ALL' ? 'All Assets' : a}</button>
            ))}
          </div>
          
          <div className="flex items-center gap-1 bg-slate-50 border border-slate-200 rounded-lg p-1">
            {['ALL', 'LIVE', 'PAPER'].map(m => (
              <button key={m} onClick={() => setModeFilter(m)} className={`px-3 py-1 rounded-md text-[11px] font-bold transition ${modeFilter === m ? 'bg-emerald-600 text-white' : 'text-slate-500 hover:bg-slate-200'}`}>{m === 'ALL' ? 'All Modes' : m}</button>
            ))}
          </div>

          {viewState === 'HISTORY' && (
            <select 
              value={dateRange} 
              onChange={e => setDateRange(e.target.value)}
              className="bg-slate-50 border border-slate-200 text-slate-700 text-xs font-bold rounded-lg px-3 py-1.5 focus:outline-none focus:ring-2 focus:ring-indigo-500"
            >
              <option value="TODAY">Today</option>
              <option value="WEEK">This Week</option>
              <option value="MONTH">This Month</option>
              <option value="ALL">All Time</option>
            </select>
          )}

        </div>
      </div>

      {/* Main Content Area */}
      {viewState === 'ACTIVE' && (
        <div className="space-y-6">
          <BrokerPositionsPanel executionBroker={executionBroker} defaultExpanded={false} />
          
          {(assetFilter === 'ALL' || assetFilter === 'FNO') && (
            <div className="bg-white p-1 rounded-2xl border border-indigo-100 shadow-sm relative overflow-hidden">
               <div className="absolute top-0 left-0 w-1 h-full bg-indigo-500"></div>
               <div className="p-3">
                 <h3 className="text-xs font-black text-indigo-800 uppercase tracking-wider ml-2 mb-2">F&O Arbitrage Positions</h3>
                 <LivePositionsSection executionBroker={executionBroker} defaultExpanded={true} />
               </div>
            </div>
          )}

          {(assetFilter === 'ALL' || assetFilter === 'CASH') && (
            <div className="bg-white p-1 rounded-2xl border border-orange-100 shadow-sm relative overflow-hidden">
               <div className="absolute top-0 left-0 w-1 h-full bg-orange-500"></div>
               <div className="p-3">
                 <h3 className="text-xs font-black text-orange-800 uppercase tracking-wider ml-2 mb-2">Cash Equity Positions</h3>
                 <CashPositionsSection />
               </div>
            </div>
          )}
        </div>
      )}

      {viewState === 'HISTORY' && (
        <UnifiedPerformanceAndHistory 
          fnoHistory={fnoHistoryData?.positions} 
          cashHistory={cashHistoryData?.positions}
          assetFilter={assetFilter}
          modeFilter={modeFilter}
          dateRange={dateRange}
        />
      )}
    </div>
  );
}
