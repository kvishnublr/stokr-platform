import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import axios from 'axios';

const API_BASE = import.meta.env.VITE_API_URL || '';

const TYPE_COLORS = {
  PARITY_BREAK: { bg: 'bg-emerald-500/20', text: 'text-emerald-400', border: 'border-emerald-500/30' },
  IV_SPIKE: { bg: 'bg-amber-500/20', text: 'text-amber-400', border: 'border-amber-500/30' },
  DEEP_ITM_STALE: { bg: 'bg-blue-500/20', text: 'text-blue-400', border: 'border-blue-500/30' },
  SKEW_ANOMALY: { bg: 'bg-purple-500/20', text: 'text-purple-400', border: 'border-purple-500/30' },
  CALENDAR_INVERSION: { bg: 'bg-orange-500/20', text: 'text-orange-400', border: 'border-orange-500/30' },
  CHEAP_CALENDAR: { bg: 'bg-cyan-500/20', text: 'text-cyan-400', border: 'border-cyan-500/30' },
};

const TYPE_LABELS = {
  PARITY_BREAK: 'Parity Break',
  IV_SPIKE: 'IV Spike',
  DEEP_ITM_STALE: 'Deep ITM Stale',
  SKEW_ANOMALY: 'Skew Anomaly',
  CALENDAR_INVERSION: 'Calendar Inversion',
  CHEAP_CALENDAR: 'Cheap Calendar',
};

const UNDERLYINGS = ['ALL', 'NIFTY', 'BANKNIFTY', 'MIDCPNIFTY', 'FINNIFTY'];

const TABS = [
  { id: 'scan', label: 'Live Scan' },
  { id: 'calendar', label: 'Calendar Spread' },
  { id: 'volsurface', label: 'Vol Surface' },
];

export default function OptionArbitrage() {
  const [autoRefresh, setAutoRefresh] = useState(false);
  const [underlying, setUnderlying] = useState('ALL');
  const [activeTab, setActiveTab] = useState('scan');
  const [selectedOpportunity, setSelectedOpportunity] = useState(null);
  const [volUnderlying, setVolUnderlying] = useState('NIFTY');

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['option-arbitrage', underlying],
    queryFn: async () => {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/scan`, {
        params: { underlying }
      });
      return res.data;
    },
    refetchInterval: autoRefresh ? 5000 : false,
    staleTime: autoRefresh ? 3000 : 30000,
  });

  const { data: calData, isLoading: calLoading, refetch: calRefetch } = useQuery({
    queryKey: ['calendar-spread', underlying],
    queryFn: async () => {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/calendar-spread`, {
        params: { underlying }
      });
      return res.data;
    },
    enabled: activeTab === 'calendar',
    staleTime: 30000,
  });

  const { data: volData, isLoading: volLoading, refetch: volRefetch } = useQuery({
    queryKey: ['vol-surface', volUnderlying],
    queryFn: async () => {
      const res = await axios.get(`${API_BASE}/api/option-arbitrage/vol-surface`, {
        params: { underlying: volUnderlying }
      });
      return res.data;
    },
    enabled: activeTab === 'volsurface',
    staleTime: 30000,
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
  const totalEdge = opportunities.reduce((sum, o) => sum + (o.edgeAfterCosts || 0), 0);
  const spreads = calData?.spreads || [];

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-white">Option Arb Scanner</h1>
          <p className="text-sm text-zinc-400 mt-1">
            Parity breaks, IV spikes, calendar spreads, volatility surface analytics
          </p>
        </div>
        <div className="flex items-center gap-3">
          <div className={`px-3 py-1 rounded-full text-xs font-medium ${
            health?.scannerReady ? 'bg-emerald-500/20 text-emerald-400' : 'bg-red-500/20 text-red-400'
          }`}>
            {health?.scannerReady ? 'Ready' : 'Offline'}
          </div>
        </div>
      </div>

      {/* Tabs */}
      <div className="flex bg-zinc-800 rounded-lg p-1 w-fit">
        {TABS.map(tab => (
          <button
            key={tab.id}
            onClick={() => setActiveTab(tab.id)}
            className={`px-4 py-2 rounded-md text-sm font-medium transition-colors ${
              activeTab === tab.id ? 'bg-blue-600 text-white' : 'text-zinc-400 hover:text-white'
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Underlying selector + controls */}
      <div className="flex items-center gap-4">
        <div className="flex bg-zinc-800 rounded-lg p-1">
          {UNDERLYINGS.map(opt => (
            <button
              key={opt}
              onClick={() => setUnderlying(opt)}
              className={`px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${
                underlying === opt ? 'bg-blue-600 text-white' : 'text-zinc-400 hover:text-white'
              }`}
            >
              {opt === 'ALL' ? 'All' : opt}
            </button>
          ))}
        </div>

        {activeTab === 'scan' && (
          <>
            <button
              onClick={() => refetch()}
              disabled={isLoading}
              className="px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded-lg text-sm font-medium disabled:opacity-50"
            >
              {isLoading ? 'Scanning...' : 'Scan Now'}
            </button>
            <button
              onClick={() => setAutoRefresh(!autoRefresh)}
              className={`px-4 py-2 rounded-lg text-sm font-medium transition-colors ${
                autoRefresh ? 'bg-emerald-600 text-white' : 'bg-zinc-700 text-zinc-300 hover:bg-zinc-600'
              }`}
            >
              {autoRefresh ? 'Auto: ON' : 'Auto: OFF'}
            </button>
          </>
        )}

        {activeTab === 'calendar' && (
          <button
            onClick={() => calRefetch()}
            disabled={calLoading}
            className="px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded-lg text-sm font-medium disabled:opacity-50"
          >
            {calLoading ? 'Scanning...' : 'Scan Calendar Spreads'}
          </button>
        )}

        {activeTab === 'volsurface' && (
          <div className="flex items-center gap-2">
            <select
              value={volUnderlying}
              onChange={e => setVolUnderlying(e.target.value)}
              className="bg-zinc-800 text-white border border-zinc-700 rounded-lg px-3 py-2 text-sm"
            >
              {['NIFTY', 'BANKNIFTY', 'MIDCPNIFTY', 'FINNIFTY'].map(u => (
                <option key={u} value={u}>{u}</option>
              ))}
            </select>
            <button
              onClick={() => volRefetch()}
              disabled={volLoading}
              className="px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded-lg text-sm font-medium disabled:opacity-50"
            >
              {volLoading ? 'Loading...' : 'Refresh'}
            </button>
          </div>
        )}
      </div>

      {error && (
        <div className="bg-red-500/10 border border-red-500/30 rounded-lg p-4 text-red-400 text-sm">
          Error: {error.message}
        </div>
      )}

      {/* Tab Content */}
      {activeTab === 'scan' && (
        <>
          <div className="grid grid-cols-2 md:grid-cols-5 gap-4">
            <StatCard label="Total Opportunities" value={opportunities.length}
              color={opportunities.length > 0 ? 'text-emerald-400' : 'text-zinc-400'} />
            <StatCard label="Total Edge" value={`₹${totalEdge.toFixed(0)}`}
              color={totalEdge > 0 ? 'text-emerald-400' : 'text-zinc-400'} />
            <StatCard label="Parity Breaks" value={summary.PARITY_BREAK || 0} color="text-emerald-400" />
            <StatCard label="IV Spikes" value={summary.IV_SPIKE || 0} color="text-amber-400" />
            <StatCard label="Skew / Deep ITM"
              value={(summary.SKEW_ANOMALY || 0) + (summary.DEEP_ITM_STALE || 0)} color="text-blue-400" />
          </div>

          <div className="bg-zinc-900 rounded-xl border border-zinc-800 overflow-hidden">
            <div className="px-6 py-4 border-b border-zinc-800">
              <h2 className="text-lg font-semibold text-white">Opportunities ({opportunities.length})</h2>
            </div>
            {opportunities.length === 0 ? (
              <div className="px-6 py-12 text-center text-zinc-500">
                <p className="text-lg">No mispricings detected</p>
                {data?.timestamp && (
                  <p className="text-xs mt-2">Last scan: {new Date(data.timestamp).toLocaleTimeString()}</p>
                )}
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
                      <th className="px-4 py-3 text-right">Conf</th>
                      <th className="px-4 py-3 text-right">DTE</th>
                    </tr>
                  </thead>
                  <tbody>
                    {opportunities.map((opp, idx) => (
                      <tr
                        key={idx}
                        onClick={() => setSelectedOpportunity(selectedOpportunity === idx ? null : idx)}
                        className={`border-b border-zinc-800/50 cursor-pointer transition-colors ${
                          selectedOpportunity === idx ? 'bg-zinc-800' : 'hover:bg-zinc-800/50'
                        }`}
                      >
                        <td className="px-4 py-3">
                          <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium border ${
                            TYPE_COLORS[opp.type]?.bg || 'bg-zinc-500/20'
                          } ${TYPE_COLORS[opp.type]?.text || 'text-zinc-400'} ${
                            TYPE_COLORS[opp.type]?.border || 'border-zinc-500/30'
                          }`}>
                            {TYPE_LABELS[opp.type] || opp.type}
                          </span>
                        </td>
                        <td className="px-4 py-3 text-sm text-zinc-300 font-medium">{opp.underlying}</td>
                        <td className="px-4 py-3 text-sm text-white font-mono">{opp.strike}</td>
                        <td className="px-4 py-3 text-xs text-zinc-400">{opp.action}</td>
                        <td className="px-4 py-3 text-sm text-right text-zinc-300 font-mono">₹{opp.cePrice?.toFixed(0)}</td>
                        <td className="px-4 py-3 text-sm text-right text-zinc-300 font-mono">₹{opp.pePrice?.toFixed(0)}</td>
                        <td className={`px-4 py-3 text-sm text-right font-mono font-medium ${
                          opp.edgePoints > 0 ? 'text-emerald-400' : 'text-red-400'
                        }`}>
                          {opp.edgePoints > 0 ? '+' : ''}{opp.edgePoints?.toFixed(1)}
                        </td>
                        <td className={`px-4 py-3 text-sm text-right font-mono font-bold ${
                          opp.edgeAfterCosts > 0 ? 'text-emerald-400' : 'text-red-400'
                        }`}>
                          ₹{opp.edgeAfterCosts?.toFixed(0)}
                        </td>
                        <td className="px-4 py-3 text-right">
                          <span className="text-xs text-zinc-400">{opp.confidence}%</span>
                        </td>
                        <td className="px-4 py-3 text-sm text-right text-zinc-400">{opp.daysToExpiry?.toFixed(0)}d</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          {selectedOpportunity !== null && opportunities[selectedOpportunity] && (
            <OpportunityDetail opp={opportunities[selectedOpportunity]} />
          )}
        </>
      )}

      {activeTab === 'calendar' && (
        <>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
            <StatCard label="Calendar Spreads" value={spreads.length}
              color={spreads.length > 0 ? 'text-orange-400' : 'text-zinc-400'} />
            <StatCard label="Inversions"
              value={spreads.filter(s => s.type === 'CALENDAR_INVERSION').length} color="text-orange-400" />
            <StatCard label="Cheap Calendars"
              value={spreads.filter(s => s.type === 'CHEAP_CALENDAR').length} color="text-cyan-400" />
            <StatCard label="Total Edge"
              value={`₹${spreads.reduce((s, r) => s + (r.edgeAfterCosts || 0), 0).toFixed(0)}`}
              color="text-orange-400" />
          </div>

          <div className="bg-zinc-900 rounded-xl border border-zinc-800 overflow-hidden">
            <div className="px-6 py-4 border-b border-zinc-800">
              <h2 className="text-lg font-semibold text-white">Calendar Spread Opportunities ({spreads.length})</h2>
            </div>
            {spreads.length === 0 ? (
              <div className="px-6 py-12 text-center text-zinc-500">
                <p className="text-lg">No calendar spread opportunities</p>
                <p className="text-sm mt-1">Detects term structure inversions between weekly and monthly expiries</p>
              </div>
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
                      <th className="px-4 py-3 text-right">Conf</th>
                    </tr>
                  </thead>
                  <tbody>
                    {spreads.map((s, idx) => (
                      <tr key={idx} className="border-b border-zinc-800/50 hover:bg-zinc-800/50">
                        <td className="px-4 py-3">
                          <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium border ${
                            TYPE_COLORS[s.type]?.bg} ${TYPE_COLORS[s.type]?.text} ${TYPE_COLORS[s.type]?.border}`}>
                            {TYPE_LABELS[s.type] || s.type}
                          </span>
                        </td>
                        <td className="px-4 py-3 text-sm text-zinc-300">{s.underlying}</td>
                        <td className="px-4 py-3 text-sm text-white font-mono">{s.strike}</td>
                        <td className="px-4 py-3 text-sm text-right text-zinc-300 font-mono">{s.avgWeeklyIV?.toFixed(1)}%</td>
                        <td className="px-4 py-3 text-sm text-right text-zinc-300 font-mono">{s.avgMonthlyIV?.toFixed(1)}%</td>
                        <td className={`px-4 py-3 text-sm text-right font-mono font-medium ${
                          s.ivRatio > 1 ? 'text-orange-400' : 'text-cyan-400'
                        }`}>{s.ivRatio?.toFixed(2)}x</td>
                        <td className={`px-4 py-3 text-sm text-right font-mono font-bold ${
                          (s.edgeAfterCosts || 0) > 0 ? 'text-emerald-400' : 'text-red-400'
                        }`}>₹{s.edgeAfterCosts?.toFixed(0)}</td>
                        <td className="px-4 py-3 text-right">
                          <span className="text-xs text-zinc-400">{s.confidence}%</span>
                        </td>
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
          {volData.summary && (
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
              <StatCard label="Avg Weekly IV" value={`${volData.summary.avgWeeklyIV?.toFixed(1)}%`} color="text-blue-400" />
              <StatCard label="Avg Monthly IV" value={`${volData.summary.avgMonthlyIV?.toFixed(1)}%`} color="text-purple-400" />
              <StatCard label="IV vs RV" value={`${volData.summary.ivPremium?.toFixed(0)}%`}
                color={volData.summary.ivPremium > 0 ? 'text-amber-400' : 'text-cyan-400'} />
              <StatCard label="ATM Skew" value={`${volData.summary.weeklyATMSkew?.toFixed(1)}%`}
                color={Math.abs(volData.summary.weeklyATMSkew) > 5 ? 'text-orange-400' : 'text-zinc-400'} />
            </div>
          )}

          {volData.summary && (
            <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
              <SignalCard label="Vol Signal" signal={volData.summary.volSignal} note={volData.summary.volNote} />
              <SignalCard label="Skew Signal" signal={volData.summary.skewSignal} note={volData.summary.skewNote} />
              <SignalCard label="Term Structure" signal={volData.summary.termSignal} note={volData.summary.termNote} />
            </div>
          )}

          {volData.surface && (
            <div className="bg-zinc-900 rounded-xl border border-zinc-800 overflow-hidden">
              <div className="px-6 py-4 border-b border-zinc-800">
                <h2 className="text-lg font-semibold text-white">
                  IV Surface — {volData.underlying} (Spot: ₹{volData.spotPrice?.toFixed(0)})
                </h2>
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
                      <tr key={idx}
                        className={`border-b border-zinc-800/50 ${
                          row.strike === volData.atmStrike ? 'bg-blue-500/10 font-medium' : 'hover:bg-zinc-800/50'
                        }`}
                      >
                        <td className={`px-4 py-2 text-right font-mono ${
                          row.strike === volData.atmStrike ? 'text-blue-400' : 'text-white'
                        }`}>{row.strike}{row.strike === volData.atmStrike ? ' (ATM)' : ''}</td>
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

function SignalCard({ label, signal, note }) {
  const colors = {
    IV_RICH: 'border-amber-500/30 bg-amber-500/10',
    IV_CHEAP: 'border-cyan-500/30 bg-cyan-500/10',
    FAIR: 'border-zinc-500/30 bg-zinc-800',
    PUT_SKEW_HIGH: 'border-orange-500/30 bg-orange-500/10',
    CALL_SKEW_HIGH: 'border-purple-500/30 bg-purple-500/10',
    NEUTRAL: 'border-zinc-500/30 bg-zinc-800',
    NORMAL_CONTANGO: 'border-emerald-500/30 bg-emerald-500/10',
    INVERTED: 'border-red-500/30 bg-red-500/10',
    FLAT: 'border-zinc-500/30 bg-zinc-800',
  };
  const textColors = {
    IV_RICH: 'text-amber-400', IV_CHEAP: 'text-cyan-400', FAIR: 'text-zinc-400',
    PUT_SKEW_HIGH: 'text-orange-400', CALL_SKEW_HIGH: 'text-purple-400', NEUTRAL: 'text-zinc-400',
    NORMAL_CONTANGO: 'text-emerald-400', INVERTED: 'text-red-400', FLAT: 'text-zinc-400',
  };

  return (
    <div className={`rounded-xl border p-4 ${colors[signal] || 'border-zinc-500/30 bg-zinc-800'}`}>
      <p className="text-xs text-zinc-500 uppercase">{label}</p>
      <p className={`text-sm font-semibold mt-1 ${textColors[signal] || 'text-zinc-400'}`}>{signal || 'N/A'}</p>
      <p className="text-xs text-zinc-400 mt-1">{note || ''}</p>
    </div>
  );
}

function OpportunityDetail({ opp }) {
  const TYPE_COLORS_DET = {
    PARITY_BREAK: 'border-emerald-500/30 bg-emerald-500/10',
    IV_SPIKE: 'border-amber-500/30 bg-amber-500/10',
    DEEP_ITM_STALE: 'border-blue-500/30 bg-blue-500/10',
    SKEW_ANOMALY: 'border-purple-500/30 bg-purple-500/10',
  };
  return (
    <div className="bg-zinc-900 rounded-xl border border-zinc-800 p-6 space-y-4">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold text-white">
          {TYPE_LABELS[opp.type]} — {opp.underlying} {opp.strike}
        </h3>
        <span className={`inline-flex items-center px-3 py-1 rounded-full text-sm font-medium border ${
          TYPE_COLORS[opp.type]?.bg} ${TYPE_COLORS[opp.type]?.text} ${TYPE_COLORS[opp.type]?.border}`}>
          {opp.action}
        </span>
      </div>
      <p className="text-zinc-300 text-sm">{opp.description}</p>
      <div className="bg-zinc-800 rounded-lg p-4">
        <p className="text-xs text-zinc-500 uppercase mb-2">Trade Legs</p>
        <p className="text-white font-mono text-sm">{opp.legs}</p>
      </div>
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <div>
          <p className="text-xs text-zinc-500">Spot</p>
          <p className="text-white font-mono">₹{opp.spotPrice?.toFixed(2)}</p>
        </div>
        <div>
          <p className="text-xs text-zinc-500">Futures</p>
          <p className="text-white font-mono">₹{opp.futuresPrice?.toFixed(2)}</p>
        </div>
        <div>
          <p className="text-xs text-zinc-500">CE (Bid/Ask)</p>
          <p className="text-white font-mono">₹{opp.ceBid?.toFixed(0)} / ₹{opp.ceAsk?.toFixed(0)}</p>
        </div>
        <div>
          <p className="text-xs text-zinc-500">PE (Bid/Ask)</p>
          <p className="text-white font-mono">₹{opp.peBid?.toFixed(0)} / ₹{opp.peAsk?.toFixed(0)}</p>
        </div>
      </div>
      <div className="flex items-center gap-6 text-sm">
        <div>
          <span className="text-zinc-500">Edge: </span>
          <span className="text-emerald-400 font-mono font-bold">₹{opp.edgeAfterCosts?.toFixed(0)}</span>
        </div>
        <div>
          <span className="text-zinc-500">Confidence: </span>
          <span className="text-blue-400 font-mono">{opp.confidence}%</span>
        </div>
        <div>
          <span className="text-zinc-500">Detected: </span>
          <span className="text-zinc-300">{opp.detectedAt ? new Date(opp.detectedAt).toLocaleTimeString() : '-'}</span>
        </div>
      </div>
    </div>
  );
}
