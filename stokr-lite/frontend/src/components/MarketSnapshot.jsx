import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import client from '../api/client';

export default function MarketSnapshot() {
  const [viewMode, setViewMode] = useState('FULL'); // 'FULL' or 'COMPACT'
  const [activeTab, setActiveTab] = useState('GAINERS'); // 'GAINERS', 'LOSERS', 'SECTORS'

  const { data: snapshot, isLoading } = useQuery({
    queryKey: ['marketSnapshot'],
    queryFn: () => client.get('/market/snapshot').then((r) => r.data),
    refetchInterval: 10000,
    staleTime: 5000,
  });

  if (isLoading || !snapshot) {
    return (
      <div className="bg-white/80 rounded-3xl p-5 mb-7 border border-indigo-100 shadow-md animate-pulse w-full">
        <div className="h-7 bg-slate-100 rounded-xl w-1/4 mb-4"></div>
        <div className="grid grid-cols-5 gap-3">
          {[1, 2, 3, 4, 5].map((i) => (
            <div key={i} className="h-24 bg-slate-100 rounded-2xl"></div>
          ))}
        </div>
      </div>
    );
  }

  const {
    isOpen,
    marketStatus,
    regimeLabel,
    sentimentScore,
    indiaVix,
    indices = [],
    breadth = {},
    institutionalFlows = {},
    derivatives = {},
    topGainers = [],
    topLosers = [],
    sectorPerformance = [],
    newsBulletins = [],
  } = snapshot;

  return (
    <div className="bg-gradient-to-b from-white via-indigo-50/20 to-slate-50/30 rounded-3xl p-5 mb-7 border border-indigo-100/90 shadow-xl shadow-indigo-500/5 w-full relative overflow-hidden transition-all backdrop-blur-xl">
      {/* Subtle Ambient Decorative Glows */}
      <div className="absolute top-0 right-0 w-96 h-96 bg-indigo-500/5 rounded-full blur-3xl pointer-events-none"></div>
      <div className="absolute bottom-0 left-0 w-96 h-96 bg-emerald-500/5 rounded-full blur-3xl pointer-events-none"></div>

      {/* Header Bar */}
      <div className="flex flex-wrap items-center justify-between gap-3 pb-3.5 mb-4 border-b border-indigo-100/80">
        <div className="flex flex-wrap items-center gap-3">
          {/* Live Market Status Pill */}
          <div className="flex items-center gap-2 bg-white border border-slate-200/90 px-3.5 py-1.5 rounded-2xl shadow-xs">
            <div className="relative flex h-2.5 w-2.5">
              <span
                className={`animate-ping absolute inline-flex h-full w-full rounded-full ${
                  isOpen ? 'bg-emerald-400' : 'bg-amber-400'
                } opacity-75`}
              ></span>
              <span
                className={`relative inline-flex rounded-full h-2.5 w-2.5 ${
                  isOpen ? 'bg-emerald-500' : 'bg-amber-500'
                }`}
              ></span>
            </div>
            <span className="text-xs font-black tracking-wider uppercase text-slate-800">
              {marketStatus}
            </span>
          </div>

          {/* Market Bias / Regime Badge */}
          <div className="flex items-center gap-2 bg-gradient-to-r from-indigo-600 to-purple-600 text-white px-3.5 py-1.5 rounded-2xl shadow-sm text-xs font-black">
            <span>{regimeLabel || '🐂 Bullish Momentum'}</span>
            <span className="text-[10px] bg-white/20 text-white px-2 py-0.5 rounded-full font-mono font-bold">
              Score: {sentimentScore}/100
            </span>
          </div>

          {/* India VIX Badge */}
          <div className="flex items-center gap-2 bg-purple-50/90 border border-purple-200/80 px-3.5 py-1.5 rounded-2xl text-xs font-extrabold text-purple-900 shadow-2xs">
            <span className="text-purple-700">India VIX:</span>
            <span className="font-mono text-sm font-black text-slate-900">{indiaVix?.value?.toFixed(2)}</span>
            <span
              className={`text-[10px] font-black px-1.5 py-0.2 rounded-full ${
                indiaVix?.change < 0 ? 'bg-emerald-100 text-emerald-800' : 'bg-rose-100 text-rose-800'
              }`}
            >
              {indiaVix?.change >= 0 ? '+' : ''}
              {indiaVix?.percentChange?.toFixed(2)}%
            </span>
            <span className="text-[10px] text-purple-600 font-bold hidden sm:inline">({indiaVix?.status})</span>
          </div>
        </div>

        {/* View Mode Switcher */}
        <div className="flex items-center gap-1 bg-slate-100 p-1.5 rounded-2xl border border-slate-200">
          <button
            onClick={() => setViewMode('FULL')}
            className={`px-3.5 py-1.5 rounded-xl text-[11px] font-black transition-all ${
              viewMode === 'FULL'
                ? 'bg-indigo-600 text-white shadow-md'
                : 'text-slate-600 hover:text-slate-900'
            }`}
          >
            📊 Crystal Market Intel
          </button>
          <button
            onClick={() => setViewMode('COMPACT')}
            className={`px-3.5 py-1.5 rounded-xl text-[11px] font-black transition-all ${
              viewMode === 'COMPACT'
                ? 'bg-indigo-600 text-white shadow-md'
                : 'text-slate-600 hover:text-slate-900'
            }`}
          >
            ⚡ Minimal Ticker
          </button>
        </div>
      </div>

      {viewMode === 'COMPACT' ? (
        /* Marquee Ticker Bar */
        <div className="relative overflow-hidden py-1 w-full">
          <div className="flex gap-4 animate-marquee whitespace-nowrap">
            {[...indices, ...indices].map((idx, i) => (
              <div
                key={i}
                className="inline-flex items-center gap-2.5 bg-white border border-slate-200/90 px-4 py-2 rounded-2xl shadow-xs"
              >
                <span className="text-xs font-black text-slate-800">{idx.label}</span>
                <span className="font-mono text-xs font-black text-slate-900">
                  ₹{idx.price?.toLocaleString('en-IN')}
                </span>
                <span
                  className={`text-[11px] font-black px-2 py-0.5 rounded-full ${
                    idx.isUp ? 'bg-emerald-100 text-emerald-800' : 'bg-rose-100 text-rose-800'
                  }`}
                >
                  {idx.isUp ? '▲' : '▼'} {idx.isUp ? '+' : ''}
                  {idx.change} ({idx.isUp ? '+' : ''}
                  {idx.percentChange}%)
                </span>
              </div>
            ))}
          </div>
        </div>
      ) : (
        /* Full Market Intelligence Deck */
        <div className="space-y-4 w-full">
          {/* Index Cards Grid (Full 5 Column Width) */}
          <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-5 gap-3 w-full">
            {indices.map((idx) => {
              const rangePct =
                idx.high && idx.low && idx.high !== idx.low
                  ? Math.min(100, Math.max(0, ((idx.price - idx.low) / (idx.high - idx.low)) * 100))
                  : 50;

              return (
                <div
                  key={idx.symbol}
                  className="bg-white border border-slate-200/90 rounded-2xl p-3.5 hover:border-indigo-400 hover:shadow-lg hover:shadow-indigo-500/10 transition-all duration-300 group relative overflow-hidden"
                >
                  <div className="flex items-center justify-between mb-1.5">
                    <span className="text-xs font-black text-slate-700 uppercase tracking-tight group-hover:text-indigo-600 transition">
                      {idx.label}
                    </span>
                    <span
                      className={`text-[10px] font-black px-2 py-0.5 rounded-full shadow-2xs ${
                        idx.isUp
                          ? 'bg-emerald-500 text-white'
                          : 'bg-rose-500 text-white'
                      }`}
                    >
                      {idx.isUp ? '▲' : '▼'} {idx.isUp ? '+' : ''}
                      {idx.percentChange}%
                    </span>
                  </div>

                  <div className="flex items-baseline gap-1 my-1">
                    <span className="text-xs font-bold text-slate-400 font-mono">₹</span>
                    <span className="font-mono text-lg font-black text-slate-900 tracking-tight">
                      {Number(idx.price || 0).toLocaleString('en-IN', { minimumFractionDigits: 2 })}
                    </span>
                  </div>

                  {/* Range Slider Bar */}
                  <div className="space-y-1 mt-2">
                    <div className="flex justify-between text-[9px] font-mono text-slate-500 font-bold">
                      <span>L: {idx.low?.toLocaleString('en-IN')}</span>
                      <span>H: {idx.high?.toLocaleString('en-IN')}</span>
                    </div>
                    <div className="h-2 w-full bg-slate-100 rounded-full overflow-hidden relative p-0.5 border border-slate-200/60">
                      <div
                        className={`h-full rounded-full transition-all duration-500 shadow-xs ${
                          idx.isUp
                            ? 'bg-gradient-to-r from-emerald-500 to-teal-400'
                            : 'bg-gradient-to-r from-rose-500 to-amber-500'
                        }`}
                        style={{ width: `${rangePct}%` }}
                      ></div>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>

          {/* 3-Column Command Deck: Institutional Flows + Breadth + Options Data */}
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-3.5 w-full">
            {/* 1. Institutional Flows */}
            <div className="bg-white border border-slate-200/90 rounded-2xl p-4 space-y-3 shadow-xs">
              <div className="flex items-center justify-between border-b border-slate-100 pb-2.5">
                <span className="text-xs font-black text-slate-800 uppercase tracking-wider flex items-center gap-1.5">
                  <span className="text-base">🏦</span> Institutional Money Flow
                </span>
                <span className="text-[10px] font-black text-emerald-900 bg-emerald-100 border border-emerald-300 px-2.5 py-0.5 rounded-full shadow-2xs">
                  {institutionalFlows.bias || 'BUYING 🟢'}
                </span>
              </div>

              <div className="grid grid-cols-2 gap-2.5 text-xs">
                <div className="bg-gradient-to-br from-emerald-50/80 to-teal-50/40 p-3 rounded-xl border border-emerald-200/80 shadow-2xs">
                  <div className="text-[10px] font-black text-emerald-900 uppercase tracking-wider">FII Net Cash</div>
                  <div className="font-mono font-black text-base text-emerald-700 mt-0.5">
                    +₹{institutionalFlows.fiiNetCash?.toLocaleString('en-IN')} Cr
                  </div>
                </div>

                <div className="bg-gradient-to-br from-emerald-50/80 to-teal-50/40 p-3 rounded-xl border border-emerald-200/80 shadow-2xs">
                  <div className="text-[10px] font-black text-emerald-900 uppercase tracking-wider">DII Net Cash</div>
                  <div className="font-mono font-black text-base text-emerald-700 mt-0.5">
                    +₹{institutionalFlows.diiNetCash?.toLocaleString('en-IN')} Cr
                  </div>
                </div>
              </div>
            </div>

            {/* 2. Market Breadth Meter */}
            <div className="bg-white border border-slate-200/90 rounded-2xl p-4 space-y-3 shadow-xs">
              <div className="flex items-center justify-between border-b border-slate-100 pb-2.5">
                <span className="text-xs font-black text-slate-800 uppercase tracking-wider flex items-center gap-1.5">
                  <span className="text-base">⚖️</span> Market Breadth Gauge
                </span>
                <span className="text-[10px] font-black text-indigo-900 bg-indigo-100 border border-indigo-200 px-2.5 py-0.5 rounded-full shadow-2xs">
                  A/D Ratio: {breadth.adRatio}
                </span>
              </div>

              <div className="space-y-2">
                <div className="flex justify-between text-xs font-black font-mono">
                  <span className="text-emerald-700">🟢 Advances: {breadth.advances} ({breadth.advancePct}%)</span>
                  <span className="text-rose-700">🔴 Declines: {breadth.declines}</span>
                </div>

                <div className="h-3 w-full bg-slate-100 rounded-full overflow-hidden flex p-0.5 border border-slate-200/80">
                  <div
                    className="bg-gradient-to-r from-emerald-500 to-teal-400 h-full rounded-l-full"
                    style={{ width: `${breadth.advancePct}%` }}
                  ></div>
                  <div
                    className="bg-gradient-to-r from-rose-500 to-red-600 h-full rounded-r-full"
                    style={{ width: `${100 - breadth.advancePct}%` }}
                  ></div>
                </div>

                <div className="text-[10px] text-slate-500 font-extrabold text-center">
                  Market Structure: <strong className="text-emerald-700 font-black">{breadth.bias}</strong>
                </div>
              </div>
            </div>

            {/* 3. Options & Derivatives Structure */}
            <div className="bg-white border border-slate-200/90 rounded-2xl p-4 space-y-3 shadow-xs">
              <div className="flex items-center justify-between border-b border-slate-100 pb-2.5">
                <span className="text-xs font-black text-slate-800 uppercase tracking-wider flex items-center gap-1.5">
                  <span className="text-base">🎯</span> Options Derivatives Intel
                </span>
                <span className="text-[10px] font-black text-purple-900 bg-purple-100 border border-purple-200 px-2.5 py-0.5 rounded-full shadow-2xs">
                  PCR: {derivatives.niftyPcr}
                </span>
              </div>

              <div className="grid grid-cols-3 gap-2 text-center text-xs">
                <div className="bg-indigo-50/70 p-2.5 rounded-xl border border-indigo-100">
                  <div className="text-[9px] text-indigo-900 font-black uppercase">Max Pain</div>
                  <div className="font-mono font-black text-indigo-950 text-sm mt-0.5">
                    {derivatives.niftyMaxPain}
                  </div>
                </div>

                <div className="bg-purple-50/70 p-2.5 rounded-xl border border-purple-100">
                  <div className="text-[9px] text-purple-900 font-black uppercase">ATM IV</div>
                  <div className="font-mono font-black text-purple-950 text-sm mt-0.5">
                    {derivatives.niftyAtmIv}%
                  </div>
                </div>

                <div className="bg-amber-50/70 p-2.5 rounded-xl border border-amber-100">
                  <div className="text-[9px] text-amber-900 font-black uppercase">IV Rank</div>
                  <div className="font-mono font-black text-amber-950 text-sm mt-0.5">
                    {derivatives.ivRank}%
                  </div>
                </div>
              </div>
            </div>
          </div>

          {/* Top 10 Leaders & Sector Heatmap Strip */}
          <div className="bg-white border border-slate-200/90 rounded-2xl p-4 space-y-3 w-full shadow-xs">
            <div className="flex items-center justify-between border-b border-slate-100 pb-2.5">
              <span className="text-xs font-black text-slate-800 uppercase tracking-wider flex items-center gap-1.5">
                <span>🔥</span> Top Movers & Sectoral Breakdown
              </span>

              <div className="flex items-center gap-1.5 bg-slate-100 p-1 rounded-xl">
                <button
                  onClick={() => setActiveTab('GAINERS')}
                  className={`px-3.5 py-1 rounded-lg text-[10px] font-black transition ${
                    activeTab === 'GAINERS'
                      ? 'bg-emerald-600 text-white shadow-xs'
                      : 'text-slate-600 hover:text-slate-900'
                  }`}
                >
                  🚀 Top Gainers (5)
                </button>

                <button
                  onClick={() => setActiveTab('LOSERS')}
                  className={`px-3.5 py-1 rounded-lg text-[10px] font-black transition ${
                    activeTab === 'LOSERS'
                      ? 'bg-rose-600 text-white shadow-xs'
                      : 'text-slate-600 hover:text-slate-900'
                  }`}
                >
                  🔻 Top Losers (5)
                </button>

                <button
                  onClick={() => setActiveTab('SECTORS')}
                  className={`px-3.5 py-1 rounded-lg text-[10px] font-black transition ${
                    activeTab === 'SECTORS'
                      ? 'bg-indigo-600 text-white shadow-xs'
                      : 'text-slate-600 hover:text-slate-900'
                  }`}
                >
                  🏢 Sector Heatmap
                </button>
              </div>
            </div>

            {/* Tab Content */}
            {activeTab === 'GAINERS' && (
              <div className="grid grid-cols-2 md:grid-cols-5 gap-2.5 w-full">
                {topGainers.map((stk) => (
                  <div
                    key={stk.symbol}
                    className="bg-emerald-50/80 border border-emerald-200/90 p-3 rounded-xl flex flex-col justify-between hover:shadow-md transition"
                  >
                    <div className="flex items-center justify-between mb-1">
                      <div className="flex items-center gap-1.5">
                        <div className="w-5 h-5 rounded-full bg-emerald-600 text-white font-black text-[9px] flex items-center justify-center">
                          {stk.symbol.substring(0, 2)}
                        </div>
                        <span className="font-black text-xs text-slate-900">{stk.symbol}</span>
                      </div>
                      <span className="text-[10px] bg-emerald-600 text-white px-2 py-0.5 rounded-full font-black shadow-2xs">
                        +{stk.percentChange}%
                      </span>
                    </div>
                    <div className="font-mono text-sm font-black text-slate-800 mt-1">₹{stk.price}</div>
                    <div className="text-[9px] text-slate-500 font-extrabold uppercase tracking-wide mt-1">{stk.sector}</div>
                  </div>
                ))}
              </div>
            )}

            {activeTab === 'LOSERS' && (
              <div className="grid grid-cols-2 md:grid-cols-5 gap-2.5 w-full">
                {topLosers.map((stk) => (
                  <div
                    key={stk.symbol}
                    className="bg-rose-50/80 border border-rose-200/90 p-3 rounded-xl flex flex-col justify-between hover:shadow-md transition"
                  >
                    <div className="flex items-center justify-between mb-1">
                      <div className="flex items-center gap-1.5">
                        <div className="w-5 h-5 rounded-full bg-rose-600 text-white font-black text-[9px] flex items-center justify-center">
                          {stk.symbol.substring(0, 2)}
                        </div>
                        <span className="font-black text-xs text-slate-900">{stk.symbol}</span>
                      </div>
                      <span className="text-[10px] bg-rose-600 text-white px-2 py-0.5 rounded-full font-black shadow-2xs">
                        {stk.percentChange}%
                      </span>
                    </div>
                    <div className="font-mono text-sm font-black text-slate-800 mt-1">₹{stk.price}</div>
                    <div className="text-[9px] text-slate-500 font-extrabold uppercase tracking-wide mt-1">{stk.sector}</div>
                  </div>
                ))}
              </div>
            )}

            {activeTab === 'SECTORS' && (
              <div className="grid grid-cols-2 md:grid-cols-6 gap-2.5 w-full">
                {sectorPerformance.map((sec) => (
                  <div
                    key={sec.name}
                    className="bg-gradient-to-b from-slate-50 to-white border border-slate-200 p-2.5 rounded-xl text-center shadow-2xs hover:border-indigo-300 transition"
                  >
                    <div className="text-[10px] font-black text-slate-600 uppercase tracking-tight">
                      {sec.name}
                    </div>
                    <div
                      className={`font-mono text-sm font-black mt-1 ${
                        sec.change >= 0 ? 'text-emerald-600' : 'text-rose-600'
                      }`}
                    >
                      {sec.change >= 0 ? '+' : ''}
                      {sec.change}%
                    </div>
                    <div className="text-[9px] text-indigo-700 font-black mt-0.5">
                      {sec.status}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* News & Catalyst Bulletins Ticker */}
          {newsBulletins.length > 0 && (
            <div className="bg-gradient-to-r from-slate-900 via-indigo-950 to-slate-900 text-white border border-indigo-800/80 rounded-2xl p-3 flex items-center gap-3.5 w-full shadow-lg">
              <span className="text-[10px] font-black uppercase tracking-wider bg-indigo-600 text-white px-3 py-1 rounded-xl whitespace-nowrap flex items-center gap-1.5 shadow-xs">
                <span>📰</span> MARKET CATALYSTS
              </span>

              <div className="overflow-hidden relative w-full">
                <div className="flex gap-6 animate-marquee whitespace-nowrap text-xs">
                  {newsBulletins.map((n) => (
                    <div key={n.id} className="inline-flex items-center gap-2">
                      <span className="text-[9px] font-black bg-indigo-800 text-amber-300 px-2 py-0.5 rounded-md uppercase tracking-wider">
                        [{n.tag}]
                      </span>
                      <span className="text-slate-100 font-bold">{n.title}</span>
                      <span className="text-[10px] text-indigo-300 font-mono">({n.time})</span>
                      <span className="text-slate-600 font-bold">|</span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
