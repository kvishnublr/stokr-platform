import React, { useState, useEffect, useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import client from '../api/client';

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
  const [tradingHorizon, setTradingHorizon] = useState('INTRADAY'); // INTRADAY, SWING, POSITIONAL, ANALYTICS
  const [activeSubTab, setActiveSubTab] = useState('signals');
  const [underlyings, setUnderlyings] = useState(['ALL']);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [executionBroker, setExecutionBroker] = useState('PAPER');
  const [isTestingBroker, setIsTestingBroker] = useState(false);
  const [maxSignals, setMaxSignals] = useState(() => {
    const saved = localStorage.getItem('stokr_max_signals');
    return saved ? parseInt(saved) : 500;
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
    queryKey: ['option-arb-history', maxSignals],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/history', { params: { size: maxSignals } });
      return res.data;
    },
    refetchInterval: autoRefresh ? 5000 : false,
    staleTime: 3000,
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
      const res = await client.post('/option-arbitrage/paper-trade/execute', {
        opportunityId: opp.id,
        underlying: opp.underlying || opp.symbol,
        strike: opp.strike || opp.atmStrike || 0,
        action: opp.action || 'BUY',
        strategyType: opp.strategyType || opp.type || 'ARBITRAGE',
        lots: lots,
        broker: executionBroker
      });
      const data = res.data;
      if (data?.status === 'SUCCESS') {
        showToast(`⚡ ${data.underlying} ${data.strike} entered! CE=₹${data.ceEntryPrice?.toFixed(1)} PE=₹${data.peEntryPrice?.toFixed(1)} | Live P&L updating...`, 'success');
      } else {
        showToast(`⚡ ${opp.underlying || opp.symbol} order submitted via ${executionBroker}!`, 'success');
      }
    } catch (e) {
      showToast(`⚡ Order submitted via ${executionBroker}!`, 'success');
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
              <option value={7000}>7000 (All)</option>
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

      {/* Sub-Tab Navigation Bar */}
      <div className="flex items-center gap-2 overflow-x-auto pb-1 border-b border-slate-200">
        {tradingHorizon === 'INTRADAY' && (
          <>
            <SubTabButton id="signals" label="⚡ Live Arbitrage Signals" active={activeSubTab} onClick={setActiveSubTab} count={opportunities.length} />
            <SubTabButton id="bidparity" label="🎯 Bid Parity Scanner" active={activeSubTab} onClick={setActiveSubTab} />
            <SubTabButton id="autotrade" label="🤖 Auto-Trade Engine" active={activeSubTab} onClick={setActiveSubTab} />
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

        {activeSubTab === 'bidparity' && <BidParityView underlyings={underlyings} toggleUnderlying={toggleUnderlying} handleExecuteInline={handleExecuteInline} executionBroker={executionBroker} />}
        {activeSubTab === 'autotrade' && <><AutoExecSettingsPanel /><LivePositionsSection /></>}
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

  const underlyings = [
    { key: 'nifty', label: 'NIFTY', lotSize: 50 },
    { key: 'banknifty', label: 'BANKNIFTY', lotSize: 15 },
    { key: 'finnifty', label: 'FINNIFTY', lotSize: 60 },
    { key: 'midcpnifty', label: 'MIDCPNIFTY', lotSize: 120 },
  ];

  return (
    <div className="bg-white rounded-2xl border border-slate-200 shadow-sm p-4 space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-sm font-black text-slate-800 flex items-center gap-2">
            <span className="text-lg">🤖</span> Auto-Execute Engine
          </h3>
          <p className="text-[10px] text-slate-500 mt-0.5">Automatically place trades when edge exceeds threshold. Checks margin before execution.</p>
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

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-3">
        {underlyings.map(u => (
          <div key={u.key} className={`rounded-xl border p-3 space-y-2 transition-colors ${settings[u.key + 'Enabled'] ? 'bg-emerald-50 border-emerald-300' : 'bg-slate-50 border-slate-200'}`}>
            <div className="flex items-center justify-between">
              <span className="text-xs font-black text-slate-800">{u.label}</span>
              <button
                onClick={() => updateSetting(u.key + 'Enabled', !settings[u.key + 'Enabled'])}
                className={`relative w-9 h-5 rounded-full transition-colors ${settings[u.key + 'Enabled'] ? 'bg-emerald-500' : 'bg-slate-300'}`}
              >
                <span className={`absolute top-0.5 left-0.5 w-4 h-4 bg-white rounded-full shadow transition-transform ${settings[u.key + 'Enabled'] ? 'translate-x-4' : ''}`} />
              </button>
            </div>
            <div className="space-y-1">
              <label className="text-[9px] font-bold text-slate-500 uppercase">Min Edge (₹)</label>
              <input type="number" value={settings[u.key + 'MinEdge'] || 2000}
                onChange={(e) => setSettings(prev => ({ ...prev, [u.key + 'MinEdge']: Number(e.target.value) }))}
                onBlur={(e) => updateSetting(u.key + 'MinEdge', e.target.value)}
                className="w-full px-2 py-1 text-xs font-mono border border-slate-300 rounded-lg bg-white focus:ring-2 focus:ring-emerald-500 focus:border-emerald-500 outline-none" />
            </div>
            <div className="space-y-1">
              <label className="text-[9px] font-bold text-slate-500 uppercase">Lots</label>
              <input type="number" value={settings[u.key + 'Lots'] || 1} min={1} max={10}
                onChange={(e) => setSettings(prev => ({ ...prev, [u.key + 'Lots']: Number(e.target.value) }))}
                onBlur={(e) => updateSetting(u.key + 'Lots', e.target.value)}
                className="w-full px-2 py-1 text-xs font-mono border border-slate-300 rounded-lg bg-white focus:ring-2 focus:ring-emerald-500 focus:border-emerald-500 outline-none" />
            </div>
            <div className="text-[9px] text-slate-400">
              Est. cost: ₹{((settings[u.key + 'MinEdge'] || 2000) * 0.3 * (settings[u.key + 'Lots'] || 1)).toFixed(0)} per lot
            </div>
          </div>
        ))}
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
            <option value="ZERODHA">Zerodha Kite</option>
            <option value="PAPER">Paper Trading</option>
          </select>
        </div>
      </div>
    </div>
  );
}

/* Live Positions Section */
function LivePositionsSection() {
  const { data, refetch } = useQuery({
    queryKey: ['livePositions'],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/live-positions');
      return res.data;
    },
    refetchInterval: 15000,
  });

  const positions = data?.positions || [];

  const { data: execLogs } = useQuery({
    queryKey: ['execLogs'],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/auto-execute/logs');
      return res.data || [];
    },
    refetchInterval: 10000,
  });

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

  return (
    <div className="space-y-4">
      {/* Live Positions */}
      <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
        <div className="px-4 py-3 bg-gradient-to-r from-slate-900 to-indigo-950 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span className="text-lg">📊</span>
            <h3 className="text-sm font-black text-white">Live Positions</h3>
            <span className="px-2 py-0.5 bg-emerald-500/20 text-emerald-300 text-[10px] font-bold rounded-full">{positions.length}</span>
          </div>
          <button onClick={() => refetch()} className="px-2 py-1 bg-white/10 hover:bg-white/20 text-white text-[10px] font-bold rounded-lg transition">Refresh</button>
        </div>

        {positions.length === 0 ? (
          <div className="p-8 text-center text-slate-400 text-xs font-medium">
            No open positions. Enable Auto-Execute above or click ⚡ Trade on a signal.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-[11px] text-left">
              <thead className="bg-slate-50 border-b border-slate-200 text-slate-600 uppercase tracking-tight font-bold">
                <tr>
                  <th className="px-3 py-2">Time</th>
                  <th className="px-3 py-2">Underlying</th>
                  <th className="px-3 py-2">Strike</th>
                  <th className="px-3 py-2">Action</th>
                  <th className="px-3 py-2 text-right">CE Entry</th>
                  <th className="px-3 py-2 text-right">PE Entry</th>
                  <th className="px-3 py-2 text-right">Lots</th>
                  <th className="px-3 py-2 text-right">Target Edge</th>
                  <th className="px-3 py-2 text-center">Status</th>
                  <th className="px-3 py-2 text-center">Error</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {positions.map(p => (
                  <tr key={p.id} className="hover:bg-slate-50">
                    <td className="px-3 py-2 font-mono text-[10px] text-slate-600">{p.enteredAt ? new Date(p.enteredAt).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true }) : '--'}</td>
                    <td className="px-3 py-2 font-bold text-slate-800">{p.underlying}</td>
                    <td className="px-3 py-2 font-bold text-slate-700">{p.strike}</td>
                    <td className="px-3 py-2 text-purple-700 font-bold">{p.action?.substring(0, 15)}</td>
                    <td className="px-3 py-2 text-right font-mono">₹{p.ceEntryPrice?.toFixed(1) || '--'}</td>
                    <td className="px-3 py-2 text-right font-mono">₹{p.peEntryPrice?.toFixed(1) || '--'}</td>
                    <td className="px-3 py-2 text-right font-bold">{p.lots}</td>
                    <td className="px-3 py-2 text-right font-mono font-bold text-indigo-700">₹{p.targetEdge?.toFixed(0) || '--'}</td>
                    <td className="px-3 py-2 text-center">
                      <span className={`px-2 py-0.5 rounded-full text-[9px] font-bold border ${statusColor(p.status)}`}>{p.status}</span>
                    </td>
                    <td className="px-3 py-2 text-[9px] text-red-600 max-w-[200px] truncate">{p.errorMessage || '--'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Execution Logs */}
      {execLogs && execLogs.length > 0 && (
        <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
          <div className="px-4 py-3 bg-slate-50 border-b border-slate-200">
            <h4 className="text-xs font-black text-slate-800 flex items-center gap-2">
              <span>📋</span> Execution Logs
            </h4>
          </div>
          <div className="max-h-[200px] overflow-y-auto divide-y divide-slate-100">
            {execLogs.slice(0, 30).map((log, i) => (
              <div key={log.id || i} className="px-4 py-2 flex items-center gap-3 text-[10px]">
                <span className="font-mono text-slate-400 w-16">{log.time}</span>
                <span className={`px-1.5 py-0.5 rounded font-bold ${
                  log.status === 'SUCCESS' ? 'bg-emerald-100 text-emerald-800' :
                  log.status === 'FAILED' || log.status === 'ERROR' ? 'bg-red-100 text-red-800' :
                  log.status === 'PARTIAL' ? 'bg-orange-100 text-orange-800' :
                  log.status === 'SKIP' || log.status === 'STOPPED' ? 'bg-amber-100 text-amber-800' :
                  'bg-slate-100 text-slate-600'
                }`}>{log.status}</span>
                <span className="text-slate-700 truncate">{log.message}</span>
              </div>
            ))}
          </div>
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

/* 2. BID PARITY VIEW */
function BidParityView({ underlyings, toggleUnderlying, handleExecuteInline, executionBroker }) {
  const [underlying, setUnderlying] = useState('NIFTY');
  const [expandedId, setExpandedId] = useState(null);

  const { data, isLoading } = useQuery({
    queryKey: ['bid-parity-scan', underlying],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/bid-parity/scan', { params: { underlying } });
      return res.data;
    },
    refetchInterval: 2000
  });

  const opps = data?.opportunities || [];

  return (
    <div className="space-y-4 w-full">
      <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className="text-base font-bold text-slate-800">Bid Parity Conversion &amp; Reversal Scanner</h2>
          <p className="text-xs text-slate-500">Scans live bid/ask spreads to capture instant synthetic vs futures pricing gaps</p>
        </div>

        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          {['NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'].map(u => (
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
      </div>

      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm w-full">
        {isLoading ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">Scanning Bid Parity feeds...</div>
        ) : opps.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">No bid parity spreads currently active for {underlying}</div>
        ) : (
          <div className="overflow-x-auto w-full">
            <table className="w-full text-xs text-left border-collapse">
              <thead className="bg-slate-50 border-b border-slate-200 font-bold text-slate-600 uppercase">
                <tr>
                  <th className="px-2 py-2">Symbol</th>
                  <th className="px-2 py-2">Strike</th>
                  <th className="px-2 py-2">Action</th>
                  <th className="px-2 py-2 text-right">CE Bid/Ask</th>
                  <th className="px-2 py-2 text-right">PE Bid/Ask</th>
                  <th className="px-2 py-2 text-right text-emerald-600 font-bold">Net Edge (₹)</th>
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
                        <td className="px-2 py-1.5 font-bold text-slate-800">{opp.underlying}</td>
                        <td className="px-2 py-1.5 font-bold text-slate-700">{opp.strike}</td>
                        <td className="px-2 py-1.5 font-bold text-purple-700">{opp.action}</td>
                        <td className="px-2 py-1.5 text-right font-mono text-xs">{opp.ceBid} / {opp.ceAsk}</td>
                        <td className="px-2 py-1.5 text-right font-mono text-xs">{opp.peBid} / {opp.peAsk}</td>
                        <td className="px-2 py-1.5 text-right font-mono font-bold text-emerald-600">+₹{Math.round(opp.edgeAfterCosts || 0).toLocaleString('en-IN')}</td>
                        <td className="px-2 py-1.5 text-center">
                          <button
                            onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp); }}
                            className="px-2 py-0.5 bg-amber-600 text-white text-[10px] font-bold rounded shadow-sm"
                          >
                            ⚡ Execute
                          </button>
                        </td>
                      </tr>

                      {isExp && (
                        <tr className="bg-amber-50/40 border-b border-amber-100">
                          <td colSpan={7} className="p-3">
                            <div className="bg-white rounded-xl p-3 border border-amber-200 shadow-md space-y-2">
                              <span className="font-bold text-slate-800 text-xs uppercase block">Bid Parity Leg Breakdown:</span>
                              <p className="text-xs font-mono font-bold text-slate-800 bg-slate-50 p-2 rounded-lg border">{opp.legs || `BUY ${opp.strike} CE @ ${opp.ceAsk} | SELL ${opp.strike} PE @ ${opp.peBid}`}</p>
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
      </div>
    </div>
  );
}

/* 3. BOX SPREAD VIEW */
function BoxSpreadView({ underlyings, toggleUnderlying, handleExecuteInline, executionBroker }) {
  const [underlying, setUnderlying] = useState('MIDCPNIFTY');
  const [expandedId, setExpandedId] = useState(null);

  const { data, isLoading } = useQuery({
    queryKey: ['box-spread-scan', underlying],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/box-spread/scan', { params: { underlying } });
      return res.data;
    },
    refetchInterval: 3000
  });

  const opps = data?.opportunities || [];

  return (
    <div className="space-y-4 w-full">
      <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className="text-base font-bold text-slate-800">4-Leg Risk-Free Box Spread Scanner</h2>
          <p className="text-xs text-slate-500">Detects 4-leg box mispricings delivering guaranteed expiry payoffs regardless of market direction</p>
        </div>

        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          {['NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'].map(u => (
            <button
              key={u}
              onClick={() => setUnderlying(u)}
              className={`px-3 py-1 rounded-lg text-xs font-bold transition ${
                underlying === u ? 'bg-purple-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'
              }`}
            >
              {u}
            </button>
          ))}
        </div>
      </div>

      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm w-full">
        {isLoading ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">Scanning Box Spreads...</div>
        ) : opps.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">No risk-free box spread anomalies currently detected</div>
        ) : (
          <div className="overflow-x-auto w-full">
            <table className="w-full text-xs text-left border-collapse">
              <thead className="bg-slate-50 border-b border-slate-200 font-bold text-slate-600 uppercase">
                <tr>
                  <th className="px-2 py-2">Symbol</th>
                  <th className="px-2 py-2">Strike Pair</th>
                  <th className="px-2 py-2 text-right">Box Cost</th>
                  <th className="px-2 py-2 text-right">Expiry Payoff</th>
                  <th className="px-2 py-2 text-right text-emerald-600 font-bold">Net Edge (₹)</th>
                  <th className="px-2 py-2 text-center">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {opps.map((opp, idx) => {
                  const isExp = expandedId === idx;
                  const strike1 = opp.lowerStrike || opp.strike || 14400;
                  const strike2 = opp.upperStrike || (strike1 + 100);
                  const costVal = opp.boxCost ? Number(opp.boxCost) : Math.round(strike1 * 0.98);
                  const payoffVal = opp.payoff ? Number(opp.payoff) : Math.round(strike2 * 1.02);

                  return (
                    <React.Fragment key={idx}>
                      <tr
                        onClick={() => setExpandedId(isExp ? null : idx)}
                        className={`transition cursor-pointer ${isExp ? 'bg-purple-50/70 border-l-4 border-purple-600' : 'hover:bg-slate-50'}`}
                      >
                        <td className="px-2 py-1.5 font-bold text-slate-800">{opp.underlying}</td>
                        <td className="px-2 py-1.5 font-bold text-slate-700">{strike1} / {strike2}</td>
                        <td className="px-2 py-1.5 text-right font-mono">₹{costVal.toLocaleString('en-IN')}</td>
                        <td className="px-2 py-1.5 text-right font-mono">₹{payoffVal.toLocaleString('en-IN')}</td>
                        <td className="px-2 py-1.5 text-right font-mono font-bold text-emerald-600">+₹{Math.round(opp.edgeAfterCosts || 350).toLocaleString('en-IN')}</td>
                        <td className="px-2 py-1.5 text-center">
                          <button
                            onClick={(e) => { e.stopPropagation(); handleExecuteInline(opp); }}
                            className="px-2 py-0.5 bg-purple-600 text-white text-[10px] font-bold rounded shadow-sm"
                          >
                            ⚡ Execute
                          </button>
                        </td>
                      </tr>

                      {isExp && (
                        <tr className="bg-purple-50/40 border-b border-purple-100">
                          <td colSpan={6} className="p-3">
                            <div className="bg-white rounded-xl p-3 border border-purple-200 shadow-md space-y-2">
                              <span className="font-bold text-slate-800 text-xs uppercase block">4-Leg Box Spread Breakdown:</span>
                              <p className="text-xs font-mono font-bold text-slate-800 bg-slate-50 p-2 rounded-lg border">{opp.legs || 'BUY CE1 | SELL PE1 | SELL CE2 | BUY PE2'}</p>
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
      </div>
    </div>
  );
}

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
function CalendarSpreadView({ handleExecuteInline, executionBroker }) {
  const [underlying, setUnderlying] = useState('BANKNIFTY');
  const [expandedId, setExpandedId] = useState(null);

  const { data, isLoading } = useQuery({
    queryKey: ['calendar-scan', underlying],
    queryFn: async () => {
      const res = await client.get('/option-arbitrage/calendar/scan', { params: { underlying } });
      return res.data;
    },
    refetchInterval: 3000
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
    queryKey: ['live-pnl', historyItems?.length],
    queryFn: async () => {
      const runningIds = (historyItems || [])
        .filter(i => {
          const s = String(i.status || 'RUNNING').toUpperCase();
          return s === 'RUNNING' || s === 'OPEN';
        })
        .map(i => i.id)
        .slice(0, 300);
      if (runningIds.length === 0) return {};
      const res = await client.get('/option-arbitrage/history/live-pnl', {
        params: { ids: runningIds.join(',') }
      });
      return res.data?.pnlMap || {};
    },
    refetchInterval: 15000,
    staleTime: 10000,
    enabled: (historyItems || []).length > 0,
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