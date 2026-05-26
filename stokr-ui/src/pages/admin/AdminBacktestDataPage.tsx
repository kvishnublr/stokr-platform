import { BacktestHistoricalDataLoader } from "../../components/admin/BacktestHistoricalDataLoader";

/**
 * Admin page for backtesting historical data loading and management
 * Provides UI to trigger 5-year historical data load from Zerodha API
 */
export function AdminBacktestDataPage() {
  return (
    <div className="space-y-6 p-6">
      <div className="space-y-2">
        <h1 className="text-3xl font-bold text-slate-900">Backtest Data Management</h1>
        <p className="text-slate-600">
          Load and manage historical NSE data for backtesting trading strategies
        </p>
      </div>

      <BacktestHistoricalDataLoader />
    </div>
  );
}
