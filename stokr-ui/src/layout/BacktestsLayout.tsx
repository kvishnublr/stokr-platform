import { NavLink, Outlet } from "react-router-dom";
import { cn } from "../lib/utils";

const tabClass = ({ isActive }: { isActive: boolean }) =>
  cn(
    "rounded-lg px-3 py-2 text-sm font-medium transition",
    isActive ? "bg-neutral-800 text-white" : "text-neutral-400 hover:bg-neutral-900 hover:text-white",
  );

export function BacktestsLayout() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-white">Backtests</h1>
        <p className="mt-1 text-sm text-neutral-400">
          Deterministic catalog replay, integrity hash, and materialized analytics for research workflows.
        </p>
      </div>

      <div className="flex flex-wrap gap-2 border-b border-neutral-900 pb-4">
        <NavLink to="/backtests/launch" className={tabClass}>
          Launch
        </NavLink>
        <NavLink to="/backtests/history" className={tabClass}>
          History
        </NavLink>
      </div>

      <Outlet />
    </div>
  );
}
