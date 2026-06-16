import { ExecutionModeSelector } from '../../components/admin/ExecutionModeSelector';
import { ReplayControlsPanel } from '../../components/admin/ReplayControlsPanel';
import { MarketDataCoverageMonitor } from '../../components/admin/MarketDataCoverageMonitor';
import { ExecutionStatsPanel } from '../../components/admin/ExecutionStatsPanel';
import { StrategyPerformanceDashboard } from '../../components/admin/StrategyPerformanceDashboard';
import { useSessionStore } from '../../state/session';

export function AdminOpsPage() {
  const userId = useSessionStore((s) => s.userId);

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 to-slate-100 dark:from-neutral-950 dark:to-neutral-900 p-6">
      {/* Header */}
      <div className="mb-8 animate-fade-in">
        <h1 className="text-4xl font-bold bg-gradient-to-r from-blue-600 to-blue-800 dark:from-blue-400 dark:to-blue-600 bg-clip-text text-transparent mb-2">
          Operations Control Center
        </h1>
        <p className="text-gray-600 dark:text-gray-400 text-lg">
          Unified execution framework management · Mode switching · Replay controls · Market data coverage
        </p>
      </div>

      {/* Main Grid */}
      <div className="space-y-6">
        {/* Row 1: Execution Mode + Replay Controls */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <div className="animate-slide-up" style={{ animationDelay: '0ms' }}>
            <ExecutionModeSelector />
          </div>
          <div className="animate-slide-up" style={{ animationDelay: '100ms' }}>
            <ReplayControlsPanel />
          </div>
        </div>

        {/* Row 2: Market Data Coverage + Execution Stats */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <div className="animate-slide-up" style={{ animationDelay: '200ms' }}>
            <MarketDataCoverageMonitor />
          </div>
          <div className="animate-slide-up" style={{ animationDelay: '300ms' }}>
            <ExecutionStatsPanel />
          </div>
        </div>

        {/* Row 3: Strategy Performance Dashboard */}
        {userId && (
          <div className="animate-slide-up" style={{ animationDelay: '400ms' }}>
            <StrategyPerformanceDashboard userId={userId} />
          </div>
        )}
      </div>

      {/* Footer Stats */}
      <div className="mt-8 grid grid-cols-2 md:grid-cols-4 gap-4 animate-slide-up" style={{ animationDelay: '500ms' }}>
        <div className="bg-white dark:bg-neutral-900 rounded-lg p-4 border border-gray-200 dark:border-gray-700 hover:border-blue-300 dark:hover:border-blue-700 transition-colors">
          <p className="text-xs font-semibold text-gray-600 dark:text-gray-400 uppercase tracking-wider mb-1">
            System Mode
          </p>
          <p className="text-2xl font-bold text-blue-600 dark:text-blue-400">LIVE</p>
        </div>
        <div className="bg-white dark:bg-neutral-900 rounded-lg p-4 border border-gray-200 dark:border-gray-700 hover:border-green-300 dark:hover:border-green-700 transition-colors">
          <p className="text-xs font-semibold text-gray-600 dark:text-gray-400 uppercase tracking-wider mb-1">
            Broker Status
          </p>
          <p className="text-2xl font-bold text-green-600 dark:text-green-400">Connected</p>
        </div>
        <div className="bg-white dark:bg-neutral-900 rounded-lg p-4 border border-gray-200 dark:border-gray-700 hover:border-purple-300 dark:hover:border-purple-700 transition-colors">
          <p className="text-xs font-semibold text-gray-600 dark:text-gray-400 uppercase tracking-wider mb-1">
            Market Time
          </p>
          <p className="text-2xl font-bold text-purple-600 dark:text-purple-400">09:15 IST</p>
        </div>
        <div className="bg-white dark:bg-neutral-900 rounded-lg p-4 border border-gray-200 dark:border-gray-700 hover:border-orange-300 dark:hover:border-orange-700 transition-colors">
          <p className="text-xs font-semibold text-gray-600 dark:text-gray-400 uppercase tracking-wider mb-1">
            Active Positions
          </p>
          <p className="text-2xl font-bold text-orange-600 dark:text-orange-400">42</p>
        </div>
      </div>
    </div>
  );
}
