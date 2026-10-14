import React, { useState, useEffect, useMemo } from 'react';
import client from '../api/client';
import { toast } from 'react-toastify';

export default function TradeSentinelPanel({ showToast, inline = false }) {
  const [activeTab, setActiveTab] = useState('signals'); // 'signals' | 'pillars' | 'logs'
  const [viewMode, setViewMode] = useState('table'); // 'table' | 'cards'
  const [loading, setLoading] = useState(false);
  const [reconciling, setReconciling] = useState(false);
  const [statusData, setStatusData] = useState(null);
  const [signalsData, setSignalsData] = useState(null);
  const [loadingSignals, setLoadingSignals] = useState(false);
  const [error, setError] = useState(null);

  // Filters for Signals tab
  const [statusFilter, setStatusFilter] = useState('ALL'); // 'ALL' | 'EXECUTED' | 'SCANNED' | 'DUPLICATE'
  const [symbolFilter, setSymbolFilter] = useState('ALL'); // 'ALL' | 'BANKNIFTY' | 'NIFTY'
  const [searchQuery, setSearchQuery] = useState('');

  const notify = (msg, type = 'info') => {
    if (showToast) {
      showToast(msg, type);
    } else {
      try {
        if (type === 'success') toast.success(msg);
        else if (type === 'error') toast.error(msg);
        else if (type === 'warning') toast.warn(msg);
        else toast.info(msg);
      } catch (e) {
        console.log(type, msg);
      }
    }
  };

  const fetchSentinelStatus = async () => {
    try {
      setLoading(true);
      setError(null);
      const res = await client.get('/sentinel/status');
      setStatusData(res.data);
    } catch (e) {
      setError(e.response?.data?.message || e.message || 'Failed to fetch Sentinel status');
    } finally {
      setLoading(false);
    }
  };

  const fetchSignalsAudit = async () => {
    try {
      setLoadingSignals(true);
      const res = await client.get('/sentinel/signals-audit');
      setSignalsData(res.data);
    } catch (e) {
      console.error('Failed to fetch signals audit', e);
    } finally {
      setLoadingSignals(false);
    }
  };

  const handleRefreshAll = () => {
    fetchSentinelStatus();
    fetchSignalsAudit();
  };

  const handleReconcile = async () => {
    try {
      setReconciling(true);
      const res = await client.post('/sentinel/reconcile?autoHeal=true');
      notify(
        `Reconciliation complete: ${res.data.status} (${res.data.autoHealedCount} positions auto-healed)`,
        res.data.status === 'IN_SYNC' ? 'success' : 'warning'
      );
      fetchSentinelStatus();
      fetchSignalsAudit();
    } catch (e) {
      notify('Reconciliation failed: ' + e.message, 'error');
    } finally {
      setReconciling(false);
    }
  };

  const handleCertifyEod = async () => {
    try {
      setReconciling(true);
      const res = await client.post('/sentinel/certify-eod');
      notify(
        res.data.allRealPositionsFlat
          ? 'EOD Flat-Book Certified: 100% Flat across all brokers'
          : `Warning: ${res.data.openRealDbPositions} open positions detected!`,
        res.data.allRealPositionsFlat ? 'success' : 'error'
      );
      fetchSentinelStatus();
    } catch (e) {
      notify('EOD certification failed: ' + e.message, 'error');
    } finally {
      setReconciling(false);
    }
  };

  useEffect(() => {
    fetchSentinelStatus();
    fetchSignalsAudit();
    const interval = setInterval(() => {
      fetchSentinelStatus();
      fetchSignalsAudit();
    }, 15000);
    return () => clearInterval(interval);
  }, []);

  // Filter signals list
  const filteredSignals = useMemo(() => {
    if (!signalsData?.signals) return [];
    return signalsData.signals.filter((s) => {
      // Status filter
      if (statusFilter === 'EXECUTED' && s.auditStatus !== 'PASSED_EXECUTED') return false;
      if (statusFilter === 'SCANNED' && s.auditStatus === 'PASSED_EXECUTED') return false;
      if (statusFilter === 'DUPLICATE' && s.auditStatus !== 'DUPLICATE_SET') return false;

      // Symbol filter
      if (symbolFilter !== 'ALL') {
        const und = (s.underlying || '').toUpperCase();
        if (!und.includes(symbolFilter)) return false;
      }

      // Search query
      if (searchQuery.trim()) {
        const q = searchQuery.toLowerCase();
        const und = (s.underlying || '').toLowerCase();
        const strat = (s.strategyType || '').toLowerCase();
        const action = (s.action || '').toLowerCase();
        const reason = (s.statusReason || '').toLowerCase();
        const strikes = (s.setStrikes || []).join(' ');
        const id = String(s.opportunityId || '');
        const posId = String(s.positionId || '');

        return (
          und.includes(q) ||
          strat.includes(q) ||
          action.includes(q) ||
          reason.includes(q) ||
          strikes.includes(q) ||
          id.includes(q) ||
          posId.includes(q)
        );
      }
      return true;
    });
  }, [signalsData, statusFilter, symbolFilter, searchQuery]);

  const isGreen = statusData?.overallStatus === 'GREEN';
  const isYellow = statusData?.overallStatus === 'YELLOW';

  const formatIST = (timestampStr) => {
    if (!timestampStr) return '--';
    try {
      const d = new Date(timestampStr);
      if (isNaN(d.getTime())) return timestampStr;
      return d.toLocaleTimeString('en-IN', {
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: true,
      });
    } catch {
      return timestampStr;
    }
  };

  const formatStrategyName = (name) => {
    if (!name) return 'STRATEGY';
    return name
      .replace(/_/g, ' ')
      .toLowerCase()
      .replace(/\b\w/g, (c) => c.toUpperCase());
  };

  // Helper to parse action string into discrete legs styled like Image 2
  const parseLegTokens = (actionStr) => {
    if (!actionStr) return null;
    if (actionStr.includes(',')) {
      return actionStr.split(',').map((part, idx) => {
        const trimmed = part.trim();
        const isBuy = trimmed.startsWith('+') || trimmed.toUpperCase().includes('BUY') || trimmed.toUpperCase().startsWith('B ');
        return (
          <span
            key={idx}
            className={`inline-flex items-center px-2 py-0.5 rounded text-[11px] font-mono font-bold border ${
              isBuy
                ? 'bg-emerald-50 text-emerald-800 border-emerald-300'
                : 'bg-rose-50 text-rose-800 border-rose-300'
            }`}
          >
            <span className={`w-1.5 h-1.5 rounded-full mr-1 ${isBuy ? 'bg-emerald-500' : 'bg-rose-500'}`} />
            {trimmed}
          </span>
        );
      });
    }
    return (
      <span className="inline-flex items-center px-2 py-0.5 rounded text-[11px] font-mono font-bold bg-slate-100 text-slate-800 border border-slate-300">
        {actionStr}
      </span>
    );
  };

  const totalScanned = signalsData?.totalSignals ?? 0;
  const totalExecuted = signalsData?.executedSignals ?? 0;
  const totalWaiting = signalsData?.scannedSignals ?? (totalScanned >= totalExecuted ? totalScanned - totalExecuted : 0);
  const totalDuplicates = signalsData?.duplicateSignals ?? 0;

  return (
    <div className={`bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden flex flex-col transition-all ${inline ? '' : 'p-1'}`}>
      
      {/* Top Header Bar */}
      <div className="bg-gradient-to-r from-slate-50 via-indigo-50/50 to-slate-50 border-b border-slate-200 px-5 py-4 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <div className="p-2.5 bg-indigo-100/80 border border-indigo-200 rounded-2xl text-2xl shadow-xs flex items-center justify-center">
            🛡️
          </div>
          <div>
            <div className="flex items-center gap-2.5">
              <h2 className="text-base sm:text-lg font-black text-slate-900 tracking-tight">
                Trade Sentinel &amp; Reconciliation Engine
              </h2>
              <span
                className={`px-2.5 py-0.5 rounded-full text-[11px] font-black uppercase tracking-wider ${
                  isGreen
                    ? 'bg-emerald-100 text-emerald-800 border border-emerald-300'
                    : isYellow
                    ? 'bg-amber-100 text-amber-800 border border-amber-300'
                    : 'bg-rose-100 text-rose-800 border border-rose-300'
                }`}
              >
                {statusData?.statusLabel || 'Online'}
              </span>
            </div>
            <p className="text-xs text-slate-500 font-medium mt-0.5">
              Live Signal Gatekeeper • Pre-Order Margin &amp; Strike Checks Just Before Live Orders • DB vs. Broker Reconciler
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={handleRefreshAll}
            disabled={loading || loadingSignals}
            title="Refresh Sentinel &amp; Signals Data"
            className="px-3 py-1.5 bg-white hover:bg-slate-50 disabled:opacity-50 text-slate-700 font-bold text-xs rounded-xl border border-slate-200 shadow-xs transition flex items-center gap-1.5 cursor-pointer"
          >
            <span className={loading || loadingSignals ? 'animate-spin' : ''}>🔄</span>
            <span>Refresh</span>
          </button>
          <button
            onClick={handleReconcile}
            disabled={reconciling || loading}
            className="px-3.5 py-1.5 bg-emerald-600 hover:bg-emerald-500 disabled:opacity-50 text-white font-extrabold text-xs rounded-xl shadow-xs transition flex items-center gap-1.5 cursor-pointer"
          >
            <span>{reconciling ? '⏳' : '⚡'}</span>
            <span>{reconciling ? 'Auditing...' : 'Reconcile Now'}</span>
          </button>
        </div>
      </div>

      {/* Tabs & View Controls */}
      <div className="bg-slate-50/80 border-b border-slate-200 px-5 py-2.5 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-1.5 bg-white p-1 rounded-xl border border-slate-200 shadow-2xs">
          <button
            onClick={() => setActiveTab('signals')}
            className={`px-3.5 py-1.5 rounded-lg text-xs font-black transition flex items-center gap-2 cursor-pointer ${
              activeTab === 'signals'
                ? 'bg-indigo-600 text-white shadow-xs'
                : 'text-slate-600 hover:text-slate-900 hover:bg-slate-100'
            }`}
          >
            <span>📡</span>
            <span>Received Signals &amp; Gatekeeper Audit</span>
            {totalScanned > 0 && (
              <span className={`px-2 py-0.2 rounded-full text-[10px] font-extrabold ${
                activeTab === 'signals' ? 'bg-indigo-700 text-white' : 'bg-slate-200 text-slate-700'
              }`}>
                {totalScanned}
              </span>
            )}
          </button>

          <button
            onClick={() => setActiveTab('pillars')}
            className={`px-3.5 py-1.5 rounded-lg text-xs font-black transition flex items-center gap-2 cursor-pointer ${
              activeTab === 'pillars'
                ? 'bg-indigo-600 text-white shadow-xs'
                : 'text-slate-600 hover:text-slate-900 hover:bg-slate-100'
            }`}
          >
            <span>🛡️</span>
            <span>4-Pillars Governance &amp; Broker Sync</span>
          </button>

          <button
            onClick={() => setActiveTab('logs')}
            className={`px-3.5 py-1.5 rounded-lg text-xs font-black transition flex items-center gap-2 cursor-pointer ${
              activeTab === 'logs'
                ? 'bg-indigo-600 text-white shadow-xs'
                : 'text-slate-600 hover:text-slate-900 hover:bg-slate-100'
            }`}
          >
            <span>📋</span>
            <span>Event Log</span>
            {statusData?.recentEvents?.length > 0 && (
              <span className={`px-2 py-0.2 rounded-full text-[10px] font-extrabold ${
                activeTab === 'logs' ? 'bg-indigo-700 text-white' : 'bg-slate-200 text-slate-700'
              }`}>
                {statusData.recentEvents.length}
              </span>
            )}
          </button>
        </div>

        {activeTab === 'signals' && (
          <div className="flex items-center gap-2">
            <div className="flex items-center bg-white p-0.5 rounded-lg border border-slate-200 text-xs">
              <button
                onClick={() => setViewMode('table')}
                className={`px-2.5 py-1 rounded-md font-bold transition flex items-center gap-1 ${
                  viewMode === 'table' ? 'bg-indigo-50 text-indigo-700 font-extrabold' : 'text-slate-500 hover:text-slate-800'
                }`}
                title="Structured Table View"
              >
                <span>📊</span> Table View
              </button>
              <button
                onClick={() => setViewMode('cards')}
                className={`px-2.5 py-1 rounded-md font-bold transition flex items-center gap-1 ${
                  viewMode === 'cards' ? 'bg-indigo-50 text-indigo-700 font-extrabold' : 'text-slate-500 hover:text-slate-800'
                }`}
                title="Detailed Cards View"
              >
                <span>🗂️</span> Cards View
              </button>
            </div>
          </div>
        )}
      </div>

      {/* Main Panel Content Area */}
      <div className="p-4 sm:p-5 space-y-4">
        {error && (
          <div className="p-4 bg-rose-50 border border-rose-200 rounded-2xl text-rose-800 text-xs font-semibold flex items-center gap-3">
            <span>⚠️</span>
            <span>{error}</span>
          </div>
        )}

        {/* ========================================================================= */}
        {/* TAB 1: SIGNALS AUDIT & GATEKEEPER VIEW (CLEAN LIGHT THEME - NO MIN EDGE)  */}
        {/* ========================================================================= */}
        {activeTab === 'signals' && (
          <div className="space-y-4">
            
            {/* Multi-Set Explanatory Callout Banner */}
            <div className="bg-gradient-to-r from-indigo-50 via-white to-indigo-50 border border-indigo-200 rounded-2xl p-4 flex items-start gap-3 shadow-2xs">
              <span className="text-2xl shrink-0 mt-0.5">💡</span>
              <div className="text-xs leading-relaxed text-slate-700">
                <span className="font-extrabold text-indigo-900">Pre-Order Gatekeeper Architecture: </span>
                Strategy configuration filters (edge, triggers, lots) are managed by the execution engine. The Sentinel Gatekeeper executes <strong>strictly just before live order dispatch</strong> to verify +15% margin headroom, lot freeze limits, and ensure <strong>distinct strike sets</strong> without duplicate identical set entry.
              </div>
            </div>

            {/* Signals KPI Summary Cards */}
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
              <div className="bg-white border border-slate-200 rounded-2xl p-4 shadow-2xs flex flex-col justify-between">
                <span className="text-[11px] font-black uppercase tracking-wider text-slate-400">Total Scanned Signals</span>
                <div className="text-2xl sm:text-3xl font-black text-slate-900 mt-1">
                  {totalScanned}
                </div>
                <span className="text-[11px] text-slate-500 font-medium mt-0.5">Scanned signals today</span>
              </div>

              <div className="bg-emerald-50/70 border border-emerald-200 rounded-2xl p-4 shadow-2xs flex flex-col justify-between">
                <div className="flex items-center justify-between">
                  <span className="text-[11px] font-black uppercase tracking-wider text-emerald-800">Executed Trades</span>
                  <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
                </div>
                <div className="text-2xl sm:text-3xl font-black text-emerald-700 mt-1">
                  {totalExecuted}
                </div>
                <span className="text-[11px] text-emerald-700 font-semibold mt-0.5">Cleared all pre-order gatekeeper checks</span>
              </div>

              <div className="bg-indigo-50/70 border border-indigo-200 rounded-2xl p-4 shadow-2xs flex flex-col justify-between">
                <span className="text-[11px] font-black uppercase tracking-wider text-indigo-800">Scanned &amp; Ready</span>
                <div className="text-2xl sm:text-3xl font-black text-indigo-700 mt-1">
                  {totalWaiting}
                </div>
                <span className="text-[11px] text-indigo-700 font-medium mt-0.5">Awaiting live trigger criteria</span>
              </div>

              <div className="bg-slate-50 border border-slate-200 rounded-2xl p-4 shadow-2xs flex flex-col justify-between">
                <span className="text-[11px] font-black uppercase tracking-wider text-slate-500">Top Net Edge</span>
                <div className="text-2xl sm:text-3xl font-black text-slate-800 mt-1">
                  ₹{Number(signalsData?.topEdgeToday || 0).toLocaleString('en-IN', { maximumFractionDigits: 1 })}
                </div>
                <span className="text-[11px] text-slate-500 font-medium mt-0.5">Highest edge captured today</span>
              </div>
            </div>

            {/* Filter & Search Bar */}
            <div className="bg-white border border-slate-200 rounded-2xl p-3 flex flex-wrap items-center justify-between gap-3 shadow-2xs">
              <div className="flex items-center gap-1.5 flex-wrap">
                <span className="text-xs font-bold text-slate-500 mr-1">Status:</span>
                <button
                  onClick={() => setStatusFilter('ALL')}
                  className={`px-3 py-1 rounded-xl text-xs font-extrabold transition cursor-pointer ${
                    statusFilter === 'ALL'
                      ? 'bg-slate-800 text-white shadow-xs'
                      : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                  }`}
                >
                  All ({totalScanned})
                </button>
                <button
                  onClick={() => setStatusFilter('EXECUTED')}
                  className={`px-3 py-1 rounded-xl text-xs font-extrabold transition flex items-center gap-1 cursor-pointer ${
                    statusFilter === 'EXECUTED'
                      ? 'bg-emerald-600 text-white shadow-xs'
                      : 'bg-emerald-50 text-emerald-800 border border-emerald-200 hover:bg-emerald-100'
                  }`}
                >
                  <span>✓</span> Executed ({totalExecuted})
                </button>
                <button
                  onClick={() => setStatusFilter('SCANNED')}
                  className={`px-3 py-1 rounded-xl text-xs font-extrabold transition flex items-center gap-1 cursor-pointer ${
                    statusFilter === 'SCANNED'
                      ? 'bg-indigo-600 text-white shadow-xs'
                      : 'bg-indigo-50 text-indigo-700 border border-indigo-200 hover:bg-indigo-100'
                  }`}
                >
                  <span>📡</span> Scanned ({totalWaiting})
                </button>
                {totalDuplicates > 0 && (
                  <button
                    onClick={() => setStatusFilter('DUPLICATE')}
                    className={`px-3 py-1 rounded-xl text-xs font-extrabold transition flex items-center gap-1 cursor-pointer ${
                      statusFilter === 'DUPLICATE'
                        ? 'bg-rose-600 text-white shadow-xs'
                        : 'bg-rose-50 text-rose-800 border border-rose-200 hover:bg-rose-100'
                    }`}
                  >
                    <span>🛡️</span> Duplicate Sets ({totalDuplicates})
                  </button>
                )}

                <div className="h-4 w-[1px] bg-slate-300 mx-1" />

                <span className="text-xs font-bold text-slate-500 mr-1">Symbol:</span>
                <button
                  onClick={() => setSymbolFilter('ALL')}
                  className={`px-2.5 py-1 rounded-xl text-xs font-extrabold transition cursor-pointer ${
                    symbolFilter === 'ALL' ? 'bg-indigo-600 text-white shadow-xs' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                  }`}
                >
                  All
                </button>
                <button
                  onClick={() => setSymbolFilter('BANKNIFTY')}
                  className={`px-2.5 py-1 rounded-xl text-xs font-extrabold transition cursor-pointer ${
                    symbolFilter === 'BANKNIFTY' ? 'bg-indigo-600 text-white shadow-xs' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                  }`}
                >
                  BANKNIFTY
                </button>
                <button
                  onClick={() => setSymbolFilter('NIFTY')}
                  className={`px-2.5 py-1 rounded-xl text-xs font-extrabold transition cursor-pointer ${
                    symbolFilter === 'NIFTY' ? 'bg-indigo-600 text-white shadow-xs' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                  }`}
                >
                  NIFTY
                </button>
              </div>

              <div className="relative w-full sm:w-64">
                <input
                  type="text"
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  placeholder="Search strike, id, reason..."
                  className="w-full bg-slate-50 border border-slate-200 rounded-xl px-3 py-1.5 text-xs text-slate-900 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-indigo-500 font-mono shadow-2xs"
                />
                {searchQuery && (
                  <button
                    onClick={() => setSearchQuery('')}
                    className="absolute right-2.5 top-1.5 text-slate-400 hover:text-slate-700 text-xs font-bold"
                  >
                    ✕
                  </button>
                )}
              </div>
            </div>

            {/* Signals Content: Table or Cards */}
            {loadingSignals && (!signalsData || !signalsData.signals) ? (
              <div className="text-center py-12 text-slate-500 text-xs flex items-center justify-center gap-2">
                <span className="animate-spin text-lg">⏳</span> Loading today's signals audit...
              </div>
            ) : filteredSignals.length === 0 ? (
              <div className="text-center py-12 bg-slate-50 border border-slate-200 rounded-2xl text-slate-500 text-xs space-y-2">
                <div className="text-3xl">🔍</div>
                <div className="font-extrabold text-slate-800">No signals match the current filters</div>
                <p className="text-[11px] text-slate-400 max-w-sm mx-auto">
                  Try changing your filter settings or search term to see signals received earlier today.
                </p>
              </div>
            ) : viewMode === 'table' ? (
              /* STRUCTURED TABLE VIEW */
              <div className="bg-white border border-slate-200 rounded-2xl shadow-sm overflow-hidden">
                <div className="overflow-x-auto">
                  <table className="w-full text-left border-collapse text-xs">
                    <thead>
                      <tr className="bg-slate-900 text-slate-200 text-[10px] font-extrabold uppercase tracking-wider border-b border-slate-800 select-none">
                        <th className="py-3 px-3.5">Scan Time</th>
                        <th className="py-3 px-3">Strategy</th>
                        <th className="py-3 px-3">Symbol</th>
                        <th className="py-3 px-3">Strike Set</th>
                        <th className="py-3 px-3">Legs Breakdown</th>
                        <th className="py-3 px-3 text-right">Net Edge</th>
                        <th className="py-3 px-3 text-center">Status</th>
                        <th className="py-3 px-3.5">Gatekeeper Reason / Audit</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {filteredSignals.map((sig) => {
                        const isExecuted = sig.auditStatus === 'PASSED_EXECUTED';
                        const isDuplicate = sig.auditStatus === 'DUPLICATE_SET';
                        const isHours = sig.auditStatus === 'MARKET_CLOSED';

                        return (
                          <tr
                            key={sig.opportunityId}
                            className={`transition hover:bg-slate-50/80 ${
                              isExecuted ? 'bg-emerald-50/30' : isDuplicate ? 'bg-rose-50/20' : ''
                            }`}
                          >
                            {/* Scan Time */}
                            <td className="py-3 px-3.5 whitespace-nowrap">
                              <div className="font-mono font-bold text-slate-900 text-xs">
                                {formatIST(sig.scanTime)}
                              </div>
                              <span className="text-[10px] text-slate-400 font-mono">
                                #{sig.opportunityId}
                              </span>
                            </td>

                            {/* Strategy */}
                            <td className="py-3 px-3 whitespace-nowrap">
                              <span className="inline-flex items-center px-2 py-0.5 rounded-md text-[11px] font-extrabold bg-purple-50 text-purple-700 border border-purple-200 shadow-2xs">
                                {formatStrategyName(sig.strategyType)}
                              </span>
                            </td>

                            {/* Symbol */}
                            <td className="py-3 px-3 whitespace-nowrap">
                              <div className="font-black text-slate-900 text-xs">
                                {sig.underlying}
                              </div>
                              {sig.expiryDate && (
                                <span className="text-[10px] text-slate-400 font-mono">
                                  {sig.expiryDate}
                                </span>
                              )}
                            </td>

                            {/* Strike Set */}
                            <td className="py-3 px-3">
                              <div className="flex items-center gap-1 flex-wrap max-w-xs">
                                {sig.setStrikes && sig.setStrikes.length > 0 ? (
                                  sig.setStrikes.map((strk, idx) => (
                                    <span
                                      key={idx}
                                      className="px-1.5 py-0.5 rounded text-[11px] font-mono font-bold bg-slate-100 border border-slate-200 text-slate-800"
                                    >
                                      {Number(strk).toLocaleString('en-IN')}
                                    </span>
                                  ))
                                ) : (
                                  <span className="font-mono text-xs text-slate-700">{sig.strike || '--'}</span>
                                )}
                              </div>
                            </td>

                            {/* Legs Breakdown */}
                            <td className="py-3 px-3">
                              <div className="flex items-center gap-1.5 flex-wrap max-w-md">
                                {parseLegTokens(sig.action)}
                              </div>
                            </td>

                            {/* Net Edge */}
                            <td className="py-3 px-3 text-right whitespace-nowrap">
                              <div className="font-black text-xs text-emerald-700">
                                ₹{Number(sig.edgeAfterCosts || 0).toLocaleString('en-IN', { maximumFractionDigits: 1 })}
                              </div>
                              <div className="text-[10px] text-slate-400 font-mono">
                                {sig.edgePoints != null ? `+${sig.edgePoints} pts` : ''}
                              </div>
                            </td>

                            {/* Status Badge */}
                            <td className="py-3 px-3 text-center whitespace-nowrap">
                              <span
                                className={`inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-[10px] font-black uppercase tracking-wider ${
                                  isExecuted
                                    ? 'bg-emerald-100 text-emerald-800 border border-emerald-300'
                                    : isDuplicate
                                    ? 'bg-rose-100 text-rose-800 border border-rose-300'
                                    : isHours
                                    ? 'bg-slate-100 text-slate-700 border border-slate-300'
                                    : 'bg-slate-100 text-slate-700 border border-slate-300'
                                }`}
                              >
                                <span>{isExecuted ? '✓' : isDuplicate ? '🛡️' : isHours ? '⏰' : '📡'}</span>
                                <span>{sig.statusBadge || 'SCANNED'}</span>
                              </span>
                            </td>

                            {/* Gatekeeper Reason / Position Audit */}
                            <td className="py-3 px-3.5">
                              <div className="text-xs font-semibold text-slate-800 leading-snug">
                                {sig.statusReason}
                              </div>
                              {isExecuted && sig.positionId && (
                                <div className="text-[11px] text-slate-500 mt-0.5 flex items-center gap-2">
                                  <span>Trade #{sig.positionId}</span>
                                  <span className={`px-1.5 py-0.2 rounded font-bold text-[10px] ${
                                    sig.positionStatus === 'OPEN' ? 'bg-emerald-100 text-emerald-800' : 'bg-slate-100 text-slate-600'
                                  }`}>
                                    {sig.positionStatus}
                                  </span>
                                  {sig.positionPnl != null && (
                                    <span className={`font-mono font-bold ${sig.positionPnl >= 0 ? 'text-emerald-700' : 'text-rose-700'}`}>
                                      P&amp;L: {sig.positionPnl >= 0 ? '+' : ''}₹{Number(sig.positionPnl).toFixed(2)}
                                    </span>
                                  )}
                                </div>
                              )}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </div>
            ) : (
              /* DETAILED CARDS VIEW */
              <div className="space-y-3">
                {filteredSignals.map((sig) => {
                  const isExecuted = sig.auditStatus === 'PASSED_EXECUTED';
                  const isDuplicate = sig.auditStatus === 'DUPLICATE_SET';
                  const isHours = sig.auditStatus === 'MARKET_CLOSED';

                  return (
                    <div
                      key={sig.opportunityId}
                      className={`rounded-2xl border p-4 bg-white transition shadow-xs ${
                        isExecuted
                          ? 'border-emerald-300 bg-emerald-50/20'
                          : isDuplicate
                          ? 'border-rose-200'
                          : 'border-slate-200'
                      }`}
                    >
                      {/* Card Top Row */}
                      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 pb-2.5">
                        <div className="flex items-center gap-2 flex-wrap">
                          <span className="px-2.5 py-0.5 rounded-lg text-xs font-black uppercase tracking-wider bg-indigo-50 text-indigo-700 border border-indigo-200">
                            {sig.underlying}
                          </span>
                          <span className="font-extrabold text-sm text-slate-900">
                            {formatStrategyName(sig.strategyType)}
                          </span>
                          <span className="text-[11px] font-mono text-slate-500 bg-slate-100 px-2 py-0.5 rounded-md">
                            Opp #{sig.opportunityId}
                          </span>
                          {sig.expiryDate && (
                            <span className="text-[11px] text-slate-500 font-mono">
                              Exp: {sig.expiryDate}
                            </span>
                          )}
                        </div>

                        <div className="flex items-center gap-2">
                          <span className="text-xs font-mono text-slate-500">
                            ⏰ {formatIST(sig.scanTime)}
                          </span>
                          <span
                            className={`px-3 py-0.5 rounded-full text-[11px] font-black uppercase tracking-wider flex items-center gap-1.5 ${
                              isExecuted
                                ? 'bg-emerald-100 text-emerald-800 border border-emerald-300'
                                : isDuplicate
                                ? 'bg-rose-100 text-rose-800 border border-rose-300'
                                : isHours
                                ? 'bg-slate-100 text-slate-700 border border-slate-300'
                                : 'bg-slate-100 text-slate-700 border border-slate-300'
                            }`}
                          >
                            <span>{isExecuted ? '✓' : isDuplicate ? '🛡️' : isHours ? '⏰' : '📡'}</span>
                            <span>{sig.statusBadge || 'SCANNED'}</span>
                          </span>
                        </div>
                      </div>

                      {/* Card Center: Strike Set & Legs */}
                      <div className="grid grid-cols-1 md:grid-cols-12 gap-3 py-3 items-center">
                        <div className="md:col-span-8 space-y-2">
                          <div className="flex items-center gap-2 flex-wrap">
                            <span className="text-[10px] font-black uppercase tracking-wider text-slate-400">
                              Strike Set:
                            </span>
                            {sig.setStrikes && sig.setStrikes.length > 0 ? (
                              sig.setStrikes.map((strk, idx) => (
                                <span
                                  key={idx}
                                  className="px-2 py-0.5 rounded-md text-[11px] font-mono font-bold bg-slate-100 border border-slate-200 text-slate-800"
                                >
                                  {Number(strk).toLocaleString('en-IN')}
                                </span>
                              ))
                            ) : (
                              <span className="text-xs font-mono text-slate-700">Strike: {sig.strike || '--'}</span>
                            )}
                          </div>

                          <div className="flex items-center gap-1.5 flex-wrap">
                            {parseLegTokens(sig.action)}
                          </div>

                          <div className="text-[11px] text-slate-500 flex items-center gap-3">
                            <span>Spot: <strong className="text-slate-800">₹{sig.spotPrice || '--'}</strong></span>
                            <span>•</span>
                            <span>Futures: <strong className="text-slate-800">₹{sig.futuresPrice || '--'}</strong></span>
                          </div>
                        </div>

                        <div className="md:col-span-4 bg-slate-50 rounded-xl p-3 border border-slate-200 flex flex-col justify-center text-right space-y-0.5">
                          <div className="text-[10px] font-black text-slate-500 uppercase tracking-wider">
                            Net Edge (Post Costs)
                          </div>
                          <div className="text-xl font-black text-emerald-700">
                            ₹{Number(sig.edgeAfterCosts || 0).toLocaleString('en-IN', { maximumFractionDigits: 1 })}
                          </div>
                          <div className="text-[10px] text-slate-500 font-mono">
                            Raw Edge: {sig.edgePoints != null ? `+${sig.edgePoints} pts` : '--'}
                          </div>
                        </div>
                      </div>

                      {/* Card Bottom */}
                      <div className="bg-slate-50 rounded-xl p-2.5 border border-slate-200 flex flex-wrap items-center justify-between gap-2 text-xs">
                        <div className="flex items-center gap-2">
                          <span className="font-extrabold text-slate-500">Gatekeeper Status:</span>
                          <span className={`font-bold ${
                            isExecuted
                              ? 'text-emerald-800'
                              : isDuplicate
                              ? 'text-rose-700'
                              : 'text-slate-700'
                          }`}>
                            {sig.statusReason}
                          </span>
                        </div>

                        <div className="flex items-center gap-1.5 text-[10px] font-mono">
                          <span className="px-2 py-0.5 rounded bg-emerald-100 text-emerald-800 border border-emerald-200 font-bold">
                            ✓ Lot {sig.gatekeeper?.lotSize || 30}
                          </span>
                          <span className="px-2 py-0.5 rounded bg-emerald-100 text-emerald-800 border border-emerald-200 font-bold">
                            ✓ Headroom +15%
                          </span>
                          <span className={`px-2 py-0.5 rounded font-bold border ${
                            isDuplicate
                              ? 'bg-rose-100 text-rose-800 border-rose-300'
                              : 'bg-emerald-100 text-emerald-800 border-emerald-200'
                          }`}>
                            {isDuplicate ? '✕ Duplicate Set' : '✓ Unique Strike Set'}
                          </span>
                        </div>
                      </div>

                      {isExecuted && sig.positionId && (
                        <div className="mt-2.5 pt-2 border-t border-slate-100 flex items-center justify-between text-xs text-slate-600">
                          <div className="flex items-center gap-2">
                            <span>Linked Order:</span>
                            <strong className="text-slate-900">Trade #{sig.positionId}</strong>
                            <span className={`px-2 py-0.2 rounded text-[10px] font-bold ${
                              sig.positionStatus === 'OPEN'
                                ? 'bg-emerald-100 text-emerald-800'
                                : 'bg-slate-200 text-slate-700'
                            }`}>
                              {sig.positionStatus}
                            </span>
                            {sig.exitReason && (
                              <span className="text-[11px] text-slate-500">
                                Exit: {sig.exitReason}
                              </span>
                            )}
                          </div>
                          {sig.positionPnl != null && (
                            <div className="font-mono font-bold">
                              P&amp;L:{' '}
                              <span className={sig.positionPnl >= 0 ? 'text-emerald-700' : 'text-rose-700'}>
                                {sig.positionPnl >= 0 ? '+' : ''}₹{Number(sig.positionPnl).toFixed(2)}
                              </span>
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        )}

        {/* ========================================================================= */}
        {/* TAB 2: 4-PILLARS GOVERNANCE (LIGHT THEME)                                  */}
        {/* ========================================================================= */}
        {activeTab === 'pillars' && (
          <div className="space-y-5">
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-3.5">
              {/* Gatekeeper Card */}
              <div className="bg-white border border-slate-200 rounded-2xl p-4 space-y-2 shadow-2xs">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-black text-indigo-700 uppercase tracking-wider">
                    1. Gatekeeper
                  </span>
                  <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
                </div>
                <h4 className="font-black text-sm text-slate-900">Pre-Order Checks</h4>
                <p className="text-[11px] text-slate-500 leading-relaxed font-medium">
                  Executes strictly just before LIVE order placement: validates +15% margin headroom, lot freeze limits &amp; unique strike sets.
                </p>
                <div className="pt-1 text-[10px] text-emerald-700 font-bold flex items-center gap-1">
                  <span>✓ Active Pre-Order Gatekeeper</span>
                </div>
              </div>

              {/* Watchdog Card */}
              <div className="bg-white border border-slate-200 rounded-2xl p-4 space-y-2 shadow-2xs">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-black text-indigo-700 uppercase tracking-wider">
                    2. Watchdog
                  </span>
                  <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
                </div>
                <h4 className="font-black text-sm text-slate-900">In-Flight Watchdog</h4>
                <p className="text-[11px] text-slate-500 leading-relaxed font-medium">
                  BUY-first leg sequencing, ₹0.05 tick rounding, 5% marketable limit protection &amp; instant rollback.
                </p>
                <div className="pt-1 text-[10px] text-emerald-700 font-bold flex items-center gap-1">
                  <span>✓ Rollback Armed on Leg Timeout</span>
                </div>
              </div>

              {/* Reconciler Card */}
              <div className="bg-white border border-slate-200 rounded-2xl p-4 space-y-2 shadow-2xs">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-black text-indigo-700 uppercase tracking-wider">
                    3. Reconciler
                  </span>
                  <span
                    className={`w-2 h-2 rounded-full ${
                      statusData?.lastReconciliation?.status === 'IN_SYNC' ? 'bg-emerald-500' : 'bg-amber-500'
                    } animate-pulse`}
                  />
                </div>
                <h4 className="font-black text-sm text-slate-900">DB vs. Broker Sync</h4>
                <p className="text-[11px] text-slate-500 leading-relaxed font-medium">
                  Auto-heals phantom positions if closed outside the app. Verified flat at market close.
                </p>
                <div className="pt-1 text-[10px] font-bold flex items-center gap-1 text-emerald-700">
                  <span>✓ Status: {statusData?.lastReconciliation?.status || 'IN_SYNC'}</span>
                </div>
              </div>

              {/* Certifier Card */}
              <div className="bg-white border border-slate-200 rounded-2xl p-4 space-y-2 shadow-2xs">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-black text-indigo-700 uppercase tracking-wider">
                    4. Certifier
                  </span>
                  <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
                </div>
                <h4 className="font-black text-sm text-slate-900">EOD Flat-Book</h4>
                <p className="text-[11px] text-slate-500 leading-relaxed font-medium">
                  Audits post-3:20 PM positions to certify zero unintended overnight intraday exposure.
                </p>
                <div className="pt-1 text-[10px] text-emerald-700 font-bold flex items-center gap-1">
                  <span>✓ 100% Flat Book Certified</span>
                </div>
              </div>
            </div>

            {/* Connected Brokers & Margins */}
            <div className="bg-slate-50 border border-slate-200 rounded-2xl p-5 space-y-3">
              <div className="flex items-center justify-between">
                <h4 className="text-xs font-black text-slate-800 uppercase tracking-wider flex items-center gap-2">
                  <span>🏦</span> Connected Brokers &amp; Margin Health
                </h4>
                <button
                  onClick={handleCertifyEod}
                  disabled={reconciling}
                  className="text-xs text-indigo-700 hover:text-indigo-900 font-extrabold transition flex items-center gap-1 cursor-pointer"
                >
                  <span>📜 Certify Flat Book</span>
                </button>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
                {statusData?.brokerSummaries && Object.keys(statusData.brokerSummaries).length > 0 ? (
                  Object.entries(statusData.brokerSummaries).map(([broker, data]) => (
                    <div
                      key={broker}
                      className="bg-white border border-slate-200 rounded-xl p-3.5 space-y-1 shadow-2xs"
                    >
                      <div className="flex items-center justify-between">
                        <span className="font-black text-xs text-slate-900 tracking-wide">{broker}</span>
                        <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-emerald-100 text-emerald-800">
                          {data.status}
                        </span>
                      </div>
                      <div className="text-[11px] text-slate-500">
                        Client Code:{' '}
                        <span className="font-semibold text-slate-800">{data.clientCode || 'Active'}</span>
                      </div>
                      <div className="text-sm font-black text-indigo-700 pt-0.5">
                        ₹
                        {Number(data.availableMargin || 0).toLocaleString('en-IN', {
                          maximumFractionDigits: 2,
                        })}
                        <span className="text-[10px] text-slate-400 font-normal ml-1">available</span>
                      </div>
                    </div>
                  ))
                ) : (
                  <div className="col-span-3 text-center py-4 bg-white rounded-xl border border-slate-200 text-xs text-slate-500">
                    ⚡ Paper Trading Mode Active (Virtual Margin Pool Operational)
                  </div>
                )}
              </div>
            </div>
          </div>
        )}

        {/* ========================================================================= */}
        {/* TAB 3: AUDIT LOGS (LIGHT THEME)                                            */}
        {/* ========================================================================= */}
        {activeTab === 'logs' && (
          <div className="bg-slate-50 border border-slate-200 rounded-2xl p-4 sm:p-5 space-y-3">
            <div className="flex items-center justify-between">
              <h4 className="text-xs font-black text-slate-800 uppercase tracking-wider flex items-center gap-2">
                <span>📋</span> Sentinel Audit Log (Recent System Events)
              </h4>
              <span className="text-[11px] text-slate-500 font-mono">
                {statusData?.recentEvents?.length || 0} events recorded
              </span>
            </div>

            <div className="bg-white rounded-xl border border-slate-200 max-h-96 overflow-y-auto divide-y divide-slate-100 text-xs shadow-2xs">
              {statusData?.recentEvents && statusData.recentEvents.length > 0 ? (
                statusData.recentEvents.map((event) => (
                  <div
                    key={event.id}
                    className="p-3.5 flex items-start gap-3 hover:bg-slate-50 transition"
                  >
                    <span
                      className={`px-2 py-0.5 rounded text-[10px] font-extrabold uppercase tracking-wide shrink-0 ${
                        event.level === 'SUCCESS'
                          ? 'bg-emerald-100 text-emerald-800'
                          : event.level === 'WARN'
                          ? 'bg-amber-100 text-amber-800'
                          : event.level === 'ERROR'
                          ? 'bg-rose-100 text-rose-800'
                          : 'bg-indigo-100 text-indigo-800'
                      }`}
                    >
                      {event.category}
                    </span>
                    <div className="flex-1 space-y-0.5">
                      <p className="text-slate-800 font-medium text-xs leading-snug">{event.message}</p>
                      <span className="text-[10px] text-slate-400 block font-mono">
                        {formatIST(event.timestamp)}
                      </span>
                    </div>
                  </div>
                ))
              ) : (
                <div className="text-center py-10 text-slate-400 text-xs">
                  No Sentinel events recorded in this session.
                </div>
              )}
            </div>
          </div>
        )}

      </div>

      {/* Footer Info Bar */}
      <div className="bg-slate-50 border-t border-slate-200 px-5 py-3 flex flex-wrap items-center justify-between gap-3 text-xs text-slate-500">
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-2">
            <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
            <span>
              Active Open Positions: <strong className="text-slate-900">{statusData?.openPositionsCount || 0}</strong>
            </span>
          </div>
          <span>•</span>
          <div>
            Total Signals Scanned: <strong className="text-slate-900">{totalScanned}</strong>
          </div>
        </div>

        <div className="text-[11px] font-mono text-slate-400">
          Trade Sentinel v2.4 • Session: {signalsData?.sessionDate || 'Today'}
        </div>
      </div>

    </div>
  );
}
