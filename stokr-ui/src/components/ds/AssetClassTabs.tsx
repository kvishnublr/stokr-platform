import { motion } from "framer-motion";
import { Banknote, Globe, LayoutGrid, Layers, LineChart, Wheat, type LucideIcon } from "lucide-react";
import { cn } from "../../lib/utils";

export type AssetClassTabId = "ALL" | "EQUITY" | "FUTURES" | "OPTIONS" | "COMMODITY" | "CURRENCY";

export const ASSET_CLASS_TABS: Array<{
  id: AssetClassTabId;
  label: string;
  subtitle: string;
  icon: LucideIcon;
  ringLight: string;
  ringDark: string;
  activeLight: string;
  activeDark: string;
  badgeLight: string;
  badgeDark: string;
}> = [
  {
    id: "ALL",
    label: "All",
    subtitle: "Every strategy",
    icon: LayoutGrid,
    ringLight: "ring-slate-200",
    ringDark: "ring-neutral-700",
    activeLight: "bg-slate-900 text-white shadow-lg shadow-slate-900/15",
    activeDark: "bg-white text-neutral-950 shadow-lg shadow-black/40",
    badgeLight: "bg-white/20 text-white",
    badgeDark: "bg-neutral-900/15 text-neutral-900",
  },
  {
    id: "EQUITY",
    label: "Cash",
    subtitle: "NSE / BSE equity",
    icon: Banknote,
    ringLight: "ring-blue-200",
    ringDark: "ring-blue-500/30",
    activeLight: "bg-blue-600 text-white shadow-lg shadow-blue-600/25",
    activeDark: "bg-blue-500 text-white shadow-lg shadow-blue-500/30",
    badgeLight: "bg-white/20 text-white",
    badgeDark: "bg-white/20 text-white",
  },
  {
    id: "FUTURES",
    label: "Futures",
    subtitle: "Index & stock F&O",
    icon: LineChart,
    ringLight: "ring-violet-200",
    ringDark: "ring-violet-500/30",
    activeLight: "bg-violet-600 text-white shadow-lg shadow-violet-600/25",
    activeDark: "bg-violet-500 text-white shadow-lg shadow-violet-500/30",
    badgeLight: "bg-white/20 text-white",
    badgeDark: "bg-white/20 text-white",
  },
  {
    id: "OPTIONS",
    label: "Options",
    subtitle: "Calls & puts",
    icon: Layers,
    ringLight: "ring-pink-200",
    ringDark: "ring-pink-500/30",
    activeLight: "bg-pink-600 text-white shadow-lg shadow-pink-600/25",
    activeDark: "bg-pink-500 text-white shadow-lg shadow-pink-500/30",
    badgeLight: "bg-white/20 text-white",
    badgeDark: "bg-white/20 text-white",
  },
  {
    id: "COMMODITY",
    label: "Commodity",
    subtitle: "MCX metals & agri",
    icon: Wheat,
    ringLight: "ring-amber-200",
    ringDark: "ring-amber-500/30",
    activeLight: "bg-amber-600 text-white shadow-lg shadow-amber-600/25",
    activeDark: "bg-amber-500 text-white shadow-lg shadow-amber-500/30",
    badgeLight: "bg-white/20 text-white",
    badgeDark: "bg-white/20 text-white",
  },
  {
    id: "CURRENCY",
    label: "Currency",
    subtitle: "CDS pairs",
    icon: Globe,
    ringLight: "ring-teal-200",
    ringDark: "ring-teal-500/30",
    activeLight: "bg-teal-600 text-white shadow-lg shadow-teal-600/25",
    activeDark: "bg-teal-500 text-white shadow-lg shadow-teal-500/30",
    badgeLight: "bg-white/20 text-white",
    badgeDark: "bg-white/20 text-white",
  },
];

export function normalizeStrategyAssetClass(raw: string | null | undefined): AssetClassTabId {
  const v = String(raw ?? "EQUITY").trim().toUpperCase();
  if (v === "FUTURE") return "FUTURES";
  if (v === "CASH") return "EQUITY";
  if (ASSET_CLASS_TABS.some((t) => t.id === v && v !== "ALL")) return v as AssetClassTabId;
  return "EQUITY";
}

export function AssetClassTabs({
  active,
  onChange,
  counts,
  variant = "dark",
}: {
  active: AssetClassTabId;
  onChange: (id: AssetClassTabId) => void;
  counts: Partial<Record<AssetClassTabId, number>>;
  variant?: "dark" | "light";
}) {
  const isLight = variant === "light";

  return (
    <div
      role="tablist"
      aria-label="Strategy asset class"
      className={cn(
        "flex gap-2 overflow-x-auto pb-1 [-ms-overflow-style:none] [scrollbar-width:none] [&::-webkit-scrollbar]:hidden",
      )}
    >
      {ASSET_CLASS_TABS.map((tab) => {
        const selected = tab.id === active;
        const Icon = tab.icon;
        const count = counts[tab.id] ?? 0;
        const hidden = tab.id !== "ALL" && count === 0;

        return (
          <button
            key={tab.id}
            role="tab"
            type="button"
            aria-selected={selected}
            onClick={() => onChange(tab.id)}
            className={cn(
              "group relative flex min-w-[132px] shrink-0 flex-col items-start rounded-2xl border px-3.5 py-3 text-left transition-all duration-200",
              selected
                ? cn("border-transparent ring-2", isLight ? tab.ringLight : tab.ringDark, isLight ? tab.activeLight : tab.activeDark)
                : isLight
                  ? "border-border bg-card text-foreground hover:border-blue-200 hover:bg-blue-50/40"
                  : "border-neutral-800 bg-neutral-950/80 text-neutral-200 hover:border-neutral-700 hover:bg-neutral-900",
              hidden && !selected && "opacity-45",
            )}
          >
            {selected ? (
              <motion.span
                layoutId="strategy-asset-tab-glow"
                className="pointer-events-none absolute inset-0 rounded-2xl"
                transition={{ type: "spring", stiffness: 420, damping: 34 }}
              />
            ) : null}
            <div className="relative flex w-full items-center justify-between gap-2">
              <span
                className={cn(
                  "inline-flex rounded-xl p-1.5 ring-1",
                  selected
                    ? cn(isLight ? tab.badgeLight : tab.badgeDark, "ring-white/20")
                    : isLight
                      ? "bg-muted text-muted-foreground ring-border"
                      : "bg-neutral-900 text-neutral-400 ring-neutral-800",
                )}
              >
                <Icon className="h-4 w-4" />
              </span>
              <span
                className={cn(
                  "rounded-full px-2 py-0.5 text-[10px] font-bold tabular-nums",
                  selected
                    ? isLight
                      ? tab.badgeLight
                      : tab.badgeDark
                    : isLight
                      ? "bg-muted text-muted-foreground"
                      : "bg-neutral-800 text-neutral-400",
                )}
              >
                {count}
              </span>
            </div>
            <div className="relative mt-2">
              <div className={cn("text-sm font-semibold leading-none", selected ? "" : isLight ? "text-foreground" : "text-white")}>
                {tab.label}
              </div>
              <div
                className={cn(
                  "mt-1 text-[10px] font-medium leading-tight",
                  selected
                    ? "text-white/75"
                    : isLight
                      ? "text-muted-foreground"
                      : "text-neutral-500",
                )}
              >
                {tab.subtitle}
              </div>
            </div>
          </button>
        );
      })}
    </div>
  );
}
