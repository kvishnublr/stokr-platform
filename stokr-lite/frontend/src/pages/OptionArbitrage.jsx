import React, { useState, useEffect, useCallback } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';

const API_BASE = import.meta.env.VITE_API_URL || '';

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
      alert('Cancel failed: ' + (e.response?.data?.error || e.message));
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
        alert('Failed to build basket: ' + (data.error || 'Unknown error'));
        return;
      }
      setBasketData(data);
      setShowPreview(true);
    } catch (err) {
      alert('Basket error: ' + (err.response?.data?.error || err.message));
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
    queryKey: ['option-arb-history', historyPage, selectedDate],
    queryFn: async () => {
      // If a specific date is selected, filter by date. Otherwise show ALL.
      if (selectedDate) {
        const res = await axios.get(`${API_BASE}/api/option-arbitrage/history`, {
          params: { page: historyPage, size: HISTORY_SIZE, date: selectedDate }
        });
        return res.data;
      }
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/history`, {
        params: { page: historyPage, size: HISTORY_SIZE }
      });
      return res.data;
    },
    enabled: activeTab === 'history',
    staleTime: 30000,
  });

  const { data: summaryData } = useQuery({
    queryKey: ['option-arb-history-summary', selectedDate],
    queryFn: async () => {
      const params = selectedDate ? { date: selectedDate } : {};
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

  const opportunities = data?.opportunities || [];
  const summary = data?.summary || {};
  const totalEdge = displayOpps.reduce((sum, o) => sum + (o.edgeAfterCosts || 0), 0);
  const isLoading = scanLoading || todayLoading || cachedLoading;

  const sampleOpps = [
    { id: -1, type: 'PARITY_BREAK', action: 'CONVERSION', underlying: 'NIFTY', strike: 24350, spotPrice: 24345.20, futuresPrice: 24362.10, cePrice: 198.50, pePrice: 205.30, ceBid: 197.0, ceAsk: 200.0, peBid: 204.0, peAsk: 206.5, edgePoints: 14.2, edgeAfterCosts: 728.0, daysToExpiry: 3, confidence: 85, legs: 'BUY CE 24350 + SELL PE 24350 + SELL FUT', description: 'Sample — NIFTY 24350 conversion, edge ₹728/lot', detectedAt: new Date().toISOString(), _sample: true },
    { id: -2, type: 'PARITY_BREAK', action: 'CONVERSION', underlying: 'NIFTY', strike: 24300, spotPrice: 24345.20, futuresPrice: 24362.10, cePrice: 225.80, pePrice: 182.40, ceBid: 224.5, ceAsk: 227.0, peBid: 181.0, peAsk: 183.5, edgePoints: 11.8, edgeAfterCosts: 605.0, daysToExpiry: 3, confidence: 80, legs: 'BUY CE 24300 + SELL PE 24300 + SELL FUT', description: 'Sample — NIFTY 24300 conversion, edge ₹605/lot', detectedAt: new Date().toISOString(), _sample: true },
    { id: -3, type: 'PARITY_BREAK', action: 'CONVERSION', underlying: 'BANKNIFTY', strike: 58500, spotPrice: 58492.30, futuresPrice: 58602.50, cePrice: 412.60, pePrice: 425.80, ceBid: 411.0, ceAsk: 414.0, peBid: 424.5, peAsk: 427.0, edgePoints: 12.5, edgeAfterCosts: 515.0, daysToExpiry: 10, confidence: 82, legs: 'BUY CE 58500 + SELL PE 58500 + SELL FUT', description: 'Sample — BANKNIFTY 58500 conversion, edge ₹515/lot', detectedAt: new Date().toISOString(), _sample: true },
  ];

  const displayOpps = opportunities.length > 0 ? opportunities : sampleOpps;

  return (
    <div className="space-y-6">
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
      /> : activeTab === 'positions' ? <PositionsTab /> : activeTab === 'auto' ? <AutoExecTab /> : <HistoryTab
        historyData={historyData} historyLoading={historyLoading}
        summaryData={summaryData} datesData={datesData}
        historyPage={historyPage} setHistoryPage={setHistoryPage}
        selectedDate={selectedDate} setSelectedDate={setSelectedDate}
      />}
    </div>
  );
}



function LiveScanTab({ autoRefresh, setAutoRefresh, underlyings, toggleUnderlying, ALL_U, data, scanLoading, cachedLoading, error, refetch, health, opportunities, summary, totalEdge, isLoading, livePrices }) {
  const [expandedIdx, setExpandedIdx] = useState(null);
  const [sortKey, setSortKey] = useState(null);
  const [sortDir, setSortDir] = useState('desc');
  const [execState, setExecState] = useState(null);

  const startExecute = (e, opp, idx) => {
    e.stopPropagation();
    setExpandedIdx(null);
    const isConv = opp.action === 'CONVERSION';
    const legs = isConv ? [
      { name: opp.ceSymbol || '--', side: 'BUY', price: opp.ceEntryPrice || opp.cePrice || 0, qty: opp.lotSize || opp.costBreakdown?.lotSize || 65, status: 'waiting', msg: '' },
      { name: opp.futSymbol || '--', side: 'SELL', price: opp.futuresPrice || 0, qty: opp.lotSize || opp.costBreakdown?.lotSize || 65, status: 'waiting', msg: '' },
      { name: opp.peSymbol || '--', side: 'SELL', price: opp.peEntryPrice || opp.pePrice || 0, qty: opp.lotSize || opp.costBreakdown?.lotSize || 65, status: 'waiting', msg: '' },
    ] : [
      { name: opp.peSymbol || '--', side: 'BUY', price: opp.peEntryPrice || opp.pePrice || 0, qty: opp.lotSize || opp.costBreakdown?.lotSize || 65, status: 'waiting', msg: '' },
      { name: opp.futSymbol || '--', side: 'BUY', price: opp.futuresPrice || 0, qty: opp.lotSize || opp.costBreakdown?.lotSize || 65, status: 'waiting', msg: '' },
      { name: opp.ceSymbol || '--', side: 'SELL', price: opp.ceEntryPrice || opp.cePrice || 0, qty: opp.lotSize || opp.costBreakdown?.lotSize || 65, status: 'waiting', msg: '' },
    ];
    setExecState({ opp, idx, phase: 'confirm', legs, result: null });
  };

  const doExecute = async () => {
    if (!execState) return;
    const { opp } = execState;
    setExecState(s => ({ ...s, phase: 'executing', legs: s.legs.map((l, i) => ({ ...l, status: i === 0 ? 'sending' : 'pending' })) }));

    try {
      const res = await axios.post(`${API_BASE}/api/option-arbitrage/auto-execute/execute`, null, {
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
      const serverLegs = data.legs || [];
      const newLegs = execState.legs.map((l, i) => {
        const sl = serverLegs[i];
        if (sl) {
          const isFilled = sl.status === 'COMPLETE';
          const isRejected = sl.status === 'REJECTED' || sl.status === 'CANCELLED' || sl.status === 'OPEN';
          return {
            ...l,
            name: sl.symbol || l.name,
            orderId: sl.orderId,
            fillPrice: sl.fillPrice || 0,
            status: isFilled ? 'filled' : isRejected ? (sl.status === 'OPEN' ? 'pending' : 'error') : sl.status === 'ERROR' ? 'error' : 'sending',
            msg: sl.message || (isFilled ? `Filled @ ₹${sl.fillPrice || sl.requestedPrice}` : isRejected ? `Status: ${sl.status}` : sl.status || ''),
          };
        }
        return { ...l, status: 'error', msg: 'No response from server' };
      });

      setExecState(s => ({
        ...s,
        phase: 'done',
        legs: newLegs,
        result: {
          ok: data.success,
          partialFill: data.partialFill,
          tradeId: data.tradeId,
          tradeStatus: data.tradeStatus,
          error: data.error,
          marginAvailable: data.marginAvailable,
          marginRequired: data.marginRequired,
        }
      }));

      if (refetch) refetch();
    } catch (e) {
      setExecState(s => ({
        ...s,
        phase: 'done',
        legs: s.legs.map(l => ({ ...l, status: 'error', msg: e.response?.data?.error || e.message })),
        result: { ok: false, error: e.response?.data?.error || e.message }
      }));
    }
  };

  // Compute running P&L for each opportunity
  const priceMap = livePrices?.prices || {};
  function getLivePrice(opp) {
    return priceMap[opp.id] || priceMap[opp.underlying + '_' + (opp.strike || opp.strikePrice || 0)];
  }
  function computeRunningPnl(opp) {
    let lp = getLivePrice(opp);
    let entryCE, entryPE, entryFUT, futLive, lotSize;

    if (lp && lp.ceLive && lp.peLive) {
      entryCE = opp.ceEntryPrice || opp.cePrice || 0;
      entryPE = opp.peEntryPrice || opp.pePrice || 0;
      entryFUT = opp.futuresPrice || 0;
      futLive = lp.futLive || 0;
      lotSize = opp.lotSize || opp.costBreakdown?.lotSize || 65;
    } else {
      entryCE = opp.cePrice || 0;
      entryPE = opp.pePrice || 0;
      entryFUT = opp.futuresPrice || 0;
      futLive = opp.futuresPrice || 0;
      lotSize = opp.costBreakdown?.lotSize || 65;
      lp = { ceLive: opp.ceBid || opp.cePrice || 0, peLive: opp.peBid || opp.pePrice || 0 };
    }

    if (!lp || !lp.ceLive || !lp.peLive) return null;
    if (entryCE === 0 || entryPE === 0 || entryFUT === 0) return null;
    if (futLive === 0) return null;

    let pnlPoints = 0;
    if (opp.action === 'CONVERSION') {
      pnlPoints = (lp.ceLive - entryCE) + (entryPE - lp.peLive) + (entryFUT - futLive);
    } else if (opp.action === 'REVERSAL') {
      pnlPoints = (entryCE - lp.ceLive) + (lp.peLive - entryPE) + (futLive - entryFUT);
    } else if (opp.action === 'SELL_STRADDLE') {
      pnlPoints = (entryCE - lp.ceLive) + (entryPE - lp.peLive);
    } else if (opp.action === 'SELL_PUT_BUY_CALL') {
      pnlPoints = (lp.ceLive - entryCE) + (entryPE - lp.peLive);
    }
    return pnlPoints * lotSize;
  }

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

  const sortedOpps = [...opportunities].sort((a, b) => {
    if (!sortKey) return 0;
    let va, vb;
    switch (sortKey) {
      case 'type': va = a.type || ''; vb = b.type || ''; return sortDir === 'asc' ? va.localeCompare(vb) : vb.localeCompare(va);
      case 'underlying': va = a.underlying || ''; vb = b.underlying || ''; return sortDir === 'asc' ? va.localeCompare(vb) : vb.localeCompare(va);
      case 'strike': va = a.strike || 0; vb = b.strike || 0; break;
      case 'action': va = a.action || ''; vb = b.action || ''; return sortDir === 'asc' ? va.localeCompare(vb) : vb.localeCompare(va);
      case 'cePrice': va = a.ceEntryPrice || a.cePrice || 0; vb = b.ceEntryPrice || b.cePrice || 0; break;
      case 'pePrice': va = a.peEntryPrice || a.pePrice || 0; vb = b.peEntryPrice || b.pePrice || 0; break;
      case 'edgePts': va = a.edgePoints || 0; vb = b.edgePoints || 0; break;
      case 'edgeInr': va = a.edgeAfterCosts || 0; vb = b.edgeAfterCosts || 0; break;
      case 'runningPnl': {
        const pa = computeRunningPnl(a);
        const pb = computeRunningPnl(b);
        va = pa != null ? pa : -Infinity;
        vb = pb != null ? pb : -Infinity;
        break;
      }
      case 'confidence': va = a.confidence || 0; vb = b.confidence || 0; break;
      case 'dte': va = a.daysToExpiry || 0; vb = b.daysToExpiry || 0; break;
      case 'signalTime': va = a.detectedAt || a.scanTime || ''; vb = b.detectedAt || b.scanTime || ''; return sortDir === 'asc' ? va.localeCompare(vb) : vb.localeCompare(va);
      default: return 0;
    }
    return sortDir === 'asc' ? va - vb : vb - va;
  });

  const eOpp = execState?.opp;
  const eLotSize = eOpp?.lotSize || eOpp?.costBreakdown?.lotSize || 65;
  const eTotalVal = execState ? execState.legs.reduce((s, l) => s + (l.price || 0) * l.qty, 0) : 0;

  return (
    <>
      <div className="flex items-center gap-4 flex-wrap">
        <div className="flex bg-zinc-800 rounded-lg p-1">
          {ALL_U.map((opt) => {
            const active = underlyings.includes(opt);
            return (
              <button
                key={opt}
                onClick={() => toggleUnderlying(opt)}
                className={`px-4 py-2 rounded-md text-sm font-medium transition-colors ${
                  active ? 'bg-blue-600 text-white' : 'text-zinc-400 hover:text-white'
                }`}
              >
                {opt === 'ALL' ? 'All' : opt}
              </button>
            );
          })}
        </div>
        <button onClick={() => refetch()} disabled={isLoading}
          className="px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded-lg text-sm font-medium disabled:opacity-50">
          {isLoading ? 'Scanning...' : 'Scan Now'}
        </button>
        <button onClick={() => setAutoRefresh(!autoRefresh)}
          className={`px-4 py-2 rounded-lg text-sm font-medium transition-colors ${
            autoRefresh ? 'bg-emerald-600 text-white' : 'bg-zinc-700 text-zinc-300 hover:bg-zinc-600'
          }`}>
          {autoRefresh ? 'Auto: ON (30s)' : 'Auto: OFF'}
        </button>
        <span className="text-xs text-slate-500 ml-auto">
          {opportunities.length} signals | Total edge: <span className="font-bold text-emerald-600">{fmtCurrency(totalEdge, 0)}</span>
        </span>
      </div>

      {error && (
        <div className="bg-red-500/10 border border-red-500/30 rounded-lg p-4 text-red-400 text-sm">
          Error: {error.message}
        </div>
      )}

      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden shadow-sm">
        <div className="px-6 py-4 border-b border-slate-200 flex items-center justify-between">
          <h2 className="text-lg font-semibold text-slate-800">
            Detected Opportunities ({opportunities.length})
          </h2>
          {data?.timestamp && (
            <span className="text-xs text-slate-400">Last scan: {new Date(data.timestamp).toLocaleTimeString()}</span>
          )}
        </div>

        {opportunities.length === 0 ? (
          <div className="text-center py-12 text-slate-400">
            <p className="text-sm">No opportunities found. Scanner runs during market hours (9:15 AM - 3:30 PM IST).</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-xs text-slate-500 uppercase border-b border-slate-200 bg-slate-50">
                  <th className="px-3 py-3 text-left cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('type')}>Type{sortIndicator('type')}</th>
                  <th className="px-3 py-3 text-left cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('underlying')}>Underlying{sortIndicator('underlying')}</th>
                  <th className="px-3 py-3 text-left cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('strike')}>Strike{sortIndicator('strike')}</th>
                  <th className="px-3 py-3 text-left cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('action')}>Action{sortIndicator('action')}</th>
                  <th className="px-3 py-3 text-right cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('cePrice')}>CE{sortIndicator('cePrice')}</th>
                  <th className="px-3 py-3 text-right cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('pePrice')}>PE{sortIndicator('pePrice')}</th>
                  <th className="px-3 py-3 text-right cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('edgePts')}>Edge (pts){sortIndicator('edgePts')}</th>
                  <th className="px-3 py-3 text-right cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('edgeInr')}>Edge (₹){sortIndicator('edgeInr')}</th>
                  <th className="px-3 py-3 text-right cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('runningPnl')}>Running P&L{sortIndicator('runningPnl')}</th>
                  <th className="px-3 py-3 text-right cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('confidence')}>Conf{sortIndicator('confidence')}</th>
                  <th className="px-3 py-3 text-right cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('dte')}>DTE{sortIndicator('dte')}</th>
                  <th className="px-3 py-3 text-right cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('signalTime')}>Signal Time{sortIndicator('signalTime')}</th>
                  <th className="px-3 py-3 text-center">Execute</th>
                </tr>
              </thead>
              <tbody>
                {sortedOpps.map((opp, idx) => {
                  const isExpanded = expandedIdx === idx;
                  const cePrice = opp.ceEntryPrice || opp.cePrice || 0;
                  const pePrice = opp.peEntryPrice || opp.pePrice || 0;
                  const runningPnl = computeRunningPnl(opp);
                  const lp = getLivePrice(opp) || {};

                  return (
                    <React.Fragment key={idx}>
                      <tr
                        onClick={() => { setExpandedIdx(isExpanded ? null : idx); setExecState(null); }}
                        className={`border-b border-slate-100 cursor-pointer transition-colors ${
                          isExpanded ? 'bg-blue-50' : 'hover:bg-slate-50'
                        }`}
                      >
                        <td className="px-3 py-3">
                          <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border ${
                            TYPE_COLORS[opp.type]?.bg || ''
                          } ${TYPE_COLORS[opp.type]?.text || ''} ${TYPE_COLORS[opp.type]?.border || ''}`}>
                            {TYPE_LABELS[opp.type] || opp.type}
                          </span>
                        </td>
                        <td className="px-3 py-3 text-sm font-medium text-slate-700">{opp.underlying}</td>
                        <td className="px-3 py-3 text-sm font-mono font-medium text-slate-900">{opp.strike}</td>
                        <td className="px-3 py-3 text-xs text-slate-500">{ACTION_LABELS[opp.action] || opp.action}</td>
                        <td className="px-3 py-3 text-sm text-right font-mono text-slate-700">{fmtCurrency(cePrice, 1)}</td>
                        <td className="px-3 py-3 text-sm text-right font-mono text-slate-700">{fmtCurrency(pePrice, 1)}</td>
                        <td className={`px-3 py-3 text-sm text-right font-mono font-bold ${opp.edgePoints > 0 ? 'text-emerald-600' : 'text-red-500'}`}>
                          {opp.edgePoints > 0 ? '+' : ''}{fmt(opp.edgePoints, 1)}
                        </td>
                        <td className={`px-3 py-3 text-sm text-right font-mono font-bold ${opp.edgeAfterCosts > 0 ? 'text-emerald-600' : 'text-red-500'}`}>
                          {fmtCurrency(opp.edgeAfterCosts, 0)}
                        </td>
                        <td className="px-3 py-3 text-sm text-right font-mono">
                          {runningPnl != null ? (
                            <span className={`font-bold ${runningPnl > 0 ? 'text-emerald-600' : runningPnl < 0 ? 'text-red-500' : 'text-slate-400'}`}>
                              {runningPnl > 0 ? '+' : ''}{fmtCurrency(runningPnl, 0)}
                            </span>
                          ) : (
                            <span className="text-slate-400 text-xs">{livePrices ? '--' : '...'}</span>
                          )}
                        </td>
                        <td className="px-3 py-3 text-right">
                          <div className="flex items-center justify-end gap-1">
                            <div className="w-10 bg-slate-200 rounded-full h-1.5">
                              <div className="bg-blue-500 h-1.5 rounded-full" style={{ width: `${opp.confidence}%` }} />
                            </div>
                            <span className="text-xs text-slate-500 w-7 text-right">{fmt(opp.confidence, 0)}%</span>
                          </div>
                        </td>
                        <td className="px-3 py-3 text-sm text-right text-slate-500">{fmt(opp.daysToExpiry, 0)}d</td>
                        <td className="px-3 py-3 text-xs text-right text-slate-400 font-mono">{formatIstTime(opp.detectedAt || opp.scanTime)}</td>
                        <td className="px-3 py-3 text-center">
                          {opp.type === 'PARITY_BREAK' ? (
                            <div className="flex items-center justify-center gap-1.5">
                              <KiteBasketButton opp={opp} label="Kite" livePriceMap={priceMap} />
                              <button onClick={(e) => startExecute(e, opp, idx)}
                                className="px-3 py-1.5 rounded-lg text-xs font-semibold bg-emerald-600 text-white hover:bg-emerald-700 shadow-sm transition">
                                ⚡ API
                              </button>
                            </div>
                          ) : (
                            <span className="text-xs text-slate-400">Manual only</span>
                          )}
                        </td>
                      </tr>

                      {isExpanded && !execState && (
                        <tr>
                          <td colSpan={13} className="px-0 py-0">
                            <ExpandedDetail opp={opp} livePriceMap={priceMap} />
                          </td>
                        </tr>
                      )}

                      {execState && execState.opp === opp && (
                        <tr>
                          <td colSpan={13} className="px-0 py-0">
                            <div className="bg-slate-50 border-b border-slate-200 px-6 py-4">
                              <div className="flex items-center justify-between mb-3">
                                <h3 className="text-sm font-bold text-slate-800">
                                  {execState.phase === 'confirm' ? 'Review & Execute' : execState.result?.ok ? 'Trade Executed' : execState.result ? 'Execution Failed' : 'Executing...'}
                                </h3>
                                <button onClick={() => setExecState(null)} className="text-slate-400 hover:text-slate-600 text-lg leading-none">&times;</button>
                              </div>

                              <div className="grid grid-cols-4 gap-3 text-center mb-3">
                                <div><p className="text-xs text-slate-500">Underlying</p><p className="text-sm font-bold text-slate-800">{eOpp.underlying}</p></div>
                                <div><p className="text-xs text-slate-500">Strike</p><p className="text-sm font-bold text-slate-800">{eOpp.strike}</p></div>
                                <div><p className="text-xs text-slate-500">Edge After Costs</p><p className="text-sm font-bold text-emerald-600">{fmtCurrency(eOpp.edgeAfterCosts, 0)}</p></div>
                                <div><p className="text-xs text-slate-500">Lot Size</p><p className="text-sm font-bold text-slate-800">{eLotSize}</p></div>
                              </div>

                              <div className="space-y-1.5 mb-3">
                                {execState.legs.map((leg, i) => (
                                  <div key={i} className={`flex items-center justify-between p-2.5 rounded-lg border text-sm ${
                                    leg.status === 'filled' ? 'border-emerald-200 bg-emerald-50' :
                                    leg.status === 'error' ? 'border-red-200 bg-red-50' :
                                    leg.status === 'sending' ? 'border-blue-200 bg-blue-50' :
                                    leg.status === 'pending' ? 'border-amber-200 bg-amber-50' :
                                    'border-slate-200 bg-white'
                                  }`}>
                                    <div className="flex items-center gap-2.5">
                                      <span className={`w-2 h-2 rounded-full ${
                                        leg.status === 'filled' ? 'bg-emerald-500' :
                                        leg.status === 'error' ? 'bg-red-500' :
                                        leg.status === 'sending' ? 'bg-blue-500 animate-pulse' :
                                        leg.status === 'pending' ? 'bg-amber-400' :
                                        'bg-slate-300'
                                      }`} />
                                      <div>
                                        <p className="text-sm font-semibold text-slate-800">{leg.name}</p>
                                        <p className="text-xs text-slate-500">
                                          <span className={leg.side === 'BUY' ? 'text-blue-600 font-semibold' : 'text-red-600 font-semibold'}>{leg.side}</span>
                                          {' '}&times;{leg.qty} @ {fmtCurrency(leg.price, 1)}
                                        </p>
                                      </div>
                                    </div>
                                    <div className="text-right">
                                      {leg.status === 'filled' && <span className="text-xs font-semibold text-emerald-600">Filled</span>}
                                      {leg.status === 'error' && <span className="text-xs font-semibold text-red-600">Failed</span>}
                                      {leg.status === 'sending' && <span className="text-xs font-semibold text-blue-600 animate-pulse">Sending...</span>}
                                      {leg.status === 'pending' && <span className="text-xs font-semibold text-amber-500">Waiting...</span>}
                                      {leg.status === 'waiting' && <span className="text-xs text-slate-400">Queued</span>}
                                    </div>
                                  </div>
                                ))}
                              </div>

                              <div className="flex justify-between text-sm mb-3 px-1">
                                <span className="text-slate-500">Total Order Value</span>
                                <span className="font-bold text-slate-800">{fmtCurrency(eTotalVal, 0)}</span>
                              </div>

                              {execState.result && (
                                <div className={`rounded-lg p-3 mb-3 border ${execState.result.ok ? 'bg-emerald-50 border-emerald-200' : execState.result.partialFill ? 'bg-amber-50 border-amber-200' : 'bg-red-50 border-red-200'}`}>
                                  {execState.result.ok ? (
                                    <p className="text-sm font-semibold text-emerald-800">Trade #{execState.result.tradeId} placed successfully. All 3 legs filled.</p>
                                  ) : execState.result.partialFill ? (
                                    <p className="text-sm font-semibold text-amber-800">Partial fill detected — filled legs have been automatically squared off to prevent naked positions. {execState.result.error || ''}</p>
                                  ) : (
                                    <p className="text-sm font-semibold text-red-800">{execState.result.error || 'Execution failed'}</p>
                                  )}
                                  {execState.result.marginAvailable != null && (
                                    <p className="text-xs mt-1 text-slate-600">Margin available: ₹{Number(execState.result.marginAvailable).toLocaleString()} | Required: ₹{Number(execState.result.marginRequired || 0).toLocaleString()}</p>
                                  )}
                                </div>
                              )}

                              <div className="flex justify-end gap-2">
                                {execState.phase === 'confirm' && (
                                  <button onClick={doExecute}
                                    className="px-4 py-2 rounded-lg text-sm font-bold bg-emerald-600 text-white hover:bg-emerald-700 shadow-sm transition">
                                    Confirm & Execute
                                  </button>
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
      </div>

      <div className="bg-white rounded-xl border border-slate-200 p-6 shadow-sm">
        <h3 className="text-sm font-semibold text-slate-500 uppercase mb-4">Scanner Settings</h3>
        <div className="grid grid-cols-2 md:grid-cols-5 gap-4 text-sm">
          <div><span className="text-slate-500">Min Parity Deviation</span><p className="text-slate-800 font-mono font-medium">15 points</p></div>
          <div><span className="text-slate-500">Min Edge After Costs</span><p className="text-slate-800 font-mono font-medium">₹200</p></div>
          <div><span className="text-slate-500">Max Bid-Ask Spread</span><p className="text-slate-800 font-mono font-medium">5%</p></div>
          <div><span className="text-slate-500">Cooldown Period</span><p className="text-slate-800 font-mono font-medium">60 seconds</p></div>
          <div><span className="text-slate-500">Risk-Free Rate</span><p className="text-slate-800 font-mono font-medium">6.5%</p></div>
        </div>
      </div>
    </>
  );
}

function ExpandedDetail({ opp, livePriceMap }) {
  const costs = opp.costBreakdown || {};
  const lotSize = opp.lotSize || costs.lotSize || 65;
  const cePrice = opp.ceEntryPrice || opp.cePrice || 0;
  const pePrice = opp.peEntryPrice || opp.pePrice || 0;
  const spotPrice = opp.spotPrice || 0;
  const futuresPrice = opp.futuresPrice || 0;
  const maxP = opp.maxProfit || (opp.edgeAfterCosts || 0);
  const maxL = opp.maxLoss || 0;

  const lp = livePriceMap?.[opp.id] || {};
  const ceLiveNow = lp.ceLive || 0;
  const peLiveNow = lp.peLive || 0;
  const spotLive = lp.spotLive || 0;
  const futLive = lp.futLive || 0;

  let runningPnl = null;
  if (ceLiveNow && peLiveNow) {
    const entryCE = cePrice;
    const entryPE = pePrice;
    const entryFUT = futuresPrice;
    const futLiveNow = lp.futLive;
    // Require live futures for CONVERSION/REVERSAL P&L — otherwise leg delta is meaningless
    const needsFutures = opp.action === 'CONVERSION' || opp.action === 'REVERSAL';
    if (needsFutures && (!futLiveNow || futLiveNow === 0)) {
      runningPnl = null; // can't compute P&L without live futures
    } else if (opp.action === 'CONVERSION') {
      runningPnl = ((ceLiveNow - entryCE) + (entryPE - peLiveNow) + (entryFUT - futLiveNow)) * lotSize;
    } else if (opp.action === 'REVERSAL') {
      runningPnl = ((entryCE - ceLiveNow) + (peLiveNow - entryPE) + (futLiveNow - entryFUT)) * lotSize;
    } else if (opp.action === 'SELL_STRADDLE') {
      runningPnl = ((entryCE - ceLiveNow) + (entryPE - peLiveNow)) * lotSize;
    } else if (opp.action === 'SELL_PUT_BUY_CALL') {
      runningPnl = ((ceLiveNow - entryCE) + (entryPE - peLiveNow)) * lotSize;
    }
  }

  return (
    <div className="bg-slate-50 border-b border-slate-200 px-6 py-4">
      {/* Signal Header */}
      <div className="flex items-center gap-4 mb-4 flex-wrap">
        <div className="flex items-center gap-1.5">
          <div className="w-2 h-2 rounded-full bg-blue-500 animate-pulse" />
          <span className="text-xs font-semibold text-slate-700">Signal Time: {formatIstDateTime(opp.scanTime)}</span>
        </div>
        <span className="text-slate-300">|</span>
        <span className="text-xs text-slate-500">{ACTION_LABELS[opp.action] || opp.action}</span>
        <span className="text-slate-300">|</span>
        <span className="text-xs text-slate-500">DTE: <span className="font-medium text-slate-700">{fmt(opp.daysToExpiry, 0)} days</span></span>
        <span className="text-slate-300">|</span>
        <span className="text-xs text-slate-500">Confidence: <span className="font-medium text-blue-600">{fmt(opp.confidence, 1)}%</span></span>
      </div>

      {/* Trade Legs */}
      <div className="mb-4">
        <p className="text-xs text-slate-500 uppercase mb-1 font-medium">Trade Legs</p>
        <p className="text-sm text-slate-800 font-mono bg-white rounded-lg p-3 border border-slate-200">{opp.legs}</p>
      </div>

      {/* Running P&L Banner */}
      {runningPnl != null && (
        <div className={`mb-4 rounded-lg p-3 border ${runningPnl >= 0 ? 'bg-emerald-50 border-emerald-200' : 'bg-red-50 border-red-200'}`}>
          <div className="flex items-center justify-between">
            <span className="text-sm font-semibold text-slate-700">Running P&L if Entered at Signal</span>
            <span className={`text-xl font-mono font-bold ${runningPnl >= 0 ? 'text-emerald-600' : 'text-red-500'}`}>
              {runningPnl >= 0 ? '+' : ''}{fmtCurrency(runningPnl, 0)}
            </span>
          </div>
          <div className="flex gap-6 mt-1 text-xs text-slate-500">
            <span>CE: {fmtCurrency(cePrice, 1)} → {fmtCurrency(ceLiveNow, 1)}</span>
            <span>PE: {fmtCurrency(pePrice, 1)} → {fmtCurrency(peLiveNow, 1)}</span>
            {futLive > 0 && <span>FUT: {fmtCurrency(futuresPrice, 1)} → {fmtCurrency(futLive, 1)}</span>}
            <span>Lot: {lotSize}</span>
          </div>
        </div>
      )}

      {/* Entry Prices + Lot Size */}
      <div className="grid grid-cols-5 gap-3 mb-4">
        <div className="bg-white rounded-lg p-3 border border-slate-200">
          <p className="text-xs text-slate-500">Spot (Entry)</p>
          <p className="text-sm font-mono font-bold text-slate-800">{fmtCurrency(spotPrice, 2)}</p>
          {spotLive > 0 && <p className="text-xs text-slate-400">Live: {fmtCurrency(spotLive, 2)}</p>}
        </div>
        <div className="bg-white rounded-lg p-3 border border-slate-200">
          <p className="text-xs text-slate-500">Futures (Entry)</p>
          <p className="text-sm font-mono font-bold text-slate-800">{fmtCurrency(futuresPrice, 2)}</p>
          {futLive > 0 && <p className="text-xs text-slate-400">Live: {fmtCurrency(futLive, 2)}</p>}
        </div>
        <div className="bg-white rounded-lg p-3 border border-slate-200">
          <p className="text-xs text-slate-500">CE Entry</p>
          <p className="text-sm font-mono font-bold text-slate-800">{fmtCurrency(cePrice, 2)}</p>
          {ceLiveNow > 0 && <p className={`text-xs font-medium ${ceLiveNow >= cePrice ? 'text-emerald-600' : 'text-red-500'}`}>Live: {fmtCurrency(ceLiveNow, 2)}</p>}
        </div>
        <div className="bg-white rounded-lg p-3 border border-slate-200">
          <p className="text-xs text-slate-500">PE Entry</p>
          <p className="text-sm font-mono font-bold text-slate-800">{fmtCurrency(pePrice, 2)}</p>
          {peLiveNow > 0 && <p className={`text-xs font-medium ${peLiveNow <= pePrice ? 'text-emerald-600' : 'text-red-500'}`}>Live: {fmtCurrency(peLiveNow, 2)}</p>}
        </div>
        <div className="bg-white rounded-lg p-3 border border-slate-200">
          <p className="text-xs text-slate-500">Lot Size</p>
          <p className="text-sm font-mono font-bold text-slate-800">{lotSize}</p>
        </div>
      </div>

      {/* Expected Profit + Risk Side by Side */}
      <div className="grid grid-cols-2 gap-4">
        {/* Expected Profit */}
        <div className="bg-white rounded-lg p-4 border border-slate-200">
          <p className="text-xs text-slate-500 uppercase mb-2 font-medium">Expected Profit (1 Lot)</p>
          <div className="space-y-1.5 text-sm">
            <div className="flex justify-between">
              <span className="text-slate-600">Raw Edge</span>
              <span className={`font-mono font-bold ${opp.edgePoints > 0 ? 'text-emerald-600' : 'text-red-500'}`}>
                {opp.edgePoints > 0 ? '+' : ''}{fmt(opp.edgePoints, 1)} pts
              </span>
            </div>
            <div className="flex justify-between">
              <span className="text-slate-600">Edge Before Costs (₹)</span>
              <span className="font-mono font-bold text-emerald-600">
                {fmtCurrency(costs.grossEdge || opp.edgePoints * lotSize, 0)}
              </span>
            </div>
            <div className="flex justify-between">
              <span className="text-slate-600">Total Costs</span>
              <span className="font-mono font-bold text-red-500">
                {costs.totalCosts != null ? '-' + fmtCurrency(costs.totalCosts, 0) : '--'}
              </span>
            </div>
            <div className="border-t border-slate-200 pt-1.5 flex justify-between">
              <span className="text-slate-800 font-semibold">Edge After Costs</span>
              <span className={`font-mono font-bold text-lg ${(opp.edgeAfterCosts || 0) > 0 ? 'text-emerald-600' : 'text-red-500'}`}>
                {fmtCurrency(opp.edgeAfterCosts, 0)}
              </span>
            </div>
          </div>
        </div>

        {/* Risk / Reward + Taxes */}
        <div className="bg-white rounded-lg p-4 border border-slate-200">
          <p className="text-xs text-slate-500 uppercase mb-2 font-medium">Risk / Reward</p>
          <div className="space-y-1.5 text-sm mb-3">
            <div className="flex justify-between">
              <span className="text-slate-600">Max Profit</span>
              <span className="font-mono font-bold text-emerald-600">{fmtCurrency(maxP, 0)}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-slate-600">Max Loss</span>
              <span className="font-mono font-bold text-red-500">{maxL > 0 ? fmtCurrency(maxL, 0) : '₹0 (Risk-Free)'}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-slate-600">Risk:Reward</span>
              <span className="font-mono font-bold text-slate-700">{maxP > 0 && maxL > 0 ? '1:' + (maxP / maxL).toFixed(1) : maxL === 0 ? 'Infinite' : '--'}</span>
            </div>
          </div>

          {costs.totalCosts != null && (
            <>
              <p className="text-xs text-slate-500 uppercase mb-1 font-medium">Taxes & Costs</p>
              <div className="space-y-0.5 text-xs">
                {costs.stt != null && <div className="flex justify-between"><span className="text-slate-500">STT</span><span className="font-mono text-slate-600">{fmtCurrency(costs.stt, 2)}</span></div>}
                {costs.brokerage != null && <div className="flex justify-between"><span className="text-slate-500">Brokerage</span><span className="font-mono text-slate-600">{fmtCurrency(costs.brokerage, 0)}</span></div>}
                {costs.exchange != null && <div className="flex justify-between"><span className="text-slate-500">Exchange</span><span className="font-mono text-slate-600">{fmtCurrency(costs.exchange, 2)}</span></div>}
                {costs.sebi != null && costs.gst != null && <div className="flex justify-between"><span className="text-slate-500">SEBI + GST</span><span className="font-mono text-slate-600">{fmtCurrency(costs.sebi + costs.gst, 2)}</span></div>}
                {costs.ipft != null && <div className="flex justify-between"><span className="text-slate-500">IPFT</span><span className="font-mono text-slate-600">{fmtCurrency(costs.ipft, 4)}</span></div>}
              </div>
            </>
          )}
        </div>
      </div>

      {/* Kite Basket Button */}
      {opp.type === 'PARITY_BREAK' && opp.action && (
        <div className="mt-4 flex items-center gap-3">
          <KiteBasketButton opp={opp} label="Open Basket on Kite" className="px-5 py-2.5 rounded-lg text-sm font-bold bg-blue-600 text-white hover:bg-blue-700 shadow-sm transition" livePriceMap={livePriceMap} />
          <span className="text-xs text-slate-500">Opens Kite with 3-leg basket order for margin benefit</span>
        </div>
      )}
    </div>
  );
}

function HistoryTab({ historyData, historyLoading, summaryData, datesData, historyPage, setHistoryPage, selectedDate, setSelectedDate }) {
  const opportunities = historyData?.opportunities || [];
  const totalPages = historyData?.totalPages || 0;
  const totalElements = historyData?.totalElements || 0;
  const availableDates = datesData?.dates || [];
  const [expandedIdx, setExpandedIdx] = useState(null);
  const [sortKey, setSortKey] = useState(null);
  const [sortDir, setSortDir] = useState('desc');

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

  const sortedOpps = [...opportunities].sort((a, b) => {
    if (!sortKey) return 0;
    let va, vb;
    switch (sortKey) {
      case 'date': va = a.scanTime || ''; vb = b.scanTime || ''; return sortDir === 'asc' ? va.localeCompare(vb) : vb.localeCompare(va);
      case 'type': va = a.type || ''; vb = b.type || ''; return sortDir === 'asc' ? va.localeCompare(vb) : vb.localeCompare(va);
      case 'underlying': va = a.underlying || ''; vb = b.underlying || ''; return sortDir === 'asc' ? va.localeCompare(vb) : vb.localeCompare(va);
      case 'strike': va = a.strike || 0; vb = b.strike || 0; break;
      case 'cePrice': va = a.ceEntryPrice || 0; vb = b.ceEntryPrice || 0; break;
      case 'pePrice': va = a.peEntryPrice || 0; vb = b.peEntryPrice || 0; break;
      case 'edge': va = a.edgeAfterCosts || 0; vb = b.edgeAfterCosts || 0; break;
      case 'pnl': va = a.pnlAfterCosts || 0; vb = b.pnlAfterCosts || 0; break;
      case 'action': va = a.action || ''; vb = b.action || ''; return sortDir === 'asc' ? va.localeCompare(vb) : vb.localeCompare(va);
      case 'status': va = a.status || ''; vb = b.status || ''; return sortDir === 'asc' ? va.localeCompare(vb) : vb.localeCompare(va);
      default: return 0;
    }
    return sortDir === 'asc' ? va - vb : vb - va;
  });

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-4">
        <label className="text-sm text-slate-600 font-medium">Select Date:</label>
        <select value={selectedDate} onChange={(e) => setSelectedDate(e.target.value)}
          className="px-3 py-2 border border-slate-200 rounded-lg text-sm text-slate-700 bg-white focus:outline-none focus:ring-2 focus:ring-blue-500">
          <option value="">All Dates</option>
          {availableDates.map((d) => (<option key={d} value={d}>{d}</option>))}
        </select>
      </div>

      {summaryData && (
        <div className="grid grid-cols-2 md:grid-cols-5 gap-4">
          <StatCard label="Total Opportunities" value={summaryData.totalOpportunities || 0} color="text-blue-600" />
          <StatCard label="Win Rate" value={`${summaryData.winRate || 0}%`} color="text-emerald-600" />
          <StatCard label="Total P&L" value={`₹${summaryData.totalPnlAfterCosts || 0}`} color={(summaryData.totalPnlAfterCosts || 0) >= 0 ? 'text-emerald-600' : 'text-red-500'} />
          <StatCard label="Wins / Losses" value={`${summaryData.wins || 0} / ${summaryData.losses || 0}`} color="text-slate-700" />
          <StatCard label="Edge Detected" value={`₹${summaryData.totalEdgeDetected || 0}`} color="text-blue-600" />
        </div>
      )}

      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden shadow-sm">
        <div className="px-6 py-4 border-b border-slate-200">
          <h2 className="text-lg font-semibold text-slate-800">History ({totalElements} total)</h2>
        </div>

        {historyLoading ? (
          <div className="px-6 py-12 text-center text-slate-400">Loading...</div>
        ) : opportunities.length === 0 ? (
          <div className="px-6 py-12 text-center text-slate-400">
            <p className="text-lg">No history yet</p>
            <p className="text-sm mt-1">Signals from past days will appear here</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-xs text-slate-500 uppercase border-b border-slate-200 bg-slate-50">
                  <th className="px-4 py-3 text-left cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('date')}>Date{sortIndicator('date')}</th>
                  <th className="px-4 py-3 text-left cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('type')}>Type{sortIndicator('type')}</th>
                  <th className="px-4 py-3 text-left cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('underlying')}>Underlying{sortIndicator('underlying')}</th>
                  <th className="px-4 py-3 text-left cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('strike')}>Strike{sortIndicator('strike')}</th>
                  <th className="px-4 py-3 text-right cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('cePrice')}>CE Entry{sortIndicator('cePrice')}</th>
                  <th className="px-4 py-3 text-right cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('pePrice')}>PE Entry{sortIndicator('pePrice')}</th>
                  <th className="px-4 py-3 text-right cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('edge')}>Edge (₹){sortIndicator('edge')}</th>
                  <th className="px-4 py-3 text-center cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('status')}>Status{sortIndicator('status')}</th>
                  <th className="px-4 py-3 text-right cursor-pointer select-none hover:bg-slate-100" onClick={() => toggleSort('pnl')}>P&L{sortIndicator('pnl')}</th>
                </tr>
              </thead>
              <tbody>
                {sortedOpps.map((opp, idx) => {
                  const isExpanded = expandedIdx === idx;
                  const costs = opp.costBreakdown || {};
                  const lotSize = opp.lotSize || costs.lotSize || 65;
                  return (
                    <React.Fragment key={opp.id || idx}>
                      <tr onClick={() => setExpandedIdx(isExpanded ? null : idx)}
                        className={`border-b border-slate-100 cursor-pointer transition-colors ${isExpanded ? 'bg-blue-50' : 'hover:bg-slate-50'}`}>
                        <td className="px-4 py-3 text-sm text-slate-700 whitespace-nowrap">{formatIstDateTime(opp.scanTime)}</td>
                        <td className="px-4 py-3">
                          <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border ${TYPE_COLORS[opp.type]?.bg || ''} ${TYPE_COLORS[opp.type]?.text || ''} ${TYPE_COLORS[opp.type]?.border || ''}`}>
                            {TYPE_LABELS[opp.type] || opp.type}
                          </span>
                        </td>
                        <td className="px-4 py-3 text-sm text-slate-700">{opp.underlying}</td>
                        <td className="px-4 py-3 text-sm font-mono font-medium text-slate-900">{opp.strike}</td>
                        <td className="px-4 py-3 text-sm text-right font-mono text-slate-700">{fmtCurrency(opp.ceEntryPrice, 1)}</td>
                        <td className="px-4 py-3 text-sm text-right font-mono text-slate-700">{fmtCurrency(opp.peEntryPrice, 1)}</td>
                        <td className={`px-4 py-3 text-sm text-right font-mono font-bold ${(opp.edgeAfterCosts || 0) > 0 ? 'text-emerald-600' : 'text-red-500'}`}>
                          {fmtCurrency(opp.edgeAfterCosts, 0)}
                        </td>
                        <td className="px-4 py-3 text-center">
                          <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border ${STATUS_COLORS[opp.status] || ''}`}>
                            {opp.status}
                          </span>
                        </td>
                        <td className={`px-4 py-3 text-sm text-right font-mono font-bold ${(opp.pnlAfterCosts || 0) > 0 ? 'text-emerald-600' : (opp.pnlAfterCosts || 0) < 0 ? 'text-red-500' : 'text-slate-400'}`}>
                          {opp.pnlAfterCosts != null ? fmtCurrency(opp.pnlAfterCosts, 0) : '--'}
                        </td>
                      </tr>
                      {isExpanded && (
                        <tr>
                          <td colSpan={9} className="px-0 py-0">
                            <div className="bg-slate-50 border-b border-slate-200 px-6 py-4">
                              <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-4">
                                <div className="bg-white rounded-lg p-3 border border-slate-200">
                                  <p className="text-xs text-slate-500">Signal Time</p>
                                  <p className="text-sm font-medium text-slate-800">{formatIstDateTime(opp.scanTime)}</p>
                                </div>
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
                                  <p className="text-xs text-slate-500">CE Entry</p>
                                  <p className="text-sm font-mono font-bold text-slate-800">{fmtCurrency(opp.ceEntryPrice, 2)}</p>
                                </div>
                                <div className="bg-white rounded-lg p-3 border border-slate-200">
                                  <p className="text-xs text-slate-500">PE Entry</p>
                                  <p className="text-sm font-mono font-bold text-slate-800">{fmtCurrency(opp.peEntryPrice, 2)}</p>
                                </div>
                              </div>

                              <div className="grid grid-cols-2 gap-4">
                                <div className="bg-white rounded-lg p-4 border border-slate-200">
                                  <p className="text-xs text-slate-500 uppercase mb-2 font-medium">Profit / Loss</p>
                                  <div className="space-y-1.5 text-sm">
                                    <div className="flex justify-between"><span className="text-slate-600">Edge (pts)</span><span className="font-mono font-bold text-emerald-600">{fmt(opp.edgePoints, 1)}</span></div>
                                    <div className="flex justify-between"><span className="text-slate-600">Edge After Costs</span><span className="font-mono font-bold text-emerald-600">{fmtCurrency(opp.edgeAfterCosts, 0)}</span></div>
                                    {opp.pnlAfterCosts != null && (
                                      <div className="flex justify-between border-t border-slate-200 pt-1.5">
                                        <span className="text-slate-800 font-semibold">Realized P&L</span>
                                        <span className={`font-mono font-bold ${opp.pnlAfterCosts > 0 ? 'text-emerald-600' : opp.pnlAfterCosts < 0 ? 'text-red-500' : 'text-slate-400'}`}>{fmtCurrency(opp.pnlAfterCosts, 0)}</span>
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
      alert('Exit failed: ' + (e.response?.data?.error || e.message));
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
  const [expandedTradeId, setExpandedTradeId] = useState(null);
  const [replacingTradeId, setReplacingTradeId] = useState(null);

  const { data: settingsData, refetch: refetchSettings } = useQuery({
    queryKey: ['auto-exec-settings'],
    queryFn: async () => { const r = await axios.get(`${API_BASE}/api/option-arbitrage/auto-execute/settings`); return r.data; },
    staleTime: 10000,
  });

  const { data: tradesData, refetch: refetchTrades } = useQuery({
    queryKey: ['auto-exec-trades'],
    queryFn: async () => { const r = await axios.get(`${API_BASE}/api/option-arbitrage/auto-execute/trades`, { params: { status: 'ALL' } }); return r.data; },
    refetchInterval: 15000,
    staleTime: 10000,
  });

  const { data: todayData, refetch: refetchToday } = useQuery({
    queryKey: ['option-arb-today-for-replace'],
    queryFn: async () => { const r = await axios.get(`${API_BASE}/api/option-arbitrage/today`, { params: { underlying: 'ALL' } }); return r.data; },
    refetchInterval: 30000,
    staleTime: 15000,
  });

  const toggleAutoExec = useQuery({
    queryKey: ['toggle-auto-exec'],
    queryFn: async () => { const r = await axios.post(`${API_BASE}/api/option-arbitrage/auto-execute/toggle`); return r.data; },
    enabled: false,
  });

  const updateSetting = async (key, value) => {
    await axios.post(`${API_BASE}/api/option-arbitrage/auto-execute/settings`, null, { params: { key, value } });
    refetchSettings();
  };

  const runCycle = async () => {
    await axios.post(`${API_BASE}/api/option-arbitrage/auto-execute/run`);
    setTimeout(() => { refetchTrades(); refetchToday(); }, 3000);
  };

  const closeTrade = async (tradeId, what = 'all') => {
    await axios.post(`${API_BASE}/api/option-arbitrage/auto-execute/close/${tradeId}`, null, { params: { what } });
    refetchTrades();
  };

  const closeAll = async () => {
    if (!confirm('Close ALL open positions?')) return;
    await axios.post(`${API_BASE}/api/option-arbitrage/auto-execute/close-all`);
    refetchTrades();
  };

  const replaceTrade = async (tradeId, opp) => {
    if (!confirm(`Replace trade #${tradeId} with ${opp.underlying} ${opp.strike} ${opp.action}?`)) return;
    await axios.post(`${API_BASE}/api/option-arbitrage/auto-execute/replace`, null, {
      params: { tradeId, newAction: opp.action, newCePrice: opp.cePrice || opp.ceEntryPrice || 0, newPePrice: opp.pePrice || opp.peEntryPrice || 0, newFutPrice: opp.futuresPrice || 0, newSpotPrice: opp.spotPrice || 0 }
    });
    refetchTrades();
    setReplacingTradeId(null);
  };

  const replaceOptionsOnly = async (tradeId, opp) => {
    if (!confirm(`Replace options only for trade #${tradeId}? Futures leg stays.`)) return;
    await axios.post(`${API_BASE}/api/option-arbitrage/auto-execute/replace-options`, null, {
      params: { tradeId, newAction: opp.action, newCePrice: opp.cePrice || opp.ceEntryPrice || 0, newPePrice: opp.pePrice || opp.peEntryPrice || 0 }
    });
    refetchTrades();
    setReplacingTradeId(null);
  };

  const settings = settingsData?.settings || {};
  const trades = tradesData?.trades || [];
  const todayOpps = todayData?.opportunities || [];
  const openTrades = trades.filter(t => t.status === 'OPEN');
  const closedTrades = trades.filter(t => t.status !== 'OPEN');
  const replacingTrade = replacingTradeId ? openTrades.find(t => t.id === replacingTradeId) : null;
  const matchingOpps = replacingTrade ? todayOpps.filter(o => o.underlying === replacingTrade.underlying) : [];

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="bg-white rounded-xl border border-slate-200 p-5 shadow-sm">
          <div className="flex items-center justify-between mb-4">
            <h3 className="text-sm font-semibold text-slate-700">Auto-Execute</h3>
            <button onClick={async () => { await axios.post(`${API_BASE}/api/option-arbitrage/auto-execute/toggle`); refetchSettings(); }}
              className={`relative inline-flex h-7 w-12 items-center rounded-full transition-colors ${settings.auto_execute_enabled === 'true' ? 'bg-emerald-500' : 'bg-slate-300'}`}>
              <span className={`inline-block h-5 w-5 transform rounded-full bg-white shadow transition-transform ${settings.auto_execute_enabled === 'true' ? 'translate-x-6' : 'translate-x-1'}`} />
            </button>
          </div>
          <div className="space-y-3">
            <div>
              <label className="text-xs text-slate-500">Min Edge After Costs (₹)</label>
              <input type="number" value={settings.min_edge_after_costs || 500}
                onChange={(e) => updateSetting('min_edge_after_costs', e.target.value)}
                className="w-full mt-1 px-3 py-2 border border-slate-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500" />
            </div>
            <div>
              <label className="text-xs text-slate-500">Max Positions / Underlying</label>
              <input type="number" value={settings.max_positions_per_underlying || 3}
                onChange={(e) => updateSetting('max_positions_per_underlying', e.target.value)}
                className="w-full mt-1 px-3 py-2 border border-slate-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500" />
            </div>
            <div>
              <label className="text-xs text-slate-500">Max Total Positions</label>
              <input type="number" value={settings.max_total_positions || 12}
                onChange={(e) => updateSetting('max_total_positions', e.target.value)}
                className="w-full mt-1 px-3 py-2 border border-slate-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500" />
            </div>
            <div className="flex items-center gap-2">
              <input type="checkbox" checked={settings.smart_rollover === 'true'}
                onChange={(e) => updateSetting('smart_rollover', String(e.target.checked))}
                className="rounded border-slate-300 text-blue-600 focus:ring-blue-500" />
              <label className="text-xs text-slate-600">Smart rollover (same fut direction = options only)</label>
            </div>
            <div className="flex items-center gap-2">
              <input type="checkbox" checked={settings.auto_rollover_enabled !== 'false'}
                onChange={(e) => updateSetting('auto_rollover_enabled', String(e.target.checked))}
                className="rounded border-slate-300 text-blue-600 focus:ring-blue-500" />
              <label className="text-xs text-slate-600">Auto-rollover (exit max-profit, enter better opp)</label>
            </div>
            <div className="flex items-center gap-2">
              <input type="checkbox" checked={settings.time_filter_enabled === 'true'}
                onChange={(e) => updateSetting('time_filter_enabled', String(e.target.checked))}
                className="rounded border-slate-300 text-blue-600 focus:ring-blue-500" />
              <label className="text-xs text-slate-600">Peak-window filter (enter 09:15-09:45, 14:00-15:00 only)</label>
            </div>
            <div>
              <label className="text-xs text-slate-500">Target Underlyings</label>
              <div className="flex flex-wrap gap-1.5 mt-1.5">
                {['ALL','NIFTY','BANKNIFTY','MIDCPNIFTY','FINNIFTY'].map(u => {
                  const currentVal = settings.target_underlying || 'ALL';
                  const parts = currentVal.split(',').map(s => s.trim().toUpperCase());
                  const isActive = parts.includes(u);
                  return (
                    <button key={u} onClick={() => {
                      let next;
                      if (u === 'ALL') {
                        next = 'ALL';
                      } else {
                        const withoutAll = parts.filter(x => x !== 'ALL');
                        const toggled = withoutAll.includes(u) ? withoutAll.filter(x => x !== u) : [...withoutAll, u];
                        next = toggled.length === 0 ? 'ALL' : toggled.join(',');
                      }
                      updateSetting('target_underlying', next);
                    }}
                      className={`px-3 py-1.5 rounded-lg text-xs font-semibold border transition ${
                        isActive ? 'bg-blue-600 text-white border-blue-600' : 'bg-white text-slate-600 border-slate-300 hover:border-blue-400'
                      }`}>
                      {u}
                    </button>
                  );
                })}
              </div>
              <p className="text-xs text-slate-400 mt-1">Click to toggle. Multiple underlyings = parallel scan.</p>
            </div>
          </div>
        </div>

        <div className="bg-white rounded-xl border border-slate-200 p-5 shadow-sm">
          <h3 className="text-sm font-semibold text-slate-700 mb-4">Quick Actions</h3>
          <div className="space-y-3">
            <button onClick={runCycle}
              className="w-full px-4 py-2.5 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 transition">
              Run Scan & Execute Now
            </button>
            {openTrades.length > 0 && (
              <button onClick={closeAll}
                className="w-full px-4 py-2.5 bg-red-600 text-white rounded-lg text-sm font-medium hover:bg-red-700 transition">
                Close All ({openTrades.length})
              </button>
            )}
          </div>
          <div className="mt-4 space-y-2">
            <div className="flex justify-between text-xs text-slate-500">
              <span>Open positions</span><span className="font-semibold text-slate-700">{openTrades.length}</span>
            </div>
            <div className="flex justify-between text-xs text-slate-500">
              <span>Total scanned today</span><span className="font-semibold text-slate-700">{trades.length}</span>
            </div>
            <div className="flex justify-between text-xs text-slate-500">
              <span>Scan interval</span><span className="font-semibold text-slate-700">{settings.scan_interval_seconds || 300}s</span>
            </div>
          </div>
        </div>

        <div className="bg-white rounded-xl border border-slate-200 p-5 shadow-sm">
          <h3 className="text-sm font-semibold text-slate-700 mb-4">How Auto-Execute Works</h3>
          <div className="space-y-2 text-xs text-slate-600">
            <p><span className="font-semibold text-blue-600">1.</span> Scanner runs every {settings.scan_interval_seconds || 300}s, finds PARITY_BREAK opportunities</p>
            <p><span className="font-semibold text-blue-600">2.</span> Filters by edge ≥ ₹{settings.min_edge_after_costs || 500} after costs</p>
            <p><span className="font-semibold text-blue-600">3.</span> Checks position limits ({settings.max_positions_per_underlying || 3} per underlying, {settings.max_total_positions || 12} total)</p>
            <p><span className="font-semibold text-blue-600">4.</span> <span className="font-semibold">Smart rollover:</span> Same futures direction → only roll options legs (save brokerage). Different → close all + re-enter</p>
            <p><span className="font-semibold text-blue-600">5.</span> Only executes during market hours (9:15 AM - 3:30 PM IST)</p>
          </div>
        </div>
      </div>

      {openTrades.length > 0 && (
        <div>
          <h3 className="text-sm font-semibold text-slate-700 mb-3">Open Positions ({openTrades.length})</h3>
          {replacingTrade ? (
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
              <div className="bg-white rounded-xl border border-slate-200 overflow-hidden shadow-sm">
                <div className="bg-red-50 border-b border-red-200 px-4 py-3 flex items-center justify-between">
                  <h4 className="text-sm font-semibold text-red-700">Closing: {replacingTrade.underlying} {replacingTrade.strike} {replacingTrade.action}</h4>
                  <button onClick={() => setReplacingTradeId(null)} className="text-xs text-red-500 hover:text-red-700 font-medium">Cancel</button>
                </div>
                <table className="w-full text-sm">
                  <thead><tr className="bg-slate-50 border-b border-slate-200">
                    <th className="px-4 py-2 text-left text-xs text-slate-600">Underlying</th>
                    <th className="px-4 py-2 text-left text-xs text-slate-600">Strike</th>
                    <th className="px-4 py-2 text-left text-xs text-slate-600">Action</th>
                    <th className="px-4 py-2 text-right text-xs text-slate-600">CE</th>
                    <th className="px-4 py-2 text-right text-xs text-slate-600">PE</th>
                    <th className="px-4 py-2 text-right text-xs text-slate-600">FUT</th>
                    <th className="px-4 py-2 text-right text-xs text-slate-600">Lot</th>
                  </tr></thead>
                  <tbody><tr className="border-b border-slate-100">
                    <td className="px-4 py-3 font-medium text-slate-800">{replacingTrade.underlying}</td>
                    <td className="px-4 py-3 text-slate-700">{replacingTrade.strike}</td>
                    <td className="px-4 py-3"><span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${replacingTrade.action === 'CONVERSION' ? 'bg-emerald-100 text-emerald-700' : 'bg-red-100 text-red-700'}`}>{replacingTrade.action}</span></td>
                    <td className="px-4 py-3 text-right font-mono text-slate-700">{replacingTrade.ceEntryPrice?.toFixed(2) || '--'}</td>
                    <td className="px-4 py-3 text-right font-mono text-slate-700">{replacingTrade.peEntryPrice?.toFixed(2) || '--'}</td>
                    <td className="px-4 py-3 text-right font-mono text-slate-700">{replacingTrade.futEntryPrice?.toFixed(2) || '--'}</td>
                    <td className="px-4 py-3 text-right text-slate-700">{replacingTrade.lotSize}</td>
                  </tr></tbody>
                </table>
              </div>
              <div className="bg-white rounded-xl border border-slate-200 overflow-hidden shadow-sm">
                <div className="bg-emerald-50 border-b border-emerald-200 px-4 py-3">
                  <h4 className="text-sm font-semibold text-emerald-700">Enter New Position — {replacingTrade.underlying}</h4>
                  <p className="text-xs text-emerald-600 mt-1">Choose opportunity to replace with:</p>
                </div>
                <div className="max-h-96 overflow-y-auto">
                  {matchingOpps.length === 0 ? (
                    <p className="p-4 text-sm text-slate-500 text-center">No opportunities available for {replacingTrade.underlying}</p>
                  ) : (
                    <table className="w-full text-sm">
                      <thead><tr className="bg-slate-50 border-b border-slate-200 sticky top-0">
                        <th className="px-3 py-2 text-left text-xs text-slate-600">Strike</th>
                        <th className="px-3 py-2 text-left text-xs text-slate-600">Action</th>
                        <th className="px-3 py-2 text-right text-xs text-slate-600">Edge</th>
                        <th className="px-3 py-2 text-right text-xs text-slate-600">CE</th>
                        <th className="px-3 py-2 text-right text-xs text-slate-600">PE</th>
                        <th className="px-3 py-2 text-right text-xs text-slate-600">FUT</th>
                        <th className="px-3 py-2 text-right text-xs text-slate-600"></th>
                      </tr></thead>
                      <tbody>
                        {matchingOpps.map((opp, i) => (
                          <tr key={i} className="border-b border-slate-100 hover:bg-blue-50 cursor-pointer">
                            <td className="px-3 py-2 font-medium text-slate-800">{opp.strike}</td>
                            <td className="px-3 py-2"><span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${opp.action === 'CONVERSION' ? 'bg-emerald-100 text-emerald-700' : 'bg-red-100 text-red-700'}`}>{opp.action}</span></td>
                            <td className="px-3 py-2 text-right font-mono text-emerald-600 font-semibold">₹{opp.edgeAfterCosts?.toFixed(0) || '--'}</td>
                            <td className="px-3 py-2 text-right font-mono text-slate-600">{opp.cePrice?.toFixed(2) || opp.ceEntryPrice?.toFixed(2) || '--'}</td>
                            <td className="px-3 py-2 text-right font-mono text-slate-600">{opp.pePrice?.toFixed(2) || opp.peEntryPrice?.toFixed(2) || '--'}</td>
                            <td className="px-3 py-2 text-right font-mono text-slate-600">{opp.futuresPrice?.toFixed(2) || '--'}</td>
                            <td className="px-3 py-2 text-right space-x-1">
                              <button onClick={() => replaceTrade(replacingTradeId, opp)}
                                className="px-2 py-1 bg-blue-600 text-white rounded text-xs font-medium hover:bg-blue-700">
                                Replace All
                              </button>
                              {replacingTrade.action === opp.action && (
                                <button onClick={() => replaceOptionsOnly(replacingTradeId, opp)}
                                  className="px-2 py-1 bg-amber-500 text-white rounded text-xs font-medium hover:bg-amber-600">
                                  Options Only
                                </button>
                              )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </div>
              </div>
            </div>
          ) : (
          <div className="bg-white rounded-xl border border-slate-200 overflow-hidden shadow-sm">
            <table className="w-full text-sm">
              <thead>
                <tr className="bg-slate-50 border-b border-slate-200">
                  <th className="px-4 py-3 text-left text-xs font-semibold text-slate-600">Underlying</th>
                  <th className="px-4 py-3 text-left text-xs font-semibold text-slate-600">Strike</th>
                  <th className="px-4 py-3 text-left text-xs font-semibold text-slate-600">Action</th>
                  <th className="px-4 py-3 text-right text-xs font-semibold text-slate-600">CE Entry</th>
                  <th className="px-4 py-3 text-right text-xs font-semibold text-slate-600">PE Entry</th>
                  <th className="px-4 py-3 text-right text-xs font-semibold text-slate-600">FUT Entry</th>
                  <th className="px-4 py-3 text-right text-xs font-semibold text-slate-600">Lot</th>
                  <th className="px-4 py-3 text-left text-xs font-semibold text-slate-600">Time</th>
                  <th className="px-4 py-3 text-right text-xs font-semibold text-slate-600">Actions</th>
                </tr>
              </thead>
              <tbody>
                {openTrades.map(t => (
                  <tr key={t.id} className="border-b border-slate-100 hover:bg-slate-50">
                    <td className="px-4 py-3 font-medium text-slate-800">{t.underlying}</td>
                    <td className="px-4 py-3 text-slate-700">{t.strike}</td>
                    <td className="px-4 py-3">
                      <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${t.action === 'CONVERSION' ? 'bg-emerald-100 text-emerald-700' : 'bg-red-100 text-red-700'}`}>
                        {t.action}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right text-slate-700 font-mono">{t.ceEntryPrice?.toFixed(2) || '--'}</td>
                    <td className="px-4 py-3 text-right text-slate-700 font-mono">{t.peEntryPrice?.toFixed(2) || '--'}</td>
                    <td className="px-4 py-3 text-right text-slate-700 font-mono">{t.futEntryPrice?.toFixed(2) || '--'}</td>
                    <td className="px-4 py-3 text-right text-slate-700">{t.lotSize}</td>
                    <td className="px-4 py-3 text-xs text-slate-500">{t.executedAt ? new Date(t.executedAt).toLocaleTimeString() : '--'}</td>
                    <td className="px-4 py-3 text-right space-x-1">
                      <button onClick={() => setReplacingTradeId(t.id)}
                        className="px-2 py-1 bg-blue-50 text-blue-600 rounded text-xs font-medium hover:bg-blue-100">
                        Replace
                      </button>
                      <button onClick={() => closeTrade(t.id, 'all')}
                        className="px-2 py-1 bg-red-50 text-red-600 rounded text-xs font-medium hover:bg-red-100">
                        Close
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          )}
        </div>
      )}

      {closedTrades.length > 0 && (
        <div>
          <h3 className="text-sm font-semibold text-slate-700 mb-3">Recent Closed Trades ({closedTrades.length})</h3>
          <div className="bg-white rounded-xl border border-slate-200 overflow-hidden shadow-sm">
            <table className="w-full text-sm">
              <thead>
                <tr className="bg-slate-50 border-b border-slate-200">
                  <th className="px-4 py-3 text-left text-xs font-semibold text-slate-600">Underlying</th>
                  <th className="px-4 py-3 text-left text-xs font-semibold text-slate-600">Strike</th>
                  <th className="px-4 py-3 text-left text-xs font-semibold text-slate-600">Action</th>
                  <th className="px-4 py-3 text-left text-xs font-semibold text-slate-600">Status</th>
                  <th className="px-4 py-3 text-left text-xs font-semibold text-slate-600">Notes</th>
                  <th className="px-4 py-3 text-left text-xs font-semibold text-slate-600">Time</th>
                </tr>
              </thead>
              <tbody>
                {closedTrades.map(t => (
                  <tr key={t.id} className="border-b border-slate-100 hover:bg-slate-50">
                    <td className="px-4 py-3 font-medium text-slate-800">{t.underlying}</td>
                    <td className="px-4 py-3 text-slate-700">{t.strike}</td>
                    <td className="px-4 py-3 text-slate-700">{t.action}</td>
                    <td className="px-4 py-3">
                      <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${
                        t.status === 'CLOSED' ? 'bg-slate-100 text-slate-600' :
                        t.status === 'ROLLED' ? 'bg-blue-100 text-blue-600' :
                        t.status === 'CLOSED_OPTIONS' ? 'bg-amber-100 text-amber-600' :
                        'bg-red-100 text-red-600'
                      }`}>{t.status}</span>
                    </td>
                    <td className="px-4 py-3 text-xs text-slate-500">{t.notes || '--'}</td>
                    <td className="px-4 py-3 text-xs text-slate-500">{t.closedAt ? new Date(t.closedAt).toLocaleTimeString() : '--'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}

function StatCard({ label, value, color = 'text-slate-800' }) {
  return (
    <div className="bg-white rounded-xl border border-slate-200 p-4 shadow-sm">
      <p className="text-xs text-slate-500 uppercase">{label}</p>
      <p className={`text-2xl font-bold mt-1 ${color}`}>{value}</p>
    </div>
  );
}
