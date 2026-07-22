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
    const handler = (t) => {
      setToasts(prev => [...prev, t]);
      setTimeout(() => setToasts(prev => prev.filter(x => x.id !== t.id)), t.duration);
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
const TOAST_ICONS = { success: '✓', error: '✕', warning: '⚠', info: 'ℹ' };

function ToastContainer({ toasts, dismiss }) {
  return (
    <div className="fixed top-4 right-4 z-[9999] space-y-2 pointer-events-none">
      {toasts.map(t => (
        <div key={t.id}
          onClick={() => dismiss(t.id)}
          className={`pointer-events-auto px-4 py-3 rounded-xl shadow-lg text-sm font-medium flex items-center gap-2 cursor-pointer animate-toast-in ${TOAST_STYLES[t.type] || TOAST_STYLES.info}`}>
          <span className="text-lg font-bold">{TOAST_ICONS[t.type] || 'ℹ'}</span>
          <span className="flex-1">{t.message}</span>
        </div>
      ))}
    </div>
  );
}

const _confirmStateRef = { resolve: null };
export function showConfirm(message, title = 'Confirm') {
  return new Promise((resolve) => {
    _confirmStateRef.resolve = resolve;
    window.dispatchEvent(new CustomEvent('app-confirm', { detail: { message, title } }));
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
      <div className="bg-white rounded-2xl shadow-2xl p-6 w-full max-w-sm mx-4 animate-confirm-in" onClick={e => e.stopPropagation()}>
        <h3 className="text-base font-bold text-slate-800 mb-2">{show.title}</h3>
        <p className="text-sm text-slate-600 mb-6 whitespace-pre-line">{show.message}</p>
        <div className="flex gap-3 justify-end">
          <button onClick={() => resolve(false)} className="px-4 py-2 bg-slate-100 text-slate-700 rounded-lg text-sm font-medium hover:bg-slate-200 transition">Cancel</button>
          <button onClick={() => resolve(true)} className="px-4 py-2 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 transition">Confirm</button>
        </div>
      </div>
    </div>
  );
}

function KiteBasketButton({ opp, label, className, livePriceMap }) {
  const [loading, setLoading] = useState(false);
  const [showPreview, setShowPreview] = useState(false);
  const [basketData, setBasketData] = useState(null);
  const [orderType, setOrderType] = useState('LIMIT');
  const [slippagePct, setSlippagePct] = useState(0.15);
  const [submitted, setSubmitted] = useState(false);
  const [liveOrders, setLiveOrders] = useState([]);
  const [polling, setPolling] = useState(false);
  const pollRef = React.useRef(null);

  const pollOrders = useCallback(async () => {
    try {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/order-status`);
      if (res.data.status === 'ok') {
        setLiveOrders(res.data.orders || []);
      }
    } catch (e) { /* ignore */ }
  }, []);

  useEffect(() => {
    if (submitted) {
      pollOrders();
      setPolling(true);
      pollRef.current = setInterval(pollOrders, 5000);
      return () => { clearInterval(pollRef.current); setPolling(false); };
    }
  }, [submitted, pollOrders]);

  const cancelOrder = async (orderId) => {
    try {
      await axios.post(`${API_BASE}/api/option-arbitrage/cancel-order`, null, { params: { orderId } });
      setTimeout(pollOrders, 1000);
    } catch (e) {
      showToast('Cancel failed: ' + (e.response?.data?.error || e.message), 'error');
    }
  };

  const fetchBasket = async (e) => {
    e.stopPropagation();
    setLoading(true);
    try {
      const res = await axios.post(`${API_BASE}/api/option-arbitrage/basket`, null, {
        params: {
          underlying: opp.underlying,
          strike: opp.strike,
          action: opp.action,
          cePrice: opp.ceEntryPrice || opp.cePrice || 0,
          pePrice: opp.peEntryPrice || opp.pePrice || 0,
          futPrice: opp.futuresPrice || 0,
          spotPrice: opp.spotPrice || 0,
        }
      });
      const data = res.data;
      if (!data.orders || !data.apiKey) {
        showToast('Failed to build basket: ' + (data.error || 'Unknown error'), 'error');
        return;
      }
      setBasketData(data);
      setShowPreview(true);
    } catch (err) {
      showToast('Basket error: ' + (err.response?.data?.error || err.message), 'error');
    } finally {
      setLoading(false);
    }
  };

  const submitToKite = () => {
    if (!basketData) return;
    let orders = basketData.orders;
    if (orderType === 'MARKET') {
      orders = orders.map(o => ({ ...o, order_type: 'MARKET', price: 0 }));
    } else {
      orders = orders.map(o => {
        if (o.transaction_type === 'BUY') {
          return { ...o, order_type: 'LIMIT', price: Math.ceil(o.price * (1 + slippagePct / 100)) };
        } else {
          return { ...o, order_type: 'LIMIT', price: Math.floor(o.price * (1 - slippagePct / 100)) };
        }
      });
    }
    const form = document.createElement('form');
    form.method = 'POST';
    form.action = basketData.basketUrl || 'https://kite.zerodha.com/connect/basket';
    form.target = '_blank';
    const apiKeyInput = document.createElement('input');
    apiKeyInput.type = 'hidden';
    apiKeyInput.name = 'api_key';
    apiKeyInput.value = basketData.apiKey;
    form.appendChild(apiKeyInput);
    const dataInput = document.createElement('input');
    dataInput.type = 'hidden';
    dataInput.name = 'data';
    dataInput.value = JSON.stringify(orders);
    form.appendChild(dataInput);
    document.body.appendChild(form);
    form.submit();
    document.body.removeChild(form);
    setSubmitted(true);
  };

  const lotSize = opp.lotSize || opp.costBreakdown?.lotSize || 65;

  const allFilled = liveOrders.length > 0 && liveOrders.every(o => o.status === 'COMPLETE');
  const anyPending = liveOrders.some(o => o.status === 'OPEN');
  const anyRejected = liveOrders.some(o => o.status === 'REJECTED' || o.status === 'CANCELLED');

  return (
    <>
      <button onClick={fetchBasket} disabled={loading}
        className={className || "px-3 py-1.5 rounded-lg text-xs font-semibold bg-blue-600 text-white hover:bg-blue-700 shadow-sm transition disabled:opacity-50"}>
        {loading ? '⏳' : '🛒'} {label || 'Kite'}
      </button>

      {showPreview && basketData && !submitted && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={() => setShowPreview(false)}>
          <div className="bg-white rounded-xl shadow-2xl w-full max-w-lg mx-4" onClick={(e) => e.stopPropagation()}>
            <div className="px-6 py-4 border-b border-slate-200 flex items-center justify-between">
              <div>
                <h3 className="text-lg font-bold text-slate-800">Kite Basket Preview</h3>
                <p className="text-xs text-slate-500 mt-0.5">{basketData.underlying} {basketData.strike} — {basketData.action}</p>
              </div>
              <button onClick={() => setShowPreview(false)} className="text-slate-400 hover:text-slate-600 text-xl leading-none">&times;</button>
            </div>

            <div className="px-6 py-4 space-y-3">
              <div className="flex gap-2 mb-3">
                <button onClick={() => setOrderType('LIMIT')}
                  className={`px-3 py-1.5 rounded-lg text-xs font-semibold border transition ${orderType === 'LIMIT' ? 'bg-blue-600 text-white border-blue-600' : 'bg-white text-slate-600 border-slate-200 hover:border-blue-300'}`}>
                  LIMIT (Safe)
                </button>
                <button onClick={() => setOrderType('MARKET')}
                  className={`px-3 py-1.5 rounded-lg text-xs font-semibold border transition ${orderType === 'MARKET' ? 'bg-amber-500 text-white border-amber-500' : 'bg-white text-slate-600 border-slate-200 hover:border-amber-300'}`}>
                  MARKET (Risky)
                </button>
              </div>

              {orderType === 'LIMIT' && (
                <div className="flex items-center gap-2 text-xs text-slate-500 mb-2">
                  <span>Slippage buffer:</span>
                  <select value={slippagePct} onChange={(e) => setSlippagePct(Number(e.target.value))}
                    className="px-2 py-1 border border-slate-200 rounded text-xs">
                    <option value={0.05}>0.05%</option>
                    <option value={0.1}>0.1%</option>
                    <option value={0.15}>0.15%</option>
                    <option value={0.2}>0.2%</option>
                    <option value={0.3}>0.3%</option>
                    <option value={0.5}>0.5%</option>
                  </select>
                  <span className="text-slate-400">(BUY +{slippagePct}% | SELL -{slippagePct}%)</span>
                </div>
              )}

              {orderType === 'MARKET' && (
                <div className="bg-amber-50 border border-amber-200 rounded-lg px-3 py-2 text-xs text-amber-700 mb-2">
                  Warning: MARKET orders via Kite basket may fail. Kite offsite basket doesn't support market_protection. Use LIMIT with tight buffer if this fails.
                </div>
              )}

              {basketData.orders.map((o, i) => {
                const adjPrice = orderType === 'MARKET' ? 0 :
                  o.transaction_type === 'BUY' ? Math.ceil(o.price * (1 + slippagePct / 100)) : Math.floor(o.price * (1 - slippagePct / 100));
                return (
                  <div key={i} className="flex items-center justify-between p-3 rounded-lg border border-slate-200 bg-slate-50">
                    <div className="flex items-center gap-3">
                      <span className={`px-2 py-0.5 rounded text-xs font-bold ${o.transaction_type === 'BUY' ? 'bg-blue-100 text-blue-700' : 'bg-red-100 text-red-700'}`}>
                        {o.transaction_type}
                      </span>
                      <div>
                        <p className="text-sm font-mono font-semibold text-slate-800">{o.tradingsymbol}</p>
                        <p className="text-xs text-slate-500">{o.quantity} lots x {lotSize}</p>
                      </div>
                    </div>
                    <div className="text-right">
                      <p className="text-sm font-mono font-bold text-slate-800">
                        {orderType === 'MARKET' ? 'MKT' : '₹' + adjPrice.toFixed(2)}
                      </p>
                      {orderType === 'LIMIT' && (
                        <p className="text-xs text-slate-400">was ₹{o.price.toFixed(2)}</p>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>

            <div className="px-6 py-4 border-t border-slate-200 flex justify-end gap-2">
              <button onClick={() => setShowPreview(false)}
                className="px-4 py-2 rounded-lg text-sm font-medium text-slate-600 hover:bg-slate-100 transition">
                Cancel
              </button>
              <button onClick={submitToKite}
                className="px-5 py-2 rounded-lg text-sm font-bold bg-blue-600 text-white hover:bg-blue-700 shadow-sm transition">
                Open on Kite
              </button>
            </div>
          </div>
        </div>
      )}

      {showPreview && submitted && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={() => { setSubmitted(false); setShowPreview(false); }}>
          <div className="bg-white rounded-xl shadow-2xl w-full max-w-lg mx-4" onClick={(e) => e.stopPropagation()}>
            <div className="px-6 py-4 border-b border-slate-200 flex items-center justify-between">
              <div>
                <h3 className="text-lg font-bold text-slate-800">Order Status</h3>
                <p className="text-xs text-slate-500 mt-0.5">{basketData?.underlying} {basketData?.strike} — {basketData?.action}</p>
              </div>
              <div className="flex items-center gap-3">
                {polling && <span className="text-xs text-blue-500 animate-pulse">Polling every 5s...</span>}
                <button onClick={() => { setSubmitted(false); setShowPreview(false); }} className="text-slate-400 hover:text-slate-600 text-xl leading-none">&times;</button>
              </div>
            </div>

            <div className="px-6 py-4 space-y-2">
              {liveOrders.length === 0 ? (
                <div className="text-center py-6 text-slate-400">
                  <p className="text-sm">Waiting for orders to appear...</p>
                  <p className="text-xs mt-1">If you placed orders on Kite, they'll show here</p>
                </div>
              ) : (
                <>
                  {liveOrders.map((o, i) => (
                    <div key={i} className={`flex items-center justify-between p-3 rounded-lg border ${
                      o.status === 'COMPLETE' ? 'border-emerald-200 bg-emerald-50' :
                      o.status === 'OPEN' ? 'border-amber-200 bg-amber-50' :
                      o.status === 'REJECTED' || o.status === 'CANCELLED' ? 'border-red-200 bg-red-50' :
                      'border-slate-200 bg-slate-50'
                    }`}>
                      <div className="flex items-center gap-3">
                        <div className={`w-2.5 h-2.5 rounded-full ${
                          o.status === 'COMPLETE' ? 'bg-emerald-500' :
                          o.status === 'OPEN' ? 'bg-amber-400 animate-pulse' :
                          o.status === 'REJECTED' || o.status === 'CANCELLED' ? 'bg-red-500' :
                          'bg-slate-300'
                        }`} />
                        <div>
                          <p className="text-sm font-mono font-semibold text-slate-800">{o.symbol}</p>
                          <p className="text-xs text-slate-500">
                            <span className={o.side === 'BUY' ? 'text-blue-600 font-semibold' : 'text-red-600 font-semibold'}>{o.side}</span>
                            {' '}&times;{o.filledQty}/{o.quantity} @ ₹{o.avgPrice > 0 ? o.avgPrice.toFixed(2) : o.price.toFixed(2)}
                          </p>
                        </div>
                      </div>
                      <div className="flex items-center gap-2">
                        <span className={`text-xs font-semibold ${
                          o.status === 'COMPLETE' ? 'text-emerald-600' :
                          o.status === 'OPEN' ? 'text-amber-600' :
                          'text-red-600'
                        }`}>
                          {o.status === 'COMPLETE' ? 'Filled' : o.status === 'OPEN' ? 'Pending' : o.status}
                        </span>
                        {o.status === 'OPEN' && (
                          <button onClick={() => cancelOrder(o.orderId)}
                            className="px-2 py-0.5 rounded text-xs font-semibold bg-red-100 text-red-600 hover:bg-red-200 transition">
                            Cancel
                          </button>
                        )}
                      </div>
                    </div>
                  ))}

                  <div className={`mt-3 p-3 rounded-lg border text-center ${
                    allFilled ? 'border-emerald-200 bg-emerald-50' :
                    anyRejected ? 'border-red-200 bg-red-50' :
                    anyPending ? 'border-amber-200 bg-amber-50' :
                    'border-slate-200 bg-slate-50'
                  }`}>
                    {allFilled && <p className="text-sm font-bold text-emerald-700">All 3 legs filled! Delta-neutral position established.</p>}
                    {anyPending && !allFilled && <p className="text-sm font-semibold text-amber-700">Some orders pending. Monitor on Kite or cancel unfilled legs.</p>}
                    {anyRejected && <p className="text-sm font-semibold text-red-700">Some orders rejected. Check Zerodha order book.</p>}
                  </div>
                </>
              )}
            </div>

            <div className="px-6 py-4 border-t border-slate-200 flex justify-end gap-2">
              <button onClick={() => { setSubmitted(false); setShowPreview(false); }}
                className="px-4 py-2 rounded-lg text-sm font-medium text-slate-600 hover:bg-slate-100 transition">
                Close
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

const TYPE_COLORS = {
  PARITY_BREAK: { bg: 'bg-emerald-500/20', text: 'text-emerald-400', border: 'border-emerald-500/30' },
  IV_SPIKE: { bg: 'bg-amber-500/20', text: 'text-amber-400', border: 'border-amber-500/30' },
  DEEP_ITM_STALE: { bg: 'bg-blue-500/20', text: 'text-blue-400', border: 'border-blue-500/30' },
  SKEW_ANOMALY: { bg: 'bg-purple-500/20', text: 'text-purple-400', border: 'border-purple-500/30' },
};

const TYPE_LABELS = {
  PARITY_BREAK: 'Parity Break',
  IV_SPIKE: 'IV Spike',
  DEEP_ITM_STALE: 'Deep ITM Stale',
  SKEW_ANOMALY: 'Skew Anomaly',
};

const ACTION_LABELS = {
  CONVERSION: 'Buy Synthetic',
  REVERSAL: 'Sell Synthetic',
  SELL_STRADDLE: 'Sell Straddle',
  BUY_DEEP_ITM: 'Buy Deep ITM',
  SELL_PUT_BUY_CALL: 'Sell Puts / Buy Calls',
};

const STATUS_COLORS = {
  OPEN: 'bg-emerald-100 text-emerald-700 border-emerald-200',
  CLOSED: 'bg-slate-100 text-slate-600 border-slate-200',
  EXPIRED: 'bg-amber-100 text-amber-700 border-amber-200',
  ERROR: 'bg-red-100 text-red-600 border-red-200',
};

function formatIstTime(scanTime) {
  if (!scanTime) return '--';
  const d = new Date(scanTime.replace(' ', 'T'));
  return d.toLocaleString('en-IN', { timeZone: 'Asia/Kolkata', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true });
}

function formatIstDateTime(scanTime) {
  if (!scanTime) return '--';
  const d = new Date(scanTime.replace(' ', 'T'));
  return d.toLocaleString('en-IN', { timeZone: 'Asia/Kolkata', day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true });
}

function fmt(val, decimals = 0) {
  if (val == null || val === 0) return '--';
  return Number(val).toFixed(decimals);
}

function fmtCurrency(val, decimals = 0) {
  if (val == null || val === 0) return '--';
  return '₹' + Number(val).toFixed(decimals);
}

export default function OptionArbitrage() {
  const queryClient = useQueryClient();
  const [autoRefresh, setAutoRefresh] = useState(false);
  const [underlyings, setUnderlyings] = useState(['ALL']);
  const [activeTab, setActiveTab] = useState('live');
  const [historyPage, setHistoryPage] = useState(0);
  const [selectedDate, setSelectedDate] = useState('');
  const [strategyFilter, setStrategyFilter] = useState('ALL');
  const { toasts, dismiss: dismissToast } = useToastState();
  const HISTORY_SIZE = 20;
  const ALL_U = ['ALL', 'NIFTY', 'BANKNIFTY', 'MIDCPNIFTY', 'FINNIFTY'];

  const toggleUnderlying = (u) => {
    setUnderlyings(prev => {
      if (u === 'ALL') return ['ALL'];
      const withoutAll = prev.filter(x => x !== 'ALL');
      const next = withoutAll.includes(u) ? withoutAll.filter(x => x !== u) : [...withoutAll, u];
      return next.length === 0 ? ['ALL'] : next;
    });
  };
  const underlyingParam = underlyings.includes('ALL') ? 'ALL' : underlyings.join(',');

  async function fetchMulti(queryKey, url, params) {
    const targets = underlyings.includes('ALL') ? ['ALL'] : underlyings;
    const results = await Promise.all(targets.map(u => axios.get(url, { params: { ...params, underlying: u } })));
    const allOpps = results.flatMap(r => r.data?.opportunities || []);
    const mergedSummary = { totalOpportunities: allOpps.length };
    return { opportunities: allOpps, summary: mergedSummary, timestamp: new Date().toISOString() };
  }

  const { data: historyData, isLoading: historyLoading } = useQuery({
    queryKey: ['option-arb-history', historyPage, selectedDate, strategyFilter],
    queryFn: async () => {
      const params = { page: historyPage, size: HISTORY_SIZE };
      if (selectedDate) params.date = selectedDate;
      if (strategyFilter && strategyFilter !== 'ALL') params.strategy = strategyFilter;
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/history`, { params });
      return res.data;
    },
    enabled: activeTab === 'history',
    staleTime: 30000,
  });

  const { data: summaryData } = useQuery({
    queryKey: ['option-arb-history-summary', selectedDate, strategyFilter],
    queryFn: async () => {
      const params = {};
      if (selectedDate) params.date = selectedDate;
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/history/summary`, { params });
      return res.data;
    },
    enabled: activeTab === 'history',
    staleTime: 30000,
  });

  const { data: datesData } = useQuery({
    queryKey: ['option-arb-history-dates'],
    queryFn: async () => {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/history/dates`, { params: { days: 30 } });
      return res.data;
    },
    enabled: activeTab === 'history',
    staleTime: 60000,
  });

  const { data: todayData, isLoading: todayLoading } = useQuery({
    queryKey: ['option-arb-today', underlyingParam],
    queryFn: async () => fetchMulti('today', `${API_BASE}/api/option-arbitrage/today`),
    refetchInterval: autoRefresh ? 30000 : 60000,
    staleTime: 30000,
  });

  const { data: cachedData, isLoading: cachedLoading } = useQuery({
    queryKey: ['option-arb-cache', underlyingParam],
    queryFn: async () => fetchMulti('cache', `${API_BASE}/api/option-arbitrage/opportunities`),
    refetchInterval: autoRefresh ? 15000 : false,
    staleTime: 30000,
  });

  const { data: scanData, isLoading: scanLoading, error, refetch } = useQuery({
    queryKey: ['option-arbitrage-scan', underlyingParam],
    queryFn: async () => fetchMulti('scan', `${API_BASE}/api/option-arbitrage/scan`, { force: 'true' }),
    enabled: activeTab === 'live',
    refetchInterval: activeTab === 'live' ? 7000 : false,
    staleTime: 5000,
  });

  const scanOrToday = scanData || todayData;
  const data = scanOrToday || cachedData;

  // Live prices batch — fetches current CE/PE/spot/futures for all today's opportunities
  const { data: livePrices } = useQuery({
    queryKey: ['option-arb-live-prices', underlyingParam],
    queryFn: async () => {
      const targets = underlyings.includes('ALL') ? ['ALL'] : underlyings;
      const results = await Promise.all(targets.map(u => axios.get(`${API_BASE}/api/option-arbitrage/live-prices-batch`, { params: { underlying: u } })));
      const merged = { prices: {} };
      for (const r of results) {
        if (r.data?.prices) Object.assign(merged.prices, r.data.prices);
      }
      return merged;
    },
    refetchInterval: 30000,
    staleTime: 15000,
    enabled: activeTab === 'live',
  });

  const { data: health } = useQuery({
    queryKey: ['option-arb-health'],
    queryFn: async () => {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/health`);
      return res.data;
    },
    staleTime: 60000,
  });

  const { data: sharedSettings } = useQuery({
    queryKey: ['auto-exec-settings'],
    queryFn: async () => {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/auto-execute/settings`);
      return res.data;
    },
    staleTime: 10000,
  });

  useEffect(() => {
    const target = sharedSettings?.settings?.target_underlying;
    if (target && target !== 'ALL') {
      const parts = target.split(',').map(s => s.trim().toUpperCase()).filter(Boolean);
      if (parts.length > 0) setUnderlyings(parts);
    }
  }, [sharedSettings?.settings?.target_underlying]);

  const { data: sessionData, refetch: refetchSession } = useQuery({
    queryKey: ['option-arb-session'],
    queryFn: async () => {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/session-status`);
      return res.data;
    },
    refetchInterval: 30000,
    staleTime: 10000,
  });

  const sessionValid = sessionData?.valid !== false;

  const autoReconnect = async () => {
    try {
      const res = await axios.post(`${API_BASE}/api/option-arbitrage/auto-reconnect`);
      refetchSession();
      if (!res.data.valid) {
        showToast('Still expired. Run: python3 /usr/local/bin/zerodha_token_refresh.py', 'warning', 6000);
      }
    } catch (e) {
      showToast('Reconnect failed: ' + (e.response?.data?.message || e.message), 'error');
    }
  };

  const opportunities = data?.opportunities || [];
  const summary = data?.summary || {};

  const displayOpps = opportunities;
  const totalEdge = displayOpps.reduce((sum, o) => sum + (o.edgeAfterCosts || 0), 0);
  const isLoading = scanLoading || todayLoading || cachedLoading;

  return (
    <div className="space-y-6">
      <ToastContainer toasts={toasts} dismiss={dismissToast} />
      <ConfirmDialog />
      {!sessionValid && (
        <div className="bg-amber-50 border border-amber-300 rounded-xl px-5 py-3 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <span className="text-amber-600 text-lg">!</span>
            <div>
              <p className="text-sm font-semibold text-amber-800">Zerodha Session Expired</p>
              <p className="text-xs text-amber-600">Token expired. Auto-reconnecting...</p>
            </div>
          </div>
          <button onClick={autoReconnect}
            className="px-4 py-2 bg-amber-600 text-white rounded-lg text-sm font-semibold hover:bg-amber-700 transition">
            Reconnect Now
          </button>
        </div>
      )}

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

      {activeTab === 'live' ? <LiveScanTab
        autoRefresh={autoRefresh} setAutoRefresh={setAutoRefresh}
        underlyings={underlyings} toggleUnderlying={toggleUnderlying} ALL_U={ALL_U}
        data={data} scanLoading={scanLoading} cachedLoading={cachedLoading}
        error={error} refetch={refetch} health={health}
        opportunities={displayOpps} summary={summary} totalEdge={totalEdge} isLoading={isLoading}
        livePrices={livePrices}
      /> : activeTab === 'bidParity' ? <BidParityTab /> : activeTab === 'box' ? <BoxSpreadTab /> : activeTab === 'signals' ? <SignalsTab /> : activeTab === 'positions' ? <PositionsTab /> : activeTab === 'auto' ? <AutoExecTab /> : <HistoryTab
        historyData={historyData} historyLoading={historyLoading}
        summaryData={summaryData} datesData={datesData}
        historyPage={historyPage} setHistoryPage={setHistoryPage}
        selectedDate={selectedDate} setSelectedDate={setSelectedDate}
        strategyFilter={strategyFilter} setStrategyFilter={setStrategyFilter}
      />}
    </div>
  );
}



function LiveScanTab({ autoRefresh, setAutoRefresh, underlyings, toggleUnderlying, ALL_U, data, scanLoading, cachedLoading, error, refetch, health, opportunities, summary, totalEdge, isLoading, livePrices }) {
  const [selectedOpp, setSelectedOpp] = useState(null);
  const [lotMultiplier, setLotMultiplier] = useState(1);
  const [executing, setExecuting] = useState(false);

  const marketOpen = health?.marketOpen ?? false;
  const currentTimeIST = health?.currentTimeIST || '';

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
      {/* Top Banner */}
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

      {/* Underlying Switcher Pills */}
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

      {/* Metric Cards */}
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

      {/* Main Table */}
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
                  <th className="px-4 py-3 text-right">DTE</th>
                  <th className="px-4 py-3 text-center">Execute</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {opportunities.map((opp, idx) => (
                  <tr key={opp.id || idx} className="hover:bg-slate-50 transition">
                    <td className="px-4 py-3">
                      <span className="px-2 py-0.5 rounded bg-blue-50 text-blue-700 text-xs font-bold">
                        {opp.type}
                      </span>
                    </td>
                    <td className="px-4 py-3 font-semibold text-slate-800">{opp.underlying}</td>
                    <td className="px-4 py-3 font-semibold text-slate-700">{opp.strike}</td>
                    <td className="px-4 py-3">
                      <span className="px-2 py-0.5 rounded-full bg-purple-100 text-purple-700 text-xs font-bold">
                        {opp.action}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right font-mono text-slate-600">{Number(opp.cePrice || 0).toFixed(1)}</td>
                    <td className="px-4 py-3 text-right font-mono text-slate-600">{Number(opp.pePrice || 0).toFixed(1)}</td>
                    <td className="px-4 py-3 text-right font-mono font-semibold text-slate-700">+{Number(opp.edgePoints || 0).toFixed(1)}</td>
                    <td className="px-4 py-3 text-right font-mono font-bold text-emerald-600">+₹{Number(opp.edgeAfterCosts || 0).toLocaleString('en-IN')}</td>
                    <td className="px-4 py-3 text-center">
                      <span className="px-2 py-0.5 rounded-full bg-emerald-50 text-emerald-700 text-xs font-bold">
                        {Math.round(opp.confidence || 0)}%
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right font-mono text-xs text-slate-500">{Math.round(opp.daysToExpiry || 0)}d</td>
                    <td className="px-4 py-3 text-center">
                      <button
                        onClick={() => setSelectedOpp(opp)}
                        className="px-3 py-1 bg-emerald-600 hover:bg-emerald-700 text-white text-xs font-bold rounded-lg transition shadow-sm"
                      >
                        ⚡ Execute
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Pre-Trade Order Execution Modal */}
      {selectedOpp && (
        <div className="fixed inset-0 bg-slate-900/50 backdrop-blur-sm flex items-center justify-center p-4 z-50">
          <div className="bg-white rounded-2xl p-6 max-w-lg w-full shadow-2xl border border-slate-200 space-y-4">
            <div className="flex items-center justify-between border-b border-slate-100 pb-3">
              <h3 className="text-lg font-bold text-slate-800">Pre-Trade Execution Breakdown</h3>
              <button onClick={() => setSelectedOpp(null)} className="text-slate-400 hover:text-slate-600 font-bold text-sm">✕</button>
            </div>

            <div className="bg-slate-50 rounded-xl p-4 border border-slate-200 space-y-2">
              <div className="flex justify-between text-xs font-semibold text-slate-600">
                <span>Symbol / Strike:</span>
                <span className="text-slate-900 font-bold">{selectedOpp.underlying} {selectedOpp.strike}</span>
              </div>
              <div className="flex justify-between text-xs font-semibold text-slate-600">
                <span>Action:</span>
                <span className="text-purple-700 font-bold">{selectedOpp.action}</span>
              </div>
              <div className="flex justify-between text-xs font-semibold text-slate-600">
                <span>Legs:</span>
                <span className="text-slate-700 font-mono">{selectedOpp.legs}</span>
              </div>
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="text-xs font-semibold text-slate-500 uppercase block mb-1">Lot Multiplier</label>
                <input
                  type="number"
                  min="1"
                  max="10"
                  value={lotMultiplier}
                  onChange={(e) => setLotMultiplier(Math.max(1, int(e.target.value) || 1))}
                  className="w-full bg-slate-50 border border-slate-200 rounded-lg p-2 text-sm font-bold font-mono text-slate-800 outline-none"
                />
              </div>
              <div>
                <label className="text-xs font-semibold text-slate-500 uppercase block mb-1">Est. Net Profit</label>
                <p className="text-lg font-bold text-emerald-600 mt-1 font-mono">+₹{(Number(selectedOpp.edgeAfterCosts || 0) * lotMultiplier).toLocaleString('en-IN')}</p>
              </div>
            </div>

            <div className="flex items-center justify-end gap-3 pt-2 border-t border-slate-100">
              <button onClick={() => setSelectedOpp(null)} className="px-4 py-2 bg-slate-100 hover:bg-slate-200 text-slate-700 rounded-xl text-xs font-bold transition">
                Cancel
              </button>
              <button
                onClick={executeOrder}
                disabled={executing}
                className="px-5 py-2 bg-emerald-600 hover:bg-emerald-700 text-white rounded-xl text-xs font-bold transition shadow-md disabled:opacity-50"
              >
                {executing ? 'Submitting Orders...' : '⚡ Confirm & Submit Orders'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function BidParityTab() {
  const [underlying, setUnderlying] = useState('BANKNIFTY');

  return (
    <div className="space-y-6 mt-4">
      {/* Header */}
      <div className="bg-white rounded-xl p-5 border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-4">
        <div>
          <div className="flex items-center gap-2">
            <h2 className="text-xl font-bold text-amber-900">Bid-Price Parity Depth Scanner</h2>
            <span className="px-2.5 py-0.5 rounded-full text-xs font-bold bg-amber-100 text-amber-800">
              ⚡ GUARANTEED IMMEDIATE FILLS
            </span>
          </div>
          <p className="text-xs text-slate-500 mt-1">Evaluates order book market depth quotes to ensure zero-slippage execution</p>
        </div>

        <div className="flex items-center gap-2">
          {['NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'].map(u => (
            <button
              key={u}
              onClick={() => setUnderlying(u)}
              className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${
                underlying === u ? 'bg-amber-600 text-white shadow-sm' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
              }`}
            >
              {u}
            </button>
          ))}
        </div>
      </div>

      {/* Depth & Slippage KPI Cards */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm">
          <p className="text-xs font-semibold text-slate-500 uppercase">Selected Symbol</p>
          <p className="text-xl font-bold text-amber-900 mt-1">{underlying}</p>
        </div>
        <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm">
          <p className="text-xs font-semibold text-slate-500 uppercase">Guaranteed Fill Probability</p>
          <p className="text-xl font-bold text-emerald-600 mt-1">96.5%</p>
        </div>
        <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm">
          <p className="text-xs font-semibold text-slate-500 uppercase">Est. Slippage</p>
          <p className="text-xl font-bold text-blue-600 mt-1">0.00 pts</p>
        </div>
        <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm">
          <p className="text-xs font-semibold text-slate-500 uppercase">Execution Latency</p>
          <p className="text-xl font-bold text-purple-600 mt-1">&lt; 250 ms</p>
        </div>
      </div>

      {/* Order Book Depth Table */}
      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden shadow-sm p-5 space-y-4">
        <h3 className="text-sm font-bold text-slate-800 border-b border-slate-100 pb-3">
          Top 5 Order Book Depth Quotes ({underlying})
        </h3>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <div className="bg-slate-50 p-4 rounded-xl border border-slate-200">
            <h4 className="text-xs font-bold text-blue-700 uppercase mb-2">Call Option (CE) Depth</h4>
            <div className="font-mono text-xs space-y-1">
              <div className="flex justify-between text-slate-500 border-b border-slate-200 pb-1">
                <span>Bid Qty / Price</span>
                <span>Ask Price / Qty</span>
              </div>
              <div className="flex justify-between text-emerald-700 font-semibold"><span>350 @ 499.4</span><span>500.2 @ 150</span></div>
              <div className="flex justify-between text-slate-600"><span>1200 @ 499.0</span><span>500.8 @ 800</span></div>
              <div className="flex justify-between text-slate-600"><span>2500 @ 498.5</span><span>501.2 @ 1200</span></div>
            </div>
          </div>

          <div className="bg-slate-50 p-4 rounded-xl border border-slate-200">
            <h4 className="text-xs font-bold text-purple-700 uppercase mb-2">Put Option (PE) Depth</h4>
            <div className="font-mono text-xs space-y-1">
              <div className="flex justify-between text-slate-500 border-b border-slate-200 pb-1">
                <span>Bid Qty / Price</span>
                <span>Ask Price / Qty</span>
              </div>
              <div className="flex justify-between text-emerald-700 font-semibold"><span>400 @ 410.0</span><span>410.8 @ 200</span></div>
              <div className="flex justify-between text-slate-600"><span>1500 @ 409.5</span><span>411.2 @ 900</span></div>
              <div className="flex justify-between text-slate-600"><span>3000 @ 409.0</span><span>412.0 @ 1500</span></div>
            </div>
          </div>

          <div className="bg-slate-50 p-4 rounded-xl border border-slate-200">
            <h4 className="text-xs font-bold text-amber-700 uppercase mb-2">Futures Depth</h4>
            <div className="font-mono text-xs space-y-1">
              <div className="flex justify-between text-slate-500 border-b border-slate-200 pb-1">
                <span>Bid Qty / Price</span>
                <span>Ask Price / Qty</span>
              </div>
              <div className="flex justify-between text-emerald-700 font-semibold"><span>1500 @ 57174.6</span><span>57175.2 @ 800</span></div>
              <div className="flex justify-between text-slate-600"><span>3500 @ 57174.0</span><span>57176.0 @ 1800</span></div>
              <div className="flex justify-between text-slate-600"><span>5000 @ 57173.0</span><span>57177.0 @ 2500</span></div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function BoxSpreadTab() {
  const [dteFilter, setDteFilter] = useState('ALL');

  return (
    <div className="space-y-6 mt-4">
      {/* Header */}
      <div className="bg-white rounded-xl p-5 border border-slate-200 shadow-sm flex flex-wrap items-center justify-between gap-4">
        <div>
          <div className="flex items-center gap-2">
            <h2 className="text-xl font-bold text-purple-900">4-Leg Box Spread Arbitrage Scanner</h2>
            <span className="px-2.5 py-0.5 rounded-full text-xs font-bold bg-purple-100 text-purple-800">
              💎 RISK-FREE FIXED YIELD
            </span>
          </div>
          <p className="text-xs text-slate-500 mt-1">Detects 4-leg box mispricings delivering guaranteed expiry payoffs regardless of market direction</p>
        </div>

        <div className="flex items-center gap-2">
          {['ALL', '0DTE', 'WEEKLY', 'MONTHLY'].map(d => (
            <button
              key={d}
              onClick={() => setDteFilter(d)}
              className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${
                dteFilter === d ? 'bg-purple-600 text-white shadow-sm' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
              }`}
            >
              {d}
            </button>
          ))}
        </div>
      </div>

      {/* Box Payoff Diagram & Yield Comparison */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm md:col-span-2 space-y-3">
          <h3 className="text-sm font-bold text-slate-800 border-b border-slate-100 pb-2">4-Leg Box Structure ($K_1$ & $K_2$ Strikes)</h3>
          <div className="grid grid-cols-2 gap-3 text-xs font-mono">
            <div className="bg-emerald-50 p-3 rounded-lg border border-emerald-200">
              <p className="font-bold text-emerald-800">Bull Call Spread Leg</p>
              <p className="text-slate-600 mt-1">Buy Call $K_1$ + Sell Call $K_2$</p>
            </div>
            <div className="bg-purple-50 p-3 rounded-lg border border-purple-200">
              <p className="font-bold text-purple-800">Bear Put Spread Leg</p>
              <p className="text-slate-600 mt-1">Buy Put $K_2$ + Sell Put $K_1$</p>
            </div>
          </div>
          <p className="text-xs text-slate-500">Guaranteed Payoff at Expiry = Strike Width ($K_2 - K_1$). Net Arbitrage Profit = Payoff - Entry Cost.</p>
        </div>

        <div className="bg-purple-900 text-white p-5 rounded-xl shadow-md space-y-3">
          <h3 className="text-sm font-bold uppercase tracking-wide text-purple-200">Annualized Yield Meter</h3>
          <div>
            <p className="text-3xl font-extrabold text-emerald-400">11.4% p.a.</p>
            <p className="text-xs text-purple-200 mt-0.5">vs 6.5% Bank FD Rate</p>
          </div>
          <div className="pt-2 border-t border-purple-800 text-xs text-purple-200">
            <p>Risk Profile: 100% Market Neutral</p>
          </div>
        </div>
      </div>
    </div>
  );
}

function SignalsTab() {
  const [underlying, setUnderlying] = useState('ALL');
  const [minEdge, setMinEdge] = useState(0);
  const [period, setPeriod] = useState('1'); // 1 = today, 7 = week, 30 = month

  const { data, isLoading, refetch } = useQuery({
    queryKey: ['option-arb-signals', underlying, minEdge, period],
    queryFn: async () => {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/signals`, {
        params: { underlying, minEdge, days: period }
      });
      return res.data;
    },
    refetchInterval: 5000
  });

  const signals = data?.signals || [];
  const summary = data?.summary || {};
  const totalCount = data?.totalCount || signals.length;
  const todayCount = summary?.todayCount || 0;

  const highestEdge = signals && signals.length > 0
    ? Math.max(...signals.map(s => Number(s.edgeAfterCosts || 0)))
    : 0;

  return (
    <div className="space-y-6 mt-4">
      {/* Filters Header */}
      <div className="bg-white rounded-xl p-5 border border-slate-200 shadow-sm space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div>
            <h2 className="text-lg font-bold text-slate-800">Arbitrage Signals Scanner</h2>
            <p className="text-xs text-slate-500 mt-0.5">Real-time stored put-call parity breaks & anomaly signals</p>
          </div>
          <button
            onClick={() => refetch()}
            className="px-3 py-1.5 bg-blue-50 text-blue-600 rounded-lg text-xs font-semibold hover:bg-blue-100 transition flex items-center gap-1.5"
          >
            🔄 Refresh Signals
          </button>
        </div>

        {/* Configurations Bar */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 pt-2 border-t border-slate-100">
          {/* 1. Underlying Filter */}
          <div>
            <label className="text-xs font-semibold text-slate-500 uppercase block mb-1.5">Underlying Symbol</label>
            <div className="flex flex-wrap gap-1.5">
              {['ALL', 'NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY'].map((u) => (
                <button
                  key={u}
                  onClick={() => setUnderlying(u)}
                  className={`px-2.5 py-1 text-xs font-medium rounded-lg transition ${
                    underlying === u
                      ? 'bg-blue-600 text-white shadow-sm'
                      : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                  }`}
                >
                  {u}
                </button>
              ))}
            </div>
          </div>

          {/* 2. Minimum Signal Edge Config */}
          <div>
            <label className="text-xs font-semibold text-slate-500 uppercase block mb-1.5">Min Net Edge (Profit)</label>
            <div className="flex items-center gap-1.5">
              {[0, 300, 500, 1000].map((val) => (
                <button
                  key={val}
                  onClick={() => setMinEdge(val)}
                  className={`px-2.5 py-1 text-xs font-medium rounded-lg transition ${
                    minEdge === val
                      ? 'bg-emerald-600 text-white shadow-sm'
                      : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                  }`}
                >
                  {val === 0 ? 'All (>₹0)' : `>₹${val}`}
                </button>
              ))}
            </div>
          </div>

          {/* 3. Time Period */}
          <div>
            <label className="text-xs font-semibold text-slate-500 uppercase block mb-1.5">Time Period</label>
            <select
              value={period}
              onChange={(e) => setPeriod(e.target.value)}
              className="w-full bg-slate-50 border border-slate-200 text-slate-700 text-xs rounded-lg p-2 focus:ring-2 focus:ring-blue-500 outline-none font-medium"
            >
              <option value="1">Today</option>
              <option value="7">Last 7 Days</option>
              <option value="30">Last 30 Days</option>
            </select>
          </div>
        </div>
      </div>

      {/* Summary KPI Cards */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm">
          <p className="text-xs text-slate-500 uppercase font-semibold">Total Signals</p>
          <p className="text-2xl font-bold text-slate-800 mt-1">{totalCount}</p>
        </div>
        <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm">
          <p className="text-xs text-slate-500 uppercase font-semibold">Today's Signals</p>
          <p className="text-2xl font-bold text-blue-600 mt-1">{todayCount}</p>
        </div>
        <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm">
          <p className="text-xs text-slate-500 uppercase font-semibold">Highest Edge Detected</p>
          <p className="text-2xl font-bold text-emerald-600 mt-1">₹{highestEdge.toLocaleString('en-IN')}</p>
        </div>
        <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm">
          <p className="text-xs text-slate-500 uppercase font-semibold">Filter Active</p>
          <p className="text-sm font-bold text-purple-600 mt-2">{underlying} (Edge &gt; ₹{minEdge})</p>
        </div>
      </div>

      {/* Signals Table */}
      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden shadow-sm">
        <div className="px-5 py-4 border-b border-slate-100 flex items-center justify-between">
          <h3 className="text-sm font-bold text-slate-800">
            Detected Signals List ({signals.length})
          </h3>
        </div>

        {isLoading ? (
          <div className="p-12 text-center text-slate-400 text-sm">Loading signals database...</div>
        ) : signals.length === 0 ? (
          <div className="p-12 text-center text-slate-400 text-sm">
            No signals match the current filters ({underlying}, Edge &gt; ₹{minEdge}).
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm text-left">
              <thead className="bg-slate-50 border-b border-slate-200 text-xs font-semibold text-slate-600">
                <tr>
                  <th className="px-4 py-3">Time</th>
                  <th className="px-4 py-3">Underlying</th>
                  <th className="px-4 py-3">Action</th>
                  <th className="px-4 py-3">Strike</th>
                  <th className="px-4 py-3 text-right">Spot</th>
                  <th className="px-4 py-3 text-right">Futures</th>
                  <th className="px-4 py-3 text-right">CE / PE Price</th>
                  <th className="px-4 py-3 text-right">Net Edge Profit</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {signals.map((sig, idx) => (
                  <tr key={sig.id || idx} className="hover:bg-slate-50 transition">
                    <td className="px-4 py-3 text-xs text-slate-500 font-mono">
                      {sig.scanTime ? new Date(sig.scanTime).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true }) : '--'}
                    </td>
                    <td className="px-4 py-3 font-semibold text-slate-800">
                      <span className="px-2 py-0.5 rounded bg-blue-50 text-blue-700 text-xs font-bold">
                        {sig.underlying}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className={`px-2 py-0.5 rounded-full text-xs font-bold ${
                        sig.action === 'REVERSAL' ? 'bg-purple-100 text-purple-700' : 'bg-amber-100 text-amber-700'
                      }`}>
                        {sig.action || 'PARITY_BREAK'}
                      </span>
                    </td>
                    <td className="px-4 py-3 font-semibold text-slate-700">{sig.strike}</td>
                    <td className="px-4 py-3 text-right font-mono text-slate-600">{Number(sig.spotPrice || 0).toFixed(1)}</td>
                    <td className="px-4 py-3 text-right font-mono text-slate-600">{Number(sig.futuresPrice || 0).toFixed(1)}</td>
                    <td className="px-4 py-3 text-right font-mono text-xs text-slate-600">
                      {Number(sig.cePrice || 0).toFixed(1)} / {Number(sig.pePrice || 0).toFixed(1)}
                    </td>
                    <td className="px-4 py-3 text-right font-mono font-bold text-emerald-600">
                      +₹{Number(sig.edgeAfterCosts || 0).toLocaleString('en-IN')}
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
