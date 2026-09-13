import React, { useState, useEffect, useMemo } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import client from '../api/client';

const DAY_TYPE_CONFIG = {
  QUIET: { label: 'Quiet', color: '#10b981', bg: '#ecfdf5', desc: 'Range < 0.4% — Tight Iron Condor' },
  NORMAL: { label: 'Normal', color: '#3b82f6', bg: '#eff6ff', desc: 'Range 0.4-0.7% — Wide Iron Condor' },
  TRENDING: { label: 'Trending', color: '#f59e0b', bg: '#fffbeb', desc: 'Range 0.7-1.2% — One-sided Credit Spread' },
  SPIKE: { label: 'Spike', color: '#ef4444', bg: '#fef2f2', desc: 'Range > 1.2% — Far Spread or Skip' },
  UNKNOWN: { label: 'Building...', color: '#6b7280', bg: '#f9fafb', desc: 'Waiting for range data' },
};

const TREND_ICONS = { BULLISH: '↗', BEARISH: '↘', NEUTRAL: '→' };

function Stat({ label, value, sub, color }) {
  return (
    <div className="bg-white rounded-xl border border-gray-200 p-4 text-center">
      <div className="text-xs text-gray-500 mb-1">{label}</div>
      <div className="text-xl font-bold" style={{ color: color || '#111' }}>{value}</div>
      {sub && <div className="text-xs text-gray-400 mt-1">{sub}</div>}
    </div>
  );
}

function RangeCard({ underlying, data }) {
  const cfg = DAY_TYPE_CONFIG[data?.dayType] || DAY_TYPE_CONFIG.UNKNOWN;
  const trendIcon = TREND_ICONS[data?.trend] || '→';
  const rangePts = data ? Math.round(data.high - data.low) : 0;

  return (
    <div className="bg-white rounded-xl border border-gray-200 p-5 shadow-sm">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <span className="text-lg font-bold text-gray-900">{underlying}</span>
          <span className="text-lg">{trendIcon}</span>
        </div>
        <span className="px-3 py-1 rounded-full text-xs font-semibold"
          style={{ backgroundColor: cfg.bg, color: cfg.color, border: `1px solid ${cfg.color}30` }}>
          {cfg.label}
        </span>
      </div>

      {data ? (
        <>
          <div className="grid grid-cols-4 gap-3 mb-3">
            <div>
              <div className="text-xs text-gray-500">Open</div>
              <div className="font-semibold text-gray-800">{fmt(data.open)}</div>
            </div>
            <div>
              <div className="text-xs text-gray-500">High</div>
              <div className="font-semibold text-green-600">{fmt(data.high)}</div>
            </div>
            <div>
              <div className="text-xs text-gray-500">Low</div>
              <div className="font-semibold text-red-600">{fmt(data.low)}</div>
            </div>
            <div>
              <div className="text-xs text-gray-500">Current</div>
              <div className="font-semibold text-gray-800">{fmt(data.current)}</div>
            </div>
          </div>
          <div className="flex items-center gap-4 text-sm">
            <span className="text-gray-600">Range: <b>{data.rangePct}%</b> ({rangePts} pts)</span>
            {data.atmIV > 0 && <span className="text-gray-600">ATM IV: <b>{data.atmIV.toFixed(1)}%</b></span>}
            <span className={`px-2 py-0.5 rounded text-xs font-medium ${data.frozen ? 'bg-green-100 text-green-700' : 'bg-yellow-100 text-yellow-700'}`}>
              {data.frozen ? '✓ Frozen' : '⏳ Tracking...'}
            </span>
          </div>
          <div className="mt-2 text-xs text-gray-500">{cfg.desc}</div>

          {/* Visual range bar */}
          <div className="mt-3 relative h-6 bg-gray-100 rounded-full overflow-hidden">
            {data.high > data.low && (
              <>
                <div className="absolute inset-y-0 bg-blue-100 rounded-full"
                  style={{
                    left: `${Math.max(0, ((data.low - data.open + data.open * 0.01) / (data.open * 0.02)) * 100)}%`,
                    right: `${Math.max(0, 100 - ((data.high - data.open + data.open * 0.01) / (data.open * 0.02)) * 100)}%`,
                  }} />
                <div className="absolute inset-y-0 w-0.5 bg-gray-400"
                  style={{ left: '50%' }} title="Open" />
                {data.current > 0 && (
                  <div className="absolute inset-y-0 w-1.5 bg-indigo-500 rounded-full"
                    style={{ left: `${((data.current - data.open + data.open * 0.01) / (data.open * 0.02)) * 100}%` }}
                    title={`Current: ${fmt(data.current)}`} />
                )}
              </>
            )}
          </div>
        </>
      ) : (
        <div className="text-gray-400 text-sm py-4 text-center">Waiting for market data...</div>
      )}
    </div>
  );
}

function OpportunityCard({ opp, onEnter }) {
  const isIC = opp.subType === 'IRON_CONDOR';
  const legs = opp.legList || [];

  return (
    <div className="bg-white rounded-xl border border-gray-200 p-5 shadow-sm hover:shadow-md transition-shadow">
      <div className="flex items-center justify-between mb-3">
        <div>
          <span className="text-sm font-bold text-gray-900">{opp.underlying}</span>
          <span className="mx-2 text-gray-300">|</span>
          <span className="text-sm text-gray-600">{isIC ? 'Iron Condor' : opp.spreadType === 'BULL_PUT' ? 'Bull Put Spread' : 'Bear Call Spread'}</span>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-xs px-2 py-1 rounded-full bg-indigo-50 text-indigo-700 font-semibold">
            Score: {opp.compositeScore?.toFixed(0) || '--'}
          </span>
          <button onClick={() => onEnter(opp)}
            className="px-3 py-1.5 bg-indigo-600 text-white text-xs font-semibold rounded-lg hover:bg-indigo-700 transition-colors">
            Enter Trade
          </button>
        </div>
      </div>

      <div className="text-xs text-gray-500 mb-3 italic">{opp.rationale}</div>

      {/* Legs */}
      <div className="flex flex-wrap gap-2 mb-3">
        {legs.map((leg, i) => (
          <span key={i} className={`px-2 py-1 rounded text-xs font-mono ${leg.side === 'SELL' ? 'bg-red-50 text-red-700 border border-red-200' : 'bg-green-50 text-green-700 border border-green-200'}`}>
            {leg.side} {leg.strike}{leg.optionType} @ ₹{leg.price?.toFixed(1)}
          </span>
        ))}
      </div>

      {/* Stats */}
      <div className="grid grid-cols-5 gap-3 text-center">
        <div>
          <div className="text-xs text-gray-400">Credit</div>
          <div className="text-sm font-bold text-green-600">₹{opp.netCreditRs?.toFixed(0)}</div>
        </div>
        <div>
          <div className="text-xs text-gray-400">Max Loss</div>
          <div className="text-sm font-bold text-red-600">₹{opp.maxLoss?.toFixed(0)}</div>
        </div>
        <div>
          <div className="text-xs text-gray-400">Win Rate</div>
          <div className="text-sm font-bold text-blue-600">{opp.estimatedWinRate?.toFixed(0)}%</div>
        </div>
        <div>
          <div className="text-xs text-gray-400">R:R</div>
          <div className="text-sm font-bold text-gray-700">1:{(1/opp.riskRewardRatio)?.toFixed(1)}</div>
        </div>
        <div>
          <div className="text-xs text-gray-400">Distance</div>
          <div className="text-sm font-bold text-gray-700">
            {isIC ? `${opp.ceDistancePct?.toFixed(1)}% / ${opp.peDistancePct?.toFixed(1)}%` : `${opp.distancePct?.toFixed(1)}%`}
          </div>
        </div>
      </div>
    </div>
  );
}

function PositionCard({ pos, onExit }) {
  const pnl = pos.currentPnl || 0;
  const pnlColor = pnl >= 0 ? 'text-green-600' : 'text-red-600';

  return (
    <div className="bg-white rounded-xl border border-gray-200 p-5 shadow-sm">
      <div className="flex items-center justify-between mb-3">
        <div>
          <span className="text-sm font-bold text-gray-900">{pos.underlying}</span>
          <span className="mx-2 text-gray-300">|</span>
          <span className="text-sm text-gray-600">{pos.strategyType}</span>
          <span className="ml-2 px-2 py-0.5 rounded text-xs bg-blue-100 text-blue-700">{pos.broker}</span>
        </div>
        <button onClick={() => onExit(pos.id)}
          className="px-3 py-1.5 bg-red-600 text-white text-xs font-semibold rounded-lg hover:bg-red-700">
          Exit
        </button>
      </div>
      <div className="grid grid-cols-4 gap-3 text-center">
        <div>
          <div className="text-xs text-gray-400">P&L</div>
          <div className={`text-lg font-bold ${pnlColor}`}>₹{pnl.toFixed(0)}</div>
        </div>
        <div>
          <div className="text-xs text-gray-400">Lots</div>
          <div className="text-sm font-bold">{pos.lots}</div>
        </div>
        <div>
          <div className="text-xs text-gray-400">SL %</div>
          <div className="text-sm font-bold">{pos.slPct}%</div>
        </div>
        <div>
          <div className="text-xs text-gray-400">Target %</div>
          <div className="text-sm font-bold">{pos.targetPct}%</div>
        </div>
      </div>
      {pos.legs && (
        <div className="flex flex-wrap gap-1 mt-3">
          {pos.legs.map((leg, i) => (
            <span key={i} className={`px-2 py-0.5 rounded text-xs font-mono ${leg.side === 'SELL' ? 'bg-red-50 text-red-600' : 'bg-green-50 text-green-600'}`}>
              {leg.side} {leg.strike}{leg.optionType}
            </span>
          ))}
        </div>
      )}
    </div>
  );
}

function fmt(n) {
  if (!n && n !== 0) return '--';
  return Number(n).toLocaleString('en-IN', { maximumFractionDigits: 0 });
}

export default function MorningTheta() {
  const queryClient = useQueryClient();

  const { data: scanData, isLoading: scanLoading } = useQuery({
    queryKey: ['morning-theta-scan'],
    queryFn: () => client.get('/api/morning-theta/scan').then(r => r.data),
    refetchInterval: 15000,
  });

  const { data: statusData } = useQuery({
    queryKey: ['morning-theta-status'],
    queryFn: () => client.get('/api/morning-theta/status').then(r => r.data),
    refetchInterval: 10000,
  });

  const toggleMutation = useMutation({
    mutationFn: (enabled) => client.post('/api/morning-theta/toggle', { enabled }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['morning-theta-status'] }),
  });

  const enterMutation = useMutation({
    mutationFn: (opp) => client.post('/api/morning-theta/enter', {
      underlying: opp.underlying, expiry: opp.expiry, lots: 1, broker: 'PAPER',
      legList: opp.legList, action: opp.action,
      slPct: opp.slPct || 100, targetPct: opp.targetPct || 50,
      timeExitMinutes: opp.timeExitMinutes || 45,
      maxLoss: opp.maxLoss, maxProfit: opp.maxProfit,
    }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['morning-theta-status'] });
      queryClient.invalidateQueries({ queryKey: ['morning-theta-scan'] });
    },
  });

  const exitMutation = useMutation({
    mutationFn: (posId) => client.post(`/api/morning-theta/exit/${posId}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['morning-theta-status'] }),
  });

  const ranges = scanData?.morningRanges || statusData?.morningRanges || {};
  const opportunities = scanData?.opportunities || [];
  const positions = statusData?.positions || [];
  const isEnabled = statusData?.enabled || false;
  const trackingPhase = scanData?.trackingPhase || statusData?.trackingPhase || false;

  return (
    <div className="max-w-6xl mx-auto p-4 space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900 flex items-center gap-2">
            <span>🌅</span> Morning Range Theta
          </h1>
          <p className="text-sm text-gray-500 mt-1">
            Wait for the first hour, read the day's character, sell premium at safe levels
          </p>
        </div>
        <div className="flex items-center gap-3">
          <span className={`text-xs px-2 py-1 rounded-full ${trackingPhase ? 'bg-yellow-100 text-yellow-700' : 'bg-gray-100 text-gray-600'}`}>
            {trackingPhase ? '⏳ Tracking 9:15-10:15' : '✓ Range Phase Complete'}
          </span>
          <button
            onClick={() => toggleMutation.mutate(!isEnabled)}
            className={`px-4 py-2 rounded-lg text-sm font-semibold transition-colors ${
              isEnabled ? 'bg-green-600 text-white hover:bg-green-700' : 'bg-gray-200 text-gray-700 hover:bg-gray-300'
            }`}>
            {isEnabled ? '● Auto ON' : '○ Auto OFF'}
          </button>
        </div>
      </div>

      {/* Status bar */}
      {statusData && (
        <div className="bg-gray-50 rounded-xl border border-gray-200 p-4">
          <div className="flex items-center justify-between">
            <div className="text-sm text-gray-600">{statusData.lastStatus}</div>
            <div className="flex gap-4 text-sm">
              <span className="text-gray-500">Today P&L: <b className={statusData.todayPnl >= 0 ? 'text-green-600' : 'text-red-600'}>
                ₹{statusData.todayPnl?.toFixed(0) || 0}</b></span>
              <span className="text-gray-500">Open: <b>{statusData.openPositions || 0}</b></span>
            </div>
          </div>
        </div>
      )}

      {/* Morning Range Cards */}
      <div>
        <h2 className="text-lg font-semibold text-gray-800 mb-3">Morning Range (9:15 - 10:15)</h2>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <RangeCard underlying="NIFTY" data={ranges.NIFTY} />
          <RangeCard underlying="BANKNIFTY" data={ranges.BANKNIFTY} />
        </div>
      </div>

      {/* How It Works */}
      <div className="bg-gradient-to-r from-indigo-50 to-purple-50 rounded-xl border border-indigo-200 p-5">
        <h3 className="text-sm font-semibold text-indigo-800 mb-2">How Morning Range Theta Works</h3>
        <div className="grid grid-cols-4 gap-4 text-xs text-indigo-700">
          <div><b>9:15-10:15</b><br/>Track opening range — high, low, trend</div>
          <div><b>10:15</b><br/>Classify day type → pick strategy</div>
          <div><b>10:20-14:45</b><br/>Enter & monitor — SL 100%, Target 50%</div>
          <div><b>14:45</b><br/>Force exit — flat before close, no overnight risk</div>
        </div>
      </div>

      {/* Active Positions */}
      {positions.length > 0 && (
        <div>
          <h2 className="text-lg font-semibold text-gray-800 mb-3">Active Positions</h2>
          <div className="space-y-3">
            {positions.map((pos, i) => (
              <PositionCard key={pos.id || i} pos={pos} onExit={(id) => exitMutation.mutate(id)} />
            ))}
          </div>
        </div>
      )}

      {/* Opportunities */}
      <div>
        <h2 className="text-lg font-semibold text-gray-800 mb-3">
          Opportunities {opportunities.length > 0 && <span className="text-sm font-normal text-gray-500">({opportunities.length})</span>}
        </h2>
        {scanLoading ? (
          <div className="text-center py-8 text-gray-400">Scanning...</div>
        ) : opportunities.length === 0 ? (
          <div className="bg-white rounded-xl border border-gray-200 p-8 text-center">
            <div className="text-3xl mb-2">🌅</div>
            <div className="text-gray-500">
              {trackingPhase
                ? 'Waiting for morning range to freeze (10:15 AM)...'
                : 'No opportunities found — day type may not favor entry, or market is closed'}
            </div>
          </div>
        ) : (
          <div className="space-y-3">
            {opportunities.map((opp, i) => (
              <OpportunityCard key={i} opp={opp} onEnter={(o) => enterMutation.mutate(o)} />
            ))}
          </div>
        )}
      </div>

      {/* Day Type Legend */}
      <div className="bg-white rounded-xl border border-gray-200 p-5">
        <h3 className="text-sm font-semibold text-gray-800 mb-3">Day Classification Guide</h3>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          {Object.entries(DAY_TYPE_CONFIG).filter(([k]) => k !== 'UNKNOWN').map(([key, cfg]) => (
            <div key={key} className="p-3 rounded-lg" style={{ backgroundColor: cfg.bg }}>
              <div className="text-sm font-semibold" style={{ color: cfg.color }}>{cfg.label}</div>
              <div className="text-xs text-gray-600 mt-1">{cfg.desc}</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
