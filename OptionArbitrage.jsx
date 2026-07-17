import React, { useState } from 'react';
import { useQuery, useMutation } from '@tanstack/react-query';
import axios from 'axios';

const API_BASE = import.meta.env.VITE_API_URL || '';

const TYPE_COLORS = {
  PARITY_BREAK: { bg: 'bg-emerald-500/20', text: 'text-emerald-400', border: 'border-emerald-500/30' },
  IV_SPIKE: { bg: 'bg-amber-500/20', text: 'text-amber-400', border: 'border-amber-500/30' },
  DEEP_ITM_STALE: { bg: 'bg-blue-500/20', text: 'text-blue-400', border: 'border-blue-500/30' },
  SKEW_ANOMALY: { bg: 'bg-purple-500/20', text: 'text-purple-400', border: 'border-purple-500/30' },
};

const TYPE_LABELS = {
  PARITY_BREAK: 'Parity Break', IV_SPIKE: 'IV Spike',
  DEEP_ITM_STALE: 'Deep ITM Stale', SKEW_ANOMALY: 'Skew Anomaly',
};

const UNDERLYINGS = ['ALL', 'NIFTY', 'BANKNIFTY', 'MIDCPNIFTY', 'FINNIFTY'];
const TABS = [
  { id: 'scan', label: 'Live Scan' },
  { id: 'history', label: 'History' },
  { id: 'calendar', label: 'Calendar Spread' },
  { id: 'volsurface', label: 'Vol Surface' },
];

function getLotSize(u) {
  if (u === 'BANKNIFTY') return 30;
  if (u === 'MIDCPNIFTY') return 120;
  if (u === 'FINNIFTY') return 60;
  return 65;
}

function computeRunningPnl(opp, livePrices) {
  if (!livePrices || livePrices.length === 0) return null;
  const lp = livePrices.find(p => p.underlying === opp.underlying && p.strike === opp.strike);
  if (!lp || !lp.futLive || lp.futLive === 0) return null;
  if (opp.action === 'CONVERSION') {
    const ceDelta = (lp.ceLive || 0) - (opp.cePrice || 0);
    const peDelta = (opp.pePrice || 0) - (lp.peLive || 0);
    const futDelta = (opp.futuresPrice || 0) - (lp.futLive || 0);
    return (ceDelta + peDelta + futDelta) * getLotSize(opp.underlying);
  }
  if (opp.action === 'REVERSAL') {
    const ceDelta = (opp.cePrice || 0) - (lp.ceLive || 0);
    const peDelta = (lp.peLive || 0) - (opp.pePrice || 0);
    const futDelta = (lp.futLive || 0) - (opp.futuresPrice || 0);
    return (ceDelta + peDelta + futDelta) * getLotSize(opp.underlying);
  }
  if (opp.action === 'SELL_STRADDLE') {
    return ((opp.cePrice || 0) - (lp.ceLive || 0) + (opp.pePrice || 0) - (lp.peLive || 0)) * getLotSize(opp.underlying);
  }
  return null;
}

export default function OptionArbitrage() {
  const [autoRefresh, setAutoRefresh] = useState(false);
  const [underlying, setUnderlying] = useState('ALL');
  const [activeTab, setActiveTab] = useState('scan');
  const [selectedOpportunity, setSelectedOpportunity] = useState(null);
  const [volUnderlying, setVolUnderlying] = useState('NIFTY');
  const [histDate, setHistDate] = useState('ALL');

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['option-arb-today', underlying],
    queryFn: async () => {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/today`, { params: { underlying } });
      return res.data;
    },
    enabled: activeTab === 'scan',
    refetchInterval: autoRefresh ? 30000 : false,
    staleTime: 30000,
  });

  const { data: liveData } = useQuery({
    queryKey: ['option-arb-live', underlying],
    queryFn: async () => {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/live-prices-batch`, { params: { underlying } });
      return res.data;
    },
    enabled: activeTab === 'scan',
    refetchInterval: 30000,
    staleTime: 15000,
  });

  const { data: histData, isLoading: histLoading } = useQuery({
    queryKey: ['option-arb-history', histDate],
    queryFn: async () => {
      const params = {};
      if (histDate !== 'ALL') params.date = histDate;
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/history`, { params });
      return res.data;
    },
    enabled: activeTab === 'history',
    staleTime: 30000,
  });

  const { data: histDates } = useQuery({
    queryKey: ['option-arb-dates'],
    queryFn: async () => {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/history/dates`);
      return res.data;
    },
    enabled: activeTab === 'history',
    staleTime: 60000,
  });

  const { data: calData, isLoading: calLoading, refetch: calRefetch } = useQuery({
    queryKey: ['calendar-spread', underlying],
    queryFn: async () => {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/calendar-spread`, { params: { underlying } });
      return res.data;
    },
    enabled: activeTab === 'calendar',
    staleTime: 30000,
  });

  const { data: volData, isLoading: volLoading, refetch: volRefetch } = useQuery({
    queryKey: ['vol-surface', volUnderlying],
    queryFn: async () => {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/vol-surface`, { params: { underlying: volUnderlying } });
      return res.data;
    },
    enabled: activeTab === 'volsurface',
    staleTime: 30000,
  });

  const { data: health } = useQuery({
    queryKey: ['option-arb-health'],
    queryFn: async () => { const r = await axios.get(`${API_BASE}/api/option-arbitrage/health`); return r.data; },
    staleTime: 60000,
  });

  const todayOpps = data?.opportunities || [];
  const livePrices = liveData?.prices || [];
  const histOpps = histData?.opportunities || [];
  const spreads = calData?.spreads || [];
  const totalEdge = todayOpps.reduce((s, o) => s + (o.edgeAfterCosts || 0), 0);

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-white">Option Arb Scanner</h1>
          <p className="text-sm text-zinc-400 mt-1">Parity breaks, IV spikes, calendar spreads, volatility surface</p>
        </div>
        <div className={`px-3 py-1 rounded-full text-xs font-medium ${health?.scannerReady ? 'bg-emerald-500/20 text-emerald-400' : 'bg-red-500/20 text-red-400'}`}>
          {health?.scannerReady ? 'Ready' : 'Offline'}
        </div>
      </div>

      <div className="flex bg-zinc-800 rounded-lg p-1 w-fit">
        {TABS.map(tab => (
          <button key={tab.id} onClick={() => setActiveTab(tab.id)}
            className={`px-4 py-2 rounded-md text-sm font-medium transition-colors ${activeTab === tab.id ? 'bg-blue-600 text-white' : 'text-zinc-400 hover:text-white'}`}>
            {tab.label}
          </button>
        ))}
      </div>

      {activeTab === 'scan' && (
        <>
          <div className="flex items-center gap-4">
            <div className="flex bg-zinc-800 rounded-lg p-1">
              {UNDERLYINGS.map(opt => (
                <button key={opt} onClick={() => setUnderlying(opt)}
                  className={`px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${underlying === opt ? 'bg-blue-600 text-white' : 'text-zinc-400 hover:text-white'}`}>
                  {opt === 'ALL' ? 'All' : opt}
                </button>
              ))}
            </div>
            <button onClick={() => refetch()} disabled={isLoading}
              className="px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded-lg text-sm font-medium disabled:opacity-50">
              {isLoading ? 'Scanning...' : 'Scan Now'}
            </button>
            <button onClick={() => setAutoRefresh(!autoRefresh)}
              className={`px-4 py-2 rounded-lg text-sm font-medium transition-colors ${autoRefresh ? 'bg-emerald-600 text-white' : 'bg-zinc-700 text-zinc-300 hover:bg-zinc-600'}`}>
              {autoRefresh ? 'Auto: ON' : 'Auto: OFF'}
            </button>
          </div>

          <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
            <StatCard label="Today's Opportunities" value={todayOpps.length} color={todayOpps.length > 0 ? 'text-emerald-400' : 'text-zinc-400'} />
            <StatCard label="Total Edge" value={`₹${totalEdge.toFixed(0)}`} color={totalEdge > 0 ? 'text-emerald-400' : 'text-zinc-400'} />
            <StatCard label="Open" value={todayOpps.filter(o => o.status === 'OPEN').length} color="text-blue-400" />
            <StatCard label="Expired" value={todayOpps.filter(o => o.status === 'EXPIRED').length} color="text-zinc-400" />
          </div>

          <div className="bg-zinc-900 rounded-xl border border-zinc-800 overflow-hidden">
            <div className="px-6 py-4 border-b border-zinc-800">
              <h2 className="text-lg font-semibold text-white">Live Scan ({todayOpps.length})</h2>
            </div>
            {todayOpps.length === 0 ? (
              <div className="px-6 py-12 text-center text-zinc-500">
                <p className="text-lg">No opportunities today</p>
                <p className="text-xs mt-2">Click "Scan Now" to find parity breaks</p>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full">
                  <thead>
                    <tr className="text-xs text-zinc-500 uppercase border-b border-zinc-800">
                      <th className="px-4 py-3 text-left">Type</th>
                      <th className="px-4 py-3 text-left">Underlying</th>
                      <th className="px-4 py-3 text-left">Strike</th>
                      <th className="px-4 py-3 text-left">Action</th>
                      <th className="px-4 py-3 text-right">CE</th>
                      <th className="px-4 py-3 text-right">PE</th>
                      <th className="px-4 py-3 text-right">Edge (pts)</th>
                      <th className="px-4 py-3 text-right">Edge (₹)</th>
                      <th className="px-4 py-3 text-right">P&L</th>
                      <th className="px-4 py-3 text-right">Conf</th>
                      <th className="px-4 py-3 text-right">Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {todayOpps.map((opp, idx) => {
                      const pnl = computeRunningPnl(opp, livePrices);
                      return (
                        <tr key={idx}
                          onClick={() => setSelectedOpportunity(selectedOpportunity === `s${idx}` ? null : `s${idx}`)}
                          className={`border-b border-zinc-800/50 cursor-pointer transition-colors ${selectedOpportunity === `s${idx}` ? 'bg-zinc-800' : 'hover:bg-zinc-800/50'}`}>
                          <td className="px-4 py-3">
                            <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium border ${TYPE_COLORS[opp.type]?.bg} ${TYPE_COLORS[opp.type]?.text} ${TYPE_COLORS[opp.type]?.border}`}>
                              {TYPE_LABELS[opp.type] || opp.type}
                            </span>
                          </td>
                          <td className="px-4 py-3 text-sm text-zinc-300 font-medium">{opp.underlying}</td>
                          <td className="px-4 py-3 text-sm text-white font-mono">{opp.strike}</td>
                          <td className="px-4 py-3 text-xs text-zinc-400">{opp.action}</td>
                          <td className="px-4 py-3 text-sm text-right text-zinc-300 font-mono">₹{opp.cePrice?.toFixed(0)}</td>
                          <td className="px-4 py-3 text-sm text-right text-zinc-300 font-mono">₹{opp.pePrice?.toFixed(0)}</td>
                          <td className={`px-4 py-3 text-sm text-right font-mono font-medium ${(opp.edgePoints || 0) > 0 ? 'text-emerald-400' : 'text-red-400'}`}>
                            {opp.edgePoints > 0 ? '+' : ''}{opp.edgePoints?.toFixed(1)}
                          </td>
                          <td className={`px-4 py-3 text-sm text-right font-mono font-bold ${(opp.edgeAfterCosts || 0) > 0 ? 'text-emerald-400' : 'text-red-400'}`}>
                            ₹{opp.edgeAfterCosts?.toFixed(0)}
                          </td>
                          <td className={`px-4 py-3 text-sm text-right font-mono font-bold ${pnl !== null ? (pnl >= 0 ? 'text-emerald-400' : 'text-red-400') : 'text-zinc-500'}`}>
                            {pnl !== null ? `₹${pnl.toFixed(0)}` : '--'}
                          </td>
                          <td className="px-4 py-3 text-right"><span className="text-xs text-zinc-400">{opp.confidence}%</span></td>
                          <td className="px-4 py-3 text-right">
                            <span className={`text-xs font-medium ${opp.status === 'OPEN' ? 'text-blue-400' : opp.status === 'EXPIRED' ? 'text-amber-400' : 'text-zinc-400'}`}>{opp.status}</span>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          {selectedOpportunity !== null && selectedOpportunity.startsWith('s') && todayOpps[parseInt(selectedOpportunity.slice(1))] && (
            <OpportunityDetail opp={todayOpps[parseInt(selectedOpportunity.slice(1))]} livePrices={livePrices} />
          )}
        </>
      )}

      {activeTab === 'history' && (
        <>
          <div className="flex items-center gap-4">
            <label className="text-sm text-zinc-400">Select Date:</label>
            <select value={histDate} onChange={e => setHistDate(e.target.value)}
              className="bg-zinc-800 text-white border border-zinc-700 rounded-lg px-3 py-2 text-sm">
              <option value="ALL">All Dates</option>
              {histDates?.dates?.map(d => (
                <option key={d} value={d}>{d}</option>
              ))}
            </select>
          </div>

          {histOpps.length > 0 && (
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
              <StatCard label="Total Opportunities" value={histOpps.length} color="text-blue-400" />
              <StatCard label="Total Edge" value={`₹${histOpps.reduce((s, o) => s + (o.edgeAfterCosts || 0), 0).toFixed(0)}`} color="text-emerald-400" />
              <StatCard label="Open" value={histOpps.filter(o => o.status === 'OPEN').length} color="text-blue-400" />
              <StatCard label="Expired/Closed" value={histOpps.filter(o => o.status !== 'OPEN').length} color="text-zinc-400" />
            </div>
          )}

          <div className="bg-zinc-900 rounded-xl border border-zinc-800 overflow-hidden">
            <div className="px-6 py-4 border-b border-zinc-800">
              <h2 className="text-lg font-semibold text-white">History ({histOpps.length} total)</h2>
            </div>
            {histLoading ? (
              <div className="px-6 py-12 text-center text-zinc-500">Loading...</div>
            ) : histOpps.length === 0 ? (
              <div className="px-6 py-12 text-center text-zinc-500">No historical records</div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full">
                  <thead>
                    <tr className="text-xs text-zinc-500 uppercase border-b border-zinc-800">
                      <th className="px-4 py-3 text-left">Date</th>
                      <th className="px-4 py-3 text-left">Type</th>
                      <th className="px-4 py-3 text-left">Underlying</th>
                      <th className="px-4 py-3 text-left">Strike</th>
                      <th className="px-4 py-3 text-left">Action</th>
                      <th className="px-4 py-3 text-right">CE Entry</th>
                      <th className="px-4 py-3 text-right">PE Entry</th>
                      <th className="px-4 py-3 text-right">Edge (₹)</th>
                      <th className="px-4 py-3 text-right">Status</th>
                      <th className="px-4 py-3 text-right">P&L</th>
                    </tr>
                  </thead>
                  <tbody>
                    {histOpps.map((opp, idx) => (
                      <tr key={idx}
                        onClick={() => setSelectedOpportunity(selectedOpportunity === `h${idx}` ? null : `h${idx}`)}
                        className={`border-b border-zinc-800/50 cursor-pointer transition-colors ${selectedOpportunity === `h${idx}` ? 'bg-zinc-800' : 'hover:bg-zinc-800/50'}`}>
                        <td className="px-4 py-3 text-xs text-zinc-400">{opp.scanTime ? new Date(opp.scanTime).toLocaleString() : '-'}</td>
                        <td className="px-4 py-3">
                          <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium border ${TYPE_COLORS[opp.type]?.bg} ${TYPE_COLORS[opp.type]?.text} ${TYPE_COLORS[opp.type]?.border}`}>
                            {TYPE_LABELS[opp.type] || opp.type}
                          </span>
                        </td>
                        <td className="px-4 py-3 text-sm text-zinc-300 font-medium">{opp.underlying}</td>
                        <td className="px-4 py-3 text-sm text-white font-mono">{opp.strike}</td>
                        <td className="px-4 py-3 text-xs text-zinc-400">{opp.action}</td>
                        <td className="px-4 py-3 text-sm text-right text-zinc-300 font-mono">₹{opp.cePrice?.toFixed(0)}</td>
                        <td className="px-4 py-3 text-sm text-right text-zinc-300 font-mono">₹{opp.pePrice?.toFixed(0)}</td>
                        <td className={`px-4 py-3 text-sm text-right font-mono font-bold ${(opp.edgeAfterCosts || 0) > 0 ? 'text-emerald-400' : 'text-red-400'}`}>
                          ₹{opp.edgeAfterCosts?.toFixed(0)}
                        </td>
                        <td className="px-4 py-3 text-right">
                          <span className={`text-xs font-medium px-2 py-1 rounded-full ${opp.status === 'OPEN' ? 'bg-blue-500/20 text-blue-400' : opp.status === 'EXPIRED' ? 'bg-amber-500/20 text-amber-400' : opp.status === 'ACTIVE' ? 'bg-emerald-500/20 text-emerald-400' : 'bg-zinc-500/20 text-zinc-400'}`}>
                            {opp.status}
                          </span>
                        </td>
                        <td className={`px-4 py-3 text-sm text-right font-mono ${(opp.pnlAmount || 0) !== 0 ? (opp.pnlAmount > 0 ? 'text-emerald-400' : 'text-red-400') : 'text-zinc-500'}`}>
                          {opp.pnlAmount ? `₹${opp.pnlAmount.toFixed(0)}` : '--'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          {selectedOpportunity !== null && selectedOpportunity.startsWith('h') && histOpps[parseInt(selectedOpportunity.slice(1))] && (
            <OpportunityDetail opp={histOpps[parseInt(selectedOpportunity.slice(1))]} />
          )}
        </>
      )}

      {activeTab === 'calendar' && (
        <>
          <div className="flex items-center gap-4">
            <div className="flex bg-zinc-800 rounded-lg p-1">
              {UNDERLYINGS.map(opt => (
                <button key={opt} onClick={() => setUnderlying(opt)}
                  className={`px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${underlying === opt ? 'bg-blue-600 text-white' : 'text-zinc-400 hover:text-white'}`}>
                  {opt === 'ALL' ? 'All' : opt}
                </button>
              ))}
            </div>
            <button onClick={() => calRefetch()} disabled={calLoading}
              className="px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded-lg text-sm font-medium disabled:opacity-50">
              {calLoading ? 'Scanning...' : 'Scan Calendar Spreads'}
            </button>
          </div>
          <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
            <StatCard label="Calendar Spreads" value={spreads.length} color={spreads.length > 0 ? 'text-orange-400' : 'text-zinc-400'} />
            <StatCard label="Inversions" value={spreads.filter(s => s.type === 'CALENDAR_INVERSION').length} color="text-orange-400" />
            <StatCard label="Total Edge" value={`₹${spreads.reduce((s, r) => s + (r.edgeAfterCosts || 0), 0).toFixed(0)}`} color="text-orange-400" />
          </div>
          <div className="bg-zinc-900 rounded-xl border border-zinc-800 overflow-hidden">
            {spreads.length === 0 ? (
              <div className="px-6 py-12 text-center text-zinc-500">No calendar spread opportunities</div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full">
                  <thead>
                    <tr className="text-xs text-zinc-500 uppercase border-b border-zinc-800">
                      <th className="px-4 py-3 text-left">Type</th>
                      <th className="px-4 py-3 text-left">Underlying</th>
                      <th className="px-4 py-3 text-left">Strike</th>
                      <th className="px-4 py-3 text-right">Weekly IV</th>
                      <th className="px-4 py-3 text-right">Monthly IV</th>
                      <th className="px-4 py-3 text-right">IV Ratio</th>
                      <th className="px-4 py-3 text-right">Edge (₹)</th>
                    </tr>
                  </thead>
                  <tbody>
                    {spreads.map((s, idx) => (
                      <tr key={idx} className="border-b border-zinc-800/50 hover:bg-zinc-800/50">
                        <td className="px-4 py-3"><span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium border ${TYPE_COLORS[s.type]?.bg} ${TYPE_COLORS[s.type]?.text} ${TYPE_COLORS[s.type]?.border}`}>{TYPE_LABELS[s.type] || s.type}</span></td>
                        <td className="px-4 py-3 text-sm text-zinc-300">{s.underlying}</td>
                        <td className="px-4 py-3 text-sm text-white font-mono">{s.strike}</td>
                        <td className="px-4 py-3 text-sm text-right text-zinc-300 font-mono">{s.avgWeeklyIV?.toFixed(1)}%</td>
                        <td className="px-4 py-3 text-sm text-right text-zinc-300 font-mono">{s.avgMonthlyIV?.toFixed(1)}%</td>
                        <td className={`px-4 py-3 text-sm text-right font-mono font-medium ${s.ivRatio > 1 ? 'text-orange-400' : 'text-cyan-400'}`}>{s.ivRatio?.toFixed(2)}x</td>
                        <td className={`px-4 py-3 text-sm text-right font-mono font-bold ${(s.edgeAfterCosts || 0) > 0 ? 'text-emerald-400' : 'text-red-400'}`}>₹{s.edgeAfterCosts?.toFixed(0)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      )}

      {activeTab === 'volsurface' && volData && (
        <>
          <div className="flex items-center gap-2">
            <select value={volUnderlying} onChange={e => setVolUnderlying(e.target.value)}
              className="bg-zinc-800 text-white border border-zinc-700 rounded-lg px-3 py-2 text-sm">
              {['NIFTY', 'BANKNIFTY', 'MIDCPNIFTY', 'FINNIFTY'].map(u => <option key={u} value={u}>{u}</option>)}
            </select>
            <button onClick={() => volRefetch()} disabled={volLoading}
              className="px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded-lg text-sm font-medium disabled:opacity-50">
              {volLoading ? 'Loading...' : 'Refresh'}
            </button>
          </div>
          {volData.summary && (
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
              <StatCard label="Avg Weekly IV" value={`${volData.summary.avgWeeklyIV?.toFixed(1)}%`} color="text-blue-400" />
              <StatCard label="Avg Monthly IV" value={`${volData.summary.avgMonthlyIV?.toFixed(1)}%`} color="text-purple-400" />
              <StatCard label="IV vs RV" value={`${volData.summary.ivPremium?.toFixed(0)}%`} color={volData.summary.ivPremium > 0 ? 'text-amber-400' : 'text-cyan-400'} />
              <StatCard label="ATM Skew" value={`${volData.summary.weeklyATMSkew?.toFixed(1)}%`} color={Math.abs(volData.summary.weeklyATMSkew) > 5 ? 'text-orange-400' : 'text-zinc-400'} />
            </div>
          )}
          {volData.surface && (
            <div className="bg-zinc-900 rounded-xl border border-zinc-800 overflow-hidden">
              <div className="px-6 py-4 border-b border-zinc-800">
                <h2 className="text-lg font-semibold text-white">IV Surface — {volData.underlying} (Spot: ₹{volData.spotPrice?.toFixed(0)})</h2>
              </div>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-xs text-zinc-500 uppercase border-b border-zinc-800">
                      <th className="px-4 py-3 text-right">Strike</th>
                      <th className="px-4 py-3 text-right">Moneyness</th>
                      <th className="px-4 py-3 text-right">W CE IV</th>
                      <th className="px-4 py-3 text-right">W PE IV</th>
                      <th className="px-4 py-3 text-right">M CE IV</th>
                      <th className="px-4 py-3 text-right">M PE IV</th>
                    </tr>
                  </thead>
                  <tbody>
                    {volData.surface.map((row, idx) => (
                      <tr key={idx} className={`border-b border-zinc-800/50 ${row.strike === volData.atmStrike ? 'bg-blue-500/10 font-medium' : 'hover:bg-zinc-800/50'}`}>
                        <td className={`px-4 py-2 text-right font-mono ${row.strike === volData.atmStrike ? 'text-blue-400' : 'text-white'}`}>{row.strike}{row.strike === volData.atmStrike ? ' (ATM)' : ''}</td>
                        <td className="px-4 py-2 text-right text-zinc-400 font-mono">{row.moneyness?.toFixed(1)}%</td>
                        <td className="px-4 py-2 text-right font-mono text-emerald-400">{row.weeklyCE_IV?.toFixed(1)}%</td>
                        <td className="px-4 py-2 text-right font-mono text-orange-400">{row.weeklyPE_IV?.toFixed(1)}%</td>
                        <td className="px-4 py-2 text-right font-mono text-cyan-400">{row.monthlyCE_IV?.toFixed(1)}%</td>
                        <td className="px-4 py-2 text-right font-mono text-purple-400">{row.monthlyPE_IV?.toFixed(1)}%</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}

function StatCard({ label, value, color = 'text-white' }) {
  return (
    <div className="bg-zinc-900 rounded-xl border border-zinc-800 p-4">
      <p className="text-xs text-zinc-500 uppercase">{label}</p>
      <p className={`text-2xl font-bold mt-1 ${color}`}>{value}</p>
    </div>
  );
}

function OpportunityDetail({ opp, livePrices }) {
  const [executing, setExecuting] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [execResult, setExecResult] = useState(null);
  const [execError, setExecError] = useState(null);

  const lp = livePrices?.find(p => p.underlying === opp.underlying && p.strike === opp.strike);
  const pnl = computeRunningPnl(opp, livePrices);
  const isParityBreak = opp.type === 'PARITY_BREAK';
  const lotSize = getLotSize(opp.underlying);

  const executeMutation = useMutation({
    mutationFn: async () => {
      setExecuting(true); setExecResult(null); setExecError(null);
      const res = await axios.post(`${API_BASE}/api/option-arbitrage/execute`, null, {
        params: {
          underlying: opp.underlying, strike: opp.strike, action: opp.action,
          cePrice: opp.ceBid || opp.cePrice,
          pePrice: opp.action === 'CONVERSION' ? opp.peBid || opp.pePrice : opp.peAsk || opp.pePrice,
          futPrice: opp.futuresPrice, spotPrice: opp.spotPrice,
        }
      });
      return res.data;
    },
    onSuccess: (data) => { setExecResult(data); setExecuting(false); setConfirming(false); },
    onError: (err) => { setExecError(err.response?.data?.error || err.message); setExecuting(false); setConfirming(false); }
  });

  return (
    <div className="bg-zinc-900 rounded-xl border border-zinc-800 p-6 space-y-4">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold text-white">{TYPE_LABELS[opp.type]} — {opp.underlying} {opp.strike}</h3>
        <div className="flex items-center gap-3">
          <span className={`inline-flex items-center px-3 py-1 rounded-full text-sm font-medium border ${TYPE_COLORS[opp.type]?.bg} ${TYPE_COLORS[opp.type]?.text} ${TYPE_COLORS[opp.type]?.border}`}>{opp.action}</span>
          {isParityBreak && !executing && !execResult && (
            <button onClick={() => setConfirming(true)} disabled={executing}
              className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 text-white rounded-lg text-sm font-bold disabled:opacity-50 transition-colors">
              Execute Trade
            </button>
          )}
        </div>
      </div>

      {opp.description && <p className="text-zinc-300 text-sm">{opp.description}</p>}
      {opp.legs && (
        <div className="bg-zinc-800 rounded-lg p-4">
          <p className="text-xs text-zinc-500 uppercase mb-2">Trade Legs</p>
          <p className="text-white font-mono text-sm">{opp.legs}</p>
        </div>
      )}

      {confirming && (
        <div className="bg-amber-500/10 border border-amber-500/30 rounded-lg p-4 space-y-3">
          <p className="text-amber-400 font-semibold text-sm">Confirm Execution</p>
          <div className="text-sm text-zinc-300 space-y-1">
            <p><span className="text-zinc-500">Action:</span> <span className="text-white font-mono">{opp.action}</span></p>
            <p><span className="text-zinc-500">Underlying:</span> <span className="text-white">{opp.underlying} {opp.strike}</span></p>
            <p><span className="text-zinc-500">Lot Size:</span> <span className="text-white">{lotSize}</span></p>
            <p><span className="text-zinc-500">Edge:</span> <span className="text-emerald-400 font-mono font-bold">₹{opp.edgeAfterCosts?.toFixed(0)}</span></p>
          </div>
          <div className="flex gap-2">
            <button onClick={() => executeMutation.mutate()} className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 text-white rounded-lg text-sm font-bold">Confirm & Execute</button>
            <button onClick={() => setConfirming(false)} className="px-4 py-2 bg-zinc-700 hover:bg-zinc-600 text-white rounded-lg text-sm">Cancel</button>
          </div>
        </div>
      )}

      {executing && (
        <div className="bg-blue-500/10 border border-blue-500/30 rounded-lg p-4 flex items-center gap-3">
          <div className="animate-spin rounded-full h-4 w-4 border-b-2 border-blue-400"></div>
          <p className="text-blue-400 text-sm">Firing 3 orders via Zerodha...</p>
        </div>
      )}

      {execResult && (
        <div className={`rounded-lg p-4 space-y-3 ${execResult.status === 'ok' ? 'bg-emerald-500/10 border border-emerald-500/30' : 'bg-red-500/10 border border-red-500/30'}`}>
          <p className={`font-semibold text-sm ${execResult.status === 'ok' ? 'text-emerald-400' : 'text-red-400'}`}>
            {execResult.status === 'ok' ? 'All Orders Filled' : 'Partial/Failed Execution'}
          </p>
          {execResult.error && <p className="text-red-400 text-xs">{execResult.error}</p>}
          {execResult.legs?.map((leg, i) => (
            <div key={i} className="flex items-center gap-3 text-xs">
              <span className={`w-12 font-mono ${leg.side === 'BUY' ? 'text-emerald-400' : 'text-red-400'}`}>{leg.side}</span>
              <span className="text-white font-mono w-36">{leg.symbol}</span>
              <span className="text-zinc-400">x{leg.quantity}</span>
              <span className="text-zinc-300 font-mono">₹{leg.price?.toFixed(0)}</span>
              <span className={`font-mono ${leg.status === 'COMPLETE' || leg.status === 'OPEN' ? 'text-emerald-400' : 'text-red-400'}`}>{leg.status}</span>
              {leg.orderId && <span className="text-zinc-500 font-mono">#{leg.orderId}</span>}
            </div>
          ))}
        </div>
      )}

      {execError && !execResult && (
        <div className="bg-red-500/10 border border-red-500/30 rounded-lg p-4"><p className="text-red-400 text-sm">{execError}</p></div>
      )}

      <div className="grid grid-cols-2 md:grid-cols-5 gap-4">
        <div><p className="text-xs text-zinc-500">Spot</p><p className="text-white font-mono">₹{opp.spotPrice?.toFixed(2)}</p></div>
        <div><p className="text-xs text-zinc-500">Futures</p><p className="text-white font-mono">₹{opp.futuresPrice?.toFixed(2)}</p></div>
        {lp && <>
          <div><p className="text-xs text-zinc-500">Spot (Live)</p><p className="text-emerald-400 font-mono">₹{lp.spotLive?.toFixed(2) || '--'}</p></div>
          <div><p className="text-xs text-zinc-500">Futures (Live)</p><p className="text-emerald-400 font-mono">₹{lp.futLive?.toFixed(2) || '--'}</p></div>
        </>}
        <div><p className="text-xs text-zinc-500">CE (Entry)</p><p className="text-white font-mono">₹{opp.cePrice?.toFixed(0)}</p></div>
        <div><p className="text-xs text-zinc-500">PE (Entry)</p><p className="text-white font-mono">₹{opp.pePrice?.toFixed(0)}</p></div>
        {lp && <>
          <div><p className="text-xs text-zinc-500">CE (Live)</p><p className="text-emerald-400 font-mono">₹{lp.ceLive?.toFixed(0) || '--'}</p></div>
          <div><p className="text-xs text-zinc-500">PE (Live)</p><p className="text-emerald-400 font-mono">₹{lp.peLive?.toFixed(0) || '--'}</p></div>
        </>}
        <div><p className="text-xs text-zinc-500">DTE</p><p className="text-white font-mono">{opp.daysToExpiry} days</p></div>
        <div><p className="text-xs text-zinc-500">Lot Size</p><p className="text-white font-mono">{lotSize}</p></div>
      </div>

      <div className="flex items-center gap-6 text-sm">
        <div>
          <span className="text-zinc-500">Edge: </span>
          <span className="text-emerald-400 font-mono font-bold">₹{opp.edgeAfterCosts?.toFixed(0)}</span>
        </div>
        {pnl !== null && (
          <div>
            <span className="text-zinc-500">Running P&L: </span>
            <span className={`font-mono font-bold ${pnl >= 0 ? 'text-emerald-400' : 'text-red-400'}`}>₹{pnl.toFixed(0)}</span>
          </div>
        )}
        <div>
          <span className="text-zinc-500">Confidence: </span>
          <span className="text-blue-400 font-mono">{opp.confidence}%</span>
        </div>
        {opp.scanTime && (
          <div>
            <span className="text-zinc-500">Detected: </span>
            <span className="text-zinc-300">{new Date(opp.scanTime).toLocaleString()}</span>
          </div>
        )}
      </div>
    </div>
  );
}
