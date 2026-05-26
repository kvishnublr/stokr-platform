import { useState } from "react";
import { Link } from "react-router-dom";
import { BarChart3, FlaskConical, Grid3X3, LineChart, RotateCcw, Sliders, Target, TrendingUp } from "lucide-react";
import { useUiThemeStore } from "../../state/uiTheme";
import { AdminPageShell, AdminPanel, AdminSection } from "../../components/admin/institutional/AdminDesignSystem";
import { cn } from "../../lib/utils";

const TABS = [
  { id: "lab", label: "Strategy Research Lab", icon: FlaskConical, description: "Hypothesis design, signal anatomy, and strategy DNA exploration." },
  { id: "walkforward", label: "Walk-forward Validation", icon: TrendingUp, description: "Out-of-sample windows, decay curves, and stability scoring." },
  { id: "regime", label: "Regime Analysis", icon: Grid3X3, description: "Performance matrix across trend, chop, and expansion regimes." },
  { id: "replay", label: "Signal Replay Studio", icon: RotateCcw, description: "Candle context, acceptance rationale, and trade evolution timelines." },
  { id: "optimize", label: "Parameter Optimization", icon: Sliders, description: "Grid search surfaces and sensitivity heatmaps." },
  { id: "distribution", label: "Trade Distribution", icon: BarChart3, description: "PnL histograms, tail risk, and expectancy bands." },
  { id: "false-breakout", label: "False Breakout Analysis", icon: Target, description: "Trap probability, rejection structures, and chop degradation." },
  { id: "matrix", label: "Regime Performance Matrix", icon: LineChart, description: "Strategy × regime compatibility grid with live quality scores." },
] as const;

type TabId = (typeof TABS)[number]["id"];

export function AdminResearchLabPage() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const [tab, setTab] = useState<TabId>("lab");
  const active = TABS.find((t) => t.id === tab)!;

  return (
    <AdminPageShell
      isLight={isLight}
      eyebrow="Quant research infrastructure"
      title="Strategy Research Platform"
      subtitle="Institutional research surfaces — walk-forward validation, regime matrices, false breakout analysis, and parameter optimization."
      actions={
        <Link
          to="/admin/signal-lab"
          className={cn(
            "rounded-xl border px-3 py-2 text-xs font-semibold",
            isLight ? "border-indigo-300 bg-indigo-50 text-indigo-800" : "border-indigo-500/40 bg-indigo-500/10 text-indigo-200",
          )}
        >
          Open Signal Lab API
        </Link>
      }
    >
      <div className="flex flex-wrap gap-2">
        {TABS.map((t) => {
          const Icon = t.icon;
          return (
            <button
              key={t.id}
              type="button"
              onClick={() => setTab(t.id)}
              className={cn(
                "inline-flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-[11px] font-semibold transition",
                tab === t.id
                  ? isLight ? "border-blue-400 bg-blue-50 text-blue-900" : "border-blue-500/50 bg-blue-500/15 text-blue-100"
                  : isLight ? "border-neutral-200 bg-white text-neutral-600" : "border-neutral-800 bg-neutral-900/50 text-neutral-400",
              )}
            >
              <Icon className="h-3.5 w-3.5" /> {t.label}
            </button>
          );
        })}
      </div>

      <AdminSection isLight={isLight} title={active.label} subtitle={active.description}>
        <AdminPanel isLight={isLight} title="Research workspace" subtitle="Connect live signal and backtest data to populate this surface">
          <div className={cn("rounded-xl border border-dashed px-6 py-12 text-center", isLight ? "border-neutral-300 bg-neutral-50/50" : "border-neutral-700 bg-neutral-900/30")}>
            <active.icon className="mx-auto mb-3 h-8 w-8 opacity-40" />
            <p className="text-sm font-medium">{active.label}</p>
            <p className={cn("mx-auto mt-2 max-w-lg text-xs leading-relaxed", isLight ? "text-neutral-500" : "text-neutral-400")}>
              {active.description} Use Signal Lab and Signal Replay for live data; backtest jobs feed walk-forward and regime matrices when replay infrastructure completes.
            </p>
            <div className="mt-4 flex flex-wrap justify-center gap-2">
              <Link to="/admin/signal-lab" className="rounded-lg bg-blue-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-blue-700">Signal Lab</Link>
              <Link
                to="/admin/signal-replay"
                className={cn("rounded-lg border px-3 py-1.5 text-xs font-semibold", isLight ? "border-neutral-300 bg-white text-neutral-800 hover:bg-neutral-50" : "border-neutral-700 bg-neutral-900/60 text-neutral-200 hover:bg-neutral-800")}
              >
                Signal Replay
              </Link>
              <Link
                to="/admin/backfill"
                className={cn("rounded-lg border px-3 py-1.5 text-xs font-semibold", isLight ? "border-neutral-300 bg-white text-neutral-800 hover:bg-neutral-50" : "border-neutral-700 bg-neutral-900/60 text-neutral-200 hover:bg-neutral-800")}
              >
                Backfill data
              </Link>
            </div>
          </div>
        </AdminPanel>
      </AdminSection>
    </AdminPageShell>
  );
}
