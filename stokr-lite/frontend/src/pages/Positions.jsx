import React, { useState, useMemo, useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import client from '../api/client';
import { LivePositionsSection, BrokerPositionsPanel, CashPositionsSection, STRATEGY_LABELS, strategyLabel, DetailedOpportunityExpandedRow, GlobalConfirmModal } from './OptionArbitrage';

function fmtDate(ts) {
  if (!ts) return '--';
  const d = new Date(ts);
  if (isNaN(d.getTime())) return ts;
  return d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' });
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

function fmtRupees(v, signed = true) {
  const n = Math.round(Number(v) || 0);
  const sign = signed ? (n > 0 ? '+' : n < 0 ? '-' : '') : (n < 0 ? '-' : '');
  return `${sign}₹${Math.abs(n).toLocaleString('en-IN')}`;
}

/** Local calendar day of a timestamp, used to group the history log by day. */
function dayKey(ts) {
  const d = ts ? new Date(ts) : null;
  return d && !isNaN(d.getTime()) ? d.toDateString() : 'Unknown date';
}

// Collapsible Accordion Wrapper Component
function AccordionCard({ title, icon, count, defaultOpen = false, children, badgeColor = 'bg-indigo-100 text-indigo-800' }) {
  const [isOpen, setIsOpen] = useState(defaultOpen);

  return (
    <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden transition-all">
      <button
        onClick={() => setIsOpen(!isOpen)}
        className="w-full px-5 py-3.5 bg-slate-50 hover:bg-slate-100/80 border-b border-slate-200 flex items-center justify-between text-left transition select-none"
      >
        <div className="flex items-center gap-2.5">
          <span className="text-base">{icon}</span>
          <h3 className="text-xs font-black uppercase tracking-wider text-slate-800">{title}</h3>
          {count != null && (
            <span className={`px-2 py-0.5 text-[10px] font-extrabold rounded-full ${badgeColor}`}>
              {count} {count === 1 ? 'item' : 'items'}
            </span>
          )}
        </div>
        <div className="flex items-center gap-2 text-xs font-extrabold text-slate-500">
          <span>{isOpen ? 'Collapse' : 'Expand Breakdown'}</span>
          <span className={`transform transition-transform ${isOpen ? 'rotate-180' : ''}`}>▼</span>
        </div>
      </button>

      {isOpen && (
        <div className="p-4 border-t border-slate-100 bg-white">
          {children}
        </div>
      )}
    </div>
  );
}

function UnifiedPerformanceAndHistory({ fnoHistory, cashHistory, arbHistory, assetFilter = 'ALL', modeFilter = 'ALL', datePreset = 'ALL', customStartDate = '', customEndDate = '', setDatePreset, setCustomStartDate, setCustomEndDate, setAssetFilter, setModeFilter }) {
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedStrategy, setSelectedStrategy] = useState('ALL');
  const [expandedRowId, setExpandedRowId] = useState(null);
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(25);
  const [lotScaleMode, setLotScaleMode] = useState('ONE_LOT');

  // Sorting
  const [sortCol, setSortCol] = useState('exitTime');
  const [sortDir, setSortDir] = useState('desc');

  const toggleSort = (col) => {
    if (sortCol === col) setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    else { setSortCol(col); setSortDir(col === 'pnl' || col === 'exitTime' || col === 'entryTime' ? 'desc' : 'asc'); }
  };

  const allHistory = useMemo(() => {
    const rawFno = Array.isArray(fnoHistory) ? fnoHistory : (fnoHistory?.positions || []);
    const fno = rawFno.map(p => {
      const rawPnl = p.pnl != null ? Number(p.pnl) : (p.currentPnl != null ? Number(p.currentPnl) : 0);
      const lots = Number(p.lots) > 0 ? Number(p.lots) : 1;
      const realPnl = lotScaleMode === 'ONE_LOT' ? (rawPnl / lots) : rawPnl;
      const isPaper = !p.broker || p.broker === 'PAPER';

      return {
        ...p,
        id: p.id ? `fno-${p.id}` : `fno-${p.enteredAt}-${Math.random()}`,
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
        timestamp: p.exitedAt || p.enteredAt || p.createdAt,
        entryPriceVal: p.entryPrice != null ? p.entryPrice : (p.entryCost || null),
        exitPriceVal: p.exitPrice != null ? p.exitPrice : null,
        slVal: p.stopLossPrice != null ? p.stopLossPrice : (p.slPrice || (p.slPct ? `${p.slPct}%` : null)),
        targetVal: p.targetPrice != null ? p.targetPrice : (p.targetEdge ? `₹${p.targetEdge}` : (p.targetPct ? `${p.targetPct}%` : null)),
        exitReasonVal: p.errorMessage || p.exitReason || p.reason || p.status || 'CLOSED'
      };
    });

    const rawCash = Array.isArray(cashHistory) ? cashHistory : (cashHistory?.positions || cashHistory?.trades || []);
    const cash = rawCash.map(p => {
      const rawPnl = p.realizedPnl != null ? Number(p.realizedPnl) : (p.currentPnl != null ? Number(p.currentPnl) : 0);
      const isPaper = !p.broker || p.broker === 'PAPER';

      return {
        ...p,
        id: p.id ? `cash-${p.id}` : `cash-${p.enteredAt}-${Math.random()}`,
        assetClass: 'CASH',
        mode: isPaper ? 'PAPER' : (p.broker || 'LIVE'),
        rawPnl,
        realPnl: rawPnl,
        originalLots: 1,
        displaySymbol: p.symbol || p.underlying || 'CASH',
        qtyDisplay: `${p.quantity || p.qty || '--'} Qty`,
        entryDate: fmtDate(p.enteredAt),
        entryTime: fmtTime(p.enteredAt),
        exitDate: fmtDate(p.exitedAt || p.createdAt),
        exitTime: fmtTime(p.exitedAt || p.createdAt),
        duration: calcDuration(p.enteredAt, p.exitedAt),
        timestamp: p.exitedAt || p.enteredAt || p.createdAt,
        entryPriceVal: p.entryPrice || p.entryCost || (p.ceEntryPrice ? (Number(p.ceEntryPrice) + (Number(p.peEntryPrice) || 0)) : null),
        exitPriceVal: p.exitPrice || (p.closeCePrice ? (Number(p.closeCePrice) + (Number(p.closePePrice) || 0)) : null),
        slVal: p.slPrice || (p.slPct ? `${p.slPct}%` : null),
        targetVal: p.targetPrice || (p.targetEdge ? `₹${p.targetEdge}` : (p.targetPct ? `${p.targetPct}%` : null)),
        exitReasonVal: p.exitReason || p.reason || p.status || 'CLOSED'
      };
    });

    const rawArb = Array.isArray(arbHistory) ? arbHistory : (arbHistory?.items || []);

    // Deduplicate arb items against already executed FNO positions
    const executedOppIds = new Set();
    rawFno.forEach(p => {
      if (p.opportunityId != null) executedOppIds.add(String(p.opportunityId));
      if (p.id != null) executedOppIds.add(String(p.id));
    });

    const executedTradeKeys = new Set();
    rawFno.forEach(p => {
      const exitMin = p.exitedAt ? String(p.exitedAt).substring(0, 16) : (p.createdAt ? String(p.createdAt).substring(0, 16) : '');
      const und = String(p.underlying || '').toUpperCase();
      const strat = String(p.strategyType || p.strategy || '').toUpperCase();
      const strike = p.strike != null ? String(p.strike) : '';
      if (exitMin && und) {
        executedTradeKeys.add(und + '_' + strat + '_' + strike + '_' + exitMin);
      }
    });

    const filteredRawArb = rawArb.filter(p => {
      if (p.id != null && executedOppIds.has(String(p.id))) return false;
      const exitMin = (p.exitTime || p.exitedAt || p.createdAt) ? String(p.exitTime || p.exitedAt || p.createdAt).substring(0, 16) : '';
      const und = String(p.underlying || '').toUpperCase();
      const strat = String(p.strategyType || p.strategy || '').toUpperCase();
      const strike = p.strike != null ? String(p.strike) : '';
      if (exitMin && und) {
        const key = und + '_' + strat + '_' + strike + '_' + exitMin;
        if (executedTradeKeys.has(key)) return false;
      }
      return true;
    });

    const arb = filteredRawArb.map(p => {
      const rawPnl = p.pnlAfterCosts != null ? Number(p.pnlAfterCosts) : (p.edgeAfterCosts != null ? Number(p.edgeAfterCosts) : 0);
      const lots = Number(p.lots) > 0 ? Number(p.lots) : 1;
      const realPnl = lotScaleMode === 'ONE_LOT' ? (rawPnl / lots) : rawPnl;
      const isPaper = !p.broker || p.broker === 'PAPER';

      return {
        ...p,
        id: p.id ? `arb-${p.id}` : `arb-${p.scanTime}-${Math.random()}`,
        assetClass: 'FNO',
        mode: isPaper ? 'PAPER' : (p.broker || 'LIVE'),
        rawPnl,
        realPnl,
        originalLots: lots,
        displaySymbol: `${p.underlying || 'FNO'} ${p.strike ? p.strike : ''} ${p.action || ''}`.trim(),
        qtyDisplay: lotScaleMode === 'ONE_LOT' ? `1 lot (${(p.lotSize || 120).toLocaleString()} qty)` : `${lots} lot${lots > 1 ? 's' : ''} (${(lots * (p.lotSize || 120)).toLocaleString()} qty)`,
        entryDate: fmtDate(p.scanTime || p.createdAt || p.enteredAt),
        entryTime: fmtTime(p.scanTime || p.createdAt || p.enteredAt),
        exitDate: fmtDate(p.exitTime || p.exitedAt || p.createdAt),
        exitTime: fmtTime(p.exitTime || p.exitedAt || p.createdAt),
        duration: calcDuration(p.scanTime || p.createdAt, p.exitTime || p.exitedAt),
        timestamp: p.exitTime || p.exitedAt || p.scanTime || p.createdAt,
        entryPriceVal: p.entryPrice || p.netCredit || p.netDebit || null,
        exitPriceVal: p.exitPrice || null,
        slVal: p.slPrice || null,
        targetVal: p.targetPrice || (p.targetEdge ? `₹${p.targetEdge}` : null),
        exitReasonVal: p.exitReason || p.status || 'CLOSED'
      };
    });

    const activeSet = new Set(['OPEN', 'RUNNING', 'EXECUTING', 'PARTIAL', 'DETECTED', 'ENTERED']);
    return [...fno, ...cash, ...arb].filter(p => !activeSet.has(p.status));
  }, [fnoHistory, cashHistory, arbHistory, lotScaleMode]);

  // Date Filtering Logic
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

      // Date Range Filtering
      if (datePreset !== 'ALL') {
        const dateVal = p.timestamp ? new Date(p.timestamp) : null;
        if (!dateVal || isNaN(dateVal.getTime())) return false;

        const now = new Date();
        const startOfDay = new Date(now.getFullYear(), now.getMonth(), now.getDate());

        if (datePreset === 'TODAY') {
          if (dateVal < startOfDay) return false;
        } else if (datePreset === 'WEEK') {
          const weekAgo = new Date(startOfDay.getTime() - 7 * 24 * 60 * 60 * 1000);
          if (dateVal < weekAgo) return false;
        } else if (datePreset === 'MONTH') {
          const monthAgo = new Date(startOfDay.getTime() - 30 * 24 * 60 * 60 * 1000);
          if (dateVal < monthAgo) return false;
        } else if (datePreset === 'CUSTOM') {
          if (customStartDate) {
            const cStart = new Date(customStartDate);
            cStart.setHours(0, 0, 0, 0);
            if (dateVal < cStart) return false;
          }
          if (customEndDate) {
            const cEnd = new Date(customEndDate);
            cEnd.setHours(23, 59, 59, 999);
            if (dateVal > cEnd) return false;
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
  }, [allHistory, assetFilter, modeFilter, selectedStrategy, datePreset, customStartDate, customEndDate, searchTerm]);

  // Sort History
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

  const availableStrategies = useMemo(() => {
    const set = new Set();
    allHistory.forEach(p => {
      const s = p.strategyType || p.strategy;
      if (s) set.add(s);
    });
    return Array.from(set).sort();
  }, [allHistory]);

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
        if (ms > 0) { st.holdMins += (ms / 60000); st.holdCount++; }
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
    })).sort((a, b) => b.pnl - a.pnl);

    const approxNetProfit = totalPnl - (trades * 100);
    return { totalPnl, approxNetProfit, winRate, expectancy, avgHold, trades, wins, losses, strategyList };
  }, [filteredHistory]);

  useEffect(() => {
    setCurrentPage(1);
  }, [assetFilter, modeFilter, selectedStrategy, datePreset, customStartDate, customEndDate, searchTerm, lotScaleMode]);

  const totalPages = Math.ceil(sortedHistory.length / pageSize) || 1;
  const paginatedHistory = useMemo(() => {
    const start = (currentPage - 1) * pageSize;
    return sortedHistory.slice(start, start + pageSize);
  }, [sortedHistory, currentPage, pageSize]);

  // Per-day totals over the whole filtered set (not just this page), for the day subtotal rows
  const dayTotals = useMemo(() => {
    const m = {};
    filteredHistory.forEach(p => {
      const k = dayKey(p.exitedAt || p.timestamp);
      if (!m[k]) m[k] = { trades: 0, pnl: 0 };
      m[k].trades++;
      m[k].pnl += p.realPnl;
    });
    return m;
  }, [filteredHistory]);
  const groupByDay = sortCol === 'exitTime';

  const periodLabel = useMemo(() => {
    if (datePreset === 'ALL') return 'All time';
    if (datePreset === 'TODAY') return 'Today';
    if (datePreset === 'WEEK') return 'Last 7 Days';
    if (datePreset === 'MONTH') return 'Last 30 Days';
    if (datePreset === 'CUSTOM') {
      if (!customStartDate && !customEndDate) return 'Custom Date Range';
      return `${customStartDate || 'Start'} - ${customEndDate || 'End'}`;
    }
    return datePreset || 'All time';
  }, [datePreset, customStartDate, customEndDate]);
  const winRateLabel = metrics.trades > 0 ? `${metrics.winRate.toFixed(0)}%` : '--';

  return (
    <div className="space-y-6 mt-4">

      {/* Prominent Top-Level History Date & Asset Range Control Bar */}
      <div className="bg-gradient-to-r from-slate-900 via-indigo-950 to-slate-900 text-white p-5 rounded-2xl shadow-md space-y-4">
        <div className="flex flex-col lg:flex-row items-start lg:items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <span className="text-xl">📅</span>
            <div>
              <span className="text-xs font-black text-indigo-200 uppercase tracking-widest block">History &amp; Breakdown Filters</span>
              <span className="text-xs text-slate-300 font-medium">Filter performance breakdown and history log by Asset, Mode, or Date Range</span>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-3">
            {/* Asset Class Filter */}
            {setAssetFilter && (
              <div className="flex items-center gap-1 bg-slate-800/90 p-1.5 rounded-xl border border-slate-700/80">
                {[
                  { id: 'ALL', label: 'All Assets' },
                  { id: 'FNO', label: '⚡ F&O Arbitrage' },
                  { id: 'CASH', label: '📈 Cash Equity' },
                ].map(a => (
                  <button
                    key={a.id}
                    onClick={() => setAssetFilter(a.id)}
                    className={`px-3 py-1.5 rounded-lg text-xs font-black transition ${assetFilter === a.id ? 'bg-indigo-600 text-white shadow-md' : 'text-slate-300 hover:text-white hover:bg-slate-700'}`}
                  >
                    {a.label}
                  </button>
                ))}
              </div>
            )}

            {/* Quick Preset Filter Buttons */}
            <div className="flex flex-wrap items-center gap-1.5 bg-slate-800/90 p-1.5 rounded-xl border border-slate-700/80">
              {[
                { id: 'ALL', label: 'All Time' },
                { id: 'TODAY', label: 'Today (1D)' },
                { id: 'WEEK', label: '1 Week (7D)' },
                { id: 'MONTH', label: '1 Month (1M)' },
                { id: 'CUSTOM', label: 'Custom Range 🗓️' },
              ].map(b => (
                <button
                  key={b.id}
                  onClick={() => setDatePreset(b.id)}
                  className={`px-3 py-1.5 rounded-lg text-xs font-extrabold transition ${datePreset === b.id ? 'bg-indigo-600 text-white shadow-md' : 'text-slate-300 hover:text-white hover:bg-slate-700'}`}
                >
                  {b.label}
                </button>
              ))}
            </div>
          </div>
        </div>

        {/* Custom Date Inputs */}
        {datePreset === 'CUSTOM' && (
          <div className="pt-3 border-t border-slate-700/80 flex flex-wrap items-center gap-4 bg-slate-800/60 p-3.5 rounded-xl">
            <div className="flex items-center gap-2">
              <label className="text-xs font-bold text-slate-200">From Date:</label>
              <input
                type="date"
                value={customStartDate}
                onChange={e => setCustomStartDate(e.target.value)}
                className="bg-slate-900 border border-slate-600 text-white rounded-lg px-3 py-1.5 text-xs font-mono font-bold focus:ring-2 focus:ring-indigo-500 outline-none"
              />
            </div>
            <div className="flex items-center gap-2">
              <label className="text-xs font-bold text-slate-200">To Date:</label>
              <input
                type="date"
                value={customEndDate}
                onChange={e => setCustomEndDate(e.target.value)}
                className="bg-slate-900 border border-slate-600 text-white rounded-lg px-3 py-1.5 text-xs font-mono font-bold focus:ring-2 focus:ring-indigo-500 outline-none"
              />
            </div>
            {(customStartDate || customEndDate) && (
              <button
                onClick={() => { setCustomStartDate(''); setCustomEndDate(''); }}
                className="text-xs font-bold text-indigo-400 hover:text-indigo-300 underline ml-auto"
              >
                Clear Dates
              </button>
            )}
          </div>
        )}
      </div>

      {/* Lot Scaling Mode Bar */}
      <div className="bg-white p-3.5 rounded-2xl border border-slate-200 flex items-center justify-between flex-wrap gap-3 shadow-sm">
        <div className="flex items-center gap-2">
          <span className="text-base">📊</span>
          <div>
            <span className="text-xs font-black text-slate-800 uppercase tracking-wider block">P&amp;L Figure Scaling</span>
            <span className="text-[11px] text-slate-500 font-bold">
              {lotScaleMode === 'ONE_LOT' ? 'Showing Actual Figures Normalized to 1 Lot' : 'Showing Cumulative P&L Across All Executed Lots'}
            </span>
          </div>
        </div>

        <div className="flex items-center gap-1 bg-slate-100 p-1 rounded-xl border border-slate-200">
          <button
            onClick={() => setLotScaleMode('ONE_LOT')}
            className={`px-3 py-1.5 rounded-lg text-xs font-black transition ${lotScaleMode === 'ONE_LOT' ? 'bg-indigo-600 text-white shadow-sm' : 'text-slate-600 hover:text-slate-900'}`}
          >
            <span>🎯 1 Lot (Actual Figures)</span>
          </button>

          <button
            onClick={() => setLotScaleMode('FULL')}
            className={`px-3 py-1.5 rounded-lg text-xs font-black transition ${lotScaleMode === 'FULL' ? 'bg-indigo-600 text-white shadow-sm' : 'text-slate-600 hover:text-slate-900'}`}
          >
            <span>📦 Total Lots Executed</span>
          </button>
        </div>
      </div>

      {/* KPI Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-3.5">
        
        {/* Total Realized P&L */}
        <div className="bg-white rounded-2xl p-5 border border-slate-200 shadow-sm flex flex-col justify-between">
          <div>
            <div className="text-xs font-bold uppercase tracking-wider text-slate-400 mb-1">
              {lotScaleMode === 'ONE_LOT' ? '1-Lot Net P&L' : 'Total Realized P&L'}
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

        {/* Approx Net Profit */}
        <div className="bg-white rounded-2xl p-5 border border-slate-200 shadow-sm flex flex-col justify-between">
          <div>
            <div className="text-xs font-bold uppercase tracking-wider text-slate-400 mb-1">Approx Net Profit</div>
            <div className={`text-2xl font-extrabold font-mono ${metrics.approxNetProfit >= 0 ? 'text-emerald-600' : 'text-rose-600'}`}>
              {metrics.approxNetProfit >= 0 ? '+' : '-'}₹{Math.abs(Math.round(metrics.approxNetProfit)).toLocaleString('en-IN')}
            </div>
          </div>
          <div className="text-xs text-slate-400 mt-2 font-medium">Est. after charges & slippage</div>
        </div>

      </div>

      {/* Strategy Breakdown Table */}
      {metrics.strategyList.length > 0 && (
        <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
          <div className="px-5 py-3.5 border-b border-slate-100 bg-slate-50 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
            <div className="flex items-center gap-2.5">
              <h3 className="font-extrabold text-slate-800 text-xs tracking-wider uppercase">
                Strategy Performance Breakdown {lotScaleMode === 'ONE_LOT' ? '(1-Lot Figures)' : '(Total Lots)'}
              </h3>
              <span className="px-2 py-0.5 text-[10px] font-extrabold rounded-full bg-indigo-100 text-indigo-800">
                {metrics.strategyList.length} {metrics.strategyList.length === 1 ? 'Strategy' : 'Strategies'}
              </span>
            </div>

            {/* Inline Breakdown Controls: Asset Filter & Date Filter */}
            <div className="flex flex-wrap items-center gap-2">
              {/* Asset Class Pills */}
              {setAssetFilter && (
                <div className="flex items-center gap-1 bg-white p-1 rounded-xl border border-slate-200 shadow-2xs">
                  {[
                    { id: 'ALL', label: 'All' },
                    { id: 'FNO', label: 'F&O' },
                    { id: 'CASH', label: 'Cash' },
                  ].map(a => (
                    <button
                      key={a.id}
                      onClick={() => setAssetFilter(a.id)}
                      className={`px-2.5 py-1 rounded-lg text-[11px] font-extrabold transition ${assetFilter === a.id ? 'bg-indigo-600 text-white shadow-xs' : 'text-slate-600 hover:bg-slate-100'}`}
                    >
                      {a.label}
                    </button>
                  ))}
                </div>
              )}

              {/* Date Presets */}
              <div className="flex items-center gap-1 bg-white p-1 rounded-xl border border-slate-200 shadow-2xs">
                {[
                  { id: 'ALL', label: 'All Time' },
                  { id: 'TODAY', label: 'Today' },
                  { id: 'WEEK', label: 'Week' },
                  { id: 'MONTH', label: 'Month' },
                  { id: 'CUSTOM', label: 'Custom 🗓️' },
                ].map(b => (
                  <button
                    key={b.id}
                    onClick={() => setDatePreset(b.id)}
                    className={`px-2.5 py-1 rounded-lg text-[11px] font-extrabold transition ${datePreset === b.id ? 'bg-indigo-600 text-white shadow-xs' : 'text-slate-600 hover:bg-slate-100'}`}
                  >
                    {b.label}
                  </button>
                ))}
              </div>
            </div>
          </div>

          {/* Custom Date Pickers inside Breakdown Card if Custom selected */}
          {datePreset === 'CUSTOM' && (
            <div className="px-5 py-2.5 bg-indigo-50/50 border-b border-indigo-100 flex flex-wrap items-center gap-3">
              <span className="text-xs font-extrabold text-indigo-900">Custom Date Range:</span>
              <div className="flex items-center gap-1.5">
                <span className="text-[11px] font-bold text-slate-600">From:</span>
                <input
                  type="date"
                  value={customStartDate}
                  onChange={e => setCustomStartDate(e.target.value)}
                  className="bg-white border border-slate-300 text-slate-800 rounded-lg px-2.5 py-1 text-xs font-mono font-bold focus:ring-2 focus:ring-indigo-500 outline-none"
                />
              </div>
              <div className="flex items-center gap-1.5">
                <span className="text-[11px] font-bold text-slate-600">To:</span>
                <input
                  type="date"
                  value={customEndDate}
                  onChange={e => setCustomEndDate(e.target.value)}
                  className="bg-white border border-slate-300 text-slate-800 rounded-lg px-2.5 py-1 text-xs font-mono font-bold focus:ring-2 focus:ring-indigo-500 outline-none"
                />
              </div>
              {(customStartDate || customEndDate) && (
                <button
                  onClick={() => { setCustomStartDate(''); setCustomEndDate(''); }}
                  className="text-xs font-bold text-indigo-600 hover:text-indigo-800 underline ml-auto"
                >
                  Reset Dates
                </button>
              )}
            </div>
          )}
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

      {/* Trade History Table Card */}
      <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
        
        {/* Trade History Log Toolbar & Date Filters */}
        <div className="p-4 bg-slate-900 text-white border-b border-slate-800 space-y-3">
          <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-3">
            
            {/* Left Title & Live Totals Badges */}
            <div className="flex flex-wrap items-center gap-3">
              <h3 className="font-black text-sm tracking-wider uppercase text-white flex items-center gap-2">
                <span>📜 TRADE HISTORY LOG</span>
                <span className="px-2.5 py-0.5 bg-indigo-600 text-white text-xs font-extrabold rounded-full">
                  {sortedHistory.length} Trades
                </span>
              </h3>

              {/* Real-time Totals Badges */}
              <div className="flex items-center gap-2 flex-wrap text-xs font-mono font-bold">
                <span className={`px-2.5 py-1 rounded-lg border ${metrics.totalPnl >= 0 ? 'bg-emerald-950/80 text-emerald-300 border-emerald-700/60' : 'bg-rose-950/80 text-rose-300 border-rose-700/60'}`}>
                  Range P&amp;L: {metrics.totalPnl >= 0 ? '+' : ''}₹{Math.round(metrics.totalPnl).toLocaleString('en-IN')}
                </span>

                <span className="px-2.5 py-1 rounded-lg bg-slate-800 text-slate-300 border border-slate-700">
                  Ratio: {metrics.wins}W / {metrics.losses}L ({metrics.winRate.toFixed(1)}%)
                </span>
              </div>
            </div>

            {/* Strategy, Search & Page Size */}
            <div className="flex flex-wrap items-center gap-2 w-full lg:w-auto">
              <select
                value={selectedStrategy}
                onChange={e => setSelectedStrategy(e.target.value)}
                className="bg-slate-800 text-slate-200 border border-slate-700 text-xs font-semibold rounded-lg px-2.5 py-1.5 focus:outline-none focus:ring-2 focus:ring-indigo-500"
              >
                <option value="ALL">All Strategies ({availableStrategies.length})</option>
                {availableStrategies.map(s => (
                  <option key={s} value={s}>
                    {STRATEGY_LABELS[s] || s}
                  </option>
                ))}
              </select>

              <div className="relative flex-1 sm:w-44">
                <input
                  type="text"
                  placeholder="Search symbol, strategy..."
                  value={searchTerm}
                  onChange={e => setSearchTerm(e.target.value)}
                  className="w-full pl-7 pr-3 py-1.5 text-xs border border-slate-700 rounded-lg bg-slate-800 text-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
                />
                <span className="absolute left-2.5 top-2 text-slate-400 text-xs">🔍</span>
                {searchTerm && (
                  <button onClick={() => setSearchTerm('')} className="absolute right-2 top-1.5 text-slate-400 hover:text-slate-200 text-xs">✕</button>
                )}
              </div>

              <select
                value={pageSize}
                onChange={e => { setPageSize(Number(e.target.value)); setCurrentPage(1); }}
                className="bg-slate-800 text-slate-200 border border-slate-700 text-xs font-semibold rounded-lg px-2 py-1.5 focus:outline-none focus:ring-2 focus:ring-indigo-500"
              >
                <option value={15}>15 / pg</option>
                <option value={30}>30 / pg</option>
                <option value={50}>50 / pg</option>
                <option value={100}>100 / pg</option>
              </select>
            </div>
          </div>

          {/* Direct Embedded Date Preset Toolbar inside History Log */}
          <div className="pt-2 border-t border-slate-800/80 flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-1.5 flex-wrap">
              <span className="text-[11px] font-extrabold text-slate-400 uppercase tracking-wider mr-1">Timeframe:</span>
              {[
                { id: 'ALL', label: 'All Time' },
                { id: 'TODAY', label: 'Today (1D)' },
                { id: 'WEEK', label: '1 Week (7D)' },
                { id: 'MONTH', label: '1 Month (1M)' },
                { id: 'CUSTOM', label: 'Custom Date Range 🗓️' },
              ].map(b => (
                <button
                  key={b.id}
                  onClick={() => setDatePreset(b.id)}
                  className={`px-3 py-1.5 rounded-lg text-xs font-extrabold transition ${datePreset === b.id ? 'bg-indigo-600 text-white shadow-sm' : 'bg-slate-800 text-slate-300 hover:text-white hover:bg-slate-700'}`}
                >
                  {b.label}
                </button>
              ))}
            </div>

            {datePreset === 'CUSTOM' && (
              <div className="flex flex-wrap items-center gap-2 bg-slate-800 px-3 py-1 rounded-lg border border-slate-700">
                <input
                  type="date"
                  value={customStartDate}
                  onChange={e => setCustomStartDate(e.target.value)}
                  className="bg-slate-900 border border-slate-600 text-white rounded px-2 py-0.5 text-xs font-mono"
                />
                <span className="text-slate-400 text-xs font-bold">to</span>
                <input
                  type="date"
                  value={customEndDate}
                  onChange={e => setCustomEndDate(e.target.value)}
                  className="bg-slate-900 border border-slate-600 text-white rounded px-2 py-0.5 text-xs font-mono"
                />
              </div>
            )}
          </div>

        </div>

        {/* History Table */}
        {sortedHistory.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">
            No history trades found matching your active date &amp; strategy filters.
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
                  <th className="px-3 py-3 text-right">Entry Price</th>
                  <th className="px-3 py-3 text-right">Exit Price</th>
                  <th className="px-3 py-3 text-center">SL / Target</th>
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
                {paginatedHistory.map((p, idx) => {
                  const isExp = expandedRowId === p.id;
                  const isLoss = p.realPnl < 0;
                  const dk = dayKey(p.exitedAt || p.timestamp);
                  const newDay = groupByDay && (idx === 0 || dayKey(paginatedHistory[idx - 1].exitedAt || paginatedHistory[idx - 1].timestamp) !== dk);
                  const dt = dayTotals[dk];

                  return (
                    <React.Fragment key={p.id}>
                      {newDay && dt && (
                        <tr className="bg-slate-50/80">
                          <td colSpan={9} className="px-4 py-1.5 text-[10px] font-extrabold uppercase tracking-wider text-slate-500">
                            📅 {dk === 'Unknown date' ? dk : new Date(dk).toLocaleDateString('en-IN', { weekday: 'short', day: '2-digit', month: 'short', year: 'numeric' })}
                            <span className="ml-2 font-bold normal-case tracking-normal text-slate-400">{dt.trades} trade{dt.trades > 1 ? 's' : ''}</span>
                          </td>
                          <td className={`px-4 py-1.5 text-right font-mono text-[11px] font-extrabold ${dt.pnl >= 0 ? 'text-emerald-600' : 'text-rose-600'}`}>
                            {fmtRupees(dt.pnl)}
                          </td>
                          <td />
                        </tr>
                      )}
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
                          {strategyLabel(p.strategyType || p.strategy)}
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

                        {/* Entry Price */}
                        <td className="px-3 py-3 text-right font-mono text-slate-700 whitespace-nowrap">
                          {p.entryPriceVal != null ? `₹${Number(p.entryPriceVal).toLocaleString('en-IN', { minimumFractionDigits: 1, maximumFractionDigits: 2 })}` : '--'}
                        </td>

                        {/* Exit Price */}
                        <td className="px-3 py-3 text-right font-mono text-slate-700 whitespace-nowrap">
                          {p.exitPriceVal != null ? `₹${Number(p.exitPriceVal).toLocaleString('en-IN', { minimumFractionDigits: 1, maximumFractionDigits: 2 })}` : '--'}
                        </td>

                        {/* SL / Target */}
                        <td className="px-3 py-3 text-center whitespace-nowrap font-mono text-[11px]">
                          <div className="flex flex-col items-center justify-center gap-0.5">
                            {p.targetVal != null ? (
                              <span className="text-emerald-700 font-semibold text-[10px]">
                                T: {typeof p.targetVal === 'number' ? `₹${Number(p.targetVal).toLocaleString('en-IN', { maximumFractionDigits: 1 })}` : p.targetVal}
                              </span>
                            ) : null}
                            {p.slVal != null ? (
                              <span className="text-rose-600 font-medium text-[10px]">
                                SL: {typeof p.slVal === 'number' ? `₹${Number(p.slVal).toLocaleString('en-IN', { maximumFractionDigits: 1 })}` : p.slVal}
                              </span>
                            ) : null}
                            {p.targetVal == null && p.slVal == null && <span className="text-slate-400">--</span>}
                          </div>
                        </td>

                        {/* Qty / Lots */}
                        <td className="px-4 py-3 text-right font-mono font-medium text-slate-600 whitespace-nowrap">
                          {p.qtyDisplay}
                        </td>

                        {/* Realized P&L */}
                        <td className="px-4 py-3 text-right whitespace-nowrap">
                          <span className={`inline-block px-2 py-0.5 rounded-md font-mono font-bold ${p.realPnl > 0 ? 'bg-emerald-50 text-emerald-700' : p.realPnl < 0 ? 'bg-rose-50 text-rose-700' : 'bg-slate-50 text-slate-500'}`}>
                            {fmtRupees(p.realPnl)}
                          </span>
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
                          <td colSpan={11} className="p-4 space-y-3">
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

                            <DetailedOpportunityExpandedRow item={{ ...p, lots: p.lots || 1 }} title={`Trade Payoff Chart & Execution Breakdown (${p.lots || 1} Lot${(p.lots || 1) > 1 ? 's' : ''}) — ${p.displaySymbol}`} />
                          </td>
                        </tr>
                      )}
                    </React.Fragment>
                  );
                })}
              </tbody>

              {/* Table Bottom Cumulative Totals Footer Row */}
              <tfoot className="bg-slate-900 text-white font-mono text-xs font-bold border-t-2 border-slate-700">
                <tr>
                  <td colSpan={4} className="px-4 py-3 text-left tracking-wider uppercase font-sans">
                    TOTALS ({sortedHistory.length} Filtered Trades — {datePreset === 'ALL' ? 'All Time' : datePreset})
                  </td>
                  <td className="px-4 py-3 text-slate-300 font-sans">Filtered Volume Summary</td>
                  <td className="px-4 py-3 text-right text-indigo-300">
                    {sortedHistory.length} Trades Total
                  </td>
                  <td className={`px-4 py-3 text-right text-sm font-extrabold ${metrics.totalPnl >= 0 ? 'text-emerald-400' : 'text-rose-400'}`}>
                    {metrics.totalPnl >= 0 ? '+' : ''}₹{Math.round(metrics.totalPnl).toLocaleString('en-IN')}
                  </td>
                  <td className="px-4 py-3 text-center text-[10px] text-slate-400 uppercase font-sans">
                    Cum. P&amp;L
                  </td>
                </tr>
              </tfoot>
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
  
  // Dashboard Controls
  const [viewState, setViewState] = useState('ACTIVE'); // ACTIVE | HISTORY
  const [assetFilter, setAssetFilter] = useState('ALL'); // ALL | FNO | CASH
  const [modeFilter, setModeFilter] = useState('ALL'); // ALL | LIVE | PAPER
  
  // Date Filters
  const [datePreset, setDatePreset] = useState('ALL'); // ALL | TODAY | WEEK | MONTH | CUSTOM
  const [customStartDate, setCustomStartDate] = useState('');
  const [customEndDate, setCustomEndDate] = useState('');

  // Active Positions Expandable Row State
  const [expandedActiveId, setExpandedActiveId] = useState(null);

  useEffect(() => {
    client.get('/brokers/decoupled-routing')
      .then((res) => { if (res.data?.executionBroker) setExecutionBroker(res.data.executionBroker); })
      .catch(() => {});
  }, []);

  // Fetch Histories
  const { data: fnoHistoryData, refetch: refetchFno } = useQuery({
    queryKey: ['fnoHistoryClosed'],
    queryFn: () => client.get('/option-arbitrage/paper-trades').then(r => r.data),
    refetchInterval: 10000,
  });
  
  const { data: cashHistoryData, refetch: refetchCash } = useQuery({
    queryKey: ['cashHistoryData'],
    queryFn: () => client.get('/option-arbitrage/cash-history').then(r => r.data),
    refetchInterval: 10000,
  });

  const { data: arbHistoryData } = useQuery({
    queryKey: ['arbHistoryAudit'],
    queryFn: () => client.get('/option-arbitrage/history', { params: { page: 0, size: 5000 } }).then(r => r.data),
    refetchInterval: 15000,
  });

  const { data: livePositionsData, refetch: refetchLiveActive } = useQuery({
    queryKey: ['livePositionsActiveSummary'],
    queryFn: () => client.get('/option-arbitrage/live-positions').then(r => r.data),
    refetchInterval: 1000,
    staleTime: 500,
  });

  const { data: cashPositionsData, refetch: refetchCashActive } = useQuery({
    queryKey: ['cashPositionsActiveSummary'],
    queryFn: () => client.get('/option-arbitrage/cash-positions').then(r => r.data),
    refetchInterval: 1000,
    staleTime: 500,
  });

  const fnoActivePositions = useMemo(() => {
    const openRaw = (livePositionsData?.positions || []).filter(p => ['OPEN', 'RUNNING', 'EXECUTING', 'PARTIAL', 'DETECTED', 'ENTERED', 'EXECUTED'].includes(p.status));
    const closedRaw = (livePositionsData?.todayClosedPositions || []);
    const combined = [...openRaw];
    for (const c of closedRaw) {
      if (!combined.some(x => x.id === c.id)) combined.push(c);
    }
    return combined.map(p => ({
      ...p,
      assetClass: 'FNO',
      mode: p.broker || 'PAPER',
      displaySymbol: p.underlying || 'F&O Trade',
      strikeDisplay: p.strike ? String(p.strike) : (p.action ? p.action.replace(/.*\((.*)\)/, '$1') : '—'),
      qtyDisplay: `${p.lots || 1} Lot (${((p.lots || 1) * (p.lotSize || 120)).toLocaleString()} qty)`
    }));
  }, [livePositionsData]);

  const cashActivePositions = useMemo(() => {
    const raw = (cashPositionsData?.positions || []).filter(p => ['OPEN', 'RUNNING', 'EXECUTING', 'PARTIAL', 'DETECTED', 'ENTERED', 'EXECUTED'].includes(p.status));
    return raw.map(p => ({
      ...p,
      assetClass: 'CASH',
      mode: p.broker || 'PAPER',
      displaySymbol: p.symbol || p.underlying || 'CASH',
      qtyDisplay: `${p.quantity || p.qty || 1} Qty`
    }));
  }, [cashPositionsData]);

  const openPositions = useMemo(() => {
    let combined = [];
    if (assetFilter === 'ALL' || assetFilter === 'FNO') combined = combined.concat(fnoActivePositions);
    if (assetFilter === 'ALL' || assetFilter === 'CASH') combined = combined.concat(cashActivePositions);

    if (modeFilter !== 'ALL') {
      combined = combined.filter(p => {
        const isPaper = !p.broker || p.broker === 'PAPER' || p.mode === 'PAPER';
        return modeFilter === 'PAPER' ? isPaper : !isPaper;
      });
    }
    return combined;
  }, [fnoActivePositions, cashActivePositions, assetFilter, modeFilter]);

  const activePnl = openPositions.reduce((sum, p) => sum + (p.currentPnl != null ? Number(p.currentPnl) : (p.unrealizedPnl != null ? Number(p.unrealizedPnl) : 0)), 0);

  return (
    <div className="space-y-6 pb-20">
      <GlobalConfirmModal />

      {/* Header Banner */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <div className="flex items-center gap-3 mb-1">
            <div className="w-1.5 h-7 rounded-full bg-indigo-600" />
            <h1 className="text-[28px] font-black text-slate-900 tracking-tight">Portfolio &amp; Positions</h1>
          </div>
          <p className="text-slate-500 text-xs sm:text-sm ml-5 font-medium">Unified Live Command Center — F&amp;O Arbitrage &amp; Cash Equity</p>
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={() => { refetchFno(); refetchCash(); refetchLiveActive(); refetchCashActive(); }}
            className="px-3.5 py-2 bg-white border border-slate-200 hover:bg-slate-50 text-slate-700 text-xs font-bold rounded-xl shadow-sm transition flex items-center gap-2"
          >
            <span className="text-sm">🔄</span> Refresh Live Data
          </button>
        </div>
      </div>

      {/* Master View & Filter Bar */}
      <div className="bg-white p-3 rounded-2xl border border-slate-200 shadow-sm flex flex-col lg:flex-row gap-4 justify-between items-center">
        
        {/* Main Tabs */}
        <div className="flex bg-slate-100 p-1 rounded-xl w-full lg:w-auto">
          <button
            onClick={() => setViewState('ACTIVE')}
            className={`flex-1 lg:px-6 py-2 rounded-lg text-xs sm:text-sm font-extrabold transition flex items-center justify-center gap-2 ${viewState === 'ACTIVE' ? 'bg-indigo-600 text-white shadow-sm' : 'text-slate-600 hover:text-slate-900'}`}
          >
            <span>🔥 Active Trades</span>
            {openPositions.length > 0 && (
              <span className={`px-2 py-0.5 text-[10px] font-bold rounded-full ${viewState === 'ACTIVE' ? 'bg-white text-indigo-700' : 'bg-emerald-500 text-white'}`}>
                {openPositions.length}
              </span>
            )}
          </button>

          <button
            onClick={() => setViewState('HISTORY')}
            className={`flex-1 lg:px-6 py-2 rounded-lg text-xs sm:text-sm font-extrabold transition flex items-center justify-center gap-2 ${viewState === 'HISTORY' ? 'bg-indigo-600 text-white shadow-sm' : 'text-slate-600 hover:text-slate-900'}`}
          >
            <span>📜 History &amp; Performance</span>
          </button>

          <button
            onClick={() => setViewState('CONFIG')}
            className={`flex-1 lg:px-6 py-2 rounded-lg text-xs sm:text-sm font-extrabold transition flex items-center justify-center gap-2 ${viewState === 'CONFIG' ? 'bg-indigo-600 text-white shadow-sm' : 'text-slate-600 hover:text-slate-900'}`}
          >
            <span>⚙️ Strategy Configuration</span>
          </button>
        </div>

        {/* Global Filters */}
        <div className="flex flex-wrap gap-2.5 items-center justify-center w-full lg:w-auto">
          
          {/* Asset Class */}
          <div className="flex items-center gap-1 bg-slate-50 border border-slate-200 rounded-xl p-1">
            {[
              { id: 'ALL', label: 'All Assets' },
              { id: 'FNO', label: 'F&O Arbitrage' },
              { id: 'CASH', label: 'Cash Equity' },
            ].map(a => (
              <button
                key={a.id}
                onClick={() => setAssetFilter(a.id)}
                className={`px-3 py-1.5 rounded-lg text-[11px] font-extrabold transition ${assetFilter === a.id ? 'bg-slate-900 text-white shadow-sm' : 'text-slate-500 hover:bg-slate-200'}`}
              >
                {a.label}
              </button>
            ))}
          </div>
          
          {/* Mode */}
          <div className="flex items-center gap-1 bg-slate-50 border border-slate-200 rounded-xl p-1">
            {[
              { id: 'ALL', label: 'All Modes' },
              { id: 'LIVE', label: '🔴 LIVE' },
              { id: 'PAPER', label: '📄 PAPER' },
            ].map(m => (
              <button
                key={m.id}
                onClick={() => setModeFilter(m.id)}
                className={`px-3 py-1.5 rounded-lg text-[11px] font-extrabold transition ${modeFilter === m.id ? 'bg-emerald-600 text-white shadow-sm' : 'text-slate-500 hover:bg-slate-200'}`}
              >
                {m.label}
              </button>
            ))}
          </div>

        </div>
      </div>

      {/* ACTIVE TRADES VIEW */}
      {viewState === 'ACTIVE' && (
        <div className="space-y-6">

          {/* Active Summary Top KPI Bar */}
          <div className="bg-gradient-to-r from-slate-900 via-indigo-950 to-slate-900 rounded-2xl p-5 text-white shadow-md flex flex-wrap items-center justify-between gap-4">
            <div className="flex items-center gap-3">
              <div className="w-3 h-3 rounded-full bg-emerald-400 animate-ping" />
              <div>
                <div className="text-[11px] font-bold text-slate-300 uppercase tracking-wider">Live Active Positions</div>
                <div className="text-xl font-extrabold">{openPositions.length} Open Positions Currently Running</div>
              </div>
            </div>

            <div className="flex items-center gap-6">
              <div>
                <div className="text-[10px] font-bold text-slate-300 uppercase tracking-wider">Asset Breakdown</div>
                <div className="text-sm font-extrabold text-indigo-200 font-mono">
                  {fnoActivePositions.length} F&amp;O | {cashActivePositions.length} Cash
                </div>
              </div>

              <div className="bg-slate-800/80 px-4 py-2 rounded-xl border border-slate-700/60">
                <div className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">Total Live Unrealized P&amp;L</div>
                <div className={`text-xl font-extrabold font-mono ${activePnl >= 0 ? 'text-emerald-400' : 'text-rose-400'}`}>
                  {activePnl >= 0 ? '+' : ''}₹{Math.round(activePnl).toLocaleString('en-IN')}
                </div>
              </div>
            </div>
          </div>

          {/* Foldable Group 1: F&O Arbitrage Active Positions */}
          {(assetFilter === 'ALL' || assetFilter === 'FNO') && (
            <AccordionCard
              title={`F&O Arbitrage Active Trades (${fnoActivePositions.length})`}
              icon="⚡"
              count={fnoActivePositions.length}
              defaultOpen={true}
              badgeColor="bg-indigo-100 text-indigo-800"
            >
              <LivePositionsSection executionBroker={executionBroker} modeFilter={modeFilter} assetFilter={assetFilter} defaultExpanded={true} />
            </AccordionCard>
          )}

          {/* Foldable Group 2: Cash Equity Swing Active Positions */}
          {(assetFilter === 'ALL' || assetFilter === 'CASH') && (
            <AccordionCard
              title={`Cash Equity Swing Active Trades (${cashActivePositions.length})`}
              icon="📈"
              count={cashActivePositions.length}
              defaultOpen={true}
              badgeColor="bg-orange-100 text-orange-800"
            >
              <CashPositionsSection />
            </AccordionCard>
          )}

          {/* Foldable Group 3: Broker Ground Truth & Account Positions */}
          <AccordionCard
            title="Broker Ground Truth & Account Positions"
            icon="🏦"
            defaultOpen={false}
          >
            <BrokerPositionsPanel executionBroker={executionBroker} defaultExpanded={false} />
          </AccordionCard>

        </div>
      )}

      {/* HISTORY & PERFORMANCE VIEW */}
      {viewState === 'HISTORY' && (
        <UnifiedPerformanceAndHistory 
          fnoHistory={Array.isArray(fnoHistoryData) ? fnoHistoryData : (fnoHistoryData?.positions || [])} 
          cashHistory={Array.isArray(cashHistoryData) ? cashHistoryData : (cashHistoryData?.positions || cashHistoryData?.trades || [])}
          arbHistory={arbHistoryData}
          assetFilter={assetFilter}
          modeFilter={modeFilter}
          datePreset={datePreset}
          customStartDate={customStartDate}
          customEndDate={customEndDate}
          setDatePreset={setDatePreset}
          setCustomStartDate={setCustomStartDate}
          setCustomEndDate={setCustomEndDate}
          setAssetFilter={setAssetFilter}
          setModeFilter={setModeFilter}
        />
      )}

      {/* STRATEGY CONFIGURATION & RISK CONTROL VIEW */}
      {viewState === 'CONFIG' && (
        <StrategyConfigurationControlPanel 
          fnoHistory={Array.isArray(fnoHistoryData) ? fnoHistoryData : (fnoHistoryData?.positions || [])}
          cashHistory={Array.isArray(cashHistoryData) ? cashHistoryData : (cashHistoryData?.positions || cashHistoryData?.trades || [])}
        />
      )}
    </div>
  );
}


/* ⚙️ ADVANCED STRATEGY CONFIGURATION & RISK CONTROL PANEL */

/* ⚙️ ADVANCED STRATEGY CONFIGURATION & RISK CONTROL PANEL (SEPARATE PAPER & LIVE) */
function StrategyConfigurationControlPanel({ fnoHistory, cashHistory }) {
  const [saveStatus, setSaveStatus] = useState(null);
  const [liveConfirmStrat, setLiveConfirmStrat] = useState(null);
  const [fnoExpanded, setFnoExpanded] = useState(true);
  const [cashExpanded, setCashExpanded] = useState(true);

  // Fetch Saved Strategy Configs from DB
  const { data: serverConfigs, refetch: refetchConfigs } = useQuery({
    queryKey: ['strategyConfigs'],
    queryFn: () => client.get('/option-arbitrage/strategy-configs').then(r => r.data),
    refetchInterval: 10000,
  });

  const defaultStrategies = useMemo(() => ({
    BID_PARITY: { strategyId: 'BID_PARITY', name: 'Bid Parity Arbitrage', icon: '⚡', category: 'FNO', paperEnabled: true, liveEnabled: false, lots: 1, maxActiveTrades: 2, minEdge: 1500, maxDailyLoss: 3000, desc: 'Pure risk-free mispricing arbitrage between futures and synthetic options.' },
    BOX_SPREAD_ARB: { strategyId: 'BOX_SPREAD_ARB', name: 'Box Spread Arbitrage', icon: '📦', category: 'FNO', paperEnabled: true, liveEnabled: false, lots: 1, maxActiveTrades: 2, minEdge: 1500, maxDailyLoss: 3000, desc: '4-leg zero-delta convexity bound arbitrage.' },
    CONDOR_SPREAD: { strategyId: 'CONDOR_SPREAD', name: 'Put/Call Condor Spread', icon: '🎯', category: 'FNO', paperEnabled: true, liveEnabled: false, lots: 1, maxActiveTrades: 2, minEdge: 1500, maxDailyLoss: 3000, desc: 'Defined-risk 4-leg range theta accumulation.' },
    IRON_CONDOR: { strategyId: 'IRON_CONDOR', name: 'Iron Condor Spread', icon: '🛡️', category: 'FNO', paperEnabled: true, liveEnabled: false, lots: 1, maxActiveTrades: 2, minEdge: 1500, maxDailyLoss: 3000, desc: 'Symmetric OTM credit spread with tight wings.' },
    BUTTERFLY: { strategyId: 'BUTTERFLY', name: 'Butterfly Spread', icon: '🦋', category: 'FNO', paperEnabled: true, liveEnabled: false, lots: 1, maxActiveTrades: 2, minEdge: 1500, maxDailyLoss: 3000, desc: 'High win-rate centered pin butterfly.' },
    RATIO_BUTTERFLY: { strategyId: 'RATIO_BUTTERFLY', name: 'Ratio Butterfly', icon: '🎀', category: 'FNO', paperEnabled: true, liveEnabled: false, lots: 1, maxActiveTrades: 2, minEdge: 1500, maxDailyLoss: 3000, desc: 'Asymmetric ratio butterfly for directional skew.' },
    BROKEN_WING_BUTTERFLY: { strategyId: 'BROKEN_WING_BUTTERFLY', name: 'Broken Wing Butterfly', icon: '🔥', category: 'FNO', paperEnabled: true, liveEnabled: false, lots: 1, maxActiveTrades: 2, minEdge: 1500, maxDailyLoss: 3000, desc: 'Skews risk to zero on one side with credit buffer.' },
    VERTICAL: { strategyId: 'VERTICAL', name: 'Vertical Spread', icon: '📐', category: 'FNO', paperEnabled: true, liveEnabled: false, lots: 1, maxActiveTrades: 2, minEdge: 1500, maxDailyLoss: 3000, desc: '2-leg directional vertical spread.' },
    MORNING_THETA: { strategyId: 'MORNING_THETA', name: 'Morning Theta Crush', icon: '🌅', category: 'FNO', paperEnabled: true, liveEnabled: false, lots: 1, maxActiveTrades: 2, minEdge: 1500, maxDailyLoss: 3000, desc: 'Early morning volatility crush entry.' },
    JADE_LIZARD: { strategyId: 'JADE_LIZARD', name: 'Jade Lizard', icon: '🦎', category: 'FNO', paperEnabled: true, liveEnabled: false, lots: 1, maxActiveTrades: 2, minEdge: 1500, maxDailyLoss: 3000, desc: 'Short put + credit call spread.' },
    ADAPTIVE: { strategyId: 'ADAPTIVE', name: 'Adaptive Multi-Leg', icon: '⚙️', category: 'FNO', paperEnabled: true, liveEnabled: false, lots: 1, maxActiveTrades: 2, minEdge: 1500, maxDailyLoss: 3000, desc: 'Dynamic score-based multi-leg selector.' },
    CASH_SURGE: { strategyId: 'CASH_SURGE', name: 'Cash Institutional Surge', icon: '📈', category: 'CASH', paperEnabled: true, liveEnabled: false, lots: 1, maxActiveTrades: 5, minEdge: 3.5, maxDailyLoss: 5000, desc: 'High-volume delivery accumulation breakout.' },
    CASH_SWING: { strategyId: 'CASH_SWING', name: 'Cash Swing Accumulation', icon: '📊', category: 'CASH', paperEnabled: true, liveEnabled: false, lots: 1, maxActiveTrades: 5, minEdge: 3.5, maxDailyLoss: 5000, desc: '3-day institutional swing momentum setup.' },
  }), []);

  const [configs, setConfigs] = useState(defaultStrategies);

  useEffect(() => {
    if (serverConfigs) {
      setConfigs(prev => {
        const next = { ...prev };
        for (const [k, v] of Object.entries(serverConfigs)) {
          if (next[k]) {
            next[k] = { ...next[k], ...v };
          }
        }
        return next;
      });
    }
  }, [serverConfigs]);

  // Compute Performance Badges from history
  const perfMap = useMemo(() => {
    const map = {};
    const all = [...(fnoHistory || []), ...(cashHistory || [])];
    for (const item of all) {
      const strat = (item.strategyType || item.strategy || '').toUpperCase();
      if (!strat) continue;
      if (!map[strat]) map[strat] = { pnl: 0, wins: 0, total: 0 };
      const pnl = Number(item.realizedPnl || item.currentPnl || item.pnl || 0);
      map[strat].pnl += pnl;
      map[strat].total += 1;
      if (pnl > 0) map[strat].wins += 1;
    }
    return map;
  }, [fnoHistory, cashHistory]);

  const updateStrategyField = (stratId, field, val) => {
    setConfigs(prev => ({
      ...prev,
      [stratId]: {
        ...prev[stratId],
        [field]: val
      }
    }));
  };

  const handleLiveToggle = (stratId, currentLiveState) => {
    if (!currentLiveState) {
      setLiveConfirmStrat(stratId);
    } else {
      updateStrategyField(stratId, 'liveEnabled', false);
    }
  };

  const saveAllConfigs = async (customConfigObj = null) => {
    const toSave = customConfigObj || configs;
    try {
      setSaveStatus('SAVING');
      await client.post('/option-arbitrage/strategy-configs', toSave);
      setSaveStatus('SUCCESS');
      refetchConfigs();
      setTimeout(() => setSaveStatus(null), 3000);
    } catch (e) {
      setSaveStatus('ERROR');
      alert('Failed to save configs: ' + (e.response?.data?.message || e.message));
    }
  };

  const applyPreset = (presetType) => {
    const next = { ...configs };
    if (presetType === 'PURE_ARB') {
      Object.keys(next).forEach(k => {
        const isArb = k === 'BID_PARITY' || k === 'BOX_SPREAD_ARB';
        next[k] = { ...next[k], paperEnabled: isArb, liveEnabled: false };
      });
    } else if (presetType === 'ALL_PAPER') {
      Object.keys(next).forEach(k => {
        next[k] = { ...next[k], paperEnabled: true, liveEnabled: false, lots: 1 };
      });
    } else if (presetType === 'TOP_PERFORMERS') {
      const topSet = new Set(['BUTTERFLY', 'CONDOR_SPREAD', 'BID_PARITY', 'BOX_SPREAD_ARB']);
      Object.keys(next).forEach(k => {
        next[k] = { ...next[k], paperEnabled: topSet.has(k), liveEnabled: false };
      });
    }
    setConfigs(next);
    saveAllConfigs(next);
  };

  const disableAllLive = () => {
    const next = { ...configs };
    Object.keys(next).forEach(k => { next[k] = { ...next[k], liveEnabled: false }; });
    setConfigs(next);
    saveAllConfigs(next);
  };

  const toggleCategoryMode = (category, modeType, targetState) => {
    const next = { ...configs };
    const field = modeType === 'PAPER' ? 'paperEnabled' : 'liveEnabled';
    Object.keys(next).forEach(k => {
      if (next[k].category === category) {
        next[k] = { ...next[k], [field]: targetState };
      }
    });
    setConfigs(next);
  };

  const fnoKeys = Object.keys(configs).filter(k => configs[k].category === 'FNO');
  const cashKeys = Object.keys(configs).filter(k => configs[k].category === 'CASH');

  const fnoPaperAll = fnoKeys.every(k => configs[k].paperEnabled);
  const cashPaperAll = cashKeys.every(k => configs[k].paperEnabled);

  return (
    <div className="space-y-6 animate-fade-in-up pb-10">
      
      {/* Safety Confirmation Modal for LIVE Mode */}
      {liveConfirmStrat && (
        <div className="fixed inset-0 z-50 bg-slate-900/60 backdrop-blur-xs flex items-center justify-center p-4">
          <div className="bg-white rounded-2xl max-w-md w-full p-6 shadow-2xl border border-red-200 space-y-4 animate-brand-pop">
            <div className="flex items-center gap-3 text-red-600">
              <div className="w-12 h-12 rounded-2xl bg-red-100 flex items-center justify-center text-2xl animate-pulse">🔴</div>
              <div>
                <h3 className="text-base font-black text-slate-900">Enable LIVE Broker Trading?</h3>
                <p className="text-xs text-slate-500 font-medium">{configs[liveConfirmStrat]?.name}</p>
              </div>
            </div>
            <div className="p-3.5 bg-red-50 border border-red-200 rounded-xl text-xs text-red-900 font-medium space-y-1.5">
              <p>⚠️ <strong>LIVE TRADING NOTICE</strong>: Signal triggers will immediately route real market orders to your active broker account!</p>
              <p className="font-mono text-[11px]">Allocation: <strong>{configs[liveConfirmStrat]?.lots || 1} Set(s)</strong> | Max Active: <strong>{configs[liveConfirmStrat]?.maxActiveTrades || 2} Trades</strong></p>
            </div>
            <div className="flex items-center justify-end gap-2 pt-2">
              <button
                onClick={() => setLiveConfirmStrat(null)}
                className="px-4 py-2 bg-slate-100 hover:bg-slate-200 text-slate-700 text-xs font-bold rounded-xl transition"
              >
                Cancel
              </button>
              <button
                onClick={() => {
                  updateStrategyField(liveConfirmStrat, 'liveEnabled', true);
                  setLiveConfirmStrat(null);
                }}
                className="px-4 py-2 bg-rose-600 hover:bg-rose-700 text-white text-xs font-extrabold rounded-xl shadow-md transition"
              >
                🔴 Yes, Turn LIVE Broker ON
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Top Banner: Global Command Center & Controls */}
      <div className="bg-gradient-to-r from-slate-900 via-indigo-950 to-slate-900 rounded-2xl p-5 text-white shadow-xl space-y-4">
        <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <div className="w-3.5 h-3.5 rounded-full bg-emerald-400 animate-ping" />
            <div>
              <div className="text-[10px] font-black uppercase tracking-wider text-indigo-300">Granular Strategy Execution &amp; Risk Control</div>
              <h2 className="text-xl font-black tracking-tight">Independent Paper &amp; Live Strategy Toggles</h2>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <button
              onClick={disableAllLive}
              className="px-3.5 py-2 bg-rose-500/20 hover:bg-rose-500/30 text-rose-300 border border-rose-500/40 text-xs font-extrabold rounded-xl transition flex items-center gap-1.5"
            >
              <span>🔴</span> Disable ALL Live Broker Trading
            </button>
            <button
              onClick={() => saveAllConfigs()}
              className={`px-4 py-2 rounded-xl text-xs font-black transition flex items-center gap-1.5 shadow-md ${saveStatus === 'SUCCESS' ? 'bg-emerald-500 text-white' : 'bg-indigo-600 hover:bg-indigo-500 text-white'}`}
            >
              <span>{saveStatus === 'SAVING' ? '⏳' : saveStatus === 'SUCCESS' ? '✅' : '💾'}</span>
              {saveStatus === 'SAVING' ? 'Saving...' : saveStatus === 'SUCCESS' ? 'Saved to DB!' : 'Save Configurations'}
            </button>
          </div>
        </div>

        {/* Quick Presets Bar */}
        <div className="pt-2 border-t border-slate-800/80 flex flex-wrap items-center justify-between gap-3 text-xs">
          <div className="text-[11px] font-bold text-slate-400 uppercase tracking-wider">Quick Presets:</div>
          <div className="flex flex-wrap gap-2">
            <button
              onClick={() => applyPreset('PURE_ARB')}
              className="px-3 py-1 bg-slate-800 hover:bg-slate-700 text-indigo-200 border border-slate-700 rounded-lg text-[11px] font-bold transition"
            >
              🛡️ Pure Risk-Free Arbitrage Only
            </button>
            <button
              onClick={() => applyPreset('TOP_PERFORMERS')}
              className="px-3 py-1 bg-slate-800 hover:bg-slate-700 text-emerald-200 border border-slate-700 rounded-lg text-[11px] font-bold transition"
            >
              ⭐ Top Performers Only (&gt;75% Win)
            </button>
            <button
              onClick={() => applyPreset('ALL_PAPER')}
              className="px-3 py-1 bg-slate-800 hover:bg-slate-700 text-slate-300 border border-slate-700 rounded-lg text-[11px] font-bold transition"
            >
              🧪 Enable All Paper Test
            </button>
          </div>
        </div>
      </div>

      {/* SECTION 1: F&O ARBITRAGE & SPREAD STRATEGIES */}
      <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden transition-all">
        {/* Accordion Header */}
        <div className="px-5 py-4 bg-slate-900 flex flex-wrap items-center justify-between gap-3 select-none">
          <div className="flex items-center gap-3 cursor-pointer" onClick={() => setFnoExpanded(!fnoExpanded)}>
            <span className="text-xl">⚡</span>
            <div>
              <h3 className="text-sm font-black text-white tracking-tight">F&amp;O Arbitrage &amp; Spread Strategies (11)</h3>
              <p className="text-[11px] text-slate-400 font-medium">Independent Paper &amp; Live Broker controls per strategy</p>
            </div>
          </div>

          <div className="flex items-center gap-3">
            {/* Quick Category Master Switches */}
            <div className="flex items-center gap-2 bg-slate-800 px-3 py-1.5 rounded-xl border border-slate-700 text-[10px] font-bold">
              <span className="text-slate-400">📄 Paper All:</span>
              <button
                onClick={() => toggleCategoryMode('FNO', 'PAPER', !fnoPaperAll)}
                className={`px-2 py-0.5 rounded font-black transition ${fnoPaperAll ? 'bg-emerald-500 text-white' : 'bg-slate-700 text-slate-400'}`}
              >
                {fnoPaperAll ? 'ON' : 'OFF'}
              </button>
            </div>

            <button onClick={() => setFnoExpanded(!fnoExpanded)} className="text-slate-400 hover:text-white text-xs font-bold">
              {fnoExpanded ? '▼ Collapse' : '▲ Expand'}
            </button>
          </div>
        </div>

        {/* Strategy Grid Cards */}
        {fnoExpanded && (
          <div className="p-4 bg-slate-50 grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
            {fnoKeys.map(key => {
              const item = configs[key];
              const perf = perfMap[key] || { pnl: 0, wins: 0, total: 0 };
              const winRate = perf.total > 0 ? ((perf.wins / perf.total) * 100).toFixed(1) : null;
              const isPaper = item.paperEnabled;
              const isLive = item.liveEnabled;

              return (
                <div key={key} className={`bg-white rounded-2xl border p-4 shadow-sm space-y-3 transition-all transform hover:-translate-y-0.5 ${isLive ? 'border-rose-300 ring-2 ring-rose-500/20 bg-rose-50/10' : isPaper ? 'border-emerald-200 ring-1 ring-emerald-500/10' : 'border-slate-200 opacity-60 bg-slate-50/50'}`}>
                  
                  {/* Title & Description */}
                  <div className="flex items-start justify-between gap-2">
                    <div className="flex items-center gap-2">
                      <span className="text-xl">{item.icon}</span>
                      <div>
                        <h4 className="text-xs font-black text-slate-900 tracking-tight">{item.name}</h4>
                        <p className="text-[10px] text-slate-500 line-clamp-1">{item.desc}</p>
                      </div>
                    </div>

                    {/* Perf Badge */}
                    {perf.total > 0 && (
                      <div className="text-[10px] font-extrabold font-mono text-slate-600 bg-slate-100 px-2 py-0.5 rounded-md whitespace-nowrap">
                        {winRate != null ? `${winRate}% Win` : ''} | <span className={perf.pnl >= 0 ? 'text-emerald-600' : 'text-rose-600'}>{perf.pnl >= 0 ? '+' : ''}₹{Math.round(perf.pnl).toLocaleString('en-IN')}</span>
                      </div>
                    )}
                  </div>

                  {/* DUAL EXECUTION STATUS PANEL (SEPARATE PAPER & LIVE SWITCHES) */}
                  <div className="p-2.5 bg-slate-50 rounded-xl border border-slate-200 space-y-2">
                    
                    {/* Paper Trading Toggle */}
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex items-center gap-1.5">
                        <span className="text-xs">📄</span>
                        <span className="text-xs font-extrabold text-slate-700">Paper Mode</span>
                      </div>
                      <div className="flex items-center bg-white p-0.5 rounded-lg border border-slate-200">
                        <button
                          onClick={() => updateStrategyField(key, 'paperEnabled', true)}
                          className={`px-2.5 py-1 rounded-md text-[10px] font-black transition ${isPaper ? 'bg-emerald-500 text-white shadow-2xs' : 'text-slate-400 hover:text-slate-600'}`}
                        >
                          ENABLED
                        </button>
                        <button
                          onClick={() => updateStrategyField(key, 'paperEnabled', false)}
                          className={`px-2.5 py-1 rounded-md text-[10px] font-black transition ${!isPaper ? 'bg-slate-700 text-white shadow-2xs' : 'text-slate-400 hover:text-slate-600'}`}
                        >
                          DISABLED
                        </button>
                      </div>
                    </div>

                    {/* Live Broker Toggle */}
                    <div className="flex items-center justify-between gap-2 pt-1 border-t border-slate-200/60">
                      <div className="flex items-center gap-1.5">
                        <span className="text-xs">🔴</span>
                        <span className="text-xs font-extrabold text-slate-900">Live Broker</span>
                      </div>
                      <div className="flex items-center bg-white p-0.5 rounded-lg border border-slate-200">
                        <button
                          onClick={() => handleLiveToggle(key, isLive)}
                          className={`px-2.5 py-1 rounded-md text-[10px] font-black transition ${isLive ? 'bg-rose-600 text-white shadow-2xs animate-pulse' : 'text-slate-400 hover:text-slate-600'}`}
                        >
                          🔴 LIVE ON
                        </button>
                        <button
                          onClick={() => updateStrategyField(key, 'liveEnabled', false)}
                          className={`px-2.5 py-1 rounded-md text-[10px] font-black transition ${!isLive ? 'bg-slate-700 text-white shadow-2xs' : 'text-slate-400 hover:text-slate-600'}`}
                        >
                          OFF
                        </button>
                      </div>
                    </div>

                  </div>

                  {/* Settings Grid Controls */}
                  <div className="grid grid-cols-2 gap-2 pt-1">
                    {/* Size (Sets / Lots) */}
                    <div className="bg-slate-50 p-2 rounded-xl border border-slate-200">
                      <label className="text-[9px] font-black uppercase tracking-wider text-slate-400 block mb-0.5">Size / Allocation</label>
                      <select
                        value={item.lots || 1}
                        onChange={(e) => updateStrategyField(key, 'lots', Number(e.target.value))}
                        className="w-full text-xs font-black bg-white border border-slate-300 rounded-lg p-1 text-slate-800 focus:ring-2 focus:ring-indigo-500"
                      >
                        <option value={1}>1 Set (30 qty)</option>
                        <option value={2}>2 Sets (60 qty)</option>
                        <option value={3}>3 Sets (90 qty)</option>
                        <option value={5}>5 Sets (150 qty)</option>
                      </select>
                    </div>

                    {/* Max Concurrent Active Trades */}
                    <div className="bg-slate-50 p-2 rounded-xl border border-slate-200">
                      <label className="text-[9px] font-black uppercase tracking-wider text-slate-400 block mb-0.5">Max Active Trades</label>
                      <select
                        value={item.maxActiveTrades || 2}
                        onChange={(e) => updateStrategyField(key, 'maxActiveTrades', Number(e.target.value))}
                        className="w-full text-xs font-black bg-white border border-slate-300 rounded-lg p-1 text-slate-800 focus:ring-2 focus:ring-indigo-500"
                      >
                        <option value={1}>1 Trade Max</option>
                        <option value={2}>2 Trades Max</option>
                        <option value={3}>3 Trades Max</option>
                        <option value={5}>5 Trades Max</option>
                      </select>
                    </div>

                    {/* Min Edge Filter */}
                    <div className="bg-slate-50 p-2 rounded-xl border border-slate-200">
                      <label className="text-[9px] font-black uppercase tracking-wider text-slate-400 block mb-0.5">Min Net Edge (₹)</label>
                      <input
                        type="number"
                        step={100}
                        value={item.minEdge || 1500}
                        onChange={(e) => updateStrategyField(key, 'minEdge', Number(e.target.value))}
                        className="w-full text-xs font-black bg-white border border-slate-300 rounded-lg p-1 text-slate-800 focus:ring-2 focus:ring-indigo-500"
                      />
                    </div>

                    {/* Max Daily Loss Cap */}
                    <div className="bg-slate-50 p-2 rounded-xl border border-slate-200">
                      <label className="text-[9px] font-black uppercase tracking-wider text-slate-400 block mb-0.5">Daily Loss Cap (₹)</label>
                      <input
                        type="number"
                        step={500}
                        value={item.maxDailyLoss || 3000}
                        onChange={(e) => updateStrategyField(key, 'maxDailyLoss', Number(e.target.value))}
                        className="w-full text-xs font-black bg-white border border-slate-300 rounded-lg p-1 text-rose-700 focus:ring-2 focus:ring-rose-500"
                      />
                    </div>
                  </div>

                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* SECTION 2: CASH EQUITY MOMENTUM STRATEGIES */}
      <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden transition-all">
        <div className="px-5 py-4 bg-slate-900 flex flex-wrap items-center justify-between gap-3 select-none">
          <div className="flex items-center gap-3 cursor-pointer" onClick={() => setCashExpanded(!cashExpanded)}>
            <span className="text-xl">📈</span>
            <div>
              <h3 className="text-sm font-black text-white tracking-tight">Cash Equity Momentum Strategies (2)</h3>
              <p className="text-[11px] text-slate-400 font-medium">Independent Paper &amp; Live Broker controls per cash strategy</p>
            </div>
          </div>

          <div className="flex items-center gap-3">
            <div className="flex items-center gap-2 bg-slate-800 px-3 py-1.5 rounded-xl border border-slate-700 text-[10px] font-bold">
              <span className="text-slate-400">📄 Paper All:</span>
              <button
                onClick={() => toggleCategoryMode('CASH', 'PAPER', !cashPaperAll)}
                className={`px-2 py-0.5 rounded font-black transition ${cashPaperAll ? 'bg-emerald-500 text-white' : 'bg-slate-700 text-slate-400'}`}
              >
                {cashPaperAll ? 'ON' : 'OFF'}
              </button>
            </div>

            <button onClick={() => setCashExpanded(!cashExpanded)} className="text-slate-400 hover:text-white text-xs font-bold">
              {cashExpanded ? '▼ Collapse' : '▲ Expand'}
            </button>
          </div>
        </div>

        {cashExpanded && (
          <div className="p-4 bg-slate-50 grid grid-cols-1 md:grid-cols-2 gap-4">
            {cashKeys.map(key => {
              const item = configs[key];
              const isPaper = item.paperEnabled;
              const isLive = item.liveEnabled;

              return (
                <div key={key} className={`bg-white rounded-2xl border p-4 shadow-sm space-y-3 transition-all ${isLive ? 'border-rose-300 ring-2 ring-rose-500/20 bg-rose-50/10' : isPaper ? 'border-orange-200 ring-1 ring-orange-500/10' : 'border-slate-200 opacity-60 bg-slate-50/50'}`}>
                  
                  <div className="flex items-start justify-between gap-2">
                    <div className="flex items-center gap-2">
                      <span className="text-xl">{item.icon}</span>
                      <div>
                        <h4 className="text-xs font-black text-slate-900 tracking-tight">{item.name}</h4>
                        <p className="text-[10px] text-slate-500 line-clamp-1">{item.desc}</p>
                      </div>
                    </div>
                  </div>

                  <div className="p-2.5 bg-slate-50 rounded-xl border border-slate-200 space-y-2">
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex items-center gap-1.5">
                        <span className="text-xs">📄</span>
                        <span className="text-xs font-extrabold text-slate-700">Paper Mode</span>
                      </div>
                      <div className="flex items-center bg-white p-0.5 rounded-lg border border-slate-200">
                        <button
                          onClick={() => updateStrategyField(key, 'paperEnabled', true)}
                          className={`px-2.5 py-1 rounded-md text-[10px] font-black transition ${isPaper ? 'bg-emerald-500 text-white shadow-2xs' : 'text-slate-400 hover:text-slate-600'}`}
                        >
                          ENABLED
                        </button>
                        <button
                          onClick={() => updateStrategyField(key, 'paperEnabled', false)}
                          className={`px-2.5 py-1 rounded-md text-[10px] font-black transition ${!isPaper ? 'bg-slate-700 text-white shadow-2xs' : 'text-slate-400 hover:text-slate-600'}`}
                        >
                          DISABLED
                        </button>
                      </div>
                    </div>

                    <div className="flex items-center justify-between gap-2 pt-1 border-t border-slate-200/60">
                      <div className="flex items-center gap-1.5">
                        <span className="text-xs">🔴</span>
                        <span className="text-xs font-extrabold text-slate-900">Live Broker</span>
                      </div>
                      <div className="flex items-center bg-white p-0.5 rounded-lg border border-slate-200">
                        <button
                          onClick={() => handleLiveToggle(key, isLive)}
                          className={`px-2.5 py-1 rounded-md text-[10px] font-black transition ${isLive ? 'bg-rose-600 text-white shadow-2xs animate-pulse' : 'text-slate-400 hover:text-slate-600'}`}
                        >
                          🔴 LIVE ON
                        </button>
                        <button
                          onClick={() => updateStrategyField(key, 'liveEnabled', false)}
                          className={`px-2.5 py-1 rounded-md text-[10px] font-black transition ${!isLive ? 'bg-slate-700 text-white shadow-2xs' : 'text-slate-400 hover:text-slate-600'}`}
                        >
                          OFF
                        </button>
                      </div>
                    </div>
                  </div>

                  <div className="grid grid-cols-2 gap-2 pt-1">
                    <div className="bg-slate-50 p-2 rounded-xl border border-slate-200">
                      <label className="text-[9px] font-black uppercase tracking-wider text-slate-400 block mb-0.5">Max Open Positions</label>
                      <select
                        value={item.maxActiveTrades || 5}
                        onChange={(e) => updateStrategyField(key, 'maxActiveTrades', Number(e.target.value))}
                        className="w-full text-xs font-black bg-white border border-slate-300 rounded-lg p-1 text-slate-800 focus:ring-2 focus:ring-indigo-500"
                      >
                        <option value={2}>2 Positions Max</option>
                        <option value={3}>3 Positions Max</option>
                        <option value={5}>5 Positions Max</option>
                        <option value={8}>8 Positions Max</option>
                      </select>
                    </div>

                    <div className="bg-slate-50 p-2 rounded-xl border border-slate-200">
                      <label className="text-[9px] font-black uppercase tracking-wider text-slate-400 block mb-0.5">Min Price Surge %</label>
                      <input
                        type="number"
                        step={0.5}
                        value={item.minEdge || 3.5}
                        onChange={(e) => updateStrategyField(key, 'minEdge', Number(e.target.value))}
                        className="w-full text-xs font-black bg-white border border-slate-300 rounded-lg p-1 text-slate-800 focus:ring-2 focus:ring-indigo-500"
                      />
                    </div>
                  </div>

                </div>
              );
            })}
          </div>
        )}
      </div>

    </div>
  );
}
