import React, { useState, useEffect } from 'react';
import { cn } from '../../lib/utils';

interface SummaryMetrics {
  totalSignals: number;
  executed: number;
  targetsHit: number;
  slHits: number;
  stillOpen: number;
  avgPnl: number;
  totalPnl: number;
  hitRatePct: number;
}

interface ConfidenceBracketMetric {
  bracket: string;
  minConfidence: number;
  signals: number;
  executed: number;
  targetsHit: number;
  slHits: number;
  stillOpen: number;
  hitRatePct: number;
  avgPnl: number;
  totalPnl: number;
  winners: number;
  losers: number;
}

interface HourlyMetric {
  hour: string;
  signals: number;
  executed: number;
  targetsHit: number;
  slHits: number;
  avgPnl: number;
  totalPnl: number;
  winners: number;
}

interface TopPerformer {
  symbol: string;
  signals: number;
  targetsHit: number;
  avgPnl: number;
  totalPnl: number;
  avgConfidence: number;
}

interface DashboardData {
  timestamp: number;
  tradeDate: string;
  summary: SummaryMetrics;
  confidenceBrackets: ConfidenceBracketMetric[];
  hourlyBreakdown: HourlyMetric[];
  topPerformers: TopPerformer[];
}

export function PerformanceDashboard() {
  const [data, setData] = useState<DashboardData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [autoRefresh, setAutoRefresh] = useState(true);

  const fetchDashboardData = async () => {
    try {
      const response = await fetch('/api/v1/admin/performance/dashboard');
      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }
      const jsonData = await response.json();
      setData(jsonData);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to fetch data');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchDashboardData();

    if (autoRefresh) {
      const interval = setInterval(fetchDashboardData, 60000); // Refresh every 60 seconds
      return () => clearInterval(interval);
    }
  }, [autoRefresh]);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-96">
        <div className="text-gray-500">Loading dashboard...</div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="bg-red-50 border border-red-200 rounded-lg p-4">
        <div className="text-red-800">Error loading dashboard: {error}</div>
        <button
          onClick={fetchDashboardData}
          className="mt-2 px-4 py-2 bg-red-600 text-white rounded hover:bg-red-700"
        >
          Retry
        </button>
      </div>
    );
  }

  if (!data) {
    return <div className="text-gray-500">No data available</div>;
  }

  return (
    <div className="space-y-6 p-6">
      {/* Header */}
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-3xl font-bold text-gray-900">📊 Performance Dashboard</h1>
          <p className="text-gray-600 mt-1">Live Trade Metrics • Date: {data.tradeDate}</p>
        </div>
        <div className="flex items-center gap-2">
          <label className="flex items-center gap-2">
            <input
              type="checkbox"
              checked={autoRefresh}
              onChange={(e) => setAutoRefresh(e.target.checked)}
              className="w-4 h-4"
            />
            <span className="text-sm text-gray-600">Auto-refresh</span>
          </label>
          <button
            onClick={fetchDashboardData}
            className="px-4 py-2 bg-blue-600 text-white rounded hover:bg-blue-700"
          >
            Refresh Now
          </button>
        </div>
      </div>

      {/* Summary Metrics */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <MetricCard title="Total Signals" value={data.summary.totalSignals} color="bg-blue-50" borderColor="border-blue-200" />
        <MetricCard title="Executed" value={data.summary.executed} color="bg-green-50" borderColor="border-green-200" />
        <MetricCard title="🎯 Targets Hit" value={data.summary.targetsHit} color="bg-emerald-50" borderColor="border-emerald-200" subtitle={`${data.summary.hitRatePct}% hit rate`} />
        <MetricCard title="🛑 SL Hit" value={data.summary.slHits} color="bg-red-50" borderColor="border-red-200" />
      </div>

      {/* P&L Summary */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <MetricCard
          title="Avg P&L"
          value={`₹${data.summary.avgPnl.toFixed(2)}`}
          color={data.summary.avgPnl >= 0 ? 'bg-green-50' : 'bg-red-50'}
          borderColor={data.summary.avgPnl >= 0 ? 'border-green-200' : 'border-red-200'}
          valueClass={data.summary.avgPnl >= 0 ? 'text-green-600' : 'text-red-600'}
        />
        <MetricCard
          title="Total P&L"
          value={`₹${data.summary.totalPnl.toFixed(2)}`}
          color={data.summary.totalPnl >= 0 ? 'bg-green-50' : 'bg-red-50'}
          borderColor={data.summary.totalPnl >= 0 ? 'border-green-200' : 'border-red-200'}
          valueClass={data.summary.totalPnl >= 0 ? 'text-green-600' : 'text-red-600'}
        />
      </div>

      {/* Confidence Bracket Breakdown - THE KEY TABLE */}
      <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
        <div className="bg-gradient-to-r from-blue-50 to-indigo-50 px-6 py-4 border-b border-gray-200">
          <h2 className="text-xl font-bold text-gray-900">📈 Performance by Confidence Bracket</h2>
          <p className="text-sm text-gray-600 mt-1">Shows how many signals at each bracket HIT TARGET vs HIT SL</p>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-gray-100 border-b-2 border-gray-300">
                <th className="px-4 py-3 text-left font-bold text-gray-900 bg-gradient-to-r from-blue-100 to-blue-50">Confidence Bracket</th>
                <th className="px-4 py-3 text-right font-bold text-gray-900">Signals Generated</th>
                <th className="px-4 py-3 text-right font-bold text-gray-900">Executed</th>
                <th className="px-4 py-3 text-right font-bold text-green-700 bg-green-50">🎯 Targets Hit</th>
                <th className="px-4 py-3 text-right font-bold text-red-700 bg-red-50">🛑 SL Hit</th>
                <th className="px-4 py-3 text-right font-bold text-gray-900">Still Open</th>
                <th className="px-4 py-3 text-right font-bold text-gray-900">Hit Rate %</th>
                <th className="px-4 py-3 text-right font-bold text-gray-900">Avg P&L</th>
                <th className="px-4 py-3 text-right font-bold text-gray-900">Total P&L</th>
              </tr>
            </thead>
            <tbody>
              {data.confidenceBrackets.map((bracket, idx) => (
                <tr key={idx} className="border-b border-gray-200 hover:bg-gray-50 transition">
                  <td className="px-4 py-3 font-bold text-gray-900 bg-blue-50">{bracket.bracket}</td>
                  <td className="px-4 py-3 text-right text-gray-700">{bracket.signals}</td>
                  <td className="px-4 py-3 text-right text-gray-700 font-medium">{bracket.executed}</td>
                  <td className="px-4 py-3 text-right font-bold text-green-700 bg-green-50">{bracket.targetsHit}</td>
                  <td className="px-4 py-3 text-right font-bold text-red-700 bg-red-50">{bracket.slHits}</td>
                  <td className="px-4 py-3 text-right text-yellow-700 font-medium">{bracket.stillOpen}</td>
                  <td className="px-4 py-3 text-right">
                    <span
                      className={cn(
                        'px-3 py-1 rounded-full font-bold text-white',
                        bracket.hitRatePct >= 70
                          ? 'bg-green-600'
                          : bracket.hitRatePct >= 50
                            ? 'bg-yellow-600'
                            : 'bg-red-600'
                      )}
                    >
                      {bracket.hitRatePct.toFixed(1)}%
                    </span>
                  </td>
                  <td className={cn('px-4 py-3 text-right font-bold', bracket.avgPnl >= 0 ? 'text-green-600' : 'text-red-600')}>
                    ₹{bracket.avgPnl.toFixed(2)}
                  </td>
                  <td className={cn('px-4 py-3 text-right font-bold text-lg', bracket.totalPnl >= 0 ? 'text-green-600' : 'text-red-600')}>
                    ₹{bracket.totalPnl.toFixed(2)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* Hourly Breakdown */}
      <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
        <div className="bg-gradient-to-r from-purple-50 to-indigo-50 px-6 py-4 border-b border-gray-200">
          <h2 className="text-lg font-bold text-gray-900">⏰ Hourly Breakdown</h2>
          <p className="text-sm text-gray-600 mt-1">Performance by hour of trading (IST)</p>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-gray-100 border-b border-gray-200">
                <th className="px-4 py-3 text-left font-bold text-gray-900">Hour</th>
                <th className="px-4 py-3 text-right font-bold">Signals</th>
                <th className="px-4 py-3 text-right font-bold">Executed</th>
                <th className="px-4 py-3 text-right font-bold text-green-700">Targets</th>
                <th className="px-4 py-3 text-right font-bold text-red-700">SL</th>
                <th className="px-4 py-3 text-right font-bold">Winners</th>
                <th className="px-4 py-3 text-right font-bold">Total P&L</th>
              </tr>
            </thead>
            <tbody>
              {data.hourlyBreakdown.map((hourly, idx) => (
                <tr key={idx} className="border-b border-gray-200 hover:bg-gray-50">
                  <td className="px-4 py-3 font-medium text-gray-900">{hourly.hour}</td>
                  <td className="px-4 py-3 text-right">{hourly.signals}</td>
                  <td className="px-4 py-3 text-right font-medium">{hourly.executed}</td>
                  <td className="px-4 py-3 text-right font-bold text-green-600">{hourly.targetsHit}</td>
                  <td className="px-4 py-3 text-right font-bold text-red-600">{hourly.slHits}</td>
                  <td className="px-4 py-3 text-right">{hourly.winners}</td>
                  <td className={cn('px-4 py-3 text-right font-bold', hourly.totalPnl >= 0 ? 'text-green-600' : 'text-red-600')}>
                    ₹{hourly.totalPnl.toFixed(2)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* Top Performers */}
      <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
        <div className="bg-gradient-to-r from-green-50 to-emerald-50 px-6 py-4 border-b border-gray-200">
          <h2 className="text-lg font-bold text-gray-900">🏆 Top 10 Performing Stocks</h2>
          <p className="text-sm text-gray-600 mt-1">Highest P&L earners today</p>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-gray-100 border-b border-gray-200">
                <th className="px-4 py-3 text-left font-bold text-gray-900">Symbol</th>
                <th className="px-4 py-3 text-right font-bold">Signals</th>
                <th className="px-4 py-3 text-right font-bold text-green-700">Targets Hit</th>
                <th className="px-4 py-3 text-right font-bold">Avg Confidence</th>
                <th className="px-4 py-3 text-right font-bold">Avg P&L</th>
                <th className="px-4 py-3 text-right font-bold">Total P&L</th>
              </tr>
            </thead>
            <tbody>
              {data.topPerformers.map((performer, idx) => (
                <tr key={idx} className="border-b border-gray-200 hover:bg-gray-50">
                  <td className="px-4 py-3 font-bold text-gray-900">{performer.symbol}</td>
                  <td className="px-4 py-3 text-right">{performer.signals}</td>
                  <td className="px-4 py-3 text-right font-bold text-green-600">{performer.targetsHit}</td>
                  <td className="px-4 py-3 text-right">
                    <span className="bg-blue-100 text-blue-700 px-2 py-1 rounded-full font-bold">{performer.avgConfidence}%</span>
                  </td>
                  <td className={cn('px-4 py-3 text-right font-bold', performer.avgPnl >= 0 ? 'text-green-600' : 'text-red-600')}>
                    ₹{performer.avgPnl.toFixed(2)}
                  </td>
                  <td className={cn('px-4 py-3 text-right font-bold text-lg', performer.totalPnl >= 0 ? 'text-green-600' : 'text-red-600')}>
                    ₹{performer.totalPnl.toFixed(2)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

interface MetricCardProps {
  title: string;
  value: string | number;
  color: string;
  borderColor: string;
  subtitle?: string;
  valueClass?: string;
}

function MetricCard({ title, value, color, borderColor, subtitle, valueClass }: MetricCardProps) {
  return (
    <div className={cn('border-2 rounded-lg p-4 shadow-sm', borderColor, color)}>
      <div className="text-xs font-semibold text-gray-600 uppercase tracking-wider">{title}</div>
      <div className={cn('text-3xl font-bold mt-2', valueClass || 'text-gray-900')}>{value}</div>
      {subtitle && <div className="text-xs text-gray-500 mt-2">{subtitle}</div>}
    </div>
  );
}
