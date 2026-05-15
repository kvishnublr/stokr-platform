import { motion } from "framer-motion";
import { useQuery } from "@tanstack/react-query";
import {
  Bell,
  MessageSquare,
  Moon,
  Search,
  SlidersHorizontal,
  Sun,
  Wifi,
} from "lucide-react";
import type { ReactNode } from "react";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../../api/client";
import { cn } from "../../lib/utils";
import { useUiThemeStore } from "../../state/uiTheme";

type TickerRow = {
  sym: string;
  price: string;
  chg: string;
  up: boolean;
};

export function WorkspaceTopNav({
  displayName,
  username,
  unread,
  onNotificationClick,
  liveApproved,
  rightExtra,
}: {
  displayName?: string | null;
  username?: string | null;
  unread: number;
  onNotificationClick: () => void;
  liveApproved: boolean;
  /** Sign out + optional slots */
  rightExtra?: ReactNode;
}) {
  const navigate = useNavigate();
  const [paperMode, setPaperMode] = useState(!liveApproved);
  const mode = useUiThemeStore((s) => s.mode);
  const toggleTheme = useUiThemeStore((s) => s.toggle);
  const isLight = mode === "light";
  const [search, setSearch] = useState("");
  const marketWatchQuery = useQuery<TickerRow[]>({
    queryKey: ["workspace-topnav-watch"],
    queryFn: async () => {
      const res = await api.get("/api/trader/terminal/market/watch");
      const rows = Array.isArray(res.data?.data) ? res.data.data : [];
      return rows.slice(0, 3).map((row: any) => ({
        sym: String(row.symbol ?? "—"),
        price: String(row.price ?? "—"),
        chg: String(row.changePct ?? "—"),
        up: Number(row.changePct ?? 0) >= 0,
      }));
    },
    staleTime: 10_000,
    refetchInterval: 20_000,
  });

  useEffect(() => {
    setPaperMode(!liveApproved);
  }, [liveApproved]);

  const sanitizedDisplayName =
    displayName && displayName !== "Platform Admin" && displayName !== "Super Admin" ? displayName : null;
  const label = sanitizedDisplayName || username || "Trader";
  const tickers: TickerRow[] = marketWatchQuery.data ?? [];

  function runSearch() {
    const term = search.trim().toLowerCase();
    if (!term) return;
    if (term.includes("order")) return navigate("/orders");
    if (term.includes("position")) return navigate("/positions");
    if (term.includes("execut")) return navigate("/executions");
    if (term.includes("strateg")) return navigate("/strategies");
    if (term.includes("backtest")) return navigate("/backtests/launch");
    if (term.includes("broker")) return navigate("/brokers");
    navigate("/terminal");
  }

  return (
    <div
      className={cn(
        "border-b px-3 py-3 backdrop-blur-xl sm:px-5 lg:px-8",
        isLight ? "border-neutral-200 bg-white/90" : "border-white/[0.06] bg-neutral-950/80",
      )}
    >
      <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between lg:gap-6">
        <div
          className={cn(
            "relative min-w-0 max-w-xl flex-1 rounded-xl transition-shadow",
            isLight
              ? "focus-within:shadow-[0_0_0_3px_rgba(59,130,246,0.12)]"
              : "focus-within:shadow-[0_0_0_3px_rgba(59,130,246,0.15)]",
          )}
        >
          <Search
            className={cn(
              "pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2",
              isLight ? "text-neutral-400" : "text-neutral-500",
            )}
          />
          <input
            type="search"
            placeholder="Search symbols, strategies, orders…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") runSearch();
            }}
            className={cn(
              "w-full rounded-xl border py-2.5 pl-10 pr-16 text-[13px] outline-none transition focus:ring-0",
              isLight
                ? "border-neutral-200/90 bg-white text-neutral-800 shadow-sm placeholder:text-neutral-400 focus:border-blue-400/70"
                : "border-white/[0.08] bg-neutral-900/70 text-neutral-200 placeholder:text-neutral-600 focus:border-blue-500/45",
            )}
            aria-label="Search"
          />
          <kbd
            className={cn(
              "pointer-events-none absolute right-3 top-1/2 hidden -translate-y-1/2 rounded-md border px-1.5 py-0.5 font-mono text-[10px] shadow-sm sm:inline-block",
              isLight ? "border-neutral-200 bg-neutral-50 text-neutral-500" : "border-neutral-700 bg-neutral-800/90 text-neutral-500",
            )}
          >
            ⌘ K
          </kbd>
        </div>

        <div className="flex min-w-0 flex-1 items-center gap-3 overflow-x-auto pb-1 lg:justify-center lg:pb-0">
          {tickers.length === 0 ? (
            <span className={cn("text-[10px]", isLight ? "text-neutral-400" : "text-neutral-600")}>No market watch data</span>
          ) : null}
          {tickers.map((t) => (
            <motion.div
              key={t.sym}
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              whileHover={{ y: -1 }}
              transition={{ type: "spring", stiffness: 400, damping: 28 }}
              className={cn(
                "flex shrink-0 cursor-default items-baseline gap-2 rounded-lg border px-3 py-1.5 shadow-sm transition-colors",
                isLight
                  ? "border-neutral-200/90 bg-gradient-to-b from-white to-neutral-50/90 hover:border-blue-200/80"
                  : "border-white/[0.06] bg-neutral-900/50 hover:border-neutral-600",
              )}
            >
              <span
                className={cn("text-[11px] font-semibold uppercase tracking-wide", isLight ? "text-neutral-500" : "text-neutral-500")}
              >
                {t.sym}
              </span>
              <span className={cn("font-mono text-[13px]", isLight ? "text-neutral-900" : "text-white")}>{t.price}</span>
              <span className={cn("font-mono text-[11px]", t.up ? "text-emerald-600" : "text-rose-500")}>{t.chg}</span>
            </motion.div>
          ))}
          <span
            className={cn("hidden items-center gap-1 text-[10px] xl:flex", isLight ? "text-neutral-400" : "text-neutral-600")}
          >
            <Wifi className="h-3 w-3" /> Live market watch
          </span>
        </div>

        <div className="flex flex-wrap items-center justify-end gap-2">
          <div
            className={cn(
              "flex items-center rounded-xl border p-0.5",
              isLight ? "border-neutral-200 bg-neutral-100" : "border-white/[0.08] bg-neutral-900/50",
            )}
          >
            <button
              type="button"
              onClick={() => setPaperMode(true)}
              className={cn(
                "rounded-lg px-3 py-1.5 text-[11px] font-bold uppercase tracking-wide transition",
                paperMode
                  ? isLight
                    ? "bg-white text-neutral-900 shadow-sm"
                    : "bg-white text-neutral-950 shadow"
                  : isLight
                    ? "text-neutral-500 hover:text-neutral-800"
                    : "text-neutral-500 hover:text-neutral-300",
              )}
            >
              Paper
            </button>
            <button
              type="button"
              onClick={() => setPaperMode(false)}
              className={cn(
                "rounded-lg px-3 py-1.5 text-[11px] font-bold uppercase tracking-wide transition",
                !paperMode
                  ? isLight
                    ? "bg-orange-500 text-white shadow-md shadow-orange-500/25"
                    : "bg-orange-500 text-white shadow-md shadow-orange-600/30"
                  : isLight
                    ? "text-neutral-500 hover:text-neutral-800"
                    : "text-neutral-500 hover:text-neutral-300",
              )}
            >
              Live
            </button>
          </div>

          <button
            type="button"
            className={cn(
              "hidden items-center gap-2 rounded-xl border px-3 py-2 text-[11px] font-semibold transition sm:inline-flex",
              isLight
                ? "border-neutral-200 text-neutral-600 hover:border-neutral-300 hover:bg-neutral-50 hover:text-neutral-900"
                : "border-white/[0.08] text-neutral-400 hover:border-neutral-600 hover:text-neutral-200",
            )}
            onClick={() => navigate("/terminal")}
          >
            <SlidersHorizontal className="h-4 w-4" />
            Customize
          </button>

          <button
            type="button"
            onClick={toggleTheme}
            className={cn(
              "flex h-10 w-10 items-center justify-center rounded-xl border transition",
              isLight ? "border-neutral-200 bg-white text-neutral-700 hover:bg-neutral-50" : "border-white/[0.08] bg-neutral-900/60 hover:bg-neutral-800",
            )}
            aria-label={isLight ? "Switch to dark mode" : "Switch to light mode"}
          >
            {isLight ? <Moon className="h-4 w-4" /> : <Sun className="h-4 w-4" />}
          </button>

          <button
            type="button"
            className={cn(
              "relative flex h-10 w-10 items-center justify-center rounded-xl border",
              isLight
                ? "border-neutral-200 bg-white text-neutral-600 hover:bg-neutral-50"
                : "border-white/[0.08] bg-neutral-900/60 text-neutral-300 hover:bg-neutral-800",
            )}
            aria-label="Messages"
            onClick={() => navigate("/terminal")}
          >
            <MessageSquare className="h-4 w-4" />
            <span className="absolute -right-0.5 -top-0.5 flex h-4 min-w-[16px] items-center justify-center rounded-full bg-rose-500 px-1 text-[9px] font-bold text-white">
              2
            </span>
          </button>

          <button
            type="button"
            onClick={onNotificationClick}
            className={cn(
              "relative flex h-10 w-10 items-center justify-center rounded-xl border transition",
              isLight
                ? "border-neutral-200 bg-white text-neutral-700 hover:bg-neutral-50"
                : "border-white/[0.08] bg-neutral-900/60 text-neutral-200 hover:bg-neutral-800",
            )}
            aria-label="Notifications"
          >
            <Bell className="h-4 w-4" />
            <span className="absolute -right-0.5 -top-0.5 flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-rose-500 px-1 text-[10px] font-black text-white">
              {unread > 0 ? (unread > 9 ? "9+" : unread) : 3}
            </span>
          </button>

          <div
            className={cn(
              "flex items-center gap-2 rounded-xl border py-1.5 pl-2 pr-3 ring-1",
              isLight
                ? "border-neutral-100 bg-neutral-50 ring-neutral-100"
                : "border-white/[0.08] bg-gradient-to-r from-neutral-900/90 to-neutral-950/90",
            )}
          >
            <div className="flex h-9 w-9 shrink-0 items-center justify-center overflow-hidden rounded-lg bg-gradient-to-br from-blue-500/25 to-blue-700/35 text-[12px] font-bold text-blue-950 dark:text-white">
              <span aria-hidden>{label.slice(0, 1).toUpperCase()}</span>
            </div>
            <div className="min-w-0">
              <div className={cn("truncate text-[12px] font-semibold", isLight ? "text-neutral-900" : "text-white")}>
                {label}
              </div>
              <div className="flex items-center gap-1.5">
                <span
                  className={cn(
                    "rounded-full px-1.5 py-px text-[9px] font-black uppercase tracking-wide",
                    paperMode
                      ? isLight
                        ? "bg-sky-100 text-sky-800"
                        : "bg-sky-500/20 text-sky-200"
                      : isLight
                        ? "bg-emerald-100 text-emerald-800"
                        : "bg-emerald-500/20 text-emerald-200",
                  )}
                >
                  {paperMode ? "Paper" : "Live"}
                </span>
              </div>
            </div>
          </div>

          {rightExtra}
        </div>
      </div>
    </div>
  );
}
