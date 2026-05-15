import { StrategyExecutionLauncher } from "../components/strategy/execution/StrategyExecutionLauncher";
import { cn } from "../lib/utils";
import { useUiThemeStore } from "../state/uiTheme";

const MEAN_REVERSION_KEY = "MEAN_REVERSION_RANGE_FADE";

export function BacktestLauncherPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");

  return (
    <div
      className={cn(
        "-mx-4 -mt-2 min-h-[calc(100vh-8rem)] px-4 py-6 sm:-mx-6 sm:px-6",
        isLight ? "bg-[#F5F7FB]" : "bg-[#0B1220]",
      )}
    >
      <div className="mx-auto max-w-4xl">
        <p className="text-[10px] font-semibold uppercase tracking-[0.22em] text-[#64748B]">Deploy</p>
        <h1
          className={cn(
            "mt-1 text-2xl font-semibold tracking-tight",
            isLight ? "text-[#0F172A]" : "text-[#F8FAFC]",
          )}
        >
          Historical replay
        </h1>
        <p
          className={cn(
            "mt-2 max-w-2xl text-sm leading-relaxed",
            isLight ? "text-[#475569]" : "text-[#94A3B8]",
          )}
        >
          Replay how this published strategy would have traded over your chosen window. Capital, range, and mode are yours
          — symbol, timeframe, risk, and execution mechanics are defined by the strategy.
        </p>
        <div
          className={cn(
            "mt-8 rounded-[1.35rem] border p-4 sm:p-6",
            isLight
              ? "border-slate-900/[0.08] bg-white shadow-sm"
              : "border-[rgba(255,255,255,0.06)] bg-[#111827]",
          )}
        >
          <StrategyExecutionLauncher strategyKey={MEAN_REVERSION_KEY} />
        </div>
      </div>
    </div>
  );
}
