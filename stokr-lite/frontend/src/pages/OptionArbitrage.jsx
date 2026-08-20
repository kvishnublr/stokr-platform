import React, { useState, useEffect, useMemo, useRef } from 'react';
import { useQuery } from '@tanstack/react-query';
import client from '../api/client';

// Black-Scholes helpers for the "Today" MTM curve (theoretical value if spot moved to X
// right now, same remaining time-to-expiry -- not a decay simulation). Mirrors
// BlackScholesCalculator.java on the backend so both curves use the same math.
function bsErf(x) {
  const a1 = 0.254829592, a2 = -0.284496736, a3 = 1.421413741, a4 = -1.453152027, a5 = 1.061405429, p = 0.3275911;
  const sign = x >= 0 ? 1 : -1;
  x = Math.abs(x);
  const t = 1 / (1 + p * x);
  const y = 1 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-x * x);
  return sign * y;
}
function bsNormCDF(x) { return 0.5 * (1 + bsErf(x / Math.SQRT2)); }
function bsCallPrice(S, K, T, r, sigma) {
  if (T <= 0) return Math.max(S - K, 0);
  if (sigma <= 0) return Math.max(S - K * Math.exp(-r * T), 0);
  const d1 = (Math.log(S / K) + (r + 0.5 * sigma * sigma) * T) / (sigma * Math.sqrt(T));
  const d2 = d1 - sigma * Math.sqrt(T);
  return S * bsNormCDF(d1) - K * Math.exp(-r * T) * bsNormCDF(d2);
}
function bsPutPrice(S, K, T, r, sigma) {
  if (T <= 0) return Math.max(K - S, 0);
  if (sigma <= 0) return Math.max(K * Math.exp(-r * T) - S, 0);
  const d1 = (Math.log(S / K) + (r + 0.5 * sigma * sigma) * T) / (sigma * Math.sqrt(T));
  const d2 = d1 - sigma * Math.sqrt(T);
  return K * Math.exp(-r * T) * bsNormCDF(-d2) - S * bsNormCDF(-d1);
}

function fmtTime(dt) {
  if (!dt) return '--';
  const d = new Date(dt);
  const day = d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short' });
  const time = d.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true });
  return `${day} ${time}`;
}

let _toastListeners = [];
let _toastId = 0;
function _notifyToast(toast) { _toastListeners.forEach(fn => fn(toast)); }
export function showToast(message, type = 'info', duration, title) {
  const id = ++_toastId;
  // Errors get more time on screen by default -- they need to actually be read, not
  // just glimpsed for 4 seconds while looking away from a trading screen.
  const resolvedDuration = duration != null ? duration : (type === 'error' ? 9000 : type === 'warning' ? 7000 : 4500);
  _notifyToast({ id, message, type, duration: resolvedDuration, title });
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

const STRATEGY_LABELS = {
  BID_PARITY: '⚡ Bid Parity',
  BOX_SPREAD: '💎 Box Spread',
  VERTICAL_SPREAD: '📐 Vertical',
  BUTTERFLY_SPREAD: '🦋 Butterfly',
  CONDOR_SPREAD: '🎯 Condor',
  IRON_CONDOR: '🛡️ Iron Condor',
};

const TOAST_STYLES = {
  success: { bg: 'bg-white', border: 'border-emerald-400', bar: 'bg-emerald-500', icon: '✅', iconBg: 'bg-emerald-100', iconText: 'text-emerald-600', titleText: 'text-emerald-700', defaultTitle: 'Success' },
  error: { bg: 'bg-white', border: 'border-red-400', bar: 'bg-red-500', icon: '❌', iconBg: 'bg-red-100', iconText: 'text-red-600', titleText: 'text-red-700', defaultTitle: 'Error' },
  warning: { bg: 'bg-white', border: 'border-amber-400', bar: 'bg-amber-500', icon: '⚠️', iconBg: 'bg-amber-100', iconText: 'text-amber-600', titleText: 'text-amber-700', defaultTitle: 'Warning' },
  info: { bg: 'bg-white', border: 'border-indigo-400', bar: 'bg-indigo-500', icon: 'ℹ️', iconBg: 'bg-indigo-100', iconText: 'text-indigo-600', titleText: 'text-indigo-700', defaultTitle: 'Notice' },
};

function notifyBrowser(title, body) {
  if ('Notification' in window) {
    if (Notification.permission === 'granted') {
      new Notification(title, { body, icon: '/favicon.ico' });
    } else if (Notification.permission !== 'denied') {
      Notification.requestPermission().then(p => {
        if (p === 'granted') new Notification(title, { body, icon: '/favicon.ico' });
      });
    }
  }
}

function ToastCard({ t, dismiss }) {
  const [entered, setEntered] = useState(false);
  useEffect(() => {
    const raf = requestAnimationFrame(() => setEntered(true));
    return () => cancelAnimationFrame(raf);
  }, []);
  const style = TOAST_STYLES[t.type] || TOAST_STYLES.info;

  return (
    <div
      className={`pointer-events-auto w-[380px] max-w-[92vw] rounded-2xl border-2 ${style.border} ${style.bg} shadow-2xl overflow-hidden transition-all duration-300 ease-out ${entered ? 'translate-x-0 opacity-100' : 'translate-x-8 opacity-0'}`}
    >
      <div className="flex items-start gap-3 p-4">
        <div className={`shrink-0 w-9 h-9 rounded-full ${style.iconBg} flex items-center justify-center text-lg`}>
          {style.icon}
        </div>
        <div className="flex-1 min-w-0">
          <div className={`text-sm font-black ${style.titleText}`}>{t.title || style.defaultTitle}</div>
          <div className="text-[13px] text-slate-700 mt-0.5 break-words leading-snug">{t.message}</div>
        </div>
        <button onClick={() => dismiss(t.id)} className="shrink-0 text-slate-400 hover:text-slate-600 text-lg leading-none px-1">
          ×
        </button>
      </div>
      {t.duration > 0 && (
        <div className="h-1 w-full bg-slate-100">
          <div
            className={`h-full ${style.bar}`}
            style={{ animation: `stokr-toast-shrink ${t.duration}ms linear forwards` }}
          />
        </div>
      )}
    </div>
  );
}

function ToastContainer({ toasts, dismiss }) {
  if (!toasts || toasts.length === 0) return null;
  return (
    <>
      <style>{`@keyframes stokr-toast-shrink { from { width: 100%; } to { width: 0%; } }`}</style>
      <div className="fixed top-5 right-5 z-[9999] space-y-3 pointer-events-none">
        {toasts.map(t => <ToastCard key={t.id} t={t} dismiss={dismiss} />)}
      </div>
    </>
  );
}

const ALL_U = ['ALL', 'NIFTY', 'BANKNIFTY', 'MIDCPNIFTY', 'FINNIFTY'];

export default function OptionArbitrage() {
  const { toasts, dismiss: dismissToast } = useToastState();
  const urlParams = new URLSearchParams(window.location.search);
  const initialTab = urlParams.get('tab') || 'live';
  const [activeTab, setActiveTab] = useState(initialTab);
  const [underlyings, setUnderlyings] = useState(['ALL']);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [executionBroker, setExecutionBroker] = useState('PAPER');
  const [isTestingBroker, setIsTestingBroker] = useState(false);
  const [maxSignals, setMaxSignals] = useState(() => {
    const saved = localStorage.getItem('stokr_max_signals');
    return saved ? parseInt(saved) : 300;
  });

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
    if ('Notification' in window && Notification.permission === 'default') {
      Notification.requestPermission();
    }
  }, []);

  // Primary Live Arbitrage Signals Query
  const { data: liveData, isLoading: scanLoading } = useQuery({
    queryKey: ['option-arb-live', underlyings],
    queryFn: async () => {
      const uParam = underlyings.includes('ALL') ? 'ALL' : underlyings.join(',');
      const res = await client.get('/option-arbitrage/scan', { params: { underlying: uParam } });
      return res.data;
    },
    refetchInterval: autoRefresh ? 5000 : false,
    staleTime: 2000,
  });

  // Calendar Scan Query Fallback
  const { data: calendarLiveData } = useQuery({
    queryKey: ['calendar-scan-fallback'],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/calendar/scan', { params: { underlying: 'ALL' } });
      return res.data;
    },
    refetchInterval: 30000
  });

  // Vertical Spread Scan Query
  const { data: verticalLiveData } = useQuery({
    queryKey: ['vertical-scan-fallback'],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/vertical-spread/scan', { params: { underlying: 'ALL' } });
      return res.data;
    },
    refetchInterval: 30000
  });

  // Butterfly Spread Scan Query
  const { data: butterflyLiveData } = useQuery({
    queryKey: ['butterfly-scan-fallback'],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/butterfly-spread/scan', { params: { underlying: 'ALL' } });
      return res.data;
    },
    refetchInterval: 30000
  });

  // Condor Spread Scan Query
  const { data: condorSpreadLiveData } = useQuery({
    queryKey: ['condor-spread-scan-fallback'],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/condor-spread/scan', { params: { underlying: 'ALL' } });
      return res.data;
    },
    refetchInterval: 30000
  });

  // History & Signals Log Query - fetches up to 50K, server-side date filtered
  const { data: historyData, isLoading: historyLoading } = useQuery({
    queryKey: ['option-arb-history', maxSignals],
    queryFn: async () => {
      const now = new Date();
      const istNow = new Date(now.getTime() + (now.getTimezoneOffset() + 330) * 60000);
      const today = istNow.toISOString().split('T')[0];
      const res = await client.get('/option-arbitrage/history', {
        params: { size: maxSignals, startDate: today, endDate: today }
      });
      return res.data;
    },
    refetchInterval: autoRefresh ? 30000 : false,
    staleTime: 10000,
  });

  const opportunities = liveData?.opportunities || [];
  const calendarOpportunities = calendarLiveData?.opportunities || [];
  const verticalOpportunities = verticalLiveData?.opportunities || [];
  const butterflyOpportunities = butterflyLiveData?.opportunities || [];
  const condorSpreadOpportunities = condorSpreadLiveData?.opportunities || [];
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
    // Cash Surge/Swing signals are plain stock picks (symbol, no underlying/strike) --
    // route to the equity execution endpoint instead of the options-shaped one.
    const isCashEquity = !opp.underlying && !!opp.symbol;
    try {
      if (isCashEquity) {
        const res = await client.post('/option-arbitrage/cash-trade/execute', {
          symbol: opp.symbol,
          strategyType: opp.strategyType || (opp.rsiMomentum != null ? 'CASH_SWING' : 'CASH_SURGE'),
          targetPrice: opp.targetPrice || 0,
          stopLossPrice: opp.stopLossPrice || 0,
          broker: executionBroker
        });
        const data = res.data;
        if (data?.status === 'SUCCESS') {
          const msg = `BUY ${data.quantity} ${data.symbol} @ ₹${data.entryPrice?.toFixed(1)}`;
          showToast(msg, 'success', undefined, 'Trade Entered');
          notifyBrowser('Trade Entered', msg);
        } else {
          showToast(data?.message || 'Unknown error', 'error', undefined, 'Trade Failed');
          notifyBrowser('Trade Failed', data?.message || 'Unknown error');
        }
        return;
      }

      const res = await client.post('/option-arbitrage/paper-trade/execute', {
        opportunityId: opp.id || undefined,
        underlying: opp.underlying || opp.symbol,
        strike: opp.strike || opp.atmStrike || 0,
        action: opp.action || 'BUY',
        strategyType: opp.strategyType || opp.type || 'ARBITRAGE',
        description: opp.description || opp.legs || '',
        edgeAfterCosts: opp.edgeAfterCosts || opp.boxEdgeInr || 0,
        ceEntryPrice: opp.ceEntryPrice || opp.cePrice || 0,
        peEntryPrice: opp.peEntryPrice || opp.pePrice || 0,
        spotPrice: opp.spotPrice || 0,
        futuresPrice: opp.futuresPrice || 0,
        legList: opp.legList || undefined,
        lots: lots,
        broker: executionBroker
      });
      const data = res.data;
      if (data?.status === 'SUCCESS') {
        const msg = `${data.underlying} ${data.strike} entered! CE=₹${data.ceEntryPrice?.toFixed(1)} PE=₹${data.peEntryPrice?.toFixed(1)}`;
        showToast(msg, 'success', undefined, 'Trade Entered');
        notifyBrowser('Trade Entered', msg);
      } else if (data?.status === 'ERROR') {
        showToast(data.message || 'Unknown error', 'error', undefined, 'Trade Failed');
        notifyBrowser('Trade Failed', data.message || 'Unknown error');
      } else {
        showToast(`Order submitted via ${executionBroker}!`, 'success', undefined, 'Order Submitted');
        notifyBrowser('Order Submitted', `${opp.underlying} order via ${executionBroker}`);
      }
    } catch (e) {
      const errMsg = e.response?.data?.message || e.message || 'Network error';
      showToast(errMsg, 'error', undefined, 'Trade Failed');
      notifyBrowser('Trade Failed', errMsg);
    }
  };

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
              <option value="MOTILALOSWAL">🟣 Motilal Oswal</option>
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

      {/* Tab Navigation Bar */}
      <div className="bg-white rounded-2xl p-2 border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2 flex-wrap">
          {[
            { key: 'live', label: '⚡ Live Scan' },
            { key: 'bidparity', label: '🎯 Bid Parity' },
            { key: 'box', label: '💎 Box Spread' },
            { key: 'vertical', label: '📐 Vertical Spread' },
            { key: 'butterfly', label: '🦋 Butterfly Spread' },
            { key: 'condorspread', label: '🎯 Condor Spread' },
            { key: 'autotrade', label: '🤖 Auto-Trade' },
            { key: 'papertrades', label: '📋 Paper Trades' },
            { key: 'ironcondor', label: '🛡️ Iron Condor' },
            { key: 'cashsurge', label: '🔥 Cash Surge' },
            { key: 'cashswing', label: '🚀 Cash Swing' },
            { key: 'history', label: '📊 History' },
          ].map(tab => (
            <button
              key={tab.key}
              onClick={() => setActiveTab(tab.key)}
              className={`px-3.5 py-2 rounded-xl text-xs font-bold transition flex items-center gap-2 ${
                activeTab === tab.key
                  ? 'bg-indigo-600 text-white shadow-md shadow-indigo-200'
                  : 'text-slate-600 hover:bg-slate-100'
              }`}
            >
              <span>{tab.label}</span>
              {tab.key === 'live' && <span className={`px-1.5 py-0.5 rounded-full text-[10px] ${activeTab === tab.key ? 'bg-white/20 text-white' : 'bg-slate-100 text-slate-700'}`}>{opportunities.length}</span>}
              {tab.key === 'history' && <span className={`px-1.5 py-0.5 rounded-full text-[10px] ${activeTab === tab.key ? 'bg-white/20 text-white' : 'bg-slate-100 text-slate-700'}`}>{historyItems.length}</span>}
            </button>
          ))}
        </div>

        <div className="flex items-center gap-2 px-2">
          <div className="flex items-center gap-1">
            <span className="text-[10px] font-bold text-slate-500 uppercase">Max Signals:</span>
            <select
              value={maxSignals}
              onChange={(e) => {
                const v = parseInt(e.target.value);
                setMaxSignals(v);
                localStorage.setItem('stokr_max_signals', String(v));
              }}
              className="px-2 py-1 text-[10px] font-bold border border-slate-300 rounded-lg bg-white outline-none"
            >
              <option value={100}>100</option>
              <option value={250}>250</option>
              <option value={500}>500</option>
              <option value={1000}>1000</option>
              <option value={2000}>2000</option>
              <option value={5000}>5000</option>
              <option value={10000}>10000</option>
              <option value={50000}>50000</option>
              <option value={200000}>ALL</option>
            </select>
          </div>
          <button
            onClick={() => setAutoRefresh(!autoRefresh)}
            className={`px-3 py-1.5 rounded-xl text-xs font-bold transition ${
              autoRefresh ? 'bg-emerald-100 text-emerald-800 border border-emerald-300' : 'bg-slate-100 text-slate-600'
            }`}
          >
            {autoRefresh ? '⚡ Live Tick: ON' : '⏱️ Auto-Refresh: OFF'}
          </button>
        </div>
      </div>

      {/* Live/Broker Positions moved to the dedicated Positions page -- kept here would just
          duplicate it right below the header on every tab of this already-busy page. */}
      <CashPositionsSection />

      {/* Active Tab View Rendering */}
      <div className="space-y-5 w-full">
        {activeTab === 'live' && (
          <SignalsView
            underlyings={underlyings}
            toggleUnderlying={toggleUnderlying}
            opportunities={opportunities}
            calendarOpportunities={calendarOpportunities}
            verticalOpportunities={verticalOpportunities}
            butterflyOpportunities={butterflyOpportunities}
            condorSpreadOpportunities={condorSpreadOpportunities}
            summary={summary}
            scanLoading={scanLoading}
            handleExecuteInline={handleExecuteInline}
            executionBroker={executionBroker}
          />
        )}

        {activeTab === 'bidparity' && <BidParityView underlyings={underlyings} toggleUnderlying={toggleUnderlying} handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} />}
        {activeTab === 'autotrade' && (
          <div className="space-y-4">
            <AutoExecSettingsPanel />
            <AutoRollSettingsPanel />
          </div>
        )}
        {activeTab === 'papertrades' && <PaperTradesView />}
        {activeTab === 'box' && <BoxSpreadView underlyings={underlyings} toggleUnderlying={toggleUnderlying} handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} />}
        {activeTab === 'vertical' && <VerticalSpreadView handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} />}
        {activeTab === 'butterfly' && <ButterflySpreadView handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} />}
        {activeTab === 'condorspread' && <CondorSpreadView handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} />}
        {activeTab === 'ironcondor' && <IronCondorView handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} />}
        {activeTab === 'cashsurge' && <CashSurgeView handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} />}
        {activeTab === 'cashswing' && <CashSwingView handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} />}
        {activeTab === 'history' && <HistoryView calendarOpportunities={calendarOpportunities} handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} underlyings={underlyings} />}
      </div>
    </div>
  );
}

/* Auto-Execute Settings Panel */
function AutoExecSettingsPanel() {
  const [settings, setSettings] = useState(null);
  const [saving, setSaving] = useState(false);

  const { data } = useQuery({
    queryKey: ['autoExecSettings'],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/auto-execute/settings');
      return res.data;
    },
    refetchInterval: 30000,
  });

  useEffect(() => { if (data) setSettings(data); }, [data]);

  const updateSetting = async (key, value) => {
    setSaving(true);
    try {
      await client.post(`/option-arbitrage/auto-execute/settings?key=${encodeURIComponent(key)}&value=${encodeURIComponent(String(value))}`);
      setSettings(prev => ({ ...prev, [key]: value }));
      showToast(`Setting updated: ${key} = ${value}`, 'success');
    } catch (e) {
      showToast('Failed to update setting', 'error');
    }
    setSaving(false);
  };

  if (!settings) return null;

  return (
    <div className="bg-white rounded-2xl border border-slate-200 shadow-sm p-4 space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-sm font-black text-slate-800 flex items-center gap-2">
            <span className="text-lg">🤖</span> Auto-Execute Engine
          </h3>
          <p className="text-[10px] text-slate-500 mt-0.5">Master switch + shared risk/exit controls. Each strategy's own entry thresholds (min edge, lots, per underlying) now live on that strategy's own "⚡ Auto-Trade" sub-tab.</p>
        </div>
        <div className="flex items-center gap-3">
          <span className="text-[10px] text-slate-500">Engine:</span>
          <button
            onClick={() => updateSetting('enabled', !settings.enabled)}
            className={`relative w-11 h-6 rounded-full transition-colors ${settings.enabled ? 'bg-emerald-500' : 'bg-slate-300'}`}
          >
            <span className={`absolute top-0.5 left-0.5 w-5 h-5 bg-white rounded-full shadow transition-transform ${settings.enabled ? 'translate-x-5' : ''}`} />
          </button>
          <span className={`text-xs font-bold ${settings.enabled ? 'text-emerald-600' : 'text-slate-400'}`}>
            {settings.enabled ? 'ON' : 'OFF'}
          </span>
        </div>
      </div>

      <div className="flex flex-wrap gap-4 items-center pt-1 border-t border-slate-100">
        <div className="space-y-1">
          <label className="text-[9px] font-bold text-slate-500 uppercase">Max Open Positions</label>
          <input type="number" value={settings.maxOpenPositions || 5} min={1} max={20}
            onChange={(e) => setSettings(prev => ({ ...prev, maxOpenPositions: Number(e.target.value) }))}
            onBlur={(e) => updateSetting('maxOpenPositions', e.target.value)}
            className="w-16 px-2 py-1 text-xs font-mono border border-slate-300 rounded-lg bg-white outline-none" />
        </div>
        <div className="space-y-1">
          <label className="text-[9px] font-bold text-slate-500 uppercase">Max Daily Loss (₹)</label>
          <input type="number" value={settings.maxDailyLoss || 5000}
            onChange={(e) => setSettings(prev => ({ ...prev, maxDailyLoss: Number(e.target.value) }))}
            onBlur={(e) => updateSetting('maxDailyLoss', e.target.value)}
            className="w-24 px-2 py-1 text-xs font-mono border border-slate-300 rounded-lg bg-white outline-none" />
        </div>
        <div className="space-y-1">
          <label className="text-[9px] font-bold text-slate-500 uppercase">Broker</label>
          <select value={settings.broker || 'NAVIA'}
            onChange={(e) => updateSetting('broker', e.target.value)}
            className="px-2 py-1 text-xs font-bold border border-slate-300 rounded-lg bg-white outline-none">
            <option value="NAVIA">Navia Markets</option>
            <option value="MOTILALOSWAL">Motilal Oswal</option>
            <option value="ZERODHA">Zerodha Kite</option>
            <option value="DHAN">DhanHQ</option>
            <option value="FYERS">Fyers API</option>
            <option value="PAPER">Paper Trading</option>
          </select>
        </div>
      </div>

      {/* Auto-Exit Settings */}
      <div className="flex flex-wrap gap-4 items-center pt-2 border-t border-slate-100">
        <div className="flex items-center gap-3">
          <span className="text-[10px] font-bold text-slate-500 uppercase">Auto Exit on Target:</span>
          <button
            onClick={() => updateSetting('autoExitEnabled', !settings.autoExitEnabled)}
            className={`relative w-11 h-6 rounded-full transition-colors ${settings.autoExitEnabled ? 'bg-emerald-500' : 'bg-slate-300'}`}
          >
            <span className={`absolute top-0.5 left-0.5 w-5 h-5 bg-white rounded-full shadow transition-transform ${settings.autoExitEnabled ? 'translate-x-5' : ''}`} />
          </button>
          <span className={`text-xs font-bold ${settings.autoExitEnabled ? 'text-emerald-600' : 'text-slate-400'}`}>
            {settings.autoExitEnabled ? 'ON' : 'OFF'}
          </span>
        </div>
        <div className="space-y-1">
          <label className="text-[9px] font-bold text-slate-500 uppercase">Exit at % Target</label>
          <input type="number" value={settings.autoExitThresholdPct || 90} min={50} max={99}
            onChange={(e) => setSettings(prev => ({ ...prev, autoExitThresholdPct: Number(e.target.value) }))}
            onBlur={(e) => updateSetting('autoExitThresholdPct', e.target.value)}
            className="w-16 px-2 py-1 text-xs font-mono border border-slate-300 rounded-lg bg-white outline-none" />
          <span className="text-[9px] text-slate-400">%</span>
        </div>
        <div className="text-[9px] text-slate-400 italic">
          When a position reaches {settings.autoExitThresholdPct || 90}% of target edge, auto square-off (no re-entry)
        </div>
      </div>

      {/* Stop-Loss Settings */}
      <div className="flex flex-wrap gap-4 items-center pt-2 border-t border-slate-100">
        <div className="flex items-center gap-3">
          <span className="text-[10px] font-bold text-slate-500 uppercase">Stop-Loss:</span>
          <button
            onClick={() => updateSetting('stopLossEnabled', !settings.stopLossEnabled)}
            className={`relative w-11 h-6 rounded-full transition-colors ${settings.stopLossEnabled ? 'bg-red-500' : 'bg-slate-300'}`}
          >
            <span className={`absolute top-0.5 left-0.5 w-5 h-5 bg-white rounded-full shadow transition-transform ${settings.stopLossEnabled ? 'translate-x-5' : ''}`} />
          </button>
          <span className={`text-xs font-bold ${settings.stopLossEnabled ? 'text-red-600' : 'text-slate-400'}`}>
            {settings.stopLossEnabled ? 'ON' : 'OFF'}
          </span>
        </div>
        <div className="space-y-1">
          <label className="text-[9px] font-bold text-slate-500 uppercase">Loss at % of Target</label>
          <input type="number" value={settings.stopLossPct || 50} min={10} max={100}
            onChange={(e) => setSettings(prev => ({ ...prev, stopLossPct: Number(e.target.value) }))}
            onBlur={(e) => updateSetting('stopLossPct', e.target.value)}
            className="w-16 px-2 py-1 text-xs font-mono border border-slate-300 rounded-lg bg-white outline-none" />
          <span className="text-[9px] text-slate-400">%</span>
        </div>
        <div className="text-[9px] text-slate-400 italic">
          When a position's loss reaches {settings.stopLossPct || 50}% of target edge, auto square-off
        </div>
      </div>

      {/* Roll-Over Info */}
      <div className="flex flex-wrap gap-4 items-center pt-2 border-t border-slate-100">
        <div className="flex items-center gap-3">
          <span className="text-[10px] font-bold text-slate-500 uppercase">Roll-Over:</span>
          <span className="px-2 py-1 bg-indigo-50 text-indigo-700 text-[10px] font-bold rounded-lg border border-indigo-200">
            🔄 MANUAL — Click "Roll CE+PE" on any OPEN Bid Parity position (not available for multi-leg spreads)
          </span>
        </div>
        <div className="text-[9px] text-slate-400 italic">
          Only CE+PE legs are rolled. FUT position stays, saving ~₹384 per rollover.
        </div>
      </div>
    </div>
  );
}

/* Auto-Roll on Breakeven Breach -- Butterfly only, configured per underlying (same pattern
   as the Auto-Execute Engine cards above). Off by default; each symbol has its own
   enable/breach-window/max-rolls so e.g. NIFTY can run this while BANKNIFTY stays manual. */
function AutoRollSettingsPanel() {
  const [settings, setSettings] = useState(null);

  const { data } = useQuery({
    queryKey: ['autoExecSettings'],
    queryFn: async () => (await client.get('/option-arbitrage/auto-execute/settings')).data,
    refetchInterval: 30000,
  });

  useEffect(() => { if (data) setSettings(data); }, [data]);

  const updateSetting = async (key, value) => {
    try {
      await client.post(`/option-arbitrage/auto-execute/settings?key=${encodeURIComponent(key)}&value=${encodeURIComponent(String(value))}`);
      setSettings(prev => ({ ...prev, [key]: value }));
      showToast(`Setting updated: ${key} = ${value}`, 'success');
    } catch (e) {
      showToast('Failed to update setting', 'error');
    }
  };

  if (!settings) return null;

  const underlyings = [
    { key: 'nifty', label: 'NIFTY', dot: 'bg-blue-500' },
    { key: 'banknifty', label: 'BANKNIFTY', dot: 'bg-violet-500' },
    { key: 'finnifty', label: 'FINNIFTY', dot: 'bg-rose-500' },
    { key: 'midcpnifty', label: 'MIDCPNIFTY', dot: 'bg-amber-500' },
  ];
  const activeCount = underlyings.filter(u => settings[u.key + 'AutoRollEnabled']).length;

  return (
    <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
      <div className="bg-gradient-to-r from-purple-500 to-fuchsia-600 px-4 py-3.5 flex items-center justify-between">
        <div className="flex items-center gap-2.5">
          <div className="w-8 h-8 rounded-xl bg-white/20 backdrop-blur flex items-center justify-center text-base">🔄</div>
          <div>
            <h3 className="text-sm font-black text-white">Auto-Roll on Breakeven Breach</h3>
            <p className="text-[10px] text-white/80 mt-0.5">Butterfly only — auto-close on sustained breach, re-entry needs your confirm</p>
          </div>
        </div>
        <div className="flex items-center gap-1.5 bg-white/15 backdrop-blur px-3 py-1.5 rounded-full">
          <span className={`w-1.5 h-1.5 rounded-full ${activeCount > 0 ? 'bg-emerald-300 animate-pulse' : 'bg-white/40'}`} />
          <span className="text-[11px] font-bold text-white">{activeCount}/4 active</span>
        </div>
      </div>

      <div className="p-4 space-y-3">
        <p className="text-[10px] text-slate-400">
          If spot sits outside the profit zone continuously for the breach window, the position closes
          automatically; a re-centered replacement is proposed but needs a one-click confirm to actually
          enter. After Max Rolls, it rides on Auto-Exit/Stop-Loss instead.
        </p>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-3">
          {underlyings.map(u => {
            const enabled = !!settings[u.key + 'AutoRollEnabled'];
            return (
              <div key={u.key}
                className={`rounded-xl border p-3 space-y-2.5 transition-all ${enabled ? 'bg-purple-50 border-purple-300 shadow-sm shadow-purple-200' : 'bg-slate-50 border-slate-200 hover:border-slate-300'}`}>
                <div className="flex items-center justify-between">
                  <span className="flex items-center gap-1.5 text-xs font-black text-slate-800">
                    <span className={`w-1.5 h-1.5 rounded-full ${u.dot}`} />
                    {u.label}
                  </span>
                  <button
                    onClick={() => updateSetting(u.key + 'AutoRollEnabled', !enabled)}
                    className={`relative w-9 h-5 rounded-full transition-colors ${enabled ? 'bg-purple-500' : 'bg-slate-300'}`}
                  >
                    <span className={`absolute top-0.5 left-0.5 w-4 h-4 bg-white rounded-full shadow transition-transform ${enabled ? 'translate-x-4' : ''}`} />
                  </button>
                </div>
                <div className="grid grid-cols-2 gap-2">
                  <div className="space-y-1">
                    <label className="text-[8px] font-bold text-slate-400 uppercase tracking-wide">Breach (min)</label>
                    <input type="number" value={settings[u.key + 'AutoRollBreachMinutes'] ?? 5} min={1} max={60}
                      onChange={(e) => setSettings(prev => ({ ...prev, [u.key + 'AutoRollBreachMinutes']: Number(e.target.value) }))}
                      onBlur={(e) => updateSetting(u.key + 'AutoRollBreachMinutes', e.target.value)}
                      className="w-full px-2 py-1 text-xs font-mono font-bold border border-slate-300 rounded-lg bg-white focus:ring-2 focus:ring-purple-500 focus:border-purple-500 outline-none" />
                  </div>
                  <div className="space-y-1">
                    <label className="text-[8px] font-bold text-slate-400 uppercase tracking-wide">Max Rolls</label>
                    <input type="number" value={settings[u.key + 'AutoRollMaxRolls'] ?? 2} min={1} max={5}
                      onChange={(e) => setSettings(prev => ({ ...prev, [u.key + 'AutoRollMaxRolls']: Number(e.target.value) }))}
                      onBlur={(e) => updateSetting(u.key + 'AutoRollMaxRolls', e.target.value)}
                      className="w-full px-2 py-1 text-xs font-mono font-bold border border-slate-300 rounded-lg bg-white focus:ring-2 focus:ring-purple-500 focus:border-purple-500 outline-none" />
                  </div>
                </div>
                <div className={`text-[9px] font-semibold ${enabled ? 'text-purple-700' : 'text-slate-400'}`}>
                  {enabled ? `✓ Watching — closes after ${settings[u.key + 'AutoRollBreachMinutes'] ?? 5}min outside zone` : 'Not monitored'}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

/* Per-strategy Auto-Trade entry settings -- reusable across every strategy's own "Auto-Trade"
   sub-tab. Each strategy (Bid Parity/Box/Vertical/Butterfly/Condor/Iron Condor) reads/writes
   its own settings-key prefix (e.g. "box" -> boxNiftyEnabled/boxNiftyMinEdge/boxNiftyLots),
   completely independent of every other strategy's thresholds for the same underlying. */
function StrategyAutoTradePanel({ prefix, label, accent = 'indigo' }) {
  const [settings, setSettings] = useState(null);

  const { data } = useQuery({
    queryKey: ['autoExecSettings'],
    queryFn: async () => (await client.get('/option-arbitrage/auto-execute/settings')).data,
    refetchInterval: 30000,
  });

  useEffect(() => { if (data) setSettings(data); }, [data]);

  const updateSetting = async (key, value) => {
    try {
      await client.post(`/option-arbitrage/auto-execute/settings?key=${encodeURIComponent(key)}&value=${encodeURIComponent(String(value))}`);
      setSettings(prev => ({ ...prev, [key]: value }));
      showToast(`Setting updated: ${key} = ${value}`, 'success');
    } catch (e) {
      showToast('Failed to update setting', 'error');
    }
  };

  if (!settings) return null;

  const underlyings = [
    { key: prefix + 'Nifty', label: 'NIFTY', dot: 'bg-blue-500' },
    { key: prefix + 'Banknifty', label: 'BANKNIFTY', dot: 'bg-violet-500' },
    { key: prefix + 'Finnifty', label: 'FINNIFTY', dot: 'bg-rose-500' },
    { key: prefix + 'Midcpnifty', label: 'MIDCPNIFTY', dot: 'bg-amber-500' },
  ];
  const activeCount = underlyings.filter(u => settings[u.key + 'Enabled']).length;
  const theme = {
    indigo: { grad: 'from-indigo-500 to-violet-600', bg: 'bg-indigo-50', border: 'border-indigo-300', text: 'text-indigo-700', btn: 'bg-indigo-500', ring: 'focus:ring-indigo-500 focus:border-indigo-500', glow: 'shadow-indigo-200' },
    emerald: { grad: 'from-emerald-500 to-teal-600', bg: 'bg-emerald-50', border: 'border-emerald-300', text: 'text-emerald-700', btn: 'bg-emerald-500', ring: 'focus:ring-emerald-500 focus:border-emerald-500', glow: 'shadow-emerald-200' },
  }[accent] || { grad: 'from-indigo-500 to-violet-600', bg: 'bg-indigo-50', border: 'border-indigo-300', text: 'text-indigo-700', btn: 'bg-indigo-500', ring: 'focus:ring-indigo-500 focus:border-indigo-500', glow: 'shadow-indigo-200' };

  return (
    <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
      <div className={`bg-gradient-to-r ${theme.grad} px-4 py-3.5 flex items-center justify-between`}>
        <div className="flex items-center gap-2.5">
          <div className="w-8 h-8 rounded-xl bg-white/20 backdrop-blur flex items-center justify-center text-base">⚡</div>
          <div>
            <h3 className="text-sm font-black text-white">{label} Auto-Trade</h3>
            <p className="text-[10px] text-white/80 mt-0.5">Entry thresholds for this strategy only, independent of every other tab</p>
          </div>
        </div>
        <div className="flex items-center gap-1.5 bg-white/15 backdrop-blur px-3 py-1.5 rounded-full">
          <span className={`w-1.5 h-1.5 rounded-full ${activeCount > 0 ? 'bg-emerald-300 animate-pulse' : 'bg-white/40'}`} />
          <span className="text-[11px] font-bold text-white">{activeCount}/4 active</span>
        </div>
      </div>

      <div className="p-4 space-y-3">
        <p className="text-[10px] text-slate-400">
          Requires the master Engine switch (main Auto-Trade tab) to be ON, with a live broker selected there.
        </p>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-3">
          {underlyings.map(u => {
            const enabled = !!settings[u.key + 'Enabled'];
            return (
              <div key={u.key}
                className={`rounded-xl border p-3 space-y-2.5 transition-all ${enabled ? `${theme.bg} ${theme.border} shadow-sm ${theme.glow}` : 'bg-slate-50 border-slate-200 hover:border-slate-300'}`}>
                <div className="flex items-center justify-between">
                  <span className="flex items-center gap-1.5 text-xs font-black text-slate-800">
                    <span className={`w-1.5 h-1.5 rounded-full ${u.dot}`} />
                    {u.label}
                  </span>
                  <button
                    onClick={() => updateSetting(u.key + 'Enabled', !enabled)}
                    className={`relative w-9 h-5 rounded-full transition-colors ${enabled ? theme.btn : 'bg-slate-300'}`}
                  >
                    <span className={`absolute top-0.5 left-0.5 w-4 h-4 bg-white rounded-full shadow transition-transform ${enabled ? 'translate-x-4' : ''}`} />
                  </button>
                </div>
                <div className="grid grid-cols-2 gap-2">
                  <div className="space-y-1">
                    <label className="text-[8px] font-bold text-slate-400 uppercase tracking-wide">Min Edge ₹</label>
                    <input type="number" value={settings[u.key + 'MinEdge'] ?? 800}
                      onChange={(e) => setSettings(prev => ({ ...prev, [u.key + 'MinEdge']: Number(e.target.value) }))}
                      onBlur={(e) => updateSetting(u.key + 'MinEdge', e.target.value)}
                      className={`w-full px-2 py-1 text-xs font-mono font-bold border border-slate-300 rounded-lg bg-white focus:ring-2 outline-none ${theme.ring}`} />
                  </div>
                  <div className="space-y-1">
                    <label className="text-[8px] font-bold text-slate-400 uppercase tracking-wide">Lots</label>
                    <input type="number" value={settings[u.key + 'Lots'] ?? 1} min={1} max={10}
                      onChange={(e) => setSettings(prev => ({ ...prev, [u.key + 'Lots']: Number(e.target.value) }))}
                      onBlur={(e) => updateSetting(u.key + 'Lots', e.target.value)}
                      className={`w-full px-2 py-1 text-xs font-mono font-bold border border-slate-300 rounded-lg bg-white focus:ring-2 outline-none ${theme.ring}`} />
                  </div>
                </div>
                <div className={`text-[9px] font-semibold ${enabled ? theme.text : 'text-slate-400'}`}>
                  {enabled ? `✓ Live — fires above ₹${settings[u.key + 'MinEdge'] ?? 800} edge` : 'Not monitored'}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

/* Live Positions Section — standalone, always visible, 2s tick-by-tick refresh */
function LivePositionsSection({ executionBroker, defaultExpanded = false }) {
  const [collapsed, setCollapsed] = useState(!defaultExpanded);
  const [rollingId, setRollingId] = useState(null);
  const [closingId, setClosingId] = useState(null);
  const [expandedPosId, setExpandedPosId] = useState(null);
  // Defaults to whatever mode the Execution Broker dropdown is currently in, so switching
  // to a live broker doesn't leave old paper positions looking like they might be real --
  // "Live Positions" was showing paper trades with no way to tell them apart or filter
  // them out. Still overridable via the pills below.
  const [brokerFilter, setBrokerFilter] = useState(executionBroker === 'PAPER' ? 'PAPER' : 'LIVE');
  const prevExecBroker = useRef(executionBroker);
  useEffect(() => {
    if (executionBroker !== prevExecBroker.current) {
      setBrokerFilter(executionBroker === 'PAPER' ? 'PAPER' : 'LIVE');
      prevExecBroker.current = executionBroker;
    }
  }, [executionBroker]);

  const { data, refetch } = useQuery({
    queryKey: ['livePositions'],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/live-positions');
      return res.data;
    },
    refetchInterval: 2000,
    staleTime: 1000,
  });

  const allPositions = data?.positions || [];
  const isPaper = (p) => !p.broker || p.broker === 'PAPER';
  const positions = brokerFilter === 'ALL' ? allPositions
    : brokerFilter === 'PAPER' ? allPositions.filter(isPaper)
    : allPositions.filter(p => !isPaper(p));
  const filteredTotalPnl = positions.reduce((s, p) => s + (p.currentPnl || 0), 0);
  const openPositions = positions.filter(p => p.status === 'OPEN');

  const handleRollover = async (positionId, underlying, strike) => {
    if (!window.confirm(`Roll CE+PE for ${underlying} ${strike}?\n\nFUT position stays, only options are rolled.`)) return;
    setRollingId(positionId);
    try {
      const res = await client.post(`/option-arbitrage/rollover/${positionId}`);
      alert(`✅ Rolled! P&L: ₹${res.data.pnl}`);
      refetch();
    } catch (e) {
      alert(`❌ Failed: ${e.response?.data?.error || e.message}`);
    } finally {
      setRollingId(null);
    }
  };

  const handleClosePosition = async (positionId, underlying, strike, broker) => {
    const brokerLabel = broker === 'PAPER' || !broker ? 'PAPER' : broker;
    if (!window.confirm(`Close ${underlying} ${strike} (${brokerLabel})?\n\nThis places market closing orders for every leg right now.`)) return;
    setClosingId(positionId);
    try {
      const res = await client.post(`/option-arbitrage/positions/${positionId}/exit`);
      alert(`✅ Closed! P&L: ₹${res.data.pnl}`);
      refetch();
    } catch (e) {
      alert(`❌ Failed: ${e.response?.data?.message || e.response?.data?.error || e.message}`);
    } finally {
      setClosingId(null);
    }
  };

  const statusColor = (s) => {
    switch (s) {
      case 'OPEN': return 'bg-emerald-100 text-emerald-800 border-emerald-300';
      case 'EXECUTING': return 'bg-amber-100 text-amber-800 border-amber-300';
      case 'PARTIAL': return 'bg-orange-100 text-orange-800 border-orange-300';
      case 'FAILED': return 'bg-red-100 text-red-800 border-red-300';
      case 'CLOSED': return 'bg-slate-100 text-slate-600 border-slate-300';
      default: return 'bg-slate-100 text-slate-600 border-slate-300';
    }
  };

  if (allPositions.length === 0 && !defaultExpanded) return null;

  return (
    <div className="space-y-4">
      <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
        <div className="px-4 py-3 bg-gradient-to-r from-indigo-50 via-violet-50 to-white border-b border-indigo-100 flex items-center justify-between cursor-pointer" onClick={() => setCollapsed(!collapsed)}>
          <div className="flex items-center gap-2 flex-wrap">
            <span className="text-lg">📊</span>
            <h3 className="text-sm font-black text-slate-800">
              {brokerFilter === 'PAPER' ? 'Paper Positions' : brokerFilter === 'LIVE' ? 'Live Positions' : 'All Positions'}
            </h3>
            <span className="px-2 py-0.5 bg-indigo-100 text-indigo-700 text-[10px] font-bold rounded-full">{positions.length}</span>
            <span className="px-2 py-0.5 bg-slate-100 text-slate-500 text-[9px] font-bold rounded-full">2s tick</span>
            {positions.length > 0 && (
              <span className={`px-2.5 py-0.5 text-[11px] font-black rounded-full border ${filteredTotalPnl >= 0 ? 'bg-emerald-50 text-emerald-700 border-emerald-200' : 'bg-red-50 text-red-700 border-red-200'}`}>
                {brokerFilter === 'PAPER' ? 'Paper' : 'Total'} P&L: ₹{Math.round(filteredTotalPnl).toLocaleString('en-IN')}
              </span>
            )}
            <div className="flex items-center gap-0.5 bg-white border border-slate-200 rounded-full p-0.5" onClick={(e) => e.stopPropagation()}>
              {[
                { id: 'LIVE', label: '🔴 Live' },
                { id: 'PAPER', label: '📄 Paper' },
                { id: 'ALL', label: 'All' },
              ].map(f => (
                <button key={f.id} onClick={() => setBrokerFilter(f.id)}
                  className={`px-2 py-0.5 rounded-full text-[9px] font-bold transition ${brokerFilter === f.id ? 'bg-indigo-600 text-white shadow-sm' : 'text-slate-500 hover:text-slate-800'}`}>
                  {f.label}
                </button>
              ))}
            </div>
          </div>
          <div className="flex items-center gap-2">
            <button onClick={(e) => { e.stopPropagation(); refetch(); }} className="px-2 py-1 bg-white border border-slate-200 hover:bg-indigo-50 hover:border-indigo-300 text-slate-600 text-[10px] font-bold rounded-lg transition">Refresh</button>
            <span className="text-slate-400 text-xs">{collapsed ? '▼' : '▲'}</span>
          </div>
        </div>

        {!collapsed && positions.length === 0 && (
          <div className="p-8 text-center text-slate-400 text-sm font-semibold">
            No {brokerFilter === 'PAPER' ? 'paper' : brokerFilter === 'LIVE' ? 'live' : ''} positions right now
          </div>
        )}

        {!collapsed && positions.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-[11px] text-left">
              <thead className="bg-slate-50 border-b border-slate-200 text-slate-600 uppercase tracking-tight font-bold">
                <tr>
                  <th className="px-3 py-2">Time</th>
                  <th className="px-3 py-2">Broker</th>
                  <th className="px-3 py-2">Strategy</th>
                  <th className="px-3 py-2">Underlying</th>
                  <th className="px-3 py-2">Strike</th>
                  <th className="px-3 py-2">Action</th>
                  <th className="px-3 py-2 text-right">CE Entry</th>
                  <th className="px-3 py-2 text-right">PE Entry</th>
                  <th className="px-3 py-2 text-right">FUT Entry</th>
                  <th className="px-3 py-2 text-right">Edge</th>
                  <th className="px-3 py-2 text-center">Edge Progress</th>
                  <th className="px-3 py-2 text-right">Live P&amp;L</th>
                  <th className="px-3 py-2 text-right">Lots</th>
                  <th className="px-3 py-2 text-center">Status</th>
                  <th className="px-3 py-2 text-center">Rollover</th>
                  <th className="px-3 py-2 text-center">Close</th>
                  <th className="px-3 py-2 text-center">Error</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {positions.map(p => {
                  const pnl = p.currentPnl || 0;
                  const target = p.targetEdge || 0;
                  const captured = p.edgeCaptured || 0;
                  const PAYOFF_CHART_TYPES = ['BUTTERFLY_SPREAD', 'BOX_SPREAD', 'VERTICAL_SPREAD', 'CONDOR_SPREAD', 'IRON_CONDOR'];
                  const canShowPayoff = PAYOFF_CHART_TYPES.includes(p.strategyType) && Array.isArray(p.legList) && p.legList.length >= 2;
                  const isExpanded = expandedPosId === p.id;
                  return (
                    <React.Fragment key={p.id}>
                    <tr onClick={() => canShowPayoff && setExpandedPosId(isExpanded ? null : p.id)}
                      className={`hover:bg-slate-50 ${captured >= 90 ? 'bg-amber-50' : ''} ${canShowPayoff ? 'cursor-pointer' : ''} ${isExpanded ? 'bg-fuchsia-50/60' : ''}`}>
                      <td className="px-3 py-2 font-mono text-[10px] text-slate-600">{fmtTime(p.enteredAt)}</td>
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
                      <td className="px-3 py-2 text-purple-700 font-bold text-[10px]">{p.action?.substring(0, 18)}</td>
                      {p.isMultiLeg ? (
                        <td colSpan={3} className="px-3 py-2 text-[9px] text-slate-600 font-mono">
                          {Array.isArray(p.legList) && p.legList.length > 0
                            ? p.legList.map((leg, i) => (
                                <span key={i} className={`inline-block mr-2 ${leg.side === 'BUY' ? 'text-emerald-700' : 'text-red-600'}`}>
                                  {leg.strike}{leg.optionType} {leg.side}@{Number(leg.price || 0).toFixed(1)}
                                </span>
                              ))
                            : `${p.legList?.length || 0}-leg spread (no futures)`}
                        </td>
                      ) : (
                        <>
                          <td className="px-3 py-2 text-right font-mono">₹{p.ceEntryPrice?.toFixed(1) || '--'}</td>
                          <td className="px-3 py-2 text-right font-mono">₹{p.peEntryPrice?.toFixed(1) || '--'}</td>
                          <td className="px-3 py-2 text-right font-mono">₹{p.futEntryPrice?.toFixed(1) || '--'}</td>
                        </>
                      )}
                      <td className="px-3 py-2 text-right font-mono font-bold text-indigo-700">₹{target?.toFixed(0) || '--'}</td>
                      <td className="px-3 py-2">
                        <div className="flex items-center gap-1.5">
                          <div className="flex-1 h-2 bg-slate-200 rounded-full overflow-hidden">
                            <div className={`h-full rounded-full transition-all ${captured >= 90 ? 'bg-amber-500' : captured >= 50 ? 'bg-emerald-500' : 'bg-blue-500'}`}
                              style={{ width: `${Math.min(100, captured)}%` }} />
                          </div>
                          <span className={`text-[10px] font-bold ${captured >= 90 ? 'text-amber-600' : captured >= 50 ? 'text-emerald-600' : 'text-blue-600'}`}>{captured}%</span>
                        </div>
                      </td>
                      <td className={`px-3 py-2 text-right font-mono font-bold ${pnl >= 0 ? 'text-emerald-600' : 'text-red-600'}`}>
                        {pnl !== 0 ? `₹${Math.round(pnl).toLocaleString('en-IN')}` : '--'}
                      </td>
                      <td className="px-3 py-2 text-right font-bold">{p.lots}</td>
                      <td className="px-3 py-2 text-center">
                        <span className={`px-2 py-0.5 rounded-full text-[9px] font-bold border ${statusColor(p.status)}`}>{p.status}</span>
                      </td>
                      <td className="px-3 py-2 text-center">
                        {p.status === 'OPEN' && !p.isMultiLeg && (
                          <button
                            disabled={rollingId === p.id}
                            onClick={() => handleRollover(p.id, p.underlying, p.strike)}
                            className={`px-2 py-1 rounded-lg text-[9px] font-bold transition border ${
                              rollingId === p.id
                                ? 'bg-slate-200 text-slate-500 border-slate-300 cursor-wait'
                                : 'bg-indigo-50 text-indigo-700 border-indigo-300 hover:bg-indigo-100 hover:border-indigo-400'
                            }`}
                          >
                            {rollingId === p.id ? '⏳ Rolling...' : '🔄 Roll CE+PE'}
                          </button>
                        )}
                      </td>
                      <td className="px-3 py-2 text-center">
                        {p.status === 'OPEN' && (
                          <button
                            disabled={closingId === p.id}
                            onClick={(e) => { e.stopPropagation(); handleClosePosition(p.id, p.underlying, p.strike, p.broker); }}
                            className={`px-2 py-1 rounded-lg text-[9px] font-bold transition border ${
                              closingId === p.id
                                ? 'bg-slate-200 text-slate-500 border-slate-300 cursor-wait'
                                : 'bg-red-50 text-red-700 border-red-300 hover:bg-red-100 hover:border-red-400'
                            }`}
                          >
                            {closingId === p.id ? '⏳ Closing...' : '✖ Close'}
                          </button>
                        )}
                      </td>
                      <td className="px-3 py-2 text-[9px] text-red-600 max-w-[200px] truncate">{p.errorMessage || '--'}</td>
                    </tr>
                    {isExpanded && canShowPayoff && (
                      <tr className="bg-fuchsia-50/40 border-b border-fuchsia-100">
                        <td colSpan={17} className="p-3">
                          <div className="bg-white rounded-xl p-3 border border-fuchsia-200 shadow-md space-y-2">
                            <span className="font-bold text-slate-800 text-xs uppercase block">Open Position Payoff -- {p.underlying} {p.action}:</span>
                            <ArbitrageSignalPayoffChart opp={p} />
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

/* Ground-truth reconciliation: shows real positions straight from the broker's own portfolio
   API, not our DB's live_positions table. Our own tracking can drift from reality (a missed
   webhook, a race, a bug) -- this is the same view you'd see logging into Zerodha directly,
   so any mismatch between this and the Live Positions table above is visible immediately
   instead of discovered by surprise later. */
function BrokerPositionsPanel({ executionBroker, defaultExpanded = false }) {
  const [collapsed, setCollapsed] = useState(!defaultExpanded);
  const broker = executionBroker && executionBroker !== 'PAPER' ? executionBroker : 'ZERODHA';

  const { data, refetch, isFetching } = useQuery({
    queryKey: ['brokerPositions', broker],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/broker-positions', { params: { broker } });
      return res.data;
    },
    refetchInterval: 5000,
    staleTime: 2000,
  });

  const positions = data?.positions || [];

  return (
    <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
      <div className="px-4 py-3 bg-gradient-to-r from-amber-50 via-orange-50 to-white border-b border-amber-100 flex items-center justify-between cursor-pointer" onClick={() => setCollapsed(!collapsed)}>
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-lg">🏦</span>
          <h3 className="text-sm font-black text-slate-800">{broker} Broker Positions (Ground Truth)</h3>
          <span className="px-2 py-0.5 bg-amber-100 text-amber-700 text-[10px] font-bold rounded-full">{positions.length}</span>
          <span className="px-2 py-0.5 bg-slate-100 text-slate-500 text-[9px] font-bold rounded-full">5s tick</span>
          {data?.error && (
            <span className="px-2 py-0.5 bg-red-50 text-red-700 border border-red-200 text-[10px] font-bold rounded-full">{data.error}</span>
          )}
        </div>
        <div className="flex items-center gap-2">
          <button onClick={(e) => { e.stopPropagation(); refetch(); }} className="px-2 py-1 bg-white border border-slate-200 hover:bg-amber-50 hover:border-amber-300 text-slate-600 text-[10px] font-bold rounded-lg transition">
            {isFetching ? '...' : 'Refresh'}
          </button>
          <span className="text-slate-400 text-xs">{collapsed ? '▼' : '▲'}</span>
        </div>
      </div>

      {!collapsed && positions.length === 0 && !data?.error && (
        <div className="p-8 text-center text-slate-400 text-sm font-semibold">No open positions at {broker} right now</div>
      )}

      {!collapsed && positions.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-[11px] text-left">
            <thead className="bg-slate-50 border-b border-slate-200 text-slate-600 uppercase tracking-tight font-bold">
              <tr>
                <th className="px-3 py-2">Symbol</th>
                <th className="px-3 py-2">Exchange</th>
                <th className="px-3 py-2">Product</th>
                <th className="px-3 py-2 text-right">Qty</th>
                <th className="px-3 py-2 text-right">Avg Price</th>
                <th className="px-3 py-2 text-right">Last Price</th>
                <th className="px-3 py-2 text-right">Unrealized P&amp;L</th>
                <th className="px-3 py-2 text-right">Realized P&amp;L</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {positions.map((p, i) => (
                <tr key={i} className="hover:bg-slate-50">
                  <td className="px-3 py-2 font-bold text-slate-800">{p.symbol}</td>
                  <td className="px-3 py-2 text-slate-500">{p.exchange}</td>
                  <td className="px-3 py-2">
                    <span className="px-2 py-0.5 rounded-full text-[9px] font-bold border bg-slate-100 text-slate-600 border-slate-300">{p.productType}</span>
                  </td>
                  <td className={`px-3 py-2 text-right font-mono font-bold ${p.quantity >= 0 ? 'text-emerald-700' : 'text-red-600'}`}>{p.quantity}</td>
                  <td className="px-3 py-2 text-right font-mono">₹{Number(p.avgPrice || 0).toFixed(2)}</td>
                  <td className="px-3 py-2 text-right font-mono">₹{Number(p.lastPrice || 0).toFixed(2)}</td>
                  <td className={`px-3 py-2 text-right font-mono font-bold ${Number(p.unrealizedPnl || 0) >= 0 ? 'text-emerald-600' : 'text-red-600'}`}>
                    ₹{Math.round(Number(p.unrealizedPnl || 0)).toLocaleString('en-IN')}
                  </td>
                  <td className={`px-3 py-2 text-right font-mono font-bold ${Number(p.realizedPnl || 0) >= 0 ? 'text-emerald-600' : 'text-red-600'}`}>
                    ₹{Math.round(Number(p.realizedPnl || 0)).toLocaleString('en-IN')}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function CashPositionsSection() {
  const [collapsed, setCollapsed] = useState(true);
  const { data, refetch } = useQuery({
    queryKey: ['cashPositions'],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/cash-positions');
      return res.data;
    },
    refetchInterval: 5000,
    staleTime: 2000,
  });

  const positions = data?.positions || [];
  if (positions.length === 0) return null;

  return (
    <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
      <div className="px-4 py-3 bg-gradient-to-r from-amber-900 to-orange-950 flex items-center justify-between cursor-pointer" onClick={() => setCollapsed(!collapsed)}>
        <div className="flex items-center gap-2">
          <span className="text-lg">🔥</span>
          <h3 className="text-sm font-black text-white">Cash Positions</h3>
          <span className="px-2 py-0.5 bg-emerald-500/20 text-emerald-300 text-[10px] font-bold rounded-full">{positions.length}</span>
          {data?.totalPnl != null && (
            <span className={`px-2.5 py-0.5 text-[11px] font-black rounded-full ${data.totalPnl >= 0 ? 'bg-emerald-500/30 text-emerald-300' : 'bg-red-500/30 text-red-300'}`}>
              Total P&L: ₹{Math.round(data.totalPnl).toLocaleString('en-IN')}
            </span>
          )}
        </div>
        <div className="flex items-center gap-2">
          <button onClick={(e) => { e.stopPropagation(); refetch(); }} className="px-2 py-1 bg-white/10 hover:bg-white/20 text-white text-[10px] font-bold rounded-lg transition">Refresh</button>
          <span className="text-white/60 text-xs">{collapsed ? '▼' : '▲'}</span>
        </div>
      </div>

      {!collapsed && (
        <div className="overflow-x-auto">
          <table className="w-full text-[11px] text-left">
            <thead className="bg-slate-50 border-b border-slate-200 text-slate-600 uppercase tracking-tight font-bold">
              <tr>
                <th className="px-3 py-2">Time</th>
                <th className="px-3 py-2">Symbol</th>
                <th className="px-3 py-2">Strategy</th>
                <th className="px-3 py-2 text-right">Qty</th>
                <th className="px-3 py-2 text-right">Entry</th>
                <th className="px-3 py-2 text-right">Current</th>
                <th className="px-3 py-2 text-right">Target</th>
                <th className="px-3 py-2 text-right">Stop Loss</th>
                <th className="px-3 py-2 text-right">Live P&amp;L</th>
                <th className="px-3 py-2 text-center">Broker</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {positions.map(p => {
                const pnl = p.currentPnl || 0;
                return (
                  <tr key={p.id} className="hover:bg-slate-50">
                    <td className="px-3 py-2 font-mono text-[10px] text-slate-600">{fmtTime(p.enteredAt)}</td>
                    <td className="px-3 py-2 font-bold text-slate-800">{p.symbol}</td>
                    <td className="px-3 py-2 text-slate-500 text-[10px]">{p.strategyType}</td>
                    <td className="px-3 py-2 text-right font-bold">{p.quantity}</td>
                    <td className="px-3 py-2 text-right font-mono">₹{Number(p.entryPrice || 0).toFixed(1)}</td>
                    <td className="px-3 py-2 text-right font-mono">₹{Number(p.currentPrice || 0).toFixed(1)}</td>
                    <td className="px-3 py-2 text-right font-mono text-emerald-600">₹{Number(p.targetPrice || 0).toFixed(1)}</td>
                    <td className="px-3 py-2 text-right font-mono text-red-500">₹{Number(p.stopLossPrice || 0).toFixed(1)}</td>
                    <td className={`px-3 py-2 text-right font-mono font-bold ${pnl >= 0 ? 'text-emerald-600' : 'text-red-600'}`}>₹{Math.round(pnl).toLocaleString('en-IN')}</td>
                    <td className="px-3 py-2 text-center text-[10px] text-slate-500">{p.broker}</td>
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
function SignalsView({ underlyings, toggleUnderlying, opportunities, calendarOpportunities, verticalOpportunities, butterflyOpportunities, condorSpreadOpportunities, summary, scanLoading, handleExecuteInline, executionBroker }) {
  const [strategyTypeFilter, setStrategyTypeFilter] = useState('ALL');
  const [minEdge, setMinEdge] = useState(300);
  const [customEdge, setCustomEdge] = useState('');
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

    return [...opportunities, ...mappedCal, ...(verticalOpportunities || []), ...(butterflyOpportunities || []), ...(condorSpreadOpportunities || [])];
  }, [opportunities, calendarOpportunities, verticalOpportunities, butterflyOpportunities, condorSpreadOpportunities]);

  const filteredOpps = useMemo(() => {
    return combinedOpps.filter(o => {
      const typeStr = String(o.type || o.strategyType || '').toUpperCase();
      if (strategyTypeFilter === 'PARITY' && !typeStr.includes('PARITY') && !typeStr.includes('BID')) return false;
      if (strategyTypeFilter === 'BOX' && !typeStr.includes('BOX')) return false;
      if (strategyTypeFilter === 'VERTICAL' && !typeStr.includes('VERTICAL')) return false;
      if (strategyTypeFilter === 'BUTTERFLY' && !typeStr.includes('BUTTERFLY')) return false;
      if (strategyTypeFilter === 'CONDORSPREAD' && !typeStr.includes('CONDOR_SPREAD') && !typeStr.includes('CONDORSPREAD')) return false;
      if (strategyTypeFilter === 'CALENDAR' && !typeStr.includes('CALENDAR') && !typeStr.includes('TIME')) return false;
      if (strategyTypeFilter === 'CONDOR' && !typeStr.includes('IRON')) return false;
      if ((Number(o.edgeAfterCosts) || 0) < minEdge) return false;
      return true;
    });
  }, [combinedOpps, strategyTypeFilter, minEdge]);

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
            { id: 'VERTICAL', label: '📐 Vertical' },
            { id: 'BUTTERFLY', label: '🦋 Butterfly' },
            { id: 'CONDORSPREAD', label: '🎯 Condor Spread' },
            { id: 'CALENDAR', label: '⏳ Calendar' },
            { id: 'CONDOR', label: '🛡️ Iron Condor' },
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

        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          <span className="text-[10px] font-bold text-slate-500 px-1">EDGE ≥</span>
          {[0, 300, 500, 1000].map(e => (
            <button key={e} onClick={() => { setMinEdge(e); setCustomEdge(''); setCurrentPage(1); }}
              className={`px-2 py-1 rounded-lg text-[10px] font-bold transition ${minEdge === e && !customEdge ? 'bg-emerald-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              ₹{e}
            </button>
          ))}
          <input type="number" placeholder="Custom" value={customEdge} onChange={e => { setCustomEdge(e.target.value); setMinEdge(Number(e.target.value) || 0); setCurrentPage(1); }}
            className="w-16 px-1.5 py-1 rounded-lg text-[10px] font-bold border border-slate-300 focus:outline-none focus:border-emerald-500" />
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

/* 2. BID PARITY VIEW */
function BidParityView({ underlyings, toggleUnderlying, handleExecuteInline, executionBroker }) {
  const [subTab, setSubTab] = useState('signals');
  const [underlying, setUnderlying] = useState('ALL');
  const [minEdge, setMinEdge] = useState(300);
  const [customEdge, setCustomEdge] = useState('');
  const [expandedId, setExpandedId] = useState(null);
  const [sortCol, setSortCol] = useState('scanTime');
  const [sortAsc, setSortAsc] = useState(false);
  const [histPage, setHistPage] = useState(0);
  const PAGE_SIZE = 200;

  const { data: liveData, isLoading } = useQuery({
    queryKey: ['bid-parity-scan', underlying],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/bid-parity/scan', { params: { underlying } });
      return res.data;
    },
    refetchInterval: 15000
  });

  const today = new Date().toLocaleDateString('en-CA');

  const { data: historyData } = useQuery({
    queryKey: ['bid-parity-history', underlying, histPage],
    queryFn: async () => {
      const params = { page: histPage, size: PAGE_SIZE, strategyType: 'BID_PARITY', startDate: today, endDate: today };
      if (underlying !== 'ALL') params.underlying = underlying;
      const res = await client.get('/option-arbitrage/history', { params });
      return res.data;
    },
    refetchInterval: 15000
  });

  const liveOpps = (liveData?.opportunities || []).filter(o => (o.edgeAfterCosts || 0) >= minEdge);
  const historyOpps = (historyData?.items || []).filter(i =>
    (i.edgeAfterCosts || 0) >= minEdge
  );

  const allOpps = [...liveOpps];
  const liveIds = new Set(liveOpps.map(o => o.id));
  historyOpps.forEach(h => { if (!liveIds.has(h.id)) allOpps.push(h); });

  const filteredByUnderlying = underlying === 'ALL' ? allOpps : allOpps.filter(o => o.underlying === underlying);
  const totalHistory = historyData?.totalElements || 0;
  const totalHistoryPages = historyData?.totalPages || 0;

  const allUnds = ['NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'];

  const sortedOpps = [...filteredByUnderlying].sort((a, b) => {
    let va, vb;
    if (sortCol === 'scanTime') { va = a.scanTime || a.entryTime || ''; vb = b.scanTime || b.entryTime || ''; }
    else if (sortCol === 'underlying') { va = a.underlying || ''; vb = b.underlying || ''; }
    else if (sortCol === 'strike') { va = a.strike || 0; vb = b.strike || 0; }
    else if (sortCol === 'edge') { va = a.edgeAfterCosts || 0; vb = b.edgeAfterCosts || 0; }
    else if (sortCol === 'pnl') { va = a.pnlAfterCosts || 0; vb = b.pnlAfterCosts || 0; }
    else { va = a[sortCol] || ''; vb = b[sortCol] || ''; }
    if (typeof va === 'string') return sortAsc ? va.localeCompare(vb) : vb.localeCompare(va);
    return sortAsc ? va - vb : vb - va;
  });
  const toggleSort = (col) => { if (sortCol === col) setSortAsc(!sortAsc); else { setSortCol(col); setSortAsc(false); } };
  const sortIcon = (col) => sortCol === col ? (sortAsc ? ' ▲' : ' ▼') : ' ↕';

  const allIds = sortedOpps.filter(o => o.id).map(o => o.id);
  const { data: livePnlRes } = useQuery({
    queryKey: ['bid-parity-pnl', allIds.join(',')],
    queryFn: async () => {
      if (allIds.length === 0) return { pnlMap: {}, statusMap: {}, exitTimeMap: {} };
      const idsParam = allIds.slice(0, 500).join(',');
      const res = await client.get('/option-arbitrage/history/live-pnl', { params: { ids: idsParam } });
      return { pnlMap: res.data?.pnlMap || {}, statusMap: res.data?.statusMap || {}, exitTimeMap: res.data?.exitTimeMap || {} };
    },
    refetchInterval: 10000,
    enabled: allIds.length > 0
  });
  const pnlMap = livePnlRes?.pnlMap || {};
  const statusMap = livePnlRes?.statusMap || {};
  const exitTimeMap = livePnlRes?.exitTimeMap || {};

  const statsByUnderlying = useMemo(() => {
    return allUnds.map(u => {
      const items = allOpps.filter(o => o.underlying === u);
      const getMergedStatus = (o) => {
        const posStatus = statusMap[String(o.id)];
        return posStatus || o.status || 'RUNNING';
      };
      const getPnl = (o) => {
        const live = pnlMap[String(o.id)];
        if (live != null) return Number(live);
        return o.pnlAfterCosts != null ? Number(o.pnlAfterCosts) : null;
      };
      const inProfit = items.filter(o => { const p = getPnl(o); return p != null && p > 0; }).length;
      const inLoss = items.filter(o => { const p = getPnl(o); return p != null && p < 0; }).length;
      const running = items.filter(o => { const s = String(getMergedStatus(o)).toUpperCase(); return s === 'RUNNING' || s === 'OPEN' || s === 'DETECTED' || s === 'EXECUTING'; }).length;
      const exited = items.filter(o => { const s = String(getMergedStatus(o)).toUpperCase(); return s === 'EXITED' || s === 'CLOSED'; }).length;
      const maxEdge = items.length > 0 ? Math.max(...items.map(o => Number(o.edgeAfterCosts) || 0)) : 0;
      const hitTarget = items.filter(o => {
        const p = getPnl(o);
        const edge = Number(o.edgeAfterCosts) || 0;
        return p != null && edge > 0 && p >= edge;
      }).length;
      return { underlying: u, total: items.length, inProfit, inLoss, running, exited, maxEdge, hitTarget };
    }).filter(s => s.total > 0);
  }, [allOpps, pnlMap, statusMap]);

  return (
    <div className="space-y-4 w-full">
      <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl w-fit">
        <button onClick={() => setSubTab('signals')}
          className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${subTab === 'signals' ? 'bg-slate-700 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
          Arbitrage Signals
        </button>
        <button onClick={() => setSubTab('autotrade')}
          className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${subTab === 'autotrade' ? 'bg-indigo-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
          ⚡ Auto-Trade
        </button>
        <button onClick={() => setSubTab('history')}
          className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${subTab === 'history' ? 'bg-emerald-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
          📊 History
        </button>
      </div>

      {subTab === 'autotrade' ? (
        <StrategyAutoTradePanel prefix="bidParity" label="Bid Parity" accent="indigo" />
      ) : subTab === 'history' ? (
        <HistoryView lockedStrategy="PARITY" handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} />
      ) : (
      <>
      <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className="text-base font-bold text-slate-800">Bid Parity Conversion &amp; Reversal Scanner</h2>
          <p className="text-xs text-slate-500">{sortedOpps.length} signals shown{totalHistory > 0 ? ` of ${totalHistory.toLocaleString('en-IN')} total today` : ''}</p>
        </div>

        <div className="flex flex-wrap gap-2 w-full mt-2">
          {statsByUnderlying.map(s => (
            <div key={s.underlying} className="flex-1 min-w-[180px] bg-gradient-to-br from-slate-50 to-slate-100 rounded-xl border border-slate-200 p-3 space-y-1">
              <div className="flex items-center justify-between">
                <span className="text-xs font-black text-slate-700">{s.underlying}</span>
                <span className="text-[10px] font-bold text-slate-500">{s.total} signals</span>
              </div>
              <div className="flex items-center gap-3 text-[10px] font-bold">
                <span className="text-emerald-600">✓ {s.inProfit} profit</span>
                <span className="text-red-500">✗ {s.inLoss} loss</span>
                <span className="text-blue-600">● {s.running} running</span>
              </div>
              <div className="flex items-center gap-3 text-[10px] font-bold">
                <span className="text-amber-600">🎯 {s.hitTarget} hit target</span>
                <span className="text-purple-600">Max: ₹{Math.round(s.maxEdge).toLocaleString('en-IN')}</span>
              </div>
            </div>
          ))}
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          {['ALL', 'NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'].map(u => (
            <button key={u} onClick={() => { setUnderlying(u); setHistPage(0); }}
              className={`px-3 py-1 rounded-lg text-xs font-bold transition ${underlying === u ? 'bg-amber-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              {u}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          <span className="text-[10px] font-bold text-slate-500 px-1">EDGE ≥</span>
          {[300, 500, 1000].map(e => (
            <button key={e} onClick={() => { setMinEdge(e); setCustomEdge(''); setHistPage(0); }}
              className={`px-2 py-1 rounded-lg text-[10px] font-bold transition ${minEdge === e && !customEdge ? 'bg-emerald-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              ₹{e}
            </button>
          ))}
          <input type="number" placeholder="Custom" value={customEdge} onChange={e => { setCustomEdge(e.target.value); setMinEdge(Number(e.target.value) || 0); setHistPage(0); }}
            className="w-16 px-1.5 py-1 rounded-lg text-[10px] font-bold border border-slate-300 focus:outline-none focus:border-emerald-500" />
        </div>
      </div>

      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm w-full">
        {isLoading ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">Scanning Bid Parity feeds...</div>
        ) : sortedOpps.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">No bid parity spreads for {underlying}</div>
        ) : (
          <div className="overflow-x-auto w-full">
            <table className="w-full text-[11px] text-left border-collapse">
              <thead className="bg-slate-50 border-b border-slate-200 font-bold text-slate-600 uppercase">
                <tr>
                  <th className="px-2 py-2 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('scanTime')}>Time{sortIcon('scanTime')}</th>
                  <th className="px-2 py-2 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('underlying')}>Symbol{sortIcon('underlying')}</th>
                  <th className="px-2 py-2 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('strike')}>Strike{sortIcon('strike')}</th>
                  <th className="px-2 py-2">Action</th>
                  <th className="px-2 py-2 text-right">CE</th>
                  <th className="px-2 py-2 text-right">PE</th>
                  <th className="px-2 py-2 text-right">FUT</th>
                  <th className="px-2 py-2 text-right text-emerald-600 font-bold cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('edge')}>Net Edge{sortIcon('edge')}</th>
                  <th className="px-2 py-2 text-center">Status</th>
                  <th className="px-2 py-2 text-right cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('pnl')}>P&amp;L{sortIcon('pnl')}</th>
                  <th className="px-2 py-2 text-center">Exit</th>
                  <th className="px-2 py-2 text-center">Trade</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {sortedOpps.map((opp, idx) => {
                  const isExp = expandedId === (opp.id || idx);
                  const posStatus = opp.id && statusMap[String(opp.id)] ? String(statusMap[String(opp.id)]).toUpperCase() : null;
                  const statusStr = posStatus || String(opp.status || 'RUNNING').toUpperCase();
                  const isLive = statusStr === 'RUNNING' || statusStr === 'OPEN' || statusStr === 'DETECTED' || statusStr === 'EXECUTING';
                  const isExited = statusStr === 'EXITED' || statusStr === 'CLOSED';
                  const livePnl = opp.id && pnlMap[String(opp.id)] != null ? Number(pnlMap[String(opp.id)]) : null;
                  const pnlDisplay = livePnl != null ? livePnl : (opp.pnlAfterCosts != null ? Number(opp.pnlAfterCosts) : null);

                  const timeStr = fmtTime(opp.scanTime || opp.entryTime);
                  const exitTimeVal = (opp.id && exitTimeMap[String(opp.id)]) || opp.exitTime;
                  const exitStr = isExited ? fmtTime(exitTimeVal) : '';
                  const ceVal = Number(opp.ceEntryPrice || opp.cePrice || opp.ceBid || 0).toFixed(1);
                  const peVal = Number(opp.peEntryPrice || opp.pePrice || opp.peBid || 0).toFixed(1);
                  const futVal = Number(opp.futuresPrice || 0).toFixed(1);

                  return (
                    <React.Fragment key={opp.id || idx}>
                      <tr onClick={() => setExpandedId(isExp ? null : (opp.id || idx))}
                        className={`transition cursor-pointer ${isExp ? 'bg-amber-50/70 border-l-4 border-amber-600' : 'hover:bg-slate-50'}`}>
                        <td className="px-2 py-1.5 font-mono text-[10px] text-slate-500">{timeStr}</td>
                        <td className="px-2 py-1.5 font-bold text-slate-800">{opp.underlying}</td>
                        <td className="px-2 py-1.5 font-bold text-slate-700">{opp.strike}</td>
                        <td className="px-2 py-1.5 font-bold text-purple-700 truncate max-w-[120px]">{opp.action}</td>
                        <td className="px-2 py-1.5 text-right font-mono">{ceVal}</td>
                        <td className="px-2 py-1.5 text-right font-mono">{peVal}</td>
                        <td className="px-2 py-1.5 text-right font-mono">{futVal}</td>
                        <td className="px-2 py-1.5 text-right font-mono font-bold text-emerald-600">
                          +₹{Math.round(Number(opp.edgeAfterCosts || 0)).toLocaleString('en-IN')}
                        </td>
                        <td className="px-2 py-1.5 text-center">
                          <span className={`px-1.5 py-0.2 rounded-full text-[9px] font-bold border ${
                            isLive ? 'bg-emerald-100 text-emerald-800 border-emerald-300' :
                            isExited ? 'bg-slate-200 text-slate-600 border-slate-300' :
                            statusStr === 'DETECTED' ? 'bg-blue-100 text-blue-800 border-blue-300' :
                            statusStr === 'MISSED' ? 'bg-slate-100 text-slate-600 border-slate-300' :
                            statusStr === 'FAILED' ? 'bg-red-100 text-red-800 border-red-300' :
                            statusStr === 'EXPIRED' ? 'bg-amber-100 text-amber-800 border-amber-300' :
                            'bg-blue-100 text-blue-800 border-blue-300'
                          }`}>
                            {isLive ? '🟢 RUNNING' : isExited ? '⏹ EXITED' :
                             statusStr === 'DETECTED' ? '🔵 DETECTED' :
                             statusStr === 'EXPIRED' ? '⏰ EXPIRED' : statusStr === 'MISSED' ? '⚪ MISSED' :
                             statusStr === 'FAILED' ? '❌ FAILED' : statusStr}
                          </span>
                          {opp.existingOpenPosition && (
                            <span className="block mt-1 px-1.5 py-0.2 rounded-full text-[8px] font-bold border bg-amber-100 text-amber-800 border-amber-300" title={`You already hold an OPEN ${opp.existingPositionBroker || 'PAPER'} position for this signal`}>
                              📌 Already holding
                            </span>
                          )}
                        </td>
                        <td className="px-2 py-1.5 text-right font-mono font-bold">
                          {pnlDisplay != null && !isNaN(pnlDisplay)
                            ? <span className={pnlDisplay >= 0 ? 'text-emerald-600' : 'text-red-600'}>₹{Math.round(pnlDisplay).toLocaleString('en-IN')}</span>
                            : <span className="text-slate-400">--</span>}
                        </td>
                        <td className="px-2 py-1.5 text-center font-mono text-[10px] text-slate-500">{exitStr}</td>
                        <td className="px-2 py-1.5 text-center">
                          <button onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp); }}
                            className="px-2 py-0.5 bg-amber-600 text-white text-[10px] font-bold rounded shadow-sm">
                            ⚡ Trade
                          </button>
                        </td>
                      </tr>
                      {isExp && (
                        <tr className="bg-amber-50/40 border-b border-amber-100">
                          <td colSpan={12} className="p-3">
                            <div className="bg-white rounded-xl p-3 border border-amber-200 shadow-md space-y-2">
                              <span className="font-bold text-slate-800 text-xs uppercase block">Bid Parity Leg Breakdown:</span>
                              {opp.existingOpenPosition && (
                                <p className="text-[11px] font-bold text-amber-700 bg-amber-50 border border-amber-200 rounded-lg px-2 py-1">
                                  📌 You already have an OPEN {opp.existingPositionBroker || 'PAPER'} position for this exact signal. Trading again will open an additional position, not add to or replace the existing one.
                                </p>
                              )}
                              <p className="text-xs font-mono font-bold text-slate-800 bg-slate-50 p-2 rounded-lg border">{opp.legs || `BUY ${opp.strike} CE @ ${ceVal} | SELL ${opp.strike} PE @ ${peVal} | ${opp.action}`}</p>
                              {opp.costBreakdown && (
                                <div className="text-[10px] font-mono text-slate-600 grid grid-cols-4 gap-1">
                                  {Object.entries(opp.costBreakdown).map(([k,v]) => <span key={k}>{k}: ₹{v}</span>)}
                                </div>
                              )}
                              <ArbitrageSignalPayoffChart opp={opp} />
                              <div className="flex justify-end pt-1">
                                <button onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp); }} className="px-3 py-1 bg-amber-600 text-white rounded-lg text-xs font-bold shadow-md">
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
        {totalHistoryPages > 1 && (
          <div className="flex items-center justify-between px-4 py-3 border-t border-slate-200 bg-slate-50">
            <span className="text-xs text-slate-500">Page {histPage + 1} of {totalHistoryPages} ({totalHistory.toLocaleString('en-IN')} signals today)</span>
            <div className="flex gap-1">
              <button disabled={histPage === 0} onClick={() => setHistPage(0)} className="px-2 py-1 bg-white border rounded text-xs font-bold disabled:opacity-40">First</button>
              <button disabled={histPage === 0} onClick={() => setHistPage(histPage - 1)} className="px-2 py-1 bg-white border rounded text-xs font-bold disabled:opacity-40">Prev</button>
              <button disabled={histPage >= totalHistoryPages - 1} onClick={() => setHistPage(histPage + 1)} className="px-2 py-1 bg-white border rounded text-xs font-bold disabled:opacity-40">Next</button>
              <button disabled={histPage >= totalHistoryPages - 1} onClick={() => setHistPage(totalHistoryPages - 1)} className="px-2 py-1 bg-white border rounded text-xs font-bold disabled:opacity-40">Last</button>
            </div>
          </div>
        )}
      </div>
      </>
      )}
    </div>
  );
}

/* 3. BOX SPREAD VIEW */
function BoxSpreadView({ underlyings, toggleUnderlying, handleExecuteInline, executionBroker }) {
  const [subTab, setSubTab] = useState('signals');
  const [underlying, setUnderlying] = useState('ALL');
  const [minEdge, setMinEdge] = useState(0);
  const [customEdge, setCustomEdge] = useState('');
  const [expandedId, setExpandedId] = useState(null);
  const [sortCol, setSortCol] = useState('scanTime');
  const [sortAsc, setSortAsc] = useState(false);
  const [histPage, setHistPage] = useState(0);
  const PAGE_SIZE = 200;

  const { data: liveData, isLoading } = useQuery({
    queryKey: ['box-spread-scan', underlying],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/box-spread/scan', { params: { underlying } });
      return res.data;
    },
    refetchInterval: 30000
  });

  const histDate = new Date(); histDate.setDate(histDate.getDate() - 6);
  const today2 = histDate.toISOString().split('T')[0];
  const todayEnd = new Date().toISOString().split('T')[0];

  const { data: historyData } = useQuery({
    queryKey: ['box-history', underlying, histPage],
    queryFn: async () => {
      const params = { page: histPage, size: PAGE_SIZE, strategyType: 'BOX_SPREAD', startDate: today2, endDate: todayEnd };
      if (underlying !== 'ALL') params.underlying = underlying;
      const res = await client.get('/option-arbitrage/history', { params });
      return res.data;
    }
  });

  const liveOpps = (liveData?.opportunities || []).filter(o => (o.edgeAfterCosts || o.boxEdgeInr || 0) >= minEdge);
  const historyOpps = (historyData?.items || []).filter(i =>
    (i.edgeAfterCosts || i.boxEdgeInr || 0) >= minEdge
  );

  const allOpps = [...liveOpps];
  const liveIds = new Set(liveOpps.map(o => o.id));
  historyOpps.forEach(h => { if (!liveIds.has(h.id)) allOpps.push(h); });

  const filteredByUnderlying = underlying === 'ALL' ? allOpps : allOpps.filter(o => o.underlying === underlying);
  const totalHistory = historyData?.totalElements || 0;
  const totalHistoryPages = historyData?.totalPages || 0;

  const boxAllIds = allOpps.filter(o => o.id).map(o => o.id);
  const { data: boxLivePnlRes } = useQuery({
    queryKey: ['box-pnl', boxAllIds.join(',')],
    queryFn: async () => {
      if (boxAllIds.length === 0) return { pnlMap: {}, statusMap: {} };
      const idsParam = boxAllIds.slice(0, 500).join(',');
      const res = await client.get('/option-arbitrage/history/live-pnl', { params: { ids: idsParam } });
      return { pnlMap: res.data?.pnlMap || {}, statusMap: res.data?.statusMap || {} };
    },
    refetchInterval: 10000,
    enabled: boxAllIds.length > 0
  });
  const boxPnlMap = boxLivePnlRes?.pnlMap || {};
  const boxStatusMap = boxLivePnlRes?.statusMap || {};

  const boxUnds = ['NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'];
  const boxStats = useMemo(() => {
    return boxUnds.map(u => {
      const items = allOpps.filter(o => o.underlying === u);
      const getMergedStatus = (o) => {
        const posStatus = boxStatusMap[String(o.id)];
        return posStatus || o.status || 'RUNNING';
      };
      const getPnl = (o) => {
        const live = boxPnlMap[String(o.id)];
        if (live != null) return Number(live);
        return o.pnlAfterCosts != null ? Number(o.pnlAfterCosts) : null;
      };
      const inProfit = items.filter(o => { const p = getPnl(o); return p != null && p > 0; }).length;
      const inLoss = items.filter(o => { const p = getPnl(o); return p != null && p < 0; }).length;
      const running = items.filter(o => { const s = String(getMergedStatus(o)).toUpperCase(); return s === 'RUNNING' || s === 'OPEN' || s === 'DETECTED' || s === 'EXECUTING'; }).length;
      const exited = items.filter(o => { const s = String(getMergedStatus(o)).toUpperCase(); return s === 'EXITED' || s === 'CLOSED'; }).length;
      const maxEdge = items.length > 0 ? Math.max(...items.map(o => Number(o.edgeAfterCosts || o.boxEdgeInr) || 0)) : 0;
      const hitTarget = items.filter(o => {
        const p = getPnl(o);
        const edge = Number(o.edgeAfterCosts || o.boxEdgeInr) || 0;
        return p != null && edge > 0 && p >= edge;
      }).length;
      return { underlying: u, total: items.length, inProfit, inLoss, running, exited, maxEdge, hitTarget };
    }).filter(s => s.total > 0);
  }, [allOpps, boxPnlMap, boxStatusMap]);

  const sortedOpps = [...filteredByUnderlying].sort((a, b) => {
    let va, vb;
    if (sortCol === 'scanTime') { va = a.scanTime || a.entryTime || ''; vb = b.scanTime || b.entryTime || ''; }
    else if (sortCol === 'underlying') { va = a.underlying || ''; vb = b.underlying || ''; }
    else if (sortCol === 'edge') { va = a.edgeAfterCosts || a.boxEdgeInr || 0; vb = b.edgeAfterCosts || b.boxEdgeInr || 0; }
    else if (sortCol === 'pnl') { va = a.pnlAfterCosts || 0; vb = b.pnlAfterCosts || 0; }
    else { va = a[sortCol] || ''; vb = b[sortCol] || ''; }
    if (typeof va === 'string') return sortAsc ? va.localeCompare(vb) : vb.localeCompare(va);
    return sortAsc ? va - vb : vb - va;
  });
  const toggleSort = (col) => { if (sortCol === col) setSortAsc(!sortAsc); else { setSortCol(col); setSortAsc(false); } };
  const sortIcon = (col) => sortCol === col ? (sortAsc ? ' ▲' : ' ▼') : ' ↕';

  return (
    <div className="space-y-4 w-full">
      <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl w-fit">
        <button onClick={() => setSubTab('signals')}
          className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${subTab === 'signals' ? 'bg-purple-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
          Arbitrage Signals
        </button>
        <button onClick={() => setSubTab('nearmiss')}
          className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${subTab === 'nearmiss' ? 'bg-amber-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
          🔎 Near-Miss Watchlist
        </button>
        <button onClick={() => setSubTab('autotrade')}
          className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${subTab === 'autotrade' ? 'bg-indigo-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
          ⚡ Auto-Trade
        </button>
        <button onClick={() => setSubTab('history')}
          className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${subTab === 'history' ? 'bg-emerald-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
          📊 History
        </button>
      </div>

      {subTab === 'autotrade' ? (
        <StrategyAutoTradePanel prefix="box" label="Box Spread" accent="indigo" />
      ) : subTab === 'history' ? (
        <HistoryView lockedStrategy="BOX" handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} />
      ) : subTab === 'nearmiss' ? (
        <BoxNearMissPanel />
      ) : (
      <>
      <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className="text-base font-bold text-slate-800">4-Leg Risk-Free Box Spread Scanner</h2>
          <p className="text-xs text-slate-500">{sortedOpps.length} signals shown{totalHistory > 0 ? ` of ${totalHistory.toLocaleString('en-IN')} total today` : ''}</p>
        </div>

        <div className="flex flex-wrap gap-2 w-full mt-2">
          {boxStats.map(s => (
            <div key={s.underlying} className="flex-1 min-w-[180px] bg-gradient-to-br from-purple-50 to-purple-100 rounded-xl border border-purple-200 p-3 space-y-1">
              <div className="flex items-center justify-between">
                <span className="text-xs font-black text-purple-700">{s.underlying}</span>
                <span className="text-[10px] font-bold text-purple-500">{s.total} signals</span>
              </div>
              <div className="flex items-center gap-3 text-[10px] font-bold">
                <span className="text-emerald-600">✓ {s.inProfit} profit</span>
                <span className="text-red-500">✗ {s.inLoss} loss</span>
                <span className="text-blue-600">● {s.running} running</span>
              </div>
              <div className="flex items-center gap-3 text-[10px] font-bold">
                <span className="text-amber-600">🎯 {s.hitTarget} hit target</span>
                <span className="text-purple-600">Max: ₹{Math.round(s.maxEdge).toLocaleString('en-IN')}</span>
              </div>
            </div>
          ))}
        </div>

        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          {['ALL', 'NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'].map(u => (
            <button key={u} onClick={() => { setUnderlying(u); setHistPage(0); }}
              className={`px-3 py-1 rounded-lg text-xs font-bold transition ${underlying === u ? 'bg-purple-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              {u}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          <span className="text-[10px] font-bold text-slate-500 px-1">EDGE ≥</span>
          {[300, 500, 1000].map(e => (
            <button key={e} onClick={() => { setMinEdge(e); setCustomEdge(''); setHistPage(0); }}
              className={`px-2 py-1 rounded-lg text-[10px] font-bold transition ${minEdge === e && !customEdge ? 'bg-emerald-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              ₹{e}
            </button>
          ))}
          <input type="number" placeholder="Custom" value={customEdge} onChange={e => { setCustomEdge(e.target.value); setMinEdge(Number(e.target.value) || 0); setHistPage(0); }}
            className="w-16 px-1.5 py-1 rounded-lg text-[10px] font-bold border border-slate-300 focus:outline-none focus:border-emerald-500" />
        </div>
      </div>

      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm w-full">
        {isLoading ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">Scanning Box Spreads...</div>
        ) : sortedOpps.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">No box spread setups for {underlying}</div>
        ) : (
          <div className="overflow-x-auto w-full">
            <table className="w-full text-[11px] text-left border-collapse">
              <thead className="bg-slate-50 border-b border-slate-200 font-bold text-slate-600 uppercase">
                <tr>
                  <th className="px-2 py-2 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('scanTime')}>Time{sortIcon('scanTime')}</th>
                  <th className="px-2 py-2 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('underlying')}>Symbol{sortIcon('underlying')}</th>
                  <th className="px-2 py-2">Strike Pair</th>
                  <th className="px-2 py-2">Action</th>
                  <th className="px-2 py-2 text-right">CE</th>
                  <th className="px-2 py-2 text-right">PE</th>
                  <th className="px-2 py-2 text-right text-emerald-600 font-bold cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('edge')}>Net Edge{sortIcon('edge')}</th>
                  <th className="px-2 py-2 text-center">Status</th>
                  <th className="px-2 py-2 text-right cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('pnl')}>P&amp;L{sortIcon('pnl')}</th>
                  <th className="px-2 py-2 text-center">Exit</th>
                  <th className="px-2 py-2 text-center">Trade</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {sortedOpps.map((opp, idx) => {
                  const isExp = expandedId === (opp.id || idx);
                  const posStatus = opp.id && boxStatusMap[String(opp.id)] ? String(boxStatusMap[String(opp.id)]).toUpperCase() : null;
                  const statusStr = posStatus || String(opp.status || 'RUNNING').toUpperCase();
                  const isLive = statusStr === 'RUNNING' || statusStr === 'OPEN' || statusStr === 'DETECTED' || statusStr === 'EXECUTING';
                  const isExited = statusStr === 'EXITED' || statusStr === 'CLOSED';
                  const livePnl = opp.id && boxPnlMap[String(opp.id)] != null ? Number(boxPnlMap[String(opp.id)]) : null;
                  const pnlDisplay = livePnl != null ? livePnl : (opp.pnlAfterCosts != null ? Number(opp.pnlAfterCosts) : null);
                  const strike1 = opp.strike || 0;
                  const strike2 = opp.action ? parseInt(opp.action.match(/\d+\/(\d+)/)?.[1] || 0) : strike1 + 50;
                  const edgeVal = Number(opp.edgeAfterCosts || opp.boxEdgeInr || 0);

                  const timeStr = fmtTime(opp.scanTime || opp.entryTime);
                  const exitStr = isExited ? fmtTime(opp.exitTime) : '';
                  const ceVal = Number(opp.ceEntryPrice || opp.cePrice || 0).toFixed(1);
                  const peVal = Number(opp.peEntryPrice || opp.pePrice || 0).toFixed(1);

                  return (
                    <React.Fragment key={opp.id || idx}>
                      <tr onClick={() => setExpandedId(isExp ? null : (opp.id || idx))}
                        className={`transition cursor-pointer ${isExp ? 'bg-purple-50/70 border-l-4 border-purple-600' : 'hover:bg-slate-50'}`}>
                        <td className="px-2 py-1.5 font-mono text-[10px] text-slate-500">{timeStr}</td>
                        <td className="px-2 py-1.5 font-bold text-slate-800">{opp.underlying}</td>
                        <td className="px-2 py-1.5 font-bold text-slate-700">{strike1}/{strike2}</td>
                        <td className="px-2 py-1.5 font-bold text-purple-700 truncate max-w-[120px]">{opp.action}</td>
                        <td className="px-2 py-1.5 text-right font-mono">{ceVal}</td>
                        <td className="px-2 py-1.5 text-right font-mono">{peVal}</td>
                        <td className="px-2 py-1.5 text-right font-mono font-bold text-emerald-600">
                          +₹{Math.round(edgeVal).toLocaleString('en-IN')}
                        </td>
                        <td className="px-2 py-1.5 text-center">
                          <span className={`px-1.5 py-0.2 rounded-full text-[9px] font-bold border ${
                            isLive ? 'bg-emerald-100 text-emerald-800 border-emerald-300' :
                            isExited ? 'bg-slate-200 text-slate-600 border-slate-300' :
                            statusStr === 'EXPIRED' ? 'bg-amber-100 text-amber-800 border-amber-300' :
                            'bg-blue-100 text-blue-800 border-blue-300'
                          }`}>
                            {isLive ? '🟢 RUNNING' : isExited ? '⏹ EXITED' : statusStr === 'EXPIRED' ? '⏰ EXPIRED' : statusStr}
                          </span>
                          {opp.existingOpenPosition && (
                            <span className="block mt-1 px-1.5 py-0.2 rounded-full text-[8px] font-bold border bg-amber-100 text-amber-800 border-amber-300" title={`You already hold an OPEN ${opp.existingPositionBroker || 'PAPER'} position for this signal`}>
                              📌 Already holding
                            </span>
                          )}
                        </td>
                        <td className="px-2 py-1.5 text-right font-mono font-bold">
                          {pnlDisplay != null && !isNaN(pnlDisplay)
                            ? <span className={pnlDisplay >= 0 ? 'text-emerald-600' : 'text-red-600'}>₹{Math.round(pnlDisplay).toLocaleString('en-IN')}</span>
                            : <span className="text-slate-400">--</span>}
                        </td>
                        <td className="px-2 py-1.5 text-center font-mono text-[10px] text-slate-500">{exitStr}</td>
                        <td className="px-2 py-1.5 text-center">
                          <button onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp); }}
                            className="px-2 py-0.5 bg-purple-600 text-white text-[10px] font-bold rounded shadow-sm">
                            ⚡ Trade
                          </button>
                        </td>
                      </tr>
                      {isExp && (
                        <tr className="bg-purple-50/40 border-b border-purple-100">
                          <td colSpan={11} className="p-3">
                            <div className="bg-white rounded-xl p-3 border border-purple-200 shadow-md space-y-2">
                              <span className="font-bold text-slate-800 text-xs uppercase block">4-Leg Box Spread Breakdown:</span>
                              {opp.existingOpenPosition && (
                                <p className="text-[11px] font-bold text-amber-700 bg-amber-50 border border-amber-200 rounded-lg px-2 py-1">
                                  📌 You already have an OPEN {opp.existingPositionBroker || 'PAPER'} position for this exact signal. Trading again will open an additional position, not add to or replace the existing one.
                                </p>
                              )}
                              <p className="text-xs font-mono font-bold text-slate-800 bg-slate-50 p-2 rounded-lg border">{opp.legs || 'BUY CE1 | SELL PE1 | SELL CE2 | BUY PE2'}</p>
                              <ArbitrageSignalPayoffChart opp={opp} />
                              <div className="flex justify-end pt-1">
                                <button onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp); }} className="px-3 py-1 bg-purple-600 text-white rounded-lg text-xs font-bold shadow-md">
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
        {totalHistoryPages > 1 && (
          <div className="flex items-center justify-between px-4 py-3 border-t border-slate-200 bg-slate-50">
            <span className="text-xs text-slate-500">Page {histPage + 1} of {totalHistoryPages} ({totalHistory.toLocaleString('en-IN')} signals today)</span>
            <div className="flex gap-1">
              <button disabled={histPage === 0} onClick={() => setHistPage(0)} className="px-2 py-1 bg-white border rounded text-xs font-bold disabled:opacity-40">First</button>
              <button disabled={histPage === 0} onClick={() => setHistPage(histPage - 1)} className="px-2 py-1 bg-white border rounded text-xs font-bold disabled:opacity-40">Prev</button>
              <button disabled={histPage >= totalHistoryPages - 1} onClick={() => setHistPage(histPage + 1)} className="px-2 py-1 bg-white border rounded text-xs font-bold disabled:opacity-40">Next</button>
              <button disabled={histPage >= totalHistoryPages - 1} onClick={() => setHistPage(totalHistoryPages - 1)} className="px-2 py-1 bg-white border rounded text-xs font-bold disabled:opacity-40">Last</button>
            </div>
          </div>
        )}
      </div>
      </>
      )}
    </div>
  );
}

/* Box "near-miss" watchlist -- NOT a candidates panel like Butterfly/Vertical/Condor. A box's
   payoff at expiry is always exactly `width` regardless of settlement, so any box priced below
   width already IS genuine riskless arbitrage and is fully surfaced by the main scan above
   (its threshold is 0, nothing is filtered out). There's no "cheap but not quite arbitrage"
   tier for Box the way there is for the pin-bet strategies. This panel instead lists combos
   close to crossing that line -- purely informational, no payoff chart (the payoff is fixed,
   not settlement-dependent, so there's nothing to chart against price). */
function BoxNearMissPanel() {
  const [underlying, setUnderlying] = useState('ALL');
  const [maxGapPct, setMaxGapPct] = useState(0.15);
  const [sortCol, setSortCol] = useState('gapInr');
  const [sortAsc, setSortAsc] = useState(true);

  const { data, isLoading } = useQuery({
    queryKey: ['box-nearmiss', underlying, maxGapPct],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/box-spread/near-miss', { params: { underlying, maxGapPct } });
      return res.data;
    },
    refetchInterval: 30000
  });

  const nearMisses = data?.nearMisses || [];
  const toggleSort = (col) => { if (sortCol === col) setSortAsc(!sortAsc); else { setSortCol(col); setSortAsc(col === 'gapInr' || col === 'gapPct'); } };
  const sortIcon = (col) => sortCol === col ? (sortAsc ? ' ▲' : ' ▼') : ' ↕';
  const sorted = [...nearMisses].sort((a, b) => {
    let va = a[sortCol], vb = b[sortCol];
    if (sortCol === 'underlying' || sortCol === 'strikes' || sortCol === 'direction') {
      va = va || ''; vb = vb || '';
      return sortAsc ? String(va).localeCompare(String(vb)) : String(vb).localeCompare(String(va));
    }
    va = Number(va) || 0; vb = Number(vb) || 0;
    return sortAsc ? va - vb : vb - va;
  });

  return (
    <div className="space-y-4 w-full">
      <div className="bg-amber-50 border border-amber-300 rounded-2xl p-4 text-xs text-amber-900">
        <p className="font-bold mb-1">🔎 Not arbitrage yet — watchlist only</p>
        <p>A box spread's payoff at expiry is always exactly the strike width, no matter where the underlying settles — so any box priced below width is already genuine riskless arbitrage, and the Arbitrage Signals tab already shows every one of those. These are combos NOT yet profitable, but close — the quoted spread would need to narrow by roughly the gap shown for it to flip into a real box. Purely informational; nothing here is a live position.</p>
      </div>

      <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className="text-base font-bold text-slate-800">Near-Miss Box Watchlist</h2>
          <p className="text-xs text-slate-500">{nearMisses.length} combos within {Math.round(maxGapPct * 100)}% of width of becoming real arbitrage, sorted by closest gap first</p>
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          {['ALL', 'NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'].map(u => (
            <button key={u} onClick={() => setUnderlying(u)}
              className={`px-3 py-1 rounded-lg text-xs font-bold transition ${underlying === u ? 'bg-purple-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              {u}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          <span className="text-[10px] font-bold text-slate-500 px-1">MAX GAP</span>
          {[0.05, 0.15, 0.3].map(r => (
            <button key={r} onClick={() => setMaxGapPct(r)}
              className={`px-2 py-1 rounded-lg text-[10px] font-bold transition ${maxGapPct === r ? 'bg-emerald-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              {Math.round(r * 100)}%
            </button>
          ))}
        </div>
      </div>

      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm w-full">
        {isLoading ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">Scanning for near-miss boxes...</div>
        ) : nearMisses.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">No near-miss boxes within {Math.round(maxGapPct * 100)}% right now</div>
        ) : (
          <div className="overflow-x-auto w-full">
            <table className="w-full text-[11px] text-left border-collapse">
              <thead className="bg-slate-50 border-b border-slate-200 font-bold text-slate-600 uppercase">
                <tr>
                  <th className="px-2 py-2 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('underlying')}>Symbol{sortIcon('underlying')}</th>
                  <th className="px-2 py-2 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('strikes')}>Strikes{sortIcon('strikes')}</th>
                  <th className="px-2 py-2">Direction</th>
                  <th className="px-2 py-2 text-right">Width</th>
                  <th className="px-2 py-2 text-right text-red-600 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('gapInr')}>Gap (₹){sortIcon('gapInr')}</th>
                  <th className="px-2 py-2 text-right cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('gapPct')}>Gap %{sortIcon('gapPct')}</th>
                  <th className="px-2 py-2 text-right cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('daysToExpiry')}>DTE{sortIcon('daysToExpiry')}</th>
                  <th className="px-2 py-2">Legs</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {sorted.map((m, idx) => (
                  <tr key={`${m.underlying}-${m.k1}-${m.k2}-${idx}`} className="hover:bg-slate-50">
                    <td className="px-2 py-1.5 font-bold text-slate-800">{m.underlying}</td>
                    <td className="px-2 py-1.5 font-bold text-slate-700">{m.strikes}</td>
                    <td className="px-2 py-1.5 text-slate-600">{m.direction}</td>
                    <td className="px-2 py-1.5 text-right font-mono">{m.width}</td>
                    <td className="px-2 py-1.5 text-right font-mono text-red-600">₹{Math.round(m.gapInr).toLocaleString('en-IN')}</td>
                    <td className="px-2 py-1.5 text-right font-mono">{m.gapPct}%</td>
                    <td className="px-2 py-1.5 text-right font-mono text-slate-500">{m.daysToExpiry}d</td>
                    <td className="px-2 py-1.5 text-[10px] text-slate-500 truncate max-w-[280px]">{m.legs}</td>
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

/* 3b. VERTICAL SPREAD VIEW */
function VerticalSpreadView({ handleExecuteInline, executionBroker }) {
  const [subTab, setSubTab] = useState('signals');
  const [historyMode, setHistoryMode] = useState('arbitrage');
  const [underlying, setUnderlying] = useState('ALL');
  const [minEdge, setMinEdge] = useState(0);
  const [customEdge, setCustomEdge] = useState('');
  const [expandedId, setExpandedId] = useState(null);
  const [sortCol, setSortCol] = useState('scanTime');
  const [sortAsc, setSortAsc] = useState(false);
  const [histPage, setHistPage] = useState(0);
  const PAGE_SIZE = 200;

  const { data: liveData, isLoading } = useQuery({
    queryKey: ['vertical-spread-scan', underlying],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/vertical-spread/scan', { params: { underlying } });
      return res.data;
    },
    refetchInterval: 30000
  });

  const histDate = new Date(); histDate.setDate(histDate.getDate() - 6);
  const today2 = histDate.toISOString().split('T')[0];
  const todayEnd = new Date().toISOString().split('T')[0];

  const { data: historyData } = useQuery({
    queryKey: ['vertical-history', underlying, histPage],
    queryFn: async () => {
      const params = { page: histPage, size: PAGE_SIZE, strategyType: 'VERTICAL_SPREAD', startDate: today2, endDate: todayEnd };
      if (underlying !== 'ALL') params.underlying = underlying;
      const res = await client.get('/option-arbitrage/history', { params });
      return res.data;
    }
  });

  const liveOpps = (liveData?.opportunities || []).filter(o => (o.edgeAfterCosts || 0) >= minEdge);
  const historyOpps = (historyData?.items || []).filter(i => (i.edgeAfterCosts || 0) >= minEdge);

  const allOpps = [...liveOpps];
  const liveIds = new Set(liveOpps.map(o => o.id));
  historyOpps.forEach(h => { if (!liveIds.has(h.id)) allOpps.push(h); });

  const filteredByUnderlying = underlying === 'ALL' ? allOpps : allOpps.filter(o => o.underlying === underlying);
  const totalHistory = historyData?.totalElements || 0;
  const totalHistoryPages = historyData?.totalPages || 0;

  const vsAllIds = allOpps.filter(o => o.id).map(o => o.id);
  const { data: vsLivePnlRes } = useQuery({
    queryKey: ['vertical-pnl', vsAllIds.join(',')],
    queryFn: async () => {
      if (vsAllIds.length === 0) return { pnlMap: {}, statusMap: {} };
      const idsParam = vsAllIds.slice(0, 500).join(',');
      const res = await client.get('/option-arbitrage/history/live-pnl', { params: { ids: idsParam } });
      return { pnlMap: res.data?.pnlMap || {}, statusMap: res.data?.statusMap || {} };
    },
    refetchInterval: 10000,
    enabled: vsAllIds.length > 0
  });
  const vsPnlMap = vsLivePnlRes?.pnlMap || {};
  const vsStatusMap = vsLivePnlRes?.statusMap || {};

  const sortedOpps = [...filteredByUnderlying].sort((a, b) => {
    let va, vb;
    if (sortCol === 'scanTime') { va = a.scanTime || a.entryTime || ''; vb = b.scanTime || b.entryTime || ''; }
    else if (sortCol === 'underlying') { va = a.underlying || ''; vb = b.underlying || ''; }
    else if (sortCol === 'edge') { va = a.edgeAfterCosts || 0; vb = b.edgeAfterCosts || 0; }
    else if (sortCol === 'pnl') { va = a.pnlAfterCosts || 0; vb = b.pnlAfterCosts || 0; }
    else { va = a[sortCol] || ''; vb = b[sortCol] || ''; }
    if (typeof va === 'string') return sortAsc ? va.localeCompare(vb) : vb.localeCompare(va);
    return sortAsc ? va - vb : vb - va;
  });
  const toggleSort = (col) => { if (sortCol === col) setSortAsc(!sortAsc); else { setSortCol(col); setSortAsc(false); } };
  const sortIcon = (col) => sortCol === col ? (sortAsc ? ' ▲' : ' ▼') : ' ↕';

  return (
    <div className="space-y-4 w-full">
      <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl w-fit">
        <button onClick={() => setSubTab('signals')}
          className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${subTab === 'signals' ? 'bg-teal-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
          Arbitrage Signals
        </button>
        <button onClick={() => setSubTab('candidates')}
          className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${subTab === 'candidates' ? 'bg-amber-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
          🔍 Candidates (Not Arbitrage)
        </button>
        <button onClick={() => setSubTab('autotrade')}
          className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${subTab === 'autotrade' ? 'bg-indigo-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
          ⚡ Auto-Trade
        </button>
        <button onClick={() => setSubTab('history')}
          className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${subTab === 'history' ? 'bg-emerald-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
          📊 History
        </button>
      </div>

      {subTab === 'autotrade' ? (
        <StrategyAutoTradePanel prefix="vertical" label="Vertical Spread" accent="indigo" />
      ) : subTab === 'history' ? (
        <div className="space-y-3">
          <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl w-fit">
            <button onClick={() => setHistoryMode('arbitrage')}
              className={`px-3 py-1 rounded-lg text-xs font-bold transition ${historyMode === 'arbitrage' ? 'bg-teal-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              Arbitrage Signals
            </button>
            <button onClick={() => setHistoryMode('candidates')}
              className={`px-3 py-1 rounded-lg text-xs font-bold transition ${historyMode === 'candidates' ? 'bg-amber-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              🔍 Candidates
            </button>
          </div>
          {historyMode === 'candidates' ? (
            <CandidateHistoryPanel strategyType="VERTICAL_SPREAD" label="Vertical Spread" />
          ) : (
            <HistoryView lockedStrategy="VERTICAL" handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} />
          )}
        </div>
      ) : subTab === 'candidates' ? (
        <VerticalCandidatesPanel handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} />
      ) : (
      <>
      <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className="text-base font-bold text-slate-800">Vertical Spread No-Arbitrage Bound Scanner</h2>
          <p className="text-xs text-slate-500">{sortedOpps.length} signals shown{totalHistory > 0 ? ` of ${totalHistory.toLocaleString('en-IN')} total today` : ''} — model-free convexity bound (spread price vs strike width), no interest-rate or futures assumption</p>
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          {['ALL', 'NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'].map(u => (
            <button key={u} onClick={() => { setUnderlying(u); setHistPage(0); }}
              className={`px-3 py-1 rounded-lg text-xs font-bold transition ${underlying === u ? 'bg-teal-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              {u}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          <span className="text-[10px] font-bold text-slate-500 px-1">EDGE ≥</span>
          {[0, 100, 300].map(e => (
            <button key={e} onClick={() => { setMinEdge(e); setCustomEdge(''); setHistPage(0); }}
              className={`px-2 py-1 rounded-lg text-[10px] font-bold transition ${minEdge === e && !customEdge ? 'bg-emerald-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              ₹{e}
            </button>
          ))}
          <input type="number" placeholder="Custom" value={customEdge} onChange={e => { setCustomEdge(e.target.value); setMinEdge(Number(e.target.value) || 0); setHistPage(0); }}
            className="w-16 px-1.5 py-1 rounded-lg text-[10px] font-bold border border-slate-300 focus:outline-none focus:border-emerald-500" />
        </div>
      </div>

      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm w-full">
        {isLoading ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">Scanning Vertical Spreads...</div>
        ) : sortedOpps.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">No vertical spread bound violations for {underlying}</div>
        ) : (
          <div className="overflow-x-auto w-full">
            <table className="w-full text-[11px] text-left border-collapse">
              <thead className="bg-slate-50 border-b border-slate-200 font-bold text-slate-600 uppercase">
                <tr>
                  <th className="px-2 py-2 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('scanTime')}>Time{sortIcon('scanTime')}</th>
                  <th className="px-2 py-2 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('underlying')}>Symbol{sortIcon('underlying')}</th>
                  <th className="px-2 py-2">Strike Pair</th>
                  <th className="px-2 py-2">Action</th>
                  <th className="px-2 py-2 text-right text-emerald-600 font-bold cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('edge')}>Net Edge{sortIcon('edge')}</th>
                  <th className="px-2 py-2 text-center">Status</th>
                  <th className="px-2 py-2 text-right cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('pnl')}>P&amp;L{sortIcon('pnl')}</th>
                  <th className="px-2 py-2 text-center">Exit</th>
                  <th className="px-2 py-2 text-center">Trade</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {sortedOpps.map((opp, idx) => {
                  const isExp = expandedId === (opp.id || idx);
                  const posStatus = opp.id && vsStatusMap[String(opp.id)] ? String(vsStatusMap[String(opp.id)]).toUpperCase() : null;
                  const statusStr = posStatus || String(opp.status || 'RUNNING').toUpperCase();
                  const isLive = statusStr === 'RUNNING' || statusStr === 'OPEN' || statusStr === 'DETECTED' || statusStr === 'EXECUTING';
                  const isExited = statusStr === 'EXITED' || statusStr === 'CLOSED';
                  const livePnl = opp.id && vsPnlMap[String(opp.id)] != null ? Number(vsPnlMap[String(opp.id)]) : null;
                  const pnlDisplay = livePnl != null ? livePnl : (opp.pnlAfterCosts != null ? Number(opp.pnlAfterCosts) : null);
                  const strike1 = opp.strike || 0;
                  const strike2 = opp.action ? parseInt(opp.action.match(/\((\d+)\/(\d+)\)/)?.[2] || 0) : strike1;
                  const edgeVal = Number(opp.edgeAfterCosts || 0);

                  const timeStr = fmtTime(opp.scanTime || opp.entryTime);
                  const exitStr = isExited ? fmtTime(opp.exitTime) : '';

                  return (
                    <React.Fragment key={opp.id || idx}>
                      <tr onClick={() => setExpandedId(isExp ? null : (opp.id || idx))}
                        className={`transition cursor-pointer ${isExp ? 'bg-teal-50/70 border-l-4 border-teal-600' : 'hover:bg-slate-50'}`}>
                        <td className="px-2 py-1.5 font-mono text-[10px] text-slate-500">{timeStr}</td>
                        <td className="px-2 py-1.5 font-bold text-slate-800">{opp.underlying}</td>
                        <td className="px-2 py-1.5 font-bold text-slate-700">{strike1}/{strike2}</td>
                        <td className="px-2 py-1.5 font-bold text-teal-700 truncate max-w-[160px]">{opp.action}</td>
                        <td className="px-2 py-1.5 text-right font-mono font-bold text-emerald-600">
                          +₹{Math.round(edgeVal).toLocaleString('en-IN')}
                        </td>
                        <td className="px-2 py-1.5 text-center">
                          <span className={`px-1.5 py-0.2 rounded-full text-[9px] font-bold border ${
                            isLive ? 'bg-emerald-100 text-emerald-800 border-emerald-300' :
                            isExited ? 'bg-slate-200 text-slate-600 border-slate-300' :
                            statusStr === 'EXPIRED' ? 'bg-amber-100 text-amber-800 border-amber-300' :
                            'bg-blue-100 text-blue-800 border-blue-300'
                          }`}>
                            {isLive ? '🟢 RUNNING' : isExited ? '⏹ EXITED' : statusStr === 'EXPIRED' ? '⏰ EXPIRED' : statusStr}
                          </span>
                          {opp.existingOpenPosition && (
                            <span className="block mt-1 px-1.5 py-0.2 rounded-full text-[8px] font-bold border bg-amber-100 text-amber-800 border-amber-300" title={`You already hold an OPEN ${opp.existingPositionBroker || 'PAPER'} position for this signal`}>
                              📌 Already holding
                            </span>
                          )}
                        </td>
                        <td className="px-2 py-1.5 text-right font-mono font-bold">
                          {pnlDisplay != null && !isNaN(pnlDisplay)
                            ? <span className={pnlDisplay >= 0 ? 'text-emerald-600' : 'text-red-600'}>₹{Math.round(pnlDisplay).toLocaleString('en-IN')}</span>
                            : <span className="text-slate-400">--</span>}
                        </td>
                        <td className="px-2 py-1.5 text-center font-mono text-[10px] text-slate-500">{exitStr}</td>
                        <td className="px-2 py-1.5 text-center">
                          <button onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp); }}
                            className="px-2 py-0.5 bg-teal-600 text-white text-[10px] font-bold rounded shadow-sm">
                            ⚡ Trade
                          </button>
                        </td>
                      </tr>
                      {isExp && (
                        <tr className="bg-teal-50/40 border-b border-teal-100">
                          <td colSpan={9} className="p-3">
                            <div className="bg-white rounded-xl p-3 border border-teal-200 shadow-md space-y-2">
                              <span className="font-bold text-slate-800 text-xs uppercase block">Vertical Spread Breakdown:</span>
                              {opp.existingOpenPosition && (
                                <p className="text-[11px] font-bold text-amber-700 bg-amber-50 border border-amber-200 rounded-lg px-2 py-1">
                                  📌 You already have an OPEN {opp.existingPositionBroker || 'PAPER'} position for this exact signal. Trading again will open an additional position, not add to or replace the existing one.
                                </p>
                              )}
                              <p className="text-xs font-mono font-bold text-slate-800 bg-slate-50 p-2 rounded-lg border">{opp.legs || '—'}</p>
                              <p className="text-[10px] text-slate-500">{opp.description}</p>
                              <div className="flex justify-end pt-1">
                                <button onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp); }} className="px-3 py-1 bg-teal-600 text-white rounded-lg text-xs font-bold shadow-md">
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
        {totalHistoryPages > 1 && (
          <div className="flex items-center justify-between px-4 py-3 border-t border-slate-200 bg-slate-50">
            <span className="text-xs text-slate-500">Page {histPage + 1} of {totalHistoryPages} ({totalHistory.toLocaleString('en-IN')} signals today)</span>
            <div className="flex gap-1">
              <button disabled={histPage === 0} onClick={() => setHistPage(0)} className="px-2 py-1 bg-white border rounded text-xs font-bold disabled:opacity-40">First</button>
              <button disabled={histPage === 0} onClick={() => setHistPage(histPage - 1)} className="px-2 py-1 bg-white border rounded text-xs font-bold disabled:opacity-40">Prev</button>
              <button disabled={histPage >= totalHistoryPages - 1} onClick={() => setHistPage(histPage + 1)} className="px-2 py-1 bg-white border rounded text-xs font-bold disabled:opacity-40">Next</button>
              <button disabled={histPage >= totalHistoryPages - 1} onClick={() => setHistPage(totalHistoryPages - 1)} className="px-2 py-1 bg-white border rounded text-xs font-bold disabled:opacity-40">Last</button>
            </div>
          </div>
        )}
      </div>
      </>
      )}
    </div>
  );
}

/* Vertical "cheap debit spread" candidate discovery panel -- NOT arbitrage, evaluation only.
   Directional (single breakeven), unlike Butterfly's pin-bet range -- structure mirrors
   ButterflyCandidatesPanel (Today/Expiry chart, positions, margin, exit rules) but with
   2-leg payoff math and one-sided POP. */
function VerticalCandidatesPanel({ handleExecuteInline, executionBroker }) {
  const [underlying, setUnderlying] = useState('ALL');
  const [maxCostRatio, setMaxCostRatio] = useState(0.35);
  const [selected, setSelected] = useState({});
  const [hover, setHover] = useState(null);
  const [autoSelected, setAutoSelected] = useState(false);
  const [sortCol, setSortCol] = useState('pop');
  const [sortAsc, setSortAsc] = useState(false);
  const chartRef = useRef(null);

  const { data, isLoading } = useQuery({
    queryKey: ['vertical-candidates', underlying, maxCostRatio],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/vertical-spread/candidates', { params: { underlying, maxCostRatio } });
      return res.data;
    },
    refetchInterval: 30000
  });

  const { data: execSettings } = useQuery({
    queryKey: ['autoExecSettingsForVerticalCandidates'],
    queryFn: async () => (await client.get('/option-arbitrage/auto-execute/settings')).data,
    refetchInterval: 60000
  });

  const candidates = data?.candidates || [];
  // Order-independent so sorting the table doesn't break which row shows as selected.
  const rowKey = (c) => `${c.underlying}-${c.optionType}-${c.k1}-${c.k2}`;

  useEffect(() => {
    if (!autoSelected && candidates.length > 0) {
      setSelected({ [rowKey(candidates[0])]: true });
      setAutoSelected(true);
    }
  }, [candidates, autoSelected]);

  const selectedCandidates = candidates.filter((c) => selected[rowKey(c)]);
  const toggle = (key) => setSelected(prev => ({ ...prev, [key]: !prev[key] }));
  const toggleSort = (col) => { if (sortCol === col) setSortAsc(!sortAsc); else { setSortCol(col); setSortAsc(col === 'strikes' || col === 'underlying'); } };
  const sortIcon = (col) => sortCol === col ? (sortAsc ? ' ▲' : ' ▼') : ' ↕';
  const sortedCandidates = [...candidates].sort((a, b) => {
    let va = a[sortCol], vb = b[sortCol];
    if (sortCol === 'strikes' || sortCol === 'underlying' || sortCol === 'optionType') {
      va = va || ''; vb = vb || '';
      return sortAsc ? String(va).localeCompare(String(vb)) : String(vb).localeCompare(String(va));
    }
    va = Number(va) || 0; vb = Number(vb) || 0;
    return sortAsc ? va - vb : vb - va;
  });

  const payoffChart = useMemo(() => {
    if (selectedCandidates.length === 0) return null;
    const spot = selectedCandidates[0].spotPrice || selectedCandidates.reduce((s, c) => s + c.spotPrice, 0) / selectedCandidates.length;
    const lo = Math.min(...selectedCandidates.map(c => c.k1)) - 300;
    const hi = Math.max(...selectedCandidates.map(c => c.k2)) + 300;
    const steps = 200;
    const stepSize = (hi - lo) / steps;
    const points = [];
    const todayPoints = [];
    let minY = 0, maxY = 0;
    for (let i = 0; i <= steps; i++) {
      const x = lo + i * stepSize;
      let expiryTotal = 0, todayTotal = 0;
      for (const c of selectedCandidates) {
        const lotSize = c.lotSize || 25;
        const T = Math.max(c.daysToExpiry, 0.5) / 365;
        const r = c.riskFreeRate || 0.065;
        const sigma = (c.impliedVol || 20) / 100;

        let expiryPayoff, todayValue;
        if (c.optionType === 'CE') {
          expiryPayoff = Math.max(x - c.k1, 0) - Math.max(x - c.k2, 0);
          todayValue = bsCallPrice(x, c.k1, T, r, sigma) - bsCallPrice(x, c.k2, T, r, sigma);
        } else {
          expiryPayoff = Math.max(c.k2 - x, 0) - Math.max(c.k1 - x, 0);
          todayValue = bsPutPrice(x, c.k2, T, r, sigma) - bsPutPrice(x, c.k1, T, r, sigma);
        }
        expiryTotal += (expiryPayoff - c.costPerLot) * lotSize;
        todayTotal += (todayValue - c.costPerLot) * lotSize;
      }
      points.push({ x, y: expiryTotal });
      todayPoints.push({ x, y: todayTotal });
      minY = Math.min(minY, expiryTotal, todayTotal);
      maxY = Math.max(maxY, expiryTotal, todayTotal);
    }
    return { points, todayPoints, spot, lo, hi, minY: Math.min(minY, 0), maxY: Math.max(maxY, 0) };
  }, [selectedCandidates]);

  const totalMaxLoss = selectedCandidates.reduce((s, c) => s + c.maxLoss, 0);
  const totalMaxProfit = selectedCandidates.reduce((s, c) => s + c.maxProfit, 0);
  const totalMargin = selectedCandidates.reduce((s, c) => s + (c.marginEstimate || c.maxLoss), 0);
  const totalCharges = selectedCandidates.reduce((s, c) => s + (c.entryCosts || 0), 0);
  const avgPop = selectedCandidates.length > 0
    ? selectedCandidates.reduce((s, c) => s + c.pop, 0) / selectedCandidates.length : null;
  const breakevenGap = (c) => {
    if (c.spotPrice == null) return null;
    if (c.breakevenUpper != null) return c.spotPrice - c.breakevenUpper;
    if (c.breakevenLower != null) return c.breakevenLower - c.spotPrice;
    return null;
  };
  const soloBreakevenGap = selectedCandidates.length === 1 ? breakevenGap(selectedCandidates[0]) : null;

  const CHART_W = 700, CHART_H = 260, PAD_TOP = 24, PAD_BOTTOM = 34;
  const plotH = CHART_H - PAD_TOP - PAD_BOTTOM;
  const xToPx = (x) => payoffChart ? ((x - payoffChart.lo) / (payoffChart.hi - payoffChart.lo)) * CHART_W : 0;
  const yToPx = (y) => payoffChart
    ? PAD_TOP + plotH - ((y - payoffChart.minY) / (payoffChart.maxY - payoffChart.minY || 1)) * plotH
    : 0;
  const pxToX = (px) => payoffChart ? payoffChart.lo + (px / CHART_W) * (payoffChart.hi - payoffChart.lo) : 0;

  const handleChartMove = (e) => {
    if (!payoffChart || !chartRef.current) return;
    const rect = chartRef.current.getBoundingClientRect();
    const relX = (e.clientX - rect.left) / rect.width;
    const px = relX * CHART_W;
    const priceAtCursor = pxToX(px);
    let nearestIdx = 0, bestDist = Infinity;
    payoffChart.points.forEach((p, i) => {
      const d = Math.abs(p.x - priceAtCursor);
      if (d < bestDist) { bestDist = d; nearestIdx = i; }
    });
    const expiry = payoffChart.points[nearestIdx];
    const today = payoffChart.todayPoints[nearestIdx];
    setHover({ px: xToPx(expiry.x), pyExpiry: yToPx(expiry.y), pyToday: yToPx(today.y), price: expiry.x, pnlExpiry: expiry.y, pnlToday: today.y });
  };

  const zeroPx = payoffChart ? yToPx(0) : 0;
  const areaPath = payoffChart ? (() => {
    const pts = payoffChart.points.map(p => `${xToPx(p.x)},${yToPx(p.y)}`).join(' L ');
    return `M ${xToPx(payoffChart.points[0].x)},${zeroPx} L ${pts} L ${xToPx(payoffChart.points[payoffChart.points.length - 1].x)},${zeroPx} Z`;
  })() : '';

  return (
    <div className="space-y-4 w-full">
      <div className="bg-amber-50 border border-amber-300 rounded-2xl p-4 text-xs text-amber-900">
        <p className="font-bold mb-1">⚠️ Not arbitrage — evaluation tool only</p>
        <p>These are debit spreads priced cheap relative to their width — a directional bet (profits if price clears the breakeven by expiry), not a guaranteed-profit position. POP is a Black-Scholes model estimate from current implied volatility, not a backtested or historical win rate. Move your mouse over the chart to see P&amp;L at any settlement price.</p>
      </div>

      <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className="text-base font-bold text-slate-800">Cheap Vertical Spread Candidates</h2>
          <p className="text-xs text-slate-500">{candidates.length} candidates — cost ≤ {Math.round(maxCostRatio * 100)}% of width, sorted by model POP (highest first)</p>
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          {['ALL', 'NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'].map(u => (
            <button key={u} onClick={() => { setUnderlying(u); setAutoSelected(false); }}
              className={`px-3 py-1 rounded-lg text-xs font-bold transition ${underlying === u ? 'bg-amber-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              {u}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          <span className="text-[10px] font-bold text-slate-500 px-1">MAX COST/WIDTH</span>
          {[0.2, 0.35, 0.5].map(r => (
            <button key={r} onClick={() => { setMaxCostRatio(r); setAutoSelected(false); }}
              className={`px-2 py-1 rounded-lg text-[10px] font-bold transition ${maxCostRatio === r ? 'bg-emerald-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              {Math.round(r * 100)}%
            </button>
          ))}
        </div>
        {selectedCandidates.length > 0 && (
          <button onClick={() => setSelected({})}
            className="px-3 py-1.5 rounded-lg text-xs font-bold bg-red-50 text-red-600 border border-red-200 hover:bg-red-100 transition">
            ✕ Clear Selection ({selectedCandidates.length})
          </button>
        )}
      </div>

      {selectedCandidates.length > 0 && (
        <div className="bg-gradient-to-br from-white via-amber-50/30 to-indigo-50/30 rounded-2xl border-2 border-amber-200 shadow-lg p-5 space-y-5">
          <div className="grid grid-cols-2 md:grid-cols-7 gap-3">
            <div className="bg-white rounded-xl border border-slate-200 p-2.5 text-center">
              <div className="text-[9px] font-bold text-slate-400 uppercase">POP</div>
              <div className={`text-lg font-black ${avgPop >= 60 ? 'text-emerald-600' : avgPop >= 40 ? 'text-amber-600' : 'text-slate-500'}`}>{avgPop?.toFixed(1)}%</div>
            </div>
            <div className="bg-white rounded-xl border border-slate-200 p-2.5 text-center">
              <div className="text-[9px] font-bold text-slate-400 uppercase">Max Loss</div>
              <div className="text-lg font-black text-red-600">₹{Math.round(totalMaxLoss).toLocaleString('en-IN')}</div>
            </div>
            <div className="bg-white rounded-xl border border-slate-200 p-2.5 text-center">
              <div className="text-[9px] font-bold text-slate-400 uppercase">Max Profit</div>
              <div className="text-lg font-black text-emerald-600">₹{Math.round(totalMaxProfit).toLocaleString('en-IN')}</div>
            </div>
            <div className="bg-white rounded-xl border border-slate-200 p-2.5 text-center">
              <div className="text-[9px] font-bold text-slate-400 uppercase">Risk:Reward</div>
              <div className="text-lg font-black text-indigo-600">{totalMaxLoss > 0 ? (totalMaxProfit / totalMaxLoss).toFixed(1) : '0'}x</div>
            </div>
            <div className="bg-white rounded-xl border border-slate-200 p-2.5 text-center">
              <div className="text-[9px] font-bold text-slate-400 uppercase">Capital Required*</div>
              <div className="text-lg font-black text-slate-700">₹{Math.round(totalMargin).toLocaleString('en-IN')}</div>
            </div>
            <div className="bg-white rounded-xl border border-slate-200 p-2.5 text-center">
              <div className="text-[9px] font-bold text-slate-400 uppercase">Charges</div>
              <div className="text-lg font-black text-slate-700">₹{Math.round(totalCharges).toLocaleString('en-IN')}</div>
            </div>
            <div className="bg-white rounded-xl border border-slate-200 p-2.5 text-center">
              <div className="text-[9px] font-bold text-slate-400 uppercase">To Breakeven</div>
              <div className={`text-lg font-black ${soloBreakevenGap == null ? 'text-slate-300' : soloBreakevenGap >= 0 ? 'text-emerald-600' : Math.abs(soloBreakevenGap) < 50 ? 'text-amber-600' : 'text-red-600'}`}>
                {soloBreakevenGap == null ? '—' : soloBreakevenGap >= 0 ? `✅ +${Math.round(soloBreakevenGap)}` : `${Math.round(soloBreakevenGap)}`}
              </div>
            </div>
          </div>
          <p className="text-[9px] text-slate-400 -mt-3">*Margin is a conservative estimate (worst-case cash outflow) — actual broker SPAN+exposure margin may differ; verify with your broker before trading.</p>
          {soloBreakevenGap != null && soloBreakevenGap < 0 && (
            <p className="text-[11px] font-bold text-amber-700 bg-amber-50 border border-amber-200 rounded-lg px-3 py-1.5 -mt-1">⚠️ Spot still needs to move {Math.round(Math.abs(soloBreakevenGap))} points to reach breakeven — currently outside the profit zone.</p>
          )}

          {payoffChart && (
            <div>
              <div className="flex items-center gap-4 mb-1 px-1">
                <span className="flex items-center gap-1.5 text-[10px] font-bold text-amber-700"><span className="w-3 h-0.5 bg-amber-600 inline-block rounded" /> At Expiry</span>
                <span className="flex items-center gap-1.5 text-[10px] font-bold text-blue-600"><span className="w-3 h-0.5 bg-blue-500 inline-block rounded" /> Today (Black-Scholes est.)</span>
              </div>
              <div className="relative overflow-x-auto bg-white rounded-xl border border-slate-100 p-2">
                <svg ref={chartRef} viewBox={`0 0 ${CHART_W} ${CHART_H}`} className="w-full h-[260px] cursor-crosshair"
                  onMouseMove={handleChartMove} onMouseLeave={() => setHover(null)}>
                  <defs>
                    <linearGradient id="vProfitGrad" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor="#10b981" stopOpacity="0.35" />
                      <stop offset="100%" stopColor="#10b981" stopOpacity="0.02" />
                    </linearGradient>
                    <linearGradient id="vLossGrad" x1="0" y1="1" x2="0" y2="0">
                      <stop offset="0%" stopColor="#ef4444" stopOpacity="0.3" />
                      <stop offset="100%" stopColor="#ef4444" stopOpacity="0.02" />
                    </linearGradient>
                  </defs>
                  <clipPath id="vAboveZero"><rect x="0" y="0" width={CHART_W} height={zeroPx} /></clipPath>
                  <clipPath id="vBelowZero"><rect x="0" y={zeroPx} width={CHART_W} height={CHART_H - zeroPx} /></clipPath>
                  <path d={areaPath} fill="url(#vProfitGrad)" clipPath="url(#vAboveZero)" />
                  <path d={areaPath} fill="url(#vLossGrad)" clipPath="url(#vBelowZero)" />

                  <line x1="0" y1={zeroPx} x2={CHART_W} y2={zeroPx} stroke="#94a3b8" strokeWidth="1" strokeDasharray="4,4" />
                  <line x1={xToPx(payoffChart.spot)} y1={PAD_TOP} x2={xToPx(payoffChart.spot)} y2={CHART_H - PAD_BOTTOM}
                    stroke="#6366f1" strokeWidth="1.5" strokeDasharray="3,3" />
                  <text x={xToPx(payoffChart.spot)} y={PAD_TOP - 8} textAnchor="middle" fontSize="10" fontWeight="700" fill="#6366f1">
                    Spot {Math.round(payoffChart.spot).toLocaleString('en-IN')}
                  </text>

                  <polyline
                    fill="none" stroke="#3b82f6" strokeWidth="2" strokeLinejoin="round" opacity="0.85"
                    points={payoffChart.todayPoints.map(p => `${xToPx(p.x)},${yToPx(p.y)}`).join(' ')}
                  />
                  <polyline
                    fill="none" stroke="#d97706" strokeWidth="2.5" strokeLinejoin="round"
                    points={payoffChart.points.map(p => `${xToPx(p.x)},${yToPx(p.y)}`).join(' ')}
                  />

                  {hover && (
                    <g>
                      <line x1={hover.px} y1={PAD_TOP} x2={hover.px} y2={CHART_H - PAD_BOTTOM} stroke="#0f172a" strokeWidth="1" strokeDasharray="2,2" opacity="0.4" />
                      <circle cx={hover.px} cy={hover.pyToday} r="4" fill="#3b82f6" stroke="white" strokeWidth="1.5" />
                      <circle cx={hover.px} cy={hover.pyExpiry} r="4.5" fill={hover.pnlExpiry >= 0 ? '#10b981' : '#ef4444'} stroke="white" strokeWidth="1.5" />
                      {(() => {
                        const boxW = 150, boxH = 62;
                        const bx = Math.min(Math.max(hover.px - boxW / 2, 2), CHART_W - boxW - 2);
                        const anchorY = Math.min(hover.pyExpiry, hover.pyToday);
                        const by = anchorY > 90 ? anchorY - boxH - 10 : Math.max(hover.pyExpiry, hover.pyToday) + 14;
                        return (
                          <g>
                            <rect x={bx} y={by} width={boxW} height={boxH} rx="6" fill="#0f172a" opacity="0.94" />
                            <text x={bx + 8} y={by + 15} fontSize="10" fill="#cbd5e1">
                              @ {Math.round(hover.price).toLocaleString('en-IN')}
                            </text>
                            <text x={bx + 8} y={by + 32} fontSize="11" fontWeight="700" fill="#93c5fd">
                              Today: {hover.pnlToday >= 0 ? '+' : ''}₹{Math.round(hover.pnlToday).toLocaleString('en-IN')}
                            </text>
                            <text x={bx + 8} y={by + 49} fontSize="11" fontWeight="800" fill={hover.pnlExpiry >= 0 ? '#34d399' : '#f87171'}>
                              Expiry: {hover.pnlExpiry >= 0 ? '+' : ''}₹{Math.round(hover.pnlExpiry).toLocaleString('en-IN')}
                            </text>
                          </g>
                        );
                      })()}
                    </g>
                  )}

                  {selectedCandidates.length === 1 && (selectedCandidates[0].breakevenLower || selectedCandidates[0].breakevenUpper) && (
                    <line x1={xToPx(selectedCandidates[0].breakevenLower || selectedCandidates[0].breakevenUpper)} y1={PAD_TOP}
                      x2={xToPx(selectedCandidates[0].breakevenLower || selectedCandidates[0].breakevenUpper)} y2={CHART_H - PAD_BOTTOM}
                      stroke="#94a3b8" strokeWidth="1" strokeDasharray="2,3" opacity="0.6" />
                  )}
                </svg>
              </div>
              <div className="flex justify-between text-[10px] text-slate-500 px-1 mt-1">
                <span>{Math.round(payoffChart.lo).toLocaleString('en-IN')}</span>
                <span>{Math.round(payoffChart.hi).toLocaleString('en-IN')}</span>
              </div>
              <p className="text-[10px] text-slate-400 text-center mt-1">P&amp;L (₹) vs. settlement price — hover to inspect any price point</p>
            </div>
          )}

          <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
            <div className="px-3 py-2 bg-slate-50 border-b border-slate-200 text-[10px] font-black text-slate-600 uppercase">
              📋 Positions to be taken
            </div>
            <table className="w-full text-[11px]">
              <thead className="text-slate-400 text-[9px] uppercase">
                <tr>
                  <th className="px-3 py-1.5 text-left">Symbol</th>
                  <th className="px-3 py-1.5 text-left">Side</th>
                  <th className="px-3 py-1.5 text-right">Strike</th>
                  <th className="px-3 py-1.5 text-right">Qty (lots)</th>
                  <th className="px-3 py-1.5 text-right">Price</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {selectedCandidates.flatMap((c, ci) => (c.legList || []).map((leg, li) => (
                  <tr key={`${ci}-${li}`}>
                    <td className="px-3 py-1.5 font-bold text-slate-700">{c.underlying} {leg.optionType}</td>
                    <td className="px-3 py-1.5">
                      <span className={`px-1.5 py-0.5 rounded text-[9px] font-black ${leg.side === 'BUY' ? 'bg-emerald-100 text-emerald-700' : 'bg-red-100 text-red-700'}`}>{leg.side}</span>
                    </td>
                    <td className="px-3 py-1.5 text-right font-mono">{leg.strike}</td>
                    <td className="px-3 py-1.5 text-right font-mono">{leg.qty}</td>
                    <td className="px-3 py-1.5 text-right font-mono">₹{leg.price?.toFixed(2)}</td>
                  </tr>
                )))}
              </tbody>
            </table>
          </div>

          <div className="bg-indigo-50 rounded-xl border border-indigo-200 p-3 text-[11px] text-indigo-900 space-y-1">
            <p className="font-black uppercase text-[10px] text-indigo-600">🛡️ If you trade this — what happens automatically</p>
            {execSettings ? (
              <>
                <p><strong>Auto-exit on target:</strong> {execSettings.autoExitEnabled ? `ON — squares off at ${execSettings.autoExitThresholdPct ?? 90}% of max profit` : 'OFF'}</p>
                <p><strong>Stop-loss:</strong> {execSettings.stopLossEnabled ? `ON — squares off at ${execSettings.stopLossPct ?? 50}% of max loss` : 'OFF'}</p>
                <p className="text-indigo-500">Change these in the Auto-Trade tab. Only applies if you actually execute the trade (paper or live) — this panel is not a live position.</p>
              </>
            ) : (
              <p className="text-indigo-400">Loading current settings…</p>
            )}
            <p className="text-red-600 font-bold pt-1">⚠️ No automatic strike-adjustment or rolling exists for these spreads. If price moves against the position, it holds until it hits the stop-loss, hits the target, or expires — nothing rebalances it for you.</p>
          </div>
        </div>
      )}

      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm w-full">
        {isLoading ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">Scanning for cheap vertical spread candidates...</div>
        ) : data?.marketClosed ? (
          <div className="p-12 text-center text-sm font-semibold">
            <div className="text-3xl mb-2">🌙</div>
            <div className="text-slate-500">Market is closed</div>
            <div className="text-slate-400 text-xs font-normal mt-1">{data?.reason || 'NSE/NFO hours: Mon-Fri 09:15-15:30 IST'}</div>
          </div>
        ) : candidates.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">No candidates under {Math.round(maxCostRatio * 100)}% cost/width right now</div>
        ) : (
          <div className="overflow-x-auto w-full">
            <table className="w-full text-[11px] text-left border-collapse">
              <thead className="bg-slate-50 border-b border-slate-200 font-bold text-slate-600 uppercase">
                <tr>
                  <th className="px-2 py-2"></th>
                  <th className="px-2 py-2 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('underlying')}>Symbol{sortIcon('underlying')}</th>
                  <th className="px-2 py-2 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('strikes')}>Strikes{sortIcon('strikes')}</th>
                  <th className="px-2 py-2">Type</th>
                  <th className="px-2 py-2">Direction</th>
                  <th className="px-2 py-2 text-right cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('costRatio')}>Cost/Width{sortIcon('costRatio')}</th>
                  <th className="px-2 py-2 text-right text-indigo-600 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('pop')}>POP{sortIcon('pop')}</th>
                  <th className="px-2 py-2 text-right text-red-600 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('maxLoss')}>Max Loss{sortIcon('maxLoss')}</th>
                  <th className="px-2 py-2 text-right text-emerald-600 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('maxProfit')}>Max Profit{sortIcon('maxProfit')}</th>
                  <th className="px-2 py-2 text-right cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('marginEstimate')}>Capital Req.*{sortIcon('marginEstimate')}</th>
                  <th className="px-2 py-2 text-right cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('riskReward')}>R:R{sortIcon('riskReward')}</th>
                  <th className="px-2 py-2 text-right cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('impliedVol')}>IV{sortIcon('impliedVol')}</th>
                  <th className="px-2 py-2 text-right cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('daysToExpiry')}>DTE{sortIcon('daysToExpiry')}</th>
                  <th className="px-2 py-2 text-right">To BE</th>
                  <th className="px-2 py-2 text-center">Trade</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {sortedCandidates.map((c) => {
                  const key = rowKey(c);
                  const isSel = !!selected[key];
                  return (
                    <tr key={key} className={`transition ${isSel ? 'bg-amber-50' : 'hover:bg-slate-50'}`}>
                      <td className="px-2 py-1.5 text-center">
                        <input type="checkbox" checked={isSel} onChange={() => toggle(key)} className="w-3.5 h-3.5" />
                      </td>
                      <td className="px-2 py-1.5 font-bold text-slate-800">{c.underlying}</td>
                      <td className="px-2 py-1.5 font-bold text-slate-700">{c.strikes}</td>
                      <td className="px-2 py-1.5 text-slate-600">{c.optionType}</td>
                      <td className="px-2 py-1.5 text-[10px] text-slate-500">{c.direction}</td>
                      <td className="px-2 py-1.5 text-right font-mono">{Math.round(c.costRatio * 100)}%</td>
                      <td className="px-2 py-1.5 text-right">
                        <span className={`px-1.5 py-0.5 rounded-full text-[10px] font-black ${c.pop >= 60 ? 'bg-emerald-100 text-emerald-700' : c.pop >= 40 ? 'bg-amber-100 text-amber-700' : 'bg-slate-100 text-slate-500'}`}>
                          {c.pop}%
                        </span>
                      </td>
                      <td className="px-2 py-1.5 text-right font-mono text-red-600">₹{Math.round(c.maxLoss).toLocaleString('en-IN')}</td>
                      <td className="px-2 py-1.5 text-right font-mono text-emerald-600">₹{Math.round(c.maxProfit).toLocaleString('en-IN')}</td>
                      <td className="px-2 py-1.5 text-right font-mono text-slate-500">₹{Math.round(c.marginEstimate || c.maxLoss).toLocaleString('en-IN')}</td>
                      <td className="px-2 py-1.5 text-right font-mono font-bold">{c.riskReward}x</td>
                      <td className="px-2 py-1.5 text-right font-mono text-slate-500">{c.impliedVol}%</td>
                      <td className="px-2 py-1.5 text-right font-mono text-slate-500">{c.daysToExpiry}d</td>
                      <td className="px-2 py-1.5 text-right font-mono text-[10px]">
                        {(() => { const g = breakevenGap(c); return g == null ? '—' : g >= 0 ? <span className="text-emerald-600 font-bold">✅+{Math.round(g)}</span> : <span className={Math.abs(g) < 50 ? 'text-amber-600 font-bold' : 'text-red-600 font-bold'}>{Math.round(g)}</span>; })()}
                      </td>
                      <td className="px-2 py-1.5 text-center">
                        <button onClick={() => handleExecuteInline(c)}
                          className="px-2 py-0.5 bg-amber-600 text-white text-[10px] font-bold rounded shadow-sm">
                          ⚡ Trade
                        </button>
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

/* Auto-roll pending-confirmation banner -- a Butterfly position was closed automatically
   after sitting outside its profit zone past the configured breach window, and a
   re-centered replacement is proposed here. Closing was automatic; entering the
   replacement is not -- this always needs a one-click confirm. */
function AutoRollPendingPanel() {
  const [pending, setPending] = useState([]);
  const [busyId, setBusyId] = useState(null);

  const load = async () => {
    try {
      const res = await client.get('/option-arbitrage/auto-roll/pending');
      setPending(res.data?.pending || []);
    } catch (e) { /* silent -- non-critical polling */ }
  };

  useEffect(() => {
    load();
    const t = setInterval(load, 15000);
    return () => clearInterval(t);
  }, []);

  const confirm = async (id) => {
    setBusyId(id);
    try {
      await client.post(`/option-arbitrage/auto-roll/${id}/confirm`);
      await load();
    } catch (e) { /* keep in list so user can retry */ }
    setBusyId(null);
  };

  const dismiss = async (id) => {
    setBusyId(id);
    try {
      await client.post(`/option-arbitrage/auto-roll/${id}/dismiss`);
      await load();
    } catch (e) { /* keep in list so user can retry */ }
    setBusyId(null);
  };

  if (pending.length === 0) return null;

  return (
    <div className="space-y-2">
      {pending.map(p => {
        const c = p.proposal;
        return (
          <div key={p.id} className="bg-purple-50 border-2 border-purple-300 rounded-2xl p-4 flex flex-wrap items-center justify-between gap-3">
            <div className="flex-1 min-w-[280px]">
              <p className="text-xs font-black text-purple-700 uppercase mb-1">🔄 Auto-Roll Awaiting Confirm — Roll {p.rollCount}</p>
              <p className="text-sm text-slate-700">
                {p.underlying} butterfly closed after breaching breakeven
                {p.lastClosedPnl != null && (
                  <span className={p.lastClosedPnl >= 0 ? 'text-emerald-600 font-bold' : 'text-red-600 font-bold'}>
                    {' '}(P&amp;L ₹{Math.round(p.lastClosedPnl).toLocaleString('en-IN')})
                  </span>
                )}
                {c ? (
                  <> — proposing a new one at <strong>{c.strikes}</strong> {c.optionType}, cost ₹{c.costPerLot}/lot,
                  max loss ₹{Math.round(c.maxLoss).toLocaleString('en-IN')}, max profit ₹{Math.round(c.maxProfit).toLocaleString('en-IN')}.</>
                ) : ' — no re-entry could be constructed.'}
              </p>
            </div>
            {c && (
              <div className="flex gap-2">
                <button disabled={busyId === p.id} onClick={() => confirm(p.id)}
                  className="px-3 py-1.5 bg-purple-600 text-white text-xs font-bold rounded-lg shadow-sm disabled:opacity-50">
                  ✓ Confirm Roll
                </button>
                <button disabled={busyId === p.id} onClick={() => dismiss(p.id)}
                  className="px-3 py-1.5 bg-white text-slate-600 border border-slate-300 text-xs font-bold rounded-lg disabled:opacity-50">
                  ✕ Dismiss
                </button>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

/* 3c. BUTTERFLY SPREAD VIEW */
/* Payoff-at-expiry mini chart for a real Butterfly arbitrage signal (not a Candidate) --
   strikes parsed from the action string, cost derived from the actual leg prices already
   on the opportunity. Model-free: this is the guaranteed convexity-bound payoff, not a
   Black-Scholes probability estimate, so there's no "Today" curve or POP here -- the whole
   point of a real arbitrage signal is that it's non-negative everywhere, at any settlement. */
function ArbitrageSignalPayoffChart({ opp }) {
  const chartRef = useRef(null);
  const [hover, setHover] = useState(null);

  const hasLegs = Array.isArray(opp.legList) && opp.legList.length >= 2;

  const lotSize = Number(opp.lotSize) > 0 ? Number(opp.lotSize) : 1;

  const chart = useMemo(() => {
    if (!hasLegs) return null;
    // Generic across every multi-leg strategy (Vertical/Butterfly/Condor/Box, CE-only,
    // PE-only, or mixed CE+PE like a box spread) -- payoff(x) is just the sum of each leg's
    // own intrinsic value at settlement x, signed by side and scaled by its qty multiplier.
    // This replaced a version hardcoded to 3-leg CE-only butterflies (regex-parsed from the
    // action string), which silently produced nothing for box spreads or any PE leg.
    // A FUT leg (Bid Parity's conversion/reversal) has no strike -- its payoff is linear in
    // settlement price (x itself, signed), not an option's capped intrinsic value.
    const optionStrikes = [...new Set(opp.legList.filter(l => String(l.optionType).toUpperCase() !== 'FUT')
      .map(l => Number(l.strike)).filter(n => !isNaN(n)))].sort((a, b) => a - b);
    if (optionStrikes.length === 0) return null;
    const k1 = optionStrikes[0], k2 = optionStrikes[optionStrikes.length - 1];
    // Bid Parity has a single strike (same K for both CE and PE) -- fall back to a sensible
    // range around it since there's no wing-to-wing width to pad from.
    const refPrice = opp.spotPrice || opp.futuresPrice || k1;

    const cost = opp.legList.reduce((s, leg) => {
      const sign = leg.side === 'BUY' ? 1 : -1;
      return s + sign * (Number(leg.price) || 0) * (Number(leg.qty) || 1);
    }, 0);
    const width = k2 > k1 ? k2 - k1 : Math.max(refPrice * 0.03, 1);
    const lo = k1 - Math.max(width, 1) * 0.4, hi = k2 + Math.max(width, 1) * 0.4;
    const steps = 150;
    const points = [];
    let minY = 0, maxY = 0;
    for (let i = 0; i <= steps; i++) {
      const x = lo + (hi - lo) * i / steps;
      const payoff = opp.legList.reduce((sum, leg) => {
        const optType = String(leg.optionType).toUpperCase();
        const sign = leg.side === 'BUY' ? 1 : -1;
        if (optType === 'FUT') return sum + sign * x * (Number(leg.qty) || 1);
        const strike = Number(leg.strike);
        const isPe = optType === 'PE';
        const intrinsic = isPe ? Math.max(strike - x, 0) : Math.max(x - strike, 0);
        return sum + sign * intrinsic * (Number(leg.qty) || 1);
      }, 0);
      const pnl = payoff - cost;
      points.push({ x, y: pnl });
      minY = Math.min(minY, pnl); maxY = Math.max(maxY, pnl);
    }
    // Breakeven(s): where the payoff line crosses zero, found by scanning for sign changes
    // and linearly interpolating -- matches AlgoTest's "Breakeven" figures for a direct check.
    const breakevens = [];
    for (let i = 1; i < points.length; i++) {
      const a = points[i - 1], b = points[i];
      if ((a.y < 0 && b.y >= 0) || (a.y >= 0 && b.y < 0)) {
        const t = a.y === b.y ? 0 : (0 - a.y) / (b.y - a.y);
        breakevens.push(a.x + t * (b.x - a.x));
      }
    }
    return { points, lo, hi, minY: Math.min(minY, 0), maxY: Math.max(maxY, 0), k1, k2, cost, breakevens };
  }, [hasLegs, opp.legList]);

  if (!chart) return null;

  const CHART_W = 600, CHART_H = 190, PAD_TOP = 20, PAD_BOTTOM = 26, PAD_LEFT = 54;
  const plotH = CHART_H - PAD_TOP - PAD_BOTTOM;
  const plotW = CHART_W - PAD_LEFT;
  const xToPx = (x) => PAD_LEFT + ((x - chart.lo) / (chart.hi - chart.lo)) * plotW;
  const yToPx = (y) => PAD_TOP + plotH - ((y - chart.minY) / ((chart.maxY - chart.minY) || 1)) * plotH;
  const pxToX = (px) => chart.lo + ((px - PAD_LEFT) / plotW) * (chart.hi - chart.lo);
  const zeroPx = yToPx(0);
  const spot = opp.spotPrice || chart.k2;
  const maxProfitPerShare = chart.maxY;
  const maxLossPerShare = chart.minY;
  const yTicks = [chart.minY, chart.minY + (chart.maxY - chart.minY) * 0.5, chart.maxY];
  const areaPath = (() => {
    const pts = chart.points.map(p => `${xToPx(p.x)},${yToPx(p.y)}`).join(' L ');
    return `M ${xToPx(chart.points[0].x)},${zeroPx} L ${pts} L ${xToPx(chart.points[chart.points.length - 1].x)},${zeroPx} Z`;
  })();

  const handleMove = (e) => {
    if (!chartRef.current) return;
    const rect = chartRef.current.getBoundingClientRect();
    const px = ((e.clientX - rect.left) / rect.width) * CHART_W;
    const priceAtCursor = pxToX(px);
    let nearest = 0, best = Infinity;
    chart.points.forEach((p, i) => { const d = Math.abs(p.x - priceAtCursor); if (d < best) { best = d; nearest = i; } });
    const pt = chart.points[nearest];
    setHover({ px: xToPx(pt.x), py: yToPx(pt.y), price: pt.x, pnl: pt.y });
  };

  const totalMax = maxProfitPerShare * lotSize;
  const totalMin = maxLossPerShare * lotSize;

  return (
    <div className="bg-white rounded-xl border border-slate-200 p-3">
      <div className="text-[10px] font-black text-slate-500 uppercase mb-2">
        Payoff at Expiry — guaranteed by the convexity bound, not a probability estimate
      </div>
      <div className="grid grid-cols-3 gap-2 mb-3">
        <div className="bg-emerald-50 border border-emerald-200 rounded-lg px-2 py-1.5">
          <div className="text-[9px] font-bold text-emerald-700 uppercase">Max Profit</div>
          <div className="text-sm font-black text-emerald-700">₹{Math.round(totalMax).toLocaleString('en-IN')}</div>
          <div className="text-[9px] text-emerald-600">₹{maxProfitPerShare.toFixed(2)}/share × {lotSize}</div>
        </div>
        <div className="bg-red-50 border border-red-200 rounded-lg px-2 py-1.5">
          <div className="text-[9px] font-bold text-red-700 uppercase">Max Loss</div>
          <div className="text-sm font-black text-red-700">₹{Math.round(totalMin).toLocaleString('en-IN')}</div>
          <div className="text-[9px] text-red-600">₹{maxLossPerShare.toFixed(2)}/share × {lotSize}</div>
        </div>
        <div className="bg-indigo-50 border border-indigo-200 rounded-lg px-2 py-1.5">
          <div className="text-[9px] font-bold text-indigo-700 uppercase">Breakeven</div>
          <div className="text-sm font-black text-indigo-700">
            {chart.breakevens.length > 0 ? chart.breakevens.map(b => Math.round(b).toLocaleString('en-IN')).join(' / ') : '--'}
          </div>
          <div className="text-[9px] text-indigo-600">cost: ₹{chart.cost.toFixed(2)}/share</div>
        </div>
      </div>
      <div className="relative overflow-x-auto">
        <svg ref={chartRef} viewBox={`0 0 ${CHART_W} ${CHART_H}`} className="w-full h-[180px] cursor-crosshair"
          onMouseMove={handleMove} onMouseLeave={() => setHover(null)}>
          <defs>
            <linearGradient id="arbPayoffGrad" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#10b981" stopOpacity="0.35" />
              <stop offset="100%" stopColor="#10b981" stopOpacity="0.02" />
            </linearGradient>
          </defs>
          {yTicks.map((ty, i) => (
            <g key={i}>
              <line x1={PAD_LEFT} y1={yToPx(ty)} x2={CHART_W} y2={yToPx(ty)} stroke="#e2e8f0" strokeWidth="1" />
              <text x={PAD_LEFT - 6} y={yToPx(ty) + 3} textAnchor="end" fontSize="8" fill="#94a3b8">
                ₹{Math.round(ty * lotSize).toLocaleString('en-IN')}
              </text>
            </g>
          ))}
          <path d={areaPath} fill="url(#arbPayoffGrad)" />
          <line x1={PAD_LEFT} y1={zeroPx} x2={CHART_W} y2={zeroPx} stroke="#94a3b8" strokeWidth="1" strokeDasharray="4,4" />
          <line x1={xToPx(spot)} y1={PAD_TOP} x2={xToPx(spot)} y2={CHART_H - PAD_BOTTOM} stroke="#6366f1" strokeWidth="1.5" strokeDasharray="3,3" />
          <text x={xToPx(spot)} y={PAD_TOP - 6} textAnchor="middle" fontSize="9" fontWeight="700" fill="#6366f1">
            Spot {Math.round(spot).toLocaleString('en-IN')}
          </text>
          <polyline fill="none" stroke="#059669" strokeWidth="2.5" strokeLinejoin="round"
            points={chart.points.map(p => `${xToPx(p.x)},${yToPx(p.y)}`).join(' ')} />
          {hover && (
            <g>
              <line x1={hover.px} y1={PAD_TOP} x2={hover.px} y2={CHART_H - PAD_BOTTOM} stroke="#0f172a" strokeWidth="1" strokeDasharray="2,2" opacity="0.4" />
              <circle cx={hover.px} cy={hover.py} r="4" fill="#059669" stroke="white" strokeWidth="1.5" />
            </g>
          )}
        </svg>
      </div>
      <div className="flex items-center justify-between text-[10px] mt-1">
        <span className="text-slate-400">{Math.round(chart.lo).toLocaleString('en-IN')}</span>
        {hover ? (
          <span className="font-bold">
            @ {Math.round(hover.price).toLocaleString('en-IN')}: <span className={hover.pnl >= 0 ? 'text-emerald-600' : 'text-red-600'}>{hover.pnl >= 0 ? '+' : ''}₹{Math.round(hover.pnl * lotSize).toLocaleString('en-IN')} ({hover.pnl >= 0 ? '+' : ''}₹{hover.pnl.toFixed(2)}/share)</span>
          </span>
        ) : (
          <span className="text-slate-400">hover to inspect any settlement price</span>
        )}
        <span className="text-slate-400">{Math.round(chart.hi).toLocaleString('en-IN')}</span>
      </div>
    </div>
  );
}

function ButterflySpreadView({ handleExecuteInline, executionBroker }) {
  const [subTab, setSubTab] = useState('signals');
  const [historyMode, setHistoryMode] = useState('arbitrage');
  const [underlying, setUnderlying] = useState('ALL');
  const [minEdge, setMinEdge] = useState(0);
  const [customEdge, setCustomEdge] = useState('');
  const [expandedId, setExpandedId] = useState(null);
  const [sortCol, setSortCol] = useState('scanTime');
  const [sortAsc, setSortAsc] = useState(false);
  const [histPage, setHistPage] = useState(0);
  const PAGE_SIZE = 200;

  const { data: liveData, isLoading } = useQuery({
    queryKey: ['butterfly-spread-scan', underlying],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/butterfly-spread/scan', { params: { underlying } });
      return res.data;
    },
    refetchInterval: 30000
  });

  const histDate = new Date(); histDate.setDate(histDate.getDate() - 6);
  const today2 = histDate.toISOString().split('T')[0];
  const todayEnd = new Date().toISOString().split('T')[0];

  const { data: historyData } = useQuery({
    queryKey: ['butterfly-history', underlying, histPage],
    queryFn: async () => {
      const params = { page: histPage, size: PAGE_SIZE, strategyType: 'BUTTERFLY_SPREAD', startDate: today2, endDate: todayEnd };
      if (underlying !== 'ALL') params.underlying = underlying;
      const res = await client.get('/option-arbitrage/history', { params });
      return res.data;
    }
  });

  const liveOpps = (liveData?.opportunities || []).filter(o => (o.edgeAfterCosts || 0) >= minEdge);
  const historyOpps = (historyData?.items || []).filter(i => (i.edgeAfterCosts || 0) >= minEdge);

  const allOpps = [...liveOpps];
  const liveIds = new Set(liveOpps.map(o => o.id));
  historyOpps.forEach(h => { if (!liveIds.has(h.id)) allOpps.push(h); });

  const filteredByUnderlying = underlying === 'ALL' ? allOpps : allOpps.filter(o => o.underlying === underlying);
  const totalHistory = historyData?.totalElements || 0;
  const totalHistoryPages = historyData?.totalPages || 0;

  const bfAllIds = allOpps.filter(o => o.id).map(o => o.id);
  const { data: bfLivePnlRes } = useQuery({
    queryKey: ['butterfly-pnl', bfAllIds.join(',')],
    queryFn: async () => {
      if (bfAllIds.length === 0) return { pnlMap: {}, statusMap: {} };
      const idsParam = bfAllIds.slice(0, 500).join(',');
      const res = await client.get('/option-arbitrage/history/live-pnl', { params: { ids: idsParam } });
      return { pnlMap: res.data?.pnlMap || {}, statusMap: res.data?.statusMap || {} };
    },
    refetchInterval: 10000,
    enabled: bfAllIds.length > 0
  });
  const bfPnlMap = bfLivePnlRes?.pnlMap || {};
  const bfStatusMap = bfLivePnlRes?.statusMap || {};

  const sortedOpps = [...filteredByUnderlying].sort((a, b) => {
    let va, vb;
    if (sortCol === 'scanTime') { va = a.scanTime || a.entryTime || ''; vb = b.scanTime || b.entryTime || ''; }
    else if (sortCol === 'underlying') { va = a.underlying || ''; vb = b.underlying || ''; }
    else if (sortCol === 'edge') { va = a.edgeAfterCosts || 0; vb = b.edgeAfterCosts || 0; }
    else if (sortCol === 'pnl') { va = a.pnlAfterCosts || 0; vb = b.pnlAfterCosts || 0; }
    else { va = a[sortCol] || ''; vb = b[sortCol] || ''; }
    if (typeof va === 'string') return sortAsc ? va.localeCompare(vb) : vb.localeCompare(va);
    return sortAsc ? va - vb : vb - va;
  });
  const toggleSort = (col) => { if (sortCol === col) setSortAsc(!sortAsc); else { setSortCol(col); setSortAsc(false); } };
  const sortIcon = (col) => sortCol === col ? (sortAsc ? ' ▲' : ' ▼') : ' ↕';

  return (
    <div className="space-y-4 w-full">
      <AutoRollPendingPanel handleExecuteInline={handleExecuteInline} />
      <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl w-fit">
        <button onClick={() => setSubTab('signals')}
          className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${subTab === 'signals' ? 'bg-fuchsia-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
          Arbitrage Signals
        </button>
        <button onClick={() => setSubTab('candidates')}
          className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${subTab === 'candidates' ? 'bg-amber-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
          🔍 Candidates (Not Arbitrage)
        </button>
        <button onClick={() => setSubTab('autotrade')}
          className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${subTab === 'autotrade' ? 'bg-indigo-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
          ⚡ Auto-Trade
        </button>
        <button onClick={() => setSubTab('history')}
          className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${subTab === 'history' ? 'bg-emerald-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
          📊 History
        </button>
      </div>

      {subTab === 'history' ? (
        <div className="space-y-3">
          <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl w-fit">
            <button onClick={() => setHistoryMode('arbitrage')}
              className={`px-3 py-1 rounded-lg text-xs font-bold transition ${historyMode === 'arbitrage' ? 'bg-fuchsia-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              Arbitrage Signals
            </button>
            <button onClick={() => setHistoryMode('candidates')}
              className={`px-3 py-1 rounded-lg text-xs font-bold transition ${historyMode === 'candidates' ? 'bg-amber-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              🔍 Candidates
            </button>
          </div>
          {historyMode === 'candidates' ? (
            <CandidateHistoryPanel strategyType="BUTTERFLY_SPREAD" label="Butterfly Spread" />
          ) : (
            <HistoryView lockedStrategy="BUTTERFLY" handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} />
          )}
        </div>
      ) : subTab === 'autotrade' ? (
        <div className="space-y-4">
          <StrategyAutoTradePanel prefix="butterfly" label="Butterfly Spread" accent="indigo" />
          <AutoRollSettingsPanel />
        </div>
      ) : subTab === 'candidates' ? (
        <ButterflyCandidatesPanel handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} />
      ) : (
      <>
      <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className="text-base font-bold text-slate-800">Butterfly Spread No-Arbitrage Bound Scanner</h2>
          <p className="text-xs text-slate-500">{sortedOpps.length} signals shown{totalHistory > 0 ? ` of ${totalHistory.toLocaleString('en-IN')} total today` : ''} — model-free convexity bound (0 ≤ price ≤ width), no interest-rate or futures assumption</p>
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          {['ALL', 'NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'].map(u => (
            <button key={u} onClick={() => { setUnderlying(u); setHistPage(0); }}
              className={`px-3 py-1 rounded-lg text-xs font-bold transition ${underlying === u ? 'bg-fuchsia-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              {u}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          <span className="text-[10px] font-bold text-slate-500 px-1">EDGE ≥</span>
          {[0, 100, 300].map(e => (
            <button key={e} onClick={() => { setMinEdge(e); setCustomEdge(''); setHistPage(0); }}
              className={`px-2 py-1 rounded-lg text-[10px] font-bold transition ${minEdge === e && !customEdge ? 'bg-emerald-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              ₹{e}
            </button>
          ))}
          <input type="number" placeholder="Custom" value={customEdge} onChange={e => { setCustomEdge(e.target.value); setMinEdge(Number(e.target.value) || 0); setHistPage(0); }}
            className="w-16 px-1.5 py-1 rounded-lg text-[10px] font-bold border border-slate-300 focus:outline-none focus:border-emerald-500" />
        </div>
      </div>

      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm w-full">
        {isLoading ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">Scanning Butterfly Spreads...</div>
        ) : sortedOpps.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">No butterfly spread bound violations for {underlying}</div>
        ) : (
          <div className="overflow-x-auto w-full">
            <table className="w-full text-[11px] text-left border-collapse">
              <thead className="bg-slate-50 border-b border-slate-200 font-bold text-slate-600 uppercase">
                <tr>
                  <th className="px-2 py-2 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('scanTime')}>Time{sortIcon('scanTime')}</th>
                  <th className="px-2 py-2 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('underlying')}>Symbol{sortIcon('underlying')}</th>
                  <th className="px-2 py-2">Strikes</th>
                  <th className="px-2 py-2">Action</th>
                  <th className="px-2 py-2 text-right text-emerald-600 font-bold cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('edge')}>Net Edge{sortIcon('edge')}</th>
                  <th className="px-2 py-2 text-center">Status</th>
                  <th className="px-2 py-2 text-right cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('pnl')}>P&amp;L{sortIcon('pnl')}</th>
                  <th className="px-2 py-2 text-center">Exit</th>
                  <th className="px-2 py-2 text-center">Trade</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {sortedOpps.map((opp, idx) => {
                  const isExp = expandedId === (opp.id || idx);
                  const posStatus = opp.id && bfStatusMap[String(opp.id)] ? String(bfStatusMap[String(opp.id)]).toUpperCase() : null;
                  const statusStr = posStatus || String(opp.status || 'RUNNING').toUpperCase();
                  const isLive = statusStr === 'RUNNING' || statusStr === 'OPEN' || statusStr === 'DETECTED' || statusStr === 'EXECUTING';
                  const isExited = statusStr === 'EXITED' || statusStr === 'CLOSED';
                  const livePnl = opp.id && bfPnlMap[String(opp.id)] != null ? Number(bfPnlMap[String(opp.id)]) : null;
                  const pnlDisplay = livePnl != null ? livePnl : (opp.pnlAfterCosts != null ? Number(opp.pnlAfterCosts) : null);
                  const strikeMatch = opp.action ? opp.action.match(/\((\d+)\/(\d+)\/(\d+)\)/) : null;
                  const strike1 = strikeMatch ? strikeMatch[1] : (opp.strike || 0);
                  const strike2 = strikeMatch ? strikeMatch[2] : '';
                  const strike3 = strikeMatch ? strikeMatch[3] : '';
                  const edgeVal = Number(opp.edgeAfterCosts || 0);

                  const timeStr = fmtTime(opp.scanTime || opp.entryTime);
                  const exitStr = isExited ? fmtTime(opp.exitTime) : '';

                  return (
                    <React.Fragment key={opp.id || idx}>
                      <tr onClick={() => setExpandedId(isExp ? null : (opp.id || idx))}
                        className={`transition cursor-pointer ${isExp ? 'bg-fuchsia-50/70 border-l-4 border-fuchsia-600' : 'hover:bg-slate-50'}`}>
                        <td className="px-2 py-1.5 font-mono text-[10px] text-slate-500">{timeStr}</td>
                        <td className="px-2 py-1.5 font-bold text-slate-800">{opp.underlying}</td>
                        <td className="px-2 py-1.5 font-bold text-slate-700">{strike1}/{strike2}/{strike3}</td>
                        <td className="px-2 py-1.5 font-bold text-fuchsia-700 truncate max-w-[160px]">{opp.action}</td>
                        <td className="px-2 py-1.5 text-right font-mono font-bold text-emerald-600">
                          +₹{Math.round(edgeVal).toLocaleString('en-IN')}
                        </td>
                        <td className="px-2 py-1.5 text-center">
                          <span className={`px-1.5 py-0.2 rounded-full text-[9px] font-bold border ${
                            isLive ? 'bg-emerald-100 text-emerald-800 border-emerald-300' :
                            isExited ? 'bg-slate-200 text-slate-600 border-slate-300' :
                            statusStr === 'EXPIRED' ? 'bg-amber-100 text-amber-800 border-amber-300' :
                            'bg-blue-100 text-blue-800 border-blue-300'
                          }`}>
                            {isLive ? '🟢 RUNNING' : isExited ? '⏹ EXITED' : statusStr === 'EXPIRED' ? '⏰ EXPIRED' : statusStr}
                          </span>
                          {opp.existingOpenPosition && (
                            <span className="block mt-1 px-1.5 py-0.2 rounded-full text-[8px] font-bold border bg-amber-100 text-amber-800 border-amber-300" title={`You already hold an OPEN ${opp.existingPositionBroker || 'PAPER'} position for this signal`}>
                              📌 Already holding
                            </span>
                          )}
                        </td>
                        <td className="px-2 py-1.5 text-right font-mono font-bold">
                          {pnlDisplay != null && !isNaN(pnlDisplay)
                            ? <span className={pnlDisplay >= 0 ? 'text-emerald-600' : 'text-red-600'}>₹{Math.round(pnlDisplay).toLocaleString('en-IN')}</span>
                            : <span className="text-slate-400">--</span>}
                        </td>
                        <td className="px-2 py-1.5 text-center font-mono text-[10px] text-slate-500">{exitStr}</td>
                        <td className="px-2 py-1.5 text-center">
                          <button onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp); }}
                            className="px-2 py-0.5 bg-fuchsia-600 text-white text-[10px] font-bold rounded shadow-sm">
                            ⚡ Trade
                          </button>
                        </td>
                      </tr>
                      {isExp && (
                        <tr className="bg-fuchsia-50/40 border-b border-fuchsia-100">
                          <td colSpan={9} className="p-3">
                            <div className="bg-white rounded-xl p-3 border border-fuchsia-200 shadow-md space-y-2">
                              <span className="font-bold text-slate-800 text-xs uppercase block">Butterfly Spread Breakdown:</span>
                              {opp.existingOpenPosition && (
                                <p className="text-[11px] font-bold text-amber-700 bg-amber-50 border border-amber-200 rounded-lg px-2 py-1">
                                  📌 You already have an OPEN {opp.existingPositionBroker || 'PAPER'} position for this exact signal. Trading again will open an additional position, not add to or replace the existing one.
                                </p>
                              )}
                              <p className="text-xs font-mono font-bold text-slate-800 bg-slate-50 p-2 rounded-lg border">{opp.legs || '—'}</p>
                              <p className="text-[10px] text-slate-500">{opp.description}</p>
                              <ArbitrageSignalPayoffChart opp={opp} />
                              <div className="flex justify-end pt-1">
                                <button onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp); }} className="px-3 py-1 bg-fuchsia-600 text-white rounded-lg text-xs font-bold shadow-md">
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
        {totalHistoryPages > 1 && (
          <div className="flex items-center justify-between px-4 py-3 border-t border-slate-200 bg-slate-50">
            <span className="text-xs text-slate-500">Page {histPage + 1} of {totalHistoryPages} ({totalHistory.toLocaleString('en-IN')} signals today)</span>
            <div className="flex gap-1">
              <button disabled={histPage === 0} onClick={() => setHistPage(0)} className="px-2 py-1 bg-white border rounded text-xs font-bold disabled:opacity-40">First</button>
              <button disabled={histPage === 0} onClick={() => setHistPage(histPage - 1)} className="px-2 py-1 bg-white border rounded text-xs font-bold disabled:opacity-40">Prev</button>
              <button disabled={histPage >= totalHistoryPages - 1} onClick={() => setHistPage(histPage + 1)} className="px-2 py-1 bg-white border rounded text-xs font-bold disabled:opacity-40">Next</button>
              <button disabled={histPage >= totalHistoryPages - 1} onClick={() => setHistPage(totalHistoryPages - 1)} className="px-2 py-1 bg-white border rounded text-xs font-bold disabled:opacity-40">Last</button>
            </div>
          </div>
        )}
      </div>
      </>
      )}
    </div>
  );
}

/* Butterfly "cheap fly" candidate discovery panel -- NOT arbitrage, pure evaluation tool.
   Lets the user select several candidates and see the combined payoff curve across a
   settlement-price range, so the shape (capped loss, where it's profitable) is visible
   before deciding to trade anything manually. No execution wiring beyond the existing
   per-row manual Trade button. */
function ButterflyCandidatesPanel({ handleExecuteInline, executionBroker }) {
  const [underlying, setUnderlying] = useState('ALL');
  const [maxCostRatio, setMaxCostRatio] = useState(0.35);
  const [selected, setSelected] = useState({});
  const [hover, setHover] = useState(null);
  const [autoSelected, setAutoSelected] = useState(false);
  const [sortCol, setSortCol] = useState('pop');
  const [sortAsc, setSortAsc] = useState(false);
  const chartRef = useRef(null);

  const { data, isLoading } = useQuery({
    queryKey: ['butterfly-candidates', underlying, maxCostRatio],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/butterfly-spread/candidates', { params: { underlying, maxCostRatio } });
      return res.data;
    },
    refetchInterval: 30000
  });

  // Real exit rules that would apply if this were traded -- surfaced here so "what happens
  // if it moves against me" has an honest answer instead of a guess.
  const { data: execSettings } = useQuery({
    queryKey: ['autoExecSettingsForCandidates'],
    queryFn: async () => (await client.get('/option-arbitrage/auto-execute/settings')).data,
    refetchInterval: 60000
  });

  // Backend already sorts by model POP descending -- the safest-by-this-metric candidate
  // is always first. Auto-select it once per dataset load so the payoff/POP panel is
  // populated immediately instead of requiring a manual click.
  const candidates = data?.candidates || [];
  const rowKey = (c) => `${c.underlying}-${c.optionType}-${c.k1}-${c.k2}-${c.k3}`;

  useEffect(() => {
    if (!autoSelected && candidates.length > 0) {
      setSelected({ [rowKey(candidates[0])]: true });
      setAutoSelected(true);
    }
  }, [candidates, autoSelected]);

  const selectedCandidates = candidates.filter((c) => selected[rowKey(c)]);
  const toggle = (key) => setSelected(prev => ({ ...prev, [key]: !prev[key] }));
  const toggleSort = (col) => { if (sortCol === col) setSortAsc(!sortAsc); else { setSortCol(col); setSortAsc(col === 'strikes' || col === 'underlying'); } };
  const sortIcon = (col) => sortCol === col ? (sortAsc ? ' ▲' : ' ▼') : ' ↕';
  const sortedCandidates = [...candidates].sort((a, b) => {
    let va = a[sortCol], vb = b[sortCol];
    if (sortCol === 'strikes' || sortCol === 'underlying' || sortCol === 'optionType') {
      va = va || ''; vb = vb || '';
      return sortAsc ? String(va).localeCompare(String(vb)) : String(vb).localeCompare(String(va));
    }
    va = Number(va) || 0; vb = Number(vb) || 0;
    return sortAsc ? va - vb : vb - va;
  });

  // Combined payoff across a settlement-price range spanning all selected candidates --
  // both the "At Expiry" curve (final payoff) and a "Today" curve (Black-Scholes
  // theoretical value if spot moved there right now, same remaining time/IV -- not a
  // decay simulation), matching the two-curve view standard options tools show.
  const payoffChart = useMemo(() => {
    if (selectedCandidates.length === 0) return null;
    const spot = selectedCandidates[0].spotPrice || selectedCandidates.reduce((s, c) => s + c.spotPrice, 0) / selectedCandidates.length;
    const lo = Math.min(...selectedCandidates.map(c => c.k1)) - 300;
    const hi = Math.max(...selectedCandidates.map(c => c.k3)) + 300;
    const steps = 200;
    const stepSize = (hi - lo) / steps;
    const points = [];
    const todayPoints = [];
    let minY = 0, maxY = 0;
    for (let i = 0; i <= steps; i++) {
      const x = lo + i * stepSize;
      let expiryTotal = 0, todayTotal = 0;
      for (const c of selectedCandidates) {
        const lotSize = c.lotSize || (c.maxLoss > 0 && c.costPerLot > 0 ? Math.round(c.maxLoss / c.costPerLot) : 25);
        const T = Math.max(c.daysToExpiry, 0.5) / 365;
        const r = c.riskFreeRate || 0.065;
        const sigma = (c.impliedVol || 20) / 100;
        const priceFn = c.optionType === 'CE' ? bsCallPrice : bsPutPrice;

        let expiryPayoff;
        if (c.optionType === 'CE') {
          expiryPayoff = Math.max(x - c.k1, 0) - 2 * Math.max(x - c.k2, 0) + Math.max(x - c.k3, 0);
        } else {
          expiryPayoff = Math.max(c.k1 - x, 0) - 2 * Math.max(c.k2 - x, 0) + Math.max(c.k3 - x, 0);
        }
        expiryTotal += (expiryPayoff - c.costPerLot) * lotSize;

        const todayValue = priceFn(x, c.k1, T, r, sigma) - 2 * priceFn(x, c.k2, T, r, sigma) + priceFn(x, c.k3, T, r, sigma);
        todayTotal += (todayValue - c.costPerLot) * lotSize;
      }
      points.push({ x, y: expiryTotal });
      todayPoints.push({ x, y: todayTotal });
      minY = Math.min(minY, expiryTotal, todayTotal);
      maxY = Math.max(maxY, expiryTotal, todayTotal);
    }
    return { points, todayPoints, spot, lo, hi, minY: Math.min(minY, 0), maxY: Math.max(maxY, 0) };
  }, [selectedCandidates]);

  const totalMaxLoss = selectedCandidates.reduce((s, c) => s + c.maxLoss, 0);
  const totalMaxProfit = selectedCandidates.reduce((s, c) => s + c.maxProfit, 0);
  const totalMargin = selectedCandidates.reduce((s, c) => s + (c.marginEstimate || c.maxLoss), 0);
  const totalCharges = selectedCandidates.reduce((s, c) => s + (c.entryCosts || 0), 0);
  const avgPop = selectedCandidates.length > 0
    ? selectedCandidates.reduce((s, c) => s + c.pop, 0) / selectedCandidates.length : null;
  const breakevenGap = (c) => {
    if (c.breakevenLower == null || c.breakevenUpper == null || c.spotPrice == null) return null;
    return Math.min(c.spotPrice - c.breakevenLower, c.breakevenUpper - c.spotPrice);
  };
  const soloBreakevenGap = selectedCandidates.length === 1 ? breakevenGap(selectedCandidates[0]) : null;

  const CHART_W = 700, CHART_H = 260, PAD_TOP = 24, PAD_BOTTOM = 34;
  const plotH = CHART_H - PAD_TOP - PAD_BOTTOM;
  const xToPx = (x) => payoffChart ? ((x - payoffChart.lo) / (payoffChart.hi - payoffChart.lo)) * CHART_W : 0;
  const yToPx = (y) => payoffChart
    ? PAD_TOP + plotH - ((y - payoffChart.minY) / (payoffChart.maxY - payoffChart.minY || 1)) * plotH
    : 0;
  const pxToX = (px) => payoffChart ? payoffChart.lo + (px / CHART_W) * (payoffChart.hi - payoffChart.lo) : 0;

  const handleChartMove = (e) => {
    if (!payoffChart || !chartRef.current) return;
    const rect = chartRef.current.getBoundingClientRect();
    const relX = (e.clientX - rect.left) / rect.width;
    const px = relX * CHART_W;
    const priceAtCursor = pxToX(px);
    let nearestIdx = 0, bestDist = Infinity;
    payoffChart.points.forEach((p, i) => {
      const d = Math.abs(p.x - priceAtCursor);
      if (d < bestDist) { bestDist = d; nearestIdx = i; }
    });
    const expiry = payoffChart.points[nearestIdx];
    const today = payoffChart.todayPoints[nearestIdx];
    setHover({ px: xToPx(expiry.x), pyExpiry: yToPx(expiry.y), pyToday: yToPx(today.y), price: expiry.x, pnlExpiry: expiry.y, pnlToday: today.y });
  };

  const zeroPx = payoffChart ? yToPx(0) : 0;
  const areaPath = payoffChart ? (() => {
    const pts = payoffChart.points.map(p => `${xToPx(p.x)},${yToPx(p.y)}`).join(' L ');
    return `M ${xToPx(payoffChart.points[0].x)},${zeroPx} L ${pts} L ${xToPx(payoffChart.points[payoffChart.points.length - 1].x)},${zeroPx} Z`;
  })() : '';

  return (
    <div className="space-y-4 w-full">
      <div className="bg-amber-50 border border-amber-300 rounded-2xl p-4 text-xs text-amber-900">
        <p className="font-bold mb-1">⚠️ Not arbitrage — evaluation tool only</p>
        <p>These are butterflies priced cheap relative to their width (small debit vs. potential payoff if NIFTY pins near the center strike) — a directional bet on low movement, not a guaranteed-profit position. POP (probability of profit) is a Black-Scholes model estimate from current implied volatility, not a backtested or historical win rate. Move your mouse over the chart to see P&amp;L at any settlement price.</p>
      </div>

      <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className="text-base font-bold text-slate-800">Cheap Butterfly Candidates</h2>
          <p className="text-xs text-slate-500">{candidates.length} candidates — cost ≤ {Math.round(maxCostRatio * 100)}% of width, sorted by model POP (highest first)</p>
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          {['ALL', 'NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'].map(u => (
            <button key={u} onClick={() => { setUnderlying(u); setAutoSelected(false); }}
              className={`px-3 py-1 rounded-lg text-xs font-bold transition ${underlying === u ? 'bg-amber-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              {u}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          <span className="text-[10px] font-bold text-slate-500 px-1">MAX COST/WIDTH</span>
          {[0.2, 0.35, 0.5].map(r => (
            <button key={r} onClick={() => { setMaxCostRatio(r); setAutoSelected(false); }}
              className={`px-2 py-1 rounded-lg text-[10px] font-bold transition ${maxCostRatio === r ? 'bg-emerald-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              {Math.round(r * 100)}%
            </button>
          ))}
        </div>
        {selectedCandidates.length > 0 && (
          <button onClick={() => setSelected({})}
            className="px-3 py-1.5 rounded-lg text-xs font-bold bg-red-50 text-red-600 border border-red-200 hover:bg-red-100 transition">
            ✕ Clear Selection ({selectedCandidates.length})
          </button>
        )}
      </div>

      {selectedCandidates.length > 0 && (
        <div className="bg-gradient-to-br from-white via-amber-50/30 to-indigo-50/30 rounded-2xl border-2 border-amber-200 shadow-lg p-5 space-y-5">
          {/* Stat strip */}
          <div className="grid grid-cols-2 md:grid-cols-7 gap-3">
            <div className="bg-white rounded-xl border border-slate-200 p-2.5 text-center">
              <div className="text-[9px] font-bold text-slate-400 uppercase">POP</div>
              <div className={`text-lg font-black ${avgPop >= 60 ? 'text-emerald-600' : avgPop >= 40 ? 'text-amber-600' : 'text-slate-500'}`}>{avgPop?.toFixed(1)}%</div>
            </div>
            <div className="bg-white rounded-xl border border-slate-200 p-2.5 text-center">
              <div className="text-[9px] font-bold text-slate-400 uppercase">Max Loss</div>
              <div className="text-lg font-black text-red-600">₹{Math.round(totalMaxLoss).toLocaleString('en-IN')}</div>
            </div>
            <div className="bg-white rounded-xl border border-slate-200 p-2.5 text-center">
              <div className="text-[9px] font-bold text-slate-400 uppercase">Max Profit</div>
              <div className="text-lg font-black text-emerald-600">₹{Math.round(totalMaxProfit).toLocaleString('en-IN')}</div>
            </div>
            <div className="bg-white rounded-xl border border-slate-200 p-2.5 text-center">
              <div className="text-[9px] font-bold text-slate-400 uppercase">Risk:Reward</div>
              <div className="text-lg font-black text-indigo-600">{totalMaxLoss > 0 ? (totalMaxProfit / totalMaxLoss).toFixed(1) : '0'}x</div>
            </div>
            <div className="bg-white rounded-xl border border-slate-200 p-2.5 text-center">
              <div className="text-[9px] font-bold text-slate-400 uppercase">Capital Required*</div>
              <div className="text-lg font-black text-slate-700">₹{Math.round(totalMargin).toLocaleString('en-IN')}</div>
            </div>
            <div className="bg-white rounded-xl border border-slate-200 p-2.5 text-center">
              <div className="text-[9px] font-bold text-slate-400 uppercase">Charges</div>
              <div className="text-lg font-black text-slate-700">₹{Math.round(totalCharges).toLocaleString('en-IN')}</div>
            </div>
            <div className="bg-white rounded-xl border border-slate-200 p-2.5 text-center">
              <div className="text-[9px] font-bold text-slate-400 uppercase">Breakeven Gap</div>
              <div className={`text-lg font-black ${soloBreakevenGap == null ? 'text-slate-300' : soloBreakevenGap < 0 ? 'text-red-600' : soloBreakevenGap < 50 ? 'text-amber-600' : 'text-emerald-600'}`}>
                {soloBreakevenGap == null ? '—' : soloBreakevenGap < 0 ? `⚠️ -${Math.round(Math.abs(soloBreakevenGap))}` : `±${Math.round(soloBreakevenGap)}`}
              </div>
            </div>
          </div>
          <p className="text-[9px] text-slate-400 -mt-3">*Margin is a conservative estimate (worst-case cash outflow) — actual broker SPAN+exposure margin may differ; verify with your broker before trading.</p>
          {soloBreakevenGap != null && soloBreakevenGap < 0 && (
            <p className="text-[11px] font-bold text-red-600 bg-red-50 border border-red-200 rounded-lg px-3 py-1.5 -mt-1">⚠️ Spot has already moved outside this candidate's profit zone by {Math.round(Math.abs(soloBreakevenGap))} points — this is now a probable loss unless price reverses before expiry.</p>
          )}

          {payoffChart && (
            <div>
              <div className="flex items-center gap-4 mb-1 px-1">
                <span className="flex items-center gap-1.5 text-[10px] font-bold text-amber-700"><span className="w-3 h-0.5 bg-amber-600 inline-block rounded" /> At Expiry</span>
                <span className="flex items-center gap-1.5 text-[10px] font-bold text-blue-600"><span className="w-3 h-0.5 bg-blue-500 inline-block rounded" /> Today (Black-Scholes est.)</span>
              </div>
              <div className="relative overflow-x-auto bg-white rounded-xl border border-slate-100 p-2">
                <svg ref={chartRef} viewBox={`0 0 ${CHART_W} ${CHART_H}`} className="w-full h-[260px] cursor-crosshair"
                  onMouseMove={handleChartMove} onMouseLeave={() => setHover(null)}>
                  <defs>
                    <linearGradient id="profitGrad" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor="#10b981" stopOpacity="0.35" />
                      <stop offset="100%" stopColor="#10b981" stopOpacity="0.02" />
                    </linearGradient>
                    <linearGradient id="lossGrad" x1="0" y1="1" x2="0" y2="0">
                      <stop offset="0%" stopColor="#ef4444" stopOpacity="0.3" />
                      <stop offset="100%" stopColor="#ef4444" stopOpacity="0.02" />
                    </linearGradient>
                  </defs>
                  <clipPath id="aboveZero"><rect x="0" y="0" width={CHART_W} height={zeroPx} /></clipPath>
                  <clipPath id="belowZero"><rect x="0" y={zeroPx} width={CHART_W} height={CHART_H - zeroPx} /></clipPath>
                  <path d={areaPath} fill="url(#profitGrad)" clipPath="url(#aboveZero)" />
                  <path d={areaPath} fill="url(#lossGrad)" clipPath="url(#belowZero)" />

                  <line x1="0" y1={zeroPx} x2={CHART_W} y2={zeroPx} stroke="#94a3b8" strokeWidth="1" strokeDasharray="4,4" />
                  <line x1={xToPx(payoffChart.spot)} y1={PAD_TOP} x2={xToPx(payoffChart.spot)} y2={CHART_H - PAD_BOTTOM}
                    stroke="#6366f1" strokeWidth="1.5" strokeDasharray="3,3" />
                  <text x={xToPx(payoffChart.spot)} y={PAD_TOP - 8} textAnchor="middle" fontSize="10" fontWeight="700" fill="#6366f1">
                    Spot {Math.round(payoffChart.spot).toLocaleString('en-IN')}
                  </text>

                  {/* Today curve (blue), behind the expiry curve */}
                  <polyline
                    fill="none" stroke="#3b82f6" strokeWidth="2" strokeLinejoin="round" opacity="0.85"
                    points={payoffChart.todayPoints.map(p => `${xToPx(p.x)},${yToPx(p.y)}`).join(' ')}
                  />
                  {/* Expiry curve (amber) */}
                  <polyline
                    fill="none" stroke="#d97706" strokeWidth="2.5" strokeLinejoin="round"
                    points={payoffChart.points.map(p => `${xToPx(p.x)},${yToPx(p.y)}`).join(' ')}
                  />

                  {hover && (
                    <g>
                      <line x1={hover.px} y1={PAD_TOP} x2={hover.px} y2={CHART_H - PAD_BOTTOM} stroke="#0f172a" strokeWidth="1" strokeDasharray="2,2" opacity="0.4" />
                      <circle cx={hover.px} cy={hover.pyToday} r="4" fill="#3b82f6" stroke="white" strokeWidth="1.5" />
                      <circle cx={hover.px} cy={hover.pyExpiry} r="4.5" fill={hover.pnlExpiry >= 0 ? '#10b981' : '#ef4444'} stroke="white" strokeWidth="1.5" />
                      {(() => {
                        const boxW = 150, boxH = 62;
                        const bx = Math.min(Math.max(hover.px - boxW / 2, 2), CHART_W - boxW - 2);
                        const anchorY = Math.min(hover.pyExpiry, hover.pyToday);
                        const by = anchorY > 90 ? anchorY - boxH - 10 : Math.max(hover.pyExpiry, hover.pyToday) + 14;
                        return (
                          <g>
                            <rect x={bx} y={by} width={boxW} height={boxH} rx="6" fill="#0f172a" opacity="0.94" />
                            <text x={bx + 8} y={by + 15} fontSize="10" fill="#cbd5e1">
                              @ {Math.round(hover.price).toLocaleString('en-IN')}
                            </text>
                            <text x={bx + 8} y={by + 32} fontSize="11" fontWeight="700" fill="#93c5fd">
                              Today: {hover.pnlToday >= 0 ? '+' : ''}₹{Math.round(hover.pnlToday).toLocaleString('en-IN')}
                            </text>
                            <text x={bx + 8} y={by + 49} fontSize="11" fontWeight="800" fill={hover.pnlExpiry >= 0 ? '#34d399' : '#f87171'}>
                              Expiry: {hover.pnlExpiry >= 0 ? '+' : ''}₹{Math.round(hover.pnlExpiry).toLocaleString('en-IN')}
                            </text>
                          </g>
                        );
                      })()}
                    </g>
                  )}

                  {selectedCandidates.length === 1 && (
                    <>
                      <line x1={xToPx(selectedCandidates[0].breakevenLower)} y1={PAD_TOP} x2={xToPx(selectedCandidates[0].breakevenLower)} y2={CHART_H - PAD_BOTTOM} stroke="#94a3b8" strokeWidth="1" strokeDasharray="2,3" opacity="0.6" />
                      <line x1={xToPx(selectedCandidates[0].breakevenUpper)} y1={PAD_TOP} x2={xToPx(selectedCandidates[0].breakevenUpper)} y2={CHART_H - PAD_BOTTOM} stroke="#94a3b8" strokeWidth="1" strokeDasharray="2,3" opacity="0.6" />
                    </>
                  )}
                </svg>
              </div>
              <div className="flex justify-between text-[10px] text-slate-500 px-1 mt-1">
                <span>{Math.round(payoffChart.lo).toLocaleString('en-IN')}</span>
                <span>{Math.round(payoffChart.hi).toLocaleString('en-IN')}</span>
              </div>
              <p className="text-[10px] text-slate-400 text-center mt-1">P&amp;L (₹) vs. NIFTY settlement price — hover to inspect any price point</p>
            </div>
          )}

          {/* Positions that will actually be taken */}
          <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
            <div className="px-3 py-2 bg-slate-50 border-b border-slate-200 text-[10px] font-black text-slate-600 uppercase">
              📋 Positions to be taken
            </div>
            <table className="w-full text-[11px]">
              <thead className="text-slate-400 text-[9px] uppercase">
                <tr>
                  <th className="px-3 py-1.5 text-left">Symbol</th>
                  <th className="px-3 py-1.5 text-left">Side</th>
                  <th className="px-3 py-1.5 text-right">Strike</th>
                  <th className="px-3 py-1.5 text-right">Qty (lots)</th>
                  <th className="px-3 py-1.5 text-right">Price</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {selectedCandidates.flatMap((c, ci) => (c.legList || []).map((leg, li) => (
                  <tr key={`${ci}-${li}`}>
                    <td className="px-3 py-1.5 font-bold text-slate-700">{c.underlying} {leg.optionType}</td>
                    <td className="px-3 py-1.5">
                      <span className={`px-1.5 py-0.5 rounded text-[9px] font-black ${leg.side === 'BUY' ? 'bg-emerald-100 text-emerald-700' : 'bg-red-100 text-red-700'}`}>{leg.side}</span>
                    </td>
                    <td className="px-3 py-1.5 text-right font-mono">{leg.strike}</td>
                    <td className="px-3 py-1.5 text-right font-mono">{leg.qty}</td>
                    <td className="px-3 py-1.5 text-right font-mono">₹{leg.price?.toFixed(2)}</td>
                  </tr>
                )))}
              </tbody>
            </table>
          </div>

          {/* Honest exit-rules panel */}
          <div className="bg-indigo-50 rounded-xl border border-indigo-200 p-3 text-[11px] text-indigo-900 space-y-1">
            <p className="font-black uppercase text-[10px] text-indigo-600">🛡️ If you trade this — what happens automatically</p>
            {execSettings ? (
              <>
                <p>
                  <strong>Auto-exit on target:</strong> {execSettings.autoExitEnabled ? `ON — squares off at ${execSettings.autoExitThresholdPct ?? 90}% of max profit` : 'OFF'}
                </p>
                <p>
                  <strong>Stop-loss:</strong> {execSettings.stopLossEnabled ? `ON — squares off at ${execSettings.stopLossPct ?? 50}% of max loss` : 'OFF'}
                </p>
                <p className="text-indigo-500">Change these in the Auto-Trade tab. Only applies if you actually execute the trade (paper or live) — this panel is not a live position.</p>
              </>
            ) : (
              <p className="text-indigo-400">Loading current settings…</p>
            )}
            <p className="text-red-600 font-bold pt-1">⚠️ No automatic strike-adjustment or rolling exists for these spreads. If price moves against the position, it holds until it hits the stop-loss, hits the target, or expires — nothing rebalances it for you.</p>
          </div>
        </div>
      )}

      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm w-full">
        {isLoading ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">Scanning for cheap butterfly candidates...</div>
        ) : data?.marketClosed ? (
          <div className="p-12 text-center text-sm font-semibold">
            <div className="text-3xl mb-2">🌙</div>
            <div className="text-slate-500">Market is closed</div>
            <div className="text-slate-400 text-xs font-normal mt-1">{data?.reason || 'NSE/NFO hours: Mon-Fri 09:15-15:30 IST'}</div>
          </div>
        ) : candidates.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">No candidates under {Math.round(maxCostRatio * 100)}% cost/width right now</div>
        ) : (
          <div className="overflow-x-auto w-full">
            <table className="w-full text-[11px] text-left border-collapse">
              <thead className="bg-slate-50 border-b border-slate-200 font-bold text-slate-600 uppercase">
                <tr>
                  <th className="px-2 py-2"></th>
                  <th className="px-2 py-2 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('underlying')}>Symbol{sortIcon('underlying')}</th>
                  <th className="px-2 py-2 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('strikes')}>Strikes{sortIcon('strikes')}</th>
                  <th className="px-2 py-2">Type</th>
                  <th className="px-2 py-2 text-right cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('costRatio')}>Cost/Width{sortIcon('costRatio')}</th>
                  <th className="px-2 py-2 text-right text-indigo-600 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('pop')}>POP{sortIcon('pop')}</th>
                  <th className="px-2 py-2 text-right text-red-600 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('maxLoss')}>Max Loss{sortIcon('maxLoss')}</th>
                  <th className="px-2 py-2 text-right text-emerald-600 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('maxProfit')}>Max Profit{sortIcon('maxProfit')}</th>
                  <th className="px-2 py-2 text-right cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('marginEstimate')}>Capital Req.*{sortIcon('marginEstimate')}</th>
                  <th className="px-2 py-2 text-right cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('riskReward')}>R:R{sortIcon('riskReward')}</th>
                  <th className="px-2 py-2 text-right">Breakevens</th>
                  <th className="px-2 py-2 text-right cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('impliedVol')}>IV{sortIcon('impliedVol')}</th>
                  <th className="px-2 py-2 text-right cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('daysToExpiry')}>DTE{sortIcon('daysToExpiry')}</th>
                  <th className="px-2 py-2 text-right">To BE</th>
                  <th className="px-2 py-2 text-center">Trade</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {sortedCandidates.map((c) => {
                  const key = rowKey(c);
                  const isSel = !!selected[key];
                  return (
                    <tr key={key} className={`transition ${isSel ? 'bg-amber-50' : 'hover:bg-slate-50'}`}>
                      <td className="px-2 py-1.5 text-center">
                        <input type="checkbox" checked={isSel} onChange={() => toggle(key)} className="w-3.5 h-3.5" />
                      </td>
                      <td className="px-2 py-1.5 font-bold text-slate-800">{c.underlying}</td>
                      <td className="px-2 py-1.5 font-bold text-slate-700">{c.strikes}</td>
                      <td className="px-2 py-1.5 text-slate-600">{c.optionType}</td>
                      <td className="px-2 py-1.5 text-right font-mono">{Math.round(c.costRatio * 100)}%</td>
                      <td className="px-2 py-1.5 text-right">
                        <span className={`px-1.5 py-0.5 rounded-full text-[10px] font-black ${c.pop >= 60 ? 'bg-emerald-100 text-emerald-700' : c.pop >= 40 ? 'bg-amber-100 text-amber-700' : 'bg-slate-100 text-slate-500'}`}>
                          {c.pop}%
                        </span>
                      </td>
                      <td className="px-2 py-1.5 text-right font-mono text-red-600">₹{Math.round(c.maxLoss).toLocaleString('en-IN')}</td>
                      <td className="px-2 py-1.5 text-right font-mono text-emerald-600">₹{Math.round(c.maxProfit).toLocaleString('en-IN')}</td>
                      <td className="px-2 py-1.5 text-right font-mono text-slate-500">₹{Math.round(c.marginEstimate || c.maxLoss).toLocaleString('en-IN')}</td>
                      <td className="px-2 py-1.5 text-right font-mono font-bold">{c.riskReward}x</td>
                      <td className="px-2 py-1.5 text-right font-mono text-[10px] text-slate-500">{c.breakevenLower}/{c.breakevenUpper}</td>
                      <td className="px-2 py-1.5 text-right font-mono text-slate-500">{c.impliedVol}%</td>
                      <td className="px-2 py-1.5 text-right font-mono text-slate-500">{c.daysToExpiry}d</td>
                      <td className="px-2 py-1.5 text-right font-mono text-[10px]">
                        {(() => { const g = breakevenGap(c); return g == null ? '—' : g < 0 ? <span className="text-red-600 font-bold">⚠️{Math.round(Math.abs(g))}</span> : <span className={g < 50 ? 'text-amber-600 font-bold' : 'text-slate-500'}>±{Math.round(g)}</span>; })()}
                      </td>
                      <td className="px-2 py-1.5 text-center">
                        <button onClick={() => handleExecuteInline(c)}
                          className="px-2 py-0.5 bg-amber-600 text-white text-[10px] font-bold rounded shadow-sm">
                          ⚡ Trade
                        </button>
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

/* 3d. CONDOR SPREAD VIEW (model-free, distinct from Iron Condor below) */
function CondorSpreadView({ handleExecuteInline, executionBroker }) {
  const [subTab, setSubTab] = useState('signals');
  const [historyMode, setHistoryMode] = useState('arbitrage');
  const [underlying, setUnderlying] = useState('ALL');
  const [minEdge, setMinEdge] = useState(0);
  const [customEdge, setCustomEdge] = useState('');
  const [expandedId, setExpandedId] = useState(null);
  const [sortCol, setSortCol] = useState('scanTime');
  const [sortAsc, setSortAsc] = useState(false);
  const [histPage, setHistPage] = useState(0);
  const PAGE_SIZE = 200;

  const { data: liveData, isLoading } = useQuery({
    queryKey: ['condor-spread-scan', underlying],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/condor-spread/scan', { params: { underlying } });
      return res.data;
    },
    refetchInterval: 30000
  });

  const histDate = new Date(); histDate.setDate(histDate.getDate() - 6);
  const today2 = histDate.toISOString().split('T')[0];
  const todayEnd = new Date().toISOString().split('T')[0];

  const { data: historyData } = useQuery({
    queryKey: ['condor-spread-history', underlying, histPage],
    queryFn: async () => {
      const params = { page: histPage, size: PAGE_SIZE, strategyType: 'CONDOR_SPREAD', startDate: today2, endDate: todayEnd };
      if (underlying !== 'ALL') params.underlying = underlying;
      const res = await client.get('/option-arbitrage/history', { params });
      return res.data;
    }
  });

  const liveOpps = (liveData?.opportunities || []).filter(o => (o.edgeAfterCosts || 0) >= minEdge);
  const historyOpps = (historyData?.items || []).filter(i => (i.edgeAfterCosts || 0) >= minEdge);

  const allOpps = [...liveOpps];
  const liveIds = new Set(liveOpps.map(o => o.id));
  historyOpps.forEach(h => { if (!liveIds.has(h.id)) allOpps.push(h); });

  const filteredByUnderlying = underlying === 'ALL' ? allOpps : allOpps.filter(o => o.underlying === underlying);
  const totalHistory = historyData?.totalElements || 0;
  const totalHistoryPages = historyData?.totalPages || 0;

  const csAllIds = allOpps.filter(o => o.id).map(o => o.id);
  const { data: csLivePnlRes } = useQuery({
    queryKey: ['condor-spread-pnl', csAllIds.join(',')],
    queryFn: async () => {
      if (csAllIds.length === 0) return { pnlMap: {}, statusMap: {} };
      const idsParam = csAllIds.slice(0, 500).join(',');
      const res = await client.get('/option-arbitrage/history/live-pnl', { params: { ids: idsParam } });
      return { pnlMap: res.data?.pnlMap || {}, statusMap: res.data?.statusMap || {} };
    },
    refetchInterval: 10000,
    enabled: csAllIds.length > 0
  });
  const csPnlMap = csLivePnlRes?.pnlMap || {};
  const csStatusMap = csLivePnlRes?.statusMap || {};

  const sortedOpps = [...filteredByUnderlying].sort((a, b) => {
    let va, vb;
    if (sortCol === 'scanTime') { va = a.scanTime || a.entryTime || ''; vb = b.scanTime || b.entryTime || ''; }
    else if (sortCol === 'underlying') { va = a.underlying || ''; vb = b.underlying || ''; }
    else if (sortCol === 'edge') { va = a.edgeAfterCosts || 0; vb = b.edgeAfterCosts || 0; }
    else if (sortCol === 'pnl') { va = a.pnlAfterCosts || 0; vb = b.pnlAfterCosts || 0; }
    else { va = a[sortCol] || ''; vb = b[sortCol] || ''; }
    if (typeof va === 'string') return sortAsc ? va.localeCompare(vb) : vb.localeCompare(va);
    return sortAsc ? va - vb : vb - va;
  });
  const toggleSort = (col) => { if (sortCol === col) setSortAsc(!sortAsc); else { setSortCol(col); setSortAsc(false); } };
  const sortIcon = (col) => sortCol === col ? (sortAsc ? ' ▲' : ' ▼') : ' ↕';

  return (
    <div className="space-y-4 w-full">
      <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl w-fit">
        <button onClick={() => setSubTab('signals')}
          className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${subTab === 'signals' ? 'bg-cyan-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
          Arbitrage Signals
        </button>
        <button onClick={() => setSubTab('candidates')}
          className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${subTab === 'candidates' ? 'bg-amber-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
          🔍 Candidates (Not Arbitrage)
        </button>
        <button onClick={() => setSubTab('autotrade')}
          className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${subTab === 'autotrade' ? 'bg-indigo-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
          ⚡ Auto-Trade
        </button>
        <button onClick={() => setSubTab('history')}
          className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${subTab === 'history' ? 'bg-emerald-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
          📊 History
        </button>
      </div>

      {subTab === 'history' ? (
        <div className="space-y-3">
          <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl w-fit">
            <button onClick={() => setHistoryMode('arbitrage')}
              className={`px-3 py-1 rounded-lg text-xs font-bold transition ${historyMode === 'arbitrage' ? 'bg-cyan-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              Arbitrage Signals
            </button>
            <button onClick={() => setHistoryMode('candidates')}
              className={`px-3 py-1 rounded-lg text-xs font-bold transition ${historyMode === 'candidates' ? 'bg-amber-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              🔍 Candidates
            </button>
          </div>
          {historyMode === 'candidates' ? (
            <CandidateHistoryPanel strategyType="CONDOR_SPREAD" label="Condor Spread" />
          ) : (
            <HistoryView lockedStrategy="CONDORSPREAD" handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} />
          )}
        </div>
      ) : subTab === 'autotrade' ? (
        <StrategyAutoTradePanel prefix="condor" label="Condor Spread" accent="indigo" />
      ) : subTab === 'candidates' ? (
        <CondorCandidatesPanel handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} />
      ) : (
      <>
      <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className="text-base font-bold text-slate-800">Condor Spread No-Arbitrage Bound Scanner</h2>
          <p className="text-xs text-slate-500">{sortedOpps.length} signals shown{totalHistory > 0 ? ` of ${totalHistory.toLocaleString('en-IN')} total today` : ''} — model-free convexity bound (0 ≤ price ≤ width), same family as Box/Vertical/Butterfly. Not the same as Iron Condor (a real-risk premium-selling strategy).</p>
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          {['ALL', 'NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'].map(u => (
            <button key={u} onClick={() => { setUnderlying(u); setHistPage(0); }}
              className={`px-3 py-1 rounded-lg text-xs font-bold transition ${underlying === u ? 'bg-cyan-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              {u}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          <span className="text-[10px] font-bold text-slate-500 px-1">EDGE ≥</span>
          {[0, 100, 300].map(e => (
            <button key={e} onClick={() => { setMinEdge(e); setCustomEdge(''); setHistPage(0); }}
              className={`px-2 py-1 rounded-lg text-[10px] font-bold transition ${minEdge === e && !customEdge ? 'bg-emerald-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              ₹{e}
            </button>
          ))}
          <input type="number" placeholder="Custom" value={customEdge} onChange={e => { setCustomEdge(e.target.value); setMinEdge(Number(e.target.value) || 0); setHistPage(0); }}
            className="w-16 px-1.5 py-1 rounded-lg text-[10px] font-bold border border-slate-300 focus:outline-none focus:border-emerald-500" />
        </div>
      </div>

      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm w-full">
        {isLoading ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">Scanning Condor Spreads...</div>
        ) : sortedOpps.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">No condor spread bound violations for {underlying}</div>
        ) : (
          <div className="overflow-x-auto w-full">
            <table className="w-full text-[11px] text-left border-collapse">
              <thead className="bg-slate-50 border-b border-slate-200 font-bold text-slate-600 uppercase">
                <tr>
                  <th className="px-2 py-2 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('scanTime')}>Time{sortIcon('scanTime')}</th>
                  <th className="px-2 py-2 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('underlying')}>Symbol{sortIcon('underlying')}</th>
                  <th className="px-2 py-2">Strikes</th>
                  <th className="px-2 py-2">Action</th>
                  <th className="px-2 py-2 text-right text-emerald-600 font-bold cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('edge')}>Net Edge{sortIcon('edge')}</th>
                  <th className="px-2 py-2 text-center">Status</th>
                  <th className="px-2 py-2 text-right cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('pnl')}>P&amp;L{sortIcon('pnl')}</th>
                  <th className="px-2 py-2 text-center">Exit</th>
                  <th className="px-2 py-2 text-center">Trade</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {sortedOpps.map((opp, idx) => {
                  const isExp = expandedId === (opp.id || idx);
                  const posStatus = opp.id && csStatusMap[String(opp.id)] ? String(csStatusMap[String(opp.id)]).toUpperCase() : null;
                  const statusStr = posStatus || String(opp.status || 'RUNNING').toUpperCase();
                  const isLive = statusStr === 'RUNNING' || statusStr === 'OPEN' || statusStr === 'DETECTED' || statusStr === 'EXECUTING';
                  const isExited = statusStr === 'EXITED' || statusStr === 'CLOSED';
                  const livePnl = opp.id && csPnlMap[String(opp.id)] != null ? Number(csPnlMap[String(opp.id)]) : null;
                  const pnlDisplay = livePnl != null ? livePnl : (opp.pnlAfterCosts != null ? Number(opp.pnlAfterCosts) : null);
                  const strikeMatch = opp.action ? opp.action.match(/\((\d+)\/(\d+)\/(\d+)\/(\d+)\)/) : null;
                  const strike1 = strikeMatch ? strikeMatch[1] : (opp.strike || 0);
                  const strike2 = strikeMatch ? strikeMatch[2] : '';
                  const strike3 = strikeMatch ? strikeMatch[3] : '';
                  const strike4 = strikeMatch ? strikeMatch[4] : '';
                  const edgeVal = Number(opp.edgeAfterCosts || 0);

                  const timeStr = fmtTime(opp.scanTime || opp.entryTime);
                  const exitStr = isExited ? fmtTime(opp.exitTime) : '';

                  return (
                    <React.Fragment key={opp.id || idx}>
                      <tr onClick={() => setExpandedId(isExp ? null : (opp.id || idx))}
                        className={`transition cursor-pointer ${isExp ? 'bg-cyan-50/70 border-l-4 border-cyan-600' : 'hover:bg-slate-50'}`}>
                        <td className="px-2 py-1.5 font-mono text-[10px] text-slate-500">{timeStr}</td>
                        <td className="px-2 py-1.5 font-bold text-slate-800">{opp.underlying}</td>
                        <td className="px-2 py-1.5 font-bold text-slate-700">{strike1}/{strike2}/{strike3}/{strike4}</td>
                        <td className="px-2 py-1.5 font-bold text-cyan-700 truncate max-w-[160px]">{opp.action}</td>
                        <td className="px-2 py-1.5 text-right font-mono font-bold text-emerald-600">
                          +₹{Math.round(edgeVal).toLocaleString('en-IN')}
                        </td>
                        <td className="px-2 py-1.5 text-center">
                          <span className={`px-1.5 py-0.2 rounded-full text-[9px] font-bold border ${
                            isLive ? 'bg-emerald-100 text-emerald-800 border-emerald-300' :
                            isExited ? 'bg-slate-200 text-slate-600 border-slate-300' :
                            statusStr === 'EXPIRED' ? 'bg-amber-100 text-amber-800 border-amber-300' :
                            'bg-blue-100 text-blue-800 border-blue-300'
                          }`}>
                            {isLive ? '🟢 RUNNING' : isExited ? '⏹ EXITED' : statusStr === 'EXPIRED' ? '⏰ EXPIRED' : statusStr}
                          </span>
                          {opp.existingOpenPosition && (
                            <span className="block mt-1 px-1.5 py-0.2 rounded-full text-[8px] font-bold border bg-amber-100 text-amber-800 border-amber-300" title={`You already hold an OPEN ${opp.existingPositionBroker || 'PAPER'} position for this signal`}>
                              📌 Already holding
                            </span>
                          )}
                        </td>
                        <td className="px-2 py-1.5 text-right font-mono font-bold">
                          {pnlDisplay != null && !isNaN(pnlDisplay)
                            ? <span className={pnlDisplay >= 0 ? 'text-emerald-600' : 'text-red-600'}>₹{Math.round(pnlDisplay).toLocaleString('en-IN')}</span>
                            : <span className="text-slate-400">--</span>}
                        </td>
                        <td className="px-2 py-1.5 text-center font-mono text-[10px] text-slate-500">{exitStr}</td>
                        <td className="px-2 py-1.5 text-center">
                          <button onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp); }}
                            className="px-2 py-0.5 bg-cyan-600 text-white text-[10px] font-bold rounded shadow-sm">
                            ⚡ Trade
                          </button>
                        </td>
                      </tr>
                      {isExp && (
                        <tr className="bg-cyan-50/40 border-b border-cyan-100">
                          <td colSpan={9} className="p-3">
                            <div className="bg-white rounded-xl p-3 border border-cyan-200 shadow-md space-y-2">
                              <span className="font-bold text-slate-800 text-xs uppercase block">Condor Spread Breakdown:</span>
                              {opp.existingOpenPosition && (
                                <p className="text-[11px] font-bold text-amber-700 bg-amber-50 border border-amber-200 rounded-lg px-2 py-1">
                                  📌 You already have an OPEN {opp.existingPositionBroker || 'PAPER'} position for this exact signal. Trading again will open an additional position, not add to or replace the existing one.
                                </p>
                              )}
                              <p className="text-xs font-mono font-bold text-slate-800 bg-slate-50 p-2 rounded-lg border">{opp.legs || '—'}</p>
                              <p className="text-[10px] text-slate-500">{opp.description}</p>
                              <div className="flex justify-end pt-1">
                                <button onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp); }} className="px-3 py-1 bg-cyan-600 text-white rounded-lg text-xs font-bold shadow-md">
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
        {totalHistoryPages > 1 && (
          <div className="flex items-center justify-between px-4 py-3 border-t border-slate-200 bg-slate-50">
            <span className="text-xs text-slate-500">Page {histPage + 1} of {totalHistoryPages} ({totalHistory.toLocaleString('en-IN')} signals today)</span>
            <div className="flex gap-1">
              <button disabled={histPage === 0} onClick={() => setHistPage(0)} className="px-2 py-1 bg-white border rounded text-xs font-bold disabled:opacity-40">First</button>
              <button disabled={histPage === 0} onClick={() => setHistPage(histPage - 1)} className="px-2 py-1 bg-white border rounded text-xs font-bold disabled:opacity-40">Prev</button>
              <button disabled={histPage >= totalHistoryPages - 1} onClick={() => setHistPage(histPage + 1)} className="px-2 py-1 bg-white border rounded text-xs font-bold disabled:opacity-40">Next</button>
              <button disabled={histPage >= totalHistoryPages - 1} onClick={() => setHistPage(totalHistoryPages - 1)} className="px-2 py-1 bg-white border rounded text-xs font-bold disabled:opacity-40">Last</button>
            </div>
          </div>
        )}
      </div>
      </>
      )}
    </div>
  );
}

/* Condor "cheap wide pin bet" candidate discovery panel -- NOT arbitrage, evaluation only.
   Same range-bet structure as ButterflyCandidatesPanel but with a flat profit zone between
   K2-K3 (4 legs) instead of a single peak at K2 -- mirrors Butterfly's chart/positions/exit
   panel with 4-leg payoff math. */
function CondorCandidatesPanel({ handleExecuteInline, executionBroker }) {
  const [underlying, setUnderlying] = useState('ALL');
  const [maxCostRatio, setMaxCostRatio] = useState(0.35);
  const [selected, setSelected] = useState({});
  const [hover, setHover] = useState(null);
  const [autoSelected, setAutoSelected] = useState(false);
  const [sortCol, setSortCol] = useState('pop');
  const [sortAsc, setSortAsc] = useState(false);
  const chartRef = useRef(null);

  const { data, isLoading } = useQuery({
    queryKey: ['condor-candidates', underlying, maxCostRatio],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/condor-spread/candidates', { params: { underlying, maxCostRatio } });
      return res.data;
    },
    refetchInterval: 30000
  });

  const { data: execSettings } = useQuery({
    queryKey: ['autoExecSettingsForCondorCandidates'],
    queryFn: async () => (await client.get('/option-arbitrage/auto-execute/settings')).data,
    refetchInterval: 60000
  });

  const candidates = data?.candidates || [];
  const rowKey = (c) => `${c.underlying}-${c.optionType}-${c.k1}-${c.k2}-${c.k3}-${c.k4}`;

  useEffect(() => {
    if (!autoSelected && candidates.length > 0) {
      setSelected({ [rowKey(candidates[0])]: true });
      setAutoSelected(true);
    }
  }, [candidates, autoSelected]);

  const selectedCandidates = candidates.filter((c) => selected[rowKey(c)]);
  const toggle = (key) => setSelected(prev => ({ ...prev, [key]: !prev[key] }));
  const toggleSort = (col) => { if (sortCol === col) setSortAsc(!sortAsc); else { setSortCol(col); setSortAsc(col === 'strikes' || col === 'underlying'); } };
  const sortIcon = (col) => sortCol === col ? (sortAsc ? ' ▲' : ' ▼') : ' ↕';
  const sortedCandidates = [...candidates].sort((a, b) => {
    let va = a[sortCol], vb = b[sortCol];
    if (sortCol === 'strikes' || sortCol === 'underlying' || sortCol === 'optionType') {
      va = va || ''; vb = vb || '';
      return sortAsc ? String(va).localeCompare(String(vb)) : String(vb).localeCompare(String(va));
    }
    va = Number(va) || 0; vb = Number(vb) || 0;
    return sortAsc ? va - vb : vb - va;
  });

  const payoffChart = useMemo(() => {
    if (selectedCandidates.length === 0) return null;
    const spot = selectedCandidates[0].spotPrice || selectedCandidates.reduce((s, c) => s + c.spotPrice, 0) / selectedCandidates.length;
    const lo = Math.min(...selectedCandidates.map(c => c.k1)) - 300;
    const hi = Math.max(...selectedCandidates.map(c => c.k4)) + 300;
    const steps = 200;
    const stepSize = (hi - lo) / steps;
    const points = [];
    const todayPoints = [];
    let minY = 0, maxY = 0;
    for (let i = 0; i <= steps; i++) {
      const x = lo + i * stepSize;
      let expiryTotal = 0, todayTotal = 0;
      for (const c of selectedCandidates) {
        const lotSize = c.lotSize || 25;
        const T = Math.max(c.daysToExpiry, 0.5) / 365;
        const r = c.riskFreeRate || 0.065;
        const sigma = (c.impliedVol || 20) / 100;
        const priceFn = c.optionType === 'CE' ? bsCallPrice : bsPutPrice;

        let expiryPayoff;
        if (c.optionType === 'CE') {
          expiryPayoff = Math.max(x - c.k1, 0) - Math.max(x - c.k2, 0) - Math.max(x - c.k3, 0) + Math.max(x - c.k4, 0);
        } else {
          expiryPayoff = Math.max(c.k1 - x, 0) - Math.max(c.k2 - x, 0) - Math.max(c.k3 - x, 0) + Math.max(c.k4 - x, 0);
        }
        expiryTotal += (expiryPayoff - c.costPerLot) * lotSize;

        const todayValue = priceFn(x, c.k1, T, r, sigma) - priceFn(x, c.k2, T, r, sigma) - priceFn(x, c.k3, T, r, sigma) + priceFn(x, c.k4, T, r, sigma);
        todayTotal += (todayValue - c.costPerLot) * lotSize;
      }
      points.push({ x, y: expiryTotal });
      todayPoints.push({ x, y: todayTotal });
      minY = Math.min(minY, expiryTotal, todayTotal);
      maxY = Math.max(maxY, expiryTotal, todayTotal);
    }
    return { points, todayPoints, spot, lo, hi, minY: Math.min(minY, 0), maxY: Math.max(maxY, 0) };
  }, [selectedCandidates]);

  const totalMaxLoss = selectedCandidates.reduce((s, c) => s + c.maxLoss, 0);
  const totalMaxProfit = selectedCandidates.reduce((s, c) => s + c.maxProfit, 0);
  const totalMargin = selectedCandidates.reduce((s, c) => s + (c.marginEstimate || c.maxLoss), 0);
  const totalCharges = selectedCandidates.reduce((s, c) => s + (c.entryCosts || 0), 0);
  const avgPop = selectedCandidates.length > 0
    ? selectedCandidates.reduce((s, c) => s + c.pop, 0) / selectedCandidates.length : null;
  const breakevenGap = (c) => {
    if (c.breakevenLower == null || c.breakevenUpper == null || c.spotPrice == null) return null;
    return Math.min(c.spotPrice - c.breakevenLower, c.breakevenUpper - c.spotPrice);
  };
  const soloBreakevenGap = selectedCandidates.length === 1 ? breakevenGap(selectedCandidates[0]) : null;

  const CHART_W = 700, CHART_H = 260, PAD_TOP = 24, PAD_BOTTOM = 34;
  const plotH = CHART_H - PAD_TOP - PAD_BOTTOM;
  const xToPx = (x) => payoffChart ? ((x - payoffChart.lo) / (payoffChart.hi - payoffChart.lo)) * CHART_W : 0;
  const yToPx = (y) => payoffChart
    ? PAD_TOP + plotH - ((y - payoffChart.minY) / (payoffChart.maxY - payoffChart.minY || 1)) * plotH
    : 0;
  const pxToX = (px) => payoffChart ? payoffChart.lo + (px / CHART_W) * (payoffChart.hi - payoffChart.lo) : 0;

  const handleChartMove = (e) => {
    if (!payoffChart || !chartRef.current) return;
    const rect = chartRef.current.getBoundingClientRect();
    const relX = (e.clientX - rect.left) / rect.width;
    const px = relX * CHART_W;
    const priceAtCursor = pxToX(px);
    let nearestIdx = 0, bestDist = Infinity;
    payoffChart.points.forEach((p, i) => {
      const d = Math.abs(p.x - priceAtCursor);
      if (d < bestDist) { bestDist = d; nearestIdx = i; }
    });
    const expiry = payoffChart.points[nearestIdx];
    const today = payoffChart.todayPoints[nearestIdx];
    setHover({ px: xToPx(expiry.x), pyExpiry: yToPx(expiry.y), pyToday: yToPx(today.y), price: expiry.x, pnlExpiry: expiry.y, pnlToday: today.y });
  };

  const zeroPx = payoffChart ? yToPx(0) : 0;
  const areaPath = payoffChart ? (() => {
    const pts = payoffChart.points.map(p => `${xToPx(p.x)},${yToPx(p.y)}`).join(' L ');
    return `M ${xToPx(payoffChart.points[0].x)},${zeroPx} L ${pts} L ${xToPx(payoffChart.points[payoffChart.points.length - 1].x)},${zeroPx} Z`;
  })() : '';

  return (
    <div className="space-y-4 w-full">
      <div className="bg-amber-50 border border-amber-300 rounded-2xl p-4 text-xs text-amber-900">
        <p className="font-bold mb-1">⚠️ Not arbitrage — evaluation tool only</p>
        <p>These are condors priced cheap relative to their width — a wide-range pin bet (profits anywhere between the two inner strikes, or on the ramps either side, by expiry), not a guaranteed-profit position. More forgiving than a Butterfly's single-peak bet, but lower max profit per rupee risked. POP is a Black-Scholes model estimate from current implied volatility, not a backtested or historical win rate. Move your mouse over the chart to see P&amp;L at any settlement price.</p>
      </div>

      <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className="text-base font-bold text-slate-800">Cheap Condor Candidates</h2>
          <p className="text-xs text-slate-500">{candidates.length} candidates — cost ≤ {Math.round(maxCostRatio * 100)}% of width, sorted by model POP (highest first)</p>
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          {['ALL', 'NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'].map(u => (
            <button key={u} onClick={() => { setUnderlying(u); setAutoSelected(false); }}
              className={`px-3 py-1 rounded-lg text-xs font-bold transition ${underlying === u ? 'bg-amber-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              {u}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          <span className="text-[10px] font-bold text-slate-500 px-1">MAX COST/WIDTH</span>
          {[0.2, 0.35, 0.5].map(r => (
            <button key={r} onClick={() => { setMaxCostRatio(r); setAutoSelected(false); }}
              className={`px-2 py-1 rounded-lg text-[10px] font-bold transition ${maxCostRatio === r ? 'bg-emerald-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              {Math.round(r * 100)}%
            </button>
          ))}
        </div>
        {selectedCandidates.length > 0 && (
          <button onClick={() => setSelected({})}
            className="px-3 py-1.5 rounded-lg text-xs font-bold bg-red-50 text-red-600 border border-red-200 hover:bg-red-100 transition">
            ✕ Clear Selection ({selectedCandidates.length})
          </button>
        )}
      </div>

      {selectedCandidates.length > 0 && (
        <div className="bg-gradient-to-br from-white via-amber-50/30 to-indigo-50/30 rounded-2xl border-2 border-amber-200 shadow-lg p-5 space-y-5">
          <div className="grid grid-cols-2 md:grid-cols-7 gap-3">
            <div className="bg-white rounded-xl border border-slate-200 p-2.5 text-center">
              <div className="text-[9px] font-bold text-slate-400 uppercase">POP</div>
              <div className={`text-lg font-black ${avgPop >= 60 ? 'text-emerald-600' : avgPop >= 40 ? 'text-amber-600' : 'text-slate-500'}`}>{avgPop?.toFixed(1)}%</div>
            </div>
            <div className="bg-white rounded-xl border border-slate-200 p-2.5 text-center">
              <div className="text-[9px] font-bold text-slate-400 uppercase">Max Loss</div>
              <div className="text-lg font-black text-red-600">₹{Math.round(totalMaxLoss).toLocaleString('en-IN')}</div>
            </div>
            <div className="bg-white rounded-xl border border-slate-200 p-2.5 text-center">
              <div className="text-[9px] font-bold text-slate-400 uppercase">Max Profit</div>
              <div className="text-lg font-black text-emerald-600">₹{Math.round(totalMaxProfit).toLocaleString('en-IN')}</div>
            </div>
            <div className="bg-white rounded-xl border border-slate-200 p-2.5 text-center">
              <div className="text-[9px] font-bold text-slate-400 uppercase">Risk:Reward</div>
              <div className="text-lg font-black text-indigo-600">{totalMaxLoss > 0 ? (totalMaxProfit / totalMaxLoss).toFixed(1) : '0'}x</div>
            </div>
            <div className="bg-white rounded-xl border border-slate-200 p-2.5 text-center">
              <div className="text-[9px] font-bold text-slate-400 uppercase">Capital Required*</div>
              <div className="text-lg font-black text-slate-700">₹{Math.round(totalMargin).toLocaleString('en-IN')}</div>
            </div>
            <div className="bg-white rounded-xl border border-slate-200 p-2.5 text-center">
              <div className="text-[9px] font-bold text-slate-400 uppercase">Charges</div>
              <div className="text-lg font-black text-slate-700">₹{Math.round(totalCharges).toLocaleString('en-IN')}</div>
            </div>
            <div className="bg-white rounded-xl border border-slate-200 p-2.5 text-center">
              <div className="text-[9px] font-bold text-slate-400 uppercase">Breakeven Gap</div>
              <div className={`text-lg font-black ${soloBreakevenGap == null ? 'text-slate-300' : soloBreakevenGap < 0 ? 'text-red-600' : soloBreakevenGap < 50 ? 'text-amber-600' : 'text-emerald-600'}`}>
                {soloBreakevenGap == null ? '—' : soloBreakevenGap < 0 ? `⚠️ -${Math.round(Math.abs(soloBreakevenGap))}` : `±${Math.round(soloBreakevenGap)}`}
              </div>
            </div>
          </div>
          <p className="text-[9px] text-slate-400 -mt-3">*Capital required is a conservative estimate (worst-case cash outflow) — actual broker SPAN+exposure margin may differ; verify with your broker before trading.</p>
          {soloBreakevenGap != null && soloBreakevenGap < 0 && (
            <p className="text-[11px] font-bold text-red-600 bg-red-50 border border-red-200 rounded-lg px-3 py-1.5 -mt-1">⚠️ Spot has already moved outside this candidate's profit zone by {Math.round(Math.abs(soloBreakevenGap))} points — this is now a probable loss unless price reverses before expiry.</p>
          )}

          {payoffChart && (
            <div>
              <div className="flex items-center gap-4 mb-1 px-1">
                <span className="flex items-center gap-1.5 text-[10px] font-bold text-amber-700"><span className="w-3 h-0.5 bg-amber-600 inline-block rounded" /> At Expiry</span>
                <span className="flex items-center gap-1.5 text-[10px] font-bold text-blue-600"><span className="w-3 h-0.5 bg-blue-500 inline-block rounded" /> Today (Black-Scholes est.)</span>
              </div>
              <div className="relative overflow-x-auto bg-white rounded-xl border border-slate-100 p-2">
                <svg ref={chartRef} viewBox={`0 0 ${CHART_W} ${CHART_H}`} className="w-full h-[260px] cursor-crosshair"
                  onMouseMove={handleChartMove} onMouseLeave={() => setHover(null)}>
                  <defs>
                    <linearGradient id="cProfitGrad" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor="#10b981" stopOpacity="0.35" />
                      <stop offset="100%" stopColor="#10b981" stopOpacity="0.02" />
                    </linearGradient>
                    <linearGradient id="cLossGrad" x1="0" y1="1" x2="0" y2="0">
                      <stop offset="0%" stopColor="#ef4444" stopOpacity="0.3" />
                      <stop offset="100%" stopColor="#ef4444" stopOpacity="0.02" />
                    </linearGradient>
                  </defs>
                  <clipPath id="cAboveZero"><rect x="0" y="0" width={CHART_W} height={zeroPx} /></clipPath>
                  <clipPath id="cBelowZero"><rect x="0" y={zeroPx} width={CHART_W} height={CHART_H - zeroPx} /></clipPath>
                  <path d={areaPath} fill="url(#cProfitGrad)" clipPath="url(#cAboveZero)" />
                  <path d={areaPath} fill="url(#cLossGrad)" clipPath="url(#cBelowZero)" />

                  <line x1="0" y1={zeroPx} x2={CHART_W} y2={zeroPx} stroke="#94a3b8" strokeWidth="1" strokeDasharray="4,4" />
                  <line x1={xToPx(payoffChart.spot)} y1={PAD_TOP} x2={xToPx(payoffChart.spot)} y2={CHART_H - PAD_BOTTOM}
                    stroke="#6366f1" strokeWidth="1.5" strokeDasharray="3,3" />
                  <text x={xToPx(payoffChart.spot)} y={PAD_TOP - 8} textAnchor="middle" fontSize="10" fontWeight="700" fill="#6366f1">
                    Spot {Math.round(payoffChart.spot).toLocaleString('en-IN')}
                  </text>

                  <polyline
                    fill="none" stroke="#3b82f6" strokeWidth="2" strokeLinejoin="round" opacity="0.85"
                    points={payoffChart.todayPoints.map(p => `${xToPx(p.x)},${yToPx(p.y)}`).join(' ')}
                  />
                  <polyline
                    fill="none" stroke="#d97706" strokeWidth="2.5" strokeLinejoin="round"
                    points={payoffChart.points.map(p => `${xToPx(p.x)},${yToPx(p.y)}`).join(' ')}
                  />

                  {hover && (
                    <g>
                      <line x1={hover.px} y1={PAD_TOP} x2={hover.px} y2={CHART_H - PAD_BOTTOM} stroke="#0f172a" strokeWidth="1" strokeDasharray="2,2" opacity="0.4" />
                      <circle cx={hover.px} cy={hover.pyToday} r="4" fill="#3b82f6" stroke="white" strokeWidth="1.5" />
                      <circle cx={hover.px} cy={hover.pyExpiry} r="4.5" fill={hover.pnlExpiry >= 0 ? '#10b981' : '#ef4444'} stroke="white" strokeWidth="1.5" />
                      {(() => {
                        const boxW = 150, boxH = 62;
                        const bx = Math.min(Math.max(hover.px - boxW / 2, 2), CHART_W - boxW - 2);
                        const anchorY = Math.min(hover.pyExpiry, hover.pyToday);
                        const by = anchorY > 90 ? anchorY - boxH - 10 : Math.max(hover.pyExpiry, hover.pyToday) + 14;
                        return (
                          <g>
                            <rect x={bx} y={by} width={boxW} height={boxH} rx="6" fill="#0f172a" opacity="0.94" />
                            <text x={bx + 8} y={by + 15} fontSize="10" fill="#cbd5e1">
                              @ {Math.round(hover.price).toLocaleString('en-IN')}
                            </text>
                            <text x={bx + 8} y={by + 32} fontSize="11" fontWeight="700" fill="#93c5fd">
                              Today: {hover.pnlToday >= 0 ? '+' : ''}₹{Math.round(hover.pnlToday).toLocaleString('en-IN')}
                            </text>
                            <text x={bx + 8} y={by + 49} fontSize="11" fontWeight="800" fill={hover.pnlExpiry >= 0 ? '#34d399' : '#f87171'}>
                              Expiry: {hover.pnlExpiry >= 0 ? '+' : ''}₹{Math.round(hover.pnlExpiry).toLocaleString('en-IN')}
                            </text>
                          </g>
                        );
                      })()}
                    </g>
                  )}

                  {selectedCandidates.length === 1 && (
                    <>
                      <line x1={xToPx(selectedCandidates[0].breakevenLower)} y1={PAD_TOP} x2={xToPx(selectedCandidates[0].breakevenLower)} y2={CHART_H - PAD_BOTTOM} stroke="#94a3b8" strokeWidth="1" strokeDasharray="2,3" opacity="0.6" />
                      <line x1={xToPx(selectedCandidates[0].breakevenUpper)} y1={PAD_TOP} x2={xToPx(selectedCandidates[0].breakevenUpper)} y2={CHART_H - PAD_BOTTOM} stroke="#94a3b8" strokeWidth="1" strokeDasharray="2,3" opacity="0.6" />
                    </>
                  )}
                </svg>
              </div>
              <div className="flex justify-between text-[10px] text-slate-500 px-1 mt-1">
                <span>{Math.round(payoffChart.lo).toLocaleString('en-IN')}</span>
                <span>{Math.round(payoffChart.hi).toLocaleString('en-IN')}</span>
              </div>
              <p className="text-[10px] text-slate-400 text-center mt-1">P&amp;L (₹) vs. settlement price — hover to inspect any price point</p>
            </div>
          )}

          <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
            <div className="px-3 py-2 bg-slate-50 border-b border-slate-200 text-[10px] font-black text-slate-600 uppercase">
              📋 Positions to be taken
            </div>
            <table className="w-full text-[11px]">
              <thead className="text-slate-400 text-[9px] uppercase">
                <tr>
                  <th className="px-3 py-1.5 text-left">Symbol</th>
                  <th className="px-3 py-1.5 text-left">Side</th>
                  <th className="px-3 py-1.5 text-right">Strike</th>
                  <th className="px-3 py-1.5 text-right">Qty (lots)</th>
                  <th className="px-3 py-1.5 text-right">Price</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {selectedCandidates.flatMap((c, ci) => (c.legList || []).map((leg, li) => (
                  <tr key={`${ci}-${li}`}>
                    <td className="px-3 py-1.5 font-bold text-slate-700">{c.underlying} {leg.optionType}</td>
                    <td className="px-3 py-1.5">
                      <span className={`px-1.5 py-0.5 rounded text-[9px] font-black ${leg.side === 'BUY' ? 'bg-emerald-100 text-emerald-700' : 'bg-red-100 text-red-700'}`}>{leg.side}</span>
                    </td>
                    <td className="px-3 py-1.5 text-right font-mono">{leg.strike}</td>
                    <td className="px-3 py-1.5 text-right font-mono">{leg.qty}</td>
                    <td className="px-3 py-1.5 text-right font-mono">₹{leg.price?.toFixed(2)}</td>
                  </tr>
                )))}
              </tbody>
            </table>
          </div>

          <div className="bg-indigo-50 rounded-xl border border-indigo-200 p-3 text-[11px] text-indigo-900 space-y-1">
            <p className="font-black uppercase text-[10px] text-indigo-600">🛡️ If you trade this — what happens automatically</p>
            {execSettings ? (
              <>
                <p><strong>Auto-exit on target:</strong> {execSettings.autoExitEnabled ? `ON — squares off at ${execSettings.autoExitThresholdPct ?? 90}% of max profit` : 'OFF'}</p>
                <p><strong>Stop-loss:</strong> {execSettings.stopLossEnabled ? `ON — squares off at ${execSettings.stopLossPct ?? 50}% of max loss` : 'OFF'}</p>
                <p className="text-indigo-500">Change these in the Auto-Trade tab. Only applies if you actually execute the trade (paper or live) — this panel is not a live position.</p>
              </>
            ) : (
              <p className="text-indigo-400">Loading current settings…</p>
            )}
            <p className="text-red-600 font-bold pt-1">⚠️ No automatic strike-adjustment or rolling exists for these spreads. If price moves against the position, it holds until it hits the stop-loss, hits the target, or expires — nothing rebalances it for you.</p>
          </div>
        </div>
      )}

      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm w-full">
        {isLoading ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">Scanning for cheap condor candidates...</div>
        ) : data?.marketClosed ? (
          <div className="p-12 text-center text-sm font-semibold">
            <div className="text-3xl mb-2">🌙</div>
            <div className="text-slate-500">Market is closed</div>
            <div className="text-slate-400 text-xs font-normal mt-1">{data?.reason || 'NSE/NFO hours: Mon-Fri 09:15-15:30 IST'}</div>
          </div>
        ) : candidates.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">No candidates under {Math.round(maxCostRatio * 100)}% cost/width right now</div>
        ) : (
          <div className="overflow-x-auto w-full">
            <table className="w-full text-[11px] text-left border-collapse">
              <thead className="bg-slate-50 border-b border-slate-200 font-bold text-slate-600 uppercase">
                <tr>
                  <th className="px-2 py-2"></th>
                  <th className="px-2 py-2 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('underlying')}>Symbol{sortIcon('underlying')}</th>
                  <th className="px-2 py-2 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('strikes')}>Strikes{sortIcon('strikes')}</th>
                  <th className="px-2 py-2">Type</th>
                  <th className="px-2 py-2 text-right cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('costRatio')}>Cost/Width{sortIcon('costRatio')}</th>
                  <th className="px-2 py-2 text-right text-indigo-600 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('pop')}>POP{sortIcon('pop')}</th>
                  <th className="px-2 py-2 text-right text-red-600 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('maxLoss')}>Max Loss{sortIcon('maxLoss')}</th>
                  <th className="px-2 py-2 text-right text-emerald-600 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('maxProfit')}>Max Profit{sortIcon('maxProfit')}</th>
                  <th className="px-2 py-2 text-right cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('marginEstimate')}>Capital Req.*{sortIcon('marginEstimate')}</th>
                  <th className="px-2 py-2 text-right cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('riskReward')}>R:R{sortIcon('riskReward')}</th>
                  <th className="px-2 py-2 text-right">Breakevens</th>
                  <th className="px-2 py-2 text-right cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('impliedVol')}>IV{sortIcon('impliedVol')}</th>
                  <th className="px-2 py-2 text-right cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('daysToExpiry')}>DTE{sortIcon('daysToExpiry')}</th>
                  <th className="px-2 py-2 text-right">To BE</th>
                  <th className="px-2 py-2 text-center">Trade</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {sortedCandidates.map((c) => {
                  const key = rowKey(c);
                  const isSel = !!selected[key];
                  return (
                    <tr key={key} className={`transition ${isSel ? 'bg-amber-50' : 'hover:bg-slate-50'}`}>
                      <td className="px-2 py-1.5 text-center">
                        <input type="checkbox" checked={isSel} onChange={() => toggle(key)} className="w-3.5 h-3.5" />
                      </td>
                      <td className="px-2 py-1.5 font-bold text-slate-800">{c.underlying}</td>
                      <td className="px-2 py-1.5 font-bold text-slate-700">{c.strikes}</td>
                      <td className="px-2 py-1.5 text-slate-600">{c.optionType}</td>
                      <td className="px-2 py-1.5 text-right font-mono">{Math.round(c.costRatio * 100)}%</td>
                      <td className="px-2 py-1.5 text-right">
                        <span className={`px-1.5 py-0.5 rounded-full text-[10px] font-black ${c.pop >= 60 ? 'bg-emerald-100 text-emerald-700' : c.pop >= 40 ? 'bg-amber-100 text-amber-700' : 'bg-slate-100 text-slate-500'}`}>
                          {c.pop}%
                        </span>
                      </td>
                      <td className="px-2 py-1.5 text-right font-mono text-red-600">₹{Math.round(c.maxLoss).toLocaleString('en-IN')}</td>
                      <td className="px-2 py-1.5 text-right font-mono text-emerald-600">₹{Math.round(c.maxProfit).toLocaleString('en-IN')}</td>
                      <td className="px-2 py-1.5 text-right font-mono text-slate-500">₹{Math.round(c.marginEstimate || c.maxLoss).toLocaleString('en-IN')}</td>
                      <td className="px-2 py-1.5 text-right font-mono font-bold">{c.riskReward}x</td>
                      <td className="px-2 py-1.5 text-right font-mono text-[10px] text-slate-500">{c.breakevenLower}/{c.breakevenUpper}</td>
                      <td className="px-2 py-1.5 text-right font-mono text-slate-500">{c.impliedVol}%</td>
                      <td className="px-2 py-1.5 text-right font-mono text-slate-500">{c.daysToExpiry}d</td>
                      <td className="px-2 py-1.5 text-right font-mono text-[10px]">
                        {(() => { const g = breakevenGap(c); return g == null ? '—' : g < 0 ? <span className="text-red-600 font-bold">⚠️{Math.round(Math.abs(g))}</span> : <span className={g < 50 ? 'text-amber-600 font-bold' : 'text-slate-500'}>±{Math.round(g)}</span>; })()}
                      </td>
                      <td className="px-2 py-1.5 text-center">
                        <button onClick={() => handleExecuteInline(c)}
                          className="px-2 py-0.5 bg-amber-600 text-white text-[10px] font-bold rounded shadow-sm">
                          ⚡ Trade
                        </button>
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

/* 4. 0DTE IRON CONDOR VIEW */
function IronCondorView({ handleExecuteInline, executionBroker }) {
  const [subTab, setSubTab] = useState('signals');
  const [underlying, setUnderlying] = useState('ALL');
  const [minEdge, setMinEdge] = useState(300);
  const [customEdge, setCustomEdge] = useState('');
  const [expandedId, setExpandedId] = useState(null);
  const [sortCol, setSortCol] = useState('scanTime');
  const [sortAsc, setSortAsc] = useState(false);
  const [histPage, setHistPage] = useState(0);
  const PAGE_SIZE = 200;

  const { data: liveData, isLoading } = useQuery({
    queryKey: ['iron-condor-scan', underlying],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/iron-condor/scan', { params: { underlying } });
      return res.data;
    },
    refetchInterval: 30000
  });

  const today2 = new Date().toLocaleDateString('en-CA');

  const { data: historyData } = useQuery({
    queryKey: ['iron-history', underlying, histPage],
    queryFn: async () => {
      const params = { page: histPage, size: PAGE_SIZE, strategyType: 'IRON_CONDOR', startDate: today2, endDate: today2 };
      if (underlying !== 'ALL') params.underlying = underlying;
      const res = await client.get('/option-arbitrage/history', { params });
      return res.data;
    }
  });

  const liveOpps = (liveData?.opportunities || []).filter(o => (o.edgeAfterCosts || o.maxProfitRs || 0) >= minEdge);
  const historyOpps = (historyData?.items || []).filter(i => (i.edgeAfterCosts || 0) >= minEdge);

  const allOpps = [...liveOpps];
  const liveIds = new Set(liveOpps.map(o => o.id));
  historyOpps.forEach(h => { if (!liveIds.has(h.id)) allOpps.push(h); });

  const filteredByUnderlying = underlying === 'ALL' ? allOpps : allOpps.filter(o => o.underlying === underlying);
  const totalHistory = historyData?.totalElements || 0;
  const totalHistoryPages = historyData?.totalPages || 0;

  const icAllIds = allOpps.filter(o => o.id).map(o => o.id);
  const { data: icLivePnlRes } = useQuery({
    queryKey: ['ic-pnl', icAllIds.join(',')],
    queryFn: async () => {
      if (icAllIds.length === 0) return { pnlMap: {}, statusMap: {} };
      const idsParam = icAllIds.slice(0, 500).join(',');
      const res = await client.get('/option-arbitrage/history/live-pnl', { params: { ids: idsParam } });
      return { pnlMap: res.data?.pnlMap || {}, statusMap: res.data?.statusMap || {} };
    },
    refetchInterval: 10000,
    enabled: icAllIds.length > 0
  });
  const icPnlMap = icLivePnlRes?.pnlMap || {};
  const icStatusMap = icLivePnlRes?.statusMap || {};

  const icUnds = ['NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'];
  const icStats = useMemo(() => {
    return icUnds.map(u => {
      const items = allOpps.filter(o => o.underlying === u);
      const getMergedStatus = (o) => {
        const posStatus = icStatusMap[String(o.id)];
        return posStatus || o.status || 'RUNNING';
      };
      const getPnl = (o) => {
        const live = icPnlMap[String(o.id)];
        if (live != null) return Number(live);
        return o.pnlAfterCosts != null ? Number(o.pnlAfterCosts) : null;
      };
      const inProfit = items.filter(o => { const p = getPnl(o); return p != null && p > 0; }).length;
      const inLoss = items.filter(o => { const p = getPnl(o); return p != null && p < 0; }).length;
      const running = items.filter(o => { const s = String(getMergedStatus(o)).toUpperCase(); return s === 'RUNNING' || s === 'OPEN' || s === 'DETECTED' || s === 'EXECUTING'; }).length;
      const maxEdge = items.length > 0 ? Math.max(...items.map(o => Number(o.edgeAfterCosts) || 0)) : 0;
      const hitTarget = items.filter(o => {
        const p = getPnl(o);
        const edge = Number(o.edgeAfterCosts) || 0;
        return p != null && edge > 0 && p >= edge;
      }).length;
      return { underlying: u, total: items.length, inProfit, inLoss, running, maxEdge, hitTarget };
    }).filter(s => s.total > 0);
  }, [allOpps, icPnlMap, icStatusMap]);

  const sortedOpps = [...filteredByUnderlying].sort((a, b) => {
    const av = a[sortCol]; const bv = b[sortCol];
    if (av == null && bv == null) return 0;
    if (av == null) return 1;
    if (bv == null) return -1;
    if (sortCol === 'edgeAfterCosts' || sortCol === 'strike') return sortAsc ? Number(av) - Number(bv) : Number(bv) - Number(av);
    if (sortCol === 'scanTime') return sortAsc ? String(av).localeCompare(String(bv)) : String(bv).localeCompare(String(av));
    return sortAsc ? String(av).localeCompare(String(bv)) : String(bv).localeCompare(String(av));
  });

  const ColHead = ({ col, children, right }) => (
    <th className={`px-2 py-2 cursor-pointer hover:text-indigo-600 select-none ${right ? 'text-right' : ''}`}
      onClick={() => { if (sortCol === col) setSortAsc(!sortAsc); else { setSortCol(col); setSortAsc(false); } }}>
      {children} {sortCol === col ? (sortAsc ? '▲' : '▼') : ''}
    </th>
  );

  return (
    <div className="space-y-4 w-full">
      <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl w-fit">
        <button onClick={() => setSubTab('signals')}
          className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${subTab === 'signals' ? 'bg-indigo-700 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
          Signals
        </button>
        <button onClick={() => setSubTab('autotrade')}
          className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${subTab === 'autotrade' ? 'bg-indigo-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
          ⚡ Auto-Trade
        </button>
        <button onClick={() => setSubTab('history')}
          className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${subTab === 'history' ? 'bg-emerald-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
          📊 History
        </button>
      </div>

      {subTab === 'history' ? (
        <HistoryView lockedStrategy="CONDOR" handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} />
      ) : subTab === 'autotrade' ? (
        <StrategyAutoTradePanel prefix="ironCondor" label="Iron Condor" accent="indigo" />
      ) : (
      <>
      <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className="text-base font-bold text-slate-800">0DTE Delta-Neutral Iron Condor Scanner</h2>
          <p className="text-xs text-slate-500">High-probability non-directional credit wing spreads with dynamic trailing stop loss</p>
        </div>

        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          {['ALL', 'NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'].map(u => (
            <button key={u} onClick={() => { setUnderlying(u); setHistPage(0); }}
              className={`px-3 py-1 rounded-lg text-xs font-bold transition ${underlying === u ? 'bg-indigo-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              {u}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          <span className="text-[10px] font-bold text-slate-500 px-1">EDGE ≥</span>
          {[300, 500, 1000].map(e => (
            <button key={e} onClick={() => { setMinEdge(e); setCustomEdge(''); }}
              className={`px-2 py-1 rounded-lg text-[10px] font-bold transition ${minEdge === e && !customEdge ? 'bg-emerald-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              ₹{e}
            </button>
          ))}
          <input type="number" placeholder="Custom" value={customEdge} onChange={e => { setCustomEdge(e.target.value); setMinEdge(Number(e.target.value) || 0); }}
            className="w-16 px-1.5 py-1 rounded-lg text-[10px] font-bold border border-slate-300 focus:outline-none focus:border-emerald-500" />
        </div>
      </div>

      {icStats.length > 0 && (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          {icStats.map(s => (
            <div key={s.underlying} className="bg-white p-3 rounded-xl border border-slate-200 shadow-sm text-center">
              <div className="text-[10px] font-bold text-slate-500 uppercase">{s.underlying}</div>
              <div className="text-lg font-black text-slate-800">{s.total}</div>
              <div className="flex justify-center gap-2 text-[10px] mt-1">
                <span className="text-emerald-600 font-bold">▲ {s.inProfit}</span>
                <span className="text-red-500 font-bold">▼ {s.inLoss}</span>
                <span className="text-blue-500 font-bold">● {s.running}</span>
              </div>
              <div className="text-[10px] text-slate-500 mt-0.5">Max Edge: ₹{Math.round(s.maxEdge)}</div>
            </div>
          ))}
        </div>
      )}

      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm w-full">
        <div className="px-4 py-2 border-b border-slate-200 bg-slate-50 flex items-center justify-between">
          <span className="text-xs font-bold text-slate-600">{sortedOpps.length} signals shown of {totalHistory.toLocaleString('en-IN')} total today</span>
        </div>
        {isLoading ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">Scanning 0DTE Iron Condor spreads...</div>
        ) : sortedOpps.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">No 0DTE Iron Condor setups meeting risk criteria for {underlying}</div>
        ) : (
          <div className="overflow-x-auto w-full">
            <table className="w-full text-xs text-left border-collapse">
              <thead className="bg-slate-50 border-b border-slate-200 font-bold text-slate-600 uppercase">
                <tr>
                  <ColHead col="scanTime">Time</ColHead>
                  <ColHead col="underlying">Underlying</ColHead>
                  <ColHead col="strike">Strike</ColHead>
                  <ColHead col="action">Action / Legs</ColHead>
                  <ColHead col="edgeAfterCosts" right>Edge ₹</ColHead>
                  <th className="px-2 py-2 text-center">Status</th>
                  <th className="px-2 py-2 text-center">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {sortedOpps.map((opp, idx) => {
                  const isExp = expandedId === opp.id;
                  const posStatus = opp.id && icStatusMap[String(opp.id)] ? String(icStatusMap[String(opp.id)]).toUpperCase() : null;
                  const st = posStatus || String(opp.status || 'RUNNING').toUpperCase();
                  const stColor = st === 'RUNNING' || st === 'OPEN' || st === 'DETECTED' || st === 'EXECUTING' ? 'bg-blue-100 text-blue-700' : st === 'EXITED' || st === 'CLOSED' ? 'bg-emerald-100 text-emerald-700' : 'bg-slate-100 text-slate-500';
                  const pnlVal = icPnlMap[String(opp.id)] != null ? Number(icPnlMap[String(opp.id)]) : null;
                  const edge = Number(opp.edgeAfterCosts) || 0;
                  return (
                    <React.Fragment key={opp.id || idx}>
                      <tr onClick={() => setExpandedId(isExp ? null : opp.id)}
                        className={`transition cursor-pointer ${isExp ? 'bg-indigo-50/70 border-l-4 border-indigo-600' : 'hover:bg-slate-50'}`}>
                        <td className="px-2 py-1.5 text-slate-600 font-mono whitespace-nowrap">{fmtTime(opp.scanTime)}</td>
                        <td className="px-2 py-1.5 font-bold text-slate-800">{opp.underlying}</td>
                        <td className="px-2 py-1.5 font-bold text-slate-700">{opp.strike}</td>
                        <td className="px-2 py-1.5 font-mono text-xs text-slate-600 max-w-[200px] truncate" title={opp.action}>{opp.action}</td>
                        <td className={`px-2 py-1.5 text-right font-mono font-bold ${edge >= 0 ? 'text-emerald-600' : 'text-red-500'}`}>{edge >= 0 ? '+' : ''}₹{Math.round(edge)}</td>
                        <td className="px-2 py-1.5 text-center"><span className={`px-1.5 py-0.5 rounded-full text-[10px] font-bold ${stColor}`}>{st}</span></td>
                        <td className="px-2 py-1.5 text-center">
                          <button onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp); }}
                            className="px-2 py-0.5 bg-indigo-600 hover:bg-indigo-700 text-white text-[10px] font-bold rounded shadow-sm">
                            Execute
                          </button>
                        </td>
                      </tr>
                      {isExp && (
                        <tr className="bg-indigo-50/40 border-b border-indigo-100">
                          <td colSpan={7} className="p-3">
                            <div className="bg-white rounded-xl p-3 border border-indigo-200 shadow-md space-y-2">
                              <span className="font-bold text-slate-800 text-xs uppercase block">Iron Condor Details</span>
                              <p className="text-xs font-mono font-bold text-slate-800 bg-slate-50 p-2 rounded-lg border">{opp.legs || opp.action}</p>
                              <div className="grid grid-cols-3 gap-2 text-[10px]">
                                <div>Spot: ₹{Number(opp.spotPrice || 0).toLocaleString('en-IN')}</div>
                                <div>Expiry: {opp.expiryDate}</div>
                                <div>Confidence: {Number(opp.confidence || 0).toFixed(1)}%</div>
                              </div>
                              <div className="flex justify-end pt-1">
                                <button onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp); }} className="px-3 py-1 bg-indigo-600 text-white rounded-lg text-xs font-bold shadow-md">
                                  Submit ({executionBroker})
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
        {totalHistoryPages > 1 && (
          <div className="flex items-center justify-between px-4 py-3 border-t border-slate-200 bg-slate-50">
            <span className="text-xs text-slate-500">Page {histPage + 1} of {totalHistoryPages} ({totalHistory.toLocaleString('en-IN')} signals today)</span>
            <div className="flex gap-1">
              <button disabled={histPage === 0} onClick={() => setHistPage(0)} className="px-2 py-1 bg-white border rounded text-xs font-bold disabled:opacity-40">First</button>
              <button disabled={histPage === 0} onClick={() => setHistPage(histPage - 1)} className="px-2 py-1 bg-white border rounded text-xs font-bold disabled:opacity-40">Prev</button>
              <button disabled={histPage >= totalHistoryPages - 1} onClick={() => setHistPage(histPage + 1)} className="px-2 py-1 bg-white border rounded text-xs font-bold disabled:opacity-40">Next</button>
              <button disabled={histPage >= totalHistoryPages - 1} onClick={() => setHistPage(totalHistoryPages - 1)} className="px-2 py-1 bg-white border rounded text-xs font-bold disabled:opacity-40">Last</button>
            </div>
          </div>
        )}
      </div>
      </>
      )}
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
    refetchInterval: 30000
  });

  const opps = data?.opportunities || [];

  return (
    <div className="space-y-4 w-full">
      <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm">
        <h2 className="text-base font-bold text-slate-800">🔥 Cash Surge Breakout Scanner</h2>
        <p className="text-xs text-slate-500">EOD delivery % and volume surge vs 20-day average, from real NSE bhavcopy data. Directional cash-equity risk, not arbitrage — no validated win rate.</p>
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
                  <th className="px-2 py-2 text-center">Delivery Surge</th>
                  <th className="px-2 py-2 text-center">Volume Surge</th>
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
                        <td className="px-2 py-1.5 text-center font-bold text-indigo-600">{opp.deliverySurgeMultiplier || '—'}</td>
                        <td className="px-2 py-1.5 text-center font-bold text-slate-600">{opp.volumeSurgeMultiplier || '—'}</td>
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
    refetchInterval: 30000
  });

  const opps = data?.opportunities || [];

  return (
    <div className="space-y-4 w-full">
      <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm">
        <h2 className="text-base font-bold text-slate-800">🚀 2-5 Day Cash Swing Momentum Scanner</h2>
        <p className="text-xs text-slate-500">Real RSI(14) 60-68 zone, computed from EOD closes, filtered for sustained delivery accumulation (5d avg &gt;= 20d avg). Directional cash-equity risk — no validated win rate.</p>
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
function CalendarSpreadView({ handleExecuteInline, executionBroker }) {
  const [underlying, setUnderlying] = useState('BANKNIFTY');
  const [expandedId, setExpandedId] = useState(null);

  const { data, isLoading } = useQuery({
    queryKey: ['calendar-scan', underlying],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/calendar/scan', { params: { underlying } });
      return res.data;
    },
    refetchInterval: 30000
  });

  const opps = data?.opportunities || [];

  return (
    <div className="space-y-4 w-full">
      <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className="text-base font-bold text-slate-800">Near vs Far Expiry Calendar Spreads</h2>
          <p className="text-xs text-slate-500">Captures differential theta decay between near-week and far-month option contracts</p>
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
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">Scanning Calendar Time Spreads...</div>
        ) : opps.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">No calendar spread opportunities detected for {underlying}</div>
        ) : (
          <div className="overflow-x-auto w-full">
            <table className="w-full text-xs text-left border-collapse">
              <thead className="bg-slate-50 border-b border-slate-200 font-bold text-slate-600 uppercase">
                <tr>
                  <th className="px-2 py-2">Symbol</th>
                  <th className="px-2 py-2">Type</th>
                  <th className="px-2 py-2">Strike</th>
                  <th className="px-2 py-2 text-right">Near Price</th>
                  <th className="px-2 py-2 text-right">Far Price</th>
                  <th className="px-2 py-2 text-right">Spread</th>
                  <th className="px-2 py-2 text-right text-emerald-600 font-bold">Target Edge (₹)</th>
                  <th className="px-2 py-2 text-center">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {opps.slice(0, 25).map((opp, idx) => {
                  const isExp = expandedId === idx;
                  return (
                    <React.Fragment key={idx}>
                      <tr
                        onClick={() => setExpandedId(isExp ? null : idx)}
                        className={`transition cursor-pointer ${isExp ? 'bg-indigo-50/70 border-l-4 border-indigo-600' : 'hover:bg-slate-50'}`}
                      >
                        <td className="px-2 py-1.5 font-bold text-slate-800">{opp.underlying}</td>
                        <td className="px-2 py-1.5 font-bold text-indigo-700">{opp.optionType}</td>
                        <td className="px-2 py-1.5 font-bold text-slate-700">{opp.strike}</td>
                        <td className="px-2 py-1.5 text-right font-mono text-slate-600">₹{Number(opp.nearPrice || 0).toFixed(1)}</td>
                        <td className="px-2 py-1.5 text-right font-mono text-slate-600">₹{Number(opp.farPrice || 0).toFixed(1)}</td>
                        <td className="px-2 py-1.5 text-right font-mono font-bold text-slate-700">{Number(opp.spread || 0).toFixed(1)}</td>
                        <td className="px-2 py-1.5 text-right font-mono font-bold text-emerald-600">+₹{Math.round(Number(opp.edgeAfterCosts || 0)).toLocaleString('en-IN')}</td>
                        <td className="px-2 py-1.5 text-center">
                          <button
                            onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp); }}
                            className="px-2 py-0.5 bg-indigo-600 hover:bg-indigo-700 text-white text-[10px] font-bold rounded shadow-sm"
                          >
                            ⏳ Execute
                          </button>
                        </td>
                      </tr>

                      {isExp && (
                        <tr className="bg-indigo-50/40 border-b border-indigo-100">
                          <td colSpan={8} className="p-3">
                            <div className="bg-white rounded-xl p-3 border border-indigo-200 shadow-md space-y-2">
                              <span className="font-bold text-slate-800 text-xs uppercase block">Calendar Spread Leg Breakdown:</span>
                              <p className="text-xs font-mono font-bold text-slate-800 bg-slate-50 p-2 rounded-lg border">
                                BUY NEAR ({opp.nearSymbol} @ ₹{opp.nearPrice}) | SELL FAR ({opp.farSymbol} @ ₹{opp.farPrice})
                              </p>
                              <div className="flex justify-end pt-1">
                                <button onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp); }} className="px-3 py-1 bg-indigo-600 text-white rounded-lg text-xs font-bold shadow-md">
                                  ⏳ Submit ({executionBroker})
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

/* PAPER TRADES VIEW — Dedicated view for executed + exited paper trades with P&L */
function PaperTradesView() {
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [underlyingFilter, setUnderlyingFilter] = useState('ALL');
  const [modeFilter, setModeFilter] = useState('ALL');
  const [sortCol, setSortCol] = useState('enteredAt');
  const [sortAsc, setSortAsc] = useState(false);
  const [expandedId, setExpandedId] = useState(null);

  const { data, isLoading } = useQuery({
    queryKey: ['paperTrades', statusFilter, underlyingFilter, modeFilter],
    queryFn: async () => {
      const params = {};
      if (statusFilter !== 'ALL') params.status = statusFilter;
      if (underlyingFilter !== 'ALL') params.underlying = underlyingFilter;
      if (modeFilter !== 'ALL') params.mode = modeFilter;
      const res = await client.get('/option-arbitrage/paper-trades', { params });
      return res.data;
    },
    refetchInterval: 5000,
  });

  const positions = data?.positions || [];
  const totalPnl = data?.totalPnl || 0;
  const openCount = data?.openCount || 0;
  const closedCount = data?.closedCount || 0;
  const failedCount = data?.failedCount || 0;
  const paperCount = data?.paperCount || 0;
  const liveCount = data?.liveCount || 0;

  const sorted = [...positions].sort((a, b) => {
    let va, vb;
    if (sortCol === 'enteredAt') { va = a.enteredAt || ''; vb = b.enteredAt || ''; }
    else if (sortCol === 'underlying') { va = a.underlying || ''; vb = b.underlying || ''; }
    else if (sortCol === 'strike') { va = a.strike || 0; vb = b.strike || 0; }
    else if (sortCol === 'pnl') { va = a.pnl || 0; vb = b.pnl || 0; }
    else if (sortCol === 'status') { va = a.status || ''; vb = b.status || ''; }
    else { va = a[sortCol] || ''; vb = b[sortCol] || ''; }
    if (typeof va === 'string') return sortAsc ? va.localeCompare(vb) : vb.localeCompare(va);
    return sortAsc ? va - vb : vb - va;
  });

  const toggleSort = (col) => { if (sortCol === col) setSortAsc(!sortAsc); else { setSortCol(col); setSortAsc(false); } };
  const sortIcon = (col) => sortCol === col ? (sortAsc ? ' ▲' : ' ▼') : '';

  const statusColor = (s) => {
    if (s === 'OPEN') return 'bg-emerald-100 text-emerald-800 border-emerald-300';
    if (s === 'CLOSED' || s === 'EXITED') return 'bg-slate-200 text-slate-600 border-slate-300';
    if (s === 'FAILED' || s === 'REJECTED') return 'bg-red-100 text-red-800 border-red-300';
    return 'bg-blue-100 text-blue-800 border-blue-300';
  };

  return (
    <div className="space-y-4 w-full">
      {/* Header */}
      <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm">
        <div className="flex items-center justify-between mb-3">
          <div>
            <h2 className="text-base font-bold text-slate-800 flex items-center gap-2">
              📋 Paper Trade / Live Execution
            </h2>
            <p className="text-xs text-slate-500 mt-0.5">All executed positions with entry, exit & P&amp;L</p>
          </div>
          <div className={`px-3 py-1.5 rounded-xl text-sm font-black ${totalPnl >= 0 ? 'bg-emerald-50 text-emerald-700 border border-emerald-200' : 'bg-red-50 text-red-700 border border-red-200'}`}>
            Total P&amp;L: {totalPnl >= 0 ? '+' : ''}₹{totalPnl.toLocaleString('en-IN')}
          </div>
        </div>

        {/* Summary Cards */}
        <div className="grid grid-cols-5 gap-3 mb-3">
          <div className="bg-gradient-to-br from-slate-50 to-slate-100 rounded-xl border border-slate-200 p-3 text-center">
            <div className="text-[10px] font-bold text-slate-500 uppercase">Total Trades</div>
            <div className="text-xl font-black text-slate-800">{positions.length}</div>
          </div>
          <div className="bg-gradient-to-br from-emerald-50 to-emerald-100 rounded-xl border border-emerald-200 p-3 text-center">
            <div className="text-[10px] font-bold text-emerald-600 uppercase">Open</div>
            <div className="text-xl font-black text-emerald-700">{openCount}</div>
          </div>
          <div className="bg-gradient-to-br from-slate-50 to-slate-100 rounded-xl border border-slate-200 p-3 text-center">
            <div className="text-[10px] font-bold text-slate-500 uppercase">Exited</div>
            <div className="text-xl font-black text-slate-600">{closedCount}</div>
          </div>
          <div className="bg-gradient-to-br from-red-50 to-red-100 rounded-xl border border-red-200 p-3 text-center">
            <div className="text-[10px] font-bold text-red-500 uppercase">Failed</div>
            <div className="text-xl font-black text-red-700">{failedCount}</div>
          </div>
          <div className="bg-gradient-to-br from-blue-50 to-blue-100 rounded-xl border border-blue-200 p-3 text-center">
            <div className="text-[10px] font-bold text-blue-500 uppercase">PAPER / LIVE</div>
            <div className="text-lg font-black text-blue-700">{paperCount} / {liveCount}</div>
          </div>
        </div>

        {/* Filters */}
        <div className="flex flex-wrap items-center gap-2">
          <div className="flex items-center gap-1 bg-slate-100 p-1 rounded-xl">
            {['ALL', 'OPEN', 'EXITED', 'FAILED'].map(s => (
              <button key={s} onClick={() => setStatusFilter(s)}
                className={`px-2.5 py-1 rounded-lg text-[10px] font-bold transition ${statusFilter === s ? 'bg-indigo-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
                {s === 'ALL' ? '📋 All' : s === 'OPEN' ? '🟢 Open' : s === 'EXITED' ? '⏹ Exited' : '❌ Failed'}
              </button>
            ))}
          </div>
          <div className="flex items-center gap-1 bg-slate-100 p-1 rounded-xl">
            {['ALL', 'PAPER', 'LIVE'].map(m => (
              <button key={m} onClick={() => setModeFilter(m)}
                className={`px-2.5 py-1 rounded-lg text-[10px] font-bold transition ${modeFilter === m ? 'bg-purple-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
                {m === 'ALL' ? '📋 All' : m === 'PAPER' ? '📝 Paper' : '⚡ Live'}
              </button>
            ))}
          </div>
          <div className="flex items-center gap-1 bg-slate-100 p-1 rounded-xl">
            {['ALL', 'NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'].map(u => (
              <button key={u} onClick={() => setUnderlyingFilter(u)}
                className={`px-2 py-1 rounded-lg text-[10px] font-bold transition ${underlyingFilter === u ? 'bg-amber-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
                {u === 'ALL' ? 'ALL' : u}
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* Table */}
      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm">
        {isLoading ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">Loading paper trades...</div>
        ) : positions.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">No paper trades executed yet. Use the ⚡ Trade button on any signal to execute.</div>
        ) : (
          <div className="overflow-x-auto w-full">
            <table className="w-full text-[11px] text-left border-collapse">
              <thead className="bg-slate-50 border-b border-slate-200 font-bold text-slate-600 uppercase">
                <tr>
                  <th className="px-2 py-2 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('enteredAt')}>Entry Time{sortIcon('enteredAt')}</th>
                  <th className="px-2 py-2">Mode</th>
                  <th className="px-2 py-2 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('underlying')}>Symbol{sortIcon('underlying')}</th>
                  <th className="px-2 py-2 cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('strike')}>Strike{sortIcon('strike')}</th>
                  <th className="px-2 py-2">Action</th>
                  <th className="px-2 py-2 text-right">CE Entry</th>
                  <th className="px-2 py-2 text-right">PE Entry</th>
                  <th className="px-2 py-2 text-right">FUT Entry</th>
                  <th className="px-2 py-2 text-center">Lots</th>
                  <th className="px-2 py-2 text-center cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('status')}>Status{sortIcon('status')}</th>
                  <th className="px-2 py-2 text-right cursor-pointer hover:bg-slate-200 select-none" onClick={() => toggleSort('pnl')}>P&amp;L{sortIcon('pnl')}</th>
                  <th className="px-2 py-2 text-center">Exit Time</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {sorted.map((pos) => {
                  const isExp = expandedId === pos.id;
                  const pnl = pos.pnl || 0;
                  const isPaper = pos.ceOrderId && pos.ceOrderId.startsWith('PAPER');
                  return (
                    <React.Fragment key={pos.id}>
                      <tr onClick={() => setExpandedId(isExp ? null : pos.id)}
                        className={`transition cursor-pointer ${isExp ? 'bg-indigo-50/70 border-l-4 border-indigo-600' : 'hover:bg-slate-50'}`}>
                        <td className="px-2 py-1.5 font-mono text-[10px] text-slate-500">{fmtTime(pos.enteredAt)}</td>
                        <td className="px-2 py-1.5">
                          <span className={`px-1.5 py-0.2 rounded-full text-[9px] font-bold border ${isPaper ? 'bg-purple-50 text-purple-700 border-purple-200' : 'bg-amber-50 text-amber-700 border-amber-200'}`}>
                            {isPaper ? '📝 PAPER' : '⚡ LIVE'}
                          </span>
                        </td>
                        <td className="px-2 py-1.5 font-bold text-slate-800">{pos.underlying}</td>
                        <td className="px-2 py-1.5 font-bold text-slate-700">{pos.strike}</td>
                        <td className="px-2 py-1.5 font-bold text-purple-700 truncate max-w-[100px]">{pos.action}</td>
                        <td className="px-2 py-1.5 text-right font-mono">{pos.ceEntryPrice != null ? Number(pos.ceEntryPrice).toFixed(1) : '--'}</td>
                        <td className="px-2 py-1.5 text-right font-mono">{pos.peEntryPrice != null ? Number(pos.peEntryPrice).toFixed(1) : '--'}</td>
                        <td className="px-2 py-1.5 text-right font-mono">{pos.futEntryPrice != null ? Number(pos.futEntryPrice).toFixed(1) : '--'}</td>
                        <td className="px-2 py-1.5 text-center font-bold">{pos.lots}</td>
                        <td className="px-2 py-1.5 text-center">
                          <span className={`px-1.5 py-0.2 rounded-full text-[9px] font-bold border ${statusColor(pos.status)}`}>
                            {pos.status === 'OPEN' ? '🟢 OPEN' : pos.status === 'CLOSED' || pos.status === 'EXITED' ? '⏹ EXITED' : pos.status === 'FAILED' ? '❌ FAILED' : pos.status === 'REJECTED' ? '🚫 REJECTED' : pos.status}
                          </span>
                        </td>
                        <td className="px-2 py-1.5 text-right font-mono font-bold">
                          {pnl !== 0
                            ? <span className={pnl >= 0 ? 'text-emerald-600' : 'text-red-600'}>{pnl >= 0 ? '+' : ''}₹{Math.round(pnl).toLocaleString('en-IN')}</span>
                            : <span className="text-slate-400">₹0</span>}
                        </td>
                        <td className="px-2 py-1.5 text-center font-mono text-[10px] text-slate-500">{pos.exitedAt ? fmtTime(pos.exitedAt) : '--'}</td>
                      </tr>
                      {(isExp || (pos.status === 'FAILED' || pos.status === 'REJECTED') && pos.errorMessage) && (
                        <tr className="bg-indigo-50/40 border-b border-indigo-100">
                          <td colSpan={12} className="p-3">
                            <div className="bg-white rounded-xl p-3 border border-indigo-200 shadow-md space-y-2">
                              <span className="font-bold text-slate-800 text-xs uppercase block">Position Details:</span>
                              <div className="grid grid-cols-2 md:grid-cols-4 gap-2 text-[10px] font-mono">
                                <div><span className="text-slate-500">CE Symbol:</span> <span className="font-bold">{pos.ceSymbol || '--'}</span></div>
                                <div><span className="text-slate-500">PE Symbol:</span> <span className="font-bold">{pos.peSymbol || '--'}</span></div>
                                <div><span className="text-slate-500">FUT Symbol:</span> <span className="font-bold">{pos.futSymbol || '--'}</span></div>
                                <div><span className="text-slate-500">Lot Size:</span> <span className="font-bold">{pos.lotSize}</span></div>
                                <div><span className="text-slate-500">Target Edge:</span> <span className="font-bold">₹{pos.targetEdge != null ? Math.round(pos.targetEdge) : '--'}</span></div>
                                <div><span className="text-slate-500">Strategy:</span> <span className="font-bold">{pos.strategyType || '--'}</span></div>
                                <div><span className="text-slate-500">CE Exit:</span> <span className="font-bold">{pos.ceExitPrice != null ? Number(pos.ceExitPrice).toFixed(1) : '--'}</span></div>
                                <div><span className="text-slate-500">PE Exit:</span> <span className="font-bold">{pos.peExitPrice != null ? Number(pos.peExitPrice).toFixed(1) : '--'}</span></div>
                                <div><span className="text-slate-500">FUT Exit:</span> <span className="font-bold">{pos.futExitPrice != null ? Number(pos.futExitPrice).toFixed(1) : '--'}</span></div>
                                <div><span className="text-slate-500">Entry Cost:</span> <span className="font-bold">₹{pos.entryCost != null ? Math.round(pos.entryCost) : '--'}</span></div>
                                <div><span className="text-slate-500">Order IDs:</span> <span className="font-bold text-[9px]">{pos.ceOrderId || '--'}</span></div>
                                <div><span className="text-slate-500">Mode:</span> <span className="font-bold">{isPaper ? 'PAPER' : 'LIVE'}</span></div>
                              </div>
                              {pos.errorMessage && (
                                <div className={`text-[10px] font-mono mt-1 p-2 rounded-lg ${(pos.status === 'FAILED' || pos.status === 'REJECTED') ? 'bg-red-50 text-red-700 border border-red-200' : 'text-amber-600'}`}>
                                  <span className="font-bold">Error/Log:</span> {pos.errorMessage}
                                </div>
                              )}
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
/* History for the Candidates (not arbitrage) discovery scan -- Vertical/Butterfly/Condor
   only. These were never persisted before; CandidateSnapshotService now snapshots count +
   top candidate per underlying every 15 min during market hours, and this panel reads it
   back with the same day/week/month/custom range pattern as the arbitrage HistoryView. */
function CandidateHistoryPanel({ strategyType, label }) {
  const [dateRange, setDateRange] = useState('TODAY');
  const [customStartDate, setCustomStartDate] = useState('');
  const [customEndDate, setCustomEndDate] = useState('');
  const [underlyingFilter, setUnderlyingFilter] = useState('ALL');

  const getDateRange = () => {
    const now = new Date();
    const istNow = new Date(now.getTime() + (now.getTimezoneOffset() + 330) * 60000);
    const istDate = istNow.toISOString().split('T')[0];
    switch (dateRange) {
      case 'TODAY': return { start: istDate, end: istDate };
      case 'YESTERDAY': {
        const y = new Date(istNow); y.setDate(y.getDate() - 1);
        return { start: y.toISOString().split('T')[0], end: y.toISOString().split('T')[0] };
      }
      case 'WEEK': {
        const d = new Date(istNow); d.setDate(d.getDate() - 6);
        return { start: d.toISOString().split('T')[0], end: istDate };
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
  const dr = getDateRange();

  const { data, isLoading } = useQuery({
    queryKey: ['candidate-history', strategyType, underlyingFilter, dr.start, dr.end],
    queryFn: async () => {
      const params = { strategyType, startDate: dr.start, endDate: dr.end };
      if (underlyingFilter !== 'ALL') params.underlying = underlyingFilter;
      const res = await client.get('/option-arbitrage/candidate-history', { params });
      return res.data;
    },
    staleTime: 30000,
  });

  const items = data?.items || [];
  const totalCandidatesSeen = items.reduce((s, i) => s + (i.candidateCount || 0), 0);
  const avgPopOverall = items.length > 0
    ? items.reduce((s, i) => s + (i.avgPop || 0), 0) / items.length : null;

  return (
    <div className="space-y-4 w-full">
      <div className="bg-amber-50 border border-amber-300 rounded-2xl p-4 text-xs text-amber-900">
        <p className="font-bold mb-1">🔍 Candidates history — not arbitrage</p>
        <p>Periodic snapshots (every 15 min, market hours) of the Candidates discovery scan — count and the top (highest-POP) candidate per underlying at each snapshot, not every single candidate. Nothing here was ever executed automatically.</p>
      </div>

      <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="text-base font-bold text-slate-800">{label} Candidates History</h2>
            <p className="text-xs text-slate-500">
              <span className="font-bold text-slate-700">{items.length}</span> snapshots,{' '}
              <span className="font-bold text-slate-700">{totalCandidatesSeen.toLocaleString('en-IN')}</span> total candidate-sightings in this range
              {avgPopOverall != null && <> — avg POP <span className="font-bold text-slate-700">{avgPopOverall.toFixed(1)}%</span></>}
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <div className="flex items-center gap-1 bg-slate-100 p-1 rounded-xl">
              {[
                { id: 'TODAY', label: 'Today' },
                { id: 'YESTERDAY', label: 'Yesterday' },
                { id: 'WEEK', label: 'This Week' },
                { id: 'MONTH', label: 'This Month' },
                { id: 'CUSTOM', label: 'Custom' },
              ].map(d => (
                <button key={d.id} onClick={() => setDateRange(d.id)}
                  className={`px-2 py-0.5 rounded-lg text-xs font-bold transition ${dateRange === d.id ? 'bg-amber-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
                  {d.label}
                </button>
              ))}
            </div>
            {dateRange === 'CUSTOM' && (
              <div className="flex items-center gap-1">
                <input type="date" value={customStartDate} onChange={e => setCustomStartDate(e.target.value)} className="bg-white border border-slate-300 rounded-lg px-1.5 py-0.5 text-xs font-mono text-slate-800 outline-none focus:border-amber-500" />
                <span className="text-[10px] text-slate-400">to</span>
                <input type="date" value={customEndDate} onChange={e => setCustomEndDate(e.target.value)} className="bg-white border border-slate-300 rounded-lg px-1.5 py-0.5 text-xs font-mono text-slate-800 outline-none focus:border-amber-500" />
              </div>
            )}
            <div className="flex flex-wrap items-center gap-1 bg-slate-100 p-1 rounded-xl">
              {['ALL', 'NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'].map(u => (
                <button key={u} onClick={() => setUnderlyingFilter(u)}
                  className={`px-2 py-0.5 rounded-lg text-xs font-bold transition ${underlyingFilter === u ? 'bg-purple-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
                  {u}
                </button>
              ))}
            </div>
          </div>
        </div>
      </div>

      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm w-full">
        {isLoading ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">Loading candidate history...</div>
        ) : items.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">No candidate snapshots in this range</div>
        ) : (
          <div className="overflow-x-auto w-full">
            <table className="w-full text-[11px] text-left border-collapse">
              <thead className="bg-slate-50 border-b border-slate-200 font-bold text-slate-600 uppercase">
                <tr>
                  <th className="px-2 py-2">Time</th>
                  <th className="px-2 py-2">Symbol</th>
                  <th className="px-2 py-2 text-right">Count</th>
                  <th className="px-2 py-2 text-right">Avg POP</th>
                  <th className="px-2 py-2">Top Strikes</th>
                  <th className="px-2 py-2 text-right">Top POP</th>
                  <th className="px-2 py-2 text-right">Top Cost</th>
                  <th className="px-2 py-2 text-right">Top Max Loss</th>
                  <th className="px-2 py-2 text-right">Top Max Profit</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {items.map(i => (
                  <tr key={i.id} className="hover:bg-slate-50">
                    <td className="px-2 py-1.5 font-mono text-[10px] text-slate-500">{fmtTime(i.snapshotTime)}</td>
                    <td className="px-2 py-1.5 font-bold text-slate-800">{i.underlying}</td>
                    <td className="px-2 py-1.5 text-right font-mono font-bold">{i.candidateCount}</td>
                    <td className="px-2 py-1.5 text-right font-mono">{i.avgPop != null ? `${i.avgPop}%` : '—'}</td>
                    <td className="px-2 py-1.5 font-mono text-slate-700">{i.topOptionType} {i.topStrikes}</td>
                    <td className="px-2 py-1.5 text-right">
                      {i.topPop != null && (
                        <span className={`px-1.5 py-0.5 rounded-full text-[10px] font-black ${i.topPop >= 60 ? 'bg-emerald-100 text-emerald-700' : i.topPop >= 40 ? 'bg-amber-100 text-amber-700' : 'bg-slate-100 text-slate-500'}`}>
                          {i.topPop}%
                        </span>
                      )}
                    </td>
                    <td className="px-2 py-1.5 text-right font-mono">{i.topCostPerLot != null ? `₹${i.topCostPerLot}` : '—'}</td>
                    <td className="px-2 py-1.5 text-right font-mono text-red-600">{i.topMaxLoss != null ? `₹${Math.round(i.topMaxLoss).toLocaleString('en-IN')}` : '—'}</td>
                    <td className="px-2 py-1.5 text-right font-mono text-emerald-600">{i.topMaxProfit != null ? `₹${Math.round(i.topMaxProfit).toLocaleString('en-IN')}` : '—'}</td>
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

const STRATEGY_LOCK_LABELS = {
  PARITY: '⚡ Bid Parity',
  BOX: '💎 Box Spread',
  VERTICAL: '📐 Vertical Spread',
  BUTTERFLY: '🦋 Butterfly Spread',
  CONDORSPREAD: '🎯 Condor Spread',
  CONDOR: '🛡️ Iron Condor',
};

function HistoryView({ calendarOpportunities, handleExecuteInline, executionBroker, underlyings, lockedStrategy }) {
  const [strategyFilter, setStrategyFilter] = useState(lockedStrategy || 'ALL');
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [underlyingFilter, setUnderlyingFilter] = useState('ALL');
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

  const [pnlQueryTick, setPnlQueryTick] = useState(0);
  useEffect(() => {
    const iv = setInterval(() => setPnlQueryTick(t => t + 1), 10000);
    return () => clearInterval(iv);
  }, []);

  const getDateRange = () => {
    const now = new Date();
    const istNow = new Date(now.getTime() + (now.getTimezoneOffset() + 330) * 60000);
    const istDate = istNow.toISOString().split('T')[0];
    switch (dateRange) {
      case 'TODAY': return { start: istDate, end: istDate };
      case 'YESTERDAY': {
        const y = new Date(istNow); y.setDate(y.getDate() - 1);
        return { start: y.toISOString().split('T')[0], end: y.toISOString().split('T')[0] };
      }
      case 'WEEK': {
        const d = new Date(istNow); d.setDate(d.getDate() - 6);
        return { start: d.toISOString().split('T')[0], end: istDate };
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

  const dr = getDateRange();
  const strategyParam = strategyFilter === 'PARITY' ? 'BID_PARITY' : strategyFilter === 'BOX' ? 'BOX_SPREAD' : strategyFilter === 'VERTICAL' ? 'VERTICAL_SPREAD' : strategyFilter === 'BUTTERFLY' ? 'BUTTERFLY_SPREAD' : strategyFilter === 'CONDORSPREAD' ? 'CONDOR_SPREAD' : strategyFilter === 'CONDOR' ? 'IRON_CONDOR' : null;
  const underlyingParam = underlyingFilter !== 'ALL' ? underlyingFilter : null;

  const { data: histData, isLoading: histLoading } = useQuery({
    queryKey: ['hist-tab', dr.start, dr.end, strategyFilter, underlyingFilter],
    queryFn: async () => {
      const params = { page: 0, size: 50000, startDate: dr.start, endDate: dr.end };
      if (strategyParam) params.strategyType = strategyParam;
      if (underlyingParam) params.underlying = underlyingParam;
      const res = await client.get('/option-arbitrage/history', { params });
      return res.data;
    },
    staleTime: 30000,
  });

  const historyItems = histData?.items || [];

  const { data: livePnlData } = useQuery({
    queryKey: ['hist-pnl', pnlQueryTick],
    queryFn: async () => {
      const relevantIds = historyItems
        .filter(i => { const s = String(i.status || 'RUNNING').toUpperCase(); return s === 'RUNNING' || s === 'OPEN' || s === 'EXECUTING' || s === 'EXITED' || s === 'CLOSED'; })
        .map(i => i.id).slice(0, 500);
      if (relevantIds.length === 0) return {};
      const res = await client.get('/option-arbitrage/history/live-pnl', { params: { ids: relevantIds.join(',') } });
      return res.data || {};
    },
    refetchInterval: 10000,
    staleTime: 8000,
    enabled: historyItems.length > 0,
  });

  const livePnlMap = livePnlData?.pnlMap || {};
  const liveStatusMap = livePnlData?.statusMap || {};
  const liveExitTimeMap = livePnlData?.exitTimeMap || {};

  const handleSort = (col) => {
    if (sortColumn === col) {
      setSortDirection(prev => prev === 'asc' ? 'desc' : 'asc');
    } else {
      setSortColumn(col);
      setSortDirection('desc');
    }
  };

  const filteredItems = useMemo(() => {
    let items = historyItems.map(item => {
      const statusStr = String(item.status || 'RUNNING').toUpperCase();
      const isLive = statusStr === 'RUNNING' || statusStr === 'OPEN' || statusStr === 'DETECTED' || statusStr === 'EXECUTING' || statusStr === 'EXITED' || statusStr === 'CLOSED';
      const idStr = String(item.id);
      let updated = item;

      if (isLive && liveStatusMap[idStr]) {
        updated = { ...updated, status: liveStatusMap[idStr] };
      }
      if (isLive && livePnlMap[idStr] != null) {
        updated = { ...updated, pnlAfterCosts: livePnlMap[idStr] };
      }
      if (liveExitTimeMap[idStr] && !updated.exitTime) {
        updated = { ...updated, exitTime: liveExitTimeMap[idStr] };
      }
      return updated;
    });

    items = items.filter(item => {
      const edge = Number(item.edgeAfterCosts) || Number(item.grossEdge) || 0;
      const statusStr = String(item.status || 'RUNNING').toUpperCase();

      if (edge < minEdgeFilter) return false;

      if (statusFilter === 'RUNNING' && statusStr !== 'RUNNING' && statusStr !== 'OPEN' && statusStr !== 'EXECUTING' && statusStr !== 'DETECTED') return false;
      if (statusFilter === 'DETECTED' && statusStr !== 'DETECTED' && statusStr !== 'NEW') return false;
      if (statusFilter === 'EXITED' && statusStr !== 'EXITED' && statusStr !== 'CLOSED' && statusStr !== 'EXECUTED') return false;
      if (statusFilter === 'MISSED' && statusStr !== 'MISSED' && statusStr !== 'SKIPPED') return false;
      if (statusFilter === 'FAILED' && statusStr !== 'FAILED' && statusStr !== 'REJECTED') return false;

      return true;
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
  }, [historyItems, calendarOpportunities, strategyFilter, statusFilter, underlyingFilter, minEdgeFilter, dateRange, customStartDate, customEndDate, livePnlMap, sortColumn, sortDirection]);

   const countByEdge = (min) => {
     let count = historyItems.filter(item => {
       return (Number(item.edgeAfterCosts) || 0) >= min;
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
            <h2 className="text-base font-bold text-slate-800">
              {lockedStrategy ? `${STRATEGY_LOCK_LABELS[lockedStrategy] || lockedStrategy} History` : 'Arbitrage Signals & Trade Analytics'}
            </h2>
            <p className="text-xs text-slate-500">
              <span className="font-bold text-slate-700">{filteredItems.length.toLocaleString('en-IN')}</span> signals generated
              {' '}in this range — audit scans, track live MTM P&amp;L &amp; exit timestamps
            </p>
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

            {/* Strategy Filters -- hidden when embedded in a strategy's own History sub-tab,
                since the strategy is already implied by context (locked, not a free filter). */}
            {!lockedStrategy && (
              <div className="flex flex-wrap items-center gap-1 bg-slate-100 p-1 rounded-xl">
                {[
                  { id: 'ALL', label: 'All' },
                  { id: 'PARITY', label: '⚡ Parity' },
                  { id: 'BOX', label: '💎 Box' },
                  { id: 'VERTICAL', label: '📐 Vertical' },
                  { id: 'BUTTERFLY', label: '🦋 Butterfly' },
                  { id: 'CONDORSPREAD', label: '🎯 Condor Spread' },
                  { id: 'CALENDAR', label: '⏳ Calendar' },
                  { id: 'CONDOR', label: '🛡️ Iron Condor' },
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
            )}

            {/* Underlying Filter */}
            <div className="flex flex-wrap items-center gap-1 bg-slate-100 p-1 rounded-xl">
              {['ALL', 'NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'].map(u => (
                <button
                  key={u}
                  onClick={() => { setUnderlyingFilter(u); setCurrentPage(1); }}
                  className={`px-2 py-0.5 rounded-lg text-xs font-bold transition ${
                    underlyingFilter === u ? 'bg-purple-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'
                  }`}
                >
                  {u}
                </button>
              ))}
            </div>

            {/* Status Filters */}
            <div className="flex items-center gap-1 bg-slate-100 p-1 rounded-xl">
              {[
                { id: 'ALL', label: 'All' },
                { id: 'RUNNING', label: '🟢 Running' },
                { id: 'DETECTED', label: '🔵 Detected' },
                { id: 'EXITED', label: '🔴 Exited' },
                { id: 'MISSED', label: '⚪ Missed' },
                { id: 'FAILED', label: '❌ Failed' },
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
        {histLoading ? (
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
                  const isRunning = statusStr === 'RUNNING' || statusStr === 'OPEN' || statusStr === 'DETECTED' || statusStr === 'EXECUTING';
                  const isExited = statusStr === 'EXITED' || statusStr === 'CLOSED';
                  const isExpired = statusStr === 'EXPIRED';
                  const mergedStatus = liveStatusMap[String(item.id)] || statusStr;
                  const isMissed = statusStr === 'MISSED' || mergedStatus === 'MISSED';
                  const pnlVal = (() => {
                    // 1. Check live P&L map from backend
                    const livePnl = livePnlMap[String(item.id)];
                    if (livePnl != null) return Number(livePnl);
                    // 2. Check stored P&L on opportunity
                    const storedPnl = item.pnlAfterCosts != null ? Number(item.pnlAfterCosts) : null;
                    if (storedPnl != null && storedPnl !== 0) return storedPnl;
                    // 3. For EXITED/CLOSED without P&L, show 0
                    if (isExited || mergedStatus === 'EXITED' || mergedStatus === 'CLOSED') return 0;
                    // 4. For MISSED (never entered), no P&L
                    if (isMissed) return null;
                    // 5. Running signals without live position = null (shows --)
                    if (isRunning) return null;
                    return 0;
                  })();

                  const signalTimeFormatted = fmtTime(item.scanTime || item.createdAt);
                  const exitTimeFormatted = (() => {
                    // For MISSED: no exit (never entered)
                    if (isMissed) return '';
                    // For EXPIRED: contract expired, use expiry date
                    if (isExpired || mergedStatus === 'EXPIRED') {
                      return item.expiryDate || '';
                    }
                    return fmtTime(item.exitTime);
                  })();

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
                            statusStr === 'RUNNING' || statusStr === 'OPEN' ? 'bg-emerald-100 text-emerald-800 border-emerald-300' :
                            statusStr === 'DETECTED' || statusStr === 'NEW' ? 'bg-blue-100 text-blue-800 border-blue-300' :
                            statusStr === 'MISSED' || statusStr === 'SKIPPED' ? 'bg-slate-100 text-slate-600 border-slate-300' :
                            statusStr === 'FAILED' || statusStr === 'REJECTED' ? 'bg-red-100 text-red-800 border-red-300' :
                            statusStr === 'EXPIRED' ? 'bg-amber-100 text-amber-800 border-amber-300' :
                            statusStr === 'EXITED' || statusStr === 'CLOSED' ? 'bg-slate-200 text-slate-600 border-slate-300' :
                            'bg-blue-100 text-blue-800 border-blue-300'
                          }`}>
                            {statusStr === 'RUNNING' || statusStr === 'OPEN' ? '🟢 RUNNING' :
                             statusStr === 'DETECTED' || statusStr === 'NEW' ? '🔵 DETECTED' :
                             statusStr === 'MISSED' || statusStr === 'SKIPPED' ? '⚪ MISSED' :
                             statusStr === 'FAILED' || statusStr === 'REJECTED' ? '❌ FAILED' :
                             statusStr === 'EXPIRED' ? '⏰ EXPIRED' :
                             statusStr === 'EXITED' || statusStr === 'CLOSED' ? '⏹ EXITED' :
                             `🔴 ${statusStr}`}
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
                              {(['BUTTERFLY_SPREAD', 'BOX_SPREAD', 'VERTICAL_SPREAD', 'CONDOR_SPREAD', 'IRON_CONDOR'].includes(item.strategyType)
                                || ['BUTTERFLY_SPREAD', 'BOX_SPREAD', 'VERTICAL_SPREAD', 'CONDOR_SPREAD', 'IRON_CONDOR'].includes(item.type))
                                && Array.isArray(item.legList) && item.legList.length >= 2 && (
                                <ArbitrageSignalPayoffChart opp={item} />
                              )}
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
export { LivePositionsSection, BrokerPositionsPanel };
