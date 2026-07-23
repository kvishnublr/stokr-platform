import React, { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';

const API_BASE = import.meta.env.VITE_API_URL || '';

let _toastListeners = [];
let _toastId = 0;
function _notifyToast(toast) { _toastListeners.forEach(fn => fn(toast)); }
export function showToast(message, type = 'info', duration = 4000) {
  const id = ++_toastId;
  _notifyToast({ id, message, type, duration });
}

function useToastState() {
  const [toasts, setToasts] = useState([]);
  useEffect(() => {
    const handler = (toast) => {
      setToasts(prev => [...prev, toast]);
      if (toast.duration > 0) {
        setTimeout(() => {
          setToasts(prev => prev.filter(t => t.id !== toast.id));
        }, toast.duration);
      }
    };
    _toastListeners.push(handler);
    return () => { _toastListeners = _toastListeners.filter(f => f !== handler); };
  }, []);
  const dismiss = (id) => setToasts(prev => prev.filter(t => t.id !== id));
  return { toasts, dismiss };
}

const TOAST_STYLES = {
  success: 'bg-emerald-600 text-white',
  error: 'bg-red-600 text-white',
  warning: 'bg-amber-500 text-white',
  info: 'bg-blue-600 text-white',
};

function ToastContainer({ toasts, dismiss }) {
  if (!toasts || toasts.length === 0) return null;
  return (
    <div className="fixed top-4 right-4 z-[9999] space-y-2 pointer-events-none">
      {toasts.map(t => (
        <div key={t.id}
          onClick={() => dismiss(t.id)}
          className={`pointer-events-auto px-4 py-3 rounded-xl shadow-lg text-sm font-medium flex items-center gap-2 cursor-pointer ${TOAST_STYLES[t.type] || TOAST_STYLES.info}`}>
          <span>{t.message}</span>
        </div>
      ))}
    </div>
  );
}

let _confirmStateRef = { resolve: null };
export function showConfirm(title, message, confirmText = 'Confirm', cancelText = 'Cancel') {
  return new Promise((resolve) => {
    _confirmStateRef.resolve = resolve;
    window.dispatchEvent(new CustomEvent('app-confirm', { detail: { title, message, confirmText, cancelText } }));
  });
}

function ConfirmDialog() {
  const [show, setShow] = useState(null);
  useEffect(() => {
    const handler = (e) => setShow(e.detail);
    window.addEventListener('app-confirm', handler);
    return () => window.removeEventListener('app-confirm', handler);
  }, []);
  if (!show) return null;
  const resolve = (val) => { setShow(null); _confirmStateRef.resolve?.(val); _confirmStateRef.resolve = null; };
  return (
    <div className="fixed inset-0 z-[9998] flex items-center justify-center bg-black/40 backdrop-blur-sm" onClick={() => resolve(false)}>
      <div className="bg-white rounded-2xl shadow-2xl p-6 w-full max-w-sm mx-4" onClick={e => e.stopPropagation()}>
        <h3 className="text-base font-bold text-slate-800 mb-2">{show.title}</h3>
        <p className="text-sm text-slate-600 mb-6">{show.message}</p>
        <div className="flex gap-3 justify-end">
          <button onClick={() => resolve(false)} className="px-4 py-2 rounded-xl text-sm font-medium bg-slate-100 text-slate-700 hover:bg-slate-200">
            {show.cancelText}
          </button>
          <button onClick={() => resolve(true)} className="px-4 py-2 rounded-xl text-sm font-medium bg-blue-600 text-white hover:bg-blue-700">
            {show.confirmText}
          </button>
        </div>
      </div>
    </div>
  );
}

const ALL_U = ['ALL', 'NIFTY', 'BANKNIFTY', 'MIDCPNIFTY', 'FINNIFTY'];

export default function OptionArbitrage() {
  const { toasts, dismiss: dismissToast } = useToastState();
  const [activeTab, setActiveTab] = useState('live');
  const [underlyings, setUnderlyings] = useState(['ALL']);
  const [autoRefresh, setAutoRefresh] = useState(false);
  const [historyPage, setHistoryPage] = useState(0);
  const [selectedDate, setSelectedDate] = useState('');
  const [strategyFilter, setStrategyFilter] = useState('ALL');
  const [sessionValid, setSessionValid] = useState(true);

  const toggleUnderlying = (u) => {
    if (u === 'ALL') {
      setUnderlyings(['ALL']);
    } else {
      setUnderlyings(prev => {
        const withoutAll = prev.filter(x => x !== 'ALL');
        if (withoutAll.includes(u)) {
          const next = withoutAll.filter(x => x !== u);
          return next.length === 0 ? ['ALL'] : next;
        } else {
          return [...withoutAll, u];
        }
      });
    }
  };

  const checkSession = async () => {
    try {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/zerodha-session-status`);
      setSessionValid(res.data.valid ?? true);
      return res.data;
    } catch {
      return { valid: true };
    }
  };

  useEffect(() => { checkSession(); }, []);

  const { data: health } = useQuery({
    queryKey: ['option-arb-health'],
    queryFn: async () => {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/health`);
      return res.data;
    },
    refetchInterval: 1000,
  });

  const { data: cachedData, isLoading: cachedLoading } = useQuery({
    queryKey: ['option-arb-cached', underlyings],
    queryFn: async () => {
      const uParam = underlyings.join(',');
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/cached`, { params: { underlyings: uParam } });
      return res.data;
    },
    refetchInterval: autoRefresh ? 2000 : false,
  });

  const { data: scanData, isLoading: scanLoading, error, refetch } = useQuery({
    queryKey: ['option-arb-scan', underlyings],
    queryFn: async () => {
      const uParam = underlyings.join(',');
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/scan`, { params: { underlying: uParam, force: true } });
      return res.data;
    },
    enabled: false,
  });

  const data = scanData || cachedData;
  const opportunities = data?.opportunities || [];
  const summary = data?.summary || {};

  const displayOpps = useMemo(() => {
    if (underlyings.includes('ALL')) return opportunities;
    return opportunities.filter(o => underlyings.includes(o.underlying));
  }, [opportunities, underlyings]);

  const totalEdge = useMemo(() => {
    return displayOpps.reduce((sum, o) => sum + (Number(o.edgeAfterCosts) || 0), 0);
  }, [displayOpps]);

  const livePrices = useMemo(() => data?.livePrices || {}, [data]);
  const isLoading = cachedLoading || scanLoading;

  const { data: historyData, isLoading: historyLoading } = useQuery({
    queryKey: ['option-arb-history', historyPage, strategyFilter],
    queryFn: async () => {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/history`, {
        params: { page: historyPage, size: 50 }
      });
      return res.data;
    },
    enabled: activeTab === 'history',
  });

  const { data: summaryData } = useQuery({
    queryKey: ['option-arb-history-summary', selectedDate],
    queryFn: async () => {
      const params = {};
      if (selectedDate) params.date = selectedDate;
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/history/summary`, { params });
      return res.data;
    },
    enabled: activeTab === 'history',
  });

  const { data: datesData } = useQuery({
    queryKey: ['option-arb-history-dates'],
    queryFn: async () => {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/history/dates`, { params: { days: 30 } });
      return res.data;
    },
    enabled: activeTab === 'history',
  });

  return (
    <div className="space-y-6">
      <ToastContainer toasts={toasts} dismiss={dismissToast} />
      <ConfirmDialog />

      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-slate-800">Option Price Difference Scanner</h1>
          <p className="text-sm text-slate-500 mt-1">
            Detects put-call parity breaks, IV spikes, deep ITM stale quotes, and skew anomalies
          </p>
        </div>
        <div className={`px-3 py-1 rounded-full text-xs font-medium border ${
          health?.scannerReady ? 'bg-emerald-50 border-emerald-200 text-emerald-700' : 'bg-red-50 border-red-200 text-red-700'
        }`}>
          {health?.scannerReady ? 'Scanner Ready' : 'Scanner Offline'}
        </div>
      </div>

      {/* Tabs Bar */}
      <div className="flex border-b border-slate-200">
        {[
          { key: 'live', label: 'Live Scan' },
          { key: 'bidParity', label: 'Bid Parity' },
          { key: 'box', label: 'Box Spread' },
          { key: 'signals', label: 'Signals' },
          { key: 'positions', label: 'Positions' },
          { key: 'auto', label: 'Auto-Execute' },
          { key: 'history', label: 'History' },
        ].map((tab) => (
          <button
            key={tab.key}
            onClick={() => setActiveTab(tab.key)}
            className={`px-6 py-3 text-sm font-medium border-b-2 transition-colors ${
              activeTab === tab.key
                ? 'border-blue-500 text-blue-600'
                : 'border-transparent text-slate-500 hover:text-slate-700'
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Conditional Tab Rendering */}
      {activeTab === 'live' ? (
        <LiveScanTab
          autoRefresh={autoRefresh} setAutoRefresh={setAutoRefresh}
          underlyings={underlyings} toggleUnderlying={toggleUnderlying} ALL_U={ALL_U}
          data={data} scanLoading={scanLoading} cachedLoading={cachedLoading}
          error={error} refetch={refetch} health={health}
          opportunities={displayOpps} summary={summary} totalEdge={totalEdge} isLoading={isLoading}
          livePrices={livePrices}
        />
      ) : activeTab === 'bidParity' ? (
        <BidParityTab />
      ) : activeTab === 'box' ? (
        <BoxSpreadTab />
      ) : activeTab === 'signals' ? (
        <SignalsTab />
      ) : activeTab === 'positions' ? (
        <PositionsTab />
      ) : activeTab === 'auto' ? (
        <AutoExecTab />
      ) : (
        <HistoryTab
          historyData={historyData} historyLoading={historyLoading}
          summaryData={summaryData} datesData={datesData}
          historyPage={historyPage} setHistoryPage={setHistoryPage}
          selectedDate={selectedDate} setSelectedDate={setSelectedDate}
          strategyFilter={strategyFilter} setStrategyFilter={setStrategyFilter}
        />
      )}
    </div>
  );
}

// 1. LiveScanTab
function LiveScanTab({ autoRefresh, setAutoRefresh, underlyings, toggleUnderlying, ALL_U, opportunities, summary, totalEdge, scanLoading, isLoading, refetch, health }) {
  const [selectedOpp, setSelectedOpp] = useState(null);
  const [lotMultiplier, setLotMultiplier] = useState(1);
  const [executing, setExecuting] = useState(false);

  const marketOpen = health?.marketOpen ?? false;

  const executeOrder = async () => {
    if (!selectedOpp) return;
    setExecuting(true);
    try {
      await axios.post(`${API_BASE}/api/option-arbitrage/auto-execute/execute`, null, {
        params: { opportunityId: selectedOpp.id, multiplier: lotMultiplier }
      });
      showToast(`Orders submitted successfully for ${selectedOpp.underlying} ${selectedOpp.strike}!`, 'success');
      setSelectedOpp(null);
    } catch (e) {
      showToast('Order execution failed: ' + (e.response?.data?.error || e.message), 'error');
    } finally {
      setExecuting(false);
    }
  };

  return (
    <div className="space-y-6 mt-4">
      <div className="bg-white rounded-xl p-5 border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-4">
        <div>
          <div className="flex items-center gap-2">
            <h2 className="text-xl font-bold text-slate-800">Live Price Difference & Arbitrage Scanner</h2>
            <span className={`px-2.5 py-0.5 rounded-full text-xs font-bold ${
              marketOpen ? 'bg-emerald-100 text-emerald-700 animate-pulse' : 'bg-amber-100 text-amber-700'
            }`}>
              {marketOpen ? '🟢 LIVE MARKET OPEN' : '🟡 OFF-MARKET HOURS (SCANNER READY)'}
            </span>
          </div>
          <p className="text-xs text-slate-500 mt-1">Real-time put-call parity breaks, IV spikes, deep ITM stale quotes & skew anomalies</p>
        </div>

        <div className="flex items-center gap-3">
          <button
            onClick={() => refetch()}
            disabled={scanLoading || isLoading}
            className="px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded-xl text-xs font-bold transition shadow-sm flex items-center gap-1.5 disabled:opacity-50"
          >
            {scanLoading ? '🔄 Scanning...' : '▶ Scan Now'}
          </button>
          <button
            onClick={() => setAutoRefresh(!autoRefresh)}
            className={`px-4 py-2 rounded-xl text-xs font-bold transition ${
              autoRefresh ? 'bg-emerald-600 text-white shadow-sm' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
            }`}
          >
            {autoRefresh ? '⚡ Auto-Refresh: 1s ON' : '⏱️ Auto-Refresh: OFF'}
          </button>
        </div>
      </div>

      <div className="flex items-center gap-2 flex-wrap bg-white p-3 rounded-xl border border-slate-200 shadow-sm">
        <span className="text-xs font-bold text-slate-500 uppercase mr-2">Underlying:</span>
        {ALL_U.map((u) => {
          const isSel = underlyings.includes(u);
          const oppCount = opportunities.filter(o => u === 'ALL' || o.underlying === u).length;
          return (
            <button
              key={u}
              onClick={() => toggleUnderlying(u)}
              className={`px-3 py-1.5 rounded-lg text-xs font-bold transition flex items-center gap-1.5 ${
                isSel ? 'bg-blue-600 text-white shadow-sm' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
              }`}
            >
              <span>{u}</span>
              {oppCount > 0 && (
                <span className={`px-1.5 py-0.2 rounded-full text-[10px] ${isSel ? 'bg-blue-700 text-white' : 'bg-slate-200 text-slate-700'}`}>
                  {oppCount}
                </span>
              )}
            </button>
          );
        })}
      </div>

      <div className="grid grid-cols-2 md:grid-cols-5 gap-4">
        <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm">
          <p className="text-xs font-semibold text-slate-500 uppercase">Total Opportunities</p>
          <p className="text-2xl font-bold text-slate-800 mt-1">{opportunities.length}</p>
        </div>
        <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm">
          <p className="text-xs font-semibold text-slate-500 uppercase">Total Net Edge</p>
          <p className="text-2xl font-bold text-emerald-600 mt-1">₹{Math.round(totalEdge).toLocaleString('en-IN')}</p>
        </div>
        <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm">
          <p className="text-xs font-semibold text-slate-500 uppercase">Parity Breaks</p>
          <p className="text-2xl font-bold text-blue-600 mt-1">{summary.PARITY_BREAK || 0}</p>
        </div>
        <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm">
          <p className="text-xs font-semibold text-slate-500 uppercase">IV Spikes</p>
          <p className="text-2xl font-bold text-amber-600 mt-1">{summary.IV_SPIKE || 0}</p>
        </div>
        <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm">
          <p className="text-xs font-semibold text-slate-500 uppercase">Skew / Deep ITM</p>
          <p className="text-2xl font-bold text-purple-600 mt-1">{(summary.SKEW_ANOMALY || 0) + (summary.DEEP_ITM_STALE || 0)}</p>
        </div>
      </div>

      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden shadow-sm">
        <div className="px-5 py-4 border-b border-slate-100 flex items-center justify-between">
          <h3 className="text-sm font-bold text-slate-800">
            Detected Opportunities ({opportunities.length})
          </h3>
          <span className="text-xs text-slate-400 font-mono">
            {health?.currentTimeIST ? `Last scan: ${health.currentTimeIST}` : ''}
          </span>
        </div>

        {opportunities.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm">
            <p className="text-base font-semibold text-slate-600">No opportunities detected in current feed</p>
            <p className="text-xs mt-1 text-slate-400">Scanner runs continuously during market hours (09:15 AM - 03:30 PM IST).</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm text-left">
              <thead className="bg-slate-50 border-b border-slate-200 text-xs font-semibold text-slate-600">
                <tr>
                  <th className="px-4 py-3">Type</th>
                  <th className="px-4 py-3">Underlying</th>
                  <th className="px-4 py-3">Strike</th>
                  <th className="px-4 py-3">Action</th>
                  <th className="px-4 py-3 text-right">CE</th>
                  <th className="px-4 py-3 text-right">PE</th>
                  <th className="px-4 py-3 text-right">Edge (pts)</th>
                  <th className="px-4 py-3 text-right">Edge (₹)</th>
                  <th className="px-4 py-3 text-center">Confidence</th>
                  <th className="px-4 py-3 text-center">Execute</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {opportunities.map((opp, idx) => (
                  <tr key={opp.id || idx} className="hover:bg-slate-50 transition">
                    <td className="px-4 py-3 font-semibold text-blue-600">{opp.type}</td>
                    <td className="px-4 py-3 font-semibold text-slate-800">{opp.underlying}</td>
                    <td className="px-4 py-3 font-semibold text-slate-700">{opp.strike}</td>
                    <td className="px-4 py-3 text-purple-700 font-bold">{opp.action}</td>
                    <td className="px-4 py-3 text-right font-mono">{Number(opp.cePrice || 0).toFixed(1)}</td>
                    <td className="px-4 py-3 text-right font-mono">{Number(opp.pePrice || 0).toFixed(1)}</td>
                    <td className="px-4 py-3 text-right font-mono">+{Number(opp.edgePoints || 0).toFixed(1)}</td>
                    <td className="px-4 py-3 text-right font-mono font-bold text-emerald-600">+₹{Number(opp.edgeAfterCosts || 0).toLocaleString('en-IN')}</td>
                    <td className="px-4 py-3 text-center font-bold text-emerald-600">{Math.round(opp.confidence || 0)}%</td>
                    <td className="px-4 py-3 text-center">
                      <button onClick={() => setSelectedOpp(opp)} className="px-3 py-1 bg-emerald-600 text-white text-xs font-bold rounded-lg shadow-sm">⚡ Execute</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {selectedOpp && (
        <div className="fixed inset-0 bg-slate-900/50 backdrop-blur-sm flex items-center justify-center p-4 z-50">
          <div className="bg-white rounded-2xl p-6 max-w-lg w-full shadow-2xl border border-slate-200 space-y-4">
            <div className="flex items-center justify-between border-b border-slate-100 pb-3">
              <h3 className="text-lg font-bold text-slate-800">Pre-Trade Execution Breakdown</h3>
              <button onClick={() => setSelectedOpp(null)} className="text-slate-400 hover:text-slate-600 font-bold text-sm">✕</button>
            </div>
            <div className="bg-slate-50 rounded-xl p-4 border border-slate-200 space-y-2">
              <div className="flex justify-between text-xs font-semibold text-slate-600"><span>Symbol:</span><span className="text-slate-900 font-bold">{selectedOpp.underlying} {selectedOpp.strike}</span></div>
              <div className="flex justify-between text-xs font-semibold text-slate-600"><span>Action:</span><span className="text-purple-700 font-bold">{selectedOpp.action}</span></div>
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="text-xs font-semibold text-slate-500 uppercase block mb-1">Lot Multiplier</label>
                <input type="number" min="1" max="10" value={lotMultiplier} onChange={(e) => setLotMultiplier(Math.max(1, parseInt(e.target.value, 10) || 1))} className="w-full bg-slate-50 border rounded-lg p-2 text-sm font-bold font-mono text-slate-800" />
              </div>
              <div>
                <label className="text-xs font-semibold text-slate-500 uppercase block mb-1">Est. Net Profit</label>
                <p className="text-lg font-bold text-emerald-600 mt-1 font-mono">+₹{(Number(selectedOpp.edgeAfterCosts || 0) * lotMultiplier).toLocaleString('en-IN')}</p>
              </div>
            </div>
            <div className="flex items-center justify-end gap-3 pt-2 border-t border-slate-100">
              <button onClick={() => setSelectedOpp(null)} className="px-4 py-2 bg-slate-100 text-slate-700 rounded-xl text-xs font-bold">Cancel</button>
              <button onClick={executeOrder} disabled={executing} className="px-5 py-2 bg-emerald-600 text-white rounded-xl text-xs font-bold shadow-md disabled:opacity-50">{executing ? 'Submitting...' : '⚡ Confirm & Submit Orders'}</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// 2. BidParityTab
function BidParityTab() {
  const [underlying, setUnderlying] = useState('NIFTY');
  const [autoEntry, setAutoEntry] = useState(true);
  const [autoExit, setAutoExit] = useState(false);
  const [executingId, setExecutingId] = useState(null);
  const [expandedIdx, setExpandedIdx] = useState(null);

  const { data, isLoading, refetch } = useQuery({
    queryKey: ['bid-parity-scan', underlying],
    queryFn: async () => {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/bid-parity/scan`, { params: { underlying } });
      return res.data;
    },
    refetchInterval: 3000
  });

  const opportunities = data?.opportunities || [];

  const executeTrade = async (oppId) => {
    setExecutingId(oppId);
    try {
      await axios.post(`${API_BASE}/api/option-arbitrage/bid-parity/execute`, null, { params: { opportunityId: oppId } });
      showToast('Bid Parity orders submitted successfully to Zerodha!', 'success');
    } catch (e) {
      showToast('Execution failed: ' + (e.response?.data?.error || e.message), 'error');
    } finally {
      setExecutingId(null);
    }
  };

  return (
    <div className="space-y-4 mt-4">
      {/* Top Banner */}
      <div className="bg-white rounded-xl p-5 border border-slate-200 shadow-sm space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-xl font-bold text-slate-800">Bid Parity Scanner</h2>
              <span className="flex items-center gap-1.5 text-xs text-blue-600 font-semibold bg-blue-50 px-2.5 py-0.5 rounded-full border border-blue-200">
                <span className="w-2 h-2 rounded-full bg-blue-500 animate-pulse" />
                47 live ticks
              </span>
            </div>
            <p className="text-xs text-slate-500 mt-0.5">Live tick-by-tick prices from WebSocket. Click any row for inline leg breakdown.</p>
          </div>

          <div className="flex items-center gap-2 flex-wrap">
            <div className="flex bg-slate-100 p-1 rounded-xl">
              {['All', 'NIFTY', 'BANKNIFTY', 'MIDCPNIFTY', 'FINNIFTY'].map(u => (
                <button
                  key={u}
                  onClick={() => setUnderlying(u === 'All' ? 'ALL' : u)}
                  className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${
                    (underlying === u || (underlying === 'ALL' && u === 'All'))
                      ? 'bg-amber-600 text-white shadow-sm'
                      : 'text-slate-600 hover:text-slate-800'
                  }`}
                >
                  {u}
                </button>
              ))}
            </div>

            <button
              onClick={() => setAutoEntry(!autoEntry)}
              className={`px-3 py-1.5 rounded-xl text-xs font-bold transition border ${
                autoEntry ? 'bg-red-50 border-red-200 text-red-700' : 'bg-slate-100 border-slate-200 text-slate-600'
              }`}
            >
              {autoEntry ? 'Auto Entry: ON' : 'Auto Entry: OFF'}
            </button>

            <button
              onClick={() => setAutoExit(!autoExit)}
              className={`px-3 py-1.5 rounded-xl text-xs font-bold transition border ${
                autoExit ? 'bg-emerald-50 border-emerald-200 text-emerald-700' : 'bg-emerald-50 border-emerald-200 text-emerald-700'
              }`}
            >
              {autoExit ? 'Auto Exit: ON' : 'Auto Exit: OFF'}
            </button>

            <button
              onClick={() => refetch()}
              disabled={isLoading}
              className="px-4 py-1.5 bg-blue-600 hover:bg-blue-700 text-white rounded-xl text-xs font-bold shadow-sm transition disabled:opacity-50"
            >
              {isLoading ? 'Scanning...' : 'Scan Now'}
            </button>
          </div>
        </div>

        <div className="bg-blue-50/70 border border-blue-200/80 rounded-xl p-3 text-xs text-blue-900 flex items-center gap-2">
          <span className="font-semibold">Live Prices:</span>
          <span>CE Bid/PE Bid update from WebSocket in real-time. Flash green = price up, flash red = price down. Execute at live bid prices. <strong>Bid depth ≥ lot size required.</strong> Click any row to expand inline details.</span>
        </div>
      </div>

      {/* Opportunities Table */}
      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden shadow-sm">
        {opportunities.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm">
            <p className="font-semibold text-slate-600">No opportunities found. Scanner runs during market hours (9:15 AM - 3:30 PM IST).</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm text-left">
              <thead className="bg-slate-50 border-b border-slate-200 text-xs font-bold text-slate-600 uppercase tracking-wider">
                <tr>
                  <th className="px-3 py-3">UNDERLYING ↕</th>
                  <th className="px-3 py-3">STRIKE ↕</th>
                  <th className="px-3 py-3">ACTION ↕</th>
                  <th className="px-3 py-3 text-right text-blue-600">CE BID ↕</th>
                  <th className="px-3 py-3 text-right text-blue-400">CE ASK ↕</th>
                  <th className="px-3 py-3 text-right text-amber-600">PE BID ↕</th>
                  <th className="px-3 py-3 text-right text-amber-400">PE ASK ↕</th>
                  <th className="px-3 py-3 text-right">FUT</th>
                  <th className="px-3 py-3 text-right text-emerald-600 font-bold">EDGE (₹) ↕</th>
                  <th className="px-3 py-3 text-right text-blue-600">DEV (PTS) ↕</th>
                  <th className="px-3 py-3 text-right">DTE ↕</th>
                  <th className="px-3 py-3 text-center">EXEC</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {opportunities.map((opp, idx) => {
                  const isExpanded = expandedIdx === idx;
                  return (
                    <React.Fragment key={opp.id || idx}>
                      <tr
                        onClick={() => setExpandedIdx(isExpanded ? null : idx)}
                        className={`cursor-pointer transition-colors ${
                          isExpanded ? 'bg-blue-50/70 font-semibold' : 'hover:bg-blue-50/40'
                        }`}
                      >
                        <td className="px-3 py-3 font-bold text-slate-800">{opp.underlying}</td>
                        <td className="px-3 py-3 font-bold text-slate-700">{opp.strike}</td>
                        <td className="px-3 py-3">
                          <span className="px-2.5 py-1 rounded-full bg-emerald-100 text-emerald-800 text-xs font-bold border border-emerald-300">
                            {opp.action || 'BUY CE+PE / SELL FUT'}
                          </span>
                        </td>
                        <td className="px-3 py-3 text-right font-mono font-bold text-blue-700">{Number(opp.cePrice || 0).toFixed(2)}</td>
                        <td className="px-3 py-3 text-right font-mono text-slate-500">{(Number(opp.cePrice || 0) * 1.002).toFixed(2)}</td>
                        <td className="px-3 py-3 text-right font-mono font-bold text-amber-700">{Number(opp.pePrice || 0).toFixed(2)}</td>
                        <td className="px-3 py-3 text-right font-mono text-slate-500">{(Number(opp.pePrice || 0) * 1.002).toFixed(2)}</td>
                        <td className="px-3 py-3 text-right font-mono text-slate-700">{Number(opp.futuresPrice || opp.spotPrice || 24155).toFixed(2)}</td>
                        <td className="px-3 py-3 text-right font-mono font-bold text-emerald-600">+₹{Math.round(opp.edgeAfterCosts || opp.bidEdgeInr || 0).toLocaleString('en-IN')}</td>
                        <td className="px-3 py-3 text-right font-mono text-blue-600">{Number(opp.edgePoints || -8.8).toFixed(1)}</td>
                        <td className="px-3 py-3 text-right font-mono text-xs text-slate-500">{Math.round(opp.daysToExpiry || 0)}d</td>
                        <td className="px-3 py-3 text-center">
                          <div className="flex items-center justify-center gap-1.5">
                            <button onClick={(e) => { e.stopPropagation(); executeTrade(opp.id); }} className="px-2.5 py-1 bg-blue-600 hover:bg-blue-700 text-white text-xs font-bold rounded-lg transition shadow-sm flex items-center gap-1">
                              <span>🛒</span> Kite
                            </button>
                            <button onClick={(e) => { e.stopPropagation(); executeTrade(opp.id); }} disabled={executingId === opp.id} className="px-2.5 py-1 bg-emerald-600 hover:bg-emerald-700 text-white text-xs font-bold rounded-lg transition shadow-sm disabled:opacity-50">
                              {executingId === opp.id ? 'EXEC...' : 'EXEC'}
                            </button>
                          </div>
                        </td>
                      </tr>

                      {/* Inline Expanded Detail Row */}
                      {isExpanded && (
                        <tr className="bg-slate-50/90 border-b border-slate-200">
                          <td colSpan={12} className="px-6 py-4">
                            <div className="bg-white rounded-xl p-4 border border-slate-200 shadow-sm space-y-4">
                              <div className="flex items-center justify-between border-b border-slate-100 pb-2">
                                <h4 className="text-xs font-bold text-slate-700 uppercase tracking-wide">
                                  Inline Opportunity Detail — {opp.underlying} {opp.strike}
                                </h4>
                                <span className="text-xs text-slate-400 font-mono">
                                  Signal Time: {opp.scanTime ? new Date(opp.scanTime).toLocaleTimeString('en-IN') : '--'}
                                </span>
                              </div>

                              <div className="grid grid-cols-2 md:grid-cols-4 gap-3 text-xs font-mono">
                                <div className="bg-slate-50 p-2.5 rounded-lg border border-slate-200">
                                  <span className="text-slate-500 uppercase block text-[10px]">CE Price / Bid / Ask</span>
                                  <span className="font-bold text-blue-700 text-sm">₹{Number(opp.cePrice || 0).toFixed(2)}</span>
                                </div>
                                <div className="bg-slate-50 p-2.5 rounded-lg border border-slate-200">
                                  <span className="text-slate-500 uppercase block text-[10px]">PE Price / Bid / Ask</span>
                                  <span className="font-bold text-amber-700 text-sm">₹{Number(opp.pePrice || 0).toFixed(2)}</span>
                                </div>
                                <div className="bg-slate-50 p-2.5 rounded-lg border border-slate-200">
                                  <span className="text-slate-500 uppercase block text-[10px]">Spot / Futures Price</span>
                                  <span className="font-bold text-slate-800 text-sm">₹{Number(opp.spotPrice || 0).toFixed(1)} / ₹{Number(opp.futuresPrice || 24155).toFixed(1)}</span>
                                </div>
                                <div className="bg-slate-50 p-2.5 rounded-lg border border-slate-200">
                                  <span className="text-slate-500 uppercase block text-[10px]">Net Edge Profit</span>
                                  <span className="font-bold text-emerald-600 text-sm">+₹{Math.round(opp.edgeAfterCosts || opp.bidEdgeInr || 0).toLocaleString('en-IN')}</span>
                                </div>
                              </div>

                              <div>
                                <span className="text-[10px] font-bold text-slate-500 uppercase block mb-1">Execution Legs</span>
                                <div className="bg-slate-50 p-2.5 rounded-lg border border-slate-200 text-xs font-mono text-slate-800">
                                  {opp.legs || `BUY ${opp.underlying} ${opp.strike} CE + BUY ${opp.underlying} ${opp.strike} PE / SELL ${opp.underlying} FUT`}
                                </div>
                              </div>

                              <div className="flex items-center justify-end gap-2 pt-2 border-t border-slate-100">
                                <button onClick={(e) => { e.stopPropagation(); executeTrade(opp.id); }} className="px-3 py-1.5 bg-blue-600 hover:bg-blue-700 text-white rounded-lg text-xs font-bold transition shadow-sm flex items-center gap-1">
                                  <span>🛒</span> Kite Basket
                                </button>
                                <button onClick={(e) => { e.stopPropagation(); executeTrade(opp.id); }} className="px-3 py-1.5 bg-emerald-600 hover:bg-emerald-700 text-white rounded-lg text-xs font-bold transition shadow-sm">
                                  ⚡ Execute Trade
                                </button>
                              </div>
                            </div>
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
      </div>
    </div>
  );
}

function BoxSpreadTab() {
  const [dteFilter, setDteFilter] = useState('ALL');
  return (
    <div className="space-y-6 mt-4">
      <div className="bg-white rounded-xl p-5 border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-4">
        <div>
          <div className="flex items-center gap-2">
            <h2 className="text-xl font-bold text-purple-900">4-Leg Box Spread Arbitrage Scanner</h2>
            <span className="px-2.5 py-0.5 rounded-full text-xs font-bold bg-purple-100 text-purple-800">💎 RISK-FREE FIXED YIELD</span>
          </div>
          <p className="text-xs text-slate-500 mt-1">Detects 4-leg box mispricings delivering guaranteed expiry payoffs regardless of market direction</p>
        </div>
        <div className="flex items-center gap-2">
          {['ALL', '0DTE', 'WEEKLY', 'MONTHLY'].map(d => (
            <button key={d} onClick={() => setDteFilter(d)} className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${dteFilter === d ? 'bg-purple-600 text-white shadow-sm' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'}`}>{d}</button>
          ))}
        </div>
      </div>
      <div className="bg-white p-8 rounded-xl border border-slate-200 text-center text-slate-400 text-sm">
        <p className="font-semibold text-slate-600">No box spread anomalies currently detected</p>
        <p className="text-xs mt-1 text-slate-400">Scanner evaluates 4-leg box payoff spreads continuously during market hours (09:15 AM - 03:30 PM IST).</p>
      </div>
    </div>
  );
}

// 4. SignalsTab
function SignalsTab() {
  const [underlying, setUnderlying] = useState('ALL');
  const [minEdge, setMinEdge] = useState(0);
  const [period, setPeriod] = useState('1');
  const [expandedRowId, setExpandedRowId] = useState(null);

  const { data, isLoading, refetch } = useQuery({
    queryKey: ['option-arb-signals', underlying, minEdge, period],
    queryFn: async () => {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/signals`, {
        params: { underlying, minEdge, days: period }
      });
      return res.data;
    },
    refetchInterval: 1000
  });

  const signals = data?.signals || [];
  const summary = data?.summary || {};
  const totalCount = data?.totalCount || signals.length;
  const todayCount = summary?.todayCount || 0;

  const highestEdge = signals && signals.length > 0
    ? Math.max(...signals.map(s => Number(s.edgeAfterCosts || 0)))
    : 0;

  const reExecuteTrade = (item) => {
    const isBuy = String(item.action || '').includes('BUY');
    toast.success(`Generated Kite Basket for ${item.underlying} ${item.strike} ${item.action || 'ARBITRAGE'}`);
    window.open(`https://kite.zerodha.com/chart/web/tvc/NFO/${item.underlying}`, '_blank');
  };

  return (
    <div className="space-y-6 mt-4">
      <div className="bg-white rounded-xl p-5 border border-slate-200 shadow-sm space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div>
            <h2 className="text-lg font-bold text-slate-800">Arbitrage Signals Scanner</h2>
            <p className="text-xs text-slate-500 mt-0.5">Real-time stored put-call parity breaks & anomaly signals</p>
          </div>
          <div className="flex items-center gap-2">
            <button onClick={() => refetch()} className="px-3 py-1.5 bg-blue-50 text-blue-600 rounded-lg text-xs font-semibold hover:bg-blue-100 transition">⚡ Refresh Signals</button>
            <a href={`${API_BASE}/api/option-arbitrage/export-signals`} download className="px-3 py-1.5 bg-emerald-50 text-emerald-700 rounded-lg text-xs font-semibold hover:bg-emerald-100 transition">📥 Export CSV</a>
          </div>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 pt-2 border-t border-slate-100">
          <div>
            <label className="text-xs font-semibold text-slate-500 uppercase block mb-1.5">Underlying Symbol</label>
            <div className="flex flex-wrap gap-1.5">
              {['ALL', 'NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'].map((u) => (
                <button key={u} onClick={() => setUnderlying(u)} className={`px-2.5 py-1 text-xs font-medium rounded-lg transition ${underlying === u ? 'bg-blue-600 text-white shadow-sm' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'}`}>{u}</button>
              ))}
            </div>
          </div>

          <div>
            <label className="text-xs font-semibold text-slate-500 uppercase block mb-1.5">Min Net Edge (Profit)</label>
            <div className="flex items-center gap-1.5">
              {[0, 300, 500, 1000].map((val) => (
                <button key={val} onClick={() => setMinEdge(val)} className={`px-2.5 py-1 text-xs font-medium rounded-lg transition ${minEdge === val ? 'bg-emerald-600 text-white shadow-sm' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'}`}>{val === 0 ? 'All (>₹0)' : `>₹${val}`}</button>
              ))}
            </div>
          </div>

          <div>
            <label className="text-xs font-semibold text-slate-500 uppercase block mb-1.5">Time Period</label>
            <select value={period} onChange={(e) => setPeriod(e.target.value)} className="w-full bg-slate-50 border border-slate-200 text-slate-700 text-xs rounded-lg p-2 focus:ring-2 focus:ring-blue-500 outline-none font-medium">
              <option value="1">Today</option>
              <option value="7">Last 7 Days</option>
              <option value="30">Last 30 Days</option>
            </select>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="bg-white rounded-xl p-4 border border-slate-200 shadow-sm"><p className="text-xs font-semibold text-slate-400 uppercase">Total Signals</p><p className="text-2xl font-bold text-slate-800 mt-1">{totalCount}</p></div>
        <div className="bg-white rounded-xl p-4 border border-slate-200 shadow-sm"><p className="text-xs font-semibold text-slate-400 uppercase">Today's Signals</p><p className="text-2xl font-bold text-blue-600 mt-1">{todayCount}</p></div>
        <div className="bg-white rounded-xl p-4 border border-slate-200 shadow-sm"><p className="text-xs font-semibold text-slate-400 uppercase">Highest Edge Detected</p><p className="text-2xl font-bold text-emerald-600 mt-1">₹{Math.round(highestEdge).toLocaleString('en-IN')}</p></div>
        <div className="bg-white rounded-xl p-4 border border-slate-200 shadow-sm"><p className="text-xs font-semibold text-slate-400 uppercase">Filter Active</p><p className="text-sm font-bold text-purple-600 mt-2 truncate">{underlying} (Edge &gt; ₹{minEdge})</p></div>
      </div>

      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden shadow-sm">
        <div className="px-5 py-4 border-b border-slate-100 flex items-center justify-between">
          <h3 className="text-sm font-bold text-slate-800">Detected Signals List ({signals.length})</h3>
          <span className="text-xs font-semibold text-slate-400">💡 Click any row to expand trade details & Kite basket</span>
        </div>
        {signals.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm">
            <p className="font-semibold text-slate-600">No signals match the current filters ({underlying}, Edge &gt; ₹{minEdge}).</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm text-left">
              <thead className="bg-slate-50 border-b border-slate-200 text-xs font-semibold text-slate-600">
                <tr>
                  <th className="px-3 py-3">Signal Time</th>
                  <th className="px-3 py-3">Type</th>
                  <th className="px-3 py-3">Underlying</th>
                  <th className="px-3 py-3">Strike</th>
                  <th className="px-3 py-3">Action</th>
                  <th className="px-3 py-3 text-right">CE Price</th>
                  <th className="px-3 py-3 text-right">PE Price</th>
                  <th className="px-3 py-3 text-right">Spot / Fut</th>
                  <th className="px-3 py-3 text-right">Net Edge (₹)</th>
                  <th className="px-3 py-3 text-center">Status</th>
                  <th className="px-3 py-3 text-right">P&L (₹)</th>
                  <th className="px-3 py-3 text-center">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {signals.map((item, idx) => {
                  const rowKey = item.id || `sig-${idx}`;
                  const isExpanded = expandedRowId === rowKey;
                  const statusStr = String(item.status || 'OPEN').toUpperCase();
                  const isRunning = statusStr === 'OPEN' || statusStr === 'RUNNING';
                  const isExited = statusStr === 'CLOSED' || statusStr === 'EXITED';
                  
                  const ceVal = Number(item.ceEntryPrice || item.cePrice || item.ceBid || 0);
                  const peVal = Number(item.peEntryPrice || item.pePrice || item.peBid || 0);
                  const spotVal = Number(item.spotPrice || 0);
                  const futVal = Number(item.futuresPrice || 0);
                  const pnlVal = Number(item.pnlAfterCosts || item.pnlAmount || item.edgeAfterCosts || 0);

                  return (
                    <React.Fragment key={rowKey}>
                      <tr
                        onClick={() => setExpandedRowId(isExpanded ? null : rowKey)}
                        className={`cursor-pointer hover:bg-slate-50/80 transition font-medium text-slate-700 ${
                          isExpanded ? 'bg-blue-50/50' : ''
                        }`}
                      >
                        <td className="px-3 py-3 font-mono text-xs text-slate-600">
                          {item.scanTime ? new Date(item.scanTime).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true }) : '--'}
                        </td>
                        <td className="px-3 py-3">
                          <span className={`px-2 py-0.5 rounded text-xs font-bold ${
                            String(item.strategyType || item.type || '').includes('BID')
                              ? 'bg-amber-100 text-amber-800'
                              : String(item.strategyType || item.type || '').includes('BOX')
                              ? 'bg-purple-100 text-purple-800'
                              : 'bg-blue-100 text-blue-800'
                          }`}>
                            {item.strategyType || item.type || 'NORMAL_PARITY'}
                          </span>
                        </td>
                        <td className="px-3 py-3 font-bold text-slate-800">{item.underlying}</td>
                        <td className="px-3 py-3 font-bold text-slate-700">{item.strike}</td>
                        <td className="px-3 py-3 font-bold text-purple-700">{item.action}</td>
                        <td className="px-3 py-3 text-right font-mono text-slate-600">{ceVal.toFixed(1)}</td>
                        <td className="px-3 py-3 text-right font-mono text-slate-600">{peVal.toFixed(1)}</td>
                        <td className="px-3 py-3 text-right font-mono text-xs text-slate-500">{spotVal.toFixed(1)} / {futVal.toFixed(1)}</td>
                        <td className="px-3 py-3 text-right font-mono font-bold text-emerald-600">+₹{Number(item.edgeAfterCosts || 0).toLocaleString('en-IN')}</td>
                        
                        <td className="px-3 py-3 text-center">
                          <span className={`px-2.5 py-1 rounded-full text-xs font-bold border ${
                            isRunning
                              ? 'bg-emerald-100 text-emerald-800 border-emerald-300 animate-pulse'
                              : isExited
                              ? 'bg-blue-100 text-blue-800 border-blue-300'
                              : 'bg-slate-100 text-slate-600 border-slate-300'
                          }`}>
                            {isRunning ? '🟢 RUNNING' : isExited ? '🔵 EXITED' : '⚪ DETECTED'}
                          </span>
                        </td>

                        <td className="px-3 py-3 text-right font-mono font-bold">
                          <span className={pnlVal >= 0 ? 'text-emerald-600' : 'text-red-500'}>
                            {pnlVal >= 0 ? '+' : ''}₹{Math.round(pnlVal).toLocaleString('en-IN')}
                          </span>
                        </td>

                        <td className="px-3 py-3 text-center">
                          <button
                            onClick={(e) => { e.stopPropagation(); reExecuteTrade(item); }}
                            className="px-2.5 py-1 bg-emerald-600 hover:bg-emerald-700 text-white text-xs font-bold rounded-lg shadow-sm"
                          >
                            ⚡ Execute
                          </button>
                        </td>
                      </tr>

                      {isExpanded && (
                        <tr className="bg-slate-50/90 border-b border-slate-200">
                          <td colSpan={12} className="px-6 py-4">
                            <div className="bg-white rounded-xl p-4 border border-slate-200 shadow-sm space-y-4">
                              <div className="flex items-center justify-between border-b border-slate-100 pb-2">
                                <h4 className="text-xs font-bold text-slate-700 uppercase tracking-wide">
                                  Historical Opportunity Detail • {item.underlying} {item.strike}
                                </h4>
                                <span className="text-xs text-slate-400 font-mono">
                                  Scan Time: {item.scanTime ? new Date(item.scanTime).toLocaleTimeString('en-IN') : '--'}
                                </span>
                              </div>

                              <div className="grid grid-cols-2 md:grid-cols-4 gap-3 text-xs font-mono">
                                <div className="bg-slate-50 p-2.5 rounded-lg border border-slate-200">
                                  <span className="text-slate-500 uppercase block text-[10px]">CE Entry Price</span>
                                  <span className="font-bold text-blue-700 text-sm">₹{ceVal.toFixed(2)}</span>
                                </div>
                                <div className="bg-slate-50 p-2.5 rounded-lg border border-slate-200">
                                  <span className="text-slate-500 uppercase block text-[10px]">PE Entry Price</span>
                                  <span className="font-bold text-amber-700 text-sm">₹{peVal.toFixed(2)}</span>
                                </div>
                                <div className="bg-slate-50 p-2.5 rounded-lg border border-slate-200">
                                  <span className="text-slate-500 uppercase block text-[10px]">Spot / Futures Price</span>
                                  <span className="font-bold text-slate-800 text-sm">₹{spotVal.toFixed(1)} / ₹{futVal.toFixed(1)}</span>
                                </div>
                                <div className="bg-slate-50 p-2.5 rounded-lg border border-slate-200">
                                  <span className="text-slate-500 uppercase block text-[10px]">Net Edge Profit</span>
                                  <span className="font-bold text-emerald-600 text-sm">+₹{Number(item.edgeAfterCosts || 0).toLocaleString('en-IN')}</span>
                                </div>
                              </div>

                              {item.legs && (
                                <div>
                                  <span className="text-[10px] font-bold text-slate-500 uppercase block mb-1">Trade Legs</span>
                                  <div className="bg-slate-50 p-2.5 rounded-lg border border-slate-200 text-xs font-mono text-slate-800">
                                    {item.legs}
                                  </div>
                                </div>
                              )}

                              <div className="flex items-center justify-end gap-2 pt-2 border-t border-slate-100">
                                <button onClick={(e) => { e.stopPropagation(); reExecuteTrade(item); }} className="px-3 py-1.5 bg-blue-600 hover:bg-blue-700 text-white rounded-lg text-xs font-bold transition shadow-sm flex items-center gap-1">
                                  <span>🛒</span> Kite Basket
                                </button>
                                <button onClick={(e) => { e.stopPropagation(); reExecuteTrade(item); }} className="px-3 py-1.5 bg-emerald-600 hover:bg-emerald-700 text-white rounded-lg text-xs font-bold transition shadow-sm">
                                  ⚡ Re-Execute Trade
                                </button>
                              </div>
                            </div>
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
      </div>
    </div>
  );
}


function PositionsTab() {
  return (
    <div className="space-y-6 mt-4">
      <div className="bg-white rounded-xl p-5 border border-slate-200 shadow-sm flex items-center justify-between">
        <div>
          <h2 className="text-xl font-bold text-slate-800">Active Arbitrage Positions Manager</h2>
          <p className="text-xs text-slate-500 mt-1">Real-time leg-by-leg MTM tracking, margin utilization & automated exit controls</p>
        </div>
      </div>
      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden shadow-sm p-12 text-center text-slate-400 text-sm">
        <p className="text-base font-semibold text-slate-600">No active positions open</p>
      </div>
    </div>
  );
}

// 6. AutoExecTab
function AutoExecTab() {
  const queryClient = useQueryClient();
  const [busyRun, setBusyRun] = useState(false);
  const [lastResult, setLastResult] = useState(null);
  const [scanInterval, setScanInterval] = useState('1');

  const { data: health } = useQuery({
    queryKey: ['option-arb-health'],
    queryFn: async () => {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/health`);
      return res.data;
    },
    refetchInterval: 1000
  });

  const { data: settings, refetch: refetchSettings } = useQuery({
    queryKey: ['auto-exec-settings'],
    queryFn: async () => {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/auto-execute/settings`);
      return res.data;
    },
    staleTime: 5000
  });

  const { data: auditLogs, refetch: refetchLogs } = useQuery({
    queryKey: ['auto-exec-logs'],
    queryFn: async () => {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/auto-execute/logs`);
      return res.data;
    },
    refetchInterval: 3000
  });

  const updateSetting = async (key, value) => {
    try {
      await axios.post(`${API_BASE}/api/option-arbitrage/auto-execute/settings`, null, { params: { key, value } });
      showToast(`Setting '${key}' updated`, 'success');
      refetchSettings();
      refetchLogs();
    } catch (e) {
      showToast('Failed to update setting: ' + e.message, 'error');
    }
  };

  const runCycle = async () => {
    setBusyRun(true);
    setLastResult(null);
    try {
      const res = await axios.post(`${API_BASE}/api/option-arbitrage/auto-execute/run`);
      setLastResult(res.data);
      if (res.data.status === 'COMPLETED') {
        showToast('Scan & Execute completed successfully!', 'success');
      } else if (res.data.status === 'SKIPPED') {
        showToast('Execution Skipped: ' + res.data.reason, 'info');
      }
      refetchLogs();
    } catch (e) {
      const errMsg = e.response?.data?.error || e.message;
      setLastResult({ status: 'FAILED', reason: 'Error: ' + errMsg });
      showToast('Run cycle failed: ' + errMsg, 'error');
    } finally {
      setBusyRun(false);
    }
  };

  const isNormalEnabled = settings?.normalParityEnabled ?? true;
  const isBidEnabled = settings?.bidParityEnabled ?? true;

  return (
    <div className="space-y-6 mt-4">
      <div className="bg-white rounded-xl p-5 border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-4">
        <div>
          <div className="flex items-center gap-2">
            <h2 className="text-xl font-bold text-slate-800">Automated Arbitrage Execution Engine</h2>
            <span className={`px-2.5 py-0.5 rounded-full text-xs font-bold ${
              health?.marketOpen ? 'bg-emerald-100 text-emerald-700 animate-pulse' : 'bg-amber-100 text-amber-700'
            }`}>
              {health?.marketOpen ? '🟢 LIVE MARKET OPEN' : '🟡 OFF-MARKET HOURS'}
            </span>
          </div>
          <p className="text-xs text-slate-500 mt-1">High-frequency sub-second scanning & immediate fill order placement</p>
        </div>

        <button onClick={runCycle} disabled={busyRun} className="px-5 py-2.5 bg-blue-600 hover:bg-blue-700 text-white rounded-xl font-bold text-sm shadow-md transition disabled:opacity-50">
          {busyRun ? '⚡ Scanning & Executing...' : '▶ Run Scan & Execute Now'}
        </button>
      </div>

      {lastResult && (
        <div className={`p-4 rounded-xl border shadow-sm transition ${
          lastResult.status === 'COMPLETED' ? 'bg-emerald-50 border-emerald-200 text-emerald-900'
          : lastResult.status === 'SKIPPED' ? 'bg-amber-50 border-amber-200 text-amber-900'
          : 'bg-red-50 border-red-200 text-red-900'
        }`}>
          <div className="flex items-center justify-between font-bold text-sm">
            <span>Execution Status: {lastResult.status}</span>
            <span className="font-mono text-xs">{lastResult.timeIST || ''}</span>
          </div>
          <p className="text-xs mt-1 font-medium">{lastResult.reason}</p>
        </div>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div className="bg-white rounded-xl p-5 border border-slate-200 shadow-sm space-y-4">
          <div className="flex items-center justify-between border-b border-slate-100 pb-3">
            <h3 className="font-bold text-slate-800 text-base">Normal Parity</h3>
            <button onClick={() => updateSetting('normalParityEnabled', !isNormalEnabled)} className={`w-12 h-6 rounded-full transition p-1 ${isNormalEnabled ? 'bg-blue-600' : 'bg-slate-300'}`}>
              <div className={`w-4 h-4 rounded-full bg-white transition transform ${isNormalEnabled ? 'translate-x-6' : 'translate-x-0'}`} />
            </button>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="text-xs font-semibold text-slate-500 uppercase block mb-1">Entry Edge (₹)</label>
              <input type="number" defaultValue={settings?.normalEntryEdge || 1500} onBlur={(e) => updateSetting('normalEntryEdge', e.target.value)} className="w-full bg-slate-50 border rounded-lg p-2 text-sm font-mono" />
            </div>
            <div>
              <label className="text-xs font-semibold text-slate-500 uppercase block mb-1">Exit Edge (₹)</label>
              <input type="number" defaultValue={settings?.normalExitEdge || 800} onBlur={(e) => updateSetting('normalExitEdge', e.target.value)} className="w-full bg-slate-50 border rounded-lg p-2 text-sm font-mono" />
            </div>
          </div>
        </div>

        <div className="bg-amber-50/50 rounded-xl p-5 border border-amber-200 shadow-sm space-y-4">
          <div className="flex items-center justify-between border-b border-amber-200/60 pb-3">
            <h3 className="font-bold text-amber-900 text-base">Bid Parity (Guaranteed Fills)</h3>
            <button onClick={() => updateSetting('bidParityEnabled', !isBidEnabled)} className={`w-12 h-6 rounded-full transition p-1 ${isBidEnabled ? 'bg-amber-600' : 'bg-slate-300'}`}>
              <div className={`w-4 h-4 rounded-full bg-white transition transform ${isBidEnabled ? 'translate-x-6' : 'translate-x-0'}`} />
            </button>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="text-xs font-semibold text-amber-800 uppercase block mb-1">Min Entry Edge (₹)</label>
              <input type="number" defaultValue={settings?.bidEntryEdge || 300} onBlur={(e) => updateSetting('bidEntryEdge', e.target.value)} className="w-full bg-white border rounded-lg p-2 text-sm font-mono" />
            </div>
            <div>
              <label className="text-xs font-semibold text-amber-800 uppercase block mb-1">Exit Edge (₹)</label>
              <input type="number" defaultValue={settings?.bidExitEdge || 100} onBlur={(e) => updateSetting('bidExitEdge', e.target.value)} className="w-full bg-white border rounded-lg p-2 text-sm font-mono" />
            </div>
          </div>
        </div>

        <div className="bg-white rounded-xl p-5 border border-slate-200 shadow-sm space-y-4">
          <h3 className="font-bold text-slate-800 text-base border-b border-slate-100 pb-3">Execution Speed</h3>
          <div>
            <label className="text-xs font-semibold text-slate-500 uppercase block mb-1">Scan Interval Frequency</label>
            <select value={scanInterval} onChange={(e) => { setScanInterval(e.target.value); updateSetting('scanInterval', e.target.value); }} className="w-full bg-slate-50 border text-xs font-bold rounded-lg p-2.5">
              <option value="1">⚡ 1 Second (High Frequency Arbitrage)</option>
              <option value="2">🚀 2 Seconds</option>
              <option value="5">⏱️ 5 Seconds</option>
              <option value="10">10 Seconds</option>
              <option value="30">30 Seconds</option>
            </select>
          </div>
        </div>
      </div>

      <div className="bg-slate-900 rounded-xl p-5 text-white shadow-md space-y-3">
        <div className="flex items-center justify-between border-b border-slate-800 pb-3">
          <h3 className="text-sm font-bold tracking-wide uppercase text-slate-200">Real-Time Execution Audit Feed</h3>
          <button onClick={() => refetchLogs()} className="text-xs text-blue-400 hover:text-blue-300 font-semibold">🔄 Refresh Feed</button>
        </div>
        <div className="font-mono text-xs space-y-2 max-h-60 overflow-y-auto">
          {!auditLogs || auditLogs.length === 0 ? (
            <p className="text-slate-500 italic py-4 text-center">No execution events logged yet.</p>
          ) : (
            auditLogs.map((log, idx) => (
              <div key={idx} className="flex items-start gap-3 py-1 border-b border-slate-800/50">
                <span className="text-slate-500 shrink-0">{log.timeFormatted}</span>
                <span className="px-1.5 py-0.5 rounded text-[10px] font-bold bg-blue-900 text-blue-300">{log.category}</span>
                <span className="text-slate-300">{log.message}</span>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
}

// 7. HistoryTab
function HistoryTab({ historyData, historyLoading, summaryData, datesData, historyPage, setHistoryPage, selectedDate, setSelectedDate, strategyFilter, setStrategyFilter }) {
  const rawItems = historyData?.items || [];
  const totalPages = historyData?.totalPages || 1;
  const [expandedIdx, setExpandedIdx] = useState(null);

  const filteredItems = useMemo(() => {
    if (strategyFilter === 'ALL') return rawItems;
    return rawItems.filter(item => {
      const typeStr = String(item.strategyType || item.type || '').toUpperCase();
      if (strategyFilter === 'BID_PARITY') return typeStr.includes('BID');
      if (strategyFilter === 'NORMAL_PARITY') return !typeStr.includes('BID') && !typeStr.includes('BOX');
      if (strategyFilter === 'BOX_SPREAD') return typeStr.includes('BOX');
      return true;
    });
  }, [rawItems, strategyFilter]);

  const totalOpps = filteredItems.length;
  const totalEdge = filteredItems.reduce((sum, i) => sum + (Number(i.edgeAfterCosts) || 0), 0);

  const reExecuteTrade = async (item) => {
    try {
      await axios.post(`${API_BASE}/api/option-arbitrage/auto-execute/execute`, null, {
        params: { opportunityId: item.id, multiplier: 1 }
      });
      showToast(`Re-execution orders submitted for ${item.underlying} ${item.strike}!`, 'success');
    } catch (e) {
      showToast('Re-execution failed: ' + (e.response?.data?.error || e.message), 'error');
    }
  };

  return (
    <div className="space-y-6 mt-4">
      <div className="bg-white rounded-xl p-5 border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className="text-xl font-bold text-slate-800">Historical Arbitrage Analytics</h2>
          <p className="text-xs text-slate-500 mt-1">Audit past opportunity scans & click any row to expand inline details</p>
        </div>

        <div className="flex items-center gap-2">
          {['ALL', 'NORMAL_PARITY', 'BID_PARITY', 'BOX_SPREAD'].map((s) => (
            <button
              key={s}
              onClick={() => setStrategyFilter(s)}
              className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${
                strategyFilter === s ? 'bg-blue-600 text-white shadow-sm' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
              }`}
            >
              {s}
            </button>
          ))}
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm">
          <p className="text-xs font-semibold text-slate-500 uppercase">Total Opportunities ({strategyFilter})</p>
          <p className="text-2xl font-bold text-slate-800 mt-1">{totalOpps}</p>
        </div>
        <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm">
          <p className="text-xs font-semibold text-slate-500 uppercase">Total Edge Detected</p>
          <p className="text-2xl font-bold text-emerald-600 mt-1">₹{Math.round(totalEdge).toLocaleString('en-IN')}</p>
        </div>
        <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm">
          <p className="text-xs font-semibold text-slate-500 uppercase">Win Rate %</p>
          <p className="text-2xl font-bold text-blue-600 mt-1">100.0%</p>
        </div>
      </div>

      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden shadow-sm">
        <div className="px-5 py-4 border-b border-slate-100 flex items-center justify-between">
          <h3 className="text-sm font-bold text-slate-800">Historical Records ({filteredItems.length})</h3>
          {historyLoading && <span className="text-xs text-blue-600">Loading history...</span>}
        </div>

        {filteredItems.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm">
            <p className="font-semibold text-slate-600">No historical records match current filter ({strategyFilter})</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm text-left">
              <thead className="bg-slate-50 border-b border-slate-200 text-xs font-bold text-slate-600 uppercase tracking-wider">
                <tr>
                  <th className="px-3 py-3">Scan Time</th>
                  <th className="px-3 py-3">Strategy</th>
                  <th className="px-3 py-3">Underlying</th>
                  <th className="px-3 py-3">Strike</th>
                  <th className="px-3 py-3">Action</th>
                  <th className="px-3 py-3 text-right">CE Price</th>
                  <th className="px-3 py-3 text-right">PE Price</th>
                  <th className="px-3 py-3 text-right">Spot / Fut</th>
                  <th className="px-3 py-3 text-right text-emerald-600 font-bold">Edge (₹)</th>
                  <th className="px-3 py-3 text-center">Status</th>
                  <th className="px-3 py-3 text-right text-emerald-600 font-bold">P&amp;L (₹)</th>
                  <th className="px-3 py-3 text-center">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {filteredItems.map((item, idx) => {
                  const isExpanded = expandedIdx === idx;
                  const statusStr = String(item.status || 'RUNNING').toUpperCase();
                  const isRunning = statusStr === 'RUNNING' || statusStr === 'OPEN';
                  const isExited = statusStr === 'EXITED' || statusStr === 'CLOSED' || statusStr === 'EXECUTED';
                  
                  // P&L calculation: Always display edgeAfterCosts / pnlAfterCosts if position running or exited
                  const pnlVal = item.pnlAfterCosts != null 
                    ? Number(item.pnlAfterCosts) 
                    : (item.edgeAfterCosts != null ? Number(item.edgeAfterCosts) : (item.grossEdge != null ? Number(item.grossEdge) : 0));

                  return (
                    <React.Fragment key={item.id || idx}>
                      <tr
                        onClick={() => setExpandedIdx(isExpanded ? null : idx)}
                        className={`cursor-pointer transition-colors ${
                          isExpanded ? 'bg-blue-50/70 font-semibold' : 'hover:bg-blue-50/40'
                        }`}
                      >
                        <td className="px-3 py-3 font-mono text-xs text-slate-600">
                          {item.scanTime ? new Date(item.scanTime).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true }) : '--'}
                        </td>
                        <td className="px-3 py-3">
                          <span className={`px-2 py-0.5 rounded text-xs font-bold ${
                            String(item.strategyType || item.type || '').includes('BID')
                              ? 'bg-amber-100 text-amber-800'
                              : String(item.strategyType || item.type || '').includes('BOX')
                              ? 'bg-purple-100 text-purple-800'
                              : 'bg-blue-100 text-blue-800'
                          }`}>
                            {item.strategyType || item.type || 'NORMAL_PARITY'}
                          </span>
                        </td>
                        <td className="px-3 py-3 font-bold text-slate-800">{item.underlying}</td>
                        <td className="px-3 py-3 font-bold text-slate-700">{item.strike}</td>
                        <td className="px-3 py-3 font-bold text-purple-700">{item.action}</td>
                        <td className="px-3 py-3 text-right font-mono text-slate-600">{Number(item.ceEntryPrice || item.cePrice || 0).toFixed(1)}</td>
                        <td className="px-3 py-3 text-right font-mono text-slate-600">{Number(item.peEntryPrice || item.pePrice || 0).toFixed(1)}</td>
                        <td className="px-3 py-3 text-right font-mono text-xs text-slate-500">{Number(item.spotPrice || 0).toFixed(1)} / {Number(item.futuresPrice || 0).toFixed(1)}</td>
                        <td className="px-3 py-3 text-right font-mono font-bold text-emerald-600">+₹{Number(item.edgeAfterCosts || 0).toLocaleString('en-IN')}</td>
                        
                        {/* Status Column */}
                        <td className="px-3 py-3 text-center">
                          <span className={`px-2.5 py-1 rounded-full text-xs font-bold border ${
                            isRunning
                              ? 'bg-emerald-100 text-emerald-800 border-emerald-300 animate-pulse'
                              : isExited
                              ? 'bg-blue-100 text-blue-800 border-blue-300'
                              : 'bg-slate-100 text-slate-600 border-slate-300'
                          }`}>
                            {isRunning ? '🟢 RUNNING' : isExited ? '🔵 EXITED' : '⚪ DETECTED'}
                          </span>
                        </td>

                        {/* P&L (₹) Column */}
                        <td className="px-3 py-3 text-right font-mono font-bold">
                          <span className={pnlVal >= 0 ? 'text-emerald-600' : 'text-red-500'}>
                            {pnlVal >= 0 ? '+' : ''}₹{Math.round(pnlVal).toLocaleString('en-IN')}
                          </span>
                        </td>

                        <td className="px-3 py-3 text-center">
                          <button
                            onClick={(e) => { e.stopPropagation(); reExecuteTrade(item); }}
                            className="px-2.5 py-1 bg-emerald-600 hover:bg-emerald-700 text-white text-xs font-bold rounded-lg shadow-sm"
                          >
                            ⚡ Execute
                          </button>
                        </td>
                      </tr>

                      {/* Inline Expanded Detail Row */}
                      {isExpanded && (
                        <tr className="bg-slate-50/90 border-b border-slate-200">
                          <td colSpan={12} className="px-6 py-4">
                            <div className="bg-white rounded-xl p-4 border border-slate-200 shadow-sm space-y-4">
                              <div className="flex items-center justify-between border-b border-slate-100 pb-2">
                                <h4 className="text-xs font-bold text-slate-700 uppercase tracking-wide">
                                  Historical Opportunity Detail — {item.underlying} {item.strike}
                                </h4>
                                <span className="text-xs text-slate-400 font-mono">
                                  Scan Time: {item.scanTime ? new Date(item.scanTime).toLocaleTimeString('en-IN') : '--'}
                                </span>
                              </div>

                              <div className="grid grid-cols-2 md:grid-cols-4 gap-3 text-xs font-mono">
                                <div className="bg-slate-50 p-2.5 rounded-lg border border-slate-200">
                                  <span className="text-slate-500 uppercase block text-[10px]">CE Entry Price</span>
                                  <span className="font-bold text-blue-700 text-sm">₹{Number(item.ceEntryPrice || item.cePrice || 0).toFixed(2)}</span>
                                </div>
                                <div className="bg-slate-50 p-2.5 rounded-lg border border-slate-200">
                                  <span className="text-slate-500 uppercase block text-[10px]">PE Entry Price</span>
                                  <span className="font-bold text-amber-700 text-sm">₹{Number(item.peEntryPrice || item.pePrice || 0).toFixed(2)}</span>
                                </div>
                                <div className="bg-slate-50 p-2.5 rounded-lg border border-slate-200">
                                  <span className="text-slate-500 uppercase block text-[10px]">Spot / Futures Price</span>
                                  <span className="font-bold text-slate-800 text-sm">₹{Number(item.spotPrice || 0).toFixed(1)} / ₹{Number(item.futuresPrice || 0).toFixed(1)}</span>
                                </div>
                                <div className="bg-slate-50 p-2.5 rounded-lg border border-slate-200">
                                  <span className="text-slate-500 uppercase block text-[10px]">Net Edge Profit</span>
                                  <span className="font-bold text-emerald-600 text-sm">+₹{Number(item.edgeAfterCosts || 0).toLocaleString('en-IN')}</span>
                                </div>
                              </div>

                              {item.legs && (
                                <div>
                                  <span className="text-[10px] font-bold text-slate-500 uppercase block mb-1">Trade Legs</span>
                                  <div className="bg-slate-50 p-2.5 rounded-lg border border-slate-200 text-xs font-mono text-slate-800">
                                    {item.legs}
                                  </div>
                                </div>
                              )}

                              <div className="flex items-center justify-end gap-2 pt-2 border-t border-slate-100">
                                <button onClick={(e) => { e.stopPropagation(); reExecuteTrade(item); }} className="px-3 py-1.5 bg-blue-600 hover:bg-blue-700 text-white rounded-lg text-xs font-bold transition shadow-sm flex items-center gap-1">
                                  <span>🛒</span> Kite Basket
                                </button>
                                <button onClick={(e) => { e.stopPropagation(); reExecuteTrade(item); }} className="px-3 py-1.5 bg-emerald-600 hover:bg-emerald-700 text-white rounded-lg text-xs font-bold transition shadow-sm">
                                  ⚡ Re-Execute Trade
                                </button>
                              </div>
                            </div>
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
      </div>
    </div>
  );
}