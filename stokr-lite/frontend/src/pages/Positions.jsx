import React, { useState, useMemo, useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import client from '../api/client';
import { LivePositionsSection, BrokerPositionsPanel, CashPositionsSection, STRATEGY_LABELS, GlobalConfirmModal, DetailedOpportunityExpandedRow } from './OptionArbitrage';

function fmtDate(ts) {
  if (!ts) return '--';
  const d = new Date(ts);
  if (isNaN(d.getTime())) return ts;
  return d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short' });
}

function fmtTime(ts) {
  if (!ts) return '--';
  const d = new Date(ts);
  if (isNaN(d.getTime())) return ts;
  return d.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function calcDuration(entryTs, exitTs) {
  if (!entryTs || !exitTs) return null;
  const ms = new Date(exitTs) - new Date(entryTs);
  if (isNaN(ms) || ms <= 0) return null;
  const sec = Math.floor(ms / 1000);
  if (sec < 60) return `${sec}s`;
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m`;
  const hr = (min / 60).toFixed(1);
  return `${hr}h`;
}

function UnifiedPerformanceAndHistory({ fnoHistory, cashHistory, assetFilter, modeFilter, dateRange }) {
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedStrategy, setSelectedStrategy] = useState('ALL');
  const [expandedRowId, setExpandedRowId] = useState(null);
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(25);
  const [lotScaleMode, setLotScaleMode] = useState('ONE_LOT'); // ONE_LOT (Actual 1 Lot Figures) | FULL (Total Lots)

  // Sorting
  const [sortCol, setSortCol] = useState('exitTime'); // exitTime | entryTime | pnl | strategy | lots
  const [sortDir, setSortDir] = useState('desc');

  const toggleSort = (col) => {
    if (sortCol === col) setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    else { setSortCol(col); setSortDir(col === 'pnl' || col === 'exitTime' || col === 'entryTime' ? 'desc' : 'asc'); }
  };

  // Normalize and merge all history records with 1-Lot scaling logic
  const allHistory = useMemo(() => {
    const rawFno = Array.isArray(fnoHistory) ? fnoHistory : (fnoHistory?.positions || []);
    const fno = rawFno.map(p => {
      const rawPnl = p.pnl != null ? Number(p.pnl) : (p.currentPnl != null ? Number(p.currentPnl) : 0);
      const lots = Number(p.lots) > 0 ? Number(p.lots) : 1;
      const realPnl = lotScaleMode === 'ONE_LOT' ? (rawPnl / lots) : rawPnl;
      const isPaper = !p.broker || p.broker === 'PAPER';

      return {
        ...p,
        id: p.id || `fno-${p.enteredAt}`,
        assetClass: 'FNO',
        mode: isPaper ? 'PAPER' : (p.broker || 'LIVE'),
        rawPnl,
        realPnl,
        originalLots: lots,
        displaySymbol: `${p.underlying || 'FNO'} ${p.strike ? p.strike : ''} ${p.action || ''}`.trim(),
        qtyDisplay: lotScaleMode === 'ONE_LOT' ? `1 lot (${(p.lotSize || 1).toLocaleString()} qty)` : `${lots} lot${lots > 1 ? 's' : ''} (${(lots * (p.lotSize || 1)).toLocaleString()} qty)`,
        entryDate: fmtDate(p.enteredAt),
        entryTime: fmtTime(p.enteredAt),
        exitDate: fmtDate(p.exitedAt || p.createdAt),
        exitTime: fmtTime(p.exitedAt || p.createdAt),
        duration: calcDuration(p.enteredAt, p.exitedAt),
        timestamp: p.exitedAt || p.enteredAt || p.createdAt
      };
    });

    const rawCash = Array.isArray(cashHistory) ? cashHistory : (cashHistory?.positions || cashHistory?.trades || []);
    const cash = rawCash.map(p => {
      const rawPnl = p.realizedPnl != null ? Number(p.realizedPnl) : (p.currentPnl != null ? Number(p.currentPnl) : 0);
      const isPaper = !p.broker || p.broker === 'PAPER';

      return {
        ...p,
        id: p.id || `cash-${p.enteredAt}`,
        assetClass: 'CASH',
        mode: isPaper ? 'PAPER' : (p.broker || 'LIVE'),
        rawPnl,
        realPnl: rawPnl,
        originalLots: 1,
        displaySymbol: p.symbol || p.underlying || 'CASH',
        qtyDisplay: p.quantity || p.qty || '--',
        entryDate: fmtDate(p.enteredAt),
        entryTime: fmtTime(p.enteredAt),
        exitDate: fmtDate(p.exitedAt || p.createdAt),
        exitTime: fmtTime(p.exitedAt || p.createdAt),
        duration: calcDuration(p.enteredAt, p.exitedAt),
        timestamp: p.exitedAt || p.enteredAt || p.createdAt
      };
    });

    // Exclude active OPEN positions from history
    return [...fno, ...cash].filter(p => p.status !== 'OPEN');
  }, [fnoHistory, cashHistory, lotScaleMode]);

  // Apply Asset, Mode, Strategy, Date Range, and Search Filters
  const filteredHistory = useMemo(() => {
    return allHistory.filter(p => {
      if (assetFilter !== 'ALL' && p.assetClass !== assetFilter) return false;
      
      if (modeFilter !== 'ALL') {
        const isPaperMode = p.mode === 'PAPER';
        if (modeFilter === 'PAPER' && !isPaperMode) return false;
        if (modeFilter === 'LIVE' && isPaperMode) return false;
      }

      if (selectedStrategy !== 'ALL') {
        const strat = p.strategyType || p.strategy || 'OTHER';
        if (strat !== selectedStrategy) return false;
      }

      if (dateRange !== 'ALL') {
        const dateVal = p.timestamp ? new Date(p.timestamp) : null;
        if (dateVal && !isNaN(dateVal.getTime())) {
          const now = new Date();
          if (dateRange === 'TODAY') {
            if (dateVal.toDateString() !== now.toDateString()) return false;
          } else if (dateRange === 'WEEK') {
            const weekAgo = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000);
            if (dateVal < weekAgo) return false;
          } else if (dateRange === 'MONTH') {
            if (dateVal.getMonth() !== now.getMonth() || dateVal.getFullYear() !== now.getFullYear()) return false;
          }
        }
      }

      if (searchTerm.trim()) {
        const term = searchTerm.toLowerCase();
        const sym = String(p.displaySymbol || '').toLowerCase();
        const strat = String(p.strategyType || p.strategy || '').toLowerCase();
        const broker = String(p.broker || '').toLowerCase();
        if (!sym.includes(term) && !strat.includes(term) && !broker.includes(term)) {
          return false;
        }
      }

      return true;
    });
  }, [allHistory, assetFilter, modeFilter, selectedStrategy, dateRange, searchTerm]);

  // Sort Filtered History
  const sortedHistory = useMemo(() => {
    const arr = [...filteredHistory];
    const dir = sortDir === 'asc' ? 1 : -1;
    arr.sort((a, b) => {
      let va, vb;
      switch (sortCol) {
        case 'exitTime': va = new Date(a.exitedAt || a.timestamp || 0); vb = new Date(b.exitedAt || b.timestamp || 0); break;
        case 'entryTime': va = new Date(a.enteredAt || 0); vb = new Date(b.enteredAt || 0); break;
        case 'pnl': va = a.realPnl; vb = b.realPnl; break;
        case 'strategy': va = a.strategyType || ''; vb = b.strategyType || ''; break;
        case 'lots': va = a.originalLots || 0; vb = b.originalLots || 0; break;
        default: va = new Date(a.exitedAt || a.timestamp || 0); vb = new Date(b.exitedAt || b.timestamp || 0);
      }
      if (typeof va === 'string') return dir * va.localeCompare(vb);
      return dir * (va - vb);
    });
    return arr;
  }, [filteredHistory, sortCol, sortDir]);

  // Unique Strategies List
  const availableStrategies = useMemo(() => {
    const set = new Set();
    allHistory.forEach(p => {
      const s = p.strategyType || p.strategy;
      if (s) set.add(s);
    });
    return Array.from(set).sort();
  }, [allHistory]);

  // Compute Metrics & Strategy Breakdown
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

      const strat = p.strategyType || p.strategy || 'OTHER';
      if (!byStrategy[strat]) {
        byStrategy[strat] = { trades: 0, wins: 0, losses: 0, pnl: 0, best: -Infinity, worst: Infinity, holdMins: 0, holdCount: 0 };
      }
      
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
      wins: s.wins,
      losses: s.losses,
      winRate: (s.wins / s.trades) * 100,
      pnl: s.pnl,
      expectancy: s.pnl / s.trades,
      best: s.best === -Infinity ? 0 : s.best,
      worst: s.worst === Infinity ? 0 : s.worst,
      avgHold: s.holdCount > 0 ? s.holdMins / s.holdCount : 0
    })).sort((a,b) => b.pnl - a.pnl);

    return { totalPnl, winRate, expectancy, avgHold, trades, wins, losses, strategyList };
  }, [filteredHistory]);

  // Reset page on filter change
  useEffect(() => {
    setCurrentPage(1);
  }, [assetFilter, modeFilter, selectedStrategy, dateRange, searchTerm, lotScaleMode]);

  // Paginate
  const totalPages = Math.ceil(sortedHistory.length / pageSize) || 1;
  const paginatedHistory = useMemo(() => {
    const start = (currentPage - 1) * pageSize;
    return sortedHistory.slice(start, start + pageSize);
  }, [sortedHistory, currentPage, pageSize]);

  return (
    <div className="space-y-6 mt-4">

      {/* Lot Scaling Mode Toggle Bar */}
      <div className="bg-gradient-to-r from-indigo-50 via-purple-50 to-white p-3 rounded-2xl border border-indigo-100 flex items-center justify-between flex-wrap gap-3 shadow-sm">
        <div className="flex items-center gap-2">
          <span className="text-base">📊</span>
          <div>
            <span className="text-xs font-black text-indigo-950 uppercase tracking-wider block">P&amp;L Figure Scaling Mode</span>
            <span className="text-[10px] text-indigo-600 font-bold">
              {lotScaleMode === 'ONE_LOT' ? 'Showing Actual Figures Normalized to 1 Lot' : 'Showing Cumulative P&L Across All Executed Lots'}
            </span>
          </div>
        </div>

        <div className="flex items-center gap-1 bg-white p-1 rounded-xl border border-indigo-200 shadow-sm">
          <button
            onClick={() => setLotScaleMode('ONE_LOT')}
            className={`px-3 py-1.5 rounded-lg text-xs font-black transition flex items-center gap-1.5 ${lotScaleMode === 'ONE_LOT' ? 'bg-indigo-600 text-white shadow-sm' : 'text-slate-600 hover:text-slate-900'}`}
          >
            <span>🎯 1 Lot (Actual Figures)</span>
          </button>

          <button
            onClick={() => setLotScaleMode('FULL')}
            className={`px-3 py-1.5 rounded-lg text-xs font-black transition flex items-center gap-1.5 ${lotScaleMode === 'FULL' ? 'bg-indigo-600 text-white shadow-sm' : 'text-slate-600 hover:text-slate-900'}`}
          >
            <span>📦 Total Lots Executed</span>
          </button>
        </div>
      </div>

      {/* KPI Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        
        {/* Total Realized P&L */}
        <div className="bg-white rounded-2xl p-5 border border-slate-200 shadow-sm flex flex-col justify-between">
          <div>
            <div className="text-xs font-bold uppercase tracking-wider text-slate-400 mb-1">
              {lotScaleMode === 'ONE_LOT' ? '1-Lot Net P&L (Actual)' : 'Total Realized P&L'}
            </div>
            <div className={`text-2xl font-extrabold font-mono ${metrics.totalPnl >= 0 ? 'text-emerald-600' : 'text-rose-600'}`}>
              {metrics.totalPnl >= 0 ? '+' : '-'}₹{Math.abs(Math.round(metrics.totalPnl)).toLocaleString('en-IN')}
            </div>
          </div>
          <div className="text-xs text-slate-400 mt-2 font-medium">
            {metrics.trades} Trades ({metrics.wins}W / {metrics.losses}L)
          </div>
        </div>

        {/* Win Rate */}
        <div className="bg-white rounded-2xl p-5 border border-slate-200 shadow-sm flex flex-col justify-between">
          <div>
            <div className="text-xs font-bold uppercase tracking-wider text-slate-400 mb-1">Win Rate</div>
            <div className="text-2xl font-extrabold font-mono text-slate-800">
              {metrics.winRate.toFixed(1)}%
            </div>
          </div>
          <div className="text-xs text-slate-400 mt-2 font-medium">Strategy accuracy rate</div>
        </div>

        {/* Expectancy / Trade */}
        <div className="bg-white rounded-2xl p-5 border border-slate-200 shadow-sm flex flex-col justify-between">
          <div>
            <div className="text-xs font-bold uppercase tracking-wider text-slate-400 mb-1">Expectancy / Trade</div>
            <div className={`text-2xl font-extrabold font-mono ${metrics.expectancy >= 0 ? 'text-emerald-600' : 'text-rose-600'}`}>
              {metrics.expectancy >= 0 ? '+' : ''}₹{Math.round(metrics.expectancy).toLocaleString('en-IN')}
            </div>
          </div>
          <div className="text-xs text-slate-400 mt-2 font-medium">Average return per setup ({lotScaleMode === 'ONE_LOT' ? '1 lot' : 'full'})</div>
        </div>

        {/* Avg Hold Duration */}
        <div className="bg-white rounded-2xl p-5 border border-slate-200 shadow-sm flex flex-col justify-between">
          <div>
            <div className="text-xs font-bold uppercase tracking-wider text-slate-400 mb-1">Avg Hold Duration</div>
            <div className="text-2xl font-extrabold font-mono text-slate-800">
              {metrics.avgHold >= 60 ? `${(metrics.avgHold / 60).toFixed(1)} hr` : `${metrics.avgHold.toFixed(0)} min`}
            </div>
          </div>
          <div className="text-xs text-slate-400 mt-2 font-medium">Average trade duration</div>
        </div>

      </div>

      {/* Strategy Breakdown Table */}
      {metrics.strategyList.length > 0 && (
        <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
          <div className="px-5 py-3.5 border-b border-slate-100 bg-slate-50 flex items-center justify-between">
            <h3 className="font-extrabold text-slate-800 text-xs tracking-wider uppercase">
              Strategy Performance Breakdown {lotScaleMode === 'ONE_LOT' ? '(1-Lot Figures)' : '(Total Lots)'}
            </h3>
            <span className="text-xs text-slate-400 font-medium">{metrics.strategyList.length} Strategies</span>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-xs text-left">
              <thead className="bg-slate-50 text-slate-400 font-bold uppercase tracking-wider text-[10px] border-b border-slate-100">
                <tr>
                  <th className="px-5 py-3">Strategy</th>
                  <th className="px-4 py-3 text-right">Trades</th>
                  <th className="px-4 py-3 text-right">Win Rate</th>
                  <th className="px-4 py-3 text-right">Total P&amp;L</th>
                  <th className="px-4 py-3 text-right">Expectancy</th>
                  <th className="px-4 py-3 text-right">Best (1 Lot)</th>
                  <th className="px-4 py-3 text-right">Worst (1 Lot)</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {metrics.strategyList.map(s => (
                  <tr
                    key={s.name}
                    onClick={() => setSelectedStrategy(selectedStrategy === s.name ? 'ALL' : s.name)}
                    className={`cursor-pointer transition-colors ${selectedStrategy === s.name ? 'bg-indigo-50/70 font-bold' : 'hover:bg-slate-50'}`}
                  >
                    <td className="px-5 py-3 font-extrabold text-slate-700 flex items-center gap-2">
                      <span>{STRATEGY_LABELS[s.name] || s.name}</span>
                      {selectedStrategy === s.name && (
                        <span className="text-[9px] bg-indigo-600 text-white px-1.5 py-0.2 rounded font-bold">Filtered</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-right font-mono font-medium text-slate-600">{s.trades}</td>
                    <td className="px-4 py-3 text-right font-mono font-medium text-slate-600">{s.winRate.toFixed(1)}%</td>
                    <td className={`px-4 py-3 text-right font-mono font-bold ${s.pnl >= 0 ? 'text-emerald-600' : 'text-rose-600'}`}>
                      {s.pnl >= 0 ? '+' : ''}₹{Math.round(s.pnl).toLocaleString('en-IN')}
                    </td>
                    <td className={`px-4 py-3 text-right font-mono ${s.expectancy >= 0 ? 'text-emerald-600' : 'text-rose-600'}`}>
                      {s.expectancy >= 0 ? '+' : ''}₹{Math.round(s.expectancy).toLocaleString('en-IN')}
                    </td>
                    <td className="px-4 py-3 text-right font-mono text-emerald-600">
                      {s.best > 0 ? `+₹${Math.round(s.best).toLocaleString('en-IN')}` : '--'}
                    </td>
                    <td className="px-4 py-3 text-right font-mono text-rose-600">
                      {s.worst < 0 ? `₹${Math.round(s.worst).toLocaleString('en-IN')}` : '--'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Trade History Table */}
      <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden space-y-0">
        
        {/* Toolbar */}
        <div className="px-5 py-3.5 bg-slate-50 border-b border-slate-200 flex flex-col sm:flex-row gap-3 items-center justify-between">
          <div className="flex items-center gap-2">
            <h3 className="font-extrabold text-slate-800 text-xs tracking-wider uppercase">Trade History Log</h3>
            <span className="px-2 py-0.5 bg-indigo-100 text-indigo-700 text-xs font-bold rounded-full">
              {sortedHistory.length} Trades
            </span>
          </div>

          <div className="flex flex-wrap items-center gap-2 w-full sm:w-auto">
            {/* Strategy Filter */}
            <select
              value={selectedStrategy}
              onChange={e => setSelectedStrategy(e.target.value)}
              className="bg-white border border-slate-200 text-slate-700 text-xs font-semibold rounded-lg px-2.5 py-1.5 focus:outline-none focus:ring-2 focus:ring-indigo-500 shadow-sm"
            >
              <option value="ALL">All Strategies ({availableStrategies.length})</option>
              {availableStrategies.map(s => (
                <option key={s} value={s}>
                  {STRATEGY_LABELS[s] || s}
                </option>
              ))}
            </select>

            {/* Search Input */}
            <div className="relative flex-1 sm:w-48">
              <input
                type="text"
                placeholder="Search symbol, strategy..."
                value={searchTerm}
                onChange={e => setSearchTerm(e.target.value)}
                className="w-full pl-7 pr-3 py-1.5 text-xs border border-slate-200 rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500 shadow-sm"
              />
              <span className="absolute left-2.5 top-2 text-slate-400 text-xs">🔍</span>
              {searchTerm && (
                <button onClick={() => setSearchTerm('')} className="absolute right-2 top-1.5 text-slate-400 hover:text-slate-600 text-xs">✕</button>
              )}
            </div>

            {/* Page Size */}
            <select
              value={pageSize}
              onChange={e => { setPageSize(Number(e.target.value)); setCurrentPage(1); }}
              className="bg-white border border-slate-200 text-slate-700 text-xs font-semibold rounded-lg px-2 py-1.5 focus:outline-none focus:ring-2 focus:ring-indigo-500 shadow-sm"
            >
              <option value={15}>15 / pg</option>
              <option value={30}>30 / pg</option>
              <option value={50}>50 / pg</option>
              <option value={100}>100 / pg</option>
            </select>
          </div>
        </div>

        {/* History Table */}
        {sortedHistory.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">
            No history trades found matching your active filters.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-xs text-left border-collapse">
              <thead className="bg-slate-50 text-slate-400 uppercase tracking-wider font-extrabold text-[10px] border-b border-slate-200">
                <tr>
                  <th className="px-4 py-3 cursor-pointer hover:bg-slate-100 select-none whitespace-nowrap" onClick={() => toggleSort('entryTime')}>
                    Entry Time {sortCol === 'entryTime' && (sortDir === 'asc' ? '▲' : '▼')}
                  </th>
                  <th className="px-4 py-3 cursor-pointer hover:bg-slate-100 select-none whitespace-nowrap" onClick={() => toggleSort('exitTime')}>
                    Exit Time {sortCol === 'exitTime' && (sortDir === 'asc' ? '▲' : '▼')}
                  </th>
                  <th className="px-3 py-3">Asset</th>
                  <th className="px-4 py-3 cursor-pointer hover:bg-slate-100 select-none" onClick={() => toggleSort('strategy')}>
                    Strategy {sortCol === 'strategy' && (sortDir === 'asc' ? '▲' : '▼')}
                  </th>
                  <th className="px-4 py-3">Symbol / Legs</th>
                  <th className="px-4 py-3 text-right cursor-pointer hover:bg-slate-100 select-none" onClick={() => toggleSort('lots')}>
                    Qty / Lots {sortCol === 'lots' && (sortDir === 'asc' ? '▲' : '▼')}
                  </th>
                  <th className="px-4 py-3 text-right cursor-pointer hover:bg-slate-100 select-none" onClick={() => toggleSort('pnl')}>
                    Realized P&amp;L {sortCol === 'pnl' && (sortDir === 'asc' ? '▲' : '▼')}
                  </th>
                  <th className="px-4 py-3 text-center">Mode</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {paginatedHistory.map(p => {
                  const isExp = expandedRowId === p.id;
                  const isLoss = p.realPnl < 0;

                  return (
                    <React.Fragment key={p.id}>
                      <tr
                        onClick={() => setExpandedRowId(isExp ? null : p.id)}
                        className={`hover:bg-indigo-50/50 transition-colors cursor-pointer ${isExp ? 'bg-indigo-50/70 border-l-4 border-indigo-600' : ''}`}
                      >
                        {/* Entry Date & Time */}
                        <td className="px-4 py-3 font-mono text-slate-500 whitespace-nowrap">
                          <div>{p.entryTime}</div>
                          {p.entryDate && <div className="text-[10px] text-slate-400">{p.entryDate}</div>}
                        </td>

                        {/* Exit Date & Time */}
                        <td className="px-4 py-3 font-mono text-slate-500 whitespace-nowrap">
                          <div className="flex items-center gap-1.5">
                            <span>{p.exitTime}</span>
                            {p.duration && (
                              <span className="px-1.5 py-0.2 rounded bg-slate-100 text-slate-500 text-[9px] font-bold">
                                {p.duration}
                              </span>
                            )}
                          </div>
                          {p.exitDate && <div className="text-[10px] text-slate-400">{p.exitDate}</div>}
                        </td>

                        {/* Asset */}
                        <td className="px-3 py-3">
                          <span className={`px-2 py-0.5 rounded text-[9px] font-bold ${p.assetClass === 'FNO' ? 'bg-indigo-100 text-indigo-700' : 'bg-orange-100 text-orange-700'}`}>
                            {p.assetClass}
                          </span>
                        </td>

                        {/* Strategy */}
                        <td className="px-4 py-3 font-bold text-slate-700 whitespace-nowrap">
                          {STRATEGY_LABELS[p.strategyType || p.strategy] || p.strategyType || p.strategy || '—'}
                        </td>

                        {/* Symbol / Legs */}
                        <td className="px-4 py-3 font-bold text-slate-800">
                          <div>{p.displaySymbol}</div>
                          {p.description && (
                            <div className="text-[10px] font-mono font-normal text-slate-500 truncate max-w-[300px]" title={p.description}>
                              {p.description}
                            </div>
                          )}
                        </td>

                        {/* Qty / Lots */}
                        <td className="px-4 py-3 text-right font-mono font-medium text-slate-600 whitespace-nowrap">
                          {p.qtyDisplay}
                        </td>

                        {/* Realized P&L */}
                        <td className={`px-4 py-3 text-right font-mono font-bold whitespace-nowrap ${p.realPnl >= 0 ? 'text-emerald-600' : 'text-rose-600'}`}>
                          {p.realPnl >= 0 ? '+' : ''}₹{Math.round(p.realPnl).toLocaleString('en-IN')}
                        </td>

                        {/* Mode */}
                        <td className="px-4 py-3 text-center">
                          <span className={`px-2 py-0.5 rounded-full text-[9px] font-bold border ${p.mode === 'PAPER' ? 'bg-slate-100 text-slate-500 border-slate-300' : 'bg-emerald-100 text-emerald-800 border-emerald-300'}`}>
                            {p.mode}
                          </span>
                        </td>
                      </tr>

                      {/* Expandable Payoff & System Audit Drawer */}
                      {isExp && (
                        <tr className="bg-indigo-50/30 border-b border-indigo-100">
                          <td colSpan={8} className="p-4 space-y-3">
                            
                            {/* System Audit Rationale */}
                            <div className={`p-3 rounded-xl text-xs space-y-1 border ${isLoss ? 'bg-rose-50/80 border-rose-200 text-rose-800' : 'bg-slate-50 border-slate-200 text-slate-700'}`}>
                              <div className="flex items-center justify-between font-bold">
                                <span>🔍 Trade Execution Audit &amp; System Notes</span>
                                <span className="font-mono text-[10px] opacity-75">
                                  Hold Time: {p.duration || 'Instant'} | Executed Lots: {p.originalLots}
                                </span>
                              </div>
                              <p className="text-[11px] leading-relaxed">
                                Executed via <strong>{p.mode}</strong> mode. Entry: {p.entryDate} {p.entryTime} | Exit: {p.exitDate} {p.exitTime} | Exit Reason: <strong>{p.exitReason || p.status || 'CLOSED'}</strong>. 
                                {p.originalLots > 1 && lotScaleMode === 'ONE_LOT' && (
                                  <span> (Original paper trade executed {p.originalLots} lots for ₹{Math.round(p.rawPnl).toLocaleString('en-IN')}; shown normalized to 1 lot above).</span>
                                )}
                              </p>
                            </div>

                            {/* Interactive Payoff Chart & Legs Table */}
                            <DetailedOpportunityExpandedRow item={{ ...p, lots: 1 }} title={`Trade Payoff Chart & Execution Breakdown (1 Lot) — ${p.displaySymbol}`} />

                          </td>
                        </tr>
                      )}
                    </React.Fragment>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        {/* Pagination Bar */}
        {sortedHistory.length > 0 && (
          <div className="px-5 py-3.5 bg-slate-50 border-t border-slate-200 flex items-center justify-between text-xs font-bold text-slate-600">
            <div>
              Showing {Math.min(sortedHistory.length, (currentPage - 1) * pageSize + 1)} to {Math.min(sortedHistory.length, currentPage * pageSize)} of {sortedHistory.length} trades
            </div>
            <div className="flex items-center gap-2">
              <button
                disabled={currentPage === 1}
                onClick={() => setCurrentPage(p => Math.max(1, p - 1))}
                className="px-3 py-1 bg-white border border-slate-200 rounded-lg hover:bg-slate-100 disabled:opacity-40 disabled:cursor-not-allowed shadow-sm transition"
              >
                ◀ Previous
              </button>
              <span className="px-2 font-mono text-slate-700">Page {currentPage} of {totalPages}</span>
              <button
                disabled={currentPage >= totalPages}
                onClick={() => setCurrentPage(p => Math.min(totalPages, p + 1))}
                className="px-3 py-1 bg-white border border-slate-200 rounded-lg hover:bg-slate-100 disabled:opacity-40 disabled:cursor-not-allowed shadow-sm transition"
              >
                Next ▶
              </button>
            </div>
          </div>
        )}

      </div>
    </div>
  );
}

export default function Positions() {
  const [executionBroker, setExecutionBroker] = useState('PAPER');
  
  // Dashboard Filters: Default dateRange to ALL so history trades display instantly!
  const [viewState, setViewState] = useState('ACTIVE'); // ACTIVE | HISTORY
  const [assetFilter, setAssetFilter] = useState('ALL'); // ALL | FNO | CASH
  const [modeFilter, setModeFilter] = useState('ALL'); // ALL | LIVE | PAPER
  const [dateRange, setDateRange] = useState('ALL'); // TODAY | WEEK | MONTH | ALL (default ALL)
  
  useEffect(() => {
    client.get('/brokers/decoupled-routing')
      .then((res) => { if (res.data?.executionBroker) setExecutionBroker(res.data.executionBroker); })
      .catch(() => {});
  }, []);

  // Fetch Histories unconditionally
  const { data: fnoHistoryData, refetch: refetchFno } = useQuery({
    queryKey: ['fnoHistoryClosed'],
    queryFn: () => client.get('/option-arbitrage/paper-trades').then(r => r.data?.positions || (Array.isArray(r.data) ? r.data : [])),
    refetchInterval: 10000,
  });
  
  const { data: cashHistoryData, refetch: refetchCash } = useQuery({
    queryKey: ['cashHistoryData'],
    queryFn: () => client.get('/option-arbitrage/cash-history').then(r => r.data),
    refetchInterval: 10000,
  });

  const { data: livePositionsData } = useQuery({
    queryKey: ['livePositionsActiveSummary'],
    queryFn: () => client.get('/option-arbitrage/live-positions').then(r => r.data),
    refetchInterval: 2000,
  });

  const activePositions = livePositionsData?.positions || [];
  const openPositions = activePositions.filter(p => p.status === 'OPEN' || p.status === 'RUNNING' || p.status === 'EXECUTING');
  const activePnl = openPositions.reduce((sum, p) => sum + (p.currentPnl != null ? Number(p.currentPnl) : 0), 0);

  return (
    <div className="space-y-6 pb-20">
      <GlobalConfirmModal />

      {/* Header */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <div className="flex items-center gap-3 mb-1">
            <div className="w-1.5 h-7 rounded-full bg-indigo-600" />
            <h1 className="text-[28px] font-black text-slate-900 tracking-tight">Portfolio &amp; Performance</h1>
          </div>
          <p className="text-slate-500 text-xs sm:text-sm ml-5 font-medium">Unified Command Center for Live, Paper, Cash, and Options Trades</p>
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={() => { refetchFno(); refetchCash(); }}
            className="px-3 py-1.5 bg-white border border-slate-200 hover:bg-slate-50 text-slate-700 text-xs font-bold rounded-xl shadow-sm transition flex items-center gap-1.5"
          >
            <span>🔄</span> Refresh Data
          </button>
        </div>
      </div>

      {/* Master Control Bar */}
      <div className="bg-white p-2.5 rounded-2xl border border-slate-200 shadow-sm flex flex-col lg:flex-row gap-4 justify-between items-center">
        
        {/* Main View Tabs */}
        <div className="flex bg-slate-100 p-1 rounded-xl w-full lg:w-auto">
          <button
            onClick={() => setViewState('ACTIVE')}
            className={`flex-1 lg:px-6 py-2 rounded-lg text-xs sm:text-sm font-bold transition flex items-center justify-center gap-2 ${viewState === 'ACTIVE' ? 'bg-white text-indigo-700 shadow-sm' : 'text-slate-500 hover:text-slate-700'}`}
          >
            <span>Active Trades</span>
            {openPositions.length > 0 && (
              <span className="px-2 py-0.5 bg-emerald-500 text-white text-[10px] font-bold rounded-full">
                {openPositions.length}
              </span>
            )}
          </button>

          <button
            onClick={() => setViewState('HISTORY')}
            className={`flex-1 lg:px-6 py-2 rounded-lg text-xs sm:text-sm font-bold transition flex items-center justify-center gap-2 ${viewState === 'HISTORY' ? 'bg-white text-indigo-700 shadow-sm' : 'text-slate-500 hover:text-slate-700'}`}
          >
            <span>History &amp; Performance</span>
          </button>
        </div>

        {/* Master Filters */}
        <div className="flex flex-wrap gap-2 sm:gap-3 items-center justify-center w-full lg:w-auto">
          
          {/* Asset Class Filter */}
          <div className="flex items-center gap-1 bg-slate-50 border border-slate-200 rounded-xl p-1">
            {[
              { id: 'ALL', label: 'All Assets' },
              { id: 'FNO', label: 'F&O Arbitrage' },
              { id: 'CASH', label: 'Cash Equity' },
            ].map(a => (
              <button
                key={a.id}
                onClick={() => setAssetFilter(a.id)}
                className={`px-3 py-1.5 rounded-lg text-[11px] font-bold transition ${assetFilter === a.id ? 'bg-indigo-600 text-white shadow-sm' : 'text-slate-500 hover:bg-slate-200'}`}
              >
                {a.label}
              </button>
            ))}
          </div>
          
          {/* Execution Mode Filter */}
          <div className="flex items-center gap-1 bg-slate-50 border border-slate-200 rounded-xl p-1">
            {[
              { id: 'ALL', label: 'All Modes' },
              { id: 'LIVE', label: '🔴 LIVE' },
              { id: 'PAPER', label: '📄 PAPER' },
            ].map(m => (
              <button
                key={m.id}
                onClick={() => setModeFilter(m.id)}
                className={`px-3 py-1.5 rounded-lg text-[11px] font-bold transition ${modeFilter === m.id ? 'bg-emerald-600 text-white shadow-sm' : 'text-slate-500 hover:bg-slate-200'}`}
              >
                {m.label}
              </button>
            ))}
          </div>

          {/* Date Range Filter */}
          {viewState === 'HISTORY' && (
            <select 
              value={dateRange} 
              onChange={e => setDateRange(e.target.value)}
              className="bg-slate-50 border border-slate-200 text-slate-700 text-xs font-bold rounded-xl px-3 py-2 focus:outline-none focus:ring-2 focus:ring-indigo-500 shadow-sm"
            >
              <option value="ALL">📅 All Time</option>
              <option value="TODAY">📅 Today</option>
              <option value="WEEK">📅 This Week</option>
              <option value="MONTH">📅 This Month</option>
            </select>
          )}

        </div>
      </div>

      {/* Main Viewport Content */}
      {viewState === 'ACTIVE' && (
        <div className="space-y-6">

          {/* Active Trades Summary */}
          {openPositions.length > 0 && (
            <div className="bg-white rounded-2xl p-4 border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-4">
              <div className="flex items-center gap-3">
                <div className="w-2.5 h-2.5 rounded-full bg-emerald-500 animate-ping" />
                <div>
                  <div className="text-[10px] font-bold text-slate-400 uppercase">Active Trades Running</div>
                  <div className="text-lg font-bold text-slate-800">{openPositions.length} Positions Currently Open</div>
                </div>
              </div>

              <div>
                <div className="text-[10px] font-bold text-slate-400 uppercase">Unrealized P&amp;L</div>
                <div className={`text-lg font-extrabold font-mono ${activePnl >= 0 ? 'text-emerald-600' : 'text-rose-600'}`}>
                  {activePnl >= 0 ? '+' : ''}₹{Math.round(activePnl).toLocaleString('en-IN')}
                </div>
              </div>
            </div>
          )}

          {/* Broker Positions Ground Truth */}
          <BrokerPositionsPanel executionBroker={executionBroker} defaultExpanded={false} />
          
          {/* F&O Arbitrage Active Positions */}
          {(assetFilter === 'ALL' || assetFilter === 'FNO') && (
            <div className="bg-white p-1 rounded-2xl border border-indigo-100 shadow-sm relative overflow-hidden">
               <div className="absolute top-0 left-0 w-1.5 h-full bg-indigo-500"></div>
               <div className="p-3">
                 <h3 className="text-xs font-bold text-indigo-900 uppercase tracking-wider ml-2 mb-2">
                   F&amp;O Arbitrage Positions
                 </h3>
                 <LivePositionsSection executionBroker={executionBroker} modeFilter={modeFilter} assetFilter={assetFilter} defaultExpanded={true} />
               </div>
            </div>
          )}

          {/* Cash Equity Positions */}
          {(assetFilter === 'ALL' || assetFilter === 'CASH') && (
            <div className="bg-white p-1 rounded-2xl border border-orange-100 shadow-sm relative overflow-hidden">
               <div className="absolute top-0 left-0 w-1.5 h-full bg-orange-500"></div>
               <div className="p-3">
                 <h3 className="text-xs font-bold text-orange-900 uppercase tracking-wider ml-2 mb-2">
                   Cash Equity Positions
                 </h3>
                 <CashPositionsSection />
               </div>
            </div>
          )}
        </div>
      )}

      {/* History View */}
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
