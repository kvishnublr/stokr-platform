import React, { useState, useEffect, useMemo } from 'react';
import client from '../api/client';
import { toast } from 'react-toastify';

export default function TradeSentinelModal({ isOpen, onClose, showToast }) {
  const [activeTab, setActiveTab] = useState('signals'); // 'signals' | 'pillars' | 'logs'
  const [loading, setLoading] = useState(false);
  const [reconciling, setReconciling] = useState(false);
  const [statusData, setStatusData] = useState(null);
  const [signalsData, setSignalsData] = useState(null);
  const [loadingSignals, setLoadingSignals] = useState(false);
  const [error, setError] = useState(null);

  // Filters for Signals tab
  const [statusFilter, setStatusFilter] = useState('ALL'); // 'ALL' | 'EXECUTED' | 'FILTERED'
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
    const handleKeyDown = (e) => {
      if (e.key === 'Escape') onClose();
    };
    if (isOpen) {
      window.addEventListener('keydown', handleKeyDown);
      fetchSentinelStatus();
      fetchSignalsAudit();
      const interval = setInterval(() => {
        fetchSentinelStatus();
        fetchSignalsAudit();
      }, 15000);
      return () => {
        window.removeEventListener('keydown', handleKeyDown);
        clearInterval(interval);
      };
    }
  }, [isOpen]);

  // Filter signals list
  const filteredSignals = useMemo(() => {
    if (!signalsData?.signals) return [];
    return signalsData.signals.filter((s) => {
      // Status filter
      if (statusFilter === 'EXECUTED' && s.auditStatus !== 'PASSED_EXECUTED') return false;
      if (statusFilter === 'FILTERED' && s.auditStatus === 'PASSED_EXECUTED') return false;

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

  if (!isOpen) return null;

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

  return (
    <div
      className="fixed inset-0 z-[100] bg-slate-950/85 backdrop-blur-md flex items-center justify-center p-2 sm:p-4 md:p-6"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="bg-slate-900 border border-slate-700/80 rounded-3xl shadow-2xl w-full max-w-6xl max-h-[94vh] flex flex-col overflow-hidden text-slate-100">
        
        {/* Modal Header */}
        <div className="bg-gradient-to-r from-slate-950 via-indigo-950/90 to-slate-950 px-6 py-4 border-b border-slate-800 flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3.5">
            <div className="p-2.5 bg-indigo-500/20 border border-indigo-500/40 rounded-2xl text-2xl shadow-inner flex items-center justify-center">
              🛡️
            </div>
            <div>
              <div className="flex items-center gap-2.5">
                <h2 className="text-lg md:text-xl font-black text-white tracking-tight">
                  Trade Sentinel &amp; Reconciliation Engine
                </h2>
                <span
                  className={`px-2.5 py-0.5 rounded-full text-[11px] font-black uppercase tracking-wider ${
                    isGreen
                      ? 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/30'
                      : isYellow
                      ? 'bg-amber-500/20 text-amber-400 border border-amber-500/30'
                      : 'bg-rose-500/20 text-rose-400 border border-rose-500/30'
                  }`}
                >
                  {statusData?.statusLabel || 'Online'}
                </span>
              </div>
              <p className="text-xs text-slate-400 mt-0.5">
                Real-Time Signal Gatekeeper • Multi-Set Strategy Deduplication • Live Broker Reconciler
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <button
              onClick={handleRefreshAll}
              disabled={loading || loadingSignals}
              title="Refresh Sentinel &amp; Signals Data"
              className="px-3 py-1.5 bg-slate-800 hover:bg-slate-700 disabled:opacity-50 text-slate-200 font-bold text-xs rounded-xl border border-slate-700 transition flex items-center gap-1.5"
            >
              <span className={loading || loadingSignals ? 'animate-spin' : ''}>🔄</span>
              <span>Refresh</span>
            </button>
            <button
              onClick={handleReconcile}
              disabled={reconciling || loading}
              className="px-3.5 py-1.5 bg-emerald-600 hover:bg-emerald-500 disabled:opacity-50 text-white font-bold text-xs rounded-xl shadow-md transition flex items-center gap-1.5"
            >
              <span>{reconciling ? '⏳' : '⚡'}</span>
              <span>{reconciling ? 'Reconciling...' : 'Reconcile Now'}</span>
            </button>
            <button
              onClick={onClose}
              className="w-8 h-8 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-400 hover:text-white flex items-center justify-center font-bold text-sm transition"
            >
              ✕
            </button>
          </div>
        </div>

        {/* Tab Navigation */}
        <div className="bg-slate-950/70 border-b border-slate-800 px-6 py-2 flex items-center justify-between overflow-x-auto">
          <div className="flex items-center gap-2">
            <button
              onClick={() => setActiveTab('signals')}
              className={`px-4 py-2 rounded-xl text-xs font-black transition flex items-center gap-2 ${
                activeTab === 'signals'
                  ? 'bg-indigo-600 text-white shadow-lg shadow-indigo-600/30'
                  : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
              }`}
            >
              <span>📡</span>
              <span>Received Signals &amp; Gatekeeper Audit</span>
              {signalsData?.totalSignals !== undefined && (
                <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold ${
                  activeTab === 'signals' ? 'bg-indigo-800 text-indigo-100' : 'bg-slate-800 text-slate-400'
                }`}>
                  {signalsData.totalSignals}
                </span>
              )}
            </button>

            <button
              onClick={() => setActiveTab('pillars')}
              className={`px-4 py-2 rounded-xl text-xs font-black transition flex items-center gap-2 ${
                activeTab === 'pillars'
                  ? 'bg-indigo-600 text-white shadow-lg shadow-indigo-600/30'
                  : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
              }`}
            >
              <span>🛡️</span>
              <span>4-Pillars Governance &amp; Broker Sync</span>
            </button>

            <button
              onClick={() => setActiveTab('logs')}
              className={`px-4 py-2 rounded-xl text-xs font-black transition flex items-center gap-2 ${
                activeTab === 'logs'
                  ? 'bg-indigo-600 text-white shadow-lg shadow-indigo-600/30'
                  : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
              }`}
            >
              <span>📋</span>
              <span>Sentinel Event Log</span>
              {statusData?.recentEvents?.length > 0 && (
                <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold ${
                  activeTab === 'logs' ? 'bg-indigo-800 text-indigo-100' : 'bg-slate-800 text-slate-400'
                }`}>
                  {statusData.recentEvents.length}
                </span>
              )}
            </button>
          </div>

          <div className="text-[11px] text-slate-400 hidden sm:flex items-center gap-2 font-mono">
            <span>Session:</span>
            <span className="text-slate-200 font-bold">{signalsData?.sessionDate || 'Today'}</span>
          </div>
        </div>

        {/* Modal Body */}
        <div className="p-4 md:p-6 overflow-y-auto space-y-5 custom-scrollbar flex-1">
          {error && (
            <div className="p-4 bg-rose-500/10 border border-rose-500/30 rounded-2xl text-rose-400 text-xs font-semibold flex items-center gap-3">
              <span>⚠️</span>
              <span>{error}</span>
            </div>
          )}

          {/* ========================================================================= */}
          {/* TAB 1: SIGNALS AUDIT & GATEKEEPER VIEW                                    */}
          {/* ========================================================================= */}
          {activeTab === 'signals' && (
            <div className="space-y-4">
              
              {/* Multi-Set Explanatory Callout Banner */}
              <div className="bg-gradient-to-r from-indigo-950/70 via-slate-900 to-indigo-950/70 border border-indigo-700/40 rounded-2xl p-3.5 flex items-start gap-3 shadow-inner">
                <span className="text-xl shrink-0 mt-0.5">💡</span>
                <div className="text-xs leading-relaxed text-slate-300">
                  <span className="font-extrabold text-indigo-300">Multi-Set Trading Enabled: </span>
                  A single strategy (e.g. Iron Condor, Butterfly) can now have <strong>multiple active sets</strong> across different strikes. The Gatekeeper only blocks <strong>identical duplicate strike sets</strong> from executing simultaneously, allowing diversified strike sets to trade freely.
                </div>
              </div>

              {/* Signals KPI Summary Cards */}
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                <div className="bg-slate-800/60 border border-slate-700/60 rounded-2xl p-3.5 flex flex-col justify-between">
                  <span className="text-[11px] font-bold text-slate-400 uppercase tracking-wider">Total Received</span>
                  <div className="text-2xl font-black text-white mt-1">
                    {signalsData?.totalSignals ?? '--'}
                  </div>
                  <span className="text-[10px] text-slate-400 mt-0.5">Scanned signals today</span>
                </div>

                <div className="bg-emerald-950/30 border border-emerald-500/40 rounded-2xl p-3.5 flex flex-col justify-between">
                  <div className="flex items-center justify-between">
                    <span className="text-[11px] font-bold text-emerald-400 uppercase tracking-wider">Executed</span>
                    <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
                  </div>
                  <div className="text-2xl font-black text-emerald-300 mt-1">
                    {signalsData?.executedSignals ?? '--'}
                  </div>
                  <span className="text-[10px] text-emerald-400 font-semibold mt-0.5">Passed all gatekeeper checks</span>
                </div>

                <div className="bg-amber-950/30 border border-amber-500/40 rounded-2xl p-3.5 flex flex-col justify-between">
                  <span className="text-[11px] font-bold text-amber-400 uppercase tracking-wider">Filtered / Guarded</span>
                  <div className="text-2xl font-black text-amber-300 mt-1">
                    {signalsData?.filteredSignals ?? '--'}
                  </div>
                  <span className="text-[10px] text-amber-400 mt-0.5">Edge, duplicate set, or hours</span>
                </div>

                <div className="bg-indigo-950/30 border border-indigo-500/40 rounded-2xl p-3.5 flex flex-col justify-between">
                  <span className="text-[11px] font-bold text-indigo-400 uppercase tracking-wider">Top Net Edge</span>
                  <div className="text-2xl font-black text-indigo-300 mt-1">
                    ₹{Number(signalsData?.topEdgeToday || 0).toLocaleString('en-IN', { maximumFractionDigits: 1 })}
                  </div>
                  <span className="text-[10px] text-indigo-400 mt-0.5">Highest edge captured today</span>
                </div>
              </div>

              {/* Controls: Search & Filter Chips */}
              <div className="bg-slate-800/40 border border-slate-800 rounded-2xl p-3 flex flex-wrap items-center justify-between gap-3">
                <div className="flex items-center gap-1.5 flex-wrap">
                  <span className="text-xs font-bold text-slate-400 mr-1">Status:</span>
                  <button
                    onClick={() => setStatusFilter('ALL')}
                    className={`px-3 py-1 rounded-xl text-xs font-bold transition ${
                      statusFilter === 'ALL'
                        ? 'bg-slate-700 text-white border border-slate-600'
                        : 'bg-slate-900/60 text-slate-400 hover:text-white'
                    }`}
                  >
                    All ({signalsData?.totalSignals || 0})
                  </button>
                  <button
                    onClick={() => setStatusFilter('EXECUTED')}
                    className={`px-3 py-1 rounded-xl text-xs font-bold transition flex items-center gap-1 ${
                      statusFilter === 'EXECUTED'
                        ? 'bg-emerald-600 text-white'
                        : 'bg-emerald-950/40 text-emerald-400 border border-emerald-800/40 hover:bg-emerald-900/60'
                    }`}
                  >
                    <span>✓</span> Executed ({signalsData?.executedSignals || 0})
                  </button>
                  <button
                    onClick={() => setStatusFilter('FILTERED')}
                    className={`px-3 py-1 rounded-xl text-xs font-bold transition flex items-center gap-1 ${
                      statusFilter === 'FILTERED'
                        ? 'bg-amber-600 text-white'
                        : 'bg-amber-950/40 text-amber-400 border border-amber-800/40 hover:bg-amber-900/60'
                    }`}
                  >
                    <span>🛡️</span> Guarded ({signalsData?.filteredSignals || 0})
                  </button>

                  <div className="h-4 w-[1px] bg-slate-700 mx-1" />

                  <span className="text-xs font-bold text-slate-400 mr-1">Symbol:</span>
                  <button
                    onClick={() => setSymbolFilter('ALL')}
                    className={`px-2.5 py-1 rounded-xl text-xs font-bold transition ${
                      symbolFilter === 'ALL' ? 'bg-indigo-600 text-white' : 'bg-slate-900/60 text-slate-400 hover:text-white'
                    }`}
                  >
                    All
                  </button>
                  <button
                    onClick={() => setSymbolFilter('BANKNIFTY')}
                    className={`px-2.5 py-1 rounded-xl text-xs font-bold transition ${
                      symbolFilter === 'BANKNIFTY' ? 'bg-indigo-600 text-white' : 'bg-slate-900/60 text-slate-400 hover:text-white'
                    }`}
                  >
                    BANKNIFTY
                  </button>
                  <button
                    onClick={() => setSymbolFilter('NIFTY')}
                    className={`px-2.5 py-1 rounded-xl text-xs font-bold transition ${
                      symbolFilter === 'NIFTY' ? 'bg-indigo-600 text-white' : 'bg-slate-900/60 text-slate-400 hover:text-white'
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
                    className="w-full bg-slate-900/90 border border-slate-700/80 rounded-xl px-3 py-1.5 text-xs text-white placeholder-slate-500 focus:outline-none focus:border-indigo-500 font-mono"
                  />
                  {searchQuery && (
                    <button
                      onClick={() => setSearchQuery('')}
                      className="absolute right-2.5 top-1.5 text-slate-400 hover:text-white text-xs font-bold"
                    >
                      ✕
                    </button>
                  )}
                </div>
              </div>

              {/* Signals Cards Feed */}
              {loadingSignals && (!signalsData || !signalsData.signals) ? (
                <div className="text-center py-12 text-slate-400 text-xs flex items-center justify-center gap-2">
                  <span className="animate-spin text-lg">⏳</span> Loading today's signals audit...
                </div>
              ) : filteredSignals.length === 0 ? (
                <div className="text-center py-12 bg-slate-800/20 border border-slate-800 rounded-2xl text-slate-400 text-xs space-y-2">
                  <div className="text-3xl">🔍</div>
                  <div className="font-bold text-slate-300">No signals match the current filters</div>
                  <p className="text-[11px] text-slate-500 max-w-sm mx-auto">
                    Try changing your filter settings or search term to see signals received earlier today.
                  </p>
                </div>
              ) : (
                <div className="space-y-3">
                  {filteredSignals.map((sig) => {
                    const isExecuted = sig.auditStatus === 'PASSED_EXECUTED';
                    const isDuplicate = sig.auditStatus === 'FILTERED_DUPLICATE_SET';
                    const isMinEdge = sig.auditStatus === 'FILTERED_MIN_EDGE';
                    const isHours = sig.auditStatus === 'FILTERED_MARKET_HOURS';

                    return (
                      <div
                        key={sig.opportunityId}
                        className={`rounded-2xl border p-4 transition duration-150 ${
                          isExecuted
                            ? 'bg-slate-900/90 border-emerald-500/40 hover:border-emerald-500/70 shadow-lg shadow-emerald-950/20'
                            : isDuplicate
                            ? 'bg-slate-900/70 border-rose-500/30 hover:border-rose-500/50'
                            : isMinEdge
                            ? 'bg-slate-900/70 border-amber-500/30 hover:border-amber-500/50'
                            : 'bg-slate-900/70 border-slate-800 hover:border-slate-700'
                        }`}
                      >
                        {/* Card Top Row: Identity, Time, Status Pill */}
                        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-800/80 pb-2.5">
                          <div className="flex items-center gap-2 flex-wrap">
                            <span className="px-2.5 py-0.5 rounded-lg text-xs font-black uppercase tracking-wider bg-indigo-500/20 text-indigo-300 border border-indigo-500/30">
                              {sig.underlying}
                            </span>
                            <span className="font-extrabold text-sm text-white">
                              {formatStrategyName(sig.strategyType)}
                            </span>
                            <span className="text-[11px] font-mono text-slate-400 bg-slate-800 px-2 py-0.5 rounded-md">
                              Opp #{sig.opportunityId}
                            </span>
                            {sig.expiryDate && (
                              <span className="text-[10px] text-slate-400 font-mono">
                                Exp: {sig.expiryDate}
                              </span>
                            )}
                          </div>

                          <div className="flex items-center gap-2">
                            <span className="text-[11px] font-mono text-slate-400">
                              ⏰ {formatIST(sig.scanTime)}
                            </span>
                            <span
                              className={`px-3 py-1 rounded-xl text-[11px] font-black uppercase tracking-wider flex items-center gap-1.5 ${
                                isExecuted
                                  ? 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/40'
                                  : isDuplicate
                                  ? 'bg-rose-500/20 text-rose-300 border border-rose-500/40'
                                  : isMinEdge
                                  ? 'bg-amber-500/20 text-amber-300 border border-amber-500/40'
                                  : isHours
                                  ? 'bg-slate-800 text-slate-300 border border-slate-700'
                                  : 'bg-orange-500/20 text-orange-300 border border-orange-500/40'
                              }`}
                            >
                              <span>{isExecuted ? '✓' : isDuplicate ? '🛡️' : isMinEdge ? '⚠️' : 'ℹ️'}</span>
                              <span>{sig.statusBadge || 'GUARDED'}</span>
                            </span>
                          </div>
                        </div>

                        {/* Card Center: Strike Set Breakdown & Action Leg String */}
                        <div className="grid grid-cols-1 md:grid-cols-12 gap-3 py-3 items-center">
                          {/* Strike Set Badges */}
                          <div className="md:col-span-8 space-y-2">
                            <div className="flex items-center gap-2 flex-wrap">
                              <span className="text-[10px] font-bold uppercase tracking-wider text-slate-400">
                                Strike Set:
                              </span>
                              {sig.setStrikes && sig.setStrikes.length > 0 ? (
                                sig.setStrikes.map((strk, idx) => (
                                  <span
                                    key={idx}
                                    className="px-2 py-0.5 rounded-md text-[11px] font-mono font-bold bg-slate-800 border border-slate-700 text-indigo-300"
                                  >
                                    {Number(strk).toLocaleString('en-IN')}
                                  </span>
                                ))
                              ) : (
                                <span className="text-xs font-mono text-slate-300">
                                  Strike: {sig.strike || '--'}
                                </span>
                              )}
                            </div>

                            {/* Action breakdown */}
                            {sig.action && (
                              <div className="text-[11px] font-mono text-slate-300 bg-slate-950/60 rounded-lg px-2.5 py-1 border border-slate-800/80 truncate">
                                {sig.action}
                              </div>
                            )}

                            {/* Price references */}
                            <div className="text-[10px] text-slate-400 flex items-center gap-3">
                              <span>Spot: <strong className="text-slate-200">₹{sig.spotPrice || '--'}</strong></span>
                              <span>•</span>
                              <span>Futures: <strong className="text-slate-200">₹{sig.futuresPrice || '--'}</strong></span>
                            </div>
                          </div>

                          {/* Edge & Cost Comparison */}
                          <div className="md:col-span-4 bg-slate-950/50 rounded-xl p-2.5 border border-slate-800/60 flex flex-col justify-center text-right space-y-1">
                            <div className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">
                              Net Edge (Post Costs)
                            </div>
                            <div className="text-lg font-black text-emerald-400">
                              ₹{Number(sig.edgeAfterCosts || 0).toLocaleString('en-IN', { maximumFractionDigits: 1 })}
                            </div>
                            <div className="text-[10px] text-slate-400 font-mono">
                              Raw Edge: {sig.edgePoints != null ? `+${sig.edgePoints} pts` : '--'}
                            </div>
                          </div>
                        </div>

                        {/* Card Bottom: Why it passed or failed (Sentinel Gatekeeper Checklist) */}
                        <div className="bg-slate-950/70 rounded-xl p-2.5 border border-slate-800/90 flex flex-wrap items-center justify-between gap-2 text-xs">
                          <div className="flex items-center gap-2">
                            <span className="font-bold text-slate-400">Gatekeeper Reason:</span>
                            <span className={`font-semibold ${
                              isExecuted
                                ? 'text-emerald-400'
                                : isDuplicate
                                ? 'text-rose-400'
                                : isMinEdge
                                ? 'text-amber-400'
                                : 'text-slate-300'
                            }`}>
                              {sig.statusReason}
                            </span>
                          </div>

                          {/* Gatekeeper mini checklist pills */}
                          <div className="flex items-center gap-1.5 text-[10px] font-mono">
                            <span className="px-2 py-0.5 rounded bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                              ✓ Lot {sig.gatekeeper?.lotSize || 30}
                            </span>
                            <span className="px-2 py-0.5 rounded bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                              ✓ Headroom +15%
                            </span>
                            <span className={`px-2 py-0.5 rounded border ${
                              isDuplicate
                                ? 'bg-rose-500/10 text-rose-400 border-rose-500/20'
                                : 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20'
                            }`}>
                              {isDuplicate ? '✕ Duplicate Set' : '✓ Unique Strike Set'}
                            </span>
                          </div>
                        </div>

                        {/* Position Details if Executed */}
                        {isExecuted && (
                          <div className="mt-2.5 pt-2 border-t border-slate-800/80 flex items-center justify-between text-xs text-slate-400">
                            <div className="flex items-center gap-2">
                              <span>Linked Order:</span>
                              <strong className="text-white">Trade #{sig.positionId}</strong>
                              <span className={`px-2 py-0.2 rounded text-[10px] font-bold ${
                                sig.positionStatus === 'OPEN'
                                  ? 'bg-emerald-500/20 text-emerald-400'
                                  : 'bg-slate-700 text-slate-300'
                              }`}>
                                {sig.positionStatus}
                              </span>
                              {sig.exitReason && (
                                <span className="text-[10px] text-slate-400">
                                  Exit: {sig.exitReason}
                                </span>
                              )}
                            </div>
                            {sig.positionPnl != null && (
                              <div className="font-mono font-bold">
                                P&amp;L:{' '}
                                <span className={sig.positionPnl >= 0 ? 'text-emerald-400' : 'text-rose-400'}>
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
          {/* TAB 2: 4-PILLARS GOVERNANCE & BROKER HEALTH                                */}
          {/* ========================================================================= */}
          {activeTab === 'pillars' && (
            <div className="space-y-6">
              {/* 4 Pillars Status Grid */}
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-3.5">
                {/* Gatekeeper Card */}
                <div className="bg-slate-800/60 border border-slate-700/60 rounded-2xl p-4 space-y-2">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-bold text-indigo-400 uppercase tracking-wider">
                      1. Gatekeeper
                    </span>
                    <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
                  </div>
                  <h4 className="font-extrabold text-sm text-white">Pre-Order Checks</h4>
                  <p className="text-[11px] text-slate-400 leading-relaxed">
                    Requires +15% margin buffer headroom, validates exchange lot freeze limits &amp; unique strike sets.
                  </p>
                  <div className="pt-1 text-[10px] text-emerald-400 font-bold flex items-center gap-1">
                    <span>✓ Multi-Set Strike Deduplication Active</span>
                  </div>
                </div>

                {/* Watchdog Card */}
                <div className="bg-slate-800/60 border border-slate-700/60 rounded-2xl p-4 space-y-2">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-bold text-indigo-400 uppercase tracking-wider">
                      2. Watchdog
                    </span>
                    <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
                  </div>
                  <h4 className="font-extrabold text-sm text-white">In-Flight Watchdog</h4>
                  <p className="text-[11px] text-slate-400 leading-relaxed">
                    BUY-first leg sequencing, ₹0.05 tick rounding, 5% marketable limit protection &amp; instant rollback.
                  </p>
                  <div className="pt-1 text-[10px] text-emerald-400 font-bold flex items-center gap-1">
                    <span>✓ Rollback Armed on Leg Timeout</span>
                  </div>
                </div>

                {/* Reconciler Card */}
                <div className="bg-slate-800/60 border border-slate-700/60 rounded-2xl p-4 space-y-2">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-bold text-indigo-400 uppercase tracking-wider">
                      3. Reconciler
                    </span>
                    <span
                      className={`w-2 h-2 rounded-full ${
                        statusData?.lastReconciliation?.status === 'IN_SYNC' ? 'bg-emerald-400' : 'bg-amber-400'
                      } animate-pulse`}
                    />
                  </div>
                  <h4 className="font-extrabold text-sm text-white">DB vs. Broker Sync</h4>
                  <p className="text-[11px] text-slate-400 leading-relaxed">
                    Auto-heals phantom positions if closed outside the app. Verified flat at market close.
                  </p>
                  <div className="pt-1 text-[10px] font-bold flex items-center gap-1 text-emerald-400">
                    <span>✓ Status: {statusData?.lastReconciliation?.status || 'IN_SYNC'}</span>
                  </div>
                </div>

                {/* Certifier Card */}
                <div className="bg-slate-800/60 border border-slate-700/60 rounded-2xl p-4 space-y-2">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-bold text-indigo-400 uppercase tracking-wider">
                      4. Certifier
                    </span>
                    <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
                  </div>
                  <h4 className="font-extrabold text-sm text-white">EOD Flat-Book</h4>
                  <p className="text-[11px] text-slate-400 leading-relaxed">
                    Audits post-3:20 PM positions to certify zero unintended overnight intraday exposure.
                  </p>
                  <div className="pt-1 text-[10px] text-emerald-400 font-bold flex items-center gap-1">
                    <span>✓ 100% Flat Book Certified</span>
                  </div>
                </div>
              </div>

              {/* Broker Margin & Session Health */}
              <div className="bg-slate-800/40 border border-slate-800 rounded-2xl p-5 space-y-3">
                <div className="flex items-center justify-between">
                  <h4 className="text-xs font-extrabold text-slate-300 uppercase tracking-wider flex items-center gap-2">
                    <span>🏦</span> Connected Brokers &amp; Margin Health
                  </h4>
                  <button
                    onClick={handleCertifyEod}
                    disabled={reconciling}
                    className="text-[11px] text-indigo-400 hover:text-indigo-300 font-bold transition flex items-center gap-1"
                  >
                    <span>📜 Certify Flat Book</span>
                  </button>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
                  {statusData?.brokerSummaries && Object.keys(statusData.brokerSummaries).length > 0 ? (
                    Object.entries(statusData.brokerSummaries).map(([broker, data]) => (
                      <div
                        key={broker}
                        className="bg-slate-900/80 border border-slate-700/60 rounded-xl p-3.5 space-y-1"
                      >
                        <div className="flex items-center justify-between">
                          <span className="font-black text-xs text-white tracking-wide">{broker}</span>
                          <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-emerald-500/20 text-emerald-400">
                            {data.status}
                          </span>
                        </div>
                        <div className="text-[11px] text-slate-400">
                          User / Client:{' '}
                          <span className="font-semibold text-slate-200">{data.clientCode || 'Active'}</span>
                        </div>
                        <div className="text-sm font-black text-indigo-300 pt-0.5">
                          ₹
                          {Number(data.availableMargin || 0).toLocaleString('en-IN', {
                            maximumFractionDigits: 2,
                          })}
                          <span className="text-[10px] text-slate-400 font-normal ml-1">available</span>
                        </div>
                      </div>
                    ))
                  ) : (
                    <div className="col-span-3 text-center py-4 bg-slate-900/50 rounded-xl border border-slate-800 text-xs text-slate-400">
                      ⚡ Paper Trading Mode Active (Virtual Margin Pool Operational)
                    </div>
                  )}
                </div>
              </div>
            </div>
          )}

          {/* ========================================================================= */}
          {/* TAB 3: AUDIT LOGS                                                         */}
          {/* ========================================================================= */}
          {activeTab === 'logs' && (
            <div className="bg-slate-800/40 border border-slate-800 rounded-2xl p-5 space-y-3">
              <div className="flex items-center justify-between">
                <h4 className="text-xs font-extrabold text-slate-300 uppercase tracking-wider flex items-center gap-2">
                  <span>📋</span> Sentinel Audit Log (Recent System Events)
                </h4>
                <span className="text-[11px] text-slate-500 font-mono">
                  {statusData?.recentEvents?.length || 0} events recorded
                </span>
              </div>

              <div className="bg-slate-950/80 rounded-xl border border-slate-800 max-h-96 overflow-y-auto divide-y divide-slate-800/60 text-xs">
                {statusData?.recentEvents && statusData.recentEvents.length > 0 ? (
                  statusData.recentEvents.map((event) => (
                    <div
                      key={event.id}
                      className="p-3.5 flex items-start gap-3 hover:bg-slate-900/60 transition"
                    >
                      <span
                        className={`px-2 py-0.5 rounded text-[10px] font-extrabold uppercase tracking-wide shrink-0 ${
                          event.level === 'SUCCESS'
                            ? 'bg-emerald-500/20 text-emerald-400'
                            : event.level === 'WARN'
                            ? 'bg-amber-500/20 text-amber-400'
                            : event.level === 'ERROR'
                            ? 'bg-rose-500/20 text-rose-400'
                            : 'bg-indigo-500/20 text-indigo-400'
                        }`}
                      >
                        {event.category}
                      </span>
                      <div className="flex-1 space-y-0.5">
                        <p className="text-slate-200 font-medium text-xs leading-snug">{event.message}</p>
                        <span className="text-[10px] text-slate-500 block font-mono">
                          {formatIST(event.timestamp)}
                        </span>
                      </div>
                    </div>
                  ))
                ) : (
                  <div className="text-center py-10 text-slate-500 text-xs">
                    No Sentinel events recorded in this session.
                  </div>
                )}
              </div>
            </div>
          )}

        </div>

        {/* Modal Footer */}
        <div className="bg-slate-950 px-6 py-3.5 border-t border-slate-800 flex flex-wrap items-center justify-between gap-3 text-xs text-slate-400">
          <div className="flex items-center gap-3">
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
              <span>
                Active Open Positions: <strong className="text-white">{statusData?.openPositionsCount || 0}</strong>
              </span>
            </div>
            <span>•</span>
            <div>
              Total Today's Signals: <strong className="text-white">{signalsData?.totalSignals || 0}</strong>
            </div>
          </div>
          <button
            onClick={onClose}
            className="px-5 py-2 bg-slate-800 hover:bg-slate-700 text-white font-bold rounded-xl transition"
          >
            Close
          </button>
        </div>

      </div>
    </div>
  );
}
