import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import client from '../api/client';

const RISK_PRESETS = [25000, 50000, 100000, 200000, 500000];
const NIFTY50_SYMBOLS = [
  'ADANIENT','ADANIPORTS','APOLLOHOSP','ASIANPAINT','AXISBANK','BAJAJ-AUTO','BAJFINFENCE','BAJAJFINSV',
  'BPCL','BRITANNIA','CIPLA','COALINDIA','DRREDDY','EICHERMOT','GRASIM','HCLTECH','HDFCBANK','HDFCLIFE',
  'HEROMOTOCO','HINDALCO','HINDUNILVR','ICICIBANK','INDUSINDBK','INFY','ITC','JSWSTEEL','KOTAKBANK',
  'LT','M&M','MARUTI','NESTLEIND','NTPC','ONGC','POWERGRID','RELIANCE','SBILIFE','SBIN','SUNPHARMA',
  'TATAMOTORS','TATASTEEL','TCS','TECHM','TITAN','TRENT','ULTRACEMCO','WIPRO'
];

const STRATEGY_INFO = {
  4:  { icon: '🌅', color: '#f59e0b', desc: 'Short-selling ORB breakdowns during first 45min. Intraday only, exit by 2:30 PM.' },
  15: { icon: '📗', color: '#10b981', desc: 'Buy stocks dropped >3% in last 3 days. Hold 1-7 days, trailing stop.' },
  16: { icon: '⚡', color: '#ef4444', desc: '3-bar V-bottom reversal pattern. Intraday scalper, quick exits.' },
  21: { icon: '📊', color: '#6366f1', desc: 'Buy when EMA50 distance < -3%. SL 3%, hold 7 days, trailing stop.' },
  23: { icon: '🔴', color: '#dc2626', desc: '3 consecutive red days + volume surge. Mean reversion, SL 3%.' },
};

const STATUS_COLORS = {
  ACTIVE:  { bg: 'rgba(16,185,129,0.12)', text: '#059669', border: '#86efac', dot: '#10b981' },
  PAUSED:  { bg: 'rgba(245,158,11,0.12)', text: '#d97706', border: '#fcd34d', dot: '#f59e0b' },
  STOPPED: { bg: 'rgba(107,114,128,0.12)', text: '#6b7280', border: '#d1d5db', dot: '#9ca3af' },
};
const MODE_COLORS = {
  LIVE:  { bg: 'rgba(239,68,68,0.12)', text: '#dc2626' },
  PAPER: { bg: 'rgba(59,130,246,0.12)', text: '#2563eb' },
};

function timeAgo(dateStr) {
  if (!dateStr) return '—';
  const diff = Date.now() - new Date(dateStr).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.floor(hrs / 24);
  return `${days}d ago`;
}

export default function TraderDashboard() {
  const qc = useQueryClient();
  const [deployForm, setDeployForm] = useState(null);

  const { data: deployments = [] } = useQuery({
    queryKey: ['deployments'],
    queryFn: () => client.get('/deployments').then(r => r.data),
    staleTime: 30000,
    refetchInterval: 60000,
  });

  const { data: strategies = [] } = useQuery({
    queryKey: ['strategies'],
    queryFn: () => client.get('/strategies').then(r => r.data),
    staleTime: 300000,
  });

  const { data: signals = [] } = useQuery({
    queryKey: ['signals'],
    queryFn: () => client.get('/signals').then(r => r.data),
    staleTime: 30000,
    refetchInterval: 60000,
  });

  const deployMut = useMutation({
    mutationFn: (body) => client.post('/deployments', body).then(r => r.data),
    onSuccess: () => { qc.invalidateQueries(['deployments']); setDeployForm(null); },
  });

  const statusMut = useMutation({
    mutationFn: ({ id, status }) => client.patch(`/deployments/${id}/status`, { status }).then(r => r.data),
    onSuccess: () => qc.invalidateQueries(['deployments']),
  });

  const stopMut = useMutation({
    mutationFn: (id) => client.delete(`/deployments/${id}`).then(r => r.data),
    onSuccess: () => qc.invalidateQueries(['deployments']),
  });

  const [orderForm, setOrderForm] = useState({ symbol: '', side: 'BUY', quantity: 1, price: '', orderType: 'MARKET', mode: 'PAPER', deploymentId: null });
  const [orderResult, setOrderResult] = useState(null);
  const [editForm, setEditForm] = useState(null);

  const { data: brokers = [] } = useQuery({
    queryKey: ['brokers'],
    queryFn: () => client.get('/brokers').then(r => r.data),
    staleTime: 300000,
  });

  const placeOrderMut = useMutation({
    mutationFn: (body) => client.post('/orders/manual', body).then(r => r.data),
    onSuccess: (data) => {
      setOrderResult(data);
      qc.invalidateQueries(['deployments']);
    },
  });

  const editMut = useMutation({
    mutationFn: ({ id, ...patch }) => client.patch(`/deployments/${id}`, patch).then(r => r.data),
    onSuccess: () => { qc.invalidateQueries(['deployments']); setEditForm(null); },
  });

  const activeDeployments = deployments.filter(d => d.status === 'ACTIVE');
  const totalCapital = activeDeployments.reduce((s, d) => s + (d.capital || 0), 0);

  const deployedStrategyIds = new Set(deployments.filter(d => d.status !== 'STOPPED').map(d => d.strategyId));
  const availableStrategies = strategies.filter(s => s.enabled && !deployedStrategyIds.has(s.id));
  const deployedStrategies = strategies.filter(s => deployedStrategyIds.has(s.id));

  return (
    <div style={{ animation: 'fadeIn 0.5s ease' }}>
      <style>{`
        @keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
        @keyframes slideDown { from { transform: translateY(-20px); opacity: 0; } to { transform: translateY(0); opacity: 1; } }
        @keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.7; } }
        .trader-card { transition: all 0.25s ease; cursor: pointer; }
        .trader-card:hover { transform: translateY(-4px); box-shadow: 0 12px 24px rgba(0,0,0,0.12); }
        .trader-btn { transition: all 0.2s ease; }
        .trader-btn:hover { transform: scale(1.03); }
        .trader-btn:active { transform: scale(0.98); }
      `}</style>

      {/* Header */}
      <div style={{ marginBottom: '28px', animation: 'slideDown 0.5s ease' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '8px' }}>
          <div style={{ fontSize: '32px' }}>🏪</div>
          <h1 style={{ fontSize: '28px', fontWeight: 800, color: '#1f2937', margin: 0 }}>Trader Dashboard</h1>
        </div>
        <p style={{ color: '#6b7280', fontSize: '14px', margin: 0 }}>Deploy strategies, manage positions, monitor performance</p>
      </div>

      {/* Stats Row */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: '16px', marginBottom: '28px' }}>
        <div style={{ padding: '20px', background: 'linear-gradient(135deg, #6366f1, #8b5cf6)', borderRadius: '14px', color: 'white' }}>
          <div style={{ fontSize: '11px', fontWeight: 700, opacity: 0.8, textTransform: 'uppercase', letterSpacing: '0.5px', marginBottom: '8px' }}>Active Strategies</div>
          <div style={{ fontSize: '28px', fontWeight: 800 }}>{activeDeployments.length}</div>
        </div>
        <div style={{ padding: '20px', background: 'linear-gradient(135deg, #10b981, #059669)', borderRadius: '14px', color: 'white' }}>
          <div style={{ fontSize: '11px', fontWeight: 700, opacity: 0.8, textTransform: 'uppercase', letterSpacing: '0.5px', marginBottom: '8px' }}>Total Capital</div>
          <div style={{ fontSize: '28px', fontWeight: 800 }}>₹{(totalCapital / 100000).toFixed(1)}L</div>
        </div>
        <div style={{ padding: '20px', background: 'linear-gradient(135deg, #f59e0b, #d97706)', borderRadius: '14px', color: 'white' }}>
          <div style={{ fontSize: '11px', fontWeight: 700, opacity: 0.8, textTransform: 'uppercase', letterSpacing: '0.5px', marginBottom: '8px' }}>Signals Today</div>
          <div style={{ fontSize: '28px', fontWeight: 800 }}>{signals.filter(s => { const d = new Date(s.createdAt); const now = new Date(); return d.toDateString() === now.toDateString(); }).length}</div>
        </div>
        <div style={{ padding: '20px', background: 'linear-gradient(135deg, #ef4444, #dc2626)', borderRadius: '14px', color: 'white' }}>
          <div style={{ fontSize: '11px', fontWeight: 700, opacity: 0.8, textTransform: 'uppercase', letterSpacing: '0.5px', marginBottom: '8px' }}>Open Positions</div>
          <div style={{ fontSize: '28px', fontWeight: 800 }}>{activeDeployments.reduce((s, d) => s + (d.openPositions || 0), 0)}</div>
        </div>
      </div>

      {/* Place Order Terminal */}
      <div style={{ marginBottom: '32px', animation: 'slideDown 0.5s ease 0.1s both' }}>
        <h3 style={{ fontSize: '14px', fontWeight: 700, color: '#6b7280', textTransform: 'uppercase', letterSpacing: '0.8px', marginBottom: '14px' }}>
          📋 Place Order
        </h3>
        <div style={{ background: 'white', borderRadius: '16px', padding: '24px', boxShadow: '0 2px 10px rgba(0,0,0,0.05)', border: '1px solid #eef0f4' }}>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr 1fr', gap: '12px', alignItems: 'end' }}>
            {/* Symbol */}
            <div>
              <label style={{ fontSize: '11px', fontWeight: 700, color: '#6b7280', textTransform: 'uppercase', display: 'block', marginBottom: '6px' }}>Symbol</label>
              <input list="symbol-list" value={orderForm.symbol} onChange={e => setOrderForm({ ...orderForm, symbol: e.target.value.toUpperCase() })}
                placeholder="e.g. RELIANCE"
                style={{ width: '100%', padding: '10px 12px', borderRadius: '8px', border: '2px solid #e5e7eb', fontSize: '14px', fontWeight: 600, outline: 'none', textTransform: 'uppercase' }} />
              <datalist id="symbol-list">
                {NIFTY50_SYMBOLS.map(s => <option key={s} value={s} />)}
              </datalist>
            </div>

            {/* Side */}
            <div>
              <label style={{ fontSize: '11px', fontWeight: 700, color: '#6b7280', textTransform: 'uppercase', display: 'block', marginBottom: '6px' }}>Side</label>
              <div style={{ display: 'flex', gap: '6px' }}>
                {['BUY', 'SELL'].map(s => (
                  <button key={s} onClick={() => setOrderForm({ ...orderForm, side: s })}
                    className="trader-btn"
                    style={{ flex: 1, padding: '10px', borderRadius: '8px', border: '2px solid', borderColor: orderForm.side === s ? (s === 'BUY' ? '#10b981' : '#ef4444') : '#e5e7eb', background: orderForm.side === s ? (s === 'BUY' ? 'rgba(16,185,129,0.08)' : 'rgba(239,68,68,0.08)') : 'white', color: orderForm.side === s ? (s === 'BUY' ? '#059669' : '#dc2626') : '#6b7280', fontSize: '13px', fontWeight: 700, cursor: 'pointer' }}>
                    {s}
                  </button>
                ))}
              </div>
            </div>

            {/* Quantity */}
            <div>
              <label style={{ fontSize: '11px', fontWeight: 700, color: '#6b7280', textTransform: 'uppercase', display: 'block', marginBottom: '6px' }}>Qty</label>
              <input type="number" min="1" value={orderForm.quantity} onChange={e => setOrderForm({ ...orderForm, quantity: Math.max(1, parseInt(e.target.value) || 1) })}
                style={{ width: '100%', padding: '10px 12px', borderRadius: '8px', border: '2px solid #e5e7eb', fontSize: '14px', fontWeight: 600, outline: 'none' }} />
            </div>

            {/* Order Type + Price */}
            <div>
              <label style={{ fontSize: '11px', fontWeight: 700, color: '#6b7280', textTransform: 'uppercase', display: 'block', marginBottom: '6px' }}>Type</label>
              <div style={{ display: 'flex', gap: '6px', marginBottom: '6px' }}>
                {['MARKET', 'LIMIT'].map(t => (
                  <button key={t} onClick={() => setOrderForm({ ...orderForm, orderType: t })}
                    className="trader-btn"
                    style={{ flex: 1, padding: '6px', borderRadius: '6px', border: '2px solid', borderColor: orderForm.orderType === t ? '#6366f1' : '#e5e7eb', background: orderForm.orderType === t ? 'rgba(99,102,241,0.08)' : 'white', color: orderForm.orderType === t ? '#4f46e5' : '#6b7280', fontSize: '11px', fontWeight: 700, cursor: 'pointer' }}>
                    {t}
                  </button>
                ))}
              </div>
              {orderForm.orderType === 'LIMIT' && (
                <input type="number" step="0.05" value={orderForm.price} onChange={e => setOrderForm({ ...orderForm, price: e.target.value })}
                  placeholder="Price" style={{ width: '100%', padding: '6px 10px', borderRadius: '6px', border: '2px solid #e5e7eb', fontSize: '13px', fontWeight: 600, outline: 'none' }} />
              )}
            </div>

            {/* Mode + Place */}
            <div>
              <label style={{ fontSize: '11px', fontWeight: 700, color: '#6b7280', textTransform: 'uppercase', display: 'block', marginBottom: '6px' }}>Mode</label>
              <div style={{ display: 'flex', gap: '6px', marginBottom: '8px' }}>
                {['PAPER', 'LIVE'].map(m => (
                  <button key={m} onClick={() => setOrderForm({ ...orderForm, mode: m })}
                    className="trader-btn"
                    style={{ flex: 1, padding: '6px', borderRadius: '6px', border: '2px solid', borderColor: orderForm.mode === m ? (m === 'LIVE' ? '#dc2626' : '#2563eb') : '#e5e7eb', background: orderForm.mode === m ? (m === 'LIVE' ? 'rgba(239,68,68,0.08)' : 'rgba(59,130,246,0.08)') : 'white', color: orderForm.mode === m ? (m === 'LIVE' ? '#dc2626' : '#2563eb') : '#6b7280', fontSize: '11px', fontWeight: 700, cursor: 'pointer' }}>
                    {m === 'LIVE' ? '🔴' : '🔵'} {m}
                  </button>
                ))}
              </div>
              <button className="trader-btn"
                onClick={() => {
                  if (!orderForm.symbol.trim()) { alert('Enter a symbol'); return; }
                  const payload = { ...orderForm, price: orderForm.orderType === 'LIMIT' && orderForm.price ? parseFloat(orderForm.price) : null };
                  placeOrderMut.mutate(payload);
                }}
                disabled={placeOrderMut.isPending}
                style={{ width: '100%', padding: '10px', borderRadius: '8px', border: 'none', background: orderForm.side === 'BUY' ? 'linear-gradient(135deg, #10b981, #059669)' : 'linear-gradient(135deg, #ef4444, #dc2626)', color: 'white', fontSize: '13px', fontWeight: 700, cursor: placeOrderMut.isPending ? 'not-allowed' : 'pointer', opacity: placeOrderMut.isPending ? 0.6 : 1 }}>
                {placeOrderMut.isPending ? '⏳ Placing...' : `🚀 ${orderForm.side}`}
              </button>
            </div>
          </div>

          {/* Order Result */}
          {orderResult && (
            <div style={{ marginTop: '16px', padding: '12px 16px', borderRadius: '10px', background: orderResult.success ? 'rgba(16,185,129,0.08)' : 'rgba(239,68,68,0.08)', border: `1px solid ${orderResult.success ? '#86efac' : '#fca5a5'}` }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <div>
                  <span style={{ fontSize: '13px', fontWeight: 700, color: orderResult.success ? '#059669' : '#dc2626' }}>
                    {orderResult.success ? '✅ Order Placed' : '❌ Order Rejected'}
                  </span>
                  <span style={{ fontSize: '12px', color: '#6b7280', marginLeft: '12px' }}>
                    {orderResult.symbol} {orderResult.side} {orderResult.quantity} @ ₹{orderResult.price || 'MKT'} ({orderResult.mode})
                  </span>
                  {orderResult.brokerOrderId && <span style={{ fontSize: '11px', color: '#9ca3af', marginLeft: '12px' }}>Broker ID: {orderResult.brokerOrderId}</span>}
                </div>
                <button onClick={() => setOrderResult(null)} style={{ background: 'none', border: 'none', color: '#9ca3af', cursor: 'pointer', fontSize: '16px' }}>✕</button>
              </div>
              {orderResult.message && <div style={{ fontSize: '12px', color: '#6b7280', marginTop: '4px' }}>{orderResult.message}</div>}
            </div>
          )}
        </div>
      </div>

      {/* Active Deployments */}
      {deployments.length > 0 && (
        <div style={{ marginBottom: '32px', animation: 'slideDown 0.5s ease 0.1s both' }}>
          <h3 style={{ fontSize: '14px', fontWeight: 700, color: '#6b7280', textTransform: 'uppercase', letterSpacing: '0.8px', marginBottom: '14px' }}>
            Your Deployments ({deployments.length})
          </h3>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(340px, 1fr))', gap: '16px' }}>
            {deployments.map(d => {
              const info = STRATEGY_INFO[d.strategyId] || { icon: '📈', color: '#6366f1', desc: '' };
              const st = STATUS_COLORS[d.status] || STATUS_COLORS.STOPPED;
              const mc = MODE_COLORS[d.mode] || MODE_COLORS.PAPER;
              return (
                <div key={d.id} className="trader-card" style={{
                  background: 'white', borderRadius: '16px', padding: '20px',
                  boxShadow: '0 2px 10px rgba(0,0,0,0.05)', border: `1px solid ${st.border}`,
                  position: 'relative', overflow: 'hidden',
                }}>
                  <div style={{ position: 'absolute', top: 0, left: 0, right: 0, height: '4px', background: `linear-gradient(90deg, ${info.color}, ${info.color}80)` }} />
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '12px' }}>
                    <div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '4px' }}>
                        <span style={{ fontSize: '18px' }}>{info.icon}</span>
                        <span style={{ fontSize: '15px', fontWeight: 800, color: '#1f2937' }}>{d.strategyName}</span>
                      </div>
                      <div style={{ fontSize: '12px', color: '#9ca3af' }}>{info.desc}</div>
                    </div>
                    <div style={{ display: 'flex', gap: '6px' }}>
                      <span style={{ padding: '3px 8px', borderRadius: '20px', fontSize: '10px', fontWeight: 800, background: mc.bg, color: mc.text }}>{d.mode}</span>
                      <span style={{ padding: '3px 8px', borderRadius: '20px', fontSize: '10px', fontWeight: 800, background: st.bg, color: st.text, display: 'flex', alignItems: 'center', gap: '4px' }}>
                        <span style={{ width: 6, height: 6, borderRadius: '50%', background: st.dot, display: 'inline-block', animation: d.status === 'ACTIVE' ? 'pulse 2s infinite' : 'none' }} />
                        {d.status}
                      </span>
                    </div>
                  </div>

                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '10px', marginBottom: '14px' }}>
                    <div style={{ background: '#f9fafb', borderRadius: '10px', padding: '8px 10px', textAlign: 'center' }}>
                      <div style={{ fontSize: '9px', fontWeight: 700, color: '#9ca3af', textTransform: 'uppercase', marginBottom: '2px' }}>Capital</div>
                      <div style={{ fontSize: '14px', fontWeight: 800, color: '#1f2937' }}>₹{((d.capital || 0) / 1000).toFixed(0)}K</div>
                    </div>
                    <div style={{ background: '#f9fafb', borderRadius: '10px', padding: '8px 10px', textAlign: 'center' }}>
                      <div style={{ fontSize: '9px', fontWeight: 700, color: '#9ca3af', textTransform: 'uppercase', marginBottom: '2px' }}>Positions</div>
                      <div style={{ fontSize: '14px', fontWeight: 800, color: '#1f2937' }}>{d.openPositions || 0}</div>
                    </div>
                    <div style={{ background: '#f9fafb', borderRadius: '10px', padding: '8px 10px', textAlign: 'center' }}>
                      <div style={{ fontSize: '9px', fontWeight: 700, color: '#9ca3af', textTransform: 'uppercase', marginBottom: '2px' }}>Today Signals</div>
                      <div style={{ fontSize: '14px', fontWeight: 800, color: '#1f2937' }}>{d.signalsToday || 0}</div>
                    </div>
                  </div>

                  {d.lastSignalAt && (
                    <div style={{ fontSize: '11px', color: '#9ca3af', marginBottom: '12px' }}>
                      Last signal: {timeAgo(d.lastSignalAt)}
                    </div>
                  )}

                  <div style={{ display: 'flex', gap: '8px' }}>
                    <button className="trader-btn" onClick={() => setEditForm({ id: d.id, capital: d.capital || 100000, mode: d.mode || 'PAPER', brokerAccountId: d.brokerAccountId })}
                      style={{ flex: 1, padding: '8px', borderRadius: '8px', border: 'none', background: 'rgba(99,102,241,0.12)', color: '#4f46e5', fontSize: '12px', fontWeight: 700, cursor: 'pointer' }}>
                      ✏️ Edit
                    </button>
                    {d.status === 'ACTIVE' && (
                      <button className="trader-btn" onClick={() => statusMut.mutate({ id: d.id, status: 'PAUSED' })}
                        style={{ flex: 1, padding: '8px', borderRadius: '8px', border: 'none', background: 'rgba(245,158,11,0.12)', color: '#d97706', fontSize: '12px', fontWeight: 700, cursor: 'pointer' }}>
                        ⏸ Pause
                      </button>
                    )}
                    {d.status === 'PAUSED' && (
                      <button className="trader-btn" onClick={() => statusMut.mutate({ id: d.id, status: 'ACTIVE' })}
                        style={{ flex: 1, padding: '8px', borderRadius: '8px', border: 'none', background: 'rgba(16,185,129,0.12)', color: '#059669', fontSize: '12px', fontWeight: 700, cursor: 'pointer' }}>
                        ▶ Resume
                      </button>
                    )}
                    {d.status !== 'STOPPED' && (
                      <button className="trader-btn" onClick={() => { if (confirm('Stop this deployment?')) stopMut.mutate(d.id); }}
                        style={{ flex: 1, padding: '8px', borderRadius: '8px', border: 'none', background: 'rgba(239,68,68,0.12)', color: '#dc2626', fontSize: '12px', fontWeight: 700, cursor: 'pointer' }}>
                        ⏹ Stop
                      </button>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* Strategy Catalog */}
      <div style={{ marginBottom: '32px', animation: 'slideDown 0.5s ease 0.2s both' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '14px' }}>
          <h3 style={{ fontSize: '14px', fontWeight: 700, color: '#6b7280', textTransform: 'uppercase', letterSpacing: '0.8px', margin: 0 }}>
            Strategy Catalog ({availableStrategies.length} available)
          </h3>
        </div>

        {availableStrategies.length === 0 ? (
          <div style={{ background: 'white', borderRadius: '16px', padding: '40px', textAlign: 'center', boxShadow: '0 2px 10px rgba(0,0,0,0.05)' }}>
            <div style={{ fontSize: '36px', marginBottom: '12px' }}>✅</div>
            <div style={{ fontSize: '16px', fontWeight: 700, color: '#1f2937', marginBottom: '4px' }}>All strategies deployed!</div>
            <div style={{ fontSize: '13px', color: '#9ca3af' }}>Every available strategy has an active or paused deployment.</div>
          </div>
        ) : (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: '16px' }}>
            {availableStrategies.map(s => {
              const info = STRATEGY_INFO[s.id] || { icon: '📈', color: '#6366f1', desc: s.description || '' };
              return (
                <div key={s.id} className="trader-card" style={{
                  background: 'white', borderRadius: '16px', padding: '20px',
                  boxShadow: '0 2px 10px rgba(0,0,0,0.05)', border: '1px solid #eef0f4',
                  position: 'relative', overflow: 'hidden',
                }}>
                  <div style={{ position: 'absolute', top: 0, left: 0, right: 0, height: '4px', background: `linear-gradient(90deg, ${info.color}, ${info.color}60)` }} />
                  <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '10px' }}>
                    <span style={{ fontSize: '24px' }}>{info.icon}</span>
                    <div>
                      <div style={{ fontSize: '15px', fontWeight: 800, color: '#1f2937' }}>{s.name}</div>
                      <div style={{ fontSize: '11px', color: '#9ca3af', textTransform: 'uppercase' }}>{s.timeframe || 'POSITIONAL'}</div>
                    </div>
                  </div>
                  <div style={{ fontSize: '12px', color: '#6b7280', marginBottom: '16px', lineHeight: '1.5' }}>{info.desc}</div>
                  <button className="trader-btn" onClick={() => setDeployForm({ strategyId: s.id, strategyName: s.name, capital: 100000, mode: 'PAPER' })}
                    style={{ width: '100%', padding: '10px', borderRadius: '10px', border: 'none', background: `linear-gradient(135deg, ${info.color}, ${info.color}cc)`, color: 'white', fontSize: '13px', fontWeight: 700, cursor: 'pointer', letterSpacing: '0.3px' }}>
                    🚀 Deploy Strategy
                  </button>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Deploy Modal */}
      {deployForm && (
        <div style={{ position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000, animation: 'fadeIn 0.2s ease' }}
          onClick={(e) => { if (e.target === e.currentTarget) setDeployForm(null); }}>
          <div style={{ background: 'white', borderRadius: '20px', padding: '32px', maxWidth: '420px', width: '90%', boxShadow: '0 20px 60px rgba(0,0,0,0.3)', animation: 'slideDown 0.3s ease' }}>
            <h2 style={{ fontSize: '20px', fontWeight: 800, color: '#1f2937', margin: '0 0 4px' }}>Deploy {deployForm.strategyName}</h2>
            <p style={{ fontSize: '13px', color: '#9ca3af', margin: '0 0 20px' }}>Configure capital and mode</p>

            <div style={{ marginBottom: '16px' }}>
              <label style={{ fontSize: '12px', fontWeight: 700, color: '#6b7280', textTransform: 'uppercase', display: 'block', marginBottom: '6px' }}>Capital (₹)</label>
              <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap', marginBottom: '8px' }}>
                {RISK_PRESETS.map(cap => (
                  <button key={cap} onClick={() => setDeployForm({ ...deployForm, capital: cap })}
                    className="trader-btn"
                    style={{ padding: '6px 12px', borderRadius: '8px', border: '2px solid', borderColor: deployForm.capital === cap ? '#6366f1' : '#e5e7eb', background: deployForm.capital === cap ? 'rgba(99,102,241,0.08)' : 'white', color: deployForm.capital === cap ? '#4f46e5' : '#6b7280', fontSize: '12px', fontWeight: 700, cursor: 'pointer' }}>
                    ₹{(cap / 1000).toFixed(0)}K
                  </button>
                ))}
              </div>
              <input type="number" value={deployForm.capital} onChange={e => setDeployForm({ ...deployForm, capital: Number(e.target.value) })}
                style={{ width: '100%', padding: '10px 12px', borderRadius: '8px', border: '2px solid #e5e7eb', fontSize: '14px', fontWeight: 600, outline: 'none' }} />
            </div>

            <div style={{ marginBottom: '20px' }}>
              <label style={{ fontSize: '12px', fontWeight: 700, color: '#6b7280', textTransform: 'uppercase', display: 'block', marginBottom: '6px' }}>Mode</label>
              <div style={{ display: 'flex', gap: '8px' }}>
                {['PAPER', 'LIVE'].map(m => (
                  <button key={m} onClick={() => setDeployForm({ ...deployForm, mode: m })}
                    className="trader-btn"
                    style={{ flex: 1, padding: '10px', borderRadius: '8px', border: '2px solid', borderColor: deployForm.mode === m ? (m === 'LIVE' ? '#dc2626' : '#2563eb') : '#e5e7eb', background: deployForm.mode === m ? (m === 'LIVE' ? 'rgba(239,68,68,0.08)' : 'rgba(59,130,246,0.08)') : 'white', color: deployForm.mode === m ? (m === 'LIVE' ? '#dc2626' : '#2563eb') : '#6b7280', fontSize: '13px', fontWeight: 700, cursor: 'pointer' }}>
                    {m === 'LIVE' ? '🔴' : '🔵'} {m}
                  </button>
                ))}
              </div>
            </div>

            <div style={{ display: 'flex', gap: '10px' }}>
              <button onClick={() => setDeployForm(null)} style={{ flex: 1, padding: '10px', borderRadius: '10px', border: '2px solid #e5e7eb', background: 'white', color: '#6b7280', fontSize: '13px', fontWeight: 700, cursor: 'pointer' }}>Cancel</button>
              <button className="trader-btn" onClick={() => deployMut.mutate({ strategyId: deployForm.strategyId, mode: deployForm.mode, capital: deployForm.capital })}
                disabled={deployMut.isPending}
                style={{ flex: 2, padding: '10px', borderRadius: '10px', border: 'none', background: 'linear-gradient(135deg, #6366f1, #8b5cf6)', color: 'white', fontSize: '13px', fontWeight: 700, cursor: deployMut.isPending ? 'not-allowed' : 'pointer', opacity: deployMut.isPending ? 0.6 : 1 }}>
                {deployMut.isPending ? '⏳ Deploying...' : '🚀 Deploy Now'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Edit Deployment Modal */}
      {editForm && (
        <div style={{ position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000, animation: 'fadeIn 0.2s ease' }}
          onClick={(e) => { if (e.target === e.currentTarget) setEditForm(null); }}>
          <div style={{ background: 'white', borderRadius: '20px', padding: '32px', maxWidth: '440px', width: '90%', boxShadow: '0 20px 60px rgba(0,0,0,0.3)', animation: 'slideDown 0.3s ease' }}>
            <h2 style={{ fontSize: '20px', fontWeight: 800, color: '#1f2937', margin: '0 0 4px' }}>Edit Deployment</h2>
            <p style={{ fontSize: '13px', color: '#9ca3af', margin: '0 0 20px' }}>Update capital, mode, or broker assignment</p>

            {/* Capital */}
            <div style={{ marginBottom: '16px' }}>
              <label style={{ fontSize: '12px', fontWeight: 700, color: '#6b7280', textTransform: 'uppercase', display: 'block', marginBottom: '6px' }}>Capital (₹)</label>
              <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap', marginBottom: '8px' }}>
                {RISK_PRESETS.map(cap => (
                  <button key={cap} onClick={() => setEditForm({ ...editForm, capital: cap })}
                    className="trader-btn"
                    style={{ padding: '6px 12px', borderRadius: '8px', border: '2px solid', borderColor: editForm.capital === cap ? '#6366f1' : '#e5e7eb', background: editForm.capital === cap ? 'rgba(99,102,241,0.08)' : 'white', color: editForm.capital === cap ? '#4f46e5' : '#6b7280', fontSize: '12px', fontWeight: 700, cursor: 'pointer' }}>
                    ₹{(cap / 1000).toFixed(0)}K
                  </button>
                ))}
              </div>
              <input type="number" value={editForm.capital} onChange={e => setEditForm({ ...editForm, capital: Number(e.target.value) })}
                style={{ width: '100%', padding: '10px 12px', borderRadius: '8px', border: '2px solid #e5e7eb', fontSize: '14px', fontWeight: 600, outline: 'none' }} />
            </div>

            {/* Mode */}
            <div style={{ marginBottom: '16px' }}>
              <label style={{ fontSize: '12px', fontWeight: 700, color: '#6b7280', textTransform: 'uppercase', display: 'block', marginBottom: '6px' }}>Mode</label>
              <div style={{ display: 'flex', gap: '8px' }}>
                {['PAPER', 'LIVE'].map(m => (
                  <button key={m} onClick={() => setEditForm({ ...editForm, mode: m })}
                    className="trader-btn"
                    style={{ flex: 1, padding: '10px', borderRadius: '8px', border: '2px solid', borderColor: editForm.mode === m ? (m === 'LIVE' ? '#dc2626' : '#2563eb') : '#e5e7eb', background: editForm.mode === m ? (m === 'LIVE' ? 'rgba(239,68,68,0.08)' : 'rgba(59,130,246,0.08)') : 'white', color: editForm.mode === m ? (m === 'LIVE' ? '#dc2626' : '#2563eb') : '#6b7280', fontSize: '13px', fontWeight: 700, cursor: 'pointer' }}>
                    {m === 'LIVE' ? '🔴' : '🔵'} {m}
                  </button>
                ))}
              </div>
            </div>

            {/* Broker Account (only shown for LIVE mode) */}
            {editForm.mode === 'LIVE' && (
              <div style={{ marginBottom: '20px', padding: '12px', borderRadius: '10px', background: 'rgba(239,68,68,0.05)', border: '1px solid rgba(239,68,68,0.15)' }}>
                <label style={{ fontSize: '12px', fontWeight: 700, color: '#6b7280', textTransform: 'uppercase', display: 'block', marginBottom: '6px' }}>Broker Account</label>
                {brokers.length > 0 ? (
                  <select value={editForm.brokerAccountId || ''} onChange={e => setEditForm({ ...editForm, brokerAccountId: e.target.value ? Number(e.target.value) : null })}
                    style={{ width: '100%', padding: '10px 12px', borderRadius: '8px', border: '2px solid #e5e7eb', fontSize: '13px', fontWeight: 600, outline: 'none', background: 'white' }}>
                    <option value="">Select broker...</option>
                    {brokers.map(b => <option key={b.id} value={b.id}>{b.brokerName} ({b.clientId || 'Active'})</option>)}
                  </select>
                ) : (
                  <div style={{ fontSize: '12px', color: '#dc2626' }}>⚠️ No broker connected. Go to <a href="/brokers" style={{ color: '#4f46e5' }}>Brokers</a> to connect one first.</div>
                )}
              </div>
            )}

            {/* Warning when switching to LIVE */}
            {editForm.mode === 'LIVE' && (
              <div style={{ marginBottom: '16px', padding: '10px 14px', borderRadius: '8px', background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)' }}>
                <div style={{ fontSize: '12px', fontWeight: 700, color: '#d97706', marginBottom: '4px' }}>⚠️ Live Trading Warning</div>
                <div style={{ fontSize: '11px', color: '#6b7280' }}>Real money will be used. Ensure IP <b>106.51.215.133</b> is whitelisted in Kite developer console.</div>
              </div>
            )}

            <div style={{ display: 'flex', gap: '10px' }}>
              <button onClick={() => setEditForm(null)} style={{ flex: 1, padding: '10px', borderRadius: '10px', border: '2px solid #e5e7eb', background: 'white', color: '#6b7280', fontSize: '13px', fontWeight: 700, cursor: 'pointer' }}>Cancel</button>
              <button className="trader-btn"
                onClick={() => {
                  const patch = { capital: editForm.capital, mode: editForm.mode };
                  if (editForm.mode === 'LIVE' && editForm.brokerAccountId) {
                    patch.brokerAccountId = editForm.brokerAccountId;
                  }
                  editMut.mutate({ id: editForm.id, ...patch });
                }}
                disabled={editMut.isPending}
                style={{ flex: 2, padding: '10px', borderRadius: '10px', border: 'none',
                  background: editForm.mode === 'LIVE' ? 'linear-gradient(135deg, #dc2626, #b91c1c)' : 'linear-gradient(135deg, #6366f1, #8b5cf6)',
                  color: 'white', fontSize: '13px', fontWeight: 700,
                  cursor: editMut.isPending ? 'not-allowed' : 'pointer', opacity: editMut.isPending ? 0.6 : 1 }}>
                {editMut.isPending ? '⏳ Saving...' : editForm.mode === 'LIVE' ? '🔴 Go Live' : '✅ Save Changes'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
