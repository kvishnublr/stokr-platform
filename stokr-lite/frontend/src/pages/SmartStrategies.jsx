import { useState, useMemo, useRef, useCallback, Fragment } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import client from '../api/client';

const TABS = [
  { id: 'ratio', label: 'Ratio Butterfly', shortLabel: 'Ratio', icon: '🦋', risk: 'LOW', desc: 'Near-zero cost, 1:15 reward', gradient: 'from-violet-500 via-purple-500 to-fuchsia-500', lightBg: 'from-violet-50 to-purple-50', text: 'violet', accent: '#7c3aed', ring: 'ring-violet-500/30' },
  { id: 'bwb', label: 'Broken Wing', shortLabel: 'BWB', icon: '🔥', risk: 'LOW', desc: 'Credit entry, zero one-side risk', gradient: 'from-amber-500 via-orange-500 to-red-400', lightBg: 'from-amber-50 to-orange-50', text: 'amber', accent: '#f59e0b', ring: 'ring-amber-500/30' },
  { id: 'skew', label: 'Skew Harvest', shortLabel: 'Skew', icon: '📊', risk: 'LOW', desc: 'Exploit IV skew mispricing', gradient: 'from-cyan-500 via-blue-500 to-indigo-500', lightBg: 'from-cyan-50 to-blue-50', text: 'cyan', accent: '#0891b2', ring: 'ring-cyan-500/30' },
  { id: 'theta', label: 'Theta Crush', shortLabel: 'Theta', icon: '⏰', risk: 'LOW', desc: 'Expiry day theta capture', gradient: 'from-emerald-500 via-teal-500 to-cyan-500', lightBg: 'from-emerald-50 to-teal-50', text: 'emerald', accent: '#10b981', ring: 'ring-emerald-500/30' },
  { id: 'box', label: 'Box Spread', shortLabel: 'Box', icon: '📦', risk: 'ZERO', desc: 'Risk-free arbitrage profit', gradient: 'from-rose-500 via-pink-500 to-fuchsia-500', lightBg: 'from-rose-50 to-pink-50', text: 'rose', accent: '#e11d48', ring: 'ring-rose-500/30' },
  { id: 'jade', label: 'Jade Lizard', shortLabel: 'Jade', icon: '🦎', risk: 'LOW', desc: 'Zero upside risk, 70%+ win', gradient: 'from-lime-500 via-green-500 to-emerald-500', lightBg: 'from-lime-50 to-green-50', text: 'green', accent: '#16a34a', ring: 'ring-green-500/30' },
  { id: 'calendar', label: 'Calendar Edge', shortLabel: 'Calendar', icon: '📅', risk: 'LOW', desc: 'Time decay differential', gradient: 'from-sky-500 via-blue-500 to-indigo-500', lightBg: 'from-sky-50 to-blue-50', text: 'sky', accent: '#0284c7', ring: 'ring-sky-500/30' },
];

const SCAN_URLS = {
  ratio: '/smart-strategies/ratio-butterfly/scan',
  bwb: '/smart-strategies/broken-wing-butterfly/scan',
  skew: '/smart-strategies/skew-harvest/scan',
  theta: '/smart-strategies/theta-crush/scan',
  box: '/smart-strategies/box-spread/scan',
  jade: '/smart-strategies/jade-lizard/scan',
  calendar: '/smart-strategies/calendar-spread/scan',
};

const STRATEGY_INFO = {
  ratio: { structure: 'BUY 1 ATM | SELL 3 OTM | BUY 2 FAR OTM', detail: 'Risk ₹200-500 to make ₹5,000-15,000. Near-zero cost entry with asymmetric payoff. 20-25% hit rate = net profitable over time.', emptyMsg: 'No ratio butterfly setups right now. Requires near-zero cost with R:R >= 3:1. Try during market hours (9:15 AM - 3:30 PM).' },
  bwb: { structure: 'BUY Wing | SELL 2x Body | BUY Far Wing (Asymmetric)', detail: 'Credit entry with zero risk on one side. 60-65% win rate. Ideal for directional bias with protection.', emptyMsg: 'No broken wing butterfly setups found. Requires credit > 0 with valid asymmetric wing structure.' },
  skew: { structure: 'SELL OTM Put Spread (overpriced) + BUY OTM Call Spread (cheap)', detail: 'Exploits structural IV skew. Near-zero cost. Profits when market stays flat or moves up.', emptyMsg: 'No IV skew opportunities. Requires put-call IV difference >= 2%. More common in volatile/fearful markets.' },
  theta: { structure: 'SELL ATM Straddle + BUY Wings (Iron Butterfly)', detail: 'Capture 70% theta decay in last 90 minutes of expiry. 90-95% win rate in optimal window (post 1:30 PM).', emptyMsg: 'Theta crush shows only on expiry day or 1-2 days before. Most effective on expiry day after 1:30 PM.' },
  box: { structure: 'Bull Call Spread + Bear Put Spread (Same Strikes)', detail: 'Zero-risk arbitrage. Box value at expiry = strike width (guaranteed). Profit when market misprices the box below theoretical value after transaction costs.', emptyMsg: 'No box spread arbitrage found. Requires market mispricing where box cost < theoretical value minus transaction costs. Very rare in efficient markets.' },
  jade: { structure: 'SELL OTM Put + SELL OTM Call + BUY Further OTM Call', detail: 'Short put + bear call spread. Zero upside risk when credit >= call spread width. 70-80% estimated win rate with defined risk.', emptyMsg: 'No jade lizard setups found. Requires credit > 40% of call spread width. Best in moderate IV environments with slight bullish bias.' },
  calendar: { structure: 'SELL Near-Expiry + BUY Far-Expiry (Same Strike, Same Type)', detail: 'Exploits faster time decay of near-term options. Profits from theta differential and IV term structure. Low risk, defined max loss.', emptyMsg: 'No calendar spread edge found. Requires meaningful theta differential between near and far expiry. Best when near-term IV > far-term IV.' },
};

// Theoretical payoff legs for empty state diagrams (representative example strikes)
const THEORETICAL_LEGS = {
  ratio: (atm) => [
    { strike: atm, optionType: 'CE', side: 'BUY', qty: 1, price: 200 },
    { strike: atm + 200, optionType: 'CE', side: 'SELL', qty: 3, price: 80 },
    { strike: atm + 400, optionType: 'CE', side: 'BUY', qty: 2, price: 20 },
  ],
  bwb: (atm) => [
    { strike: atm + 100, optionType: 'PE', side: 'BUY', qty: 1, price: 150 },
    { strike: atm - 100, optionType: 'PE', side: 'SELL', qty: 2, price: 100 },
    { strike: atm - 400, optionType: 'PE', side: 'BUY', qty: 1, price: 30 },
  ],
  skew: (atm) => [
    { strike: atm - 300, optionType: 'PE', side: 'SELL', qty: 1, price: 60 },
    { strike: atm - 500, optionType: 'PE', side: 'BUY', qty: 1, price: 25 },
    { strike: atm + 300, optionType: 'CE', side: 'BUY', qty: 1, price: 40 },
    { strike: atm + 500, optionType: 'CE', side: 'SELL', qty: 1, price: 15 },
  ],
  theta: (atm) => [
    { strike: atm, optionType: 'CE', side: 'SELL', qty: 1, price: 120 },
    { strike: atm, optionType: 'PE', side: 'SELL', qty: 1, price: 120 },
    { strike: atm + 300, optionType: 'CE', side: 'BUY', qty: 1, price: 30 },
    { strike: atm - 300, optionType: 'PE', side: 'BUY', qty: 1, price: 30 },
  ],
  box: (atm) => [
    { strike: atm, optionType: 'CE', side: 'BUY', qty: 1, price: 180 },
    { strike: atm + 200, optionType: 'CE', side: 'SELL', qty: 1, price: 90 },
    { strike: atm, optionType: 'PE', side: 'SELL', qty: 1, price: 80 },
    { strike: atm + 200, optionType: 'PE', side: 'BUY', qty: 1, price: 110 },
  ],
  jade: (atm) => [
    { strike: atm - 200, optionType: 'PE', side: 'SELL', qty: 1, price: 50 },
    { strike: atm + 200, optionType: 'CE', side: 'SELL', qty: 1, price: 45 },
    { strike: atm + 400, optionType: 'CE', side: 'BUY', qty: 1, price: 15 },
  ],
  calendar: (atm) => [
    { strike: atm, optionType: 'CE', side: 'SELL', qty: 1, price: 80 },
    { strike: atm, optionType: 'CE', side: 'BUY', qty: 1, price: 140 },
  ],
};

export default function SmartStrategies() {
  const [activeTab, setActiveTab] = useState('ratio');
  const [underlying, setUnderlying] = useState('ALL');
  const [entryModal, setEntryModal] = useState(null);
  const tab = TABS.find(t => t.id === activeTab);

  return (
    <div className="min-h-screen bg-[#f8fafc]">
      {/* ──── HEADER ──── */}
      <div className="relative overflow-hidden">
        <div className="absolute inset-0 bg-gradient-to-br from-[#0f172a] via-[#1e293b] to-[#0f172a]" />
        <div className="absolute inset-0" style={{backgroundImage: 'radial-gradient(ellipse 80% 60% at 20% 120%, rgba(139,92,246,0.15) 0%, transparent 60%), radial-gradient(ellipse 60% 50% at 85% -10%, rgba(6,182,212,0.12) 0%, transparent 60%)'}} />
        <div className="relative max-w-[1400px] mx-auto px-6 pt-5 pb-5">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="w-9 h-9 rounded-lg bg-gradient-to-br from-violet-500 to-cyan-400 flex items-center justify-center shadow-lg shadow-violet-500/25">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
              </div>
              <div>
                <h1 className="text-lg font-black text-white tracking-tight leading-none">Smart Strategies</h1>
                <p className="text-[11px] text-slate-500 mt-0.5 font-medium">7 advanced strategies with real-time scanning</p>
              </div>
            </div>
            <div className="flex items-center gap-1.5 bg-white/[0.06] backdrop-blur-sm rounded-lg p-1 border border-white/[0.08]">
              {['ALL', 'NIFTY', 'BANKNIFTY'].map(u => (
                <button key={u} onClick={() => setUnderlying(u)}
                  className={`px-4 py-1.5 rounded-md text-[11px] font-bold tracking-wide transition-all duration-200 ${
                    underlying === u
                      ? 'bg-white text-slate-900 shadow-md'
                      : 'text-slate-400 hover:text-white/80 hover:bg-white/[0.05]'
                  }`}>{u}</button>
              ))}
            </div>
          </div>
        </div>
      </div>

      {/* ──── STRATEGY NAV ──── */}
      <div className="sticky top-0 z-30 border-b border-slate-200/50" style={{background: 'linear-gradient(180deg, rgba(255,255,255,0.95) 0%, rgba(248,250,252,0.98) 100%)', backdropFilter: 'blur(20px) saturate(180%)'}}>
        <div className="max-w-[1400px] mx-auto px-6 py-3">
          <div className="grid grid-cols-7 gap-2">
            {TABS.map(t => {
              const isActive = activeTab === t.id;
              return (
                <button key={t.id} onClick={() => setActiveTab(t.id)}
                  className={`group relative rounded-xl px-3 py-3 transition-all duration-300 text-center ${
                    isActive
                      ? 'shadow-lg scale-[1.03]'
                      : 'bg-white border border-slate-100 shadow-[0_1px_2px_rgba(0,0,0,0.03)] hover:shadow-md hover:border-slate-200 hover:-translate-y-0.5'
                  }`}>
                  {isActive && <div className={`absolute inset-0 rounded-xl bg-gradient-to-br ${t.gradient}`} />}
                  <div className="relative flex flex-col items-center gap-1.5">
                    <span className={`text-xl leading-none ${isActive ? 'drop-shadow-sm' : 'grayscale-[30%] group-hover:grayscale-0 transition-all'}`}>{t.icon}</span>
                    <div className={`text-[11px] font-bold leading-tight ${isActive ? 'text-white' : 'text-slate-700'}`}>{t.label}</div>
                    <div className={`text-[9px] leading-tight ${isActive ? 'text-white/50' : 'text-slate-400'}`}>{t.desc}</div>
                    {t.risk === 'ZERO' && !isActive && (
                      <span className="mt-0.5 px-1.5 py-0.5 rounded text-[7px] font-black bg-emerald-50 text-emerald-600 border border-emerald-100 uppercase tracking-wider">Zero Risk</span>
                    )}
                    {t.risk === 'ZERO' && isActive && (
                      <span className="mt-0.5 px-1.5 py-0.5 rounded text-[7px] font-black bg-white/20 text-white uppercase tracking-wider">Zero Risk</span>
                    )}
                  </div>
                </button>
              );
            })}
          </div>
        </div>
      </div>

      {/* ──── ACTIVE POSITIONS ──── */}
      <div className="max-w-[1400px] mx-auto px-6 pt-5">
        <ActivePositionsPanel />
      </div>

      {/* ──── CONTENT ──── */}
      <div className="max-w-[1400px] mx-auto px-6 py-5">
        <TabContent tab={activeTab} underlying={underlying} tabInfo={tab} key={activeTab} onEnter={setEntryModal} />
      </div>

      {/* ──── ENTRY MODAL ──── */}
      {entryModal && <EnterTradeModal opp={entryModal} onClose={() => setEntryModal(null)} />}
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
    <div className="relative bg-white rounded-xl border border-slate-100/80 p-4 shadow-[0_1px_3px_rgba(0,0,0,0.04)] hover:shadow-[0_4px_12px_rgba(0,0,0,0.06)] transition-all duration-200 group overflow-hidden">
      <div className="absolute top-0 right-0 w-16 h-16 opacity-[0.04] text-4xl flex items-center justify-center pointer-events-none select-none">{icon}</div>
      <span className="text-[10px] text-slate-400 font-semibold uppercase tracking-widest">{label}</span>
      <div className={`text-xl font-black ${color} leading-none mt-1.5`}>{value}</div>
      {sub && <div className="text-[10px] text-slate-400 mt-1">{sub}</div>}
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
    <div className="bg-white rounded-xl border border-slate-200/60 shadow-[0_1px_3px_rgba(0,0,0,0.04)] overflow-hidden">
      <div className="px-5 py-3.5 border-b border-slate-100 bg-slate-50/40">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className={`w-8 h-8 rounded-lg bg-gradient-to-br ${tabData.gradient} flex items-center justify-center text-sm shadow-sm`}>{tabData.icon}</div>
            <div>
              <h3 className="text-[12px] font-bold text-slate-700">{info.structure}</h3>
              <p className="text-[10px] text-slate-400 mt-0.5 max-w-xl">{info.detail}</p>
            </div>
          </div>
          {count > 0 && (
            <div className="flex items-center gap-1.5 px-3 py-1 rounded-full bg-slate-100 text-slate-600 text-[11px] font-bold">
              <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse"></span>
              {count} signal{count !== 1 ? 's' : ''}
            </div>
          )}
        </div>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead className="bg-slate-50/60 text-[10px] text-slate-400 uppercase tracking-wider font-semibold border-b border-slate-100">
            {headerContent}
          </thead>
          <tbody className="divide-y divide-slate-50/80">
            {children}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function LoadingState() {
  return (
    <div className="flex flex-col items-center justify-center py-20">
      <div className="relative mb-6">
        <div className="w-12 h-12 rounded-xl bg-gradient-to-br from-violet-500 to-cyan-400 animate-pulse shadow-lg shadow-violet-500/20" />
        <div className="absolute -bottom-1 -right-1 w-5 h-5 rounded-md bg-white border border-slate-200 flex items-center justify-center shadow-sm">
          <div className="w-2.5 h-2.5 border-[2px] border-slate-200 border-t-slate-600 rounded-full animate-spin" />
        </div>
      </div>
      <span className="text-slate-700 font-bold text-sm">Scanning Live Chain</span>
      <span className="text-slate-400 text-[11px] mt-1">Real-time quotes from NSE</span>
    </div>
  );
}

function ErrorState({ error }) {
  const status = error?.response?.status;
  const is502 = status === 502 || status === 503;
  const msg = status === 401 ? 'Zerodha token expired. Reconnect via the Brokers page to resume live scanning.'
    : is502 ? 'Backend is restarting after a deploy. Please wait 30-60 seconds and it will auto-refresh.'
    : status === 500 ? 'Server error. Check if Zerodha market data feed is connected.'
    : error?.message || 'Something went wrong';
  return (
    <div className="flex flex-col items-center justify-center py-16">
      <div className={`w-14 h-14 rounded-xl flex items-center justify-center mb-4 ${
        is502 ? 'bg-amber-50 border border-amber-100' : 'bg-red-50 border border-red-100'
      }`}>
        <span className="text-2xl">{is502 ? '🔄' : '⚠️'}</span>
      </div>
      <span className={`font-bold text-sm ${is502 ? 'text-amber-600' : 'text-red-500'}`}>{is502 ? 'Restarting...' : 'Scan Unavailable'}</span>
      <span className="text-slate-400 text-[11px] mt-1.5 max-w-md text-center leading-relaxed">{msg}</span>
    </div>
  );
}

function EmptyState({ tab, marketOpen, lastScannedAt }) {
  const info = STRATEGY_INFO[tab];
  const tabData = TABS.find(t => t.id === tab);
  const theoreticalAtm = 24500;
  const theoreticalLegs = THEORETICAL_LEGS[tab]?.(theoreticalAtm) || [];

  const isWeekend = new Date().getDay() === 0 || new Date().getDay() === 6;
  const isMarketClosed = marketOpen === false;
  const emptyTitle = isWeekend ? 'Weekend — Reconnect to See LTP Data'
    : isMarketClosed ? 'Market Closed' : 'No Opportunities Right Now';
  const emptySubtext = isWeekend
    ? 'Scanners work on weekends using last traded prices (LTP). If you see no data, reconnect Zerodha on the Brokers page to refresh the API token.'
    : isMarketClosed ? 'NSE hours: 9:15 AM — 3:30 PM IST. If token is expired, reconnect on Brokers page.'
    : info.emptyMsg;

  return (
    <div className="space-y-4">
      {/* Strategy overview */}
      <div className="bg-white rounded-xl border border-slate-200/60 shadow-[0_1px_3px_rgba(0,0,0,0.04)] p-5">
        <div className="flex items-start gap-4">
          <div className={`w-11 h-11 rounded-lg bg-gradient-to-br ${tabData.gradient} flex items-center justify-center text-xl shadow-sm shrink-0`}>
            {tabData.icon}
          </div>
          <div className="min-w-0">
            <h3 className="text-sm font-bold text-slate-800">{tabData.label}</h3>
            <p className="text-[11px] text-slate-500 font-medium mt-0.5">{info.structure}</p>
            <p className="text-[11px] text-slate-400 mt-1.5 max-w-xl leading-relaxed">{info.detail}</p>
          </div>
        </div>
      </div>

      {/* Theoretical payoff chart */}
      {theoreticalLegs.length > 0 && (
        <div className="bg-white rounded-xl border border-slate-200/60 shadow-[0_1px_3px_rgba(0,0,0,0.04)] overflow-hidden">
          <div className="px-5 py-2.5 border-b border-slate-100 flex items-center justify-between">
            <span className="text-[11px] font-semibold text-slate-500">Theoretical Payoff</span>
            <span className="text-[9px] text-slate-400 bg-slate-50 px-2 py-0.5 rounded font-medium">Example — not a live signal</span>
          </div>
          <div className="p-4">
            <PayoffChart legs={theoreticalLegs} lotSize={75} spot={theoreticalAtm} accentColor={tabData.accent || '#7c3aed'} />
          </div>
        </div>
      )}

      {/* Empty message */}
      <div className="bg-white rounded-xl border border-slate-200/60 shadow-[0_1px_3px_rgba(0,0,0,0.04)]">
        <div className="flex flex-col items-center justify-center py-10 px-8">
          <div className={`w-12 h-12 rounded-xl flex items-center justify-center mb-3 ${
            isMarketClosed ? 'bg-slate-800' : 'bg-slate-50 border border-slate-100'
          }`}>
            <span className="text-2xl">{isMarketClosed ? '🌙' : '🔍'}</span>
          </div>
          <span className="font-bold text-sm text-slate-600">{emptyTitle}</span>
          <span className="text-slate-400 text-[11px] mt-1.5 max-w-sm text-center leading-relaxed">{emptySubtext}</span>
          <div className="mt-4 flex items-center gap-3 text-[10px] text-slate-400">
            {lastScannedAt && <span>Last scan: <span className="font-mono text-slate-500">{lastScannedAt}</span></span>}
            <span className="flex items-center gap-1">
              <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse"></span>
              Auto-refresh 30s
            </span>
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
  const svgRef = useRef(null);
  const [hover, setHover] = useState(null);

  const handleMouseMove = useCallback((e) => {
    const svg = svgRef.current;
    if (!svg || points.length === 0) return;
    const rect = svg.getBoundingClientRect();
    const svgX = ((e.clientX - rect.left) / rect.width) * 700;
    const PAD_L = 70, PAD_R = 40;
    const plotW = 700 - PAD_L - PAD_R;
    if (svgX < PAD_L || svgX > 700 - PAD_R) { setHover(null); return; }
    const ratio = (svgX - PAD_L) / plotW;
    const idx = Math.min(Math.max(0, Math.round(ratio * (points.length - 1))), points.length - 1);
    setHover({ idx, svgX });
  }, [points]);

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

  const pathD = points.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(p.s).toFixed(1)},${y(p.pnl).toFixed(1)}`).join(' ');

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

  const yTicks = 5;
  const yLabels = [];
  for (let i = 0; i <= yTicks; i++) {
    const val = minPnl + (pnlRange * i) / yTicks;
    yLabels.push({ val, yPos: y(val) });
  }
  const xTicks = 6;
  const xLabels = [];
  for (let i = 0; i <= xTicks; i++) {
    const val = minS + (sRange * i) / xTicks;
    xLabels.push({ val: Math.round(val), xPos: x(val) });
  }

  const maxProfitPt = points.reduce((a, b) => b.pnl > a.pnl ? b : a);
  const maxLossPt = points.reduce((a, b) => b.pnl < a.pnl ? b : a);

  const hoverPt = hover ? points[hover.idx] : null;

  return (
    <div className="bg-gradient-to-b from-slate-50 to-white rounded-xl border border-slate-100 p-4">
      <div className="flex items-center justify-between mb-3">
        <span className="text-xs font-black text-slate-600">Payoff at Expiry</span>
        <div className="flex items-center gap-4 text-[10px]">
          <span className="flex items-center gap-1"><span className="w-3 h-2 rounded-sm bg-emerald-400/40"></span> Profit Zone</span>
          <span className="flex items-center gap-1"><span className="w-3 h-2 rounded-sm bg-red-400/40"></span> Loss Zone</span>
          <span className="flex items-center gap-1"><span className="w-3 h-0.5 bg-slate-400"></span> Spot: {spot}</span>
          {hoverPt && (
            <span className={`font-mono font-black ${hoverPt.pnl >= 0 ? 'text-emerald-600' : 'text-red-500'}`}>
              {hoverPt.s.toLocaleString()} | {hoverPt.pnl >= 0 ? '+' : ''}₹{Math.round(hoverPt.pnl).toLocaleString()}
            </span>
          )}
        </div>
      </div>
      <svg ref={svgRef} viewBox={`0 0 ${W} ${H}`} className="w-full cursor-crosshair" style={{ maxHeight: 260 }}
        onMouseMove={handleMouseMove} onMouseLeave={() => setHover(null)}>
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

        {minPnl < 0 && maxPnl > 0 && (
          <line x1={PAD.l} y1={zeroY} x2={W - PAD.r} y2={zeroY} stroke="#475569" strokeWidth="1" strokeDasharray="4,3" />
        )}

        <path d={profitPath.join(' ')} fill="rgba(16,185,129,0.15)" />
        <path d={lossPath.join(' ')} fill="rgba(239,68,68,0.12)" />
        <path d={pathD} fill="none" stroke={accentColor} strokeWidth="2.5" strokeLinejoin="round" />

        <line x1={spotX} y1={PAD.t} x2={spotX} y2={H - PAD.b} stroke="#64748b" strokeWidth="1" strokeDasharray="3,3" />
        <text x={spotX} y={PAD.t - 6} textAnchor="middle" fontSize="9" fill="#64748b" fontWeight="bold">SPOT</text>

        <circle cx={x(maxProfitPt.s)} cy={y(maxProfitPt.pnl)} r="4" fill="#10b981" stroke="white" strokeWidth="2" />
        <text x={x(maxProfitPt.s)} y={y(maxProfitPt.pnl) - 10} textAnchor="middle" fontSize="9" fill="#10b981" fontWeight="bold">
          +₹{Math.round(maxProfitPt.pnl).toLocaleString()}
        </text>

        {maxLossPt.pnl < 0 && (
          <>
            <circle cx={x(maxLossPt.s)} cy={y(maxLossPt.pnl)} r="4" fill="#ef4444" stroke="white" strokeWidth="2" />
            <text x={x(maxLossPt.s)} y={y(maxLossPt.pnl) + 16} textAnchor="middle" fontSize="9" fill="#ef4444" fontWeight="bold">
              -₹{Math.abs(Math.round(maxLossPt.pnl)).toLocaleString()}
            </text>
          </>
        )}

        {/* Cursor crosshair + tooltip */}
        {hoverPt && (
          <>
            <line x1={x(hoverPt.s)} y1={PAD.t} x2={x(hoverPt.s)} y2={H - PAD.b} stroke={accentColor} strokeWidth="1" strokeDasharray="2,2" opacity="0.7" />
            <line x1={PAD.l} y1={y(hoverPt.pnl)} x2={W - PAD.r} y2={y(hoverPt.pnl)} stroke={accentColor} strokeWidth="1" strokeDasharray="2,2" opacity="0.4" />
            <circle cx={x(hoverPt.s)} cy={y(hoverPt.pnl)} r="5" fill={hoverPt.pnl >= 0 ? '#10b981' : '#ef4444'} stroke="white" strokeWidth="2" />
            <g transform={`translate(${Math.min(x(hoverPt.s) + 10, W - PAD.r - 120)}, ${Math.max(y(hoverPt.pnl) - 38, PAD.t)})`}>
              <rect x="0" y="0" width="115" height="32" rx="6" fill="#1e293b" opacity="0.92" />
              <text x="8" y="13" fontSize="9" fill="#94a3b8" fontFamily="monospace">Price: {hoverPt.s.toLocaleString()}</text>
              <text x="8" y="26" fontSize="10" fill={hoverPt.pnl >= 0 ? '#6ee7b7' : '#fca5a5'} fontWeight="bold" fontFamily="monospace">
                P&L: {hoverPt.pnl >= 0 ? '+' : ''}₹{Math.round(hoverPt.pnl).toLocaleString()}
              </text>
            </g>
          </>
        )}

        <text x={W / 2} y={H - 4} textAnchor="middle" fontSize="10" fill="#94a3b8" fontWeight="bold">Underlying Price at Expiry</text>
        <text x={12} y={H / 2} textAnchor="middle" fontSize="10" fill="#94a3b8" fontWeight="bold" transform={`rotate(-90,12,${H / 2})`}>P&L (₹)</text>
      </svg>
    </div>
  );
}

function ExpandableRows({ opps, colSpan, renderRow, getLegs, getLotSize, getSpot, accentColor, onEnter }) {
  const [expanded, setExpanded] = useState(null);
  return opps.map((o, i) => (
    <Fragment key={i}>
      <tr className={`cursor-pointer transition-colors ${expanded === i ? 'bg-slate-50' : ''}`}
        onClick={() => setExpanded(expanded === i ? null : i)}>
        {renderRow(o, i)}
        <td className="px-2 py-3 text-center" onClick={e => e.stopPropagation()}>
          <button onClick={() => onEnter?.(o)}
            className="px-2.5 py-1.5 rounded-lg bg-gradient-to-r from-violet-500 to-indigo-500 text-white text-[10px] font-bold shadow-sm hover:shadow-md hover:scale-105 transition-all"
            title="Enter this trade">
            Enter
          </button>
        </td>
        <td className="px-2 py-3 text-center">
          <span className={`inline-block transition-transform duration-200 text-slate-400 text-xs ${expanded === i ? 'rotate-180' : ''}`}>▼</span>
        </td>
      </tr>
      {expanded === i && (
        <tr>
          <td colSpan={colSpan + 2} className="px-4 py-3 bg-slate-50/50">
            <PayoffChart legs={getLegs(o)} lotSize={getLotSize(o)} spot={getSpot(o)} accentColor={accentColor} />
          </td>
        </tr>
      )}
    </Fragment>
  ));
}

function useSort(defaultField = null, defaultDir = 'desc') {
  const [sortField, setSortField] = useState(defaultField);
  const [sortDir, setSortDir] = useState(defaultDir);
  const toggle = useCallback((field) => {
    if (sortField === field) setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    else { setSortField(field); setSortDir('desc'); }
  }, [sortField]);
  const sorted = useCallback((arr) => {
    if (!sortField) return arr;
    return [...arr].sort((a, b) => {
      const va = a[sortField] ?? 0, vb = b[sortField] ?? 0;
      const cmp = typeof va === 'string' ? va.localeCompare(vb) : va - vb;
      return sortDir === 'asc' ? cmp : -cmp;
    });
  }, [sortField, sortDir]);
  return { sortField, sortDir, toggle, sorted };
}

function SortTh({ field, label, sort, className = '' }) {
  const active = sort.sortField === field;
  return (
    <th className={`px-4 py-3 cursor-pointer select-none hover:text-slate-600 transition-colors ${className}`}
      onClick={() => sort.toggle(field)}>
      <span className="inline-flex items-center gap-1">
        {label}
        <span className={`text-[8px] ${active ? 'text-violet-500' : 'text-slate-300'}`}>
          {active ? (sort.sortDir === 'asc' ? '▲' : '▼') : '⇅'}
        </span>
      </span>
    </th>
  );
}

function ScanStatusBar({ data }) {
  if (!data) return null;
  const { lastScannedAt, marketOpen, count } = data;
  return (
    <div className="flex items-center justify-between mb-4">
      <div className="flex items-center gap-2.5 text-[11px]">
        <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[10px] font-semibold ${
          marketOpen ? 'bg-emerald-50 text-emerald-600' : 'bg-slate-100 text-slate-500'
        }`}>
          <span className={`w-1.5 h-1.5 rounded-full ${marketOpen ? 'bg-emerald-400 animate-pulse' : 'bg-slate-300'}`}></span>
          {marketOpen ? 'Live' : 'Closed'}
        </span>
        {lastScannedAt && (
          <span className="text-slate-400 font-medium">
            Scanned <span className="font-mono text-slate-500">{lastScannedAt}</span>
          </span>
        )}
      </div>
      {count > 0 && (
        <span className="text-[10px] text-slate-400 font-medium">
          <span className="font-mono font-bold text-slate-600">{count}</span> found
        </span>
      )}
    </div>
  );
}

function TabContent({ tab, underlying, tabInfo, onEnter }) {
  const { data, isLoading, error } = useScan(tab, underlying);
  if (isLoading) return <LoadingState />;
  if (error) return <ErrorState error={error} />;
  const opps = data?.opportunities || [];
  if (opps.length === 0) return <EmptyState tab={tab} marketOpen={data?.marketOpen} lastScannedAt={data?.lastScannedAt} />;

  const content = (() => {
    switch (tab) {
      case 'ratio': return <RatioContent opps={opps} onEnter={onEnter} />;
      case 'bwb': return <BWBContent opps={opps} onEnter={onEnter} />;
      case 'skew': return <SkewContent opps={opps} onEnter={onEnter} />;
      case 'theta': return <ThetaContent opps={opps} onEnter={onEnter} />;
      case 'box': return <BoxContent opps={opps} onEnter={onEnter} />;
      case 'jade': return <JadeContent opps={opps} onEnter={onEnter} />;
      case 'calendar': return <CalendarContent opps={opps} onEnter={onEnter} />;
      default: return null;
    }
  })();

  return (
    <div>
      {data?.stale && (
        <div className="mb-4 flex items-center gap-3 px-4 py-3 rounded-xl bg-amber-50 border border-amber-200/60">
          <span className="text-lg">⚡</span>
          <div>
            <span className="text-[11px] font-bold text-amber-700">Showing cached data (LTP based)</span>
            <span className="text-[10px] text-amber-500 ml-2">{data.staleReason}</span>
          </div>
        </div>
      )}
      {data?.ltpBased && !data?.stale && (
        <div className="mb-4 flex items-center gap-3 px-4 py-3 rounded-xl bg-blue-50 border border-blue-200/60">
          <span className="text-lg">📊</span>
          <div>
            <span className="text-[11px] font-bold text-blue-700">LTP-based estimates</span>
            <span className="text-[10px] text-blue-500 ml-2">Market closed — prices based on Last Traded Price. Actual bid/ask may differ when market opens.</span>
          </div>
        </div>
      )}
      <ScanStatusBar data={data} />
      {content}
    </div>
  );
}

/* ──────── RATIO BUTTERFLY ──────── */
function RatioContent({ opps, onEnter }) {
  const sort = useSort('riskReward');
  const b = opps[0];
  const sorted = sort.sorted(opps);
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
          <SortTh field="underlying" label="Index" sort={sort} className="text-left" />
          <SortTh field="optionType" label="Type" sort={sort} className="text-left" />
          <th className="px-4 py-3 text-left">Buy 1x</th>
          <th className="px-4 py-3 text-left">Sell 3x</th>
          <th className="px-4 py-3 text-left">Buy 2x</th>
          <SortTh field="netCostRs" label="Net Cost" sort={sort} className="text-right" />
          <SortTh field="maxLoss" label="Max Risk" sort={sort} className="text-right" />
          <SortTh field="maxProfit" label="Max Reward" sort={sort} className="text-right" />
          <SortTh field="riskReward" label="R:R" sort={sort} className="text-right" />
          <th className="px-4 py-3 text-left">Expiry</th>
          <th className="px-2 py-3 text-center w-8"></th>
          <th className="px-2 py-3 text-center w-8"></th>
        </tr>
      }>
        <ExpandableRows opps={sorted} colSpan={10} accentColor="#7c3aed" onEnter={onEnter}
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
function BWBContent({ opps, onEnter }) {
  const [bwbType, setBwbType] = useState('PE');
  const putOpps = opps.filter(o => o.optionType === 'PE');
  const callOpps = opps.filter(o => o.optionType === 'CE');
  const filtered = bwbType === 'PE' ? putOpps : callOpps;
  const sort = useSort('creditRs');
  const sorted = sort.sorted(filtered);
  const b = filtered[0];
  return (
    <div className="space-y-5">
      <div className="flex items-center gap-2 mb-1">
        <button onClick={() => setBwbType('PE')}
          className={`flex items-center gap-2 px-4 py-2.5 rounded-xl text-xs font-bold transition-all ${
            bwbType === 'PE'
              ? 'bg-gradient-to-r from-emerald-500 to-teal-500 text-white shadow-lg shadow-emerald-200'
              : 'bg-slate-100 text-slate-500 hover:bg-slate-200'
          }`}>
          <span>🛡️</span> PUT BWB <span className="ml-1 px-1.5 py-0.5 rounded-md text-[10px] font-mono bg-white/20">{putOpps.length}</span>
          <span className={`text-[9px] font-medium ${bwbType === 'PE' ? 'text-emerald-100' : 'text-slate-400'}`}>Zero risk if market goes UP</span>
        </button>
        <button onClick={() => setBwbType('CE')}
          className={`flex items-center gap-2 px-4 py-2.5 rounded-xl text-xs font-bold transition-all ${
            bwbType === 'CE'
              ? 'bg-gradient-to-r from-blue-500 to-indigo-500 text-white shadow-lg shadow-blue-200'
              : 'bg-slate-100 text-slate-500 hover:bg-slate-200'
          }`}>
          <span>🛡️</span> CALL BWB <span className="ml-1 px-1.5 py-0.5 rounded-md text-[10px] font-mono bg-white/20">{callOpps.length}</span>
          <span className={`text-[9px] font-medium ${bwbType === 'CE' ? 'text-blue-100' : 'text-slate-400'}`}>Zero risk if market goes DOWN</span>
        </button>
      </div>
      {filtered.length === 0 ? (
        <div className="text-center py-10 text-slate-400 text-sm">No {bwbType === 'PE' ? 'PUT' : 'CALL'} BWB opportunities right now</div>
      ) : (<>
        <div className="grid grid-cols-2 sm:grid-cols-5 gap-3">
          <Stat label="Best Credit" value={`₹${Math.round(b.creditRs).toLocaleString()}`} color="text-emerald-600" icon="💵" />
          <Stat label="Zero Risk Side" value={b.zeroRiskSide} color={bwbType === 'PE' ? 'text-emerald-600' : 'text-blue-600'} icon="🛡️" />
          <Stat label="Max Profit" value={`₹${Math.round(b.maxProfit).toLocaleString()}`} color="text-emerald-600" icon="💰" />
          <Stat label="Max Loss" value={`₹${Math.round(b.maxLoss).toLocaleString()}`} color="text-red-500" icon="⚠️" />
          <Stat label="Signals" value={filtered.length} sub={b.expiry} color="text-amber-600" icon="📡" />
        </div>
        <div className={`px-4 py-2.5 rounded-xl text-[11px] font-medium border ${
          bwbType === 'PE'
            ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
            : 'bg-blue-50 text-blue-700 border-blue-200'
        }`}>
          {bwbType === 'PE'
            ? '📈 PUT BWB — Safe if market rallies. Risk only on the downside. Ideal when you expect market to stay flat or go up.'
            : '📉 CALL BWB — Safe if market drops. Risk only on the upside. Ideal when you expect market to stay flat or go down.'}
        </div>
        <TableShell tab="bwb" count={filtered.length} headerContent={
          <tr>
            <SortTh field="underlying" label="Index" sort={sort} className="text-left" />
            <th className="px-4 py-3 text-left">Near Wing</th>
            <th className="px-4 py-3 text-left">Body (2x Sell)</th>
            <th className="px-4 py-3 text-left">Far Wing</th>
            <SortTh field="creditRs" label="Credit" sort={sort} className="text-right" />
            <SortTh field="maxProfit" label="Max Profit" sort={sort} className="text-right" />
            <SortTh field="maxLoss" label="Max Loss" sort={sort} className="text-right" />
            <SortTh field="riskReward" label="R:R" sort={sort} className="text-right" />
            <th className="px-4 py-3 text-left">Expiry</th>
            <th className="px-2 py-3 text-center w-8"></th>
            <th className="px-2 py-3 text-center w-8"></th>
          </tr>
        }>
          <ExpandableRows opps={sorted} colSpan={10} accentColor={bwbType === 'PE' ? '#10b981' : '#3b82f6'} onEnter={onEnter}
            getLegs={o => o.legList} getLotSize={o => o.lotSize} getSpot={o => o.spotPrice}
            renderRow={(o, i) => (<>
              <td className="px-4 py-3 font-bold text-slate-800">{o.underlying}</td>
              <td className="px-4 py-3 font-mono text-slate-600">{o.nearWingStrike} <span className="text-slate-300">@</span> ₹{o.nearWingPrice}</td>
              <td className="px-4 py-3 font-mono font-bold text-red-600">{o.bodyStrike} <span className="text-red-300">@</span> ₹{o.bodyPrice}</td>
              <td className="px-4 py-3 font-mono text-slate-600">{o.farWingStrike} <span className="text-slate-300">@</span> ₹{o.farWingPrice}</td>
              <td className="px-4 py-3 text-right font-mono font-bold text-emerald-600">₹{Math.round(o.creditRs).toLocaleString()}</td>
              <td className="px-4 py-3 text-right font-mono text-emerald-600">₹{Math.round(o.maxProfit).toLocaleString()}</td>
              <td className="px-4 py-3 text-right font-mono text-red-500">₹{Math.round(o.maxLoss).toLocaleString()}</td>
              <td className="px-4 py-3 text-right font-mono font-bold text-slate-700">{o.riskReward?.toFixed(2)}</td>
              <td className="px-4 py-3 text-slate-400 text-[10px]">{o.expiry}</td>
            </>)}
          />
        </TableShell>
      </>)}
    </div>
  );
}

/* ──────── SKEW HARVEST ──────── */
function SkewContent({ opps, onEnter }) {
  const sort = useSort('skewEdge');
  const b = opps[0];
  const sorted = sort.sorted(opps);
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
          <SortTh field="underlying" label="Index" sort={sort} className="text-left" />
          <th className="px-4 py-3 text-left">Put Spread (Sell)</th>
          <th className="px-4 py-3 text-left">Call Spread (Buy)</th>
          <SortTh field="putSellIV" label="Put IV" sort={sort} className="text-right" />
          <SortTh field="callBuyIV" label="Call IV" sort={sort} className="text-right" />
          <SortTh field="skewEdge" label="Skew" sort={sort} className="text-right" />
          <SortTh field="netCostRs" label="Net Cost" sort={sort} className="text-right" />
          <SortTh field="scenarioFlat" label="Flat P&L" sort={sort} className="text-right" />
          <SortTh field="scenarioUp" label="Up P&L" sort={sort} className="text-right" />
          <SortTh field="scenarioDown" label="Down P&L" sort={sort} className="text-right" />
          <th className="px-2 py-3 text-center w-8"></th>
          <th className="px-2 py-3 text-center w-8"></th>
        </tr>
      }>
        <ExpandableRows opps={sorted} colSpan={10} accentColor="#0891b2" onEnter={onEnter}
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
function ThetaContent({ opps, onEnter }) {
  const sort = useSort('thetaDecayExpected');
  const b = opps[0];
  const isExpiryDay = b.dte === 0;
  const sorted = sort.sorted(opps);

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
          <SortTh field="underlying" label="Index" sort={sort} className="text-left" />
          <SortTh field="ceStrike" label="CE Strike" sort={sort} className="text-left" />
          <SortTh field="peStrike" label="PE Strike" sort={sort} className="text-left" />
          <SortTh field="straddleCredit" label="Straddle" sort={sort} className="text-right" />
          <SortTh field="netCreditRs" label="Net Credit" sort={sort} className="text-right" />
          <SortTh field="expectedProfitRs" label="Exp. P&L" sort={sort} className="text-right" />
          <SortTh field="maxLoss" label="Max Loss" sort={sort} className="text-right" />
          <th className="px-4 py-3 text-center">Window</th>
          <th className="px-4 py-3 text-center">Win Rate</th>
          <th className="px-2 py-3 text-center w-8"></th>
          <th className="px-2 py-3 text-center w-8"></th>
        </tr>
      }>
        <ExpandableRows opps={sorted} colSpan={9} accentColor="#10b981" onEnter={onEnter}
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

/* ──────── BOX SPREAD ARBITRAGE ──────── */
function BoxContent({ opps, onEnter }) {
  const sort = useSort('netEdgeRs');
  const b = opps[0];
  const sorted = sort.sorted(opps);
  return (
    <div className="space-y-5">
      <div className="grid grid-cols-2 sm:grid-cols-5 gap-3">
        <Stat label="Best Edge" value={`₹${Math.round(b.netEdgeRs).toLocaleString()}`} color="text-emerald-600" icon="💎" />
        <Stat label="Return" value={`${b.returnPct}%`} color="text-emerald-600" icon="📈" />
        <Stat label="Annualized" value={`${b.annualizedReturn}%`} color="text-blue-600" icon="🚀" />
        <Stat label="Risk Level" value={b.riskLevel} color="text-emerald-600" icon="🛡️" />
        <Stat label="Signals" value={opps.length} sub={b.expiry} color="text-rose-600" icon="📡" />
      </div>
      <TableShell tab="box" count={opps.length} headerContent={
        <tr>
          <SortTh field="underlying" label="Index" sort={sort} className="text-left" />
          <SortTh field="boxType" label="Type" sort={sort} className="text-left" />
          <th className="px-4 py-3 text-left">Strikes</th>
          <SortTh field="theoreticalValue" label="Theo Value" sort={sort} className="text-right" />
          <SortTh field="boxCost" label="Box Cost" sort={sort} className="text-right" />
          <SortTh field="netEdgeRs" label="Edge ₹" sort={sort} className="text-right" />
          <SortTh field="txnCostRs" label="Txn Cost" sort={sort} className="text-right" />
          <SortTh field="returnPct" label="Return %" sort={sort} className="text-right" />
          <SortTh field="annualizedReturn" label="Annual %" sort={sort} className="text-right" />
          <th className="px-4 py-3 text-left">Expiry</th>
          <th className="px-2 py-3 text-center w-8"></th>
          <th className="px-2 py-3 text-center w-8"></th>
        </tr>
      }>
        <ExpandableRows opps={sorted} colSpan={10} accentColor="#e11d48" onEnter={onEnter}
          getLegs={o => o.legList} getLotSize={o => o.lotSize} getSpot={o => o.spotPrice}
          renderRow={(o, i) => (<>
            <td className="px-4 py-3 font-bold text-slate-800">{o.underlying}</td>
            <td className="px-4 py-3">
              <span className={`px-2.5 py-1 rounded-lg text-[10px] font-black border ${
                o.boxType === 'LONG_BOX' ? 'bg-emerald-50 text-emerald-700 border-emerald-200' : 'bg-blue-50 text-blue-700 border-blue-200'
              }`}>{o.boxType === 'LONG_BOX' ? 'LONG' : 'SHORT'}</span>
            </td>
            <td className="px-4 py-3 font-mono text-slate-600">{o.lowerStrike} — {o.upperStrike}</td>
            <td className="px-4 py-3 text-right font-mono text-slate-600">₹{o.theoreticalValue}</td>
            <td className="px-4 py-3 text-right font-mono font-bold text-slate-800">₹{Math.round(Math.abs(o.boxCost)).toLocaleString()}</td>
            <td className="px-4 py-3 text-right font-mono font-bold text-emerald-600">₹{Math.round(o.netEdgeRs).toLocaleString()}</td>
            <td className="px-4 py-3 text-right font-mono text-red-400">₹{Math.round(o.txnCostRs).toLocaleString()}</td>
            <td className="px-4 py-3 text-right font-mono font-bold text-emerald-600">{o.returnPct}%</td>
            <td className="px-4 py-3 text-right font-mono font-bold text-blue-600">{o.annualizedReturn}%</td>
            <td className="px-4 py-3 text-slate-400 text-[10px]">{o.expiry}</td>
          </>)}
        />
      </TableShell>
    </div>
  );
}

/* ──────── JADE LIZARD ──────── */
function JadeContent({ opps, onEnter }) {
  const sort = useSort('creditRs');
  const b = opps[0];
  const sorted = sort.sorted(opps);
  return (
    <div className="space-y-5">
      <div className="grid grid-cols-2 sm:grid-cols-5 gap-3">
        <Stat label="Best Credit" value={`₹${Math.round(b.creditRs).toLocaleString()}`} color="text-emerald-600" icon="💵" />
        <Stat label="Upside Risk" value={b.zeroUpsideRisk ? 'ZERO' : 'LIMITED'} color={b.zeroUpsideRisk ? 'text-emerald-600' : 'text-amber-600'} icon={b.zeroUpsideRisk ? '🛡️' : '⚠️'} />
        <Stat label="Win Rate" value={`${Math.round(b.estimatedWinRate)}%`} color="text-blue-600" icon="🎯" />
        <Stat label="Break Even" value={Math.round(b.breakEvenDown).toLocaleString()} sub="Downside" color="text-red-500" icon="📉" />
        <Stat label="Signals" value={opps.length} sub={b.expiry} color="text-green-600" icon="📡" />
      </div>
      <TableShell tab="jade" count={opps.length} headerContent={
        <tr>
          <SortTh field="underlying" label="Index" sort={sort} className="text-left" />
          <th className="px-4 py-3 text-left">Sell Put</th>
          <th className="px-4 py-3 text-left">Sell Call</th>
          <th className="px-4 py-3 text-left">Buy Call</th>
          <SortTh field="creditRs" label="Credit ₹" sort={sort} className="text-right" />
          <SortTh field="zeroUpsideRisk" label="Upside" sort={sort} className="text-center" />
          <SortTh field="estimatedWinRate" label="Win %" sort={sort} className="text-right" />
          <SortTh field="scenarioFlat" label="Flat P&L" sort={sort} className="text-right" />
          <SortTh field="scenarioUp" label="Up P&L" sort={sort} className="text-right" />
          <SortTh field="scenarioDown" label="Down P&L" sort={sort} className="text-right" />
          <th className="px-2 py-3 text-center w-8"></th>
          <th className="px-2 py-3 text-center w-8"></th>
        </tr>
      }>
        <ExpandableRows opps={sorted} colSpan={10} accentColor="#16a34a" onEnter={onEnter}
          getLegs={o => o.legList} getLotSize={o => o.lotSize} getSpot={o => o.spotPrice}
          renderRow={(o, i) => (<>
            <td className="px-4 py-3 font-bold text-slate-800">{o.underlying}</td>
            <td className="px-4 py-3 font-mono text-red-600 font-bold">{o.putSellStrike} <span className="text-red-300">@</span> ₹{o.putPrice}</td>
            <td className="px-4 py-3 font-mono text-red-600 font-bold">{o.callSellStrike} <span className="text-red-300">@</span> ₹{o.callSellPrice}</td>
            <td className="px-4 py-3 font-mono text-slate-600">{o.callBuyStrike} <span className="text-slate-300">@</span> ₹{o.callBuyPrice}</td>
            <td className="px-4 py-3 text-right font-mono font-bold text-emerald-600">₹{Math.round(o.creditRs).toLocaleString()}</td>
            <td className="px-4 py-3 text-center">
              <span className={`px-2.5 py-1 rounded-lg text-[10px] font-black border ${
                o.zeroUpsideRisk ? 'bg-emerald-50 text-emerald-700 border-emerald-200' : 'bg-amber-50 text-amber-700 border-amber-200'
              }`}>{o.zeroUpsideRisk ? 'ZERO RISK' : 'LIMITED'}</span>
            </td>
            <td className="px-4 py-3 text-right font-mono text-slate-600">{Math.round(o.estimatedWinRate)}%</td>
            <td className="px-4 py-3 text-right font-mono text-emerald-600">₹{Math.round(o.scenarioFlat).toLocaleString()}</td>
            <td className={`px-4 py-3 text-right font-mono font-bold ${o.scenarioUp >= 0 ? 'text-emerald-600' : 'text-red-500'}`}>
              {o.scenarioUp >= 0 ? '+' : ''}₹{Math.round(o.scenarioUp).toLocaleString()}
            </td>
            <td className="px-4 py-3 text-right font-mono text-red-500">₹{Math.round(o.scenarioDown).toLocaleString()}</td>
          </>)}
        />
      </TableShell>
    </div>
  );
}

/* ──────── CALENDAR SPREAD EDGE ──────── */
function CalendarContent({ opps, onEnter }) {
  const sort = useSort('ivEdge');
  const b = opps[0];
  const sorted = sort.sorted(opps);
  return (
    <div className="space-y-5">
      <div className="grid grid-cols-2 sm:grid-cols-5 gap-3">
        <Stat label="IV Edge" value={`${b.ivEdge}%`} color="text-sky-600" icon="📊" />
        <Stat label="Daily Theta" value={`₹${Math.round(b.dailyThetaEdgeRs).toLocaleString()}`} sub="Per lot/day" color="text-emerald-600" icon="⏰" />
        <Stat label="Expected P&L" value={`₹${Math.round(b.expectedProfitRs).toLocaleString()}`} color="text-emerald-600" icon="💰" />
        <Stat label="Max Loss" value={`₹${Math.round(b.maxLoss).toLocaleString()}`} sub="Debit paid" color="text-red-500" icon="🛡️" />
        <Stat label="Signals" value={opps.length} sub={`${b.nearExpiry} → ${b.farExpiry}`} color="text-sky-600" icon="📡" />
      </div>

      {b.ivBackwardation && (
        <div className="bg-gradient-to-r from-sky-50 to-blue-50 rounded-2xl border border-sky-200/60 p-5 flex items-start gap-4">
          <div className="w-12 h-12 rounded-xl bg-gradient-to-br from-sky-400 to-blue-500 flex items-center justify-center shrink-0 shadow-lg shadow-sky-400/20">
            <span className="text-xl">🔥</span>
          </div>
          <div>
            <div className="text-sm font-black text-sky-800">IV Backwardation Detected</div>
            <div className="text-xs text-sky-600/80 mt-1 leading-relaxed">Near-term IV is higher than far-term IV — this amplifies the calendar spread edge as near-term options are relatively overpriced. Sell the expensive near-term, buy the cheap far-term.</div>
          </div>
        </div>
      )}

      <TableShell tab="calendar" count={opps.length} headerContent={
        <tr>
          <SortTh field="underlying" label="Index" sort={sort} className="text-left" />
          <SortTh field="optionType" label="Type" sort={sort} className="text-left" />
          <SortTh field="strike" label="Strike" sort={sort} className="text-right" />
          <th className="px-4 py-3 text-left">Near Expiry</th>
          <th className="px-4 py-3 text-left">Far Expiry</th>
          <SortTh field="nearIV" label="Near IV" sort={sort} className="text-right" />
          <SortTh field="farIV" label="Far IV" sort={sort} className="text-right" />
          <SortTh field="ivEdge" label="IV Edge" sort={sort} className="text-right" />
          <SortTh field="dailyThetaEdgeRs" label="Daily θ ₹" sort={sort} className="text-right" />
          <SortTh field="expectedProfitRs" label="Exp. P&L" sort={sort} className="text-right" />
          <th className="px-2 py-3 text-center w-8"></th>
          <th className="px-2 py-3 text-center w-8"></th>
        </tr>
      }>
        <ExpandableRows opps={sorted} colSpan={10} accentColor="#0284c7" onEnter={onEnter}
          getLegs={o => o.legList} getLotSize={o => o.lotSize} getSpot={o => o.spotPrice}
          renderRow={(o, i) => (<>
            <td className="px-4 py-3 font-bold text-slate-800">{o.underlying}</td>
            <td className="px-4 py-3"><TypeBadge type={o.optionType} /></td>
            <td className="px-4 py-3 text-right font-mono font-bold text-slate-800">{o.strike}</td>
            <td className="px-4 py-3 text-slate-500 text-[11px]">
              <span className="font-mono text-red-500 font-bold">SELL</span> {o.nearExpiry} <span className="text-slate-300">@</span> ₹{o.nearPrice}
            </td>
            <td className="px-4 py-3 text-slate-500 text-[11px]">
              <span className="font-mono text-emerald-500 font-bold">BUY</span> {o.farExpiry} <span className="text-slate-300">@</span> ₹{o.farPrice}
            </td>
            <td className="px-4 py-3 text-right font-mono text-slate-600">{o.nearIV}%</td>
            <td className="px-4 py-3 text-right font-mono text-slate-600">{o.farIV}%</td>
            <td className="px-4 py-3 text-right">
              <span className={`px-2.5 py-1 rounded-lg text-[10px] font-black border ${
                o.ivEdge > 0 ? 'bg-sky-50 text-sky-700 border-sky-200' : 'bg-slate-50 text-slate-600 border-slate-200'
              }`}>{o.ivEdge > 0 ? '+' : ''}{o.ivEdge}%</span>
            </td>
            <td className="px-4 py-3 text-right font-mono font-bold text-emerald-600">₹{Math.round(o.dailyThetaEdgeRs).toLocaleString()}</td>
            <td className="px-4 py-3 text-right font-mono font-bold text-emerald-600">₹{Math.round(o.expectedProfitRs).toLocaleString()}</td>
          </>)}
        />
      </TableShell>
    </div>
  );
}

/* ──────── ACTIVE POSITIONS PANEL ──────── */
function ActivePositionsPanel() {
  const queryClient = useQueryClient();
  const { data: positions } = useQuery({
    queryKey: ['smart-positions'],
    queryFn: async () => { const r = await client.get('/smart-strategies/positions'); return r.data; },
    refetchInterval: 10000,
    staleTime: 5000,
  });

  const exitMutation = useMutation({
    mutationFn: (id) => client.post(`/smart-strategies/exit/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['smart-positions'] }),
  });

  if (!positions || positions.length === 0) return null;

  const STRAT_LABELS = {
    BROKEN_WING_BUTTERFLY: 'BWB', RATIO_BUTTERFLY: 'Ratio', SKEW_HARVEST: 'Skew',
    EXPIRY_THETA_CRUSH: 'Theta', BOX_SPREAD_ARB: 'Box', JADE_LIZARD: 'Jade', CALENDAR_SPREAD_EDGE: 'Calendar',
  };

  return (
    <div className="bg-white rounded-xl border border-slate-200/60 shadow-[0_1px_3px_rgba(0,0,0,0.04)] overflow-hidden mb-1">
      <div className="px-5 py-3 border-b border-slate-100 bg-gradient-to-r from-violet-50 to-indigo-50 flex items-center justify-between">
        <div className="flex items-center gap-2.5">
          <div className="w-7 h-7 rounded-lg bg-gradient-to-br from-violet-500 to-indigo-500 flex items-center justify-center text-sm shadow-sm">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5"><path d="M12 2v20M2 12h20"/></svg>
          </div>
          <span className="text-xs font-black text-slate-700">Active Positions</span>
          <span className="px-2 py-0.5 rounded-full bg-violet-100 text-violet-700 text-[10px] font-bold">{positions.length}</span>
        </div>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead className="bg-slate-50/60 text-[10px] text-slate-400 uppercase tracking-wider font-semibold border-b border-slate-100">
            <tr>
              <th className="px-4 py-2.5 text-left">Strategy</th>
              <th className="px-4 py-2.5 text-left">Index</th>
              <th className="px-4 py-2.5 text-left">Broker</th>
              <th className="px-4 py-2.5 text-center">Lots</th>
              <th className="px-4 py-2.5 text-right">P&L</th>
              <th className="px-4 py-2.5 text-center">SL%</th>
              <th className="px-4 py-2.5 text-center">Target%</th>
              <th className="px-4 py-2.5 text-center">Time Exit</th>
              <th className="px-4 py-2.5 text-left">Entered</th>
              <th className="px-4 py-2.5 text-center">Action</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-50/80">
            {positions.map(p => {
              const pnl = p.currentPnl ?? 0;
              const isPaper = !p.broker || p.broker === 'PAPER';
              return (
                <tr key={p.id} className="hover:bg-slate-50/50 transition-colors">
                  <td className="px-4 py-3">
                    <span className="px-2 py-1 rounded-md bg-violet-50 text-violet-700 text-[10px] font-black border border-violet-200">
                      {STRAT_LABELS[p.strategyType] || p.strategyType}
                    </span>
                  </td>
                  <td className="px-4 py-3 font-bold text-slate-800">{p.underlying}</td>
                  <td className="px-4 py-3">
                    <span className={`px-2 py-0.5 rounded text-[9px] font-bold ${isPaper ? 'bg-amber-50 text-amber-600 border border-amber-200' : 'bg-emerald-50 text-emerald-600 border border-emerald-200'}`}>
                      {isPaper ? 'PAPER' : p.broker}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-center font-mono font-bold text-slate-600">{p.lots || 1}</td>
                  <td className={`px-4 py-3 text-right font-mono font-bold ${pnl >= 0 ? 'text-emerald-600' : 'text-red-500'}`}>
                    {pnl >= 0 ? '+' : ''}₹{Math.round(pnl).toLocaleString()}
                  </td>
                  <td className="px-4 py-3 text-center font-mono text-slate-500">{p.slPct ? `${p.slPct}%` : '--'}</td>
                  <td className="px-4 py-3 text-center font-mono text-slate-500">{p.targetPct ? `${p.targetPct}%` : '--'}</td>
                  <td className="px-4 py-3 text-center font-mono text-slate-500">{p.timeExitMinutes ? `${p.timeExitMinutes}m` : '--'}</td>
                  <td className="px-4 py-3 text-slate-400 text-[10px]">{p.enteredAt ? new Date(p.enteredAt).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' }) : '--'}</td>
                  <td className="px-4 py-3 text-center">
                    <button onClick={() => { if (confirm('Exit this position?')) exitMutation.mutate(p.id); }}
                      disabled={exitMutation.isPending}
                      className="px-3 py-1.5 rounded-lg bg-gradient-to-r from-red-500 to-rose-500 text-white text-[10px] font-bold shadow-sm hover:shadow-md hover:scale-105 transition-all disabled:opacity-50">
                      {exitMutation.isPending ? '...' : 'Exit'}
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/* ──────── ENTER TRADE MODAL ──────── */
function EnterTradeModal({ opp, onClose }) {
  const queryClient = useQueryClient();
  const [lots, setLots] = useState(1);
  const [slPct, setSlPct] = useState(50);
  const [targetPct, setTargetPct] = useState(80);
  const [timeExit, setTimeExit] = useState(5);
  const [slEnabled, setSlEnabled] = useState(true);
  const [targetEnabled, setTargetEnabled] = useState(true);
  const [timeEnabled, setTimeEnabled] = useState(true);
  const [broker, setBroker] = useState('PAPER');
  const [result, setResult] = useState(null);

  const enterMutation = useMutation({
    mutationFn: (payload) => client.post('/smart-strategies/enter', payload),
    onSuccess: (res) => {
      setResult(res.data);
      queryClient.invalidateQueries({ queryKey: ['smart-positions'] });
    },
    onError: (err) => setResult({ status: 'ERROR', message: err?.response?.data?.message || err.message }),
  });

  const stratType = opp.strategyType || opp.strategy || 'UNKNOWN';
  const maxLoss = (opp.maxLoss || 0) * lots;
  const maxProfit = (opp.maxProfit || opp.netEdgeRs || 0) * lots;

  const handleSubmit = () => {
    enterMutation.mutate({
      strategyType: stratType,
      underlying: opp.underlying,
      expiry: opp.expiry,
      action: opp.action || stratType,
      lots,
      broker,
      slPct: slEnabled ? slPct : null,
      targetPct: targetEnabled ? targetPct : null,
      timeExitMinutes: timeEnabled ? timeExit : null,
      maxLoss: opp.maxLoss || 0,
      maxProfit: opp.maxProfit || opp.netEdgeRs || 0,
      legList: opp.legList,
    });
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm" onClick={onClose}>
      <div className="bg-white rounded-2xl shadow-2xl w-[520px] max-h-[90vh] overflow-y-auto" onClick={e => e.stopPropagation()}>
        {/* Header */}
        <div className="px-6 py-4 border-b border-slate-100 bg-gradient-to-r from-violet-50 to-indigo-50 rounded-t-2xl">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="w-9 h-9 rounded-lg bg-gradient-to-br from-violet-500 to-indigo-500 flex items-center justify-center shadow-lg">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5"><path d="M12 2v20M2 12h20"/></svg>
              </div>
              <div>
                <h3 className="text-sm font-black text-slate-800">Enter Trade</h3>
                <p className="text-[10px] text-slate-500">{stratType.replace(/_/g, ' ')} — {opp.underlying}</p>
              </div>
            </div>
            <button onClick={onClose} className="w-8 h-8 rounded-lg bg-slate-100 hover:bg-slate-200 flex items-center justify-center text-slate-400 hover:text-slate-600 transition-colors">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><path d="M18 6L6 18M6 6l12 12"/></svg>
            </button>
          </div>
        </div>

        {result ? (
          <div className="p-6">
            <div className={`p-5 rounded-xl border ${result.status === 'SUCCESS' ? 'bg-emerald-50 border-emerald-200' : 'bg-red-50 border-red-200'}`}>
              <div className={`text-sm font-black ${result.status === 'SUCCESS' ? 'text-emerald-700' : 'text-red-700'}`}>
                {result.status === 'SUCCESS' ? 'Trade Entered!' : 'Entry Failed'}
              </div>
              <div className={`text-xs mt-1 ${result.status === 'SUCCESS' ? 'text-emerald-600' : 'text-red-600'}`}>{result.message}</div>
              {result.positionId && <div className="text-[10px] text-slate-400 mt-2">Position #{result.positionId}</div>}
            </div>
            <button onClick={onClose} className="mt-4 w-full py-2.5 rounded-xl bg-slate-100 hover:bg-slate-200 text-sm font-bold text-slate-700 transition-colors">Close</button>
          </div>
        ) : (
          <div className="p-6 space-y-5">
            {/* Legs preview */}
            {opp.legList && (
              <div className="bg-slate-50 rounded-xl border border-slate-100 p-3">
                <div className="text-[10px] text-slate-400 font-semibold uppercase tracking-wider mb-2">Legs</div>
                <div className="space-y-1">
                  {opp.legList.map((leg, i) => (
                    <div key={i} className="flex items-center gap-2 text-[11px] font-mono">
                      <span className={`px-1.5 py-0.5 rounded text-[9px] font-black ${leg.side === 'BUY' ? 'bg-emerald-100 text-emerald-700' : 'bg-red-100 text-red-700'}`}>
                        {leg.side}
                      </span>
                      <span className="text-slate-600">{leg.qty || 1}x</span>
                      <span className="font-bold text-slate-800">{leg.strike} {leg.optionType}</span>
                      <span className="text-slate-400">@</span>
                      <span className="text-slate-600">₹{leg.price}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* P&L summary */}
            <div className="grid grid-cols-2 gap-3">
              <div className="p-3 rounded-xl bg-red-50 border border-red-100 text-center">
                <div className="text-[9px] text-red-400 font-semibold uppercase">Max Risk (per lot)</div>
                <div className="text-sm font-black text-red-600 mt-0.5">₹{Math.round(opp.maxLoss || 0).toLocaleString()}</div>
              </div>
              <div className="p-3 rounded-xl bg-emerald-50 border border-emerald-100 text-center">
                <div className="text-[9px] text-emerald-400 font-semibold uppercase">Max Reward (per lot)</div>
                <div className="text-sm font-black text-emerald-600 mt-0.5">₹{Math.round(opp.maxProfit || opp.netEdgeRs || 0).toLocaleString()}</div>
              </div>
            </div>

            {/* Controls */}
            <div className="space-y-3">
              {/* Broker + Lots */}
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="text-[10px] text-slate-400 font-semibold uppercase tracking-wider">Broker</label>
                  <select value={broker} onChange={e => setBroker(e.target.value)}
                    className="mt-1 w-full px-3 py-2 rounded-lg border border-slate-200 text-xs font-bold text-slate-700 bg-white focus:ring-2 focus:ring-violet-200 focus:border-violet-400 outline-none">
                    <option value="PAPER">PAPER (Simulated)</option>
                    <option value="ZERODHA">ZERODHA (Live)</option>
                    <option value="NAVIA">NAVIA (Live)</option>
                  </select>
                </div>
                <div>
                  <label className="text-[10px] text-slate-400 font-semibold uppercase tracking-wider">Lots</label>
                  <input type="number" min="1" max="50" value={lots} onChange={e => setLots(Math.max(1, +e.target.value))}
                    className="mt-1 w-full px-3 py-2 rounded-lg border border-slate-200 text-xs font-bold text-slate-700 bg-white focus:ring-2 focus:ring-violet-200 focus:border-violet-400 outline-none" />
                </div>
              </div>

              {/* Stop Loss */}
              <div className={`p-3 rounded-xl border transition-colors ${slEnabled ? 'bg-red-50/50 border-red-100' : 'bg-slate-50 border-slate-100'}`}>
                <div className="flex items-center justify-between">
                  <label className="flex items-center gap-2 cursor-pointer">
                    <input type="checkbox" checked={slEnabled} onChange={e => setSlEnabled(e.target.checked)}
                      className="w-3.5 h-3.5 rounded border-slate-300 text-red-500 focus:ring-red-200" />
                    <span className="text-[11px] font-bold text-slate-700">Stop Loss</span>
                  </label>
                  {slEnabled && <span className="text-[10px] font-mono text-red-500">Exit if loss hits ₹{Math.round(maxLoss * slPct / 100).toLocaleString()}</span>}
                </div>
                {slEnabled && (
                  <div className="mt-2 flex items-center gap-3">
                    <input type="range" min="10" max="100" step="5" value={slPct} onChange={e => setSlPct(+e.target.value)}
                      className="flex-1 h-1.5 bg-red-200 rounded-full accent-red-500" />
                    <span className="text-xs font-black text-red-600 w-12 text-right">{slPct}%</span>
                  </div>
                )}
              </div>

              {/* Target */}
              <div className={`p-3 rounded-xl border transition-colors ${targetEnabled ? 'bg-emerald-50/50 border-emerald-100' : 'bg-slate-50 border-slate-100'}`}>
                <div className="flex items-center justify-between">
                  <label className="flex items-center gap-2 cursor-pointer">
                    <input type="checkbox" checked={targetEnabled} onChange={e => setTargetEnabled(e.target.checked)}
                      className="w-3.5 h-3.5 rounded border-slate-300 text-emerald-500 focus:ring-emerald-200" />
                    <span className="text-[11px] font-bold text-slate-700">Auto Book Profit</span>
                  </label>
                  {targetEnabled && <span className="text-[10px] font-mono text-emerald-600">Exit if profit hits ₹{Math.round(maxProfit * targetPct / 100).toLocaleString()}</span>}
                </div>
                {targetEnabled && (
                  <div className="mt-2 flex items-center gap-3">
                    <input type="range" min="10" max="100" step="5" value={targetPct} onChange={e => setTargetPct(+e.target.value)}
                      className="flex-1 h-1.5 bg-emerald-200 rounded-full accent-emerald-500" />
                    <span className="text-xs font-black text-emerald-600 w-12 text-right">{targetPct}%</span>
                  </div>
                )}
              </div>

              {/* Time Exit */}
              <div className={`p-3 rounded-xl border transition-colors ${timeEnabled ? 'bg-blue-50/50 border-blue-100' : 'bg-slate-50 border-slate-100'}`}>
                <div className="flex items-center justify-between">
                  <label className="flex items-center gap-2 cursor-pointer">
                    <input type="checkbox" checked={timeEnabled} onChange={e => setTimeEnabled(e.target.checked)}
                      className="w-3.5 h-3.5 rounded border-slate-300 text-blue-500 focus:ring-blue-200" />
                    <span className="text-[11px] font-bold text-slate-700">Time-Based Exit</span>
                  </label>
                  {timeEnabled && <span className="text-[10px] font-mono text-blue-600">Exit {timeExit}min before 3:30 PM</span>}
                </div>
                {timeEnabled && (
                  <div className="mt-2 flex items-center gap-3">
                    <input type="range" min="1" max="30" step="1" value={timeExit} onChange={e => setTimeExit(+e.target.value)}
                      className="flex-1 h-1.5 bg-blue-200 rounded-full accent-blue-500" />
                    <span className="text-xs font-black text-blue-600 w-12 text-right">{timeExit}m</span>
                  </div>
                )}
              </div>
            </div>

            {/* Total risk summary */}
            <div className="p-3 rounded-xl bg-slate-800 text-white">
              <div className="flex items-center justify-between text-[11px]">
                <span className="text-slate-300">Total Risk ({lots} lot{lots > 1 ? 's' : ''})</span>
                <span className="font-mono font-black text-red-400">₹{Math.round(maxLoss).toLocaleString()}</span>
              </div>
              <div className="flex items-center justify-between text-[11px] mt-1">
                <span className="text-slate-300">Max Reward ({lots} lot{lots > 1 ? 's' : ''})</span>
                <span className="font-mono font-black text-emerald-400">₹{Math.round(maxProfit).toLocaleString()}</span>
              </div>
            </div>

            {/* Submit */}
            <button onClick={handleSubmit} disabled={enterMutation.isPending}
              className="w-full py-3 rounded-xl bg-gradient-to-r from-violet-500 to-indigo-500 text-white text-sm font-black shadow-lg shadow-violet-500/25 hover:shadow-xl hover:scale-[1.02] transition-all disabled:opacity-50 disabled:scale-100">
              {enterMutation.isPending ? 'Placing Orders...' : broker === 'PAPER' ? `Enter PAPER Trade (${lots} lot${lots > 1 ? 's' : ''})` : `Enter LIVE Trade via ${broker}`}
            </button>

            {broker !== 'PAPER' && (
              <div className="text-[10px] text-amber-600 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2 text-center font-medium">
                LIVE orders will be placed with your broker. Market must be open (9:15 AM - 3:30 PM IST).
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
