import { AnimatePresence, motion, useReducedMotion } from "framer-motion";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Bell,
  ChevronRight,
  LogOut,
  MessageSquare,
  Moon,
  Search,
  SlidersHorizontal,
  Sun,
  Zap,
} from "lucide-react";
import type { ReactNode } from "react";
import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../../api/client";
import {
  TRADER_EXECUTION_MODE_QUERY_KEY,
  type TraderExecutionMode,
  invalidateTraderExecutionModeQueries,
} from "../../lib/traderExecutionMode";
import { cn } from "../../lib/utils";
import { useUiThemeStore } from "../../state/uiTheme";
import { toast } from "sonner";

type TickerRow = {
  sym: string;
  price: string;
  chg: string;
  up: boolean;
};

function NavIconButton({
  label,
  onClick,
  isLight,
  children,
  badge,
  active,
}: {
  label: string;
  onClick: () => void;
  isLight: boolean;
  children: ReactNode;
  badge?: string | number;
  active?: boolean;
}) {
  return (
    <motion.button
      type="button"
      onClick={onClick}
      whileHover={{ y: -1, scale: 1.02 }}
      whileTap={{ scale: 0.98 }}
      aria-label={label}
      className={cn(
        "relative flex h-10 w-10 items-center justify-center rounded-xl border transition-colors duration-300",
        active
          ? isLight
            ? "border-blue-300/80 bg-blue-50 text-blue-700 shadow-[0_0_20px_-6px_rgba(59,130,246,0.45)]"
            : "border-blue-500/40 bg-blue-500/10 text-blue-200 shadow-[0_0_24px_-8px_rgba(59,130,246,0.55)]"
          : isLight
            ? "border-neutral-200/90 bg-white/80 text-neutral-600 hover:border-neutral-300 hover:bg-neutral-50 hover:text-neutral-900"
            : "border-white/[0.08] bg-neutral-900/50 text-neutral-300 hover:border-white/15 hover:bg-neutral-800/80 hover:text-white",
      )}
    >
      {children}
      {badge != null && Number(badge) > 0 ? (
        <motion.span
          initial={{ scale: 0 }}
          animate={{ scale: 1 }}
          className="absolute -right-0.5 -top-0.5 flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-gradient-to-br from-rose-500 to-rose-600 px-1 text-[10px] font-black text-white shadow-[0_0_12px_rgba(244,63,94,0.55)]"
        >
          {typeof badge === "number" && badge > 9 ? "9+" : badge}
        </motion.span>
      ) : null}
    </motion.button>
  );
}

function LiveTickerTape({ tickers, isLight, loading }: { tickers: TickerRow[]; isLight: boolean; loading: boolean }) {
  const reduceMotion = useReducedMotion();
  const loop = useMemo(() => (tickers.length > 0 ? [...tickers, ...tickers] : []), [tickers]);

  if (loading && tickers.length === 0) {
    return (
      <div className="flex h-10 items-center gap-2 px-2">
        {[1, 2, 3].map((i) => (
          <div key={i} className={cn("h-8 w-28 animate-pulse rounded-lg", isLight ? "bg-neutral-100" : "bg-neutral-800/60")} />
        ))}
      </div>
    );
  }

  if (tickers.length === 0) {
    return (
      <div className={cn("flex h-10 items-center gap-2 px-3 text-[11px]", isLight ? "text-neutral-400" : "text-neutral-500")}>
        <span className="relative flex h-2 w-2">
          <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-amber-400 opacity-40" />
          <span className="relative inline-flex h-2 w-2 rounded-full bg-amber-500" />
        </span>
        Awaiting market watch feed
      </div>
    );
  }

  return (
    <div className="relative min-w-0 flex-1 overflow-hidden">
      <div
        className={cn(
          "pointer-events-none absolute inset-y-0 left-0 z-10 w-10",
          isLight ? "bg-gradient-to-r from-white via-white/90 to-transparent" : "bg-gradient-to-r from-neutral-950 via-neutral-950/90 to-transparent",
        )}
      />
      <div
        className={cn(
          "pointer-events-none absolute inset-y-0 right-0 z-10 w-10",
          isLight ? "bg-gradient-to-l from-white via-white/90 to-transparent" : "bg-gradient-to-l from-neutral-950 via-neutral-950/90 to-transparent",
        )}
      />

      <motion.div
        className="flex w-max items-center gap-2 py-1"
        animate={reduceMotion ? undefined : { x: ["0%", "-50%"] }}
        transition={reduceMotion ? undefined : { duration: Math.max(18, tickers.length * 6), repeat: Infinity, ease: "linear" }}
      >
        {loop.map((t, i) => (
          <motion.div
            key={`${t.sym}-${i}`}
            whileHover={{ scale: 1.03, y: -1 }}
            className={cn(
              "group flex shrink-0 items-center gap-2.5 rounded-xl border px-3 py-1.5 backdrop-blur-sm transition-shadow duration-300",
              isLight
                ? "border-neutral-200/80 bg-gradient-to-b from-white to-neutral-50/90 shadow-[0_1px_0_rgba(255,255,255,0.8)_inset,0_8px_24px_-16px_rgba(15,23,42,0.25)] hover:border-blue-200/70 hover:shadow-[0_12px_32px_-20px_rgba(59,130,246,0.35)]"
                : "border-white/[0.07] bg-gradient-to-b from-neutral-900/80 to-neutral-950/90 shadow-[inset_0_1px_0_rgba(255,255,255,0.04)] hover:border-blue-500/25",
            )}
          >
            <span className={cn("text-[10px] font-bold uppercase tracking-[0.14em]", isLight ? "text-neutral-500" : "text-neutral-400")}>
              {t.sym.replace(/^NSE:/, "")}
            </span>
            <span className={cn("font-mono text-[13px] font-semibold tabular-nums tracking-tight", isLight ? "text-neutral-900" : "text-white")}>
              {t.price}
            </span>
            <span
              className={cn(
                "rounded-md px-1.5 py-0.5 font-mono text-[10px] font-bold tabular-nums",
                t.up
                  ? isLight ? "bg-emerald-500/10 text-emerald-600" : "bg-emerald-500/15 text-emerald-400"
                  : isLight ? "bg-rose-500/10 text-rose-600" : "bg-rose-500/15 text-rose-400",
              )}
            >
              {t.up && !String(t.chg).startsWith("+") ? "+" : ""}{t.chg}
            </span>
          </motion.div>
        ))}
      </motion.div>
    </div>
  );
}

function ExecutionModeToggle({
  paperMode,
  liveApproved,
  pending,
  isLight,
  onPaper,
  onLive,
}: {
  paperMode: boolean;
  liveApproved: boolean;
  pending: boolean;
  isLight: boolean;
  onPaper: () => void;
  onLive: () => void;
}) {
  return (
    <div
      className={cn(
        "relative flex items-center rounded-2xl border p-1 shadow-inner",
        isLight ? "border-neutral-200/90 bg-neutral-100/80" : "border-white/[0.08] bg-neutral-900/60",
      )}
    >
      <motion.div
        layout
        transition={{ type: "spring", stiffness: 420, damping: 32 }}
        className={cn(
          "absolute inset-y-1 w-[calc(50%-4px)] rounded-xl shadow-sm",
          paperMode
            ? isLight ? "left-1 bg-white shadow-[0_8px_24px_-12px_rgba(15,23,42,0.18)]" : "left-1 bg-white/95 text-neutral-950"
            : isLight
              ? "left-[calc(50%+2px)] bg-gradient-to-br from-orange-500 to-rose-500 shadow-[0_8px_28px_-10px_rgba(249,115,22,0.55)]"
              : "left-[calc(50%+2px)] bg-gradient-to-br from-orange-500 to-rose-500 shadow-[0_8px_28px_-10px_rgba(249,115,22,0.45)]",
        )}
      />
      <button
        type="button"
        onClick={onPaper}
        disabled={pending}
        className={cn(
          "relative z-10 min-w-[4.5rem] rounded-xl px-3 py-2 text-[10px] font-black uppercase tracking-[0.16em] transition-colors",
          pending && "opacity-60",
          paperMode ? (isLight ? "text-neutral-900" : "text-neutral-950") : isLight ? "text-neutral-500" : "text-neutral-500",
        )}
      >
        Paper
      </button>
      <button
        type="button"
        onClick={onLive}
        disabled={!liveApproved || pending}
        className={cn(
          "relative z-10 flex min-w-[4.5rem] items-center justify-center gap-1 rounded-xl px-3 py-2 text-[10px] font-black uppercase tracking-[0.16em] transition-colors",
          pending && "opacity-60",
          !paperMode ? "text-white" : isLight ? "text-neutral-500" : "text-neutral-500",
          !liveApproved && "cursor-not-allowed opacity-45",
        )}
      >
        {!paperMode ? (
          <motion.span
            className="h-1.5 w-1.5 rounded-full bg-white"
            animate={{ opacity: [1, 0.35, 1] }}
            transition={{ duration: 1.4, repeat: Infinity }}
          />
        ) : null}
        Live
      </button>
    </div>
  );
}

export function WorkspaceTopNav({
  displayName,
  username,
  unread,
  messageUnread,
  onNotificationClick,
  onMessageClick,
  liveApproved,
  rightExtra,
}: {
  displayName?: string | null;
  username?: string | null;
  unread: number;
  messageUnread: number;
  onNotificationClick: () => void;
  onMessageClick: () => void;
  liveApproved: boolean;
  rightExtra?: ReactNode;
}) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const mode = useUiThemeStore((s) => s.mode);
  const toggleTheme = useUiThemeStore((s) => s.toggle);
  const isLight = mode === "light";
  const [search, setSearch] = useState("");
  const [searchFocused, setSearchFocused] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const marketWatchQuery = useQuery<TickerRow[]>({
    queryKey: ["workspace-topnav-watch"],
    queryFn: async () => {
      const res = await api.get("/api/trader/terminal/market/watch");
      const rows = Array.isArray(res.data?.data) ? res.data.data : [];
      return rows.slice(0, 6).map((row: Record<string, unknown>) => ({
        sym: String(row.symbol ?? "-"),
        price: String(row.price ?? "-"),
        chg: String(row.changePct ?? "-"),
        up: Number(row.changePct ?? 0) >= 0,
      }));
    },
    staleTime: 10_000,
    refetchInterval: 20_000,
  });

  const executionModeQuery = useQuery<TraderExecutionMode>({
    queryKey: [...TRADER_EXECUTION_MODE_QUERY_KEY],
    queryFn: async () => {
      const res = await api.get("/api/trader/me/execution-mode");
      const raw = String(res.data?.data?.executionMode ?? "PAPER").toUpperCase();
      return raw === "LIVE" ? "LIVE" : "PAPER";
    },
    staleTime: 30_000,
    refetchOnWindowFocus: true,
  });

  const updateMode = useMutation({
    mutationFn: async (next: TraderExecutionMode) => {
      const res = await api.put("/api/trader/me/execution-mode", { executionMode: next });
      const saved = String(res.data?.data?.executionMode ?? next).toUpperCase();
      return saved === "LIVE" ? "LIVE" : "PAPER";
    },
    onMutate: async (next) => {
      await queryClient.cancelQueries({ queryKey: [...TRADER_EXECUTION_MODE_QUERY_KEY] });
      const previous = queryClient.getQueryData<TraderExecutionMode>([...TRADER_EXECUTION_MODE_QUERY_KEY]);
      queryClient.setQueryData<TraderExecutionMode>([...TRADER_EXECUTION_MODE_QUERY_KEY], next);
      return { previous };
    },
    onError: (_err, _next, context) => {
      if (context?.previous) {
        queryClient.setQueryData([...TRADER_EXECUTION_MODE_QUERY_KEY], context.previous);
      }
      toast.error("Could not update execution mode.");
    },
    onSuccess: (saved) => {
      queryClient.setQueryData<TraderExecutionMode>([...TRADER_EXECUTION_MODE_QUERY_KEY], saved);
      invalidateTraderExecutionModeQueries(queryClient);
      toast.success(saved === "LIVE" ? "Switched to Live execution" : "Switched to Paper execution");
    },
  });

  const selectedMode: TraderExecutionMode =
    updateMode.isPending && updateMode.variables
      ? updateMode.variables
      : executionModeQuery.data ?? (liveApproved ? "LIVE" : "PAPER");
  const paperMode = selectedMode !== "LIVE";

  const sanitizedDisplayName =
    displayName && displayName !== "Platform Admin" && displayName !== "Super Admin" ? displayName : null;
  const label = sanitizedDisplayName || username || "Trader";
  const tickers: TickerRow[] = marketWatchQuery.data ?? [];

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        inputRef.current?.focus();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  function runSearch() {
    const term = search.trim().toLowerCase();
    if (!term) return;
    if (term.includes("order")) return navigate("/orders");
    if (term.includes("position")) return navigate("/positions");
    if (term.includes("execut")) return navigate("/executions");
    if (term.includes("strateg")) return navigate("/strategies");
    if (term.includes("signal")) return navigate("/signals");
    if (term.includes("backtest")) return navigate("/backtests/launch");
    if (term.includes("broker")) return navigate("/brokers");
    navigate("/terminal");
  }

  function selectPaper() {
    if (updateMode.isPending || selectedMode === "PAPER") return;
    updateMode.mutate("PAPER");
  }

  function selectLive() {
    if (!liveApproved) {
      toast.message("LIVE not approved yet. Complete onboarding and ops approval.");
      return;
    }
    if (updateMode.isPending || selectedMode === "LIVE") return;
    updateMode.mutate("LIVE");
  }

  return (
    <div className="relative overflow-hidden">
      <motion.div
        aria-hidden
        className={cn(
          "pointer-events-none absolute inset-x-0 top-0 h-px",
          isLight
            ? "bg-gradient-to-r from-transparent via-blue-400/50 to-transparent"
            : "bg-gradient-to-r from-transparent via-blue-500/35 to-transparent",
        )}
        animate={{ opacity: [0.35, 0.85, 0.35] }}
        transition={{ duration: 4, repeat: Infinity, ease: "easeInOut" }}
      />

      <div
        className={cn(
          "relative border-b px-3 py-3 backdrop-blur-2xl sm:px-5 lg:px-6",
          isLight
            ? "border-neutral-200/80 bg-[linear-gradient(180deg,rgba(255,255,255,0.96)_0%,rgba(248,250,252,0.88)_100%)]"
            : "border-white/[0.06] bg-[linear-gradient(180deg,rgba(10,10,10,0.92)_0%,rgba(10,10,10,0.78)_100%)]",
        )}
      >
        <div className="flex flex-col gap-3 xl:grid xl:grid-cols-[minmax(220px,320px)_minmax(0,1fr)_auto] xl:items-center xl:gap-5">
          {/* Command search */}
          <motion.div
            animate={
              searchFocused
                ? isLight
                  ? { boxShadow: "0 0 0 1px rgba(59,130,246,0.25), 0 16px 40px -24px rgba(59,130,246,0.45)" }
                  : { boxShadow: "0 0 0 1px rgba(59,130,246,0.35), 0 16px 40px -24px rgba(59,130,246,0.35)" }
                : { boxShadow: "0 0 0 0px transparent" }
            }
            className={cn(
              "relative min-w-0 rounded-2xl transition-shadow duration-300",
              isLight ? "bg-white/70" : "bg-neutral-900/40",
            )}
          >
            <Search
              className={cn(
                "pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 transition-colors",
                searchFocused ? "text-blue-500" : isLight ? "text-neutral-400" : "text-neutral-500",
              )}
            />
            <input
              ref={inputRef}
              type="search"
              placeholder="Search symbols, strategies, orders..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              onFocus={() => setSearchFocused(true)}
              onBlur={() => setSearchFocused(false)}
              onKeyDown={(e) => {
                if (e.key === "Enter") runSearch();
              }}
              className={cn(
                "w-full rounded-2xl border py-2.5 pl-10 pr-16 text-[13px] outline-none transition-colors duration-300 focus:ring-0",
                isLight
                  ? "border-neutral-200/80 bg-white/90 text-neutral-800 placeholder:text-neutral-400 focus:border-blue-400/60"
                  : "border-white/[0.08] bg-neutral-950/50 text-neutral-100 placeholder:text-neutral-600 focus:border-blue-500/40",
              )}
              aria-label="Search"
            />
            <kbd
              className={cn(
                "pointer-events-none absolute right-3 top-1/2 hidden -translate-y-1/2 rounded-lg border px-2 py-0.5 font-mono text-[10px] sm:inline-block",
                isLight ? "border-neutral-200 bg-neutral-50 text-neutral-500" : "border-neutral-700 bg-neutral-800/90 text-neutral-500",
              )}
            >
              ⌘K
            </kbd>
          </motion.div>

          {/* Live ticker */}
          <div className="flex min-w-0 items-center gap-3">
            <div className="hidden shrink-0 items-center gap-2 lg:flex">
              <span className="relative flex h-2 w-2">
                <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-35" />
                <span className="relative inline-flex h-2 w-2 rounded-full bg-emerald-500" />
              </span>
              <span className={cn("text-[10px] font-bold uppercase tracking-[0.18em]", isLight ? "text-neutral-500" : "text-neutral-400")}>
                Tape
              </span>
            </div>
            <LiveTickerTape tickers={tickers} isLight={isLight} loading={marketWatchQuery.isLoading} />
          </div>

          {/* Controls rail */}
          <div className="flex flex-wrap items-center justify-end gap-2 xl:justify-end">
            <ExecutionModeToggle
              paperMode={paperMode}
              liveApproved={liveApproved}
              pending={updateMode.isPending}
              isLight={isLight}
              onPaper={selectPaper}
              onLive={selectLive}
            />

            <motion.button
              type="button"
              whileHover={{ y: -1 }}
              whileTap={{ scale: 0.98 }}
              className={cn(
                "hidden items-center gap-2 rounded-2xl border px-3 py-2 text-[11px] font-semibold transition-colors duration-300 sm:inline-flex",
                isLight
                  ? "border-neutral-200/90 bg-white/80 text-neutral-600 hover:border-neutral-300 hover:text-neutral-900"
                  : "border-white/[0.08] bg-neutral-900/50 text-neutral-400 hover:border-white/15 hover:text-neutral-100",
              )}
              onClick={() => navigate("/terminal")}
            >
              <SlidersHorizontal className="h-4 w-4" />
              Customize
            </motion.button>

            <NavIconButton label={isLight ? "Switch to dark mode" : "Switch to light mode"} onClick={toggleTheme} isLight={isLight}>
              <AnimatePresence mode="wait" initial={false}>
                <motion.span
                  key={isLight ? "moon" : "sun"}
                  initial={{ rotate: -20, opacity: 0, scale: 0.8 }}
                  animate={{ rotate: 0, opacity: 1, scale: 1 }}
                  exit={{ rotate: 20, opacity: 0, scale: 0.8 }}
                  transition={{ duration: 0.2 }}
                >
                  {isLight ? <Moon className="h-4 w-4" /> : <Sun className="h-4 w-4" />}
                </motion.span>
              </AnimatePresence>
            </NavIconButton>

            <NavIconButton
              label="Messages"
              onClick={onMessageClick}
              isLight={isLight}
              badge={messageUnread > 0 ? messageUnread : undefined}
            >
              <MessageSquare className="h-4 w-4" />
            </NavIconButton>

            <NavIconButton
              label="Notifications"
              onClick={onNotificationClick}
              isLight={isLight}
              badge={unread > 0 ? unread : undefined}
            >
              <Bell className="h-4 w-4" />
            </NavIconButton>

            <motion.div
              whileHover={{ y: -1 }}
              className={cn(
                "flex items-center gap-2 rounded-2xl border py-1.5 pl-1.5 pr-3",
                isLight
                  ? "border-neutral-200/90 bg-gradient-to-r from-white to-neutral-50/90 shadow-[0_10px_30px_-22px_rgba(15,23,42,0.28)]"
                  : "border-white/[0.08] bg-gradient-to-r from-neutral-900/90 to-neutral-950/90",
              )}
            >
              <div className="relative flex h-9 w-9 shrink-0 items-center justify-center overflow-hidden rounded-xl bg-gradient-to-br from-blue-500 via-blue-600 to-indigo-700 text-[12px] font-bold text-white shadow-[0_10px_24px_-12px_rgba(59,130,246,0.65)]">
                <span aria-hidden>{label.slice(0, 1).toUpperCase()}</span>
                {!paperMode ? (
                  <span className="absolute -bottom-0.5 -right-0.5 h-2.5 w-2.5 rounded-full border-2 border-white bg-orange-500" />
                ) : null}
              </div>
              <div className="min-w-0">
                <div className={cn("truncate text-[12px] font-semibold", isLight ? "text-neutral-900" : "text-white")}>{label}</div>
                <div className="flex items-center gap-1.5">
                  <span
                    className={cn(
                      "inline-flex items-center gap-1 rounded-full px-1.5 py-px text-[9px] font-black uppercase tracking-wide",
                      paperMode
                        ? isLight ? "bg-sky-100 text-sky-800" : "bg-sky-500/20 text-sky-200"
                        : isLight ? "bg-orange-100 text-orange-800" : "bg-orange-500/20 text-orange-200",
                    )}
                  >
                    {!paperMode ? <Zap className="h-2.5 w-2.5" /> : null}
                    {paperMode ? "Paper" : "Live"}
                  </span>
                </div>
              </div>
              <ChevronRight className={cn("hidden h-3.5 w-3.5 opacity-40 lg:block", isLight ? "text-neutral-400" : "text-neutral-500")} />
            </motion.div>

            {rightExtra ?? (
              <motion.button
                type="button"
                whileHover={{ y: -1 }}
                whileTap={{ scale: 0.98 }}
                onClick={() => navigate("/login")}
                className={cn(
                  "inline-flex items-center gap-2 rounded-2xl border px-4 py-2 text-[11px] font-bold uppercase tracking-[0.14em]",
                  isLight
                    ? "border-neutral-200 bg-white text-neutral-700 hover:border-neutral-300"
                    : "border-white/[0.1] bg-neutral-900/60 text-neutral-300 hover:border-neutral-600",
                )}
              >
                <LogOut className="h-3.5 w-3.5" />
                Sign out
              </motion.button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
