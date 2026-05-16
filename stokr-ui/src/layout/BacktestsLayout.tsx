import { NavLink, Outlet } from "react-router-dom";
import { cn } from "../lib/utils";
import { useUiThemeStore } from "../state/uiTheme";

function tabClass(isLight: boolean) {
  return ({ isActive }: { isActive: boolean }) =>
    cn(
      "rounded-lg px-3 py-2 text-sm font-medium transition",
      isActive
        ? "bg-[#2563EB] text-white shadow-sm"
        : isLight
          ? "text-[#475569] hover:bg-[#F8FAFC] hover:text-[#0F172A]"
          : "text-[#94A3B8] hover:bg-[#111827] hover:text-white",
    );
}

export function BacktestsLayout() {
  const isLight = useUiThemeStore((s) => s.mode === "light");

  return (
    <div className="space-y-6">
      <div>
        <h1
          className={cn(
            "text-2xl font-semibold tracking-tight",
            isLight ? "text-[#0F172A]" : "text-[#F8FAFC]",
          )}
        >
          Backtests
        </h1>
        <p className={cn("mt-1 text-sm", isLight ? "text-[#475569]" : "text-[#94A3B8]")}>
          Institutional-grade replay and run history. Launch uses the published strategy profile - not a parameter lab.
        </p>
      </div>

      <div
        className={cn(
          "flex flex-wrap gap-2 border-b pb-4",
          isLight ? "border-slate-900/[0.08]" : "border-[rgba(255,255,255,0.06)]",
        )}
      >
        <NavLink to="/backtests/launch" className={tabClass(isLight)}>
          Launch
        </NavLink>
        <NavLink to="/backtests/history" className={tabClass(isLight)}>
          History
        </NavLink>
      </div>

      <Outlet />
    </div>
  );
}
