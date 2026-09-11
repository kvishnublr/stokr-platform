import { useState, useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import client from '../api/client';

const TABS = [
  { id: 'ratio', label: 'Ratio Butterfly', icon: '🦋', color: 'from-violet-600 to-purple-700', desc: 'Near-zero cost, 1:15 reward' },
  { id: 'bwb', label: 'Broken Wing', icon: '🔥', color: 'from-amber-600 to-orange-700', desc: 'Credit entry, zero risk one side' },
  { id: 'skew', label: 'Skew Harvest', icon: '📐', color: 'from-cyan-600 to-blue-700', desc: 'Sell overpriced puts, buy cheap calls' },
  { id: 'theta', label: 'Theta Crush', icon: '⏱️', color: 'from-emerald-600 to-teal-700', desc: 'Expiry day decay capture' },
];

const SCAN_URLS = {
  ratio: '/smart-strategies/ratio-butterfly/scan',
  bwb: '/smart-strategies/broken-wing-butterfly/scan',
  skew: '/smart-strategies/skew-harvest/scan',
  theta: '/smart-strategies/theta-crush/scan',
};

export default function SmartStrategies() {
  const [activeTab, setActiveTab] = useState('ratio');
  const [underlying, setUnderlying] = useState('ALL');

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 via-white to-slate-100">
      {/* Header */}
      <div className="bg-gradient-to-r from-slate-900 via-slate-800 to-slate-900 px-6 py-5">
        <div className="max-w-7xl mx-auto">
          <div className="flex items-center justify-between">
            <div>
              <h1 className="text-2xl font-black text-white tracking-tight">Smart Strategies</h1>
              <p className="text-slate-400 text-sm mt-0.5">Low-risk option strategies with dynamic risk management</p>
            </div>
            <div className="flex items-center gap-2">
              {['ALL', 'NIFTY', 'BANKNIFTY'].map(u => (
                <button key={u} onClick={() => setUnderlying(u)}
                  className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${
                    underlying === u ? 'bg-white text-slate-900' : 'bg-white/10 text-white/70 hover:bg-white/20'
                  }`}>{u}</button>
              ))}
            </div>
          </div>
        </div>
      </div>

      {/* Tab Bar */}
      <div className="max-w-7xl mx-auto px-6 -mt-4">
        <div className="flex gap-3">
          {TABS.map(tab => (
            <button key={tab.id} onClick={() => setActiveTab(tab.id)}
              className={`flex-1 rounded-xl p-3 transition-all duration-200 border-2 ${
                activeTab === tab.id
                  ? `bg-gradient-to-r ${tab.color} text-white border-transparent shadow-lg shadow-${tab.color.split('-')[1]}-500/20 scale-[1.02]`
                  : 'bg-white text-slate-600 border-slate-200 hover:border-slate-300 hover:shadow-sm'
              }`}>
              <div className="flex items-center gap-2">
                <span className="text-xl">{tab.icon}</span>
                <div className="text-left">
                  <div className="text-xs font-black">{tab.label}</div>
                  <div className={`text-[9px] ${activeTab === tab.id ? 'text-white/70' : 'text-slate-400'}`}>{tab.desc}</div>
                </div>
              </div>
            </button>
          ))}
        </div>
      </div>

      {/* Content */}
      <div className="max-w-7xl mx-auto px-6 py-5">
        {activeTab === 'ratio' && <RatioButterflyTab underlying={underlying} />}
        {activeTab === 'bwb' && <BWBTab underlying={underlying} />}
        {activeTab === 'skew' && <SkewHarvestTab underlying={underlying} />}
        {activeTab === 'theta' && <ThetaCrushTab underlying={underlying} />}
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
  });
}

function StatCard({ label, value, sub, color = 'text-slate-800' }) {
  return (
    <div className="bg-white rounded-xl border border-slate-200 p-3 shadow-sm">
      <div className="text-[10px] text-slate-400 font-bold uppercase tracking-wider">{label}</div>
      <div className={`text-lg font-black ${color} mt-0.5`}>{value}</div>
      {sub && <div className="text-[10px] text-slate-400 mt-0.5">{sub}</div>}
    </div>
  );
}

function ScanStatus({ data, isLoading }) {
  if (isLoading) return (
    <div className="flex items-center justify-center py-16">
      <div className="animate-spin w-8 h-8 border-4 border-slate-200 border-t-slate-800 rounded-full"></div>
      <span className="ml-3 text-slate-500 font-bold">Scanning live option chain...</span>
    </div>
  );
  if (!data) return <div className="text-center py-16 text-slate-400">No data</div>;
  return null;
}

/* ──────── RATIO BUTTERFLY ──────── */
function RatioButterflyTab({ underlying }) {
  const { data, isLoading } = useScan('ratio', underlying);
  const opps = data?.opportunities || [];

  const status = <ScanStatus data={data} isLoading={isLoading} />;
  if (status) return status;

  const best = opps[0];
  return (
    <div className="space-y-4">
      {best && (
        <div className="grid grid-cols-5 gap-3">
          <StatCard label="Best R:R" value={`1:${Math.round(best.riskReward)}`} color="text-emerald-600" />
          <StatCard label="Max Risk" value={`₹${Math.round(best.maxLoss)}`} sub="Per lot" color="text-red-500" />
          <StatCard label="Max Reward" value={`₹${Math.round(best.maxProfit).toLocaleString()}`} color="text-emerald-600" />
          <StatCard label="Sweet Spot" value={best.sweetSpot} sub={best.underlying} />
          <StatCard label="Signals" value={opps.length} sub={best.expiry} />
        </div>
      )}

      <div className="bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden">
        <div className="px-4 py-3 bg-gradient-to-r from-violet-50 to-purple-50 border-b border-slate-200">
          <h3 className="text-xs font-black text-violet-800">BUY 1 ATM | SELL 3 OTM | BUY 2 FAR OTM</h3>
          <p className="text-[10px] text-violet-500 mt-0.5">Risk ₹200-500 to make ₹5,000-15,000. 20-25% hit rate = net profitable.</p>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-[11px] text-left">
            <thead className="bg-slate-50 text-slate-500 uppercase tracking-wider font-bold border-b">
              <tr>
                <th className="px-3 py-2">Index</th>
                <th className="px-3 py-2">Type</th>
                <th className="px-3 py-2">Buy</th>
                <th className="px-3 py-2">Sell 3x</th>
                <th className="px-3 py-2">Buy 2x</th>
                <th className="px-3 py-2 text-right">Net Cost</th>
                <th className="px-3 py-2 text-right">Max Risk</th>
                <th className="px-3 py-2 text-right">Max Reward</th>
                <th className="px-3 py-2 text-right">R:R</th>
                <th className="px-3 py-2">Expiry</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {opps.map((o, i) => (
                <tr key={i} className={`hover:bg-violet-50/50 ${i === 0 ? 'bg-violet-50/30' : ''}`}>
                  <td className="px-3 py-2 font-bold">{o.underlying}</td>
                  <td className="px-3 py-2"><span className={`px-1.5 py-0.5 rounded text-[9px] font-black ${o.optionType === 'CE' ? 'bg-emerald-100 text-emerald-700' : 'bg-red-100 text-red-700'}`}>{o.optionType}</span></td>
                  <td className="px-3 py-2 font-mono">{o.buyStrike} @ ₹{o.buyPrice}</td>
                  <td className="px-3 py-2 font-mono font-bold text-red-600">{o.sellStrike} @ ₹{o.sellPrice}</td>
                  <td className="px-3 py-2 font-mono">{o.farBuyStrike} @ ₹{o.farBuyPrice}</td>
                  <td className={`px-3 py-2 text-right font-mono font-bold ${o.netCost <= 0 ? 'text-emerald-600' : 'text-amber-600'}`}>₹{Math.round(o.netCostRs)}</td>
                  <td className="px-3 py-2 text-right font-mono text-red-500">₹{Math.round(o.maxLoss)}</td>
                  <td className="px-3 py-2 text-right font-mono font-bold text-emerald-600">₹{Math.round(o.maxProfit).toLocaleString()}</td>
                  <td className="px-3 py-2 text-right"><span className="px-2 py-0.5 bg-emerald-100 text-emerald-700 rounded-full text-[10px] font-black">1:{Math.round(o.riskReward)}</span></td>
                  <td className="px-3 py-2 text-slate-400 text-[10px]">{o.expiry}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {opps.length === 0 && <div className="text-center py-8 text-slate-400 text-sm">No ratio butterfly opportunities found right now</div>}
        </div>
      </div>
    </div>
  );
}

/* ──────── BROKEN WING BUTTERFLY ──────── */
function BWBTab({ underlying }) {
  const { data, isLoading } = useScan('bwb', underlying);
  const opps = data?.opportunities || [];

  const status = <ScanStatus data={data} isLoading={isLoading} />;
  if (status) return status;

  const best = opps[0];
  return (
    <div className="space-y-4">
      {best && (
        <div className="grid grid-cols-5 gap-3">
          <StatCard label="Best Credit" value={`₹${Math.round(best.creditRs)}`} color="text-emerald-600" />
          <StatCard label="Zero Risk Side" value={best.zeroRiskSide} color="text-blue-600" />
          <StatCard label="Max Profit" value={`₹${Math.round(best.maxProfit).toLocaleString()}`} color="text-emerald-600" />
          <StatCard label="Max Loss" value={`₹${Math.round(best.maxLoss)}`} color="text-red-500" />
          <StatCard label="Signals" value={opps.length} sub={best.expiry} />
        </div>
      )}

      <div className="bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden">
        <div className="px-4 py-3 bg-gradient-to-r from-amber-50 to-orange-50 border-b border-slate-200">
          <h3 className="text-xs font-black text-amber-800">BUY Wing | SELL 2x Body | BUY Far Wing (Skipped)</h3>
          <p className="text-[10px] text-amber-500 mt-0.5">Credit entry. Zero risk on one direction. 60-65% win rate.</p>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-[11px] text-left">
            <thead className="bg-slate-50 text-slate-500 uppercase tracking-wider font-bold border-b">
              <tr>
                <th className="px-3 py-2">Index</th>
                <th className="px-3 py-2">Type</th>
                <th className="px-3 py-2">Near Wing</th>
                <th className="px-3 py-2">Body (2x)</th>
                <th className="px-3 py-2">Far Wing</th>
                <th className="px-3 py-2 text-right">Credit</th>
                <th className="px-3 py-2 text-right">Max Profit</th>
                <th className="px-3 py-2 text-right">Max Loss</th>
                <th className="px-3 py-2 text-center">Zero Risk</th>
                <th className="px-3 py-2">Expiry</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {opps.map((o, i) => (
                <tr key={i} className={`hover:bg-amber-50/50 ${i === 0 ? 'bg-amber-50/30' : ''}`}>
                  <td className="px-3 py-2 font-bold">{o.underlying}</td>
                  <td className="px-3 py-2"><span className={`px-1.5 py-0.5 rounded text-[9px] font-black ${o.optionType === 'CE' ? 'bg-emerald-100 text-emerald-700' : 'bg-red-100 text-red-700'}`}>{o.optionType}</span></td>
                  <td className="px-3 py-2 font-mono">{o.nearWingStrike} @ ₹{o.nearWingPrice}</td>
                  <td className="px-3 py-2 font-mono font-bold text-red-600">{o.bodyStrike} @ ₹{o.bodyPrice}</td>
                  <td className="px-3 py-2 font-mono">{o.farWingStrike} @ ₹{o.farWingPrice}</td>
                  <td className="px-3 py-2 text-right font-mono font-bold text-emerald-600">₹{Math.round(o.creditRs)}</td>
                  <td className="px-3 py-2 text-right font-mono text-emerald-600">₹{Math.round(o.maxProfit).toLocaleString()}</td>
                  <td className="px-3 py-2 text-right font-mono text-red-500">₹{Math.round(o.maxLoss)}</td>
                  <td className="px-3 py-2 text-center"><span className="px-2 py-0.5 bg-blue-100 text-blue-700 rounded-full text-[9px] font-black">{o.zeroRiskSide}</span></td>
                  <td className="px-3 py-2 text-slate-400 text-[10px]">{o.expiry}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {opps.length === 0 && <div className="text-center py-8 text-slate-400 text-sm">No broken wing butterfly opportunities found</div>}
        </div>
      </div>
    </div>
  );
}

/* ──────── SKEW HARVEST ──────── */
function SkewHarvestTab({ underlying }) {
  const { data, isLoading } = useScan('skew', underlying);
  const opps = data?.opportunities || [];

  const status = <ScanStatus data={data} isLoading={isLoading} />;
  if (status) return status;

  const best = opps[0];
  return (
    <div className="space-y-4">
      {best && (
        <div className="grid grid-cols-5 gap-3">
          <StatCard label="IV Skew Edge" value={`${best.skewEdge}%`} color="text-cyan-600" />
          <StatCard label="Net Cost" value={`₹${Math.round(best.netCostRs)}`} sub={best.netCost > 0 ? 'Debit' : 'Credit'} color={best.netCost <= 0 ? 'text-emerald-600' : 'text-amber-600'} />
          <StatCard label="If Flat" value={`₹${Math.round(best.scenarioFlat)}`} color="text-emerald-600" />
          <StatCard label="If Up" value={`₹${Math.round(best.scenarioUp)}`} color="text-emerald-600" />
          <StatCard label="If Down" value={`₹${Math.round(best.scenarioDown)}`} color="text-red-500" />
        </div>
      )}

      <div className="bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden">
        <div className="px-4 py-3 bg-gradient-to-r from-cyan-50 to-blue-50 border-b border-slate-200">
          <h3 className="text-xs font-black text-cyan-800">SELL OTM Put Spread (overpriced) + BUY OTM Call Spread (cheap)</h3>
          <p className="text-[10px] text-cyan-500 mt-0.5">Exploits structural IV skew. Near-zero cost. Win on flat + up, small loss only on crash.</p>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-[11px] text-left">
            <thead className="bg-slate-50 text-slate-500 uppercase tracking-wider font-bold border-b">
              <tr>
                <th className="px-3 py-2">Index</th>
                <th className="px-3 py-2">Put Spread</th>
                <th className="px-3 py-2">Call Spread</th>
                <th className="px-3 py-2 text-right">Put IV</th>
                <th className="px-3 py-2 text-right">Call IV</th>
                <th className="px-3 py-2 text-right">Skew</th>
                <th className="px-3 py-2 text-right">Net Cost</th>
                <th className="px-3 py-2 text-right">If Flat</th>
                <th className="px-3 py-2 text-right">If Up</th>
                <th className="px-3 py-2 text-right">If Down</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {opps.map((o, i) => (
                <tr key={i} className={`hover:bg-cyan-50/50 ${i === 0 ? 'bg-cyan-50/30' : ''}`}>
                  <td className="px-3 py-2 font-bold">{o.underlying}</td>
                  <td className="px-3 py-2 font-mono text-red-600">S{o.putSellStrike}/B{o.putBuyStrike}</td>
                  <td className="px-3 py-2 font-mono text-emerald-600">B{o.callBuyStrike}/S{o.callSellStrike}</td>
                  <td className="px-3 py-2 text-right font-mono">{o.putSellIV}%</td>
                  <td className="px-3 py-2 text-right font-mono">{o.callBuyIV}%</td>
                  <td className="px-3 py-2 text-right"><span className="px-2 py-0.5 bg-cyan-100 text-cyan-700 rounded-full text-[10px] font-black">{o.skewEdge}%</span></td>
                  <td className={`px-3 py-2 text-right font-mono font-bold ${o.netCost <= 0 ? 'text-emerald-600' : 'text-amber-600'}`}>₹{Math.round(o.netCostRs)}</td>
                  <td className="px-3 py-2 text-right font-mono text-emerald-600">₹{Math.round(o.scenarioFlat)}</td>
                  <td className="px-3 py-2 text-right font-mono font-bold text-emerald-600">₹{Math.round(o.scenarioUp)}</td>
                  <td className="px-3 py-2 text-right font-mono text-red-500">₹{Math.round(o.scenarioDown)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {opps.length === 0 && <div className="text-center py-8 text-slate-400 text-sm">No skew harvest opportunities (need IV skew &gt; 2%)</div>}
        </div>
      </div>
    </div>
  );
}

/* ──────── THETA CRUSH ──────── */
function ThetaCrushTab({ underlying }) {
  const { data, isLoading } = useScan('theta', underlying);
  const opps = data?.opportunities || [];

  const status = <ScanStatus data={data} isLoading={isLoading} />;
  if (status) return status;

  const best = opps[0];
  const isExpiryDay = best && best.dte === 0;

  return (
    <div className="space-y-4">
      {best && (
        <div className="grid grid-cols-5 gap-3">
          <StatCard label="Status" value={isExpiryDay ? 'EXPIRY DAY' : `${best.dte}d to Expiry`} color={isExpiryDay ? 'text-emerald-600' : 'text-amber-600'} />
          <StatCard label="Window" value={best.window?.split(' ')[0] || '--'} sub={best.isOptimalWindow ? 'GO!' : 'Wait'} color={best.isOptimalWindow ? 'text-emerald-600' : 'text-slate-500'} />
          <StatCard label="Net Credit" value={`₹${Math.round(best.netCreditRs || 0)}`} color="text-emerald-600" />
          <StatCard label="Expected P&L" value={`₹${Math.round(best.expectedProfitRs || best.dailyDecayRs || 0)}`} color="text-emerald-600" />
          <StatCard label="Win Rate" value={best.winRate || '--'} color="text-blue-600" />
        </div>
      )}

      {!isExpiryDay && (
        <div className="bg-gradient-to-r from-amber-50 to-yellow-50 rounded-xl border border-amber-200 p-4">
          <div className="flex items-center gap-2">
            <span className="text-2xl">⏳</span>
            <div>
              <div className="text-sm font-black text-amber-800">Not Expiry Day</div>
              <div className="text-xs text-amber-600">Theta Crush strategy is most effective on expiry day (Thursday) after 1:30 PM. Showing preview of current straddle values.</div>
            </div>
          </div>
        </div>
      )}

      <div className="bg-white rounded-xl border border-slate-200 shadow-sm overflow-hidden">
        <div className="px-4 py-3 bg-gradient-to-r from-emerald-50 to-teal-50 border-b border-slate-200">
          <h3 className="text-xs font-black text-emerald-800">SELL ATM Straddle + BUY Wings (hedged)</h3>
          <p className="text-[10px] text-emerald-500 mt-0.5">Capture 70% theta decay in last 90 minutes of expiry. 90-95% win rate in optimal window.</p>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-[11px] text-left">
            <thead className="bg-slate-50 text-slate-500 uppercase tracking-wider font-bold border-b">
              <tr>
                <th className="px-3 py-2">Index</th>
                <th className="px-3 py-2">CE Strike</th>
                <th className="px-3 py-2">PE Strike</th>
                <th className="px-3 py-2 text-right">Straddle</th>
                <th className="px-3 py-2 text-right">Net Credit</th>
                <th className="px-3 py-2 text-right">Exp. Decay</th>
                <th className="px-3 py-2 text-right">Exp. P&L</th>
                <th className="px-3 py-2 text-right">Max Loss</th>
                <th className="px-3 py-2 text-center">Window</th>
                <th className="px-3 py-2 text-center">Win Rate</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {opps.map((o, i) => (
                <tr key={i} className={`hover:bg-emerald-50/50 ${o.isOptimalWindow ? 'bg-emerald-50/30' : ''}`}>
                  <td className="px-3 py-2 font-bold">{o.underlying}</td>
                  <td className="px-3 py-2 font-mono">{o.ceStrike}</td>
                  <td className="px-3 py-2 font-mono">{o.peStrike}</td>
                  <td className="px-3 py-2 text-right font-mono">₹{o.straddleCredit || o.straddleValue || '--'}</td>
                  <td className="px-3 py-2 text-right font-mono font-bold text-emerald-600">₹{Math.round((o.netCreditRs || o.dailyDecayRs || 0))}</td>
                  <td className="px-3 py-2 text-right font-mono">{o.thetaDecayExpected ? `₹${o.thetaDecayExpected}` : '--'}</td>
                  <td className="px-3 py-2 text-right font-mono font-bold text-emerald-600">₹{Math.round(o.expectedProfitRs || o.dailyDecayRs || 0)}</td>
                  <td className="px-3 py-2 text-right font-mono text-red-500">{o.maxLoss ? `₹${Math.round(o.maxLoss)}` : '--'}</td>
                  <td className="px-3 py-2 text-center">
                    {o.isOptimalWindow
                      ? <span className="px-2 py-0.5 bg-emerald-100 text-emerald-700 rounded-full text-[9px] font-black animate-pulse">OPTIMAL</span>
                      : <span className="px-2 py-0.5 bg-slate-100 text-slate-500 rounded-full text-[9px] font-bold">{o.minutesToClose ? `${o.minutesToClose}m left` : 'PREVIEW'}</span>
                    }
                  </td>
                  <td className="px-3 py-2 text-center text-[10px] font-bold">{o.winRate || '--'}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {opps.length === 0 && <div className="text-center py-8 text-slate-400 text-sm">No theta crush signals (shows on expiry day or 1-2 days before)</div>}
        </div>
      </div>
    </div>
  );
}
