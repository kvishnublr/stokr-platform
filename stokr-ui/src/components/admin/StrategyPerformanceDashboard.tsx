import React, { useState, useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { BarChart, Bar, LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer, Cell, PieChart, Pie } from 'recharts';
import { TrendingUp, Target, Zap, Award, DollarSign, Clock, AlertCircle } from 'lucide-react';

interface StrategyMetrics {
  strategyKey: string;
  executionMode: string;
  totalOrders: number;
  filledOrders: number;
  rejectedOrders: number;
  totalTrades: number;
  winningTrades: number;
  realizedPnL: string;
  avgSlippageBps: string;
  avgLatencyMs: number;
  fillRatePercent: number;
  winRatePercent: number;
}

interface PerformanceReport {
  userId: string;
  executionMode: string;
  startTime: string;
  endTime: string;
  aggregate: {
    totalOrders: number;
    filledOrders: number;
    rejectedOrders: number;
    pendingOrders: number;
    totalTrades: number;
    fillRatePercent: number;
    realizedPnL: string;
    avgSlippageBps: string;
    avgLatencyMs: number;
    winRatePercent: number;
  };
  strategies: StrategyMetrics[];
}

const COLORS = ['#3b82f6', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#ec4899', '#06b6d4', '#f97316'];

async function fetchStrategyPerformance(userId: string, mode: string, startDate?: string, endDate?: string) {
  const params = new URLSearchParams({ executionMode: mode });
  if (startDate) params.append('startDate', startDate);
  if (endDate) params.append('endDate', endDate);

  const response = await fetch(`/api/strategy/performance/user/${userId}?${params}`, {
    method: 'GET',
    headers: { 'Content-Type': 'application/json' }
  });

  if (!response.ok) throw new Error('Failed to fetch performance data');
  const data = await response.json();
  return data.data;
}

export function StrategyPerformanceDashboard({ userId }: { userId: string }) {
  const [executionMode, setExecutionMode] = useState('SIMULATED');
  const [startDate, setStartDate] = useState(new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString().split('T')[0]);
  const [endDate, setEndDate] = useState(new Date().toISOString().split('T')[0]);

  const { data: performance, isLoading, error } = useQuery({
    queryKey: ['strategy-performance', userId, executionMode, startDate, endDate],
    queryFn: () => fetchStrategyPerformance(userId, executionMode, startDate, endDate),
    staleTime: 60000,
  });

  if (isLoading) {
    return (
      <div className="rounded-lg border border-border bg-card p-6 text-center">
        <p className="text-muted-foreground">Loading performance data...</p>
      </div>
    );
  }

  if (error) {
    return (
      <div className="rounded-lg border border-border bg-card p-6 text-center">
        <p className="text-destructive">Failed to load performance data</p>
      </div>
    );
  }

  if (!performance) {
    return null;
  }

  const aggregate = performance.aggregate;
  const strategies = performance.strategies || [];

  const strategyChartData = strategies.map((s: StrategyMetrics) => ({
    name: s.strategyKey,
    pnl: parseFloat(s.realizedPnL),
    fillRate: s.fillRatePercent,
    trades: s.totalTrades,
  }));

  const orderStatusData = [
    { name: 'Filled', value: aggregate.filledOrders, fill: '#10b981' },
    { name: 'Rejected', value: aggregate.rejectedOrders, fill: '#ef4444' },
    { name: 'Pending', value: aggregate.pendingOrders, fill: '#f59e0b' },
  ];

  const MetricCard = ({ icon: Icon, label, value, unit, color }: any) => (
    <div className="animate-fade-in rounded-lg border border-border bg-gradient-to-br from-card via-card to-card/80 p-4 shadow-sm transition hover:shadow-md">
      <div className="flex items-center justify-between">
        <div>
          <p className="text-xs text-muted-foreground">{label}</p>
          <p className="mt-1 text-xl font-bold text-foreground">{value}{unit}</p>
        </div>
        <div className={`rounded-lg p-3 ${color}`}>
          <Icon className="h-5 w-5 text-white" />
        </div>
      </div>
    </div>
  );

  return (
    <div className="space-y-6">
      {/* Header with controls */}
      <div className="rounded-2xl border border-border bg-gradient-to-r from-card via-card to-primary/5 p-5 shadow-sm">
        <h2 className="text-2xl font-bold tracking-tight">Strategy Performance Analytics</h2>
        <p className="mt-1 text-sm text-muted-foreground">
          Comprehensive metrics for {executionMode.toLowerCase()} execution mode
        </p>

        {/* Controls */}
        <div className="mt-4 grid grid-cols-1 gap-3 md:grid-cols-4">
          <div>
            <label className="text-xs font-medium text-muted-foreground">Execution Mode</label>
            <select
              value={executionMode}
              onChange={(e) => setExecutionMode(e.target.value)}
              className="mt-1 w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary focus:ring-1 focus:ring-primary"
            >
              <option value="SIMULATED">Simulated</option>
              <option value="PAPER">Paper</option>
              <option value="LIVE">Live</option>
              <option value="BOTH">Both</option>
            </select>
          </div>
          <div>
            <label className="text-xs font-medium text-muted-foreground">Start Date</label>
            <input
              type="date"
              value={startDate}
              onChange={(e) => setStartDate(e.target.value)}
              className="mt-1 w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary focus:ring-1 focus:ring-primary"
            />
          </div>
          <div>
            <label className="text-xs font-medium text-muted-foreground">End Date</label>
            <input
              type="date"
              value={endDate}
              onChange={(e) => setEndDate(e.target.value)}
              className="mt-1 w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-primary focus:ring-1 focus:ring-primary"
            />
          </div>
          <div className="flex items-end">
            <button className="w-full rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90">
              Refresh
            </button>
          </div>
        </div>
      </div>

      {/* Key Metrics Grid */}
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-4">
        <MetricCard
          icon={TrendingUp}
          label="Realized P&L"
          value={aggregate.realizedPnL}
          unit=""
          color="bg-blue-600"
        />
        <MetricCard
          icon={Target}
          label="Fill Rate"
          value={aggregate.fillRatePercent.toFixed(1)}
          unit="%"
          color="bg-green-600"
        />
        <MetricCard
          icon={Award}
          label="Win Rate"
          value={aggregate.winRatePercent.toFixed(1)}
          unit="%"
          color="bg-purple-600"
        />
        <MetricCard
          icon={Clock}
          label="Avg Latency"
          value={aggregate.avgLatencyMs.toFixed(0)}
          unit="ms"
          color="bg-orange-600"
        />
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        {/* Orders Summary */}
        <div className="rounded-lg border border-border bg-card p-6 shadow-sm">
          <h3 className="font-semibold text-foreground">Order Summary</h3>
          <div className="mt-4 space-y-3">
            <div className="flex items-center justify-between">
              <span className="text-sm text-muted-foreground">Total Orders</span>
              <span className="font-bold text-foreground">{aggregate.totalOrders}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-sm text-muted-foreground">Filled</span>
              <span className="font-bold text-green-600">{aggregate.filledOrders}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-sm text-muted-foreground">Rejected</span>
              <span className="font-bold text-red-600">{aggregate.rejectedOrders}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-sm text-muted-foreground">Pending</span>
              <span className="font-bold text-amber-600">{aggregate.pendingOrders}</span>
            </div>
            <hr className="my-3" />
            <div className="flex items-center justify-between">
              <span className="text-sm text-muted-foreground">Total Trades</span>
              <span className="font-bold text-blue-600">{aggregate.totalTrades}</span>
            </div>
          </div>
        </div>

        {/* Execution Quality */}
        <div className="rounded-lg border border-border bg-card p-6 shadow-sm">
          <h3 className="font-semibold text-foreground">Execution Quality</h3>
          <div className="mt-4 space-y-3">
            <div>
              <p className="text-xs text-muted-foreground">Avg Slippage</p>
              <p className="mt-1 text-lg font-bold text-foreground">{aggregate.avgSlippageBps} bps</p>
            </div>
            <div>
              <p className="text-xs text-muted-foreground">Avg Latency</p>
              <p className="mt-1 text-lg font-bold text-foreground">{aggregate.avgLatencyMs.toFixed(0)} ms</p>
            </div>
            <div className="mt-4 grid grid-cols-2 gap-2 text-sm">
              <div className="rounded-lg bg-blue-50 p-3">
                <p className="text-xs text-muted-foreground">Fill Rate</p>
                <p className="font-bold text-blue-700">{aggregate.fillRatePercent.toFixed(1)}%</p>
              </div>
              <div className="rounded-lg bg-purple-50 p-3">
                <p className="text-xs text-muted-foreground">Win Rate</p>
                <p className="font-bold text-purple-700">{aggregate.winRatePercent.toFixed(1)}%</p>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Strategy Breakdown */}
      {strategies.length > 0 && (
        <div className="rounded-lg border border-border bg-card p-6 shadow-sm">
          <h3 className="font-semibold text-foreground">Strategy Breakdown</h3>
          <div className="mt-6 overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border">
                  <th className="px-4 py-2 text-left font-medium text-muted-foreground">Strategy</th>
                  <th className="px-4 py-2 text-center font-medium text-muted-foreground">Orders</th>
                  <th className="px-4 py-2 text-center font-medium text-muted-foreground">Filled %</th>
                  <th className="px-4 py-2 text-right font-medium text-muted-foreground">P&L</th>
                  <th className="px-4 py-2 text-center font-medium text-muted-foreground">Trades</th>
                  <th className="px-4 py-2 text-center font-medium text-muted-foreground">Win %</th>
                </tr>
              </thead>
              <tbody>
                {strategies.map((strategy: StrategyMetrics, idx: number) => (
                  <tr key={idx} className="border-b border-border hover:bg-muted/50">
                    <td className="px-4 py-3 font-medium text-foreground">{strategy.strategyKey}</td>
                    <td className="px-4 py-3 text-center text-muted-foreground">{strategy.totalOrders}</td>
                    <td className="px-4 py-3 text-center">
                      <span className="rounded-full bg-green-100 px-2 py-1 text-xs font-medium text-green-700">
                        {strategy.fillRatePercent.toFixed(1)}%
                      </span>
                    </td>
                    <td className={`px-4 py-3 text-right font-bold ${parseFloat(strategy.realizedPnL) >= 0 ? 'text-green-600' : 'text-red-600'}`}>
                      {strategy.realizedPnL}
                    </td>
                    <td className="px-4 py-3 text-center text-muted-foreground">{strategy.totalTrades}</td>
                    <td className="px-4 py-3 text-center">
                      <span className="rounded-full bg-purple-100 px-2 py-1 text-xs font-medium text-purple-700">
                        {strategy.winRatePercent.toFixed(1)}%
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
