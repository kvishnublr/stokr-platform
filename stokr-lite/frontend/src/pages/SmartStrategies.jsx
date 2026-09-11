import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import client from '../api/client';

const TABS = [
  { id: 'ratio', label: 'Ratio Butterfly', icon: '🦋', accent: 'violet', desc: 'Near-zero cost, 1:15 reward' },
  { id: 'bwb', label: 'Broken Wing', icon: '🔥', accent: 'amber', desc: 'Credit entry, zero risk one side' },
  { id: 'skew', label: 'Skew Harvest', icon: '📐', accent: 'cyan', desc: 'Sell overpriced puts, buy cheap calls' },
  { id: 'theta', label: 'Theta Crush', icon: '⏱️', accent: 'emerald', desc: 'Expiry day decay capture' },
];

const SCAN_URLS = {
  ratio: '/smart-strategies/ratio-butterfly/scan',
  bwb: '/smart-strategies/broken-wing-butterfly/scan',
  skew: '/smart-strategies/skew-harvest/scan',
  theta: '/smart-strategies/theta-crush/scan',
};

const ACCENT_CLASSES = {
  violet: { tabActive: 'bg-gradient-to-r from-violet-600 to-purple-700 text-white border-transparent shadow-lg shadow-violet-500/25', headerBg: 'from-violet-50 to-purple-50', headerText: 'text-violet-800', headerSub: 'text-violet-500', rowHover: 'hover:bg-violet-50/50', rowTop: 'bg-violet-50/30' },
  amber: { tabActive: 'bg-gradient-to-r from-amber-500 to-orange-600 text-white border-transparent shadow-lg shadow-amber-500/25', headerBg: 'from-amber-50 to-orange-50', headerText: 'text-amber-800', headerSub: 'text-amber-500', rowHover: 'hover:bg-amber-50/50', rowTop: 'bg-amber-50/30' },
  cyan: { tabActive: 'bg-gradient-to-r from-cyan-600 to-blue-700 text-white border-transparent shadow-lg shadow-cyan-500/25', headerBg: 'from-cyan-50 to-blue-50', headerText: 'text-cyan-800', headerSub: 'text-cyan-500', rowHover: 'hover:bg-cyan-50/50', rowTop: 'bg-cyan-50/30' },
  emerald: { tabActive: 'bg-gradient-to-r from-emerald-600 to-teal-700 text-white border-transparent shadow-lg shadow-emerald-500/25', headerBg: 'from-emerald-50 to-teal-50', headerText: 'text-emerald-800', headerSub: 'text-emerald-500', rowHover: 'hover:bg-emerald-50/50', rowTop: 'bg-emerald-50/30' },
};

export default function SmartStrategies() {
  const [activeTab, setActiveTab] = useState('ratio');
  const [underlying, setUnderlying] = useState('ALL');
  const activeTabData = TABS.find(t => t.id === activeTab);

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 via-white to-slate-100">
      {/* Header */}
      <div className="bg-gradient-to-r from-slate-900 via-slate-800 to-slate-900 px-4 sm:px-6 py-5 pb-10">
        <div className="max-w-7xl mx-auto">
          <div className="flex items-center justify-between">
            <div>
              <h1 className="text-2xl font-black text-white tracking-tight flex items-center gap-2">
                <span className="bg-gradient-to-r from-violet-400 to-cyan-400 bg-clip-text text-transparent">Smart Strategies</span>
              </h1>
              <p className="text-slate-400 text-sm mt-0.5">Low-risk option strategies with dynamic risk management</p>
            </div>
            <div className="flex items-center gap-1.5 bg-white/5 rounded-xl p-1">
              {['ALL', 'NIFTY', 'BANKNIFTY'].map(u => (
                <button key={u} onClick={() => setUnderlying(u)}
                  className={`px-4 py-2 rounded-lg text-xs font-bold transition-all duration-200 ${
                    underlying === u
                      ? 'bg-white text-slate-900 shadow-md'
                      : 'text-white/60 hover:text-white hover:bg-white/10'
                  }`}>{u}</button>
              ))}
            </div>
          </div>
        </div>
      </div>

      {/* Tab Bar — overlapping header */}
      <div className="max-w-7xl mx-auto px-4 sm:px-6 -mt-6">
        <div className="grid grid-cols-4 gap-3">
          {TABS.map(tab => {
            const isActive = activeTab === tab.id;
            const cls = ACCENT_CLASSES[tab.accent];
            return (
              <button key={tab.id} onClick={() => setActiveTab(tab.id)}
                className={`rounded-xl p-4 transition-all duration-200 border-2 ${
                  isActive
                    ? `${cls.tabActive} scale-[1.03]`
                    : 'bg-white text-slate-600 border-slate-200/80 hover:border-slate-300 hover:shadow-md shadow-sm'
                }`}>
                <div className="flex items-center gap-3">
                  <span className="text-2xl">{tab.icon}</span>
                  <div className="text-left min-w-0">
                    <div className="text-sm font-black truncate">{tab.label}</div>
                    <div className={`text-[10px] truncate ${isActive ? 'text-white/70' : 'text-slate-400'}`}>{tab.desc}</div>
                  </div>
                </div>
              </button>
            );
          })}
        </div>
      </div>

      {/* Content */}
      <div className="max-w-7xl mx-auto px-4 sm:px-6 py-6">
        <TabContent tab={activeTab} underlying={underlying} accent={activeTabData.accent} />
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

function StatCard({ label, value, sub, color = 'text-slate-800' }) {
  return (
    <div className="bg-white rounded-xl border border-slate-200/80 p-4 shadow-sm hover:shadow-md transition-shadow">
      <div className="text-[10px] text-slate-400 font-bold uppercase tracking-wider mb-1">{label}</div>
      <div className={`text-xl font-black ${color} leading-tight`}>{value}</div>
      {sub && <div className="text-[10px] text-slate-400 mt-1">{sub}</div>}
    </div>
  );
}

function LoadingState() {
  return (
    <div className="flex flex-col items-center justify-center py-20">
      <div className="relative">
        <div className="animate-spin w-12 h-12 border-4 border-slate-200 border-t-slate-700 rounded-full"></div>
        <div className="absolute inset-0 flex items-center justify-center">
          <div className="w-4 h-4 bg-slate-700 rounded-full animate-pulse"></div>
        </div>
      </div>
      <span className="mt-4 text-slate-500 font-bold text-sm">Scanning live option chain...</span>
      <span className="mt-1 text-slate-400 text-xs">Fetching quotes from NSE</span>
    </div>
  );
}

function ErrorState({ error }) {
  return (
    <div className="flex flex-col items-center justify-center py-20">
      <div className="w-16 h-16 bg-red-50 rounded-2xl flex items-center justify-center mb-4">
        <span className="text-3xl">⚠️</span>
      </div>
      <span className="text-red-600 font-bold text-sm">Scan Failed</span>
      <span className="mt-1 text-slate-400 text-xs max-w-md text-center">
        {error?.response?.status === 401 ? 'Session expired — please log in again' :
         error?.response?.status === 500 ? 'Server error — check if market data feed is connected' :
         error?.message || 'Unknown error'}
      </span>
    </div>
  );
}

function EmptyState({ message }) {
  return (
    <div className="flex flex-col items-center justify-center py-16">
      <div className="w-16 h-16 bg-slate-50 rounded-2xl flex items-center justify-center mb-4">
        <span className="text-3xl">🔍</span>
      </div>
      <span className="text-slate-500 font-bold text-sm">No Opportunities Found</span>
      <span className="mt-1 text-slate-400 text-xs max-w-sm text-center">{message}</span>
    </div>
  );
}

function TabContent({ tab, underlying, accent }) {
  const { data, isLoading, error } = useScan(tab, underlying);

  if (isLoading) return <LoadingState />;
  if (error) return <ErrorState error={error} />;

  const opps = data?.opportunities || [];
  const cls = ACCENT_CLASSES[accent];

  switch (tab) {
    case 'ratio': return <RatioButterflyContent opps={opps} cls={cls} />;
    case 'bwb': return <BWBContent opps={opps} cls={cls} />;
    case 'skew': return <SkewContent opps={opps} cls={cls} />;
    case 'theta': return <ThetaContent opps={opps} cls={cls} />;
    default: return null;
  }
}

/* ──────── RATIO BUTTERFLY ──────── */
function RatioButterflyContent({ opps, cls }) {
  if (opps.length === 0) return <EmptyState message="No ratio butterfly setups right now. Need near-zero cost with R:R >= 3:1. Try during market hours." />;
  const best = opps[0];

  return (
    <div className="space-y-5">
      <div className="grid grid-cols-2 sm:grid-cols-5 gap-3">
        <StatCard label="Best R:R" value={`1:${Math.round(best.riskReward)}`} color="text-emerald-600" />
        <StatCard label="Max Risk" value={`₹${Math.round(best.maxLoss).toLocaleString()}`} sub="Per lot" color="text-red-500" />
        <StatCard label="Max Reward" value={`₹${Math.round(best.maxProfit).toLocaleString()}`} sub="Per lot" color="text-emerald-600" />
        <StatCard label="Sweet Spot" value={best.sweetSpot} sub={best.underlying} />
        <StatCard label="Signals" value={opps.length} sub={best.expiry} color="text-violet-600" />
      </div>

      <div className="bg-white rounded-2xl border border-slate-200/80 shadow-sm overflow-hidden">
        <div className={`px-5 py-4 bg-gradient-to-r ${cls.headerBg} border-b border-slate-200/60`}>
          <h3 className={`text-sm font-black ${cls.headerText}`}>BUY 1 ATM | SELL 3 OTM | BUY 2 FAR OTM</h3>
          <p className={`text-xs ${cls.headerSub} mt-0.5`}>Risk ₹200-500 to make ₹5,000-15,000. 20-25% hit rate = net profitable.</p>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-xs text-left">
            <thead className="bg-slate-50/80 text-slate-500 uppercase tracking-wider text-[10px] font-bold border-b">
              <tr>
                <th className="px-4 py-3">Index</th>
                <th className="px-4 py-3">Type</th>
                <th className="px-4 py-3">Buy 1x</th>
                <th className="px-4 py-3">Sell 3x</th>
                <th className="px-4 py-3">Buy 2x</th>
                <th className="px-4 py-3 text-right">Net Cost</th>
                <th className="px-4 py-3 text-right">Max Risk</th>
                <th className="px-4 py-3 text-right">Max Reward</th>
                <th className="px-4 py-3 text-right">R:R</th>
                <th className="px-4 py-3">Expiry</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100/80">
              {opps.map((o, i) => (
                <tr key={i} className={`${cls.rowHover} transition-colors ${i === 0 ? cls.rowTop : ''}`}>
                  <td className="px-4 py-3 font-bold text-slate-800">{o.underlying}</td>
                  <td className="px-4 py-3"><TypeBadge type={o.optionType} /></td>
                  <td className="px-4 py-3 font-mono text-slate-700">{o.buyStrike} <span className="text-slate-400">@</span> ₹{o.buyPrice}</td>
                  <td className="px-4 py-3 font-mono font-bold text-red-600">{o.sellStrike} <span className="text-red-400">@</span> ₹{o.sellPrice}</td>
                  <td className="px-4 py-3 font-mono text-slate-700">{o.farBuyStrike} <span className="text-slate-400">@</span> ₹{o.farBuyPrice}</td>
                  <td className={`px-4 py-3 text-right font-mono font-bold ${o.netCostRs <= 0 ? 'text-emerald-600' : 'text-amber-600'}`}>₹{Math.round(o.netCostRs).toLocaleString()}</td>
                  <td className="px-4 py-3 text-right font-mono text-red-500">₹{Math.round(o.maxLoss).toLocaleString()}</td>
                  <td className="px-4 py-3 text-right font-mono font-bold text-emerald-600">₹{Math.round(o.maxProfit).toLocaleString()}</td>
                  <td className="px-4 py-3 text-right"><RRBadge value={o.riskReward} /></td>
                  <td className="px-4 py-3 text-slate-400 text-[10px]">{o.expiry}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

/* ──────── BROKEN WING BUTTERFLY ──────── */
function BWBContent({ opps, cls }) {
  if (opps.length === 0) return <EmptyState message="No broken wing butterfly setups found. Need credit > 0 with valid wing structure." />;
  const best = opps[0];

  return (
    <div className="space-y-5">
      <div className="grid grid-cols-2 sm:grid-cols-5 gap-3">
        <StatCard label="Best Credit" value={`₹${Math.round(best.creditRs).toLocaleString()}`} color="text-emerald-600" />
        <StatCard label="Zero Risk Side" value={best.zeroRiskSide} color="text-blue-600" />
        <StatCard label="Max Profit" value={`₹${Math.round(best.maxProfit).toLocaleString()}`} color="text-emerald-600" />
        <StatCard label="Max Loss" value={`₹${Math.round(best.maxLoss).toLocaleString()}`} color="text-red-500" />
        <StatCard label="Signals" value={opps.length} sub={best.expiry} color="text-amber-600" />
      </div>

      <div className="bg-white rounded-2xl border border-slate-200/80 shadow-sm overflow-hidden">
        <div className={`px-5 py-4 bg-gradient-to-r ${cls.headerBg} border-b border-slate-200/60`}>
          <h3 className={`text-sm font-black ${cls.headerText}`}>BUY Wing | SELL 2x Body | BUY Far Wing (Asymmetric)</h3>
          <p className={`text-xs ${cls.headerSub} mt-0.5`}>Credit entry. Zero risk on one direction. 60-65% win rate.</p>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-xs text-left">
            <thead className="bg-slate-50/80 text-slate-500 uppercase tracking-wider text-[10px] font-bold border-b">
              <tr>
                <th className="px-4 py-3">Index</th>
                <th className="px-4 py-3">Type</th>
                <th className="px-4 py-3">Near Wing</th>
                <th className="px-4 py-3">Body (2x)</th>
                <th className="px-4 py-3">Far Wing</th>
                <th className="px-4 py-3 text-right">Credit</th>
                <th className="px-4 py-3 text-right">Max Profit</th>
                <th className="px-4 py-3 text-right">Max Loss</th>
                <th className="px-4 py-3 text-center">Zero Risk</th>
                <th className="px-4 py-3">Expiry</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100/80">
              {opps.map((o, i) => (
                <tr key={i} className={`${cls.rowHover} transition-colors ${i === 0 ? cls.rowTop : ''}`}>
                  <td className="px-4 py-3 font-bold text-slate-800">{o.underlying}</td>
                  <td className="px-4 py-3"><TypeBadge type={o.optionType} /></td>
                  <td className="px-4 py-3 font-mono text-slate-700">{o.nearWingStrike} <span className="text-slate-400">@</span> ₹{o.nearWingPrice}</td>
                  <td className="px-4 py-3 font-mono font-bold text-red-600">{o.bodyStrike} <span className="text-slate-400">@</span> ₹{o.bodyPrice}</td>
                  <td className="px-4 py-3 font-mono text-slate-700">{o.farWingStrike} <span className="text-slate-400">@</span> ₹{o.farWingPrice}</td>
                  <td className="px-4 py-3 text-right font-mono font-bold text-emerald-600">₹{Math.round(o.creditRs).toLocaleString()}</td>
                  <td className="px-4 py-3 text-right font-mono text-emerald-600">₹{Math.round(o.maxProfit).toLocaleString()}</td>
                  <td className="px-4 py-3 text-right font-mono text-red-500">₹{Math.round(o.maxLoss).toLocaleString()}</td>
                  <td className="px-4 py-3 text-center"><span className="px-2.5 py-1 bg-blue-50 text-blue-700 rounded-full text-[10px] font-black border border-blue-200">{o.zeroRiskSide}</span></td>
                  <td className="px-4 py-3 text-slate-400 text-[10px]">{o.expiry}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

/* ──────── SKEW HARVEST ──────── */
function SkewContent({ opps, cls }) {
  if (opps.length === 0) return <EmptyState message="No IV skew harvest opportunities. Need put-call IV difference >= 2%. More common in volatile markets." />;
  const best = opps[0];

  return (
    <div className="space-y-5">
      <div className="grid grid-cols-2 sm:grid-cols-5 gap-3">
        <StatCard label="IV Skew Edge" value={`${best.skewEdge}%`} color="text-cyan-600" />
        <StatCard label="Net Cost" value={`₹${Math.round(best.netCostRs).toLocaleString()}`} sub={best.netCostRs > 0 ? 'Debit' : 'Credit'} color={best.netCostRs <= 0 ? 'text-emerald-600' : 'text-amber-600'} />
        <StatCard label="If Flat" value={`₹${Math.round(best.scenarioFlat).toLocaleString()}`} color="text-emerald-600" />
        <StatCard label="If Up" value={`₹${Math.round(best.scenarioUp).toLocaleString()}`} color="text-emerald-600" />
        <StatCard label="If Down" value={`₹${Math.round(best.scenarioDown).toLocaleString()}`} color="text-red-500" />
      </div>

      <div className="bg-white rounded-2xl border border-slate-200/80 shadow-sm overflow-hidden">
        <div className={`px-5 py-4 bg-gradient-to-r ${cls.headerBg} border-b border-slate-200/60`}>
          <h3 className={`text-sm font-black ${cls.headerText}`}>SELL OTM Put Spread (overpriced) + BUY OTM Call Spread (cheap)</h3>
          <p className={`text-xs ${cls.headerSub} mt-0.5`}>Exploits structural IV skew. Near-zero cost. Win on flat + up, small loss only on crash.</p>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-xs text-left">
            <thead className="bg-slate-50/80 text-slate-500 uppercase tracking-wider text-[10px] font-bold border-b">
              <tr>
                <th className="px-4 py-3">Index</th>
                <th className="px-4 py-3">Put Spread</th>
                <th className="px-4 py-3">Call Spread</th>
                <th className="px-4 py-3 text-right">Put IV</th>
                <th className="px-4 py-3 text-right">Call IV</th>
                <th className="px-4 py-3 text-right">Skew</th>
                <th className="px-4 py-3 text-right">Net Cost</th>
                <th className="px-4 py-3 text-right">If Flat</th>
                <th className="px-4 py-3 text-right">If Up</th>
                <th className="px-4 py-3 text-right">If Down</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100/80">
              {opps.map((o, i) => (
                <tr key={i} className={`${cls.rowHover} transition-colors ${i === 0 ? cls.rowTop : ''}`}>
                  <td className="px-4 py-3 font-bold text-slate-800">{o.underlying}</td>
                  <td className="px-4 py-3 font-mono"><span className="text-red-500">S</span>{o.putSellStrike} / <span className="text-emerald-500">B</span>{o.putBuyStrike}</td>
                  <td className="px-4 py-3 font-mono"><span className="text-emerald-500">B</span>{o.callBuyStrike} / <span className="text-red-500">S</span>{o.callSellStrike}</td>
                  <td className="px-4 py-3 text-right font-mono">{o.putSellIV}%</td>
                  <td className="px-4 py-3 text-right font-mono">{o.callBuyIV}%</td>
                  <td className="px-4 py-3 text-right"><span className="px-2.5 py-1 bg-cyan-50 text-cyan-700 rounded-full text-[10px] font-black border border-cyan-200">{o.skewEdge}%</span></td>
                  <td className={`px-4 py-3 text-right font-mono font-bold ${o.netCostRs <= 0 ? 'text-emerald-600' : 'text-amber-600'}`}>₹{Math.round(o.netCostRs).toLocaleString()}</td>
                  <td className="px-4 py-3 text-right font-mono text-emerald-600">₹{Math.round(o.scenarioFlat).toLocaleString()}</td>
                  <td className="px-4 py-3 text-right font-mono font-bold text-emerald-600">₹{Math.round(o.scenarioUp).toLocaleString()}</td>
                  <td className="px-4 py-3 text-right font-mono text-red-500">₹{Math.round(o.scenarioDown).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

/* ──────── THETA CRUSH ──────── */
function ThetaContent({ opps, cls }) {
  if (opps.length === 0) return <EmptyState message="No theta crush signals. This strategy shows opportunities on expiry day or 1-2 days before." />;
  const best = opps[0];
  const isExpiryDay = best.dte === 0;

  return (
    <div className="space-y-5">
      <div className="grid grid-cols-2 sm:grid-cols-5 gap-3">
        <StatCard label="Status" value={isExpiryDay ? 'EXPIRY DAY' : `${best.dte}d to Expiry`} color={isExpiryDay ? 'text-emerald-600' : 'text-amber-600'} />
        <StatCard label="Window" value={best.window?.split(' ')[0] || '--'} sub={best.isOptimalWindow ? 'GO NOW!' : 'Wait for optimal'} color={best.isOptimalWindow ? 'text-emerald-600' : 'text-slate-500'} />
        <StatCard label="Net Credit" value={`₹${Math.round(best.netCreditRs ?? 0).toLocaleString()}`} color="text-emerald-600" />
        <StatCard label="Expected P&L" value={`₹${Math.round(best.expectedProfitRs ?? best.dailyDecayRs ?? 0).toLocaleString()}`} color="text-emerald-600" />
        <StatCard label="Win Rate" value={best.winRate || '--'} color="text-blue-600" />
      </div>

      {!isExpiryDay && (
        <div className="bg-gradient-to-r from-amber-50 to-yellow-50 rounded-2xl border border-amber-200/80 p-5 flex items-start gap-4">
          <div className="w-12 h-12 bg-amber-100 rounded-xl flex items-center justify-center shrink-0">
            <span className="text-2xl">⏳</span>
          </div>
          <div>
            <div className="text-sm font-black text-amber-800">Not Expiry Day</div>
            <div className="text-xs text-amber-600 mt-0.5">Theta Crush strategy is most effective on expiry day after 1:30 PM when 70% of remaining time value decays. Showing preview of current straddle values below.</div>
          </div>
        </div>
      )}

      <div className="bg-white rounded-2xl border border-slate-200/80 shadow-sm overflow-hidden">
        <div className={`px-5 py-4 bg-gradient-to-r ${cls.headerBg} border-b border-slate-200/60`}>
          <h3 className={`text-sm font-black ${cls.headerText}`}>SELL ATM Straddle + BUY Wings (Iron Butterfly)</h3>
          <p className={`text-xs ${cls.headerSub} mt-0.5`}>Capture 70% theta decay in last 90 minutes of expiry. 90-95% win rate in optimal window.</p>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-xs text-left">
            <thead className="bg-slate-50/80 text-slate-500 uppercase tracking-wider text-[10px] font-bold border-b">
              <tr>
                <th className="px-4 py-3">Index</th>
                <th className="px-4 py-3">CE Strike</th>
                <th className="px-4 py-3">PE Strike</th>
                <th className="px-4 py-3 text-right">Straddle</th>
                <th className="px-4 py-3 text-right">Net Credit</th>
                <th className="px-4 py-3 text-right">Exp. P&L</th>
                <th className="px-4 py-3 text-right">Max Loss</th>
                <th className="px-4 py-3 text-center">Window</th>
                <th className="px-4 py-3 text-center">Win Rate</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100/80">
              {opps.map((o, i) => (
                <tr key={i} className={`${cls.rowHover} transition-colors ${o.isOptimalWindow ? cls.rowTop : ''}`}>
                  <td className="px-4 py-3 font-bold text-slate-800">{o.underlying}</td>
                  <td className="px-4 py-3 font-mono text-slate-700">{o.ceStrike}</td>
                  <td className="px-4 py-3 font-mono text-slate-700">{o.peStrike}</td>
                  <td className="px-4 py-3 text-right font-mono">₹{o.straddleCredit ?? o.straddleValue ?? '--'}</td>
                  <td className="px-4 py-3 text-right font-mono font-bold text-emerald-600">₹{Math.round(o.netCreditRs ?? o.dailyDecayRs ?? 0).toLocaleString()}</td>
                  <td className="px-4 py-3 text-right font-mono font-bold text-emerald-600">₹{Math.round(o.expectedProfitRs ?? o.dailyDecayRs ?? 0).toLocaleString()}</td>
                  <td className="px-4 py-3 text-right font-mono text-red-500">{o.maxLoss ? `₹${Math.round(o.maxLoss).toLocaleString()}` : '--'}</td>
                  <td className="px-4 py-3 text-center">
                    {o.isOptimalWindow
                      ? <span className="px-2.5 py-1 bg-emerald-100 text-emerald-700 rounded-full text-[10px] font-black animate-pulse border border-emerald-200">OPTIMAL</span>
                      : <span className="px-2.5 py-1 bg-slate-100 text-slate-500 rounded-full text-[10px] font-bold">{o.minutesToClose ? `${o.minutesToClose}m` : 'PREVIEW'}</span>
                    }
                  </td>
                  <td className="px-4 py-3 text-center text-[10px] font-bold text-slate-600">{o.winRate || '--'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

/* ──── Shared Components ──── */
function TypeBadge({ type }) {
  return (
    <span className={`px-2 py-0.5 rounded text-[10px] font-black ${
      type === 'CE' ? 'bg-emerald-50 text-emerald-700 border border-emerald-200' : 'bg-red-50 text-red-700 border border-red-200'
    }`}>{type}</span>
  );
}

function RRBadge({ value }) {
  return (
    <span className="px-2.5 py-1 bg-emerald-50 text-emerald-700 rounded-full text-[10px] font-black border border-emerald-200">
      1:{Math.round(value)}
    </span>
  );
}
