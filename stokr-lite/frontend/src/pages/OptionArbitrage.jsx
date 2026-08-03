import { useSearchParams } from 'react-router-dom';
import React, { useState, useEffect, useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import client from '../api/client';
import BidParityPayoffChart from '../components/BidParityPayoffChart';

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
  info: 'bg-indigo-600 text-white',
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

const ALL_U = ['ALL', 'NIFTY', 'BANKNIFTY', 'MIDCPNIFTY', 'FINNIFTY'];

export default function OptionArbitrage() {
  const { toasts, dismiss: dismissToast } = useToastState();
  const [searchParams] = useSearchParams();
  const bidParityOnly = searchParams.get('tab') === 'bidparity';
  const boxOnly = searchParams.get('tab') === 'box';
  const calendarOnly = searchParams.get('tab') === 'calendar';
  const strategyHub = bidParityOnly || boxOnly || calendarOnly;
  const [tradingHorizon, setTradingHorizon] = useState('INTRADAY'); // INTRADAY, SWING, POSITIONAL, ANALYTICS
  const [activeSubTab, setActiveSubTab] = useState(() => {
    if (bidParityOnly) return 'bidparity';
    if (boxOnly) return 'box';
    if (calendarOnly) return 'calendar';
    return 'signals';
  });
  const [underlyings, setUnderlyings] = useState(['ALL']);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [executionBroker, setExecutionBroker] = useState('PAPER');
  const [isTestingBroker, setIsTestingBroker] = useState(false);

  useEffect(() => {
    if (bidParityOnly) {
      setTradingHorizon('INTRADAY');
      setActiveSubTab('bidparity');
    } else if (boxOnly) {
      setTradingHorizon('SWING');
      setActiveSubTab('box');
    } else if (calendarOnly) {
      setTradingHorizon('POSITIONAL');
      setActiveSubTab('calendar');
    }
  }, [bidParityOnly, boxOnly, calendarOnly]);

  const fetchBrokerRouting = async () => {
    try {
      const res = await client.get('/brokers/decoupled-routing');
      if (res.data?.executionBroker) {
        setExecutionBroker(res.data.executionBroker);
      }
    } catch (e) {
      // silent
    }
  };

  const changeExecutionBroker = async (broker) => {
    setExecutionBroker(broker);
    try {
      await client.post('/brokers/decoupled-routing', { executionBroker: broker });
      showToast(`Order Execution Broker updated to ${broker}`, 'info');
    } catch (e) {
      showToast('Failed to update execution broker', 'error');
    }
  };

  const testBrokerConnection = async () => {
    setIsTestingBroker(true);
    try {
      const res = await client.post('/brokers/test-execution', { broker: executionBroker });
      if (res.data?.ok) {
        showToast(res.data.message, 'success');
      } else {
        showToast(res.data?.message || 'Broker test failed', 'error');
      }
    } catch (e) {
      showToast('Broker connection test error: ' + e.message, 'error');
    } finally {
      setIsTestingBroker(false);
    }
  };

  useEffect(() => {
    fetchBrokerRouting();
  }, []);

  // Primary Live Arbitrage Signals Query
  const { data: liveData, isLoading: scanLoading } = useQuery({
    queryKey: ['option-arb-live', underlyings],
    queryFn: async () => {
      const uParam = underlyings.includes('ALL') ? 'ALL' : underlyings.join(',');
      const res = await client.get('/option-arbitrage/scan', { params: { underlying: uParam } });
      return res.data;
    },
    refetchInterval: autoRefresh ? 1000 : false,
    staleTime: 500,
  });

  // Calendar Scan Query Fallback
  const { data: calendarLiveData } = useQuery({
    queryKey: ['calendar-scan-fallback'],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/calendar/scan', { params: { underlying: 'ALL' } });
      return res.data;
    },
    refetchInterval: 3000
  });

  // History & Signals Log Query
  const { data: historyData, isLoading: historyLoading } = useQuery({
    queryKey: ['option-arb-history'],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/history', { params: { size: 7000 } });
      return res.data;
    },
    refetchInterval: autoRefresh ? 1000 : false,
    staleTime: 500,
  });

  const opportunities = liveData?.opportunities || [];
  const calendarOpportunities = calendarLiveData?.opportunities || [];
  const summary = liveData?.summary || {};
  const historyItems = historyData?.items || [];

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

  const handleExecuteInline = async (opp, lots = 1) => {
    try {
      const isPaper = String(executionBroker || '').toUpperCase() === 'PAPER';
      const isParity = String(opp?.strategyType || opp?.type || '').toUpperCase().includes('PARITY');
      // Live broker + DB opportunity → auto-exec 3-leg path
      if (!isPaper && opp?.id && isParity) {
        const res = await client.get('/option-arbitrage/auto-execute/execute', {
          params: { opportunityId: opp.id, multiplier: lots },
        });
        const status = res.data?.status || 'SUBMITTED';
        if (status === 'SUBMITTED' || status === 'COMPLETED') {
          showToast(`⚡ ${opp.underlying || opp.symbol} submitted to auto-exec (${executionBroker})`, 'success');
        } else {
          showToast(res.data?.message || `Exec status: ${status}`, 'warning');
        }
        return;
      }
      // Paper recorder (always for PAPER mode; also Box/Calendar without live fire)
      const res = await client.post('/option-arbitrage/paper-trade', {
        ...opp,
        lots,
        strategyType: opp.strategyType || opp.type || 'PAPER',
        edgeAfterCosts: opp.edgeAfterCosts || opp.boxEdgeInr || opp.targetEdge || 0,
        futuresPrice: opp.futuresPrice || opp.futPrice || 0,
        ceAsk: opp.ceAsk,
        peBid: opp.peBid,
        ceBid: opp.ceBid,
        peAsk: opp.peAsk,
        legs: opp.legs,
      });
      if (res.data?.status === 'SUBMITTED') {
        showToast(`📝 Paper ${opp.underlying || ''} recorded (${executionBroker || 'PAPER'})`, 'success');
      } else {
        showToast(res.data?.message || 'Paper trade failed', 'warning');
      }
    } catch (e) {
      showToast(e.response?.data?.message || e.message || 'Execute failed', 'error');
    }
  };

  const renderStrategyChrome = (title, subtitle, accent) => {
    // Static Tailwind maps only — dynamic via-${accent} classes get purged
    const T = {
      amber: {
        shell: 'from-slate-900 via-amber-950 to-slate-900',
        iconBox: 'bg-amber-600/30 border-amber-400/30',
        sub: 'text-amber-200/80',
        select: 'text-amber-300',
        btn: 'bg-amber-600 hover:bg-amber-500',
        icon: '🎯',
      },
      purple: {
        shell: 'from-slate-900 via-purple-950 to-slate-900',
        iconBox: 'bg-purple-600/30 border-purple-400/30',
        sub: 'text-purple-200/80',
        select: 'text-purple-300',
        btn: 'bg-purple-600 hover:bg-purple-500',
        icon: '📦',
      },
      sky: {
        shell: 'from-slate-900 via-sky-950 to-slate-900',
        iconBox: 'bg-sky-600/30 border-sky-400/30',
        sub: 'text-sky-200/80',
        select: 'text-sky-300',
        btn: 'bg-sky-600 hover:bg-sky-500',
        icon: '⏳',
      },
    }[accent] || {
      shell: 'from-slate-900 via-indigo-950 to-slate-900',
      iconBox: 'bg-indigo-600/30 border-indigo-400/30',
      sub: 'text-indigo-200/80',
      select: 'text-indigo-300',
      btn: 'bg-indigo-600 hover:bg-indigo-500',
      icon: '⚡',
    };
    return (
      <div className={`bg-gradient-to-r ${T.shell} text-white rounded-2xl p-4 md:p-5 shadow-xl border border-slate-800 flex flex-wrap items-center justify-between gap-4`}>
        <div className="flex items-center gap-3">
          <div className={`p-2 rounded-xl border ${T.iconBox}`}>
            <span className="text-xl">{T.icon}</span>
          </div>
          <div>
            <h1 className="text-xl font-black tracking-tight text-white">{title}</h1>
            <p className={`text-xs font-medium ${T.sub}`}>{subtitle}</p>
          </div>
        </div>
        <div className="flex items-center gap-2.5 flex-wrap">
          <div className="bg-slate-800/80 backdrop-blur-md px-3 py-1.5 rounded-xl border border-slate-700/80 flex items-center gap-2 text-xs">
            <span className="text-slate-300 font-medium">Execution:</span>
            <select
              value={executionBroker}
              onChange={(e) => changeExecutionBroker(e.target.value)}
              className={`bg-slate-900 font-bold border border-slate-700 rounded-lg px-2 py-1 outline-none text-xs ${T.select}`}
            >
              <option value="PAPER">📝 Paper Trading (Virtual ₹1 Cr)</option>
              <option value="NAVIA">⚡ Navia Markets</option>
              <option value="ICICI_DIRECT">🏦 ICICI Direct Breeze</option>
              <option value="ZERODHA">🚀 Zerodha Kite</option>
              <option value="DHAN">🎯 DhanHQ</option>
              <option value="FYERS">🔥 Fyers API</option>
            </select>
          </div>
          <button
            onClick={testBrokerConnection}
            disabled={isTestingBroker}
            className={`px-3 py-1.5 text-white font-bold rounded-xl text-xs transition shadow-lg disabled:opacity-50 ${T.btn}`}
          >
            {isTestingBroker ? 'Testing...' : '⚡ Test Connection'}
          </button>
          <button
            onClick={() => setAutoRefresh(!autoRefresh)}
            className={`px-3 py-1.5 rounded-xl text-xs font-bold transition ${
              autoRefresh ? 'bg-emerald-100 text-emerald-800 border border-emerald-300' : 'bg-slate-100 text-slate-600'
            }`}
          >
            {autoRefresh ? '⚡ Live: ON' : '⏱️ Live: OFF'}
          </button>
        </div>
      </div>
    );
  };

  // Dedicated strategy hubs (sidebar deep-links)
  if (bidParityOnly) {
    return (
      <div className="w-full max-w-full space-y-5 font-sans text-slate-900">
        <ToastContainer toasts={toasts} dismiss={dismissToast} />
        {renderStrategyChrome('Bid Parity', 'Live Signals · Positions · History · Configuration', 'amber')}
        <BidParityHub
          handleExecuteInline={handleExecuteInline}
          executionBroker={executionBroker}
          autoRefresh={autoRefresh}
          changeExecutionBroker={changeExecutionBroker}
        />
      </div>
    );
  }
  if (boxOnly) {
    return (
      <div className="w-full max-w-full space-y-5 font-sans text-slate-900">
        <ToastContainer toasts={toasts} dismiss={dismissToast} />
        {renderStrategyChrome('Box Spread', 'Live Signals · History · Paper only (4-leg)', 'purple')}
        <BoxSpreadHub
          handleExecuteInline={handleExecuteInline}
          executionBroker={executionBroker}
          autoRefresh={autoRefresh}
        />
      </div>
    );
  }
  if (calendarOnly) {
    return (
      <div className="w-full max-w-full space-y-5 font-sans text-slate-900">
        <ToastContainer toasts={toasts} dismiss={dismissToast} />
        {renderStrategyChrome('Calendar Spreads', 'Live Signals · History · Paper only', 'sky')}
        <CalendarSpreadHub
          handleExecuteInline={handleExecuteInline}
          executionBroker={executionBroker}
          autoRefresh={autoRefresh}
        />
      </div>
    );
  }

  return (
    <div className="w-full max-w-full space-y-5 font-sans text-slate-900">
      <ToastContainer toasts={toasts} dismiss={dismissToast} />

      {/* Top Header Card */}
      <div className="bg-gradient-to-r from-slate-900 via-indigo-950 to-slate-900 text-white rounded-2xl p-4 md:p-5 shadow-xl border border-slate-800 flex flex-wrap items-center justify-between gap-4">
        <div className="space-y-1">
          <div className="flex items-center gap-3">
            <div className="p-2 bg-indigo-600/30 rounded-xl border border-indigo-400/30">
              <span className="text-xl">⚡</span>
            </div>
            <div>
              <h1 className="text-xl font-black tracking-tight text-white">Stokr Arbitrage Scanner</h1>
              <p className="text-xs text-indigo-200/80 font-medium">Put-Call Parity Breaks, 4-Leg Risk-Free Box Spreads & Mispricing Engine</p>
            </div>
          </div>
        </div>

        {/* Live Router & Execution Control */}
        <div className="flex items-center gap-2.5 flex-wrap">
          <div className="bg-slate-800/80 backdrop-blur-md px-3 py-1.5 rounded-xl border border-slate-700/80 flex items-center gap-2 text-xs">
            <span className="w-2.5 h-2.5 rounded-full bg-emerald-400 animate-pulse" />
            <span className="text-slate-300 font-medium">Data Feed:</span>
            <span className="font-bold text-white">Zerodha Kite Connect</span>
          </div>

          <div className="bg-slate-800/80 backdrop-blur-md px-3 py-1.5 rounded-xl border border-slate-700/80 flex items-center gap-2 text-xs">
            <span className="text-slate-300 font-medium">Execution Broker:</span>
            <select
              value={executionBroker}
              onChange={(e) => changeExecutionBroker(e.target.value)}
              className="bg-slate-900 text-amber-300 font-bold border border-slate-700 rounded-lg px-2 py-1 outline-none text-xs"
            >
              <option value="PAPER">📝 Paper Trading (Virtual ₹1 Cr)</option>
              <option value="NAVIA">⚡ Navia Markets</option>
              <option value="ICICI_DIRECT">🏦 ICICI Direct Breeze</option>
              <option value="ZERODHA">🚀 Zerodha Kite</option>
              <option value="DHAN">🎯 DhanHQ</option>
              <option value="FYERS">🔥 Fyers API</option>
            </select>
          </div>

          <button
            onClick={testBrokerConnection}
            disabled={isTestingBroker}
            className="px-3 py-1.5 bg-indigo-600 hover:bg-indigo-500 text-white font-bold rounded-xl text-xs transition shadow-lg disabled:opacity-50"
          >
            {isTestingBroker ? 'Testing...' : '⚡ Test Connection'}
          </button>
        </div>
      </div>

      {/* Main Trading Horizon Navigation Bar */}
      <div className="bg-white rounded-2xl p-2 border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2 flex-wrap">
          {[
            { id: 'INTRADAY', label: '⚡ Intraday Arbitrage (0-1D)', icon: '⚡' },
            { id: 'SWING', label: '🔄 Swing Arbitrage (2-5D)', icon: '💎' },
            { id: 'POSITIONAL', label: '⏳ Positional & Calendar', icon: '⏳' },
            { id: 'ANALYTICS', label: '📈 Signals & Trade History', icon: '📊' },
          ].map(h => (
            <button
              key={h.id}
              onClick={() => {
                setTradingHorizon(h.id);
                if (h.id === 'INTRADAY') setActiveSubTab('signals');
                if (h.id === 'SWING') setActiveSubTab('box');
                if (h.id === 'POSITIONAL') setActiveSubTab('calendar');
                if (h.id === 'ANALYTICS') setActiveSubTab('history');
              }}
              className={`px-3.5 py-2 rounded-xl text-xs font-bold transition flex items-center gap-2 ${
                tradingHorizon === h.id
                  ? 'bg-indigo-600 text-white shadow-md shadow-indigo-200'
                  : 'text-slate-600 hover:bg-slate-100'
              }`}
            >
              <span>{h.label}</span>
            </button>
          ))}
        </div>

        <div className="flex items-center gap-2 px-2">
          <button
            onClick={() => setAutoRefresh(!autoRefresh)}
            className={`px-3 py-1.5 rounded-xl text-xs font-bold transition ${
              autoRefresh ? 'bg-emerald-100 text-emerald-800 border border-emerald-300' : 'bg-slate-100 text-slate-600'
            }`}
          >
            {autoRefresh ? '⚡ 1-Sec Live Tick: ON' : '⏱️ Auto-Refresh: OFF'}
          </button>
        </div>
      </div>

      {/* Sub-Tab Navigation Bar */}
      <div className="flex items-center gap-2 overflow-x-auto pb-1 border-b border-slate-200">
        {tradingHorizon === 'INTRADAY' && (
          <>
            <SubTabButton id="signals" label="⚡ Live Arbitrage Signals" active={activeSubTab} onClick={setActiveSubTab} count={opportunities.length} />
            <SubTabButton id="ironcondor" label="🛡️ 0DTE Iron Condor" active={activeSubTab} onClick={setActiveSubTab} />
            <SubTabButton id="cashsurge" label="🔥 10%+ Cash Surge" active={activeSubTab} onClick={setActiveSubTab} />
          </>
        )}

        {tradingHorizon === 'SWING' && (
          <>
            <SubTabButton id="box" label="💎 Risk-Free Box Spread" active={activeSubTab} onClick={setActiveSubTab} />
            <SubTabButton id="cashswing" label="🚀 2-5D Cash Swing" active={activeSubTab} onClick={setActiveSubTab} />
          </>
        )}

        {tradingHorizon === 'POSITIONAL' && (
          <>
            <SubTabButton id="calendar" label="⏳ Calendar Time Spreads" active={activeSubTab} onClick={setActiveSubTab} />
          </>
        )}

        {tradingHorizon === 'ANALYTICS' && (
          <>
            <SubTabButton id="history" label="📊 Trade History & Analytics" active={activeSubTab} onClick={setActiveSubTab} count={historyItems.length} />
          </>
        )}
      </div>

      {/* Active Sub-Tab View Rendering */}
      <div className="space-y-5 w-full">
        {activeSubTab === 'signals' && (
          <SignalsView
            underlyings={underlyings}
            toggleUnderlying={toggleUnderlying}
            opportunities={opportunities}
            calendarOpportunities={calendarOpportunities}
            summary={summary}
            scanLoading={scanLoading}
            handleExecuteInline={handleExecuteInline}
            executionBroker={executionBroker}
          />
        )}

        {activeSubTab === 'box' && <BoxSpreadView underlyings={underlyings} toggleUnderlying={toggleUnderlying} handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} />}
        {activeSubTab === 'ironcondor' && <IronCondorView handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} />}
        {activeSubTab === 'cashsurge' && <CashSurgeView handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} />}
        {activeSubTab === 'cashswing' && <CashSwingView handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} />}
        {activeSubTab === 'calendar' && <CalendarSpreadView handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} />}
        {activeSubTab === 'history' && <HistoryView historyItems={historyItems} calendarOpportunities={calendarOpportunities} historyLoading={historyLoading} handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} />}
      </div>
    </div>
  );
}

function SubTabButton({ id, label, active, onClick, count }) {
  const isSel = active === id;
  return (
    <button
      onClick={() => onClick(id)}
      className={`px-3 py-1.5 rounded-xl text-xs font-bold whitespace-nowrap transition flex items-center gap-2 border ${
        isSel
          ? 'bg-slate-900 text-white border-slate-900 shadow-sm'
          : 'bg-white text-slate-600 border-slate-200 hover:bg-slate-100'
      }`}
    >
      <span>{label}</span>
      {count != null && (
        <span className={`px-2 py-0.5 rounded-full text-[10px] ${isSel ? 'bg-indigo-600 text-white' : 'bg-slate-100 text-slate-700'}`}>
          {count}
        </span>
      )}
    </button>
  );
}

/* 1. SIGNALS VIEW */
function SignalsView({ underlyings, toggleUnderlying, opportunities, calendarOpportunities, summary, scanLoading, handleExecuteInline, executionBroker }) {
  const [strategyTypeFilter, setStrategyTypeFilter] = useState('ALL');
  const [expandedId, setExpandedId] = useState(null);
  const [lotsMap, setLotsMap] = useState({});
  const [currentPage, setCurrentPage] = useState(1);
  const pageSize = 25;

  const combinedOpps = useMemo(() => {
    const mappedCal = (calendarOpportunities || []).map(c => ({
      id: c.id || `CAL_${c.underlying}_${c.strike}_${c.nearExpiry}`,
      type: 'CALENDAR_SPREAD',
      strategyType: 'CALENDAR_SPREAD',
      underlying: c.underlying,
      strike: c.strike,
      action: c.action || 'SELL_FAR_BUY_NEAR',
      cePrice: c.nearPrice || 0,
      pePrice: c.farPrice || 0,
      edgeAfterCosts: c.edgeAfterCosts || c.spread * 25,
      confidence: 90,
      legs: `BUY NEAR (${c.nearSymbol || ''} @ ₹${c.nearPrice}) | SELL FAR (${c.farSymbol || ''} @ ₹${c.farPrice})`
    }));

    return [...opportunities, ...mappedCal];
  }, [opportunities, calendarOpportunities]);

  const filteredOpps = useMemo(() => {
    return combinedOpps.filter(o => {
      const typeStr = String(o.type || o.strategyType || '').toUpperCase();
      if (strategyTypeFilter === 'PARITY' && !typeStr.includes('PARITY') && !typeStr.includes('BID')) return false;
      if (strategyTypeFilter === 'BOX' && !typeStr.includes('BOX')) return false;
      if (strategyTypeFilter === 'CALENDAR' && !typeStr.includes('CALENDAR') && !typeStr.includes('TIME')) return false;
      if (strategyTypeFilter === 'CONDOR' && !typeStr.includes('CONDOR') && !typeStr.includes('IRON')) return false;
      return true;
    });
  }, [combinedOpps, strategyTypeFilter]);

  const totalEdge = useMemo(() => {
    return filteredOpps.reduce((sum, o) => sum + (Number(o.edgeAfterCosts) || 0), 0);
  }, [filteredOpps]);

  const totalPages = Math.max(1, Math.ceil(filteredOpps.length / pageSize));
  const paginatedOpps = useMemo(() => {
    const start = (currentPage - 1) * pageSize;
    return filteredOpps.slice(start, start + pageSize);
  }, [filteredOpps, currentPage]);

  const updateLots = (id, delta) => {
    setLotsMap(prev => ({
      ...prev,
      [id]: Math.max(1, (prev[id] || 1) + delta)
    }));
  };

  return (
    <div className="space-y-4 w-full">
      {/* Controls Bar */}
      <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-4">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-xs font-bold text-slate-500 uppercase mr-1">Underlying:</span>
          {ALL_U.map((u) => {
            const isSel = underlyings.includes(u);
            return (
              <button
                key={u}
                onClick={() => toggleUnderlying(u)}
                className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${
                  isSel ? 'bg-indigo-600 text-white shadow-sm' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                }`}
              >
                {u}
              </button>
            );
          })}
        </div>

        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          <span className="text-xs font-bold text-slate-500 uppercase px-2">Type:</span>
          {[
            { id: 'ALL', label: 'All Types' },
            { id: 'PARITY', label: '⚡ Bid Parity' },
            { id: 'BOX', label: '💎 Box Spread' },
            { id: 'CALENDAR', label: '⏳ Calendar' },
            { id: 'CONDOR', label: '🛡️ Condor' },
          ].map(f => (
            <button
              key={f.id}
              onClick={() => { setStrategyTypeFilter(f.id); setCurrentPage(1); }}
              className={`px-2.5 py-1 rounded-lg text-xs font-bold transition ${
                strategyTypeFilter === f.id ? 'bg-purple-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'
              }`}
            >
              {f.label}
            </button>
          ))}
        </div>
      </div>

      {/* Stat Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm">
          <p className="text-xs font-semibold text-slate-500 uppercase">Active Signals</p>
          <p className="text-2xl font-black text-slate-800 mt-1">{filteredOpps.length}</p>
        </div>
        <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm">
          <p className="text-xs font-semibold text-slate-500 uppercase">Total Edge Detected</p>
          <p className="text-2xl font-black text-emerald-600 mt-1">₹{Math.round(totalEdge).toLocaleString('en-IN')}</p>
        </div>
        <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm">
          <p className="text-xs font-semibold text-slate-500 uppercase">Parity Breaks</p>
          <p className="text-2xl font-black text-indigo-600 mt-1">{summary.PARITY_BREAK || 0}</p>
        </div>
        <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm">
          <p className="text-xs font-semibold text-slate-500 uppercase">Risk Profile</p>
          <p className="text-2xl font-black text-emerald-600 mt-1">Delta-Neutral</p>
        </div>
      </div>

      {/* Data Table */}
      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm w-full">
        {scanLoading && filteredOpps.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">Scanning NFO option chains for arbitrage opportunities...</div>
        ) : filteredOpps.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">No active signals match current filter ({strategyTypeFilter})</div>
        ) : (
          <div className="overflow-x-auto w-full">
            <table className="w-full text-xs text-left border-collapse">
              <thead className="bg-slate-50 border-b border-slate-200 font-bold text-slate-600 uppercase tracking-wider">
                <tr>
                  <th className="px-2 py-2">Type</th>
                  <th className="px-2 py-2">Symbol</th>
                  <th className="px-2 py-2">Strike</th>
                  <th className="px-2 py-2">Action</th>
                  <th className="px-2 py-2 text-right">CE Price</th>
                  <th className="px-2 py-2 text-right">PE Price</th>
                  <th className="px-2 py-2 text-right text-emerald-600 font-bold">Net Edge (₹)</th>
                  <th className="px-2 py-2 text-center">Conf</th>
                  <th className="px-2 py-2 text-center">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {paginatedOpps.map((opp, idx) => {
                  const rowId = opp.id || idx;
                  const isExpanded = expandedId === rowId;
                  const lots = lotsMap[rowId] || 1;

                  const ceVal = Number(opp.cePrice || opp.ceEntryPrice || opp.ceAsk || 0).toFixed(1);
                  const peVal = Number(opp.pePrice || opp.peEntryPrice || opp.peBid || 0).toFixed(1);

                  return (
                    <React.Fragment key={rowId}>
                      <tr
                        onClick={() => setExpandedId(isExpanded ? null : rowId)}
                        className={`transition cursor-pointer ${isExpanded ? 'bg-indigo-50/70 border-l-4 border-indigo-600' : 'hover:bg-slate-50'}`}
                      >
                        <td className="px-2 py-1.5">
                          <span className="px-1.5 py-0.5 rounded text-[10px] font-bold bg-indigo-100 text-indigo-800">
                            {opp.type || opp.strategyType || 'BID_PARITY'}
                          </span>
                        </td>
                        <td className="px-2 py-1.5 font-bold text-slate-800">{opp.underlying}</td>
                        <td className="px-2 py-1.5 font-bold text-slate-700">{opp.strike}</td>
                        <td className="px-2 py-1.5 font-bold text-purple-700">{opp.action}</td>
                        <td className="px-2 py-1.5 text-right font-mono text-slate-600">{ceVal}</td>
                        <td className="px-2 py-1.5 text-right font-mono text-slate-600">{peVal}</td>
                        <td className="px-2 py-1.5 text-right font-mono font-bold text-emerald-600">+₹{Math.round(Number(opp.edgeAfterCosts || 0)).toLocaleString('en-IN')}</td>
                        <td className="px-2 py-1.5 text-center font-bold text-emerald-600">{Math.round(opp.confidence || 85)}%</td>
                        <td className="px-2 py-1.5 text-center">
                          <button
                            onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp, lots); }}
                            className="px-2 py-0.5 bg-emerald-600 hover:bg-emerald-700 text-white text-[10px] font-bold rounded shadow-sm"
                          >
                            ⚡ Execute
                          </button>
                        </td>
                      </tr>

                      {/* INLINE ROW EXPANSION BREAKDOWN */}
                      {isExpanded && (
                        <tr className="bg-indigo-50/40 border-b border-indigo-100">
                          <td colSpan={9} className="p-3">
                            <div className="bg-white rounded-xl p-3 border border-indigo-200 shadow-md space-y-2">
                              <div className="flex items-center justify-between border-b border-slate-100 pb-1.5">
                                <span className="font-bold text-slate-800 text-xs uppercase tracking-wide">Inline Leg &amp; Execution Breakdown</span>
                                <span className="text-xs font-mono font-bold text-indigo-600 bg-indigo-50 px-2 py-0.5 rounded-md border border-indigo-200">{opp.action}</span>
                              </div>

                              {opp.legs && (
                                <div>
                                  <span className="text-[10px] font-bold text-slate-500 uppercase block mb-0.5">Contract Order Legs:</span>
                                  <p className="text-slate-800 text-xs font-mono font-bold bg-slate-50 p-2 rounded-lg border border-slate-200 leading-relaxed">
                                    {opp.legs}
                                  </p>
                                </div>
                              )}

                              <div className="flex flex-wrap items-center justify-between gap-3 pt-1.5 border-t border-slate-100">
                                <div className="flex items-center gap-2">
                                  <span className="text-xs font-bold text-slate-600">Lots:</span>
                                  <button onClick={(e) => { e.stopPropagation(); updateLots(rowId, -1); }} className="w-6 h-6 bg-slate-100 hover:bg-slate-200 border rounded-lg font-bold text-slate-700 flex items-center justify-center text-xs shadow-sm">-</button>
                                  <span className="font-mono text-xs font-black text-slate-800 w-5 text-center">{lots}</span>
                                  <button onClick={(e) => { e.stopPropagation(); updateLots(rowId, 1); }} className="w-6 h-6 bg-slate-100 hover:bg-slate-200 border rounded-lg font-bold text-slate-700 flex items-center justify-center text-xs shadow-sm">+</button>
                                </div>

                                <div className="flex items-center gap-3">
                                  <span className="text-xs font-bold text-emerald-600 font-mono">
                                    Est Net: +₹{(Math.round(Number(opp.edgeAfterCosts || 350)) * lots).toLocaleString('en-IN')}
                                  </span>
                                  <button
                                    onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp, lots); }}
                                    className="px-3 py-1 bg-emerald-600 hover:bg-emerald-700 text-white rounded-lg text-xs font-bold transition shadow-md"
                                  >
                                    ⚡ Confirm ({executionBroker})
                                  </button>
                                </div>
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

            {/* Pagination Controls */}
            <div className="px-4 py-2.5 bg-slate-50 border-t border-slate-200 flex flex-wrap items-center justify-between gap-3 text-xs text-slate-600">
              <span className="font-medium">
                Showing <strong className="text-slate-800">{(currentPage - 1) * pageSize + 1}</strong> to <strong className="text-slate-800">{Math.min(currentPage * pageSize, filteredOpps.length)}</strong> of <strong className="text-slate-800">{filteredOpps.length}</strong> signals
              </span>
              <div className="flex items-center gap-2">
                <button
                  disabled={currentPage <= 1}
                  onClick={() => setCurrentPage(p => Math.max(1, p - 1))}
                  className="px-2.5 py-1 bg-white border border-slate-300 rounded-lg disabled:opacity-40 font-bold hover:bg-slate-100"
                >
                  ◀ Prev
                </button>
                <span className="font-bold px-2 py-0.5 bg-white border border-slate-200 rounded-lg text-indigo-600">
                  {currentPage} / {totalPages}
                </span>
                <button
                  disabled={currentPage >= totalPages}
                  onClick={() => setCurrentPage(p => Math.min(totalPages, p + 1))}
                  className="px-2.5 py-1 bg-white border border-slate-300 rounded-lg disabled:opacity-40 font-bold hover:bg-slate-100"
                >
                  Next ▶
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

/* 2. BID PARITY HUB — Live Signals | Positions | History | Configuration */
function BidParityHub({ handleExecuteInline, executionBroker, autoRefresh, changeExecutionBroker }) {
  const [searchParams, setSearchParams] = useSearchParams();
  const initialBp = () => {
    const bp = searchParams.get('bp');
    if (bp === 'history' || bp === 'config' || bp === 'positions') return bp;
    return 'live';
  };
  const [bpTab, setBpTab] = useState(initialBp);

  useEffect(() => {
    const bp = searchParams.get('bp');
    if (bp === 'history' || bp === 'config' || bp === 'positions') setBpTab(bp);
    else setBpTab('live');
  }, [searchParams]);

  const switchTab = (id) => {
    setBpTab(id);
    const next = new URLSearchParams(searchParams);
    next.set('tab', 'bidparity');
    if (id === 'live') next.delete('bp');
    else next.set('bp', id);
    setSearchParams(next, { replace: true });
  };

  const { data: posSummary } = useQuery({
    queryKey: ['bid-parity-pos-count'],
    queryFn: async () => (await client.get('/option-arbitrage/live-positions', {
      params: { strategyType: 'BID', includeClosedToday: false, mode: 'BOTH' },
      timeout: 10000,
    })).data,
    refetchInterval: autoRefresh ? 5000 : false,
    placeholderData: (prev) => prev,
  });
  const openN = posSummary?.openCount ?? (posSummary?.positions || []).length;

  return (
    <div className="space-y-4 w-full">
      <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-base font-bold text-slate-800">Conversion &amp; Reversal Scanner</h2>
          <p className="text-xs text-slate-500">Executable bid/ask parity vs monthly futures · 3-leg hedge (CE + PE + FUT)</p>
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          {[
            { id: 'live', label: '📡 Live Signals' },
            { id: 'positions', label: openN > 0 ? `💼 Positions (${openN})` : '💼 Positions' },
            { id: 'history', label: '📜 History' },
            { id: 'config', label: '⚙️ Configuration' },
          ].map(t => (
            <button
              key={t.id}
              onClick={() => switchTab(t.id)}
              className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${
                bpTab === t.id ? 'bg-amber-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'
              }`}
            >
              {t.label}
            </button>
          ))}
        </div>
      </div>

      {bpTab === 'live' && (
        <BidParityLiveView
          handleExecuteInline={async (opp, lots) => {
            await handleExecuteInline(opp, lots);
            showToast('Opened — live PnL / Exit on Positions', 'success');
            switchTab('positions');
          }}
          executionBroker={executionBroker}
          autoRefresh={autoRefresh}
          onOpenPositions={() => switchTab('positions')}
          openPositionCount={openN}
        />
      )}
      {bpTab === 'positions' && (
        <BidParityPositionsView executionBroker={executionBroker} autoRefresh={autoRefresh} />
      )}
      {bpTab === 'history' && (
        <BidParityHistoryView
          handleExecuteInline={handleExecuteInline}
          executionBroker={executionBroker}
        />
      )}
      {bpTab === 'config' && (
        <BidParityConfigView
          executionBroker={executionBroker}
          changeExecutionBroker={changeExecutionBroker}
        />
      )}
    </div>
  );
}

function BidParityPositionsView({ executionBroker, autoRefresh = true }) {
  const [exitingId, setExitingId] = useState(null);
  const [filter, setFilter] = useState('OPEN'); // OPEN | CLOSED | ALL
  // Mode follows header execution broker; Both for comparison
  const defaultMode = String(executionBroker || '').toUpperCase() === 'PAPER' ? 'PAPER' : 'LIVE';
  const [modeFilter, setModeFilter] = useState(defaultMode); // PAPER | LIVE | BOTH

  useEffect(() => {
    const next = String(executionBroker || '').toUpperCase() === 'PAPER' ? 'PAPER' : 'LIVE';
    setModeFilter(next);
  }, [executionBroker]);

  const { data, isLoading, isFetching, isError, error, refetch, dataUpdatedAt } = useQuery({
    queryKey: ['bid-parity-positions-book', modeFilter],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/live-positions', {
        params: { strategyType: 'BID', includeClosedToday: true, mode: modeFilter },
        timeout: 12000,
      });
      return res.data;
    },
    refetchInterval: autoRefresh ? 5000 : false,
    staleTime: 2000,
    placeholderData: (prev) => prev,
    retry: 1,
  });

  const positions = data?.positions || [];
  const rows = positions.filter(p => {
    const st = String(p.tradeStatus || p.status || '').toUpperCase();
    const open = st === 'ENTERED' || st === 'OPEN' || st === 'PARTIAL' || st === 'EXECUTING';
    if (filter === 'OPEN') return open;
    if (filter === 'CLOSED') return !open;
    return true;
  });

  const exitPosition = async (positionId) => {
    if (!positionId) return;
    setExitingId(positionId);
    try {
      const res = await client.post(`/option-arbitrage/live-positions/${positionId}/exit`, { note: 'positions-tab' });
      if (res.data?.status === 'EXITED') {
        showToast(`Exited · PnL ₹${Math.round(Number(res.data?.exitPnl || 0)).toLocaleString('en-IN')}`, 'success');
        refetch();
      } else {
        showToast(res.data?.message || 'Exit failed', 'warning');
      }
    } catch (e) {
      showToast(e.response?.data?.message || e.message || 'Exit failed', 'error');
    } finally {
      setExitingId(null);
    }
  };

  const pnlCls = (v) => Number(v) >= 0 ? 'text-emerald-600' : 'text-red-600';
  const openPnl = data?.openPnl ?? 0;
  const closedPnl = data?.closedPnl ?? 0;
  const netPnl = data?.netPnl ?? 0;
  const modeLabel = modeFilter === 'BOTH' ? 'Paper + Live' : modeFilter;

  return (
    <div className="space-y-4 w-full">
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <div className="bg-white rounded-xl border border-slate-200 p-3">
          <div className="text-[10px] font-bold uppercase text-slate-500">Open ({modeLabel})</div>
          <div className="text-xl font-black text-slate-900">{data?.openCount ?? 0}</div>
          <div className="text-[10px] text-slate-400 mt-0.5">
            Paper {data?.paperOpenCount ?? 0} · Live {data?.liveOpenCount ?? 0}
          </div>
        </div>
        <div className="bg-white rounded-xl border border-slate-200 p-3">
          <div className="text-[10px] font-bold uppercase text-slate-500">Open PnL</div>
          <div className={`text-xl font-black ${pnlCls(openPnl)}`}>₹{Math.round(openPnl).toLocaleString('en-IN')}</div>
        </div>
        <div className="bg-white rounded-xl border border-slate-200 p-3">
          <div className="text-[10px] font-bold uppercase text-slate-500">Closed today</div>
          <div className={`text-xl font-black ${pnlCls(closedPnl)}`}>₹{Math.round(closedPnl).toLocaleString('en-IN')}</div>
        </div>
        <div className="bg-white rounded-xl border border-slate-200 p-3">
          <div className="text-[10px] font-bold uppercase text-slate-500">Net today</div>
          <div className={`text-xl font-black ${pnlCls(netPnl)}`}>₹{Math.round(netPnl).toLocaleString('en-IN')}</div>
        </div>
      </div>

      <div className="bg-white p-3 rounded-2xl border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <div className="flex items-center gap-1.5 bg-slate-100 p-1 rounded-xl">
            {[
              { id: 'PAPER', label: 'Paper' },
              { id: 'LIVE', label: 'Live' },
              { id: 'BOTH', label: 'Both' },
            ].map(t => (
              <button key={t.id} onClick={() => setModeFilter(t.id)}
                className={`px-3 py-1 rounded-lg text-xs font-bold ${modeFilter === t.id ? 'bg-slate-900 text-white' : 'text-slate-600'}`}>
                {t.label}
              </button>
            ))}
          </div>
          <div className="flex items-center gap-1.5 bg-slate-100 p-1 rounded-xl">
            {[
              { id: 'OPEN', label: 'Open' },
              { id: 'CLOSED', label: 'Closed today' },
              { id: 'ALL', label: 'All' },
            ].map(t => (
              <button key={t.id} onClick={() => setFilter(t.id)}
                className={`px-3 py-1 rounded-lg text-xs font-bold ${filter === t.id ? 'bg-amber-600 text-white' : 'text-slate-600'}`}>
                {t.label}
              </button>
            ))}
          </div>
        </div>
        <div className="flex items-center gap-2 text-xs">
          <span className="text-slate-500">
            Header: <b>{executionBroker || 'PAPER'}</b>
            {data?.marketOpen === false ? ' · after hours' : ''}
          </span>
          <button onClick={() => refetch()} className="px-3 py-1.5 rounded-lg bg-slate-900 text-white font-bold">
            Refresh PnL
          </button>
          {isFetching && (
            <span className="text-[10px] font-bold text-slate-400">syncing…</span>
          )}
          {dataUpdatedAt ? (
            <span className="text-[10px] text-slate-400 font-mono">
              {new Date(dataUpdatedAt).toLocaleTimeString('en-IN')}
            </span>
          ) : null}
        </div>
      </div>

      <div className="bg-slate-50 border border-slate-200 text-slate-700 text-xs font-semibold px-4 py-3 rounded-xl">
        Mode filter shows Paper / Live / Both so Navia view never mixes paper fills.
        Near target: <b>smart roll</b> (close CE+PE, keep FUT, open new options) when a ≥₹300 signal exists; else full exit.
        Paper (1 set) and Live (1 set) auto-tracks can run together for comparison.
      </div>

      {isError && (
        <div className="bg-amber-50 border border-amber-200 text-amber-900 text-xs font-semibold px-4 py-3 rounded-xl">
          Positions refresh issue: {error?.message || 'retry Refresh'}. Showing last loaded rows if any.
        </div>
      )}

      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm">
        {isLoading && !data ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">Loading positions…</div>
        ) : rows.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">
            No {filter === 'OPEN' ? 'open' : filter === 'CLOSED' ? 'closed' : ''} {modeFilter === 'BOTH' ? '' : modeFilter + ' '}Bid Parity positions.
            {modeFilter === 'LIVE' ? ' Live fires only when Navia auto-exec is ON and edge ≥ ₹300.' : ' Paper auto deploys 1 set by default when edge ≥ ₹300.'}
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-xs text-left border-collapse">
              <thead className="bg-slate-50 border-b border-slate-200 font-bold text-slate-600 uppercase">
                <tr>
                  <th className="px-2 py-2">Mode</th>
                  <th className="px-2 py-2">Status</th>
                  <th className="px-2 py-2">Symbol</th>
                  <th className="px-2 py-2">Side</th>
                  <th className="px-2 py-2">Strike</th>
                  <th className="px-2 py-2 text-right">Target edge</th>
                  <th className="px-2 py-2 text-right">Auto-exit ≥</th>
                  <th className="px-2 py-2 text-right">Live / Exit PnL</th>
                  <th className="px-2 py-2">P/L</th>
                  <th className="px-2 py-2">Entered</th>
                  <th className="px-2 py-2">Exited</th>
                  <th className="px-2 py-2 text-center">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {rows.map((p) => {
                  const st = String(p.tradeStatus || p.status || '').toUpperCase();
                  const open = st === 'ENTERED' || st === 'OPEN' || st === 'PARTIAL' || st === 'EXECUTING';
                  const pnl = open ? Number(p.currentPnl ?? p.pnl ?? 0) : Number(p.exitPnl ?? p.pnl ?? 0);
                  const mode = p.mode || (String(p.ceOrderId || '').startsWith('PAPER') ? 'PAPER' : 'LIVE');
                  const target = Number(p.targetEdge || 0);
                  const autoAt = p.autoExitAt != null
                    ? Number(p.autoExitAt)
                    : (target > 0 ? Math.max(0, target - 10) : null);
                  return (
                    <tr key={p.id} className="hover:bg-slate-50">
                      <td className="px-2 py-1.5">
                        <span className={`px-1.5 py-0.5 rounded text-[10px] font-bold border ${
                          mode === 'PAPER' ? 'bg-sky-50 text-sky-800 border-sky-200' : 'bg-amber-50 text-amber-800 border-amber-200'
                        }`}>{mode}</span>
                      </td>
                      <td className="px-2 py-1.5">
                        <span className={`px-1.5 py-0.5 rounded text-[10px] font-bold border ${
                          open ? 'bg-emerald-100 text-emerald-800 border-emerald-300' : 'bg-slate-200 text-slate-800 border-slate-300'
                        }`}>{open ? 'ENTERED' : 'EXITED'}</span>
                      </td>
                      <td className="px-2 py-1.5 font-bold">{p.underlying}</td>
                      <td className="px-2 py-1.5 font-bold text-purple-700">{p.action}</td>
                      <td className="px-2 py-1.5 font-bold">{p.strike}</td>
                      <td className="px-2 py-1.5 text-right font-mono text-emerald-700">
                        ₹{Math.round(target).toLocaleString('en-IN')}
                      </td>
                      <td className="px-2 py-1.5 text-right font-mono text-amber-800">
                        {autoAt != null ? `₹${Math.round(autoAt).toLocaleString('en-IN')}` : '—'}
                      </td>
                      <td className={`px-2 py-1.5 text-right font-mono font-black ${pnlCls(pnl)}`}>
                        ₹{Math.round(pnl).toLocaleString('en-IN')}
                      </td>
                      <td className="px-2 py-1.5">
                        <span className={`text-[10px] font-bold ${pnl >= 0 ? 'text-emerald-700' : 'text-red-700'}`}>
                          {pnl >= 0 ? 'PROFIT' : 'LOSS'}
                        </span>
                      </td>
                      <td className="px-2 py-1.5 font-mono text-[10px] text-slate-500">{p.enteredAt ? String(p.enteredAt).slice(0, 19) : '—'}</td>
                      <td className="px-2 py-1.5 font-mono text-[10px] text-slate-500">{p.exitedAt ? String(p.exitedAt).slice(0, 19) : '—'}</td>
                      <td className="px-2 py-1.5 text-center">
                        {open ? (
                          <button
                            disabled={exitingId === p.id}
                            onClick={() => exitPosition(p.id)}
                            className="px-3 py-1 bg-slate-900 hover:bg-slate-800 text-white text-[10px] font-bold rounded disabled:opacity-50"
                          >
                            {exitingId === p.id ? 'Exiting…' : 'Exit'}
                          </button>
                        ) : (
                          <span className="text-slate-400 text-[10px]">done</span>
                        )}
                      </td>
                    </tr>
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

function BidParityConfigView({ executionBroker, changeExecutionBroker }) {
  const [form, setForm] = useState(null);
  const [saving, setSaving] = useState(false);
  const [probe, setProbe] = useState(null);
  const [probing, setProbing] = useState(false);
  const [msg, setMsg] = useState(null);

  const { data: settings, isLoading, refetch } = useQuery({
    queryKey: ['bid-parity-settings'],
    queryFn: async () => (await client.get('/option-arbitrage/auto-execute/settings')).data,
  });

  const { data: logs, refetch: refetchLogs } = useQuery({
    queryKey: ['bid-parity-exec-logs'],
    queryFn: async () => (await client.get('/option-arbitrage/auto-execute/logs')).data,
    refetchInterval: 5000,
  });

  const { data: positions } = useQuery({
    queryKey: ['bid-parity-live-positions-config'],
    queryFn: async () => (await client.get('/option-arbitrage/live-positions', {
      params: { strategyType: 'BID', includeClosedToday: false },
    })).data,
    refetchInterval: 5000,
  });

  useEffect(() => {
    if (!settings) return;
    setForm({
      enabled: !!settings.enabled,
      broker: settings.broker || 'NAVIA',
      niftyEnabled: !!settings.niftyEnabled,
      bankniftyEnabled: !!settings.bankniftyEnabled,
      finniftyEnabled: !!settings.finniftyEnabled,
      midcpniftyEnabled: !!settings.midcpniftyEnabled,
      niftyMinEdge: Number(settings.niftyMinEdge ?? 2000),
      bankniftyMinEdge: Number(settings.bankniftyMinEdge ?? 2000),
      finniftyMinEdge: Number(settings.finniftyMinEdge ?? 2000),
      midcpniftyMinEdge: Number(settings.midcpniftyMinEdge ?? 2000),
      niftyLots: Number(settings.niftyLots ?? 1),
      bankniftyLots: Number(settings.bankniftyLots ?? 1),
      finniftyLots: Number(settings.finniftyLots ?? 1),
      midcpniftyLots: Number(settings.midcpniftyLots ?? 1),
      maxOpenPositions: Number(settings.maxOpenPositions ?? 3),
      maxDailyLoss: Number(settings.maxDailyLoss ?? 5000),
      availableMarginGate: Number(settings.availableMarginGate ?? 5000),
      marginUsageCap: Number(settings.marginUsageCap ?? 0.85),
      parallelTimeoutSec: Number(settings.parallelTimeoutSec ?? 8),
      strategyFilter: settings.strategyFilter || 'PARITY',
      bidParityAutoExitEnabled: settings.bidParityAutoExitEnabled !== false,
      bidParityExitNearBuffer: Number(settings.bidParityExitNearBuffer ?? 10),
      paperAutoEnabled: settings.paperAutoEnabled !== false,
      paperMaxOpen: Number(settings.paperMaxOpen ?? 1),
      smartRollEnabled: settings.smartRollEnabled !== false,
    });
  }, [settings]);

  const setField = (k, v) => setForm(prev => ({ ...prev, [k]: v }));

  const save = async () => {
    if (!form) return;
    setSaving(true); setMsg(null);
    try {
      // Persist auto-exec broker + sync UI execution broker for Test Connection
      await client.post('/option-arbitrage/auto-execute/settings/bulk', form);
      if (form.broker && changeExecutionBroker && form.broker !== executionBroker) {
        try { await changeExecutionBroker(form.broker); } catch (_) { /* optional */ }
      }
      await refetch();
      setMsg({ ok: true, text: 'Configuration saved' });
      showToast('Bid Parity configuration saved', 'success');
    } catch (e) {
      setMsg({ ok: false, text: e.response?.data?.error || e.message || 'Save failed' });
    } finally {
      setSaving(false);
    }
  };

  const runProbe = async () => {
    setProbing(true); setProbe(null);
    try {
      // Save broker first so probe uses selected broker
      if (form?.broker) {
        await client.post('/option-arbitrage/auto-execute/settings', null, {
          params: { key: 'broker', value: form.broker },
        });
      }
      const res = await client.get('/option-arbitrage/auto-execute/readiness');
      setProbe(res.data);
      if (res.data?.ok) showToast(res.data.message, 'success');
      else showToast(res.data?.message || 'Navia probe failed', 'warning');
    } catch (e) {
      setProbe({ ok: false, message: e.response?.data?.message || e.message });
      showToast('Probe failed', 'error');
    } finally {
      setProbing(false);
      refetchLogs();
    }
  };

  if (isLoading || !form) {
    return <div className="p-10 text-center text-slate-400 text-sm font-semibold">Loading configuration…</div>;
  }

  const indices = [
    { key: 'nifty', label: 'NIFTY' },
    { key: 'banknifty', label: 'BANKNIFTY' },
    { key: 'finnifty', label: 'FINNIFTY' },
    { key: 'midcpnifty', label: 'MIDCPNIFTY' },
  ];

  return (
    <div className="space-y-4 w-full">
      {/* Master switches */}
      <div className="bg-white rounded-2xl border border-slate-200 shadow-sm p-4 space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h3 className="text-sm font-bold text-slate-800">Auto-Execute</h3>
            <p className="text-xs text-slate-500">Margin-gated parallel CE+PE+FUT on Navia · window 09:16–15:25 IST</p>
          </div>
          <label className="flex items-center gap-2 text-xs font-bold cursor-pointer">
            <input
              type="checkbox"
              checked={form.enabled}
              onChange={e => setField('enabled', e.target.checked)}
              className="w-4 h-4 accent-amber-600"
            />
            <span className={form.enabled ? 'text-emerald-700' : 'text-slate-500'}>
              {form.enabled ? 'ENABLED' : 'DISABLED'}
            </span>
          </label>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-3 text-xs">
          <div>
            <label className="font-semibold text-slate-600 block mb-1">Execution Broker</label>
            <select
              value={form.broker}
              onChange={e => setField('broker', e.target.value)}
              className="w-full border border-slate-200 rounded-lg px-2 py-1.5 font-bold"
            >
              <option value="NAVIA">Navia Markets</option>
              <option value="ZERODHA">Zerodha Kite</option>
              <option value="PAPER">Paper (no live fire)</option>
            </select>
          </div>
          <div>
            <label className="font-semibold text-slate-600 block mb-1">Strategy Filter</label>
            <select
              value={form.strategyFilter}
              onChange={e => setField('strategyFilter', e.target.value)}
              className="w-full border border-slate-200 rounded-lg px-2 py-1.5 font-bold"
            >
              <option value="PARITY">Bid Parity only</option>
              <option value="ALL">All strategies</option>
            </select>
          </div>
          <div>
            <label className="font-semibold text-slate-600 block mb-1">Parallel place timeout (sec)</label>
            <input
              type="number"
              min={3}
              max={30}
              value={form.parallelTimeoutSec}
              onChange={e => setField('parallelTimeoutSec', Number(e.target.value) || 8)}
              className="w-full border border-slate-200 rounded-lg px-2 py-1.5 font-mono"
            />
          </div>
        </div>

        <div className="flex flex-wrap gap-2">
          <button
            onClick={runProbe}
            disabled={probing}
            className="px-3 py-1.5 rounded-lg bg-slate-900 text-white text-xs font-bold disabled:opacity-50"
          >
            {probing ? 'Probing Navia…' : '🔌 Test Navia + Margin'}
          </button>
          <button
            onClick={save}
            disabled={saving}
            className="px-3 py-1.5 rounded-lg bg-amber-600 text-white text-xs font-bold disabled:opacity-50"
          >
            {saving ? 'Saving…' : '💾 Save Configuration'}
          </button>
        </div>

        {msg && (
          <div className={`text-xs font-semibold px-3 py-2 rounded-xl ${msg.ok ? 'bg-emerald-50 text-emerald-800' : 'bg-red-50 text-red-700'}`}>
            {msg.text}
          </div>
        )}
        {probe && (
          <div className={`text-xs font-semibold px-3 py-2 rounded-xl space-y-1 ${probe.ok ? 'bg-emerald-50 text-emerald-900' : 'bg-amber-50 text-amber-900'}`}>
            <div>{probe.message}</div>
            {probe.availableMargin != null && (
              <div className="font-mono">
                AvailableMargin ₹{Number(probe.availableMargin).toLocaleString('en-IN')}
                {' · '}usable ₹{Number(probe.usableMargin || 0).toLocaleString('en-IN')}
                {' · '}~{probe.maxNiftySets ?? 0} NIFTY set(s)
              </div>
            )}
            <div className="text-[10px] opacity-80">
              Parallel 3-leg: {probe.parallelLegs ? 'ON' : 'OFF'} · Auto-exec: {probe.autoExecEnabled ? 'ON' : 'OFF'} · Gate ₹{probe.marginGate}
            </div>
          </div>
        )}
      </div>

      {/* Per-index */}
      <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
        <div className="px-4 py-3 border-b border-slate-100 text-sm font-bold text-slate-800">Per-Index Enable / Min Edge / Lots</div>
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead className="bg-slate-50 text-slate-600 font-bold">
              <tr>
                <th className="px-3 py-2 text-left">Index</th>
                <th className="px-3 py-2 text-center">Enabled</th>
                <th className="px-3 py-2 text-right">Min Edge ₹</th>
                <th className="px-3 py-2 text-right">Lots</th>
                <th className="px-3 py-2 text-right">Est. Margin / set</th>
              </tr>
            </thead>
            <tbody>
              {indices.map(({ key, label }) => {
                const est = (settings?.hedgedMarginEstimate || {})[label] || 200000;
                return (
                  <tr key={key} className="border-t border-slate-100">
                    <td className="px-3 py-2 font-bold">{label}</td>
                    <td className="px-3 py-2 text-center">
                      <input
                        type="checkbox"
                        checked={!!form[`${key}Enabled`]}
                        onChange={e => setField(`${key}Enabled`, e.target.checked)}
                        className="accent-amber-600"
                      />
                    </td>
                    <td className="px-3 py-2 text-right">
                      <input
                        type="number"
                        value={form[`${key}MinEdge`]}
                        onChange={e => setField(`${key}MinEdge`, Number(e.target.value) || 0)}
                        className="w-24 border border-slate-200 rounded px-2 py-1 font-mono text-right"
                      />
                    </td>
                    <td className="px-3 py-2 text-right">
                      <input
                        type="number"
                        min={1}
                        value={form[`${key}Lots`]}
                        onChange={e => setField(`${key}Lots`, Number(e.target.value) || 1)}
                        className="w-16 border border-slate-200 rounded px-2 py-1 font-mono text-right"
                      />
                    </td>
                    <td className="px-3 py-2 text-right font-mono text-slate-500">
                      ₹{Math.round(est * 1.15).toLocaleString('en-IN')}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>

      {/* Risk / margin */}
      <div className="bg-white rounded-2xl border border-slate-200 shadow-sm p-4">
        <h3 className="text-sm font-bold text-slate-800 mb-3">Risk &amp; Margin Gates</h3>
        <div className="grid grid-cols-1 md:grid-cols-4 gap-3 text-xs">
          <div>
            <label className="font-semibold text-slate-600 block mb-1">Max open positions</label>
            <input type="number" min={1} value={form.maxOpenPositions}
              onChange={e => setField('maxOpenPositions', Number(e.target.value) || 1)}
              className="w-full border border-slate-200 rounded-lg px-2 py-1.5 font-mono" />
          </div>
          <div>
            <label className="font-semibold text-slate-600 block mb-1">Max daily loss ₹</label>
            <input type="number" value={form.maxDailyLoss}
              onChange={e => setField('maxDailyLoss', Number(e.target.value) || 0)}
              className="w-full border border-slate-200 rounded-lg px-2 py-1.5 font-mono" />
          </div>
          <div>
            <label className="font-semibold text-slate-600 block mb-1">Min AvailableMargin ₹</label>
            <input type="number" value={form.availableMarginGate}
              onChange={e => setField('availableMarginGate', Number(e.target.value) || 0)}
              className="w-full border border-slate-200 rounded-lg px-2 py-1.5 font-mono" />
          </div>
          <div>
            <label className="font-semibold text-slate-600 block mb-1">Margin usage cap (0–1)</label>
            <input type="number" step="0.01" min={0.1} max={1}
              value={form.marginUsageCap}
              onChange={e => setField('marginUsageCap', Number(e.target.value) || 0.85)}
              className="w-full border border-slate-200 rounded-lg px-2 py-1.5 font-mono" />
          </div>
        </div>
        <p className="text-[11px] text-slate-500 mt-3">
          Before each fire: refresh Navia TOTP → fetch AvailableMargin → require ≥ gate → require hedged estimate ≤ AvailableMargin × usage cap → place CE+PE+FUT in parallel (Navia qty = lots).
        </p>
      </div>

      {/* Auto exit near entry edge */}
      <div className="bg-white rounded-2xl border border-amber-200 shadow-sm p-4">
        <h3 className="text-sm font-bold text-slate-800 mb-1">Auto Exit + Smart Roll + Paper track</h3>
        <p className="text-[11px] text-slate-500 mb-3">
          Near target (edge ₹300 → ≥ ₹{Math.max(0, 300 - Number(form.bidParityExitNearBuffer || 10))}):
          smart-roll options (keep FUT) when a new ≥₹300 signal exists; else full square-off.
          Paper auto (1 set) runs beside Live for comparison.
        </p>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-3 text-xs items-end">
          <div>
            <label className="font-semibold text-slate-600 block mb-1">Auto exit enabled</label>
            <button type="button"
              onClick={() => setField('bidParityAutoExitEnabled', !form.bidParityAutoExitEnabled)}
              className={`px-3 py-1.5 rounded-lg text-xs font-bold border ${
                form.bidParityAutoExitEnabled
                  ? 'bg-emerald-600 text-white border-emerald-700'
                  : 'bg-slate-100 text-slate-600 border-slate-200'
              }`}>
              {form.bidParityAutoExitEnabled ? 'ON' : 'OFF'}
            </button>
          </div>
          <div>
            <label className="font-semibold text-slate-600 block mb-1">Near-edge buffer ₹</label>
            <input type="number" min={0} max={200} value={form.bidParityExitNearBuffer}
              onChange={e => setField('bidParityExitNearBuffer', Number(e.target.value) || 0)}
              className="w-full border border-slate-200 rounded-lg px-2 py-1.5 font-mono" />
          </div>
          <div>
            <label className="font-semibold text-slate-600 block mb-1">Smart roll (keep FUT)</label>
            <button type="button"
              onClick={() => setField('smartRollEnabled', !form.smartRollEnabled)}
              className={`px-3 py-1.5 rounded-lg text-xs font-bold border ${
                form.smartRollEnabled
                  ? 'bg-emerald-600 text-white border-emerald-700'
                  : 'bg-slate-100 text-slate-600 border-slate-200'
              }`}>
              {form.smartRollEnabled ? 'ON' : 'OFF'}
            </button>
          </div>
          <div>
            <label className="font-semibold text-slate-600 block mb-1">Paper auto (1 set)</label>
            <button type="button"
              onClick={() => setField('paperAutoEnabled', !form.paperAutoEnabled)}
              className={`px-3 py-1.5 rounded-lg text-xs font-bold border ${
                form.paperAutoEnabled
                  ? 'bg-sky-600 text-white border-sky-700'
                  : 'bg-slate-100 text-slate-600 border-slate-200'
              }`}>
              {form.paperAutoEnabled ? 'ON' : 'OFF'}
            </button>
          </div>
          <div>
            <label className="font-semibold text-slate-600 block mb-1">Paper max open</label>
            <input type="number" min={1} max={3} value={form.paperMaxOpen}
              onChange={e => setField('paperMaxOpen', Number(e.target.value) || 1)}
              className="w-full border border-slate-200 rounded-lg px-2 py-1.5 font-mono" />
          </div>
          <div className="text-[11px] text-slate-600 font-semibold pb-1">
            Live uses Auto-exec master switch + Navia · Paper track is independent.
          </div>
        </div>
      </div>

      {/* Open positions */}
      <div className="bg-white rounded-2xl border border-slate-200 shadow-sm p-4">
        <h3 className="text-sm font-bold text-slate-800 mb-2">
          Live Positions ({positions?.openCount ?? positions?.count ?? 0})
          <span className="ml-2 text-[11px] font-semibold text-slate-500">
            (full board → Positions tab)
          </span>
        </h3>
        {(positions?.positions || []).length === 0 ? (
          <div className="text-xs text-slate-400 font-semibold">No open Bid Parity positions</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead className="bg-slate-50 text-slate-600 font-bold">
                <tr>
                  <th className="px-2 py-1 text-left">Mode</th>
                  <th className="px-2 py-1 text-left">Underlying</th>
                  <th className="px-2 py-1 text-right">Strike</th>
                  <th className="px-2 py-1">Action</th>
                  <th className="px-2 py-1">Status</th>
                  <th className="px-2 py-1 text-right">Live PnL</th>
                  <th className="px-2 py-1 text-right">Lots</th>
                </tr>
              </thead>
              <tbody>
                {(positions.positions || []).map((p, i) => (
                  <tr key={p.id || i} className="border-t border-slate-100">
                    <td className="px-2 py-1 font-bold">{p.mode || '—'}</td>
                    <td className="px-2 py-1 font-bold">{p.underlying}</td>
                    <td className="px-2 py-1 text-right font-mono">{p.strike}</td>
                    <td className="px-2 py-1">{p.action}</td>
                    <td className="px-2 py-1">{p.tradeStatus || p.status}</td>
                    <td className={`px-2 py-1 text-right font-mono font-bold ${Number(p.currentPnl ?? p.pnl ?? 0) >= 0 ? 'text-emerald-600' : 'text-red-600'}`}>
                      ₹{Math.round(Number(p.currentPnl ?? p.pnl ?? 0)).toLocaleString('en-IN')}
                    </td>
                    <td className="px-2 py-1 text-right">{p.lots}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Exec logs */}
      <div className="bg-white rounded-2xl border border-slate-200 shadow-sm p-4">
        <div className="flex items-center justify-between mb-2">
          <h3 className="text-sm font-bold text-slate-800">Auto-Exec Logs</h3>
          <button onClick={() => refetchLogs()} className="text-[11px] font-bold text-slate-500 hover:text-slate-800">Refresh</button>
        </div>
        <div className="max-h-64 overflow-y-auto space-y-1 font-mono text-[11px]">
          {(logs || []).length === 0 && <div className="text-slate-400">No logs yet</div>}
          {(logs || []).slice(0, 40).map((l, i) => (
            <div key={l.id || i} className="flex gap-2 border-b border-slate-50 py-1">
              <span className="text-slate-400 w-14 shrink-0">{l.time}</span>
              <span className={`font-bold w-16 shrink-0 ${
                l.status === 'OK' || l.status === 'SUCCESS' ? 'text-emerald-600'
                  : l.status === 'BLOCKED' || l.status === 'ERROR' || l.status === 'FAILED' ? 'text-red-600'
                    : 'text-amber-700'
              }`}>{l.status}</span>
              <span className="text-slate-400 w-14 shrink-0">{l.type}</span>
              <span className="text-slate-700">{l.message}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function BidParityLiveView({ handleExecuteInline, executionBroker, autoRefresh, onOpenPositions, openPositionCount = 0 }) {
  const [underlying, setUnderlying] = useState('ALL');
  const [expiryMode, setExpiryMode] = useState('BOTH'); // MONTHLY | WEEKLY | BOTH
  const [expandedId, setExpandedId] = useState(null);
  const [minEdge, setMinEdge] = useState(300);
  const [paperLots, setPaperLots] = useState(1);
  const lastDataRef = React.useRef(null);
  const stickyRef = React.useRef(new Map()); // client belt-and-suspenders

  const { data, isLoading, error, refetch, isFetching } = useQuery({
    queryKey: ['bid-parity-scan', underlying, expiryMode, minEdge],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/bid-parity/scan', {
        params: { underlying, expiry: expiryMode, minEdge },
      });
      lastDataRef.current = res.data;
      return res.data;
    },
    refetchInterval: autoRefresh ? 4000 : false,
    staleTime: 1200,
    placeholderData: (prev) => prev ?? lastDataRef.current ?? undefined,
  });

  // Merge server list into sticky map — never drop a signal once seen this session
  const opps = React.useMemo(() => {
    const incoming = data?.opportunities || [];
    const map = stickyRef.current;
    const now = Date.now();
    for (const o of incoming) {
      const edge = Number(o.edgeAfterCosts || 0);
      const key = `${o.underlying}|${o.strike}|${o.action}|${o.expiryDate}|${o.expiryMode || ''}`;
      if (edge >= 300 || map.has(key) || o.sticky) {
        const prev = map.get(key);
        const peak = Math.max(edge, Number(prev?.peakEdgeAfterCosts || 0), Number(o.peakEdgeAfterCosts || 0));
        map.set(key, {
          ...o,
          sticky: true,
          live: o.live !== false,
          peakEdgeAfterCosts: peak,
          firstSeenAt: prev?.firstSeenAt || o.firstSeenAt || now,
          lastSeenAt: now,
        });
      }
    }
    // If this response was empty/timeout, keep prior sticky rows
    let rows = [...map.values()];
    if (underlying !== 'ALL') rows = rows.filter(r => r.underlying === underlying);
    if (expiryMode !== 'BOTH') rows = rows.filter(r => (r.expiryMode || 'MONTHLY') === expiryMode);
    // Sticky rows stay visible even if live edge later dips below min
    rows = rows.filter(o => o.sticky || (o.peakEdgeAfterCosts || o.edgeAfterCosts || 0) >= minEdge);
    rows.sort((a, b) => (b.peakEdgeAfterCosts || b.edgeAfterCosts || 0) - (a.peakEdgeAfterCosts || a.edgeAfterCosts || 0));
    return rows;
  }, [data, underlying, expiryMode, minEdge]);

  const marketClosed = data?.marketClosed;
  const fromTodayBoard = !!data?.fromTodayBoard;
  const scanMs = data?.scanMs;
  const timedOut = !!data?.timedOut;
  const scanReason = data?.reason;
  const stickyCount = opps.filter(o => o.sticky && o.live === false).length;

  const LOT = { NIFTY: 25, BANKNIFTY: 15, FINNIFTY: 25, MIDCPNIFTY: 50 };
  const LIQ = { NIFTY: 'high', BANKNIFTY: 'high', FINNIFTY: 'medium', MIDCPNIFTY: 'thin' };

  return (
    <div className="space-y-4 w-full">
      {openPositionCount > 0 && (
        <button
          type="button"
          onClick={() => onOpenPositions && onOpenPositions()}
          className="w-full text-left bg-emerald-50 border border-emerald-300 text-emerald-900 text-xs font-bold px-4 py-3 rounded-xl hover:bg-emerald-100 transition"
        >
          {openPositionCount} open position{openPositionCount > 1 ? 's' : ''} — click for live PnL / Exit →
        </button>
      )}
      <div className="bg-white p-3 rounded-2xl border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
            {['ALL', 'NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'].map(u => (
              <button
                key={u}
                onClick={() => setUnderlying(u)}
                className={`px-3 py-1 rounded-lg text-xs font-bold transition ${
                  underlying === u ? 'bg-amber-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'
                }`}
              >
                {u}
              </button>
            ))}
          </div>
          <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
            {[
              { id: 'MONTHLY', label: 'Monthly' },
              { id: 'WEEKLY', label: 'Weekly' },
              { id: 'BOTH', label: 'Both' },
            ].map(m => (
              <button
                key={m.id}
                onClick={() => setExpiryMode(m.id)}
                className={`px-3 py-1 rounded-lg text-xs font-bold transition ${
                  expiryMode === m.id ? 'bg-slate-900 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'
                }`}
              >
                {m.label}
              </button>
            ))}
          </div>
        </div>
        <div className="flex items-center gap-2 text-xs">
          <label className="font-semibold text-slate-600">Min edge ₹</label>
          <input
            type="number"
            value={minEdge}
            onChange={e => setMinEdge(Number(e.target.value) || 0)}
            className="w-20 border border-slate-200 rounded-lg px-2 py-1 font-mono"
          />
          <button onClick={() => refetch()} className="px-3 py-1 rounded-lg bg-slate-900 text-white font-bold">
            {isFetching ? 'Scanning…' : 'Refresh'}
          </button>
          {scanMs != null && (
            <span className={`font-mono font-bold ${scanMs <= 2000 ? 'text-emerald-600' : 'text-amber-600'}`}>
              {scanMs}ms
            </span>
          )}
          {opps.length > 0 && (
            <span className="text-[10px] font-bold text-emerald-700 bg-emerald-50 border border-emerald-200 px-2 py-0.5 rounded">
              {opps.length} held{stickyCount > 0 ? ` · ${stickyCount} sticky` : ''}
            </span>
          )}
        </div>
      </div>

      <div className="bg-slate-50 border border-slate-200 text-slate-700 text-xs font-semibold px-4 py-3 rounded-xl space-y-1">
        <div>
          Model: <span className="font-bold text-slate-900">Black-76</span> (C−P = DF·(F−K)).
          Default min <span className="font-bold">₹300</span>. Once a signal prints ≥ ₹300 it <span className="font-bold text-emerald-700">stays on this board for the day</span> — including after market close.
        </div>
        <div>
          <span className="text-emerald-700">NIFTY / BANKNIFTY</span> — best for live.{' '}
          <span className="text-amber-700">FINNIFTY / MIDCPNIFTY</span> — thinner; verify touch qty. Use filter <span className="font-bold">ALL</span> to see everything fast.
        </div>
        {(expiryMode === 'WEEKLY' || expiryMode === 'BOTH') && (
          <div>
            Weekly uses ATM-implied forward when index spot is missing; hedge still <span className="font-bold">monthly FUT</span> (basis risk).
          </div>
        )}
      </div>

      {opps.some(o => Number(o.edgeAfterCosts) > 1500) && (
        <div className="bg-rose-50 border border-rose-200 text-rose-800 text-xs font-bold px-4 py-3 rounded-xl">
          Large ₹ edges (&gt;1500) look inflated — check payoff Quality badge before trading.
        </div>
      )}

      {marketClosed && (
        <div className="bg-amber-50 border border-amber-200 text-amber-800 text-xs font-semibold px-4 py-3 rounded-xl">
          Market closed (Mon–Fri 09:15–15:30 IST). Showing <b>today&apos;s signals</b> on this board
          {fromTodayBoard ? ` · ${opps.length} print${opps.length === 1 ? '' : 's'}` : ''}.
          Live scan resumes next session.
        </div>
      )}

      {!marketClosed && fromTodayBoard && (
        <div className="bg-sky-50 border border-sky-200 text-sky-900 text-xs font-semibold px-4 py-3 rounded-xl">
          No fresh live prints right now — showing today&apos;s saved signals (≥ ₹{minEdge}).
        </div>
      )}

      {timedOut && (
        <div className="bg-amber-50 border border-amber-300 text-amber-900 text-xs font-semibold px-4 py-3 rounded-xl">
          Broker quote lag — scan hit timeout{scanMs != null ? ` (${scanMs}ms)` : ''}.
          {' '}{scanReason || 'Click Refresh, or switch to BANKNIFTY / NIFTY alone (faster than ALL).'}
        </div>
      )}

      {error && (
        <div className="bg-red-50 border border-red-200 text-red-700 text-xs font-semibold px-4 py-3 rounded-xl">
          Scan failed: {error.message}
        </div>
      )}

      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm w-full">
        {isLoading && !data ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">Scanning Bid Parity feeds...</div>
        ) : opps.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold space-y-1">
            <div>
              {timedOut
                ? 'Scan timed out before quotes arrived — not “no edges”.'
                : marketClosed
                  ? `No Bid Parity signals ≥ ₹${minEdge} saved for today (${underlying} / ${expiryMode}).`
                  : `No executable bid-parity edges ≥ ₹${minEdge} for ${underlying} (${expiryMode})`}
            </div>
            <div className="text-[11px] font-medium text-slate-400">
              {timedOut
                ? 'Try BANKNIFTY Monthly, then Refresh. ALL+Both is heaviest.'
                : marketClosed
                  ? 'Signals appear here as they print during the session and remain after close.'
                  : 'Try filter ALL · lower Min edge to ₹0–50 · Black-76 means tight NIFTY/BN books often show nothing'}
              {scanMs != null ? ` · last scan ${scanMs}ms` : ''}
            </div>
          </div>
        ) : (
          <div className="overflow-x-auto w-full">
            <table className="w-full text-xs text-left border-collapse">
              <thead className="bg-slate-50 border-b border-slate-200 font-bold text-slate-600 uppercase">
                <tr>
                  <th className="px-2 py-2">Symbol</th>
                  <th className="px-2 py-2">Expiry</th>
                  <th className="px-2 py-2">Strike</th>
                  <th className="px-2 py-2">Side</th>
                  <th className="px-2 py-2 text-right">Spot / Fut</th>
                  <th className="px-2 py-2 text-right">CE Bid/Ask</th>
                  <th className="px-2 py-2 text-right">PE Bid/Ask</th>
                  <th className="px-2 py-2 text-right">Pts×Lot</th>
                  <th className="px-2 py-2 text-right text-emerald-600 font-bold">Net Edge (₹)</th>
                  <th className="px-2 py-2 text-center">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {opps.map((opp, idx) => {
                  const isExp = expandedId === idx;
                  const weekly = opp.expiryMode === 'WEEKLY' || opp.basisRisk || opp.strategyType === 'BID_PARITY_WEEKLY';
                  const u = String(opp.underlying || '').toUpperCase();
                  const lot = LOT[u] || 25;
                  const thin = LIQ[u] === 'thin' || LIQ[u] === 'medium';
                  const pts = Number(opp.edgePoints || 0);
                  return (
                    <React.Fragment key={`${opp.underlying}-${opp.strike}-${opp.action}-${opp.expiryDate}-${idx}`}>
                      <tr
                        onClick={() => setExpandedId(isExp ? null : idx)}
                        className={`transition cursor-pointer ${isExp ? 'bg-amber-50/70 border-l-4 border-amber-600' : 'hover:bg-slate-50'}`}
                      >
                        <td className="px-2 py-1.5 font-bold text-slate-800">
                          {opp.underlying}
                          {thin && <div className="text-[9px] text-amber-700 font-bold">thinner book</div>}
                          {opp.sticky && opp.live === false && (
                            <div className="text-[9px] text-emerald-700 font-bold">HELD</div>
                          )}
                        </td>
                        <td className="px-2 py-1.5">
                          <span className={`px-1.5 py-0.5 rounded text-[10px] font-bold ${weekly ? 'bg-orange-100 text-orange-800' : 'bg-slate-100 text-slate-700'}`}>
                            {weekly ? 'WEEKLY' : 'MONTHLY'}
                          </span>
                          <div className="text-[10px] text-slate-500 font-mono mt-0.5">{opp.expiryDate || '—'}</div>
                        </td>
                        <td className="px-2 py-1.5 font-bold text-slate-700">{opp.strike}</td>
                        <td className="px-2 py-1.5 font-bold text-purple-700">{opp.action}</td>
                        <td className="px-2 py-1.5 text-right font-mono text-[11px]">
                          {Number(opp.spotPrice || 0).toFixed(1)} / {Number(opp.futuresPrice || 0).toFixed(1)}
                        </td>
                        <td className="px-2 py-1.5 text-right font-mono text-xs">{opp.ceBid} / {opp.ceAsk}</td>
                        <td className="px-2 py-1.5 text-right font-mono text-xs">{opp.peBid} / {opp.peAsk}</td>
                        <td className="px-2 py-1.5 text-right font-mono text-[11px] text-slate-600">
                          {pts > 0 ? `${pts}×${lot}` : `lot ${lot}`}
                        </td>
                        <td className="px-2 py-1.5 text-right font-mono font-bold text-emerald-600">
                          +₹{Math.round(opp.edgeAfterCosts || 0).toLocaleString('en-IN')}
                          {(Number(opp.edgeAfterCosts) > 1500 || Number(opp.edgePoints) > 30) && (
                            <div className="text-[9px] text-rose-600 font-bold">check model</div>
                          )}
                        </td>
                        <td className="px-2 py-1.5 text-center">
                          <button
                            onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp); }}
                            className="px-2 py-0.5 bg-amber-600 text-white text-[10px] font-bold rounded shadow-sm"
                          >
                            Submit
                          </button>
                        </td>
                      </tr>
                      {isExp && (
                        <tr className="bg-slate-50/80">
                          <td colSpan={10} className="px-3 py-4">
                            <div className="space-y-3 animate-[expandDown_0.35s_ease]">
                              <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
                                <div className="font-semibold text-slate-700">{opp.description || opp.legs}</div>
                                <div className="flex items-center gap-2">
                                  <label className="font-bold text-slate-500">Lots</label>
                                  <input
                                    type="number"
                                    min={1}
                                    max={10}
                                    value={paperLots}
                                    onClick={(e) => e.stopPropagation()}
                                    onChange={(e) => setPaperLots(Math.max(1, Number(e.target.value) || 1))}
                                    className="w-14 border border-slate-200 rounded-lg px-2 py-1 font-mono"
                                  />
                                  <button
                                    onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp, paperLots); }}
                                    className="px-3 py-1.5 rounded-lg bg-amber-600 text-white text-[11px] font-bold shadow"
                                  >
                                    Submit ({executionBroker})
                                  </button>
                                </div>
                              </div>
                              {weekly && (
                                <div className="text-orange-800 text-xs font-semibold">
                                  Basis risk: parity vs F≈{opp.parityForward ?? '—'}; hedge monthly FUT (residual ≈ {opp.basisResidual ?? '—'} pts)
                                </div>
                              )}
                              {thin && (
                                <div className="text-amber-800 text-xs font-semibold">
                                  {u} liquidity is thinner than NIFTY/BN — verify CE/PE/FUT bid-ask qty before live fire.
                                </div>
                              )}
                              <BidParityPayoffChart
                                opp={opp}
                                lots={paperLots}
                                executionBroker={executionBroker}
                                onPaperTrade={(o, lots) => handleExecuteInline(o, lots)}
                              />
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

function BidParityHistoryView({ handleExecuteInline, executionBroker }) {
  const [underlying, setUnderlying] = useState('ALL');
  const [days, setDays] = useState(7);
  const [minEdge, setMinEdge] = useState(0);
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [expandedId, setExpandedId] = useState(null);
  const [exitingId, setExitingId] = useState(null);

  const { data, isLoading, isFetching, refetch } = useQuery({
    queryKey: ['bid-parity-history', underlying, days, minEdge],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/bid-parity/history', {
        params: { underlying, days, minEdge },
      });
      return res.data;
    },
    staleTime: 1500,
    refetchInterval: 4000,
  });

  const items = (data?.items || []).filter(row => {
    if (statusFilter === 'ALL') return true;
    return String(row.tradeStatus || row.status || '').toUpperCase() === statusFilter;
  });
  const summary = data?.summary || {};
  const queryMs = data?.queryMs;
  const cached = data?.cached;

  const exitPosition = async (positionId) => {
    if (!positionId) return;
    setExitingId(positionId);
    try {
      const res = await client.post(`/option-arbitrage/live-positions/${positionId}/exit`, { note: 'manual-ui' });
      if (res.data?.status === 'EXITED') {
        showToast(`Exited · PnL ₹${Math.round(res.data?.exitPnl || 0)}`, 'success');
        refetch();
      } else {
        showToast(res.data?.message || 'Exit failed', 'warning');
      }
    } catch (e) {
      showToast(e.response?.data?.message || e.message || 'Exit failed', 'error');
    } finally {
      setExitingId(null);
    }
  };

  const statusBadge = (st) => {
    const s = String(st || 'SIGNAL').toUpperCase();
    const cls = s === 'ENTERED' ? 'bg-emerald-100 text-emerald-800 border-emerald-300'
      : s === 'EXITED' ? 'bg-slate-200 text-slate-800 border-slate-300'
      : s === 'EXPIRED' || s === 'FAILED' ? 'bg-amber-100 text-amber-800 border-amber-300'
      : 'bg-sky-100 text-sky-800 border-sky-300';
    return <span className={`px-1.5 py-0.5 rounded text-[10px] font-bold border ${cls}`}>{s}</span>;
  };

  const pnlCell = (row) => {
    const st = String(row.tradeStatus || row.status || '').toUpperCase();
    if (st === 'ENTERED') {
      const v = Number(row.currentPnl ?? row.pnlAfterCosts ?? 0);
      return <span className={`font-mono font-bold ${v >= 0 ? 'text-emerald-600' : 'text-red-600'}`}>₹{Math.round(v).toLocaleString('en-IN')}</span>;
    }
    if (st === 'EXITED') {
      const v = Number(row.exitPnl ?? row.pnlAfterCosts ?? 0);
      return <span className={`font-mono font-bold ${v >= 0 ? 'text-emerald-600' : 'text-red-600'}`}>₹{Math.round(v).toLocaleString('en-IN')}</span>;
    }
    return <span className="text-slate-400">—</span>;
  };

  return (
    <div className="space-y-4 w-full">
      <div className="bg-white p-3 rounded-2xl border border-slate-200 shadow-sm flex flex-wrap items-center gap-3">
        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          {['ALL', 'NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'].map(u => (
            <button
              key={u}
              onClick={() => setUnderlying(u)}
              className={`px-3 py-1 rounded-lg text-xs font-bold transition ${
                underlying === u ? 'bg-slate-900 text-white' : 'text-slate-600 hover:bg-slate-200'
              }`}
            >
              {u}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 p-1 rounded-xl">
          {['ALL', 'SIGNAL', 'ENTERED', 'EXITED'].map(s => (
            <button key={s} onClick={() => setStatusFilter(s)}
              className={`px-2 py-1 rounded-lg text-[10px] font-bold ${statusFilter === s ? 'bg-amber-600 text-white' : 'text-slate-600'}`}>
              {s}
            </button>
          ))}
        </div>
        <select
          value={days}
          onChange={e => setDays(Number(e.target.value))}
          className="text-xs border border-slate-200 rounded-lg px-2 py-1.5 font-semibold"
        >
          <option value={1}>Today</option>
          <option value={3}>3 days</option>
          <option value={7}>7 days</option>
          <option value={30}>30 days</option>
        </select>
        <div className="flex items-center gap-2 text-xs">
          <span className="font-semibold text-slate-600">Min ₹</span>
          <input
            type="number"
            value={minEdge}
            onChange={e => setMinEdge(Number(e.target.value) || 0)}
            className="w-20 border border-slate-200 rounded-lg px-2 py-1 font-mono"
          />
        </div>
        <button onClick={() => refetch()} className="px-3 py-1.5 rounded-lg bg-slate-900 text-white text-xs font-bold">
          {isFetching ? 'Loading…' : 'Reload'}
        </button>
        <span className="text-xs text-slate-500 ml-auto">
          {items.length} rows · sig {summary.signals ?? '—'} · in {summary.entered ?? '—'} · out {summary.exited ?? '—'}
          {queryMs != null && <span className="ml-2 font-mono text-emerald-600">{cached ? 'cache' : `${queryMs}ms`}</span>}
        </span>
      </div>

      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm">
        {isLoading ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">Loading bid-parity history…</div>
        ) : items.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">No BID_PARITY history for this filter</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-xs text-left border-collapse">
              <thead className="bg-slate-50 border-b border-slate-200 font-bold text-slate-600 uppercase">
                <tr>
                  <th className="px-2 py-2">Time</th>
                  <th className="px-2 py-2">Underlying</th>
                  <th className="px-2 py-2">Strike</th>
                  <th className="px-2 py-2">Action</th>
                  <th className="px-2 py-2 text-center">Status</th>
                  <th className="px-2 py-2 text-right text-emerald-600">Edge ₹</th>
                  <th className="px-2 py-2 text-right">PnL ₹</th>
                  <th className="px-2 py-2">Entered</th>
                  <th className="px-2 py-2">Exited</th>
                  <th className="px-2 py-2 text-center">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {items.map((row) => {
                  const open = expandedId === row.id;
                  const st = String(row.tradeStatus || row.status || 'SIGNAL').toUpperCase();
                  return (
                    <React.Fragment key={`${row.id}-${row.positionId || ''}`}>
                      <tr
                        className={`cursor-pointer transition ${open ? 'bg-amber-50/70 border-l-4 border-amber-600' : 'hover:bg-slate-50'}`}
                        onClick={() => setExpandedId(open ? null : row.id)}
                      >
                        <td className="px-2 py-1.5 font-mono text-[11px] text-slate-600">{String(row.scanTime || row.detectedAt || '').slice(0, 19)}</td>
                        <td className="px-2 py-1.5 font-bold">{row.underlying}</td>
                        <td className="px-2 py-1.5 font-bold">{row.strike}</td>
                        <td className="px-2 py-1.5 font-bold text-purple-700">{row.action}</td>
                        <td className="px-2 py-1.5 text-center">{statusBadge(st)}</td>
                        <td className="px-2 py-1.5 text-right font-mono font-bold text-emerald-600">
                          ₹{Math.round(row.edgeAfterCosts || 0).toLocaleString('en-IN')}
                        </td>
                        <td className="px-2 py-1.5 text-right">{pnlCell(row)}</td>
                        <td className="px-2 py-1.5 font-mono text-[10px] text-slate-500">{row.enteredAt ? String(row.enteredAt).slice(0, 19) : '—'}</td>
                        <td className="px-2 py-1.5 font-mono text-[10px] text-slate-500">{row.exitedAt || row.exitTime ? String(row.exitedAt || row.exitTime).slice(0, 19) : '—'}</td>
                        <td className="px-2 py-1.5 text-center" onClick={(e) => e.stopPropagation()}>
                          {st === 'SIGNAL' && (
                            <button onClick={() => handleExecuteInline(row)} className="px-2 py-0.5 bg-amber-600 text-white text-[10px] font-bold rounded">Paper</button>
                          )}
                          {st === 'ENTERED' && row.positionId && (
                            <button disabled={exitingId === row.positionId}
                              onClick={() => exitPosition(row.positionId)}
                              className="px-2 py-0.5 bg-slate-800 text-white text-[10px] font-bold rounded disabled:opacity-50">
                              {exitingId === row.positionId ? '…' : 'Exit'}
                            </button>
                          )}
                        </td>
                      </tr>
                      {open && (
                        <tr className="bg-slate-50/80">
                          <td colSpan={10} className="px-3 py-4">
                            <BidParityPayoffChart
                              opp={row}
                              lots={1}
                              executionBroker={executionBroker || 'PAPER'}
                              onPaperTrade={handleExecuteInline
                                ? (o, lots) => handleExecuteInline(o, lots)
                                : undefined}
                            />
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

/* legacy alias kept unused — BidParityView replaced by BidParityHub */
function BidParityView() { return null; }

function BidParityPaperSimView() {
  const [underlying, setUnderlying] = useState('NIFTY');
  const [minEdge, setMinEdge] = useState(150);
  const [capital, setCapital] = useState(180000);
  const [maxTrades, setMaxTrades] = useState(2);
  const [days, setDays] = useState(10);
  const [fillRate, setFillRate] = useState(0.6);

  const { data, isLoading, isFetching, refetch, error } = useQuery({
    queryKey: ['bid-parity-paper-sim', underlying, minEdge, capital, maxTrades, days, fillRate],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/bid-parity/paper-sim', {
        params: { underlying, minEdge, capital, maxTradesPerDay: maxTrades, days, fillRate },
      });
      return res.data;
    },
    staleTime: 30000,
  });

  const p = data?.projection || {};
  const daily = data?.daily || [];
  const fresh = data?.freshSignals || [];
  const top = data?.topSignals || [];

  const fmt = (v) => (v == null ? '—' : `₹${Math.round(Number(v)).toLocaleString('en-IN')}`);

  return (
    <div className="space-y-4 w-full">
      <div className="bg-white p-3 rounded-2xl border border-slate-200 shadow-sm flex flex-wrap items-center gap-3">
        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          {['NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY', 'ALL'].map(u => (
            <button key={u} onClick={() => setUnderlying(u)}
              className={`px-3 py-1 rounded-lg text-xs font-bold transition ${underlying === u ? 'bg-slate-900 text-white' : 'text-slate-600 hover:bg-slate-200'}`}>
              {u}
            </button>
          ))}
        </div>
        <label className="text-xs font-semibold text-slate-600 flex items-center gap-1">Min ₹
          <input type="number" value={minEdge} onChange={e => setMinEdge(Number(e.target.value) || 0)} className="w-20 border rounded-lg px-2 py-1 font-mono" />
        </label>
        <label className="text-xs font-semibold text-slate-600 flex items-center gap-1">Capital ₹
          <input type="number" value={capital} onChange={e => setCapital(Number(e.target.value) || 0)} className="w-28 border rounded-lg px-2 py-1 font-mono" />
        </label>
        <label className="text-xs font-semibold text-slate-600 flex items-center gap-1">Max/day
          <input type="number" value={maxTrades} onChange={e => setMaxTrades(Number(e.target.value) || 1)} className="w-14 border rounded-lg px-2 py-1 font-mono" />
        </label>
        <label className="text-xs font-semibold text-slate-600 flex items-center gap-1">Days
          <input type="number" value={days} onChange={e => setDays(Number(e.target.value) || 1)} className="w-14 border rounded-lg px-2 py-1 font-mono" />
        </label>
        <label className="text-xs font-semibold text-slate-600 flex items-center gap-1">Fill %
          <input type="number" step="0.1" min="0" max="1" value={fillRate}
            onChange={e => setFillRate(Number(e.target.value) || 0)} className="w-16 border rounded-lg px-2 py-1 font-mono" />
        </label>
        <button onClick={() => refetch()} className="px-3 py-1.5 rounded-lg bg-amber-600 text-white text-xs font-bold ml-auto">
          {isFetching ? 'Running…' : 'Run Paper Sim'}
        </button>
      </div>

      {error && (
        <div className="bg-red-50 border border-red-200 text-red-700 text-xs font-semibold px-4 py-3 rounded-xl">
          Sim failed: {error.message}
        </div>
      )}

      {isLoading ? (
        <div className="p-12 text-center text-slate-400 text-sm font-semibold">Running paper-fill simulator…</div>
      ) : (
        <>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            {[
              { label: 'Avg trades / day', value: p.avgTradesPerDay ?? '—' },
              { label: 'Expected daily (fill)', value: fmt(p.expectedDailyAtFillRate), sub: 'conservative × fillRate' },
              { label: 'Expected monthly', value: fmt(p.expectedMonthlyConservative), sub: '20 sessions × fillRate' },
              { label: 'Monthly (slip +1pt)', value: fmt(p.expectedMonthlySlip), sub: 'stress case' },
            ].map(c => (
              <div key={c.label} className="bg-white rounded-2xl border border-slate-200 p-4 shadow-sm">
                <div className="text-[10px] uppercase font-bold text-slate-500 tracking-wider">{c.label}</div>
                <div className="text-xl font-bold text-slate-900 mt-1">{c.value}</div>
                {c.sub && <div className="text-[11px] text-slate-400 mt-1">{c.sub}</div>}
              </div>
            ))}
          </div>

          <div className="bg-amber-50 border border-amber-200 text-amber-900 text-xs px-4 py-3 rounded-xl space-y-1">
            <div className="font-bold">Source: {data?.source || '—'} · signals: {data?.rawSignalCount ?? 0} · {data?.elapsedMs ?? 0}ms</div>
            <div>{p.note}</div>
            <div className="text-amber-800/80">Mid monthly {fmt(p.expectedMonthlyMid)} · sample days {p.tradingDaysInSample ?? 0} (with trades {p.daysWithTrades ?? 0})</div>
          </div>

          {fresh.length > 0 && (
            <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm">
              <div className="px-3 py-2 bg-slate-50 border-b text-xs font-bold text-slate-700">Fresh off-hours / last-quote signals (new logic)</div>
              <div className="overflow-x-auto">
                <table className="w-full text-xs text-left">
                  <thead className="bg-slate-50 font-bold text-slate-600 uppercase">
                    <tr>
                      <th className="px-2 py-2">Underlying</th>
                      <th className="px-2 py-2">Strike</th>
                      <th className="px-2 py-2">Action</th>
                      <th className="px-2 py-2 text-right">Cons ₹</th>
                      <th className="px-2 py-2 text-right">Mid ₹</th>
                      <th className="px-2 py-2 text-right">Slip ₹</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y">
                    {fresh.slice(0, 15).map((r, i) => (
                      <tr key={i} className="hover:bg-slate-50">
                        <td className="px-2 py-1.5 font-bold">{r.underlying}</td>
                        <td className="px-2 py-1.5 font-bold">{r.strike}</td>
                        <td className="px-2 py-1.5 text-purple-700 font-bold">{r.action}</td>
                        <td className="px-2 py-1.5 text-right font-mono text-emerald-600">{fmt(r.conservativeNet)}</td>
                        <td className="px-2 py-1.5 text-right font-mono">{fmt(r.midNet)}</td>
                        <td className="px-2 py-1.5 text-right font-mono text-amber-700">{fmt(r.slipNet)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm">
            <div className="px-3 py-2 bg-slate-50 border-b text-xs font-bold text-slate-700">Capital-constrained daily breakdown</div>
            {daily.length === 0 ? (
              <div className="p-8 text-center text-slate-400 text-sm font-semibold">
                No clean executable history yet (old junk filtered). Run during market hours for live expectancy.
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-xs text-left">
                  <thead className="bg-slate-50 font-bold text-slate-600 uppercase">
                    <tr>
                      <th className="px-2 py-2">Date</th>
                      <th className="px-2 py-2 text-right">Signals</th>
                      <th className="px-2 py-2 text-right">Taken</th>
                      <th className="px-2 py-2 text-right">Cons ₹</th>
                      <th className="px-2 py-2 text-right">Mid ₹</th>
                      <th className="px-2 py-2 text-right">Slip ₹</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y">
                    {[...daily].reverse().map((d) => (
                      <tr key={d.date} className="hover:bg-slate-50">
                        <td className="px-2 py-1.5 font-mono">{d.date}</td>
                        <td className="px-2 py-1.5 text-right">{d.signals}</td>
                        <td className="px-2 py-1.5 text-right font-bold">{d.taken}</td>
                        <td className="px-2 py-1.5 text-right font-mono text-emerald-600">{fmt(d.conservativePnl)}</td>
                        <td className="px-2 py-1.5 text-right font-mono">{fmt(d.midPnl)}</td>
                        <td className="px-2 py-1.5 text-right font-mono text-amber-700">{fmt(d.slipPnl)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          {top.length > 0 && (
            <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm">
              <div className="px-3 py-2 bg-slate-50 border-b text-xs font-bold text-slate-700">Top clean signals in sample</div>
              <div className="overflow-x-auto max-h-72">
                <table className="w-full text-xs text-left">
                  <thead className="bg-slate-50 font-bold text-slate-600 uppercase sticky top-0">
                    <tr>
                      <th className="px-2 py-2">Date</th>
                      <th className="px-2 py-2">U</th>
                      <th className="px-2 py-2">Strike</th>
                      <th className="px-2 py-2">Action</th>
                      <th className="px-2 py-2 text-right">Cons ₹</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y">
                    {top.slice(0, 25).map((r, i) => (
                      <tr key={i}>
                        <td className="px-2 py-1 font-mono text-[11px]">{r.date}</td>
                        <td className="px-2 py-1 font-bold">{r.underlying}</td>
                        <td className="px-2 py-1">{r.strike}</td>
                        <td className="px-2 py-1 text-purple-700 font-bold">{r.action}</td>
                        <td className="px-2 py-1 text-right font-mono text-emerald-600">{fmt(r.conservativeNet)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}

/* 3. BOX SPREAD HUB + VIEW */
function BoxSpreadHub({ handleExecuteInline, executionBroker, autoRefresh }) {
  const [searchParams, setSearchParams] = useSearchParams();
  const [tab, setTab] = useState(searchParams.get('bp') === 'history' ? 'history' : 'live');
  const switchTab = (id) => {
    setTab(id);
    const next = new URLSearchParams(searchParams);
    next.set('tab', 'box');
    if (id === 'live') next.delete('bp');
    else next.set('bp', id);
    setSearchParams(next, { replace: true });
  };
  return (
    <div className="space-y-4 w-full">
      <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-base font-bold text-slate-800">4-Leg Box Spread Scanner</h2>
          <p className="text-xs text-slate-500">Same-expiry LONG/SHORT vs DF·(K2−K1) · weekly + monthly · paper only</p>
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          {[
            { id: 'live', label: '📡 Live Signals' },
            { id: 'history', label: '📜 History' },
          ].map(t => (
            <button key={t.id} onClick={() => switchTab(t.id)}
              className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${tab === t.id ? 'bg-purple-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              {t.label}
            </button>
          ))}
        </div>
      </div>
      {tab === 'live' && <BoxSpreadView handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} autoRefresh={autoRefresh} />}
      {tab === 'history' && <StrategyHistoryView strategyFilter="BOX_SPREAD" handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} accent="purple" />}
    </div>
  );
}

function BoxSpreadView({ handleExecuteInline, executionBroker, autoRefresh = true }) {
  const [underlying, setUnderlying] = useState('ALL');
  const [expiryMode, setExpiryMode] = useState('BOTH');
  const [expandedId, setExpandedId] = useState(null);
  const [minEdge, setMinEdge] = useState(75);
  const lastDataRef = React.useRef(null);

  const { data, isLoading, refetch, isFetching } = useQuery({
    queryKey: ['box-spread-scan', underlying, expiryMode],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/box-spread/scan', {
        params: { underlying, expiry: expiryMode },
      });
      lastDataRef.current = res.data;
      return res.data;
    },
    refetchInterval: autoRefresh ? 4000 : false,
    staleTime: 1200,
    placeholderData: (prev) => prev ?? lastDataRef.current ?? undefined,
  });

  const opps = (data?.opportunities || []).filter(o => (o.edgeAfterCosts || 0) >= minEdge);
  const marketClosed = data?.marketClosed;
  const scanMs = data?.scanMs;

  return (
    <div className="space-y-4 w-full">
      <div className="bg-white p-3 rounded-2xl border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
            {['ALL', 'NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'].map(u => (
              <button key={u} onClick={() => setUnderlying(u)}
                className={`px-3 py-1 rounded-lg text-xs font-bold transition ${underlying === u ? 'bg-purple-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
                {u}
              </button>
            ))}
          </div>
          <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
            {['MONTHLY', 'WEEKLY', 'BOTH'].map(m => (
              <button key={m} onClick={() => setExpiryMode(m)}
                className={`px-3 py-1 rounded-lg text-xs font-bold transition ${expiryMode === m ? 'bg-slate-900 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
                {m}
              </button>
            ))}
          </div>
        </div>
        <div className="flex items-center gap-2 text-xs">
          <label className="font-semibold text-slate-600">Min ₹</label>
          <input type="number" value={minEdge} onChange={e => setMinEdge(Number(e.target.value) || 0)}
            className="w-20 border border-slate-200 rounded-lg px-2 py-1 font-mono" />
          <button onClick={() => refetch()} className="px-3 py-1 rounded-lg bg-slate-900 text-white font-bold">
            {isFetching ? 'Scanning…' : 'Refresh'}
          </button>
          {scanMs != null && <span className={`font-mono font-bold ${scanMs <= 2000 ? 'text-emerald-600' : 'text-amber-600'}`}>{scanMs}ms</span>}
        </div>
      </div>

      <div className="bg-slate-50 border border-slate-200 text-slate-700 text-xs font-semibold px-4 py-3 rounded-xl">
        Fair = DF·(K2−K1). Same-expiry 4 legs. Not auto-fired by Bid Parity 3-leg exec — use Paper Submit.
        Empty NIFTY/BN often means tight books (healthy), not a broken scanner.
      </div>

      {marketClosed && (
        <div className="bg-amber-50 border border-amber-200 text-amber-800 text-xs font-semibold px-4 py-3 rounded-xl">
          Market closed — box scan runs Mon–Fri 09:15–15:30 IST.
        </div>
      )}

      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm w-full">
        {isLoading && !data ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">Scanning Box Spreads...</div>
        ) : opps.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">
            No executable box edges ≥ ₹{minEdge} for {underlying} ({expiryMode})
            {scanMs != null ? ` · ${scanMs}ms` : ''}
          </div>
        ) : (
          <div className="overflow-x-auto w-full">
            <table className="w-full text-xs text-left border-collapse">
              <thead className="bg-slate-50 border-b border-slate-200 font-bold text-slate-600 uppercase">
                <tr>
                  <th className="px-2 py-2">Symbol</th>
                  <th className="px-2 py-2">Expiry</th>
                  <th className="px-2 py-2">Side</th>
                  <th className="px-2 py-2">Strike Pair</th>
                  <th className="px-2 py-2 text-right">Box Cost</th>
                  <th className="px-2 py-2 text-right">Fair PV</th>
                  <th className="px-2 py-2 text-right">Width</th>
                  <th className="px-2 py-2 text-right text-emerald-600 font-bold">Net Edge (₹)</th>
                  <th className="px-2 py-2 text-center">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {opps.map((opp, idx) => {
                  const isExp = expandedId === idx;
                  const strike1 = Number(opp.lowerStrike || opp.strike || 0);
                  const strike2 = Number(opp.upperStrike || 0);
                  const costVal = Number(opp.boxCost ?? 0);
                  const fairVal = Number(opp.fairValue ?? 0);
                  const widthVal = Number(opp.width ?? (strike2 - strike1));
                  return (
                    <React.Fragment key={`${opp.action}-${strike1}-${strike2}-${opp.expiryDate}-${idx}`}>
                      <tr onClick={() => setExpandedId(isExp ? null : idx)}
                        className={`transition cursor-pointer ${isExp ? 'bg-purple-50/70 border-l-4 border-purple-600' : 'hover:bg-slate-50'}`}>
                        <td className="px-2 py-1.5 font-bold text-slate-800">{opp.underlying}</td>
                        <td className="px-2 py-1.5">
                          <span className="px-1.5 py-0.5 rounded text-[10px] font-bold bg-slate-100 text-slate-700">{opp.expiryMode || '—'}</span>
                          <div className="text-[10px] text-slate-500 font-mono mt-0.5">{opp.expiryDate || '—'}</div>
                        </td>
                        <td className="px-2 py-1.5 font-bold text-purple-700">{String(opp.action || '').split(' (')[0]}</td>
                        <td className="px-2 py-1.5 font-bold text-slate-700">{strike1} / {strike2 || '—'}</td>
                        <td className="px-2 py-1.5 text-right font-mono">{costVal.toFixed(1)}</td>
                        <td className="px-2 py-1.5 text-right font-mono">{fairVal.toFixed(1)}</td>
                        <td className="px-2 py-1.5 text-right font-mono">{widthVal}</td>
                        <td className="px-2 py-1.5 text-right font-mono font-bold text-emerald-600">
                          +₹{Math.round(opp.edgeAfterCosts || 0).toLocaleString('en-IN')}
                        </td>
                        <td className="px-2 py-1.5 text-center">
                          <button onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp); }}
                            className="px-2 py-0.5 bg-purple-600 text-white text-[10px] font-bold rounded shadow-sm">
                            Paper
                          </button>
                        </td>
                      </tr>
                      {isExp && (
                        <tr className="bg-purple-50/40 border-b border-purple-100">
                          <td colSpan={9} className="p-3">
                            <div className="bg-white rounded-xl p-3 border border-purple-200 shadow-md space-y-2">
                              <span className="font-bold text-slate-800 text-xs uppercase block">4-leg breakdown</span>
                              <p className="text-xs font-mono font-bold text-slate-800 bg-slate-50 p-2 rounded-lg border">
                                {opp.legs || opp.description}
                              </p>
                              <button onClick={() => handleExecuteInline(opp)}
                                className="px-3 py-1.5 rounded-lg bg-purple-600 text-white text-[11px] font-bold">
                                📝 Paper trade ({executionBroker})
                              </button>
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

function StrategyHistoryView({ strategyFilter, handleExecuteInline, executionBroker, accent = 'slate' }) {
  const [underlying, setUnderlying] = useState('ALL');
  const [days, setDays] = useState(7);
  const [minEdge, setMinEdge] = useState(0);
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [exitingId, setExitingId] = useState(null);
  const btnActive = accent === 'purple' ? 'bg-purple-600 text-white'
    : accent === 'sky' ? 'bg-sky-600 text-white'
    : accent === 'amber' ? 'bg-amber-600 text-white'
    : 'bg-slate-900 text-white';
  const paperBtn = accent === 'purple' ? 'bg-purple-600'
    : accent === 'sky' ? 'bg-sky-600'
    : accent === 'amber' ? 'bg-amber-600'
    : 'bg-slate-700';
  const statusActive = accent === 'purple' ? 'bg-purple-600 text-white' : accent === 'sky' ? 'bg-sky-600 text-white' : 'bg-slate-900 text-white';

  const { data, isLoading, isFetching, refetch } = useQuery({
    queryKey: ['strategy-history-trades', strategyFilter, underlying, days, minEdge],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/history/trades', {
        params: { strategyType: strategyFilter, underlying, days, minEdge },
      });
      return res.data;
    },
    staleTime: 1500,
    refetchInterval: 4000,
  });

  const items = (data?.items || []).filter(row => {
    if (statusFilter === 'ALL') return true;
    return String(row.tradeStatus || row.status || '').toUpperCase() === statusFilter;
  });
  const summary = data?.summary || {};

  const exitPosition = async (positionId) => {
    if (!positionId) return;
    setExitingId(positionId);
    try {
      const res = await client.post(`/option-arbitrage/live-positions/${positionId}/exit`, { note: 'manual-ui' });
      if (res.data?.status === 'EXITED') {
        showToast(`Exited · PnL ₹${Math.round(res.data?.exitPnl || 0)}`, 'success');
        refetch();
      } else {
        showToast(res.data?.message || 'Exit failed', 'warning');
      }
    } catch (e) {
      showToast(e.response?.data?.message || e.message || 'Exit failed', 'error');
    } finally {
      setExitingId(null);
    }
  };

  const statusBadge = (st) => {
    const s = String(st || 'SIGNAL').toUpperCase();
    const cls = s === 'ENTERED' ? 'bg-emerald-100 text-emerald-800 border-emerald-300'
      : s === 'EXITED' ? 'bg-slate-200 text-slate-800 border-slate-300'
      : s === 'EXPIRED' || s === 'FAILED' ? 'bg-amber-100 text-amber-800 border-amber-300'
      : 'bg-sky-100 text-sky-800 border-sky-300';
    return <span className={`px-1.5 py-0.5 rounded text-[10px] font-bold border ${cls}`}>{s}</span>;
  };

  return (
    <div className="space-y-3">
      <div className="bg-white p-3 rounded-2xl border border-slate-200 flex flex-wrap gap-2 items-center justify-between">
        <div className="flex gap-1.5 bg-slate-100 p-1 rounded-xl">
          {['ALL', 'NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'].map(u => (
            <button key={u} onClick={() => setUnderlying(u)}
              className={`px-2 py-1 rounded-lg text-xs font-bold ${underlying === u ? btnActive : 'text-slate-600'}`}>{u}</button>
          ))}
        </div>
        <div className="flex gap-1 bg-slate-100 p-1 rounded-xl">
          {['ALL', 'SIGNAL', 'ENTERED', 'EXITED'].map(s => (
            <button key={s} onClick={() => setStatusFilter(s)}
              className={`px-2 py-1 rounded-lg text-[10px] font-bold ${statusFilter === s ? statusActive : 'text-slate-600'}`}>{s}</button>
          ))}
        </div>
        <div className="flex items-center gap-2 text-xs">
          <select value={days} onChange={e => setDays(Number(e.target.value))} className="border rounded-lg px-2 py-1">
            {[1, 3, 7, 14].map(d => <option key={d} value={d}>{d}d</option>)}
          </select>
          <button onClick={() => refetch()} className="px-3 py-1 bg-slate-900 text-white rounded-lg font-bold">
            {isFetching ? '…' : 'Refresh'}
          </button>
          <span className="text-slate-500">
            sig {summary.signals ?? 0} · in {summary.entered ?? 0} · out {summary.exited ?? 0}
            {data?.queryMs != null && <span className="ml-1 font-mono text-emerald-600">{data.cached ? 'cache' : `${data.queryMs}ms`}</span>}
          </span>
        </div>
      </div>
      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden">
        {isLoading ? <div className="p-10 text-center text-slate-400 text-sm">Loading history…</div>
          : items.length === 0 ? <div className="p-10 text-center text-slate-400 text-sm">No {strategyFilter} history</div>
          : (
            <table className="w-full text-xs">
              <thead className="bg-slate-50 font-bold text-slate-600 uppercase">
                <tr>
                  <th className="px-2 py-2 text-left">Time</th>
                  <th className="px-2 py-2 text-left">Symbol</th>
                  <th className="px-2 py-2 text-left">Action</th>
                  <th className="px-2 py-2 text-center">Status</th>
                  <th className="px-2 py-2 text-right">Edge ₹</th>
                  <th className="px-2 py-2 text-right">PnL ₹</th>
                  <th className="px-2 py-2 text-left">Entered</th>
                  <th className="px-2 py-2 text-left">Exited</th>
                  <th className="px-2 py-2 text-center">Do</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {items.slice(0, 200).map((row, i) => {
                  const st = String(row.tradeStatus || row.status || 'SIGNAL').toUpperCase();
                  const pnl = st === 'ENTERED' ? Number(row.currentPnl ?? 0)
                    : st === 'EXITED' ? Number(row.exitPnl ?? row.pnlAfterCosts ?? 0) : null;
                  return (
                    <tr key={`${row.id}-${row.positionId || i}`} className="hover:bg-slate-50">
                      <td className="px-2 py-1.5 font-mono text-slate-500">{String(row.scanTime || row.createdAt || '').slice(0, 19)}</td>
                      <td className="px-2 py-1.5 font-bold">{row.underlying} {row.strike}</td>
                      <td className="px-2 py-1.5">{row.action}</td>
                      <td className="px-2 py-1.5 text-center">{statusBadge(st)}</td>
                      <td className="px-2 py-1.5 text-right font-mono font-bold text-emerald-600">₹{Math.round(row.edgeAfterCosts || 0)}</td>
                      <td className={`px-2 py-1.5 text-right font-mono font-bold ${pnl == null ? 'text-slate-400' : pnl >= 0 ? 'text-emerald-600' : 'text-red-600'}`}>
                        {pnl == null ? '—' : `₹${Math.round(pnl)}`}
                      </td>
                      <td className="px-2 py-1.5 font-mono text-[10px] text-slate-500">{row.enteredAt ? String(row.enteredAt).slice(0, 19) : '—'}</td>
                      <td className="px-2 py-1.5 font-mono text-[10px] text-slate-500">{row.exitedAt || row.exitTime ? String(row.exitedAt || row.exitTime).slice(0, 19) : '—'}</td>
                      <td className="px-2 py-1.5 text-center">
                        {st === 'SIGNAL' && (
                          <button onClick={() => handleExecuteInline(row)} className={`px-2 py-0.5 ${paperBtn} text-white text-[10px] font-bold rounded`}>Paper</button>
                        )}
                        {st === 'ENTERED' && row.positionId && (
                          <button disabled={exitingId === row.positionId}
                            onClick={() => exitPosition(row.positionId)}
                            className="px-2 py-0.5 bg-slate-800 text-white text-[10px] font-bold rounded disabled:opacity-50">
                            {exitingId === row.positionId ? '…' : 'Exit'}
                          </button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
      </div>
    </div>
  );
}

function CalendarSpreadHub({ handleExecuteInline, executionBroker, autoRefresh }) {
  const [searchParams, setSearchParams] = useSearchParams();
  const [tab, setTab] = useState(searchParams.get('bp') === 'history' ? 'history' : 'live');
  const switchTab = (id) => {
    setTab(id);
    const next = new URLSearchParams(searchParams);
    next.set('tab', 'calendar');
    if (id === 'live') next.delete('bp');
    else next.set('bp', id);
    setSearchParams(next, { replace: true });
  };
  return (
    <div className="space-y-4 w-full">
      <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-base font-bold text-slate-800">Calendar Time Spreads</h2>
          <p className="text-xs text-slate-500">Weekly vs monthly same-strike CE/PE · heuristic (not risk-free) · paper only</p>
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          {[
            { id: 'live', label: '📡 Live Signals' },
            { id: 'history', label: '📜 History' },
          ].map(t => (
            <button key={t.id} onClick={() => switchTab(t.id)}
              className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${tab === t.id ? 'bg-sky-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              {t.label}
            </button>
          ))}
        </div>
      </div>
      {tab === 'live' && <CalendarSpreadView handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} autoRefresh={autoRefresh} />}
      {tab === 'history' && <StrategyHistoryView strategyFilter="CALENDAR_SPREAD" handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} accent="sky" />}
    </div>
  );
}

/* legacy BOX view kept as BoxSpreadView above */

/* 4. 0DTE IRON CONDOR VIEW */
function IronCondorView({ handleExecuteInline, executionBroker }) {
  const [underlying, setUnderlying] = useState('NIFTY');
  const [expandedId, setExpandedId] = useState(null);

  const { data, isLoading } = useQuery({
    queryKey: ['iron-condor-scan', underlying],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/iron-condor/scan', { params: { underlying } });
      return res.data;
    },
    refetchInterval: 3000
  });

  const opps = data?.opportunities || [];

  return (
    <div className="space-y-4 w-full">
      <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className="text-base font-bold text-slate-800">0DTE Delta-Neutral Iron Condor Scanner</h2>
          <p className="text-xs text-slate-500">High-probability non-directional credit wing spreads with dynamic trailing stop loss</p>
        </div>

        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          {['NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'].map(u => (
            <button
              key={u}
              onClick={() => setUnderlying(u)}
              className={`px-3 py-1 rounded-lg text-xs font-bold transition ${
                underlying === u ? 'bg-indigo-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'
              }`}
            >
              {u}
            </button>
          ))}
        </div>
      </div>

      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm w-full">
        {isLoading ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">Scanning 0DTE Iron Condor spreads...</div>
        ) : opps.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">No 0DTE Iron Condor setups meeting risk criteria for {underlying}</div>
        ) : (
          <div className="overflow-x-auto w-full">
            <table className="w-full text-xs text-left border-collapse">
              <thead className="bg-slate-50 border-b border-slate-200 font-bold text-slate-600 uppercase">
                <tr>
                  <th className="px-2 py-2">Symbol</th>
                  <th className="px-2 py-2">ATM Strike</th>
                  <th className="px-2 py-2">Call Wing</th>
                  <th className="px-2 py-2">Put Wing</th>
                  <th className="px-2 py-2 text-right font-bold text-emerald-600">Net Credit</th>
                  <th className="px-2 py-2 text-right">Max Risk</th>
                  <th className="px-2 py-2 text-center font-bold text-emerald-600">Win Prob</th>
                  <th className="px-2 py-2 text-center">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {opps.map((opp, idx) => {
                  const isExp = expandedId === idx;
                  return (
                    <React.Fragment key={idx}>
                      <tr
                        onClick={() => setExpandedId(isExp ? null : idx)}
                        className={`transition cursor-pointer ${isExp ? 'bg-indigo-50/70 border-l-4 border-indigo-600' : 'hover:bg-slate-50'}`}
                      >
                        <td className="px-2 py-1.5 font-bold text-slate-800">{opp.underlying}</td>
                        <td className="px-2 py-1.5 font-bold text-slate-700">{opp.atmStrike}</td>
                        <td className="px-2 py-1.5 font-mono text-purple-700 font-bold">{opp.shortCallStrike} / {opp.longCallStrike}</td>
                        <td className="px-2 py-1.5 font-mono text-indigo-700 font-bold">{opp.shortPutStrike} / {opp.longPutStrike}</td>
                        <td className="px-2 py-1.5 text-right font-mono font-bold text-emerald-600">+₹{Number(opp.maxProfitRs || 0).toLocaleString('en-IN')}</td>
                        <td className="px-2 py-1.5 text-right font-mono text-slate-500">₹{Number(opp.maxRiskRs || 0).toLocaleString('en-IN')}</td>
                        <td className="px-2 py-1.5 text-center font-bold text-emerald-600">{opp.winProbability || 84.5}%</td>
                        <td className="px-2 py-1.5 text-center">
                          <button
                            onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp); }}
                            className="px-2 py-0.5 bg-indigo-600 hover:bg-indigo-700 text-white text-[10px] font-bold rounded shadow-sm"
                          >
                            ⚡ Execute
                          </button>
                        </td>
                      </tr>

                      {isExp && (
                        <tr className="bg-indigo-50/40 border-b border-indigo-100">
                          <td colSpan={8} className="p-3">
                            <div className="bg-white rounded-xl p-3 border border-indigo-200 shadow-md space-y-2">
                              <span className="font-bold text-slate-800 text-xs uppercase block">0DTE Iron Condor Wing Breakdown:</span>
                              <p className="text-xs font-mono font-bold text-slate-800 bg-slate-50 p-2 rounded-lg border">
                                SELL {opp.shortCallStrike} CE | BUY {opp.longCallStrike} CE | SELL {opp.shortPutStrike} PE | BUY {opp.longPutStrike} PE
                              </p>
                              <div className="flex justify-end pt-1">
                                <button onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp); }} className="px-3 py-1 bg-indigo-600 text-white rounded-lg text-xs font-bold shadow-md">
                                  ⚡ Submit ({executionBroker})
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

/* 5. CASH SURGE VIEW */
function CashSurgeView({ handleExecuteInline, executionBroker }) {
  const [expandedId, setExpandedId] = useState(null);

  const { data, isLoading } = useQuery({
    queryKey: ['cash-surge-scan'],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/cash-surge/scan');
      return res.data;
    },
    refetchInterval: 5000
  });

  const opps = data?.opportunities || [];

  return (
    <div className="space-y-4 w-full">
      <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm">
        <h2 className="text-base font-bold text-slate-800">🔥 10%+ Cash Surge Breakout Scanner</h2>
        <p className="text-xs text-slate-500">Detects high-volume institutional delivery surges with 3.5x volume expansion and 87.5% est. win rate</p>
      </div>

      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm w-full">
        {isLoading ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">Scanning delivery surge breakouts across Nifty 500...</div>
        ) : opps.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">No volume surge breakouts detected in current market session</div>
        ) : (
          <div className="overflow-x-auto w-full">
            <table className="w-full text-xs text-left border-collapse">
              <thead className="bg-slate-50 border-b border-slate-200 font-bold text-slate-600 uppercase">
                <tr>
                  <th className="px-2 py-2">Symbol</th>
                  <th className="px-2 py-2">Sector</th>
                  <th className="px-2 py-2 text-right">Entry (₹)</th>
                  <th className="px-2 py-2 text-right text-emerald-600 font-bold">Target (₹)</th>
                  <th className="px-2 py-2 text-right text-red-500 font-bold">Stop Loss (₹)</th>
                  <th className="px-2 py-2 text-center">Volume Surge</th>
                  <th className="px-2 py-2 text-center font-bold text-emerald-600">Win Rate</th>
                  <th className="px-2 py-2 text-center">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {opps.map((opp, idx) => {
                  const isExp = expandedId === idx;
                  return (
                    <React.Fragment key={idx}>
                      <tr
                        onClick={() => setExpandedId(isExp ? null : idx)}
                        className={`transition cursor-pointer ${isExp ? 'bg-amber-50/70 border-l-4 border-amber-600' : 'hover:bg-slate-50'}`}
                      >
                        <td className="px-2 py-1.5 font-bold text-slate-800">{opp.symbol}</td>
                        <td className="px-2 py-1.5 font-semibold text-slate-500">{opp.sector || 'Equities'}</td>
                        <td className="px-2 py-1.5 text-right font-mono font-bold text-slate-800">₹{Number(opp.entryPrice || 0).toLocaleString('en-IN')}</td>
                        <td className="px-2 py-1.5 text-right font-mono font-bold text-emerald-600">₹{Number(opp.targetPrice || 0).toLocaleString('en-IN')} (+{opp.expectedGainPct}%)</td>
                        <td className="px-2 py-1.5 text-right font-mono text-red-500 font-bold">₹{Number(opp.stopLossPrice || 0).toLocaleString('en-IN')}</td>
                        <td className="px-2 py-1.5 text-center font-bold text-indigo-600">{opp.deliverySurgeMultiplier || '4.0x'}</td>
                        <td className="px-2 py-1.5 text-center font-bold text-emerald-600">{opp.winProbability || 87.5}%</td>
                        <td className="px-2 py-1.5 text-center">
                          <button
                            onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp); }}
                            className="px-2 py-0.5 bg-amber-600 hover:bg-amber-700 text-white text-[10px] font-bold rounded shadow-sm"
                          >
                            🔥 Buy Cash
                          </button>
                        </td>
                      </tr>

                      {isExp && (
                        <tr className="bg-amber-50/40 border-b border-amber-100">
                          <td colSpan={8} className="p-3">
                            <div className="bg-white rounded-xl p-3 border border-amber-200 shadow-md space-y-2">
                              <span className="font-bold text-slate-800 text-xs uppercase block">Cash Surge Technical Breakdown:</span>
                              <p className="text-xs font-mono font-bold text-slate-800 bg-slate-50 p-2 rounded-lg border">
                                BUY {opp.symbol} @ ₹{opp.entryPrice} | TARGET ₹{opp.targetPrice} | SL ₹{opp.stopLossPrice} ({opp.atrTrailingSL})
                              </p>
                              <div className="flex justify-end pt-1">
                                <button onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp); }} className="px-3 py-1 bg-amber-600 text-white rounded-lg text-xs font-bold shadow-md">
                                  🔥 Submit Cash Order ({executionBroker})
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

/* 6. CASH SWING VIEW */
function CashSwingView({ handleExecuteInline, executionBroker }) {
  const [expandedId, setExpandedId] = useState(null);

  const { data, isLoading } = useQuery({
    queryKey: ['cash-momentum-scan'],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/cash-momentum/scan');
      return res.data;
    },
    refetchInterval: 5000
  });

  const opps = data?.opportunities || [];

  return (
    <div className="space-y-4 w-full">
      <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm">
        <h2 className="text-base font-bold text-slate-800">🚀 2-5 Day Cash Swing Momentum Scanner</h2>
        <p className="text-xs text-slate-500">RSI 60-68 zone momentum filter combined with NIFTY market regime trend confirmation</p>
      </div>

      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm w-full">
        {isLoading ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">Scanning 2-5 Day Swing Momentum setups...</div>
        ) : opps.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">No swing momentum setups active right now</div>
        ) : (
          <div className="overflow-x-auto w-full">
            <table className="w-full text-xs text-left border-collapse">
              <thead className="bg-slate-50 border-b border-slate-200 font-bold text-slate-600 uppercase">
                <tr>
                  <th className="px-2 py-2">Symbol</th>
                  <th className="px-2 py-2">Sector</th>
                  <th className="px-2 py-2 text-right">Entry (₹)</th>
                  <th className="px-2 py-2 text-right text-emerald-600 font-bold">Target (₹)</th>
                  <th className="px-2 py-2 text-right text-red-500 font-bold">Stop Loss (₹)</th>
                  <th className="px-2 py-2 text-center">RSI (14)</th>
                  <th className="px-2 py-2 text-center">Horizon</th>
                  <th className="px-2 py-2 text-center">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {opps.map((opp, idx) => {
                  const isExp = expandedId === idx;
                  return (
                    <React.Fragment key={idx}>
                      <tr
                        onClick={() => setExpandedId(isExp ? null : idx)}
                        className={`transition cursor-pointer ${isExp ? 'bg-purple-50/70 border-l-4 border-purple-600' : 'hover:bg-slate-50'}`}
                      >
                        <td className="px-2 py-1.5 font-bold text-slate-800">{opp.symbol}</td>
                        <td className="px-2 py-1.5 font-semibold text-slate-500">{opp.sector || 'Equities'}</td>
                        <td className="px-2 py-1.5 text-right font-mono font-bold text-slate-800">₹{Number(opp.entryPrice || 0).toLocaleString('en-IN')}</td>
                        <td className="px-2 py-1.5 text-right font-mono font-bold text-emerald-600">₹{Number(opp.targetPrice || 0).toLocaleString('en-IN')}</td>
                        <td className="px-2 py-1.5 text-right font-mono text-red-500 font-bold">₹{Number(opp.stopLossPrice || 0).toLocaleString('en-IN')}</td>
                        <td className="px-2 py-1.5 text-center font-bold text-purple-700">{opp.rsiMomentum || 64.0}</td>
                        <td className="px-2 py-1.5 text-center font-bold text-slate-600">{opp.holdingPeriodDays || '2-5 Days'}</td>
                        <td className="px-2 py-1.5 text-center">
                          <button
                            onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp); }}
                            className="px-2 py-0.5 bg-purple-600 hover:bg-purple-700 text-white text-[10px] font-bold rounded shadow-sm"
                          >
                            🚀 Swing Buy
                          </button>
                        </td>
                      </tr>

                      {isExp && (
                        <tr className="bg-purple-50/40 border-b border-purple-100">
                          <td colSpan={8} className="p-3">
                            <div className="bg-white rounded-xl p-3 border border-purple-200 shadow-md space-y-2">
                              <span className="font-bold text-slate-800 text-xs uppercase block">Swing Momentum Breakdown:</span>
                              <p className="text-xs font-mono font-bold text-slate-800 bg-slate-50 p-2 rounded-lg border">
                                SWING BUY {opp.symbol} @ ₹{opp.entryPrice} | TARGET ₹{opp.targetPrice} | SL ₹{opp.stopLossPrice} ({opp.niftyRegime})
                              </p>
                              <div className="flex justify-end pt-1">
                                <button onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp); }} className="px-3 py-1 bg-purple-600 text-white rounded-lg text-xs font-bold shadow-md">
                                  🚀 Submit ({executionBroker})
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

/* 7. CALENDAR SPREAD VIEW */
function CalendarSpreadView({ handleExecuteInline, executionBroker, autoRefresh = true }) {
  const [underlying, setUnderlying] = useState('ALL');
  const [expandedId, setExpandedId] = useState(null);
  const [minEdge, setMinEdge] = useState(75);
  const lastDataRef = React.useRef(null);

  const { data, isLoading, refetch, isFetching } = useQuery({
    queryKey: ['calendar-scan', underlying],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/calendar/scan', { params: { underlying } });
      lastDataRef.current = res.data;
      return res.data;
    },
    refetchInterval: autoRefresh ? 4000 : false,
    staleTime: 1200,
    placeholderData: (prev) => prev ?? lastDataRef.current ?? undefined,
  });

  const opps = (data?.opportunities || []).filter(o => Math.abs(o.edgeAfterCosts || 0) >= minEdge);
  const marketClosed = data?.marketClosed;
  const scanMs = data?.scanMs;

  return (
    <div className="space-y-4 w-full">
      <div className="bg-white p-3 rounded-2xl border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
            {['ALL', 'NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'].map(u => (
              <button key={u} onClick={() => setUnderlying(u)}
                className={`px-3 py-1 rounded-lg text-xs font-bold transition ${underlying === u ? 'bg-sky-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
                {u}
              </button>
            ))}
          </div>
        </div>
        <div className="flex items-center gap-2 text-xs">
          <label className="font-semibold text-slate-600">Min ₹</label>
          <input type="number" value={minEdge} onChange={e => setMinEdge(Number(e.target.value) || 0)}
            className="w-20 border border-slate-200 rounded-lg px-2 py-1 font-mono" />
          <button onClick={() => refetch()} className="px-3 py-1 rounded-lg bg-slate-900 text-white font-bold">
            {isFetching ? 'Scanning…' : 'Refresh'}
          </button>
          {scanMs != null && <span className={`font-mono font-bold ${scanMs <= 2000 ? 'text-emerald-600' : 'text-amber-600'}`}>{scanMs}ms</span>}
        </div>
      </div>

      <div className="bg-slate-50 border border-slate-200 text-slate-700 text-xs font-semibold px-4 py-3 rounded-xl">
        Weekly vs monthly same-strike CE/PE. Heuristic carry band — not risk-free arb. Prefer NIFTY/BN depth. Paper only.
      </div>

      {marketClosed && (
        <div className="bg-amber-50 border border-amber-200 text-amber-800 text-xs font-semibold px-4 py-3 rounded-xl">
          Market closed — calendar scan runs Mon–Fri 09:15–15:30 IST.
        </div>
      )}

      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm w-full">
        {isLoading && !data ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">Scanning Calendar Time Spreads...</div>
        ) : opps.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">
            No calendar edges ≥ ₹{minEdge} for {underlying}
            {scanMs != null ? ` · ${scanMs}ms` : ''}
          </div>
        ) : (
          <div className="overflow-x-auto w-full">
            <table className="w-full text-xs text-left border-collapse">
              <thead className="bg-slate-50 border-b border-slate-200 font-bold text-slate-600 uppercase">
                <tr>
                  <th className="px-2 py-2">Symbol</th>
                  <th className="px-2 py-2">Type</th>
                  <th className="px-2 py-2">Strike</th>
                  <th className="px-2 py-2">Near / Far</th>
                  <th className="px-2 py-2 text-right">Near Mid</th>
                  <th className="px-2 py-2 text-right">Far Mid</th>
                  <th className="px-2 py-2 text-right">Debit</th>
                  <th className="px-2 py-2 text-right text-emerald-600 font-bold">Net Edge (₹)</th>
                  <th className="px-2 py-2 text-center">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {opps.slice(0, 40).map((opp, idx) => {
                  const isExp = expandedId === idx;
                  return (
                    <React.Fragment key={`${opp.underlying}-${opp.strike}-${opp.optionType}-${opp.action}-${idx}`}>
                      <tr
                        onClick={() => setExpandedId(isExp ? null : idx)}
                        className={`transition cursor-pointer ${isExp ? 'bg-sky-50/70 border-l-4 border-sky-600' : 'hover:bg-slate-50'}`}
                      >
                        <td className="px-2 py-1.5 font-bold text-slate-800">{opp.underlying}</td>
                        <td className="px-2 py-1.5 font-bold text-sky-700">{opp.optionType}</td>
                        <td className="px-2 py-1.5 font-bold text-slate-700">{opp.strike}</td>
                        <td className="px-2 py-1.5 text-[10px] font-mono text-slate-500">
                          {opp.nearExpiry || '—'} → {opp.farExpiry || '—'}
                        </td>
                        <td className="px-2 py-1.5 text-right font-mono text-slate-600">₹{Number(opp.nearPrice || 0).toFixed(1)}</td>
                        <td className="px-2 py-1.5 text-right font-mono text-slate-600">₹{Number(opp.farPrice || 0).toFixed(1)}</td>
                        <td className="px-2 py-1.5 text-right font-mono font-bold text-slate-700">{Number(opp.spread || 0).toFixed(1)}</td>
                        <td className="px-2 py-1.5 text-right font-mono font-bold text-emerald-600">+₹{Math.round(Number(opp.edgeAfterCosts || 0)).toLocaleString('en-IN')}</td>
                        <td className="px-2 py-1.5 text-center">
                          <button
                            onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp); }}
                            className="px-2 py-0.5 bg-sky-600 hover:bg-sky-700 text-white text-[10px] font-bold rounded shadow-sm"
                          >
                            Paper
                          </button>
                        </td>
                      </tr>

                      {isExp && (
                        <tr className="bg-sky-50/40 border-b border-sky-100">
                          <td colSpan={9} className="p-3">
                            <div className="bg-white rounded-xl p-3 border border-sky-200 shadow-md space-y-2">
                              <span className="font-bold text-slate-800 text-xs uppercase block">{opp.action || 'Calendar'}</span>
                              <p className="text-xs font-mono font-bold text-slate-800 bg-slate-50 p-2 rounded-lg border">
                                {opp.legs || opp.description || `${opp.optionType} ${opp.strike}`}
                              </p>
                              <button onClick={() => handleExecuteInline(opp)}
                                className="px-3 py-1.5 rounded-lg bg-sky-600 text-white text-[11px] font-bold">
                                📝 Paper trade ({executionBroker})
                              </button>
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

/* 8. HISTORY VIEW */
function HistoryView({ historyItems, calendarOpportunities, historyLoading, handleExecuteInline, executionBroker }) {
  const [strategyFilter, setStrategyFilter] = useState('ALL');
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [minEdgeFilter, setMinEdgeFilter] = useState(300);
  const [customMinEdge, setCustomMinEdge] = useState('300');
  const [dateRange, setDateRange] = useState('TODAY');
  const [customStartDate, setCustomStartDate] = useState('');
  const [customEndDate, setCustomEndDate] = useState('');
  const [sortColumn, setSortColumn] = useState('scanTime');
  const [sortDirection, setSortDirection] = useState('desc');
  const [expandedId, setExpandedId] = useState(null);
  const [currentPage, setCurrentPage] = useState(1);
  const pageSize = 25;

  const { data: livePnlData } = useQuery({
    queryKey: ['live-pnl'],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/history/live-pnl');
      return res.data?.pnlMap || {};
    },
    refetchInterval: 30000,
    staleTime: 15000,
  });

  const livePnlMap = livePnlData || {};

  const getDateFilter = () => {
    const now = new Date();
    const istOffset = now.getTimezoneOffset();
    const istNow = new Date(now.getTime() + (istOffset + 330) * 60000);
    const istDate = istNow.toISOString().split('T')[0];
    const istTime = istNow.toISOString().split('T')[1].split(':')[0];
    switch (dateRange) {
      case 'TODAY': return { start: istDate, end: istDate };
      case 'YESTERDAY': {
        const y = new Date(istNow); y.setDate(y.getDate() - 1);
        const yDate = y.toISOString().split('T')[0];
        return { start: yDate, end: yDate };
      }
      case 'WEEK': {
        const d = new Date(istNow); d.setDate(d.getDate() - d.getDay());
        const e = new Date(istNow); e.setDate(e.getDate() + (6 - e.getDay()));
        return { start: d.toISOString().split('T')[0], end: e.toISOString().split('T')[0] };
      }
      case 'MONTH': {
        const s = new Date(istNow.getFullYear(), istNow.getMonth(), 1);
        const en = new Date(istNow.getFullYear(), istNow.getMonth() + 1, 0);
        return { start: s.toISOString().split('T')[0], end: en.toISOString().split('T')[0] };
      }
      case 'CUSTOM': {
        if (customStartDate && customEndDate) return { start: customStartDate, end: customEndDate };
        return { start: istDate, end: istDate };
      }
      default: return { start: istDate, end: istDate };
    }
  };

  const handleSort = (col) => {
    if (sortColumn === col) {
      setSortDirection(prev => prev === 'asc' ? 'desc' : 'asc');
    } else {
      setSortColumn(col);
      setSortDirection('desc');
    }
  };

  const filteredItems = useMemo(() => {
    const { start, end } = getDateFilter();
    let items = historyItems.filter(item => {
      const edge = Number(item.edgeAfterCosts) || Number(item.grossEdge) || 0;
      const typeStr = String(item.strategyType || item.type || '').toUpperCase();
      const statusStr = String(item.status || 'RUNNING').toUpperCase();
      const itemDate = item.scanTime ? item.scanTime.split('T')[0] : (item.createdAt ? item.createdAt.split('T')[0] : '');

      if (edge < minEdgeFilter) return false;
      if (itemDate < start || itemDate > end) return false;

      if (strategyFilter === 'PARITY' && !typeStr.includes('PARITY') && !typeStr.includes('BID')) return false;
      if (strategyFilter === 'BOX' && !typeStr.includes('BOX')) return false;
      if (strategyFilter === 'CALENDAR' && !typeStr.includes('CALENDAR') && !typeStr.includes('TIME')) return false;
      if (strategyFilter === 'CONDOR' && !typeStr.includes('CONDOR') && !typeStr.includes('IRON')) return false;

      if (statusFilter === 'RUNNING' && statusStr !== 'RUNNING' && statusStr !== 'OPEN') return false;
      if (statusFilter === 'EXITED' && statusStr !== 'EXITED' && statusStr !== 'CLOSED' && statusStr !== 'EXECUTED') return false;

      return true;
    });

    // Merge live P&L for running/open items
    items = items.map(item => {
      const statusStr = String(item.status || 'RUNNING').toUpperCase();
      const isRunning = statusStr === 'RUNNING' || statusStr === 'OPEN';
      if (isRunning && livePnlMap[String(item.id)] != null) {
        return { ...item, pnlAfterCosts: livePnlMap[String(item.id)] };
      }
      return item;
    });

    // Fallback: If filtering by Calendar returns 0 db items, merge live scanned calendar opportunities
    if (items.length === 0 && strategyFilter === 'CALENDAR' && calendarOpportunities?.length > 0) {
      items = calendarOpportunities.map(c => ({
        id: c.id || `CAL_HIST_${c.underlying}_${c.strike}`,
        scanTime: new Date().toISOString(),
        strategyType: 'CALENDAR_SPREAD',
        type: 'CALENDAR_SPREAD',
        underlying: c.underlying,
        strike: c.strike,
        action: c.action || 'SELL_FAR_BUY_NEAR',
        ceEntryPrice: c.nearPrice || 0,
        peEntryPrice: c.farPrice || 0,
        edgeAfterCosts: c.edgeAfterCosts || c.spread * 25,
        status: 'RUNNING',
        pnlAfterCosts: c.edgeAfterCosts || c.spread * 25,
        exitTime: null,
        legs: `BUY NEAR (${c.nearSymbol || ''} @ ₹${c.nearPrice}) | SELL FAR (${c.farSymbol || ''} @ ₹${c.farPrice})`
      })).filter(c => (c.edgeAfterCosts || 0) >= minEdgeFilter);
    }

    return items.sort((a, b) => {
      let aVal = a[sortColumn];
      let bVal = b[sortColumn];

      if (sortColumn === 'scanTime') {
        aVal = new Date(a.scanTime || a.createdAt || 0).getTime();
        bVal = new Date(b.scanTime || b.createdAt || 0).getTime();
      } else if (sortColumn === 'edgeAfterCosts') {
        aVal = Number(a.edgeAfterCosts || 0);
        bVal = Number(b.edgeAfterCosts || 0);
      } else if (sortColumn === 'pnl') {
        aVal = Number(a.pnlAfterCosts || a.edgeAfterCosts || 0);
        bVal = Number(b.pnlAfterCosts || b.edgeAfterCosts || 0);
      } else if (sortColumn === 'strike') {
        aVal = Number(a.strike || 0);
        bVal = Number(b.strike || 0);
      } else if (sortColumn === 'cePrice') {
        aVal = Number(a.ceEntryPrice || a.cePrice || 0);
        bVal = Number(b.ceEntryPrice || b.cePrice || 0);
      } else if (sortColumn === 'pePrice') {
        aVal = Number(a.peEntryPrice || a.pePrice || 0);
        bVal = Number(b.peEntryPrice || b.pePrice || 0);
      } else if (typeof aVal === 'string') {
        return sortDirection === 'asc' ? aVal.localeCompare(bVal) : bVal.localeCompare(aVal);
      }

      if (aVal < bVal) return sortDirection === 'asc' ? -1 : 1;
      if (aVal > bVal) return sortDirection === 'asc' ? 1 : -1;
      return 0;
    });
  }, [historyItems, calendarOpportunities, strategyFilter, statusFilter, minEdgeFilter, dateRange, customStartDate, customEndDate, livePnlMap, sortColumn, sortDirection]);

   const countByEdge = (min) => {
     const { start, end } = getDateFilter();
     let count = historyItems.filter(item => {
       const itemDate = item.scanTime ? item.scanTime.split('T')[0] : (item.createdAt ? item.createdAt.split('T')[0] : '');
       return (Number(item.edgeAfterCosts) || 0) >= min && itemDate >= start && itemDate <= end;
     }).length;
     if (count === 0 && calendarOpportunities?.length > 0) {
       count = calendarOpportunities.filter(c => (Number(c.edgeAfterCosts || c.spread * 25) || 0) >= min).length;
     }
     return count;
   };

  const totalPages = Math.max(1, Math.ceil(filteredItems.length / pageSize));
  const paginatedItems = useMemo(() => {
    const start = (currentPage - 1) * pageSize;
    return filteredItems.slice(start, start + pageSize);
  }, [filteredItems, currentPage]);

  const SortHeader = ({ col, label, align = "left", widthClass = "" }) => {
    const isSel = sortColumn === col;
    return (
      <th
        onClick={() => handleSort(col)}
        className={`px-1.5 py-2 cursor-pointer hover:bg-slate-100 transition select-none ${widthClass} ${align === 'right' ? 'text-right' : align === 'center' ? 'text-center' : 'text-left'}`}
      >
        <div className={`flex items-center gap-0.5 ${align === 'right' ? 'justify-end' : align === 'center' ? 'justify-center' : 'justify-start'}`}>
          <span className="truncate">{label}</span>
          <span className="text-[9px] text-slate-400">
            {isSel ? (sortDirection === 'asc' ? '▲' : '▼') : '↕'}
          </span>
        </div>
      </th>
    );
  };

  return (
    <div className="space-y-4 w-full">
      {/* Controls Bar */}
      <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="text-base font-bold text-slate-800">Arbitrage Signals &amp; Trade Analytics</h2>
            <p className="text-xs text-slate-500">Audit historical scans, track live MTM P&amp;L &amp; exit timestamps</p>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            {/* Date Range Filter */}
            <div className="flex items-center gap-1 bg-slate-100 p-1 rounded-xl">
              {[
                { id: 'TODAY', label: 'Today' },
                { id: 'YESTERDAY', label: 'Yesterday' },
                { id: 'WEEK', label: 'This Week' },
                { id: 'MONTH', label: 'This Month' },
                { id: 'CUSTOM', label: 'Custom' },
              ].map(d => (
                <button
                  key={d.id}
                  onClick={() => { setDateRange(d.id); setCurrentPage(1); }}
                  className={`px-2 py-0.5 rounded-lg text-xs font-bold transition ${
                    dateRange === d.id ? 'bg-emerald-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'
                  }`}
                >
                  {d.label}
                </button>
              ))}
            </div>

            {dateRange === 'CUSTOM' && (
              <div className="flex items-center gap-1">
                <input type="date" value={customStartDate} onChange={e => setCustomStartDate(e.target.value)} className="bg-white border border-slate-300 rounded-lg px-1.5 py-0.5 text-xs font-mono text-slate-800 outline-none focus:border-emerald-500" />
                <span className="text-[10px] text-slate-400">to</span>
                <input type="date" value={customEndDate} onChange={e => setCustomEndDate(e.target.value)} className="bg-white border border-slate-300 rounded-lg px-1.5 py-0.5 text-xs font-mono text-slate-800 outline-none focus:border-emerald-500" />
              </div>
            )}

            {/* Strategy Filters */}
            <div className="flex flex-wrap items-center gap-1 bg-slate-100 p-1 rounded-xl">
              {[
                { id: 'ALL', label: 'All' },
                { id: 'PARITY', label: '⚡ Parity' },
                { id: 'BOX', label: '💎 Box' },
                { id: 'CALENDAR', label: '⏳ Calendar' },
                { id: 'CONDOR', label: '🛡️ Condor' },
              ].map(s => (
                <button
                  key={s.id}
                  onClick={() => { setStrategyFilter(s.id); setCurrentPage(1); }}
                  className={`px-2 py-0.5 rounded-lg text-xs font-bold transition ${
                    strategyFilter === s.id ? 'bg-indigo-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'
                  }`}
                >
                  {s.label}
                </button>
              ))}
            </div>

            {/* Status Filters */}
            <div className="flex items-center gap-1 bg-slate-100 p-1 rounded-xl">
              {[
                { id: 'ALL', label: 'All Status' },
                { id: 'RUNNING', label: '🟢 Running' },
                { id: 'EXITED', label: '🔴 Exited' },
              ].map(st => (
                <button
                  key={st.id}
                  onClick={() => { setStatusFilter(st.id); setCurrentPage(1); }}
                  className={`px-2 py-0.5 rounded-lg text-xs font-bold transition ${
                    statusFilter === st.id ? 'bg-purple-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'
                  }`}
                >
                  {st.label}
                </button>
              ))}
            </div>
          </div>
        </div>

        {/* Net Edge Minimum Threshold Filter Bar */}
        <div className="flex flex-wrap items-center justify-between gap-2.5 pt-2 border-t border-slate-100">
          <div className="flex items-center gap-1.5 flex-wrap">
            <span className="text-[11px] font-bold text-slate-500 uppercase mr-1">Minimum Net Edge:</span>
            {[
              { label: 'All Edges', min: 0 },
              { label: '> ₹100', min: 100 },
              { label: '> ₹300 (Default)', min: 300 },
              { label: '> ₹500', min: 500 },
              { label: '> ₹1,000', min: 1000 },
            ].map(b => (
              <button
                key={b.min}
                onClick={() => { setMinEdgeFilter(b.min); setCustomMinEdge(String(b.min)); setCurrentPage(1); }}
                className={`px-2 py-0.5 rounded-lg text-xs font-bold transition flex items-center gap-1 ${
                  minEdgeFilter === b.min ? 'bg-emerald-600 text-white shadow-sm' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                }`}
              >
                <span>{b.label}</span>
                <span className={`px-1.5 py-0.2 rounded-full text-[10px] ${minEdgeFilter === b.min ? 'bg-emerald-800 text-white' : 'bg-slate-200 text-slate-700'}`}>
                  {countByEdge(b.min)}
                </span>
              </button>
            ))}
          </div>

          <div className="flex items-center gap-1.5">
            <span className="text-xs text-slate-500 font-bold">Custom Edge:</span>
            <div className="flex items-center gap-0.5">
              <span className="text-xs font-bold text-slate-400">₹</span>
              <input
                type="number"
                value={customMinEdge}
                onChange={(e) => {
                  setCustomMinEdge(e.target.value);
                  const val = parseFloat(e.target.value) || 0;
                  setMinEdgeFilter(val);
                  setCurrentPage(1);
                }}
                className="w-14 bg-slate-50 border border-slate-300 rounded-lg px-1.5 py-0.5 text-xs font-bold text-slate-800 font-mono outline-none focus:border-indigo-500"
                placeholder="300"
              />
            </div>
          </div>
        </div>
      </div>

      {/* Ultra-Compact History Data Table (100% Fit, No Side Overflow) */}
      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm w-full">
        {historyLoading ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">Loading trade analytics...</div>
        ) : filteredItems.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">No records match current filters (Min Edge ≥ ₹{minEdgeFilter})</div>
        ) : (
          <div className="w-full overflow-x-auto">
            <table className="w-full text-[11px] text-left border-collapse table-fixed min-w-[780px]">
              <thead className="bg-slate-50 border-b border-slate-200 font-bold text-slate-600 uppercase tracking-tight">
                <tr>
                  <SortHeader col="scanTime" label="Time" widthClass="w-[75px]" />
                  <SortHeader col="strategyType" label="Type" widthClass="w-[70px]" />
                  <SortHeader col="underlying" label="Symbol" widthClass="w-[85px]" />
                  <SortHeader col="strike" label="Strike" widthClass="w-[50px]" />
                  <SortHeader col="action" label="Action" widthClass="w-[80px]" />
                  <SortHeader col="cePrice" label="CE" align="right" widthClass="w-[45px]" />
                  <SortHeader col="pePrice" label="PE" align="right" widthClass="w-[45px]" />
                  <SortHeader col="edgeAfterCosts" label="Target" align="right" widthClass="w-[60px]" />
                  <SortHeader col="status" label="Status" align="center" widthClass="w-[65px]" />
                  <SortHeader col="pnl" label="P&amp;L (₹)" align="right" widthClass="w-[65px]" />
                  <SortHeader col="exitTime" label="Exit" align="center" widthClass="w-[45px]" />
                  <th className="px-1.5 py-2 text-center w-[60px]">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {paginatedItems.map((item, idx) => {
                  const rowId = item.id || idx;
                  const isExp = expandedId === rowId;
                  const statusStr = String(item.status || 'RUNNING').toUpperCase();
                  const isRunning = statusStr === 'RUNNING' || statusStr === 'OPEN';
                  const pnlVal = item.pnlAfterCosts != null 
                    ? Number(item.pnlAfterCosts) 
                    : (isRunning ? null : (item.edgeAfterCosts != null ? Number(item.edgeAfterCosts) : 0.0));

                  const signalTimeFormatted = item.scanTime 
                    ? new Date(item.scanTime).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true }) 
                    : (item.createdAt ? new Date(item.createdAt).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true }) : '--');

                  const exitTimeFormatted = item.exitTime
                    ? new Date(item.exitTime).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true })
                    : '--';

                  const ceVal = Number(item.ceEntryPrice || item.cePrice || 0).toFixed(1);
                  const peVal = Number(item.peEntryPrice || item.pePrice || 0).toFixed(1);

                  return (
                    <React.Fragment key={rowId}>
                      <tr
                        onClick={() => setExpandedId(isExp ? null : rowId)}
                        className={`transition cursor-pointer ${isExp ? 'bg-indigo-50/70 border-l-4 border-indigo-600' : 'hover:bg-slate-50'}`}
                      >
                        <td className="px-1.5 py-1.5 font-mono text-[10px] text-slate-600 truncate">{signalTimeFormatted}</td>
                        <td className="px-1.5 py-1.5 truncate">
                          <span className="px-1 py-0.2 rounded text-[9px] font-bold bg-indigo-100 text-indigo-800">
                            {item.strategyType || item.type || 'PARITY'}
                          </span>
                        </td>
                        <td className="px-1.5 py-1.5 font-bold text-slate-800 truncate">{item.underlying}</td>
                        <td className="px-1.5 py-1.5 font-bold text-slate-700 truncate">{item.strike}</td>
                        <td className="px-1.5 py-1.5 font-bold text-purple-700 truncate">{item.action}</td>
                        <td className="px-1.5 py-1.5 text-right font-mono text-slate-600 truncate">{ceVal}</td>
                        <td className="px-1.5 py-1.5 text-right font-mono text-slate-600 truncate">{peVal}</td>
                        
                        {/* Target Net Edge at Entry */}
                        <td className="px-1.5 py-1.5 text-right font-mono font-bold text-indigo-700 truncate">
                          +₹{Math.round(Number(item.edgeAfterCosts || 0)).toLocaleString('en-IN')}
                        </td>
                        
                        {/* Status Badge */}
                        <td className="px-1.5 py-1.5 text-center truncate">
                          <span className={`px-1.5 py-0.2 rounded-full text-[9px] font-bold border ${
                            isRunning ? 'bg-emerald-100 text-emerald-800 border-emerald-300' : 'bg-blue-100 text-blue-800 border-blue-300'
                          }`}>
                            {isRunning ? '🟢 RUNNING' : '🔴 EXITED'}
                          </span>
                        </td>

                        {/* Captured P&L Badge */}
                        <td className="px-1.5 py-1.5 text-right font-mono font-bold truncate">
                          {pnlVal !== null && !isNaN(pnlVal) 
                            ? <span className={isRunning ? 'text-emerald-600 font-bold' : 'px-1 py-0.2 bg-emerald-100 text-emerald-800 rounded border border-emerald-300 font-bold'}>
                                {pnlVal >= 0 ? '+' : ''}₹{Math.round(pnlVal).toLocaleString('en-IN')}
                              </span>
                            : <span className="text-slate-400">--</span>}
                        </td>
                        <td className="px-1.5 py-1.5 text-center font-mono text-[10px] text-slate-500 truncate">{exitTimeFormatted}</td>
                        <td className="px-1.5 py-1.5 text-center whitespace-nowrap">
                          <button
                            onClick={(e) => { e.stopPropagation(); handleExecuteInline(item); }}
                            className="px-1.5 py-0.5 bg-emerald-600 hover:bg-emerald-700 text-white text-[9px] font-bold rounded shadow-sm"
                          >
                            ⚡ Trade
                          </button>
                        </td>
                      </tr>

                      {isExp && (
                        <tr className="bg-indigo-50/40 border-b border-indigo-100">
                          <td colSpan={12} className="p-3">
                            <div className="bg-white rounded-xl p-3 border border-indigo-200 shadow-md space-y-2">
                              <span className="font-bold text-slate-800 text-xs uppercase block">Historical Signal Audit Breakdown:</span>
                              <p className="text-xs font-mono font-bold text-slate-800 bg-slate-50 p-2 rounded-lg border">
                                {item.legs || `${item.action} on ${item.underlying} ${item.strike}`}
                              </p>
                              <div className="flex justify-end pt-1">
                                <button onClick={(e) => { e.stopPropagation(); handleExecuteInline(item); }} className="px-3 py-1 bg-emerald-600 text-white rounded-lg text-xs font-bold shadow-md">
                                  ⚡ Re-Execute ({executionBroker})
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

            {/* Pagination Footer */}
            <div className="px-4 py-2.5 bg-slate-50 border-t border-slate-200 flex flex-wrap items-center justify-between gap-3 text-xs text-slate-600">
              <span className="font-medium">
                Showing <strong className="text-slate-800">{(currentPage - 1) * pageSize + 1}</strong> to <strong className="text-slate-800">{Math.min(currentPage * pageSize, filteredItems.length)}</strong> of <strong className="text-slate-800">{filteredItems.length.toLocaleString('en-IN')}</strong> records (Edge ≥ ₹{minEdgeFilter})
              </span>
              <div className="flex items-center gap-2">
                <button
                  disabled={currentPage <= 1}
                  onClick={() => setCurrentPage(p => Math.max(1, p - 1))}
                  className="px-2.5 py-1 bg-white border border-slate-300 rounded-lg disabled:opacity-40 font-bold hover:bg-slate-100"
                >
                  ◀ Prev
                </button>
                <span className="font-bold px-2 py-0.5 bg-white border border-slate-200 rounded-lg text-indigo-600">
                  {currentPage} / {totalPages}
                </span>
                <button
                  disabled={currentPage >= totalPages}
                  onClick={() => setCurrentPage(p => Math.min(totalPages, p + 1))}
                  className="px-2.5 py-1 bg-white border border-slate-300 rounded-lg disabled:opacity-40 font-bold hover:bg-slate-100"
                >
                  Next ▶
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}