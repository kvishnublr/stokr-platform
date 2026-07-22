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
  const [underlyings, setUnderlyings] = useState(['ALL']);
  const [expandedIdx, setExpandedIdx] = useState(null);
  const [executing, setExecuting] = useState(null);
  const [sortKey, setSortKey] = useState(null);
  const [sortDir, setSortDir] = useState('desc');
  const [prevPrices, setPrevPrices] = useState({});

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

  const { data: bidData, isLoading, refetch } = useQuery({
    queryKey: ['bidParity', underlyingParam],
    queryFn: async () => {
      const targets = underlyings.includes('ALL') ? ['ALL'] : underlyings;
      const results = await Promise.all(targets.map(u =>
        axios.get(`${API_BASE}/api/option-arbitrage/bid-parity/scan`, {
          params: { underlying: u, force: true }
        })
      ));
      const allOpps = results.flatMap(r => r.data?.opportunities || []);
      return { opportunities: allOpps };
    },
    refetchInterval: 5000,
  });

  const { data: tickData } = useQuery({
    queryKey: ['bidParityTicks'],
    queryFn: async () => {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/bid-parity/live-ticks`);
      return res.data;
    },
    refetchInterval: 1500,
  });

  const { data: autoStatus, refetch: refetchStatus } = useQuery({
    queryKey: ['bidParityAuto'],
    queryFn: async () => {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/bid-parity/auto-status`);
      return res.data;
    },
    refetchInterval: 5000,
  });

  const { data: bpSettingsData } = useQuery({
    queryKey: ['auto-exec-settings'],
    queryFn: async () => {
      const r = await axios.get(`${API_BASE}/api/option-arbitrage/auto-execute/settings`);
      return r.data;
    },
    staleTime: 10000,
  });

  const toggleAuto = async () => {
    try {
      await axios.post(`${API_BASE}/api/option-arbitrage/bid-parity/auto-toggle`);
      refetchStatus();
    } catch (e) { showToast('Toggle failed: ' + e.message, 'error'); }
  };

  const toggleAutoExit = async () => {
    try {
      await axios.post(`${API_BASE}/api/option-arbitrage/bid-parity/auto-exit-toggle`);
      refetchStatus();
    } catch (e) { showToast('Toggle failed: ' + e.message, 'error'); }
  };

  const rawOpps = bidData?.opportunities || [];
  const wsTicks = tickData?.wsTicks || {};
  const bpSettings = bpSettingsData?.settings || {};

  useEffect(() => {
    const target = bpSettings.target_underlying;
    if (target && target !== 'ALL') {
      const parts = target.split(',').map(s => s.trim().toUpperCase()).filter(Boolean);
      if (parts.length > 0) setUnderlyings(parts);
    }
  }, [bpSettings.target_underlying]);
  const isAutoOn = autoStatus?.autoEnabled || false;
  const isAutoExitOn = autoStatus?.autoExitEnabled !== false;
  const wsTickCount = tickData?.wsCount || 0;

  const opps = React.useMemo(() => {
    if (!rawOpps.length) return [];
    const newPrices = {};
    const merged = rawOpps.map(opp => {
      const m = { ...opp };
      if (opp.ceSymbol && wsTicks[opp.ceSymbol]) {
        const t = wsTicks[opp.ceSymbol];
        m.ceBidLive = t.bidPrice;
        m.ceAskLive = t.askPrice;
        m.ceBidQtyLive = t.bidQty;
        m.ceAskQtyLive = t.askQty;
        m.ceLtpLive = t.ltp;
        m.ceLive = true;
      }
      if (opp.peSymbol && wsTicks[opp.peSymbol]) {
        const t = wsTicks[opp.peSymbol];
        m.peBidLive = t.bidPrice;
        m.peAskLive = t.askPrice;
        m.peBidQtyLive = t.bidQty;
        m.peAskQtyLive = t.askQty;
        m.peLtpLive = t.ltp;
        m.peLive = true;
      }
      m._ceBid = m.ceBidLive ?? opp.ceBid;
      m._ceAsk = m.ceAskLive ?? opp.ceAsk;
      m._peBid = m.peBidLive ?? opp.peBid;
      m._peAsk = m.peAskLive ?? opp.peAsk;
      const priceKey = `${opp.underlying}_${opp.strike}`;
      const old = prevPrices[priceKey];
      if (old) {
        m._ceBidUp = old.ceBid != null && m._ceBid > old.ceBid;
        m._ceBidDown = old.ceBid != null && m._ceBid < old.ceBid;
        m._peBidUp = old.peBid != null && m._peBid > old.peBid;
        m._peBidDown = old.peBid != null && m._peBid < old.peBid;
      }
      newPrices[priceKey] = { ceBid: m._ceBid, peBid: m._peBid };
      return m;
    });
    if (Object.keys(newPrices).length > 0) {
      setTimeout(() => setPrevPrices(newPrices), 0);
    }
    return merged;
  }, [rawOpps, wsTicks, prevPrices]);

  function toggleSort(key) {
    if (sortKey === key) setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    else { setSortKey(key); setSortDir('desc'); }
  }
  function sortIndicator(key) {
    if (sortKey !== key) return ' ↕';
    return sortDir === 'asc' ? ' ↑' : ' ↓';
  }

  const sortedOpps = [...opps].sort((a, b) => {
    if (!sortKey) return 0;
    let va, vb;
    switch (sortKey) {
      case 'underlying': va = a.underlying || ''; vb = b.underlying || ''; return sortDir === 'asc' ? va.localeCompare(vb) : vb.localeCompare(va);
      case 'strike': va = a.strike || 0; vb = b.strike || 0; break;
      case 'action': va = a.action || ''; vb = b.action || ''; return sortDir === 'asc' ? va.localeCompare(vb) : vb.localeCompare(va);
      case 'ceBid': va = a._ceBid || 0; vb = b._ceBid || 0; break;
      case 'ceAsk': va = a._ceAsk || 0; vb = b._ceAsk || 0; break;
      case 'ceBidQty': va = a.ceBidQty || 0; vb = b.ceBidQty || 0; break;
      case 'peBid': va = a._peBid || 0; vb = b._peBid || 0; break;
      case 'peAsk': va = a._peAsk || 0; vb = b._peAsk || 0; break;
      case 'peBidQty': va = a.peBidQty || 0; vb = b.peBidQty || 0; break;
      case 'futPrice': va = a.futuresPrice || 0; vb = b.futuresPrice || 0; break;
      case 'deviation': va = a.bidParityDev || 0; vb = b.bidParityDev || 0; break;
      case 'edgeAfterCosts': va = a.edgeAfterCosts || 0; vb = b.edgeAfterCosts || 0; break;
      case 'confidence': va = a.confidence || 0; vb = b.confidence || 0; break;
      case 'daysToExpiry': va = a.daysToExpiry || 0; vb = b.daysToExpiry || 0; break;
      case 'signalTime': va = a.detectedAt || a.scanTime || ''; vb = b.detectedAt || b.scanTime || ''; return sortDir === 'asc' ? va.localeCompare(vb) : vb.localeCompare(va);
      default: return 0;
    }
    return sortDir === 'asc' ? va - vb : vb - va;
  });

  const executeBidParity = async (opp) => {
    setExecuting(opp.strike);
    try {
      const isConv = opp.action === 'CONVERSION';
      const cePrice = isConv ? opp._ceAsk : opp._ceBid;
      const pePrice = isConv ? opp._peBid : opp._peAsk;

      const res = await axios.post(`${API_BASE}/api/option-arbitrage/bid-parity/execute`, null, {
        params: {
          underlying: opp.underlying,
          strike: opp.strike,
          action: opp.action,
          cePrice: cePrice,
          pePrice: pePrice,
          futPrice: opp.futuresPrice,
          spotPrice: opp.spotPrice,
          ceBidQty: opp.ceBidQty || 0,
          peBidQty: opp.peBidQty || 0,
        }
      });

      if (res.data.success) {
        showToast(`Trade executed! ID: ${res.data.tradeId} — ${res.data.tradeStatus}`, 'success');
        refetch();
        refetchStatus();
      } else {
        showToast(`Execution failed: ${res.data.error || 'unknown error'}`, 'error');
      }
    } catch (err) {
      showToast('Execution error: ' + (err.response?.data?.error || err.message), 'error');
    }
    setExecuting(null);
  };

  const fmt = (v, d = 2) => v != null ? Number(v).toFixed(d) : '--';
  const fmtCurrency = (v, d = 0) => v != null ? '₹' + Number(v).toLocaleString('en-IN', { minimumFractionDigits: d, maximumFractionDigits: d }) : '--';

  const priceFlashClass = (up, down) => {
    if (up) return 'text-emerald-600 bg-emerald-50 transition-all duration-300';
    if (down) return 'text-red-600 bg-red-50 transition-all duration-300';
    return '';
  };

  return (
    <div>
      <div className="flex items-center justify-between mb-4">
        <div>
          <h2 className="text-lg font-bold text-slate-800">Bid Parity Scanner</h2>
          <p className="text-xs text-slate-500 mt-1">Live tick-by-tick prices from WebSocket. All prices update in real-time.</p>
        </div>
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-2 text-xs text-slate-500">
            <span className={`w-2 h-2 rounded-full ${wsTickCount > 0 ? 'bg-blue-500 animate-pulse' : 'bg-slate-300'}`}></span>
            {wsTickCount > 0 ? `${wsTickCount} live ticks` : 'no WS'}
          </div>
          <div className="flex bg-zinc-800 rounded-lg p-1">
            {ALL_U.map((opt) => {
              const active = underlyings.includes(opt);
              return (
                <button key={opt} onClick={() => toggleUnderlying(opt)}
                  className={`px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${active ? 'bg-amber-500 text-white' : 'text-zinc-400 hover:text-white'}`}>
                  {opt === 'ALL' ? 'All' : opt}
                </button>
              );
            })}
          </div>
          <button onClick={toggleAuto}
            className={`px-4 py-1.5 text-sm font-medium rounded-lg transition-colors ${
              isAutoOn ? 'bg-red-100 text-red-700 border border-red-300 hover:bg-red-200' : 'bg-green-100 text-green-700 border border-green-300 hover:bg-green-200'
            }`}>
            {isAutoOn ? 'Auto Entry: ON' : 'Auto Entry: OFF'}
          </button>
          <button onClick={toggleAutoExit}
            className={`px-4 py-1.5 text-sm font-medium rounded-lg transition-colors ${
              isAutoExitOn ? 'bg-red-100 text-red-700 border border-red-300 hover:bg-red-200' : 'bg-green-100 text-green-700 border border-green-300 hover:bg-green-200'
            }`}>
            {isAutoExitOn ? 'Auto Exit: ON' : 'Auto Exit: OFF'}
          </button>
          <button onClick={() => refetch()} disabled={isLoading}
            className="px-4 py-1.5 bg-blue-600 text-white text-sm font-medium rounded-lg hover:bg-blue-700 disabled:opacity-50">
            {isLoading ? 'Scanning...' : 'Scan Now'}
          </button>
        </div>
      </div>

      <div className="bg-blue-50 border border-blue-200 rounded-lg p-3 mb-4">
        <p className="text-xs text-blue-700">
          <strong>Live Prices:</strong> CE Bid/PE Bid update from WebSocket in real-time. Flash green = price up, flash red = price down.
          Execute at live bid prices. <strong>Bid depth ≥ lot size</strong> required.
        </p>
      </div>

      <BidParityPositions />

      {isLoading && opps.length === 0 ? (
        <div className="text-center py-12 text-slate-400">Scanning all underlyings...</div>
      ) : opps.length === 0 ? (
        <div className="text-center py-12">
          <p className="text-slate-400 text-lg">No bid parity breaks found</p>
          <p className="text-slate-400 text-sm mt-1">Bid prices are more conservative — fewer opps but guaranteed fills</p>
        </div>
      ) : (
        <div className="overflow-x-auto bg-white rounded-xl border border-slate-200 shadow-sm">
          <table className="min-w-full">
            <thead>
              <tr className="border-b border-slate-200 bg-slate-50">
                <th onClick={() => toggleSort('underlying')} className="px-2 py-2.5 text-left text-xs font-semibold text-slate-500 uppercase cursor-pointer hover:text-blue-600 select-none">Underlying{sortIndicator('underlying')}</th>
                <th onClick={() => toggleSort('strike')} className="px-2 py-2.5 text-left text-xs font-semibold text-slate-500 uppercase cursor-pointer hover:text-blue-600 select-none">Strike{sortIndicator('strike')}</th>
                <th onClick={() => toggleSort('action')} className="px-2 py-2.5 text-left text-xs font-semibold text-slate-500 uppercase cursor-pointer hover:text-blue-600 select-none">Action{sortIndicator('action')}</th>
                <th onClick={() => toggleSort('ceBid')} className="px-2 py-2.5 text-right text-xs font-semibold text-blue-700 uppercase cursor-pointer hover:text-blue-600 select-none bg-blue-50/80">CE Bid{sortIndicator('ceBid')}</th>
                <th onClick={() => toggleSort('ceAsk')} className="px-2 py-2.5 text-right text-xs font-semibold text-blue-600 uppercase cursor-pointer hover:text-blue-600 select-none bg-blue-50/80">CE Ask{sortIndicator('ceAsk')}</th>
                <th onClick={() => toggleSort('peBid')} className="px-2 py-2.5 text-right text-xs font-semibold text-orange-700 uppercase cursor-pointer hover:text-blue-600 select-none bg-orange-50/80">PE Bid{sortIndicator('peBid')}</th>
                <th onClick={() => toggleSort('peAsk')} className="px-2 py-2.5 text-right text-xs font-semibold text-orange-600 uppercase cursor-pointer hover:text-blue-600 select-none bg-orange-50/80">PE Ask{sortIndicator('peAsk')}</th>
                <th onClick={() => toggleSort('futPrice')} className="px-2 py-2.5 text-right text-xs font-semibold text-slate-500 uppercase cursor-pointer hover:text-blue-600 select-none">FUT{sortIndicator('futPrice')}</th>
                <th onClick={() => toggleSort('edgeAfterCosts')} className="px-2 py-2.5 text-right text-xs font-semibold text-slate-500 uppercase cursor-pointer hover:text-blue-600 select-none">Edge (₹){sortIndicator('edgeAfterCosts')}</th>
                <th onClick={() => toggleSort('deviation')} className="px-2 py-2.5 text-right text-xs font-semibold text-slate-500 uppercase cursor-pointer hover:text-blue-600 select-none">Dev (pts){sortIndicator('deviation')}</th>
                <th onClick={() => toggleSort('daysToExpiry')} className="px-2 py-2.5 text-right text-xs font-semibold text-slate-500 uppercase cursor-pointer hover:text-blue-600 select-none">DTE{sortIndicator('daysToExpiry')}</th>
                <th className="px-2 py-2.5 text-center text-xs font-semibold text-slate-500 uppercase">Exec</th>
              </tr>
            </thead>
            <tbody>
              {sortedOpps.map((opp, idx) => (
                <React.Fragment key={`${opp.underlying}_${opp.strike}_${idx}`}>
                  <tr className="border-b border-slate-100 hover:bg-blue-50/30 cursor-pointer transition-colors"
                    onClick={() => setExpandedIdx(expandedIdx === idx ? null : idx)}>
                    <td className="px-2 py-2.5 text-sm font-medium text-slate-700">{opp.underlying}</td>
                    <td className="px-2 py-2.5 text-sm font-mono font-medium text-slate-900">{opp.strike}</td>
                    <td className="px-2 py-2.5">
                      <span className={`inline-flex px-2 py-0.5 rounded-full text-xs font-medium ${
                        opp.action === 'CONVERSION' ? 'bg-green-100 text-green-700 border border-green-200' : 'bg-orange-100 text-orange-700 border border-orange-200'
                      }`}>
                        {opp.action === 'CONVERSION' ? 'BUY CE+PE / SELL FUT' : 'SELL CE+PE / BUY FUT'}
                      </span>
                    </td>
                    <td className={`px-2 py-2.5 text-sm text-right font-mono font-bold text-blue-700 ${priceFlashClass(opp._ceBidUp, opp._ceBidDown)} px-1 rounded`}>
                      {fmt(opp._ceBid)}
                      {opp.ceLive && <span className="inline-block w-1.5 h-1.5 bg-blue-500 rounded-full ml-0.5 animate-pulse" title="Live WebSocket"></span>}
                    </td>
                    <td className="px-2 py-2.5 text-sm text-right font-mono text-blue-600">
                      {fmt(opp._ceAsk)}
                    </td>
                    <td className={`px-2 py-2.5 text-sm text-right font-mono font-bold text-orange-700 ${priceFlashClass(opp._peBidUp, opp._peBidDown)} px-1 rounded`}>
                      {fmt(opp._peBid)}
                      {opp.peLive && <span className="inline-block w-1.5 h-1.5 bg-orange-500 rounded-full ml-0.5 animate-pulse" title="Live WebSocket"></span>}
                    </td>
                    <td className="px-2 py-2.5 text-sm text-right font-mono text-orange-600">
                      {fmt(opp._peAsk)}
                    </td>
                    <td className="px-2 py-2.5 text-sm text-right font-mono font-medium text-slate-700">
                      {fmt(opp.futuresPrice)}
                    </td>
                    <td className={`px-2 py-2.5 text-sm text-right font-mono font-bold ${opp.edgeAfterCosts > 0 ? 'text-emerald-600' : 'text-red-500'}`}>
                      {fmtCurrency(opp.edgeAfterCosts, 0)}
                    </td>
                    <td className="px-2 py-2.5 text-sm text-right font-mono font-medium text-blue-700">{fmt(opp.bidParityDev, 1)}</td>
                    <td className="px-2 py-2.5 text-sm text-right text-slate-500">{fmt(opp.daysToExpiry, 0)}d</td>
                    <td className="px-2 py-2.5 text-center">
                      <div className="flex items-center justify-center gap-1">
                        <KiteBasketButton opp={opp} label="Kite" livePriceMap={opp.ceLive ? { [opp.ceSymbol]: { bid: opp._ceBid, ask: opp._ceAsk }, [opp.peSymbol]: { bid: opp._peBid, ask: opp._peAsk } } : {}} />
                        <button onClick={(e) => { e.stopPropagation(); executeBidParity(opp); }}
                          disabled={executing === opp.strike}
                          className="px-2.5 py-1.5 rounded-lg text-xs font-semibold bg-emerald-600 text-white hover:bg-emerald-700 shadow-sm transition disabled:opacity-50">
                          {executing === opp.strike ? '...' : 'EXEC'}
                        </button>
                      </div>
                    </td>
                  </tr>
                  {expandedIdx === idx && (
                    <tr>
                      <td colSpan={12} className="px-6 py-4 bg-slate-50 border-b border-slate-200">
                        <div className="grid grid-cols-2 md:grid-cols-5 gap-4 text-sm">
                          <div><span className="text-slate-500">Spot</span><p className="font-mono">{fmtCurrency(opp.spotPrice, 1)}</p></div>
                          <div><span className="text-slate-500">Futures</span><p className="font-mono">{fmtCurrency(opp.futuresPrice, 1)}</p></div>
                          <div><span className="text-slate-500">Lot Size</span><p className="font-mono">{opp.lotSize}</p></div>
                          <div><span className="text-slate-500">CE Symbol</span><p className="font-mono text-xs">{opp.ceSymbol || '--'}</p></div>
                          <div><span className="text-slate-500">PE Symbol</span><p className="font-mono text-xs">{opp.peSymbol || '--'}</p></div>
                        </div>
                        <div className="grid grid-cols-2 md:grid-cols-6 gap-4 text-sm mt-3">
                          <div><span className="text-slate-500">CE Bid</span><p className="font-mono font-bold text-blue-700">{fmt(opp._ceBid)} <span className="text-xs text-slate-400">× {opp.ceBidQty?.toLocaleString()}</span></p></div>
                          <div><span className="text-slate-500">CE Ask</span><p className="font-mono text-blue-600">{fmt(opp._ceAsk)} <span className="text-xs text-slate-400">× {opp.ceAskQty?.toLocaleString()}</span></p></div>
                          <div><span className="text-slate-500">PE Bid</span><p className="font-mono font-bold text-orange-700">{fmt(opp._peBid)} <span className="text-xs text-slate-400">× {opp.peBidQty?.toLocaleString()}</span></p></div>
                          <div><span className="text-slate-500">PE Ask</span><p className="font-mono text-orange-600">{fmt(opp._peAsk)} <span className="text-xs text-slate-400">× {opp.peAskQty?.toLocaleString()}</span></p></div>
                          <div><span className="text-slate-500">Gross Edge</span><p className="font-mono text-emerald-600">{fmtCurrency(opp.grossEdge)}</p></div>
                          <div><span className="text-slate-500">Total Costs</span><p className="font-mono text-red-600">{fmtCurrency(opp.totalCosts)}</p></div>
                        </div>
                        {opp.costBreakdown && (
                          <div className="mt-3 p-3 bg-white rounded-lg border border-slate-200">
                            <p className="text-xs font-semibold text-slate-500 mb-2">Cost Breakdown</p>
                            <div className="grid grid-cols-3 md:grid-cols-6 gap-2 text-xs">
                              <div><span className="text-slate-400">STT</span><p className="font-mono">{fmtCurrency(opp.costBreakdown.stt)}</p></div>
                              <div><span className="text-slate-400">Brokerage</span><p className="font-mono">{fmtCurrency(opp.costBreakdown.brokerage)}</p></div>
                              <div><span className="text-slate-400">Exchange</span><p className="font-mono">{fmtCurrency(opp.costBreakdown.exchange)}</p></div>
                              <div><span className="text-slate-400">SEBI</span><p className="font-mono">{fmtCurrency(opp.costBreakdown.sebi)}</p></div>
                              <div><span className="text-slate-400">GST</span><p className="font-mono">{fmtCurrency(opp.costBreakdown.gst)}</p></div>
                              <div><span className="text-slate-400">Total Costs</span><p className="font-mono text-red-600">{fmtCurrency(opp.costBreakdown.totalCosts)}</p></div>
                            </div>
                          </div>
                        )}
                        <div className="mt-3 flex gap-2 items-center">
                          <KiteBasketButton opp={opp} label="Open Basket on Kite" className="px-5 py-2.5 rounded-lg text-sm font-bold bg-blue-600 text-white hover:bg-blue-700 shadow-sm transition" livePriceMap={opp.ceLive ? { [opp.ceSymbol]: { bid: opp._ceBid, ask: opp._ceAsk }, [opp.peSymbol]: { bid: opp._peBid, ask: opp._peAsk } } : {}} />
                          <button onClick={(e) => { e.stopPropagation(); executeBidParity(opp); }}
                            disabled={executing === opp.strike}
                            className="px-4 py-2 bg-emerald-600 text-white text-sm font-semibold rounded-lg hover:bg-emerald-700 transition disabled:opacity-50">
                            {executing === opp.strike ? 'Executing...' : 'Execute Trade'}
                          </button>
                          <p className="text-xs text-slate-500 self-center">{opp.description}</p>
                        </div>
                      </td>
                    </tr>
                  )}
                </React.Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function BoxSpreadTab() {
  const [underlying, setUnderlying] = useState('ALL');
  const [sortKey, setSortKey] = useState(null);
  const [sortDir, setSortDir] = useState('desc');

  const { data: boxData, isLoading, refetch } = useQuery({
    queryKey: ['box-spread', underlying],
    queryFn: async () => {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/box-spread`, {
        params: { underlying, force: 'true' }
      });
      return res.data;
    },
    refetchInterval: 30000,
    staleTime: 10000,
  });

  const opps = boxData?.opportunities || [];
  const totalProfit = opps.reduce((sum, o) => sum + (o.netProfitPerLot || 0), 0);

  function toggleSort(key) {
    if (sortKey === key) setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    else { setSortKey(key); setSortDir('desc'); }
  }
  function sortIndicator(key) {
    if (sortKey !== key) return ' ↕';
    return sortDir === 'asc' ? ' ↑' : ' ↓';
  }
  const sortedOpps = [...opps].sort((a, b) => {
    if (!sortKey) return 0;
    let va, vb;
    switch (sortKey) {
      case 'underlying': va = a.underlying || ''; vb = b.underlying || ''; return sortDir === 'asc' ? va.localeCompare(vb) : vb.localeCompare(va);
      case 'width': va = a.width || 0; vb = b.width || 0; break;
      case 'entryCost': va = a.entryCost || 0; vb = b.entryCost || 0; break;
      case 'payoff': va = a.guaranteedPayoff || 0; vb = b.guaranteedPayoff || 0; break;
      case 'grossProfit': va = a.grossProfit || 0; vb = b.grossProfit || 0; break;
      case 'costs': va = a.totalCosts || 0; vb = b.totalCosts || 0; break;
      case 'netProfit': va = a.netProfitPerLot || 0; vb = b.netProfitPerLot || 0; break;
      case 'rom': va = a.returnOnMargin || 0; vb = b.returnOnMargin || 0; break;
      case 'dte': va = a.daysToExpiry || 0; vb = b.daysToExpiry || 0; break;
      default: return 0;
    }
    return sortDir === 'asc' ? va - vb : vb - va;
  });

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-bold text-slate-800">Box Spread Scanner</h2>
          <p className="text-sm text-slate-500 mt-0.5">
            Risk-free guaranteed payoff: BUY CE K1 + SELL PE K1 + SELL CE K2 + BUY PE K2 = Payoff (K2-K1)
          </p>
        </div>
        <div className="flex items-center gap-3">
          <div className="flex bg-zinc-800 rounded-lg p-1">
            {['ALL', 'NIFTY', 'BANKNIFTY'].map((u) => (
              <button key={u} onClick={() => setUnderlying(u)}
                className={`px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${
                  underlying === u ? 'bg-blue-600 text-white' : 'text-zinc-400 hover:text-white'
                }`}>
                {u === 'ALL' ? 'All' : u}
              </button>
            ))}
          </div>
          <button onClick={() => refetch()} disabled={isLoading}
            className="px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded-lg text-sm font-medium disabled:opacity-50">
            {isLoading ? 'Scanning...' : 'Scan Now'}
          </button>
        </div>
      </div>

      {/* Summary Cards */}
      <div className="grid grid-cols-4 gap-4">
        <div className="bg-white rounded-xl border border-slate-200 p-4 shadow-sm">
          <p className="text-xs text-slate-500 uppercase">Opportunities</p>
          <p className="text-2xl font-bold text-slate-800 mt-1">{opps.length}</p>
        </div>
        <div className="bg-white rounded-xl border border-slate-200 p-4 shadow-sm">
          <p className="text-xs text-slate-500 uppercase">Total Profit/lot</p>
          <p className={`text-2xl font-bold mt-1 ${totalProfit >= 0 ? 'text-emerald-600' : 'text-red-500'}`}>
            {totalProfit > 0 ? '+' : ''}₹{Math.round(totalProfit).toLocaleString()}
          </p>
        </div>
        <div className="bg-white rounded-xl border border-slate-200 p-4 shadow-sm">
          <p className="text-xs text-slate-500 uppercase">Strategy</p>
          <p className="text-sm font-semibold text-slate-800 mt-1">Zero Risk</p>
          <p className="text-xs text-slate-500">Guaranteed payoff at expiry</p>
        </div>
        <div className="bg-white rounded-xl border border-slate-200 p-4 shadow-sm">
          <p className="text-xs text-slate-500 uppercase">How It Works</p>
          <p className="text-xs text-slate-600 mt-1">4-leg spread locks in profit when entry cost &lt; guaranteed payoff (K2-K1)</p>
        </div>
      </div>

      {/* Opportunities Table */}
      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden shadow-sm">
        <div className="px-6 py-4 border-b border-slate-200">
          <h2 className="text-lg font-semibold text-slate-800">
            Box Spread Opportunities ({opps.length})
          </h2>
        </div>

        {opps.length === 0 ? (
          <div className="text-center py-12 text-slate-400">
            <p className="text-sm">No box spread opportunities found.</p>
            <p className="text-xs mt-1">Box spreads require entry cost &lt; guaranteed payoff after costs.</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-xs text-slate-500 uppercase border-b border-slate-200 bg-slate-50">
                  <th onClick={() => toggleSort('underlying')} className="px-4 py-3 text-left cursor-pointer hover:text-blue-600 select-none">Underlying{sortIndicator('underlying')}</th>
                  <th onClick={() => toggleSort('width')} className="px-4 py-3 text-left cursor-pointer hover:text-blue-600 select-none">Width (pts){sortIndicator('width')}</th>
                  <th className="px-4 py-3 text-left">Legs</th>
                  <th onClick={() => toggleSort('entryCost')} className="px-4 py-3 text-right cursor-pointer hover:text-blue-600 select-none">Entry Cost{sortIndicator('entryCost')}</th>
                  <th onClick={() => toggleSort('payoff')} className="px-4 py-3 text-right cursor-pointer hover:text-blue-600 select-none">Guaranteed Payoff{sortIndicator('payoff')}</th>
                  <th onClick={() => toggleSort('grossProfit')} className="px-4 py-3 text-right cursor-pointer hover:text-blue-600 select-none">Gross Profit{sortIndicator('grossProfit')}</th>
                  <th onClick={() => toggleSort('costs')} className="px-4 py-3 text-right cursor-pointer hover:text-blue-600 select-none">Costs{sortIndicator('costs')}</th>
                  <th onClick={() => toggleSort('netProfit')} className="px-4 py-3 text-right cursor-pointer hover:text-blue-600 select-none">Net Profit/Lot{sortIndicator('netProfit')}</th>
                  <th onClick={() => toggleSort('rom')} className="px-4 py-3 text-right cursor-pointer hover:text-blue-600 select-none">RoM %{sortIndicator('rom')}</th>
                  <th onClick={() => toggleSort('dte')} className="px-4 py-3 text-center cursor-pointer hover:text-blue-600 select-none">DTE{sortIndicator('dte')}</th>
                </tr>
              </thead>
              <tbody>
              {sortedOpps.map((opp, idx) => (
                  <tr key={idx} className="border-b border-slate-100 hover:bg-slate-50">
                    <td className="px-4 py-3 text-sm font-medium text-slate-800">{opp.underlying}</td>
                    <td className="px-4 py-3 text-sm font-mono text-slate-700">{opp.width}</td>
                    <td className="px-4 py-3 text-xs font-mono text-slate-600 max-w-xs truncate" title={opp.legs}>
                      {opp.legs}
                    </td>
                    <td className="px-4 py-3 text-sm text-right font-mono text-slate-700">
                      ₹{opp.entryCost?.toFixed(1)}
                    </td>
                    <td className="px-4 py-3 text-sm text-right font-mono text-blue-600 font-semibold">
                      ₹{opp.guaranteedPayoff}
                    </td>
                    <td className={`px-4 py-3 text-sm text-right font-mono font-bold ${opp.grossProfitPts > 0 ? 'text-emerald-600' : 'text-red-500'}`}>
                      {opp.grossProfitPts > 0 ? '+' : ''}{opp.grossProfitPts?.toFixed(1)} pts
                    </td>
                    <td className="px-4 py-3 text-sm text-right font-mono text-red-500">
                      -₹{opp.totalCosts}
                    </td>
                    <td className={`px-4 py-3 text-sm text-right font-mono font-bold ${opp.netProfitPerLot > 0 ? 'text-emerald-600' : 'text-red-500'}`}>
                      {opp.netProfitPerLot > 0 ? '+' : ''}₹{opp.netProfitPerLot}
                    </td>
                    <td className="px-4 py-3 text-sm text-right font-mono text-slate-700">
                      {opp.returnOnMargin?.toFixed(2)}%
                    </td>
                    <td className="px-4 py-3 text-sm text-center text-slate-500">
                      {opp.daysToExpiry}d
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Info Box */}
      <div className="bg-blue-50 border border-blue-200 rounded-xl p-5">
        <h3 className="text-sm font-semibold text-blue-800 mb-2">How Box Spreads Work</h3>
        <div className="grid grid-cols-2 gap-4 text-xs text-blue-700">
          <div>
            <p className="font-semibold mb-1">Structure (4 legs):</p>
            <p>1. BUY CE at K1 (lower strike)</p>
            <p>2. SELL PE at K1 (lower strike)</p>
            <p>3. SELL CE at K2 (upper strike)</p>
            <p>4. BUY PE at K2 (upper strike)</p>
          </div>
          <div>
            <p className="font-semibold mb-1">At expiry, guaranteed payoff = K2 - K1</p>
            <p className="mt-1">If entry cost &lt; K2-K1, profit is locked in.</p>
            <p className="mt-1"><strong>Edge:</strong> ₹10-50/lot, 10-20 opportunities/month</p>
            <p><strong>Risk:</strong> Zero (if all 4 legs fill)</p>
          </div>
        </div>
      </div>
    </div>
  );
}

function HistoryTab({ historyData, historyLoading, summaryData, datesData, historyPage, setHistoryPage, selectedDate, setSelectedDate, strategyFilter, setStrategyFilter }) {
  const opportunities = historyData?.opportunities || [];
  const totalPages = historyData?.totalPages || 0;
  const totalElements = historyData?.totalElements || 0;
  const availableDates = datesData?.dates || [];
  const [expandedIdx, setExpandedIdx] = useState(null);
  const [sortKey, setSortKey] = useState(null);
  const [sortDir, setSortDir] = useState('desc');
  const [minEdgeFilter, setMinEdgeFilter] = useState(0);

  const STRATEGY_TABS = [
    { key: 'ALL', label: 'All', color: 'bg-slate-100 text-slate-700 border-slate-300' },
    { key: 'NORMAL_PARITY', label: 'Normal Parity', color: 'bg-blue-50 text-blue-700 border-blue-300' },
    { key: 'BID_PARITY', label: 'Bid Parity', color: 'bg-amber-50 text-amber-700 border-amber-300' },
    { key: 'BOX_SPREAD', label: 'Box Spread', color: 'bg-purple-50 text-purple-700 border-purple-300' },
  ];

  const STRATEGY_COLORS = {
    NORMAL_PARITY: { bg: 'bg-blue-50', text: 'text-blue-700', border: 'border-blue-200' },
    BID_PARITY: { bg: 'bg-amber-50', text: 'text-amber-700', border: 'border-amber-200' },
    BOX_SPREAD: { bg: 'bg-purple-50', text: 'text-purple-700', border: 'border-purple-200' },
  };

  const STRATEGY_LABELS = {
    NORMAL_PARITY: 'Normal Parity',
    BID_PARITY: 'Bid Parity',
    BOX_SPREAD: 'Box Spread',
  };

  const STATUS_STYLE = {
    RUNNING: { bg: 'bg-emerald-50', text: 'text-emerald-700', border: 'border-emerald-200', icon: '●' },
    EXPIRED: { bg: 'bg-slate-100', text: 'text-slate-500', border: 'border-slate-200', icon: '○' },
    CLOSED: { bg: 'bg-blue-50', text: 'text-blue-700', border: 'border-blue-200', icon: '✓' },
  };

  function toggleSort(key) {
    if (sortKey === key) {
      setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    } else {
      setSortKey(key);
      setSortDir('desc');
    }
  }

  function sortIndicator(key) {
    if (sortKey !== key) return ' ↕';
    return sortDir === 'asc' ? ' ↑' : ' ↓';
  }

  const filteredOpps = minEdgeFilter > 0
    ? opportunities.filter(o => (o.edgeAfterCosts || 0) >= minEdgeFilter)
    : opportunities;

  const sortedOpps = [...filteredOpps].sort((a, b) => {
    if (!sortKey) return 0;
    let va, vb;
    switch (sortKey) {
      case 'date': va = a.scanTime || ''; vb = b.scanTime || ''; return sortDir === 'asc' ? va.localeCompare(vb) : vb.localeCompare(va);
      case 'strategy': va = a.strategyType || ''; vb = b.strategyType || ''; return sortDir === 'asc' ? va.localeCompare(vb) : vb.localeCompare(va);
      case 'underlying': va = a.underlying || ''; vb = b.underlying || ''; return sortDir === 'asc' ? va.localeCompare(vb) : vb.localeCompare(va);
      case 'strike': va = a.strike || 0; vb = b.strike || 0; break;
      case 'edge': va = a.edgeAfterCosts || 0; vb = b.edgeAfterCosts || 0; break;
        case 'pnl': va = a.pnlAfterCosts ?? 0; vb = b.pnlAfterCosts ?? 0; break;
      case 'status': va = a.status || ''; vb = b.status || ''; return sortDir === 'asc' ? va.localeCompare(vb) : vb.localeCompare(va);
      default: return 0;
    }
    return sortDir === 'asc' ? va - vb : vb - va;
  });

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-4 flex-wrap">
        <label className="text-sm text-slate-600 font-medium">Date:</label>
        <select value={selectedDate} onChange={(e) => { setSelectedDate(e.target.value); setHistoryPage(0); }}
          className="px-3 py-2 border border-slate-200 rounded-lg text-sm text-slate-700 bg-white focus:outline-none focus:ring-2 focus:ring-blue-500">
          <option value="">All Dates</option>
          {availableDates.map((d) => (<option key={d} value={d}>{d}</option>))}
        </select>
      </div>

      <div className="flex gap-2 flex-wrap items-center">
        {STRATEGY_TABS.map(tab => (
          <button key={tab.key} onClick={() => { setStrategyFilter(tab.key); setHistoryPage(0); }}
            className={`px-4 py-2 text-sm font-medium rounded-lg border transition-all ${
              strategyFilter === tab.key
                ? `${tab.color} shadow-sm font-semibold`
                : 'bg-white text-slate-500 border-slate-200 hover:bg-slate-50'
            }`}>
            {tab.label}
          </button>
        ))}
        <div className="flex items-center gap-2 ml-4 border-l border-slate-200 pl-4">
          <label className="text-xs font-medium text-slate-500">Min Edge</label>
          <input type="number" value={minEdgeFilter} onChange={(e) => setMinEdgeFilter(Number(e.target.value) || 0)}
            placeholder="0" className="w-24 px-2 py-1.5 border border-slate-200 rounded-md text-sm font-mono focus:ring-2 focus:ring-blue-500 focus:border-blue-500" />
          <span className="text-xs text-slate-400">₹</span>
        </div>
      </div>

      {summaryData && (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <StatCard label="Total Opportunities" value={strategyFilter === 'ALL' ? (summaryData.totalOpportunities || totalElements) : totalElements} color="text-blue-600" />
          <StatCard label="Edge Detected" value={`₹${(strategyFilter === 'ALL' ? (summaryData.totalEdgeDetected || 0) : opportunities.reduce((s, o) => s + (o.edgeAfterCosts || 0), 0)).toLocaleString('en-IN', { maximumFractionDigits: 2 })}`} color="text-emerald-600" />
          <StatCard label="Total P&L" value={`₹${(summaryData.totalPnlAfterCosts || 0).toLocaleString()}`} color={(summaryData.totalPnlAfterCosts || 0) >= 0 ? 'text-emerald-600' : 'text-red-500'} />
          <StatCard label="Win Rate" value={`${summaryData.winRate || 0}%`} color="text-blue-600" />
        </div>
      )}

      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden shadow-sm">
        <div className="px-6 py-4 border-b border-slate-200 flex items-center justify-between">
          <h2 className="text-lg font-semibold text-slate-800">History {minEdgeFilter > 0 ? `(${filteredOpps.length} of ${totalElements})` : `(${totalElements} total)`}</h2>
          <span className="text-xs text-slate-400">Edge = at detection | P&L = current</span>
        </div>

        {historyLoading ? (
          <div className="px-6 py-12 text-center text-slate-400">Loading...</div>
        ) : opportunities.length === 0 ? (
          <div className="px-6 py-12 text-center text-slate-400">
            <p className="text-lg">No history yet</p>
            <p className="text-sm mt-1">Scan results will appear here</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-xs text-slate-500 uppercase border-b border-slate-200 bg-slate-50">
                  <th className="px-3 py-3 text-left cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('date')}>Date{sortIndicator('date')}</th>
                  <th className="px-3 py-3 text-left cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('strategy')}>Strategy{sortIndicator('strategy')}</th>
                  <th className="px-3 py-3 text-left cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('underlying')}>Underlying{sortIndicator('underlying')}</th>
                  <th className="px-3 py-3 text-right cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('strike')}>Strike{sortIndicator('strike')}</th>
                  <th className="px-3 py-3 text-right cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('edge')}>Edge (₹){sortIndicator('edge')}</th>
                  <th className="px-3 py-3 text-right cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('pnl')}>P&L (₹){sortIndicator('pnl')}</th>
                  <th className="px-3 py-3 text-center cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('status')}>Status{sortIndicator('status')}</th>
                </tr>
              </thead>
              <tbody>
                {sortedOpps.map((opp, idx) => {
                  const isExpanded = expandedIdx === idx;
                  const costs = opp.costBreakdown || {};
                  const lotSize = opp.lotSize || costs.lotSize || 65;
                  const livePnl = opp.pnlAfterCosts != null ? opp.pnlAfterCosts : null;
                  const st = STATUS_STYLE[opp.status] || STATUS_STYLE.EXPIRED;
                  const sc = STRATEGY_COLORS[opp.strategyType] || STRATEGY_COLORS.NORMAL_PARITY;
                  return (
                    <React.Fragment key={opp.id || idx}>
                      <tr onClick={() => setExpandedIdx(isExpanded ? null : idx)}
                        className={`border-b border-slate-100 cursor-pointer transition-colors ${isExpanded ? 'bg-blue-50' : 'hover:bg-slate-50'}`}>
                        <td className="px-3 py-3 text-sm text-slate-700 whitespace-nowrap">{formatIstDateTime(opp.scanTime)}</td>
                        <td className="px-3 py-3">
                          <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border ${sc.bg} ${sc.text} ${sc.border}`}>
                            {STRATEGY_LABELS[opp.strategyType] || opp.strategyType || 'Normal Parity'}
                          </span>
                        </td>
                        <td className="px-3 py-3 text-sm text-slate-700">{opp.underlying}</td>
                        <td className="px-3 py-3 text-sm font-mono font-medium text-slate-900">{opp.strike}</td>
                        <td className={`px-3 py-3 text-sm text-right font-mono font-bold ${(opp.edgeAfterCosts || 0) > 0 ? 'text-emerald-600' : 'text-red-500'}`}>
                          {fmtCurrency(opp.edgeAfterCosts, 0)}
                        </td>
                        <td className={`px-3 py-3 text-sm text-right font-mono font-bold ${(livePnl || 0) > 0 ? 'text-emerald-600' : (livePnl || 0) < 0 ? 'text-red-500' : 'text-slate-400'}`}>
                          {livePnl != null ? fmtCurrency(livePnl, 0) : '--'}
                        </td>
                        <td className="px-3 py-3 text-center">
                          <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border ${st.bg} ${st.text} ${st.border}`}>
                            {st.icon} {opp.status || 'EXPIRED'}
                          </span>
                        </td>
                      </tr>
                      {isExpanded && (
                        <tr>
                          <td colSpan={7} className="px-0 py-0">
                            <div className="bg-slate-50 border-b border-slate-200 px-6 py-4">
                              <div className="grid grid-cols-2 md:grid-cols-5 gap-4 mb-4">
                                <div className="bg-white rounded-lg p-3 border border-slate-200">
                                  <p className="text-xs text-slate-500">Action</p>
                                  <p className="text-sm font-medium text-slate-800">{ACTION_LABELS[opp.action] || opp.action}</p>
                                </div>
                                <div className="bg-white rounded-lg p-3 border border-slate-200">
                                  <p className="text-xs text-slate-500">DTE</p>
                                  <p className="text-sm font-medium text-slate-800">{fmt(opp.daysToExpiry, 0)} days</p>
                                </div>
                                <div className="bg-white rounded-lg p-3 border border-slate-200">
                                  <p className="text-xs text-slate-500">Lot Size</p>
                                  <p className="text-sm font-medium text-slate-800">{lotSize}</p>
                                </div>
                                <div className="bg-white rounded-lg p-3 border border-slate-200">
                                  <p className="text-xs text-slate-500">Expiry</p>
                                  <p className="text-sm font-medium text-slate-800">{opp.expiryDate || '--'}</p>
                                </div>
                                <div className="bg-white rounded-lg p-3 border border-slate-200">
                                  <p className="text-xs text-slate-500">Strategy</p>
                                  <p className="text-sm font-medium text-slate-800">{STRATEGY_LABELS[opp.strategyType] || opp.strategyType || 'Normal Parity'}</p>
                                </div>
                              </div>

                              {opp.legs && (
                                <div className="mb-4">
                                  <p className="text-xs text-slate-500 uppercase mb-1 font-medium">Trade Legs</p>
                                  <p className="text-sm text-slate-800 font-mono bg-white rounded-lg p-3 border border-slate-200">{opp.legs}</p>
                                </div>
                              )}

                              <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-4">
                                <div className="bg-white rounded-lg p-3 border border-slate-200">
                                  <p className="text-xs text-slate-500">Spot Price</p>
                                  <p className="text-sm font-mono font-bold text-slate-800">{fmtCurrency(opp.spotPrice, 2)}</p>
                                </div>
                                <div className="bg-white rounded-lg p-3 border border-slate-200">
                                  <p className="text-xs text-slate-500">Futures Price</p>
                                  <p className="text-sm font-mono font-bold text-slate-800">{fmtCurrency(opp.futuresPrice, 2)}</p>
                                </div>
                                <div className="bg-white rounded-lg p-3 border border-slate-200">
                                  <p className="text-xs text-slate-500">CE Entry / Bid / Ask</p>
                                  <p className="text-sm font-mono font-bold text-slate-800">{fmtCurrency(opp.ceEntryPrice, 2)}</p>
                                  <p className="text-[10px] text-slate-400 font-mono">BID {fmt(opp.ceBid, 2)} / ASK {fmt(opp.ceAsk, 2)}</p>
                                </div>
                                <div className="bg-white rounded-lg p-3 border border-slate-200">
                                  <p className="text-xs text-slate-500">PE Entry / Bid / Ask</p>
                                  <p className="text-sm font-mono font-bold text-slate-800">{fmtCurrency(opp.peEntryPrice, 2)}</p>
                                  <p className="text-[10px] text-slate-400 font-mono">BID {fmt(opp.peBid, 2)} / ASK {fmt(opp.peAsk, 2)}</p>
                                </div>
                              </div>

                              {(opp.ceSymbol || opp.peSymbol || opp.futSymbol) && (
                                <div className="mb-4 flex gap-4 text-xs text-slate-500 font-mono">
                                  {opp.ceSymbol && <span>CE: <span className="text-slate-700 font-medium">{opp.ceSymbol}</span></span>}
                                  {opp.peSymbol && <span>PE: <span className="text-slate-700 font-medium">{opp.peSymbol}</span></span>}
                                  {opp.futSymbol && <span>FUT: <span className="text-slate-700 font-medium">{opp.futSymbol}</span></span>}
                                </div>
                              )}

                              <div className="grid grid-cols-2 gap-4">
                                <div className="bg-white rounded-lg p-4 border border-slate-200">
                                  <p className="text-xs text-slate-500 uppercase mb-2 font-medium">Profit / Loss</p>
                                  <div className="space-y-1.5 text-sm">
                                    <div className="flex justify-between"><span className="text-slate-600">Edge at Detection</span><span className="font-mono font-bold text-emerald-600">{fmtCurrency(opp.edgeAfterCosts, 0)}</span></div>
                                    <div className="flex justify-between"><span className="text-slate-600">Edge (pts)</span><span className="font-mono font-bold text-emerald-600">{fmt(opp.edgePoints, 1)}</span></div>
                                    {opp.pnlAmount != null ? (
                                      <>
                                        <div className="flex justify-between"><span className="text-slate-600">CE Exit</span><span className="font-mono text-slate-700">{fmtCurrency(opp.ceExitPrice, 2)}</span></div>
                                        <div className="flex justify-between"><span className="text-slate-600">PE Exit</span><span className="font-mono text-slate-700">{fmtCurrency(opp.peExitPrice, 2)}</span></div>
                                        {opp.exitTime && <div className="flex justify-between"><span className="text-slate-600">Exit Time</span><span className="font-mono text-slate-700">{formatIstTime(opp.exitTime)}</span></div>}
                                        <div className="flex justify-between border-t border-slate-200 pt-1.5">
                                          <span className="text-slate-800 font-semibold">Realized P&L</span>
                                          <span className={`font-mono font-bold ${(opp.pnlAmount || 0) >= 0 ? 'text-emerald-600' : 'text-red-500'}`}>{fmtCurrency(opp.pnlAmount, 0)}</span>
                                        </div>
                                      </>
                                    ) : (
                                      <div className="flex justify-between border-t border-slate-200 pt-1.5">
                                        <span className="text-slate-800 font-semibold">Current P&L</span>
                                        <span className="text-slate-400 font-mono">Not yet resolved</span>
                                      </div>
                                    )}
                                  </div>
                                </div>
                                {costs.totalCosts != null && (
                                  <div className="bg-white rounded-lg p-4 border border-slate-200">
                                    <p className="text-xs text-slate-500 uppercase mb-2 font-medium">Transaction Costs</p>
                                    <div className="space-y-0.5 text-xs">
                                      {costs.stt != null && <div className="flex justify-between"><span className="text-slate-500">STT</span><span className="font-mono text-slate-600">{fmtCurrency(costs.stt, 2)}</span></div>}
                                      {costs.brokerage != null && <div className="flex justify-between"><span className="text-slate-500">Brokerage</span><span className="font-mono text-slate-600">{fmtCurrency(costs.brokerage, 0)}</span></div>}
                                      {costs.exchange != null && <div className="flex justify-between"><span className="text-slate-500">Exchange</span><span className="font-mono text-slate-600">{fmtCurrency(costs.exchange, 2)}</span></div>}
                                      {costs.sebi != null && costs.gst != null && <div className="flex justify-between"><span className="text-slate-500">SEBI + GST</span><span className="font-mono text-slate-600">{fmtCurrency(costs.sebi + costs.gst, 2)}</span></div>}
                                      <div className="flex justify-between border-t border-slate-200 pt-1"><span className="text-slate-800 font-semibold">Total Costs</span><span className="font-mono font-bold text-red-500">{fmtCurrency(costs.totalCosts, 0)}</span></div>
                                    </div>
                                  </div>
                                )}
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

        {totalPages > 1 && (
          <div className="px-6 py-3 border-t border-slate-200 flex items-center justify-between">
            <span className="text-sm text-slate-500">Page {historyPage + 1} of {totalPages}</span>
            <div className="flex gap-2">
              <button onClick={() => setHistoryPage(Math.max(0, historyPage - 1))} disabled={historyPage === 0}
                className="px-3 py-1 text-sm border border-slate-200 rounded-md disabled:opacity-40 text-slate-600 hover:bg-slate-50">Previous</button>
              <button onClick={() => setHistoryPage(Math.min(totalPages - 1, historyPage + 1))} disabled={historyPage >= totalPages - 1}
                className="px-3 py-1 text-sm border border-slate-200 rounded-md disabled:opacity-40 text-slate-600 hover:bg-slate-50">Next</button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function PositionsTab() {
  const [selectedPositions, setSelectedPositions] = useState(new Set());
  const [exiting, setExiting] = useState(false);

  const { data: posData, refetch, isLoading } = useQuery({
    queryKey: ['arb-positions'],
    queryFn: async () => {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/positions`);
      return res.data;
    },
    refetchInterval: 10000,
    staleTime: 5000,
  });

  const positions = posData?.positions || [];
  const totalPnl = posData?.totalPnl || 0;
  const count = posData?.count || 0;

  const toggleSelect = (symbol) => {
    setSelectedPositions(prev => {
      const next = new Set(prev);
      if (next.has(symbol)) next.delete(symbol);
      else next.add(symbol);
      return next;
    });
  };

  const selectAll = () => {
    if (selectedPositions.size === positions.length) {
      setSelectedPositions(new Set());
    } else {
      setSelectedPositions(new Set(positions.map(p => p.tradingsymbol)));
    }
  };

  const exitSelected = async () => {
    if (selectedPositions.size === 0) return;
    setExiting(true);
    try {
      for (const symbol of selectedPositions) {
        const pos = positions.find(p => p.tradingsymbol === symbol);
        if (!pos) continue;
        const txType = pos.quantity > 0 ? 'SELL' : 'BUY';
        const absQty = Math.abs(pos.quantity);
        await axios.post(`${API_BASE}/api/option-arbitrage/exit-position`, null, {
          params: { symbol: pos.tradingsymbol, exchange: pos.exchange, product: pos.product, quantity: absQty, transactionType: txType }
        });
      }
      setSelectedPositions(new Set());
      setTimeout(refetch, 2000);
    } catch (e) {
      showToast('Exit failed: ' + (e.response?.data?.error || e.message), 'error');
    } finally {
      setExiting(false);
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-bold text-slate-800">Live NFO Positions</h2>
          <p className="text-sm text-slate-500 mt-0.5">Real-time positions from Zerodha. Only NFO segment shown.</p>
        </div>
        <div className="flex items-center gap-3">
          <button onClick={() => refetch()} className="px-4 py-2 bg-zinc-800 text-white rounded-lg text-sm font-medium hover:bg-zinc-700 transition">
            Refresh
          </button>
          {selectedPositions.size > 0 && (
            <button onClick={exitSelected} disabled={exiting}
              className="px-4 py-2 bg-red-600 text-white rounded-lg text-sm font-bold hover:bg-red-700 transition disabled:opacity-50">
              {exiting ? 'Exiting...' : `Exit ${selectedPositions.size} Position(s)`}
            </button>
          )}
        </div>
      </div>

      <div className="grid grid-cols-3 gap-4">
        <div className="bg-white rounded-xl border border-slate-200 p-4 shadow-sm">
          <p className="text-xs text-slate-500 uppercase">Total Positions</p>
          <p className="text-2xl font-bold text-slate-800 mt-1">{count}</p>
        </div>
        <div className="bg-white rounded-xl border border-slate-200 p-4 shadow-sm">
          <p className="text-xs text-slate-500 uppercase">Total P&L</p>
          <p className={`text-2xl font-bold mt-1 ${totalPnl >= 0 ? 'text-emerald-600' : 'text-red-500'}`}>
            {totalPnl !== 0 ? `${totalPnl >= 0 ? '+' : ''}₹${Math.abs(totalPnl).toFixed(0)}` : '₹0'}
          </p>
        </div>
        <div className="bg-white rounded-xl border border-slate-200 p-4 shadow-sm">
          <p className="text-xs text-slate-500 uppercase">Selected</p>
          <p className="text-2xl font-bold text-blue-600 mt-1">{selectedPositions.size}</p>
        </div>
      </div>

      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden shadow-sm">
        {isLoading ? (
          <div className="px-6 py-12 text-center text-slate-400">Loading positions...</div>
        ) : positions.length === 0 ? (
          <div className="px-6 py-12 text-center text-slate-400">
            <p className="text-lg">No NFO positions</p>
            <p className="text-sm mt-1">Place an arbitrage trade to see positions here</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-xs text-slate-500 uppercase border-b border-slate-200 bg-slate-50">
                  <th className="px-4 py-3 w-10">
                    <input type="checkbox" checked={selectedPositions.size === positions.length && positions.length > 0}
                      onChange={selectAll} className="rounded border-slate-300" />
                  </th>
                  <th className="px-4 py-3 text-left">Symbol</th>
                  <th className="px-4 py-3 text-left">Type</th>
                  <th className="px-4 py-3 text-right">Qty</th>
                  <th className="px-4 py-3 text-right">Avg Price</th>
                  <th className="px-4 py-3 text-right">LTP</th>
                  <th className="px-4 py-3 text-right">P&L</th>
                  <th className="px-4 py-3 text-right">MTM</th>
                  <th className="px-4 py-3 text-center">Action</th>
                </tr>
              </thead>
              <tbody>
                {positions.map((pos, i) => (
                  <tr key={i} className="border-b border-slate-100 hover:bg-slate-50">
                    <td className="px-4 py-3">
                      <input type="checkbox" checked={selectedPositions.has(pos.tradingsymbol)}
                        onChange={() => toggleSelect(pos.tradingsymbol)} className="rounded border-slate-300" />
                    </td>
                    <td className="px-4 py-3 text-sm font-mono font-semibold text-slate-800">{pos.tradingsymbol}</td>
                    <td className="px-4 py-3">
                      <span className={`px-2 py-0.5 rounded text-xs font-bold ${pos.instrumentType === 'CE' ? 'bg-blue-100 text-blue-700' : pos.instrumentType === 'PE' ? 'bg-purple-100 text-purple-700' : pos.instrumentType === 'FUT' ? 'bg-amber-100 text-amber-700' : 'bg-slate-100 text-slate-600'}`}>
                        {pos.instrumentType || '--'}
                      </span>
                    </td>
                    <td className={`px-4 py-3 text-sm text-right font-mono font-medium ${pos.quantity > 0 ? 'text-blue-600' : pos.quantity < 0 ? 'text-red-600' : 'text-slate-400'}`}>
                      {pos.quantity > 0 ? '+' : ''}{pos.quantity}
                    </td>
                    <td className="px-4 py-3 text-sm text-right font-mono text-slate-700">{fmtCurrency(pos.avgPrice, 2)}</td>
                    <td className="px-4 py-3 text-sm text-right font-mono text-slate-700">{fmtCurrency(pos.ltp, 2)}</td>
                    <td className={`px-4 py-3 text-sm text-right font-mono font-bold ${pos.pnl >= 0 ? 'text-emerald-600' : 'text-red-500'}`}>
                      {fmtCurrency(pos.pnl, 2)}
                    </td>
                    <td className={`px-4 py-3 text-sm text-right font-mono font-bold ${(pos.mtm || 0) >= 0 ? 'text-emerald-600' : 'text-red-500'}`}>
                      {fmtCurrency(pos.mtm, 2)}
                    </td>
                    <td className="px-4 py-3 text-center">
                      <button onClick={() => toggleSelect(pos.tradingsymbol)}
                        className="px-2 py-1 rounded text-xs font-semibold bg-red-100 text-red-600 hover:bg-red-200 transition">
                        Exit
                      </button>
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
    refetchInterval: 5000
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
      {/* Header Diagnostic Banner */}
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

        <div className="flex items-center gap-3">
          <button
            onClick={runCycle}
            disabled={busyRun}
            className="px-5 py-2.5 bg-blue-600 hover:bg-blue-700 text-white rounded-xl font-bold text-sm shadow-md transition flex items-center gap-2 disabled:opacity-50"
          >
            {busyRun ? '⚡ Scanning & Executing...' : '▶ Run Scan & Execute Now'}
          </button>
        </div>
      </div>

      {/* Instant Result / Reason Feedback Banner */}
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

      {/* Main Grid: Controls + Logs */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        
        {/* Card 1: Normal Parity Config */}
        <div className="bg-white rounded-xl p-5 border border-slate-200 shadow-sm space-y-4">
          <div className="flex items-center justify-between border-b border-slate-100 pb-3">
            <div>
              <h3 className="font-bold text-slate-800 text-base">Normal Parity</h3>
              <p className="text-xs text-slate-400">Regular Scanner &bull; Conversion / Reversal</p>
            </div>
            <button
              onClick={() => updateSetting('normalParityEnabled', !isNormalEnabled)}
              className={`w-12 h-6 rounded-full transition p-1 ${isNormalEnabled ? 'bg-blue-600' : 'bg-slate-300'}`}
            >
              <div className={`w-4 h-4 rounded-full bg-white transition transform ${isNormalEnabled ? 'translate-x-6' : 'translate-x-0'}`} />
            </button>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="text-xs font-semibold text-slate-500 uppercase block mb-1">Entry Edge (₹)</label>
              <input
                type="number"
                defaultValue={settings?.normalEntryEdge || 1500}
                onBlur={(e) => updateSetting('normalEntryEdge', e.target.value)}
                className="w-full bg-slate-50 border border-slate-200 rounded-lg p-2 text-sm font-mono text-slate-700 outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
            <div>
              <label className="text-xs font-semibold text-slate-500 uppercase block mb-1">Exit Edge (₹)</label>
              <input
                type="number"
                defaultValue={settings?.normalExitEdge || 800}
                onBlur={(e) => updateSetting('normalExitEdge', e.target.value)}
                className="w-full bg-slate-50 border border-slate-200 rounded-lg p-2 text-sm font-mono text-slate-700 outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
          </div>

          <div>
            <label className="text-xs font-semibold text-slate-500 uppercase block mb-1">Max Concurrent Sets</label>
            <input
              type="number"
              defaultValue={settings?.normalMaxSets || 3}
              onBlur={(e) => updateSetting('normalMaxSets', e.target.value)}
              className="w-full bg-slate-50 border border-slate-200 rounded-lg p-2 text-sm font-mono text-slate-700 outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
        </div>

        {/* Card 2: Bid Parity Config (Highlighted Amber) */}
        <div className="bg-amber-50/50 rounded-xl p-5 border border-amber-200 shadow-sm space-y-4">
          <div className="flex items-center justify-between border-b border-amber-200/60 pb-3">
            <div>
              <h3 className="font-bold text-amber-900 text-base">Bid Parity (Guaranteed Fills)</h3>
              <p className="text-xs text-amber-700">Bid-price depth scanner &bull; Immediate Execution</p>
            </div>
            <button
              onClick={() => updateSetting('bidParityEnabled', !isBidEnabled)}
              className={`w-12 h-6 rounded-full transition p-1 ${isBidEnabled ? 'bg-amber-600' : 'bg-slate-300'}`}
            >
              <div className={`w-4 h-4 rounded-full bg-white transition transform ${isBidEnabled ? 'translate-x-6' : 'translate-x-0'}`} />
            </button>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="text-xs font-semibold text-amber-800 uppercase block mb-1">Min Entry Edge (₹)</label>
              <input
                type="number"
                defaultValue={settings?.bidEntryEdge || 300}
                onBlur={(e) => updateSetting('bidEntryEdge', e.target.value)}
                className="w-full bg-white border border-amber-200 rounded-lg p-2 text-sm font-mono text-slate-700 outline-none focus:ring-2 focus:ring-amber-500"
              />
            </div>
            <div>
              <label className="text-xs font-semibold text-amber-800 uppercase block mb-1">Exit Edge (₹)</label>
              <input
                type="number"
                defaultValue={settings?.bidExitEdge || 100}
                onBlur={(e) => updateSetting('bidExitEdge', e.target.value)}
                className="w-full bg-white border border-amber-200 rounded-lg p-2 text-sm font-mono text-slate-700 outline-none focus:ring-2 focus:ring-amber-500"
              />
            </div>
          </div>

          <div>
            <label className="text-xs font-semibold text-amber-800 uppercase block mb-1">Max Concurrent Sets</label>
            <input
              type="number"
              defaultValue={settings?.bidMaxSets || 3}
              onBlur={(e) => updateSetting('bidMaxSets', e.target.value)}
              className="w-full bg-white border border-amber-200 rounded-lg p-2 text-sm font-mono text-slate-700 outline-none focus:ring-2 focus:ring-amber-500"
            />
          </div>
        </div>

        {/* Card 3: Execution Controls & Fast Scan Interval */}
        <div className="bg-white rounded-xl p-5 border border-slate-200 shadow-sm space-y-4">
          <h3 className="font-bold text-slate-800 text-base border-b border-slate-100 pb-3">Execution Speed & Interval</h3>

          <div>
            <label className="text-xs font-semibold text-slate-500 uppercase block mb-1">Scan Interval Frequency</label>
            <select
              value={scanInterval}
              onChange={(e) => {
                setScanInterval(e.target.value);
                updateSetting('scanInterval', e.target.value);
              }}
              className="w-full bg-slate-50 border border-slate-200 text-slate-800 text-xs font-bold rounded-lg p-2.5 focus:ring-2 focus:ring-blue-500 outline-none"
            >
              <option value="1">⚡ 1 Second (High Frequency Arbitrage)</option>
              <option value="2">🚀 2 Seconds</option>
              <option value="5">⏱️ 5 Seconds</option>
              <option value="10">10 Seconds</option>
              <option value="30">30 Seconds</option>
              <option value="300">300 Seconds (5 Min)</option>
            </select>
          </div>

          <div className="pt-2 border-t border-slate-100">
            <p className="text-xs font-semibold text-slate-500 uppercase mb-2">Target Underlyings</p>
            <div className="flex flex-wrap gap-1.5">
              {['ALL', 'NIFTY', 'BANKNIFTY', 'MIDCPNIFTY', 'FINNIFTY'].map((u) => (
                <button
                  key={u}
                  onClick={() => updateSetting('targetUnderlyings', u)}
                  className="px-2.5 py-1 text-xs font-bold rounded-lg bg-blue-50 text-blue-700 hover:bg-blue-100 transition"
                >
                  {u}
                </button>
              ))}
            </div>
          </div>
        </div>

      </div>

      {/* Real-time Execution Audit Log Feed */}
      <div className="bg-slate-900 rounded-xl p-5 text-white shadow-md space-y-3">
        <div className="flex items-center justify-between border-b border-slate-800 pb-3">
          <div className="flex items-center gap-2">
            <div className="w-2.5 h-2.5 rounded-full bg-emerald-400 animate-pulse" />
            <h3 className="text-sm font-bold tracking-wide uppercase text-slate-200">Real-Time Execution Audit Feed</h3>
          </div>
          <button
            onClick={() => refetchLogs()}
            className="text-xs text-blue-400 hover:text-blue-300 font-semibold"
          >
            🔄 Refresh Feed
          </button>
        </div>

        <div className="font-mono text-xs space-y-2 max-h-60 overflow-y-auto pr-2">
          {!auditLogs || auditLogs.length === 0 ? (
            <p className="text-slate-500 italic py-4 text-center">No execution events logged yet.</p>
          ) : (
            auditLogs.map((log, idx) => (
              <div key={idx} className="flex items-start gap-3 py-1 border-b border-slate-800/50">
                <span className="text-slate-500 shrink-0">{log.timeFormatted}</span>
                <span className={`px-1.5 py-0.5 rounded text-[10px] font-bold shrink-0 ${
                  log.level === 'SUCCESS' ? 'bg-emerald-900 text-emerald-300'
                  : log.level === 'WARN' ? 'bg-amber-900 text-amber-300'
                  : log.level === 'ERROR' ? 'bg-red-900 text-red-300'
                  : 'bg-blue-900 text-blue-300'
                }`}>
                  {log.category}
                </span>
                <span className="text-slate-300">{log.message}</span>
              </div>
            ))
          )}
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
