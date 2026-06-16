import { NavLink, Outlet } from "react-router-dom";
import { cn } from "../lib/utils";

const tabClass = ({ isActive }: { isActive: boolean }) =>
  cn(
    "rounded-lg px-3 py-2 text-sm font-medium transition",
    isActive ? "bg-neutral-800 text-white" : "text-neutral-400 hover:bg-neutral-900 hover:text-white",
  );

export function StrategyResearchLayout() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-white">Strategy research</h1>
        <p className="mt-1 text-sm text-neutral-400">
          Catalog performance aggregates from your materialized backtests - Sharpe, win rate, drawdowns by strategy key.
        </p>
      </div>

      <div className="flex flex-wrap gap-2 border-b border-neutral-900 pb-4">
        <NavLink to="/research/leaderboard" className={tabClass}>
          Leaderboard
        </NavLink>
        <NavLink to="/strategies" className={tabClass}>
          Catalog
        </NavLink>
        <NavLink to="/backtests/launch" className={tabClass}>
          Run backtest
        </NavLink>
      </div>

      <Outlet />
    </div>
  );
}
