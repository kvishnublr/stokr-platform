import { useState, useMemo, Fragment } from 'react';
import { useQuery } from '@tanstack/react-query';
import client from '../api/client';

const TABS = [
  { id: 'ratio', label: 'Ratio Butterfly', icon: '🦋', desc: 'Near-zero cost, 1:15 reward ratio', gradient: 'from-violet-500 via-purple-500 to-fuchsia-500', lightBg: 'from-violet-50 to-purple-50', text: 'violet' },
  { id: 'bwb', label: 'Broken Wing', icon: '🔥', desc: 'Credit entry, zero risk one side', gradient: 'from-amber-500 via-orange-500 to-red-400', lightBg: 'from-amber-50 to-orange-50', text: 'amber' },
  { id: 'skew', label: 'Skew Harvest', icon: '📐', desc: 'Sell overpriced puts, buy cheap calls', gradient: 'from-cyan-500 via-blue-500 to-indigo-500', lightBg: 'from-cyan-50 to-blue-50', text: 'cyan' },
  { id: 'theta', label: 'Theta Crush', icon: '⏱️', desc: 'Expiry day theta decay capture', gradient: 'from-emerald-500 via-teal-500 to-cyan-500', lightBg: 'from-emerald-50 to-teal-50', text: 'emerald' },
];

const SCAN_URLS = {
  ratio: '/smart-strategies/ratio-butterfly/scan',
  bwb: '/smart-strategies/broken-wing-butterfly/scan',
  skew: '/smart-strategies/skew-harvest/scan',
  theta: '/smart-strategies/theta-crush/scan',
};

const STRATEGY_INFO = {
  ratio: { structure: 'BUY 1 ATM | SELL 3 OTM | BUY 2 FAR OTM', detail: 'Risk ₹200-500 to make ₹5,000-15,000. Near-zero cost entry with asymmetric payoff. 20-25% hit rate = net profitable over time.', emptyMsg: 'No ratio butterfly setups right now. Requires near-zero cost with R:R >= 3:1. Try during market hours (9:15 AM - 3:30 PM).' },
  bwb: { structure: 'BUY Wing | SELL 2x Body | BUY Far Wing (Asymmetric)', detail: 'Credit entry with zero risk on one side. 60-65% win rate. Ideal for directional bias with protection.', emptyMsg: 'No broken wing butterfly setups found. Requires credit > 0 with valid asymmetric wing structure.' },
  skew: { structure: 'SELL OTM Put Spread (overpriced) + BUY OTM Call Spread (cheap)', detail: 'Exploits structural IV skew. Near-zero cost. Profits when market stays flat or moves up.', emptyMsg: 'No IV skew opportunities. Requires put-call IV difference >= 2%. More common in volatile/fearful markets.' },
  theta: { structure: 'SELL ATM Straddle + BUY Wings (Iron Butterfly)', detail: 'Capture 70% theta decay in last 90 minutes of expiry. 90-95% win rate in optimal window (post 1:30 PM).', emptyMsg: 'Theta crush shows only on expiry day or 1-2 days before. Most effective on expiry day after 1:30 PM.' },
};

export default function SmartStrategies() {
  const [activeTab, setActiveTab] = useState('ratio');
  const [underlying, setUnderlying] = useState('ALL');
  const tab = TABS.find(t => t.id === activeTab);

  return (
    <div className="min-h-screen bg-slate-50">
      {/* ──── HERO HEADER ──── */}
      <div className="relative overflow-hidden">
        <div className="absolute inset-0 bg-gradient-to-br from-slate-900 via-slate-800 to-slate-900" />
        <div className="absolute inset-0 opacity-30" style={{backgroundImage: 'radial-gradient(circle at 20% 50%, rgba(139,92,246,0.3) 0%, transparent 50%), radial-gradient(circle at 80% 20%, rgba(6,182,212,0.2) 0%, transparent 50%), radial-gradient(circle at 60% 80%, rgba(16,185,129,0.2) 0%, transparent 50%)'}} />
        <div className="relative max-w-7xl mx-auto px-4 sm:px-6 pt-6 pb-12">
          <div className="flex items-center justify-between">
            <div>
              <div className="flex items-center gap-3 mb-1">
                <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-violet-500 to-cyan-500 flex items-center justify-center shadow-lg shadow-violet-500/20">
                  <span className="text-white text-lg font-black">S</span>
                </div>
                <div>
                  <h1 className="text-2xl font-black text-white tracking-tight">Smart Strategies</h1>
                  <p className="text-slate-400 text-xs">Advanced option strategies with dynamic risk management</p>
                </div>
              </div>
            </div>
            <div className="flex items-center gap-1 bg-white/[0.07] backdrop-blur-sm rounded-xl p-1 border border-white/10">
              {['ALL', 'NIFTY', 'BANKNIFTY'].map(u => (
                <button key={u} onClick={() => setUnderlying(u)}
                  className={`px-5 py-2 rounded-lg text-xs font-bold transition-all duration-300 ${
                    underlying === u
                      ? 'bg-white text-slate-900 shadow-lg shadow-white/20'
                      : 'text-white/50 hover:text-white/80 hover:bg-white/[0.06]'
                  }`}>{u}</button>
              ))}
            </div>
          </div>
        </div>
      </div>

      {/* ──── TAB CARDS ──── */}
      <div className="max-w-7xl mx-auto px-4 sm:px-6 -mt-6 relative z-10">
        <div className="grid grid-cols-4 gap-4">
          {TABS.map(t => {
            const isActive = activeTab === t.id;
            return (
              <button key={t.id} onClick={() => setActiveTab(t.id)}
                className={`group relative rounded-2xl p-5 transition-all duration-300 overflow-hidden ${
                  isActive
                    ? 'shadow-xl scale-[1.02]'
                    : 'bg-white border border-slate-200 shadow-sm hover:shadow-lg hover:-translate-y-0.5'
                }`}>
                {isActive && <div className={`absolute inset-0 bg-gradient-to-br ${t.gradient}`} />}
                <div className="relative flex items-center gap-3">
                  <div className={`w-11 h-11 rounded-xl flex items-center justify-center text-2xl shrink-0 ${
                    isActive ? 'bg-white/20 shadow-inner' : 'bg-slate-50 border border-slate-100'
                  }`}>{t.icon}</div>
                  <div className="text-left min-w-0">
                    <div className={`text-sm font-black truncate ${isActive ? 'text-white' : 'text-slate-800'}`}>{t.label}</div>
                    <div className={`text-[10px] truncate mt-0.5 ${isActive ? 'text-white/60' : 'text-slate-400'}`}>{t.desc}</div>
                  </div>
                </div>
                {isActive && <div className="absolute bottom-0 left-1/2 -translate-x-1/2 w-8 h-1 bg-white/40 rounded-t-full" />}
              </button>
            );
          })}
        </div>
      </div>

      {/* ──── CONTENT ──── */}
      <div className="max-w-7xl mx-auto px-4 sm:px-6 py-6">
        <TabContent tab={activeTab} underlying={underlying} tabInfo={tab} />
      </div>
    </div>
  );
}

function useScan(tab, underlying) {
  return useQuery({
    queryKey: ['smart-scan', tab, underlying],
    queryFn: async () => {
      const res = await client.get(SCAN_URLS[tab], { params: { underlying } });
      return res.data;
    },
    refetchInterval: 30000,
    staleTime: 20000,
    retry: 2,
  });
}

/* ──── SHARED UI ──── */
function Stat({ label, value, sub, color = 'text-slate-800', icon }) {
  return (
    <div className="bg-white rounded-2xl border border-slate-100 p-4 shadow-sm hover:shadow-md transition-all group">
      <div className="flex items-center justify-between mb-2">
        <span className="text-[10px] text-slate-400 font-bold uppercase tracking-wider">{label}</span>
        {icon && <span className="text-sm opacity-60 group-hover:opacity-100 transition-opacity">{icon}</span>}
      </div>
      <div className={`text-xl font-black ${color} leading-none`}>{value}</div>
      {sub && <div className="text-[10px] text-slate-400 mt-1.5">{sub}</div>}
    </div>
  );
}

function TypeBadge({ type }) {
  const isCE = type === 'CE';
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded-md text-[10px] font-black border ${
      isCE ? 'bg-emerald-50 text-emerald-700 border-emerald-200' : 'bg-red-50 text-red-700 border-red-200'
    }`}>{type}</span>
  );
}

function RRBadge({ value }) {
  const v = Math.round(value);
  return (
    <span className={`inline-flex items-center px-2.5 py-1 rounded-lg text-[10px] font-black border ${
      v >= 10 ? 'bg-emerald-50 text-emerald-700 border-emerald-200' :
      v >= 5 ? 'bg-blue-50 text-blue-700 border-blue-200' :
      'bg-slate-50 text-slate-600 border-slate-200'
    }`}>1:{v}</span>
  );
}

function TableShell({ tab, headerContent, children, count }) {
  const info = STRATEGY_INFO[tab];
  const tabData = TABS.find(t => t.id === tab);
  return (
    <div className="bg-white rounded-2xl border border-slate-100 shadow-sm overflow-hidden">
      <div className={`px-5 py-4 bg-gradient-to-r ${tabData.lightBg} border-b border-slate-100`}>
        <div className="flex items-center justify-between">
          <div>
            <h3 className={`text-sm font-black text-${tabData.text}-800`}>{info.structure}</h3>
            <p className={`text-[11px] text-${tabData.text}-500/80 mt-0.5 max-w-2xl`}>{info.detail}</p>
          </div>
          {count > 0 && (
            <div className={`px-3 py-1.5 rounded-lg bg-${tabData.text}-100 text-${tabData.text}-700 text-xs font-black`}>
              {count} signal{count !== 1 ? 's' : ''}
            </div>
          )}
        </div>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead className="bg-slate-50/80 text-[10px] text-slate-400 uppercase tracking-wider font-bold border-b border-slate-100">
            {headerContent}
          </thead>
          <tbody className="divide-y divide-slate-50">
            {children}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function LoadingState() {
  return (
    <div className="flex flex-col items-center justify-center py-24">
      <div className="relative mb-5">
        <div className="w-14 h-14 rounded-2xl bg-gradient-to-br from-violet-500 to-cyan-500 animate-pulse" />
        <div className="absolute -bottom-1 -right-1 w-6 h-6 rounded-lg bg-white border-2 border-slate-100 flex items-center justify-center">
          <div className="w-3 h-3 border-2 border-slate-300 border-t-slate-700 rounded-full animate-spin" />
        </div>
      </div>
      <span className="text-slate-600 font-bold text-sm">Scanning Live Option Chain</span>
      <span className="text-slate-400 text-xs mt-1">Fetching real-time quotes from NSE...</span>
    </div>
  );
}

function ErrorState({ error }) {
  const msg = error?.response?.status === 401 ? 'Session expired. Please log in again.'
    : error?.response?.status === 500 ? 'Server error. Check if Zerodha market data feed is connected.'
    : error?.message || 'Something went wrong';
  return (
    <div className="flex flex-col items-center justify-center py-24">
      <div className="w-16 h-16 rounded-2xl bg-red-50 border border-red-100 flex items-center justify-center mb-4">
        <span className="text-3xl">⚠️</span>
      </div>
      <span className="text-red-600 font-bold text-sm">Scan Failed</span>
      <span className="text-slate-400 text-xs mt-1 max-w-sm text-center">{msg}</span>
    </div>
  );
}

function EmptyState({ tab }) {
  const info = STRATEGY_INFO[tab];
  const tabData = TABS.find(t => t.id === tab);
  return (
    <div className="space-y-5">
      {/* Strategy info card even when empty */}
      <div className={`bg-gradient-to-r ${tabData.lightBg} rounded-2xl border border-slate-100 p-6`}>
        <div className="flex items-start gap-4">
          <div className={`w-14 h-14 rounded-2xl bg-gradient-to-br ${tabData.gradient} flex items-center justify-center text-3xl shadow-lg shrink-0`}>
            {tabData.icon}
          </div>
          <div>
            <h3 className={`text-lg font-black text-${tabData.text}-800 mb-1`}>{tabData.label} Strategy</h3>
            <p className={`text-sm text-${tabData.text}-700/70 font-medium`}>{info.structure}</p>
            <p className={`text-xs text-${tabData.text}-600/50 mt-2 max-w-xl`}>{info.detail}</p>
          </div>
        </div>
      </div>
      <div className="bg-white rounded-2xl border border-slate-100 shadow-sm overflow-hidden">
        <div className="flex flex-col items-center justify-center py-16 px-8">
          <div className="w-20 h-20 rounded-3xl bg-slate-50 border border-slate-100 flex items-center justify-center mb-5">
            <span className="text-4xl opacity-40">🔍</span>
          </div>
          <span className="text-slate-500 font-bold text-base">No Opportunities Found</span>
          <span className="text-slate-400 text-xs mt-2 max-w-md text-center leading-relaxed">{info.emptyMsg}</span>
          <div className="mt-6 flex items-center gap-2 text-[10px] text-slate-400">
            <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse"></span>
            Auto-refreshing every 30 seconds
          </div>
        </div>
      </div>
    </div>
  );
}

/* ──── PAYOFF CHART ──── */
function computePayoff(legs, lotSize, spot) {
  if (!legs || legs.length === 0) return [];
  const strikes = legs.map(l => l.strike);
  const minS = Math.min(...strikes, spot);
  const maxS = Math.max(...strikes, spot);
  const range = maxS - minS || 100;
  const lo = minS - range * 0.5;
  const hi = maxS + range * 0.5;
  const step = (hi - lo) / 120;
  const points = [];
  for (let s = lo; s <= hi; s += step) {
    let pnl = 0;
    for (const leg of legs) {
      const { strike, optionType, side, qty = 1, price } = leg;
      let intrinsic = optionType === 'CE' ? Math.max(0, s - strike) : Math.max(0, strike - s);
      let legPnl = side === 'BUY' ? (intrinsic - price) * qty : (price - intrinsic) * qty;
      pnl += legPnl;
    }
    points.push({ s: Math.round(s), pnl: pnl * lotSize });
  }
  return points;
}

function PayoffChart({ legs, lotSize, spot, accentColor = '#7c3aed' }) {
  const points = useMemo(() => computePayoff(legs, lotSize, spot), [legs, lotSize, spot]);
  if (points.length === 0) return null;

  const W = 700, H = 260, PAD = { t: 30, r: 40, b: 40, l: 70 };
  const plotW = W - PAD.l - PAD.r, plotH = H - PAD.t - PAD.b;

  const minPnl = Math.min(...points.map(p => p.pnl));
  const maxPnl = Math.max(...points.map(p => p.pnl));
  const pnlRange = maxPnl - minPnl || 1;
  const minS = points[0].s, maxS = points[points.length - 1].s;
  const sRange = maxS - minS || 1;

  const x = s => PAD.l + ((s - minS) / sRange) * plotW;
  const y = pnl => PAD.t + plotH - ((pnl - minPnl) / pnlRange) * plotH;

  const zeroY = y(0);
  const spotX = x(spot);

  // Build path
  const pathD = points.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(p.s).toFixed(1)},${y(p.pnl).toFixed(1)}`).join(' ');

  // Fill areas (profit green, loss red)
  const profitPath = [];
  const lossPath = [];
  for (let i = 0; i < points.length - 1; i++) {
    const p1 = points[i], p2 = points[i + 1];
    const x1 = x(p1.s), y1 = y(p1.pnl), x2 = x(p2.s), y2 = y(p2.pnl);
    const zy = zeroY;
    if (p1.pnl >= 0 && p2.pnl >= 0) {
      profitPath.push(`M${x1},${zy} L${x1},${y1} L${x2},${y2} L${x2},${zy} Z`);
    } else if (p1.pnl < 0 && p2.pnl < 0) {
      lossPath.push(`M${x1},${zy} L${x1},${y1} L${x2},${y2} L${x2},${zy} Z`);
    } else {
      // Crossing zero — split
      const ratio = Math.abs(p1.pnl) / (Math.abs(p1.pnl) + Math.abs(p2.pnl));
      const cx = x1 + (x2 - x1) * ratio;
      if (p1.pnl >= 0) {
        profitPath.push(`M${x1},${zy} L${x1},${y1} L${cx},${zy} Z`);
        lossPath.push(`M${cx},${zy} L${x2},${y2} L${x2},${zy} Z`);
      } else {
        lossPath.push(`M${x1},${zy} L${x1},${y1} L${cx},${zy} Z`);
        profitPath.push(`M${cx},${zy} L${x2},${y2} L${x2},${zy} Z`);
      }
    }
  }

  // Y-axis labels
  const yTicks = 5;
  const yLabels = [];
  for (let i = 0; i <= yTicks; i++) {
    const val = minPnl + (pnlRange * i) / yTicks;
    yLabels.push({ val, yPos: y(val) });
  }

  // X-axis labels
  const xTicks = 6;
  const xLabels = [];
  for (let i = 0; i <= xTicks; i++) {
    const val = minS + (sRange * i) / xTicks;
    xLabels.push({ val: Math.round(val), xPos: x(val) });
  }

  // Key points — max profit, max loss, breakevens
  const maxProfitPt = points.reduce((a, b) => b.pnl > a.pnl ? b : a);
  const maxLossPt = points.reduce((a, b) => b.pnl < a.pnl ? b : a);

  return (
    <div className="bg-gradient-to-b from-slate-50 to-white rounded-xl border border-slate-100 p-4">
      <div className="flex items-center justify-between mb-3">
        <span className="text-xs font-black text-slate-600">Payoff at Expiry</span>
        <div className="flex items-center gap-4 text-[10px]">
          <span className="flex items-center gap-1"><span className="w-3 h-2 rounded-sm bg-emerald-400/40"></span> Profit Zone</span>
          <span className="flex items-center gap-1"><span className="w-3 h-2 rounded-sm bg-red-400/40"></span> Loss Zone</span>
          <span className="flex items-center gap-1"><span className="w-3 h-0.5 bg-slate-400"></span> Spot: {spot}</span>
        </div>
      </div>
      <svg viewBox={`0 0 ${W} ${H}`} className="w-full" style={{ maxHeight: 260 }}>
        {/* Grid */}
        {yLabels.map((yl, i) => (
          <g key={`y${i}`}>
            <line x1={PAD.l} y1={yl.yPos} x2={W - PAD.r} y2={yl.yPos} stroke="#e2e8f0" strokeWidth="0.5" />
            <text x={PAD.l - 8} y={yl.yPos + 3} textAnchor="end" fontSize="9" fill="#94a3b8" fontFamily="monospace">
              {yl.val >= 0 ? '' : '-'}₹{Math.abs(Math.round(yl.val)).toLocaleString()}
            </text>
          </g>
        ))}
        {xLabels.map((xl, i) => (
          <g key={`x${i}`}>
            <line x1={xl.xPos} y1={PAD.t} x2={xl.xPos} y2={H - PAD.b} stroke="#e2e8f0" strokeWidth="0.5" />
            <text x={xl.xPos} y={H - PAD.b + 14} textAnchor="middle" fontSize="9" fill="#94a3b8" fontFamily="monospace">{xl.val}</text>
          </g>
        ))}

        {/* Zero line */}
        {minPnl < 0 && maxPnl > 0 && (
          <line x1={PAD.l} y1={zeroY} x2={W - PAD.r} y2={zeroY} stroke="#475569" strokeWidth="1" strokeDasharray="4,3" />
        )}

        {/* Profit/Loss fills */}
        <path d={profitPath.join(' ')} fill="rgba(16,185,129,0.15)" />
        <path d={lossPath.join(' ')} fill="rgba(239,68,68,0.12)" />

        {/* Payoff line */}
        <path d={pathD} fill="none" stroke={accentColor} strokeWidth="2.5" strokeLinejoin="round" />

        {/* Spot vertical */}
        <line x1={spotX} y1={PAD.t} x2={spotX} y2={H - PAD.b} stroke="#64748b" strokeWidth="1" strokeDasharray="3,3" />
        <text x={spotX} y={PAD.t - 6} textAnchor="middle" fontSize="9" fill="#64748b" fontWeight="bold">SPOT</text>

        {/* Max profit dot */}
        <circle cx={x(maxProfitPt.s)} cy={y(maxProfitPt.pnl)} r="4" fill="#10b981" stroke="white" strokeWidth="2" />
        <text x={x(maxProfitPt.s)} y={y(maxProfitPt.pnl) - 10} textAnchor="middle" fontSize="9" fill="#10b981" fontWeight="bold">
          +₹{Math.round(maxProfitPt.pnl).toLocaleString()}
        </text>

        {/* Max loss dot */}
        {maxLossPt.pnl < 0 && (
          <>
            <circle cx={x(maxLossPt.s)} cy={y(maxLossPt.pnl)} r="4" fill="#ef4444" stroke="white" strokeWidth="2" />
            <text x={x(maxLossPt.s)} y={y(maxLossPt.pnl) + 16} textAnchor="middle" fontSize="9" fill="#ef4444" fontWeight="bold">
              -₹{Math.abs(Math.round(maxLossPt.pnl)).toLocaleString()}
            </text>
          </>
        )}

        {/* Axis labels */}
        <text x={W / 2} y={H - 4} textAnchor="middle" fontSize="10" fill="#94a3b8" fontWeight="bold">Underlying Price at Expiry</text>
        <text x={12} y={H / 2} textAnchor="middle" fontSize="10" fill="#94a3b8" fontWeight="bold" transform={`rotate(-90,12,${H / 2})`}>P&L (₹)</text>
      </svg>
    </div>
  );
}

function ExpandableRows({ opps, colSpan, renderRow, getLegs, getLotSize, getSpot, accentColor }) {
  const [expanded, setExpanded] = useState(null);
  return opps.map((o, i) => (
    <Fragment key={i}>
      <tr className={`cursor-pointer transition-colors ${expanded === i ? 'bg-slate-50' : ''}`}
        onClick={() => setExpanded(expanded === i ? null : i)}>
        {renderRow(o, i)}
        <td className="px-2 py-3 text-center">
          <span className={`inline-block transition-transform duration-200 text-slate-400 text-xs ${expanded === i ? 'rotate-180' : ''}`}>▼</span>
        </td>
      </tr>
      {expanded === i && (
        <tr>
          <td colSpan={colSpan + 1} className="px-4 py-3 bg-slate-50/50">
            <PayoffChart legs={getLegs(o)} lotSize={getLotSize(o)} spot={getSpot(o)} accentColor={accentColor} />
          </td>
        </tr>
      )}
    </Fragment>
  ));
}

function TabContent({ tab, underlying, tabInfo }) {
  const { data, isLoading, error } = useScan(tab, underlying);
  if (isLoading) return <LoadingState />;
  if (error) return <ErrorState error={error} />;
  const opps = data?.opportunities || [];
  if (opps.length === 0) return <EmptyState tab={tab} />;

  switch (tab) {
    case 'ratio': return <RatioContent opps={opps} />;
    case 'bwb': return <BWBContent opps={opps} />;
    case 'skew': return <SkewContent opps={opps} />;
    case 'theta': return <ThetaContent opps={opps} />;
    default: return null;
  }
}

/* ──────── RATIO BUTTERFLY ──────── */
function RatioContent({ opps }) {
  const b = opps[0];
  return (
    <div className="space-y-5">
      <div className="grid grid-cols-2 sm:grid-cols-5 gap-3">
        <Stat label="Best R:R" value={`1:${Math.round(b.riskReward)}`} color="text-emerald-600" icon="🎯" />
        <Stat label="Max Risk" value={`₹${Math.round(b.maxLoss).toLocaleString()}`} sub="Per lot" color="text-red-500" icon="🛡️" />
        <Stat label="Max Reward" value={`₹${Math.round(b.maxProfit).toLocaleString()}`} sub="Per lot" color="text-emerald-600" icon="💰" />
        <Stat label="Sweet Spot" value={b.sweetSpot} sub={b.underlying} icon="📍" />
        <Stat label="Signals" value={opps.length} sub={b.expiry} color="text-violet-600" icon="📡" />
      </div>
      <TableShell tab="ratio" count={opps.length} headerContent={
        <tr>
          <th className="px-4 py-3 text-left">Index</th>
          <th className="px-4 py-3 text-left">Type</th>
          <th className="px-4 py-3 text-left">Buy 1x</th>
          <th className="px-4 py-3 text-left">Sell 3x</th>
          <th className="px-4 py-3 text-left">Buy 2x</th>
          <th className="px-4 py-3 text-right">Net Cost</th>
          <th className="px-4 py-3 text-right">Max Risk</th>
          <th className="px-4 py-3 text-right">Max Reward</th>
          <th className="px-4 py-3 text-right">R:R</th>
          <th className="px-4 py-3 text-left">Expiry</th>
          <th className="px-2 py-3 text-center w-8"></th>
        </tr>
      }>
        <ExpandableRows opps={opps} colSpan={10} accentColor="#7c3aed"
          getLegs={o => o.legList} getLotSize={o => o.lotSize} getSpot={o => o.spotPrice}
          renderRow={(o, i) => (<>
            <td className="px-4 py-3 font-bold text-slate-800">{o.underlying}</td>
            <td className="px-4 py-3"><TypeBadge type={o.optionType} /></td>
            <td className="px-4 py-3 font-mono text-slate-600">{o.buyStrike} <span className="text-slate-300">@</span> ₹{o.buyPrice}</td>
            <td className="px-4 py-3 font-mono font-bold text-red-600">{o.sellStrike} <span className="text-red-300">@</span> ₹{o.sellPrice}</td>
            <td className="px-4 py-3 font-mono text-slate-600">{o.farBuyStrike} <span className="text-slate-300">@</span> ₹{o.farBuyPrice}</td>
            <td className={`px-4 py-3 text-right font-mono font-bold ${o.netCostRs <= 0 ? 'text-emerald-600' : 'text-amber-600'}`}>₹{Math.round(o.netCostRs).toLocaleString()}</td>
            <td className="px-4 py-3 text-right font-mono text-red-500">₹{Math.round(o.maxLoss).toLocaleString()}</td>
            <td className="px-4 py-3 text-right font-mono font-bold text-emerald-600">₹{Math.round(o.maxProfit).toLocaleString()}</td>
            <td className="px-4 py-3 text-right"><RRBadge value={o.riskReward} /></td>
            <td className="px-4 py-3 text-slate-400 text-[10px]">{o.expiry}</td>
          </>)}
        />
      </TableShell>
    </div>
  );
}

/* ──────── BROKEN WING BUTTERFLY ──────── */
function BWBContent({ opps }) {
  const b = opps[0];
  return (
    <div className="space-y-5">
      <div className="grid grid-cols-2 sm:grid-cols-5 gap-3">
        <Stat label="Best Credit" value={`₹${Math.round(b.creditRs).toLocaleString()}`} color="text-emerald-600" icon="💵" />
        <Stat label="Zero Risk Side" value={b.zeroRiskSide} color="text-blue-600" icon="🛡️" />
        <Stat label="Max Profit" value={`₹${Math.round(b.maxProfit).toLocaleString()}`} color="text-emerald-600" icon="💰" />
        <Stat label="Max Loss" value={`₹${Math.round(b.maxLoss).toLocaleString()}`} color="text-red-500" icon="⚠️" />
        <Stat label="Signals" value={opps.length} sub={b.expiry} color="text-amber-600" icon="📡" />
      </div>
      <TableShell tab="bwb" count={opps.length} headerContent={
        <tr>
          <th className="px-4 py-3 text-left">Index</th>
          <th className="px-4 py-3 text-left">Type</th>
          <th className="px-4 py-3 text-left">Near Wing</th>
          <th className="px-4 py-3 text-left">Body (2x Sell)</th>
          <th className="px-4 py-3 text-left">Far Wing</th>
          <th className="px-4 py-3 text-right">Credit</th>
          <th className="px-4 py-3 text-right">Max Profit</th>
          <th className="px-4 py-3 text-right">Max Loss</th>
          <th className="px-4 py-3 text-center">Zero Risk</th>
          <th className="px-4 py-3 text-left">Expiry</th>
          <th className="px-2 py-3 text-center w-8"></th>
        </tr>
      }>
        <ExpandableRows opps={opps} colSpan={10} accentColor="#f59e0b"
          getLegs={o => o.legList} getLotSize={o => o.lotSize} getSpot={o => o.spotPrice}
          renderRow={(o, i) => (<>
            <td className="px-4 py-3 font-bold text-slate-800">{o.underlying}</td>
            <td className="px-4 py-3"><TypeBadge type={o.optionType} /></td>
            <td className="px-4 py-3 font-mono text-slate-600">{o.nearWingStrike} <span className="text-slate-300">@</span> ₹{o.nearWingPrice}</td>
            <td className="px-4 py-3 font-mono font-bold text-red-600">{o.bodyStrike} <span className="text-red-300">@</span> ₹{o.bodyPrice}</td>
            <td className="px-4 py-3 font-mono text-slate-600">{o.farWingStrike} <span className="text-slate-300">@</span> ₹{o.farWingPrice}</td>
            <td className="px-4 py-3 text-right font-mono font-bold text-emerald-600">₹{Math.round(o.creditRs).toLocaleString()}</td>
            <td className="px-4 py-3 text-right font-mono text-emerald-600">₹{Math.round(o.maxProfit).toLocaleString()}</td>
            <td className="px-4 py-3 text-right font-mono text-red-500">₹{Math.round(o.maxLoss).toLocaleString()}</td>
            <td className="px-4 py-3 text-center">
              <span className={`px-2.5 py-1 rounded-lg text-[10px] font-black border ${
                o.zeroRiskSide === 'UPSIDE' ? 'bg-emerald-50 text-emerald-700 border-emerald-200' : 'bg-blue-50 text-blue-700 border-blue-200'
              }`}>{o.zeroRiskSide}</span>
            </td>
            <td className="px-4 py-3 text-slate-400 text-[10px]">{o.expiry}</td>
          </>)}
        />
      </TableShell>
    </div>
  );
}

/* ──────── SKEW HARVEST ──────── */
function SkewContent({ opps }) {
  const b = opps[0];
  return (
    <div className="space-y-5">
      <div className="grid grid-cols-2 sm:grid-cols-5 gap-3">
        <Stat label="IV Skew Edge" value={`${b.skewEdge}%`} color="text-cyan-600" icon="📊" />
        <Stat label="Net Cost" value={`₹${Math.round(b.netCostRs).toLocaleString()}`} sub={b.netCostRs > 0 ? 'Debit' : 'Credit'} color={b.netCostRs <= 0 ? 'text-emerald-600' : 'text-amber-600'} icon="💵" />
        <Stat label="If Flat" value={`₹${Math.round(b.scenarioFlat).toLocaleString()}`} color="text-emerald-600" icon="➡️" />
        <Stat label="If Up" value={`₹${Math.round(b.scenarioUp).toLocaleString()}`} color="text-emerald-600" icon="📈" />
        <Stat label="If Down" value={`₹${Math.round(b.scenarioDown).toLocaleString()}`} color="text-red-500" icon="📉" />
      </div>
      <TableShell tab="skew" count={opps.length} headerContent={
        <tr>
          <th className="px-4 py-3 text-left">Index</th>
          <th className="px-4 py-3 text-left">Put Spread (Sell)</th>
          <th className="px-4 py-3 text-left">Call Spread (Buy)</th>
          <th className="px-4 py-3 text-right">Put IV</th>
          <th className="px-4 py-3 text-right">Call IV</th>
          <th className="px-4 py-3 text-right">Skew</th>
          <th className="px-4 py-3 text-right">Net Cost</th>
          <th className="px-4 py-3 text-right">Flat P&L</th>
          <th className="px-4 py-3 text-right">Up P&L</th>
          <th className="px-4 py-3 text-right">Down P&L</th>
          <th className="px-2 py-3 text-center w-8"></th>
        </tr>
      }>
        <ExpandableRows opps={opps} colSpan={10} accentColor="#0891b2"
          getLegs={o => o.legList} getLotSize={o => o.lotSize} getSpot={o => o.spotPrice}
          renderRow={(o, i) => (<>
            <td className="px-4 py-3 font-bold text-slate-800">{o.underlying}</td>
            <td className="px-4 py-3 font-mono text-sm"><span className="text-red-500 font-bold">S</span>{o.putSellStrike} / <span className="text-emerald-500 font-bold">B</span>{o.putBuyStrike}</td>
            <td className="px-4 py-3 font-mono text-sm"><span className="text-emerald-500 font-bold">B</span>{o.callBuyStrike} / <span className="text-red-500 font-bold">S</span>{o.callSellStrike}</td>
            <td className="px-4 py-3 text-right font-mono text-slate-600">{o.putSellIV}%</td>
            <td className="px-4 py-3 text-right font-mono text-slate-600">{o.callBuyIV}%</td>
            <td className="px-4 py-3 text-right">
              <span className="px-2.5 py-1 rounded-lg bg-cyan-50 text-cyan-700 border border-cyan-200 text-[10px] font-black">{o.skewEdge}%</span>
            </td>
            <td className={`px-4 py-3 text-right font-mono font-bold ${o.netCostRs <= 0 ? 'text-emerald-600' : 'text-amber-600'}`}>₹{Math.round(o.netCostRs).toLocaleString()}</td>
            <td className="px-4 py-3 text-right font-mono text-emerald-600">₹{Math.round(o.scenarioFlat).toLocaleString()}</td>
            <td className="px-4 py-3 text-right font-mono font-bold text-emerald-600">₹{Math.round(o.scenarioUp).toLocaleString()}</td>
            <td className="px-4 py-3 text-right font-mono text-red-500">₹{Math.round(o.scenarioDown).toLocaleString()}</td>
          </>)}
        />
      </TableShell>
    </div>
  );
}

/* ──────── THETA CRUSH ──────── */
function ThetaContent({ opps }) {
  const b = opps[0];
  const isExpiryDay = b.dte === 0;

  return (
    <div className="space-y-5">
      <div className="grid grid-cols-2 sm:grid-cols-5 gap-3">
        <Stat label="Status" value={isExpiryDay ? 'EXPIRY DAY' : `${b.dte}d to Expiry`} color={isExpiryDay ? 'text-emerald-600' : 'text-amber-600'} icon={isExpiryDay ? '🟢' : '🟡'} />
        <Stat label="Window" value={b.window?.split(' ')[0] || '--'} sub={b.isOptimalWindow ? 'GO NOW!' : 'Wait for optimal'} color={b.isOptimalWindow ? 'text-emerald-600' : 'text-slate-500'} icon="⏰" />
        <Stat label="Net Credit" value={`₹${Math.round(b.netCreditRs ?? 0).toLocaleString()}`} color="text-emerald-600" icon="💵" />
        <Stat label="Expected P&L" value={`₹${Math.round(b.expectedProfitRs ?? b.dailyDecayRs ?? 0).toLocaleString()}`} color="text-emerald-600" icon="💰" />
        <Stat label="Win Rate" value={b.winRate || '--'} color="text-blue-600" icon="🎯" />
      </div>

      {!isExpiryDay && (
        <div className="bg-gradient-to-r from-amber-50 to-yellow-50 rounded-2xl border border-amber-200/60 p-5 flex items-start gap-4">
          <div className="w-12 h-12 rounded-xl bg-gradient-to-br from-amber-400 to-orange-400 flex items-center justify-center shrink-0 shadow-lg shadow-amber-400/20">
            <span className="text-xl">⏳</span>
          </div>
          <div>
            <div className="text-sm font-black text-amber-800">Not Expiry Day — Preview Mode</div>
            <div className="text-xs text-amber-600/80 mt-1 leading-relaxed">Theta Crush is most effective on expiry day after 1:30 PM when 70% of remaining time value decays in the last 90 minutes. Showing current straddle values as preview.</div>
          </div>
        </div>
      )}

      <TableShell tab="theta" count={opps.length} headerContent={
        <tr>
          <th className="px-4 py-3 text-left">Index</th>
          <th className="px-4 py-3 text-left">CE Strike</th>
          <th className="px-4 py-3 text-left">PE Strike</th>
          <th className="px-4 py-3 text-right">Straddle</th>
          <th className="px-4 py-3 text-right">Net Credit</th>
          <th className="px-4 py-3 text-right">Exp. P&L</th>
          <th className="px-4 py-3 text-right">Max Loss</th>
          <th className="px-4 py-3 text-center">Window</th>
          <th className="px-4 py-3 text-center">Win Rate</th>
          <th className="px-2 py-3 text-center w-8"></th>
        </tr>
      }>
        <ExpandableRows opps={opps} colSpan={9} accentColor="#10b981"
          getLegs={o => o.legList} getLotSize={o => o.lotSize} getSpot={o => o.spotPrice}
          renderRow={(o, i) => (<>
            <td className="px-4 py-3 font-bold text-slate-800">{o.underlying}</td>
            <td className="px-4 py-3 font-mono text-slate-600">{o.ceStrike}</td>
            <td className="px-4 py-3 font-mono text-slate-600">{o.peStrike}</td>
            <td className="px-4 py-3 text-right font-mono">₹{o.straddleCredit ?? o.straddleValue ?? '--'}</td>
            <td className="px-4 py-3 text-right font-mono font-bold text-emerald-600">₹{Math.round(o.netCreditRs ?? o.dailyDecayRs ?? 0).toLocaleString()}</td>
            <td className="px-4 py-3 text-right font-mono font-bold text-emerald-600">₹{Math.round(o.expectedProfitRs ?? o.dailyDecayRs ?? 0).toLocaleString()}</td>
            <td className="px-4 py-3 text-right font-mono text-red-500">{o.maxLoss ? `₹${Math.round(o.maxLoss).toLocaleString()}` : '--'}</td>
            <td className="px-4 py-3 text-center">
              {o.isOptimalWindow
                ? <span className="px-2.5 py-1 bg-emerald-100 text-emerald-700 rounded-lg text-[10px] font-black animate-pulse border border-emerald-200">OPTIMAL</span>
                : <span className="px-2.5 py-1 bg-slate-50 text-slate-500 rounded-lg text-[10px] font-bold border border-slate-200">{o.minutesToClose ? `${o.minutesToClose}m` : 'PREVIEW'}</span>
              }
            </td>
            <td className="px-4 py-3 text-center text-[11px] font-bold text-slate-600">{o.winRate || '--'}</td>
          </>)}
        />
      </TableShell>
    </div>
  );
}
