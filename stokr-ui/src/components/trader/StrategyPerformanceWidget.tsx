import React, { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { TrendingUp, TrendingDown } from 'lucide-react';
import { api } from '../../api/client';

interface PerformanceData {
  aggregate: {
    realizedPnL: string;
    fillRatePercent: number;
    totalTrades: number;
    winRatePercent: number;
    avgLatencyMs: number;
  };
  strategies: Array<{
    strategyKey: string;
    realizedPnL: string;
    totalTrades: number;
    fillRatePercent: number;
  }>;
}

async function fetchTodayPerformance(userId: string, mode: string) {
  const { data } = await api.get(`/api/strategy/performance/today?userId=${userId}&executionMode=${mode}`);
  return data.data;
}

export function StrategyPerformanceWidget({ userId, executionMode = 'SIMULATED' }: { userId: string; executionMode?: string }) {
  const { data: performance, isLoading } = useQuery({
    queryKey: ['strategy-perf-widget', userId, executionMode],
    queryFn: () => fetchTodayPerformance(userId, executionMode),
    refetchInterval: 60000, // Refresh every minute
    staleTime: 30000,
  });

  if (isLoading || !performance) {
    return (
      <div className="animate-fade-in rounded-lg border border-border bg-card p-4 shadow-sm">
        <p className="text-xs text-muted-foreground">Loading performance...</p>
      </div>
    );
  }

  const { aggregate, strategies } = performance;
  const pnlValue = parseFloat(aggregate.realizedPnL);
  const isProfit = pnlValue >= 0;

  return (
    <div className="space-y-4">
      {/* Main Metrics */}
      <div className="animate-fade-in rounded-lg border border-border bg-gradient-to-br from-card via-card to-card/80 p-4 shadow-sm">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-xs text-muted-foreground">Today's P&L</p>
            <p className={`mt-1 text-2xl font-bold ${isProfit ? 'text-green-600' : 'text-red-600'}`}>
              {aggregate.realizedPnL}
            </p>
          </div>
          <div className={`rounded-lg p-3 ${isProfit ? 'bg-green-100' : 'bg-red-100'}`}>
            {isProfit ? (
              <TrendingUp className={`h-6 w-6 ${isProfit ? 'text-green-600' : 'text-red-600'}`} />
            ) : (
              <TrendingDown className="h-6 w-6 text-red-600" />
            )}
          </div>
        </div>

        {/* Quick Stats Grid */}
        <div className="mt-4 grid grid-cols-3 gap-2">
          <div className="rounded-lg bg-blue-50 p-2">
            <p className="text-xs text-muted-foreground">Fill Rate</p>
            <p className="mt-0.5 font-bold text-blue-700">{aggregate.fillRatePercent.toFixed(1)}%</p>
          </div>
          <div className="rounded-lg bg-purple-50 p-2">
            <p className="text-xs text-muted-foreground">Win Rate</p>
            <p className="mt-0.5 font-bold text-purple-700">{aggregate.winRatePercent.toFixed(1)}%</p>
          </div>
          <div className="rounded-lg bg-amber-50 p-2">
            <p className="text-xs text-muted-foreground">Trades</p>
            <p className="mt-0.5 font-bold text-amber-700">{aggregate.totalTrades}</p>
          </div>
        </div>
      </div>

      {/* Top Strategies */}
      {strategies.length > 0 && (
        <div className="animate-slide-up rounded-lg border border-border bg-card p-4 shadow-sm">
          <p className="text-xs font-semibold text-muted-foreground">Strategy Performance</p>
          <div className="mt-3 space-y-2">
            {strategies.slice(0, 3).map((strategy: typeof strategies[number], idx: number) => {
              const pnl = parseFloat(strategy.realizedPnL);
              return (
                <div key={idx} className="flex items-center justify-between rounded-lg bg-muted/50 px-3 py-2 hover:bg-muted">
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-xs font-medium text-foreground">{strategy.strategyKey}</p>
                    <p className="text-xs text-muted-foreground">{strategy.totalTrades} trades</p>
                  </div>
                  <div className="text-right">
                    <p className={`text-xs font-bold ${pnl >= 0 ? 'text-green-600' : 'text-red-600'}`}>
                      {pnl >= 0 ? '+' : ''}{strategy.realizedPnL}
                    </p>
                    <p className="text-xs text-muted-foreground">{strategy.fillRatePercent.toFixed(0)}% fill</p>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* Latency Info */}
      <div className="animate-slide-up rounded-lg border border-border bg-card p-3 shadow-sm">
        <p className="text-xs font-semibold text-muted-foreground">Execution Latency</p>
        <p className="mt-2 text-lg font-bold text-foreground">{aggregate.avgLatencyMs.toFixed(0)} ms</p>
        <div className="mt-2 h-1 w-full overflow-hidden rounded-full bg-muted">
          <div
            className="h-full bg-gradient-to-r from-green-500 to-blue-500 transition-all"
            style={{ width: `${Math.min((aggregate.avgLatencyMs / 1000) * 100, 100)}%` }}
          />
        </div>
      </div>
    </div>
  );
}
