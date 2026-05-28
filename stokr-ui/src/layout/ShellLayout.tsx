import { Outlet, useNavigate, useLocation } from "react-router-dom";
import { useEffect, useMemo, useRef, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Activity,
  ActivitySquare,
  AlertOctagon,
  BarChart3,
  Bell,
  Building2,
  ClipboardCheck,
  Database,
  FileBarChart,
  Gauge,
  History,
  Layers,
  LayoutDashboard,
  LogOut,
  MessageSquare,
  Radio,
  RadioTower,
  RotateCcw,
  ScrollText,
  Server,
  Settings,
  Settings2,
  Shield,
  ShieldCheck,
  SlidersHorizontal,
  Stethoscope,
  Star,
  Terminal,
  TrendingUp,
  UserRound,
  Users,
  Wallet,
  Radar,
  GitBranch,
  Zap,
  Cpu,
  BrainCircuit,
} from "lucide-react";
import { toast } from "sonner";
import { api, parseAxiosMessage } from "../api/client";
import { connectStomp } from "../lib/realtime/stomp";
import { invalidateBrokerPositionQueries } from "../lib/hooks/useBrokerPositionSync";
import { useOperationalInbox } from "../hooks/useOperationalInbox";
import { useNotificationStore } from "../state/notifications";
import { useMessagesStore } from "../state/messages";
import { useSessionStore } from "../state/session";
import { AppShell } from "./AppShell";
import type { SidebarLink } from "./Sidebar";
import { SidebarAppearanceRow, SidebarBrand, SidebarLinks, SidebarSection } from "./Sidebar";
import { cn } from "../lib/utils";
import { useUiThemeStore } from "../state/uiTheme";
import { WorkspaceTopNav } from "../components/workspace/WorkspaceTopNav";
import { TraderAccountCard } from "../components/workspace/TraderAccountCard";
import { NotificationDrawer } from "../components/ds/NotificationDrawer";
import { MessagesDrawer } from "../components/ds/MessagesDrawer";
import { AnimatedPage } from "../components/ds/AnimatedPage";
import { performLogout } from "../services/auth/logout";
import { AdminInstitutionalSidebar } from "../components/admin/institutional/AdminInstitutionalSidebar";

export function ShellLayout() {
  const location = useLocation();
  const isLightUi = useUiThemeStore((s) => s.mode === "light");
  const navigate = useNavigate();
  const username = useSessionStore((s) => s.username);
  const displayName = useSessionStore((s) => s.displayName);
  const accessToken = useSessionStore((s) => s.accessToken);
  const userId = useSessionStore((s) => s.userId);
  const emailVerified = useSessionStore((s) => s.emailVerified);
  const onboardingComplete = useSessionStore((s) => s.onboardingComplete);
  const isAdmin = useSessionStore((s) => s.hasRole("ROLE_ADMIN"));
  const hasTraderAccess = useSessionStore((s) => s.hasTraderAccess());
  const liveTradingApproved = useSessionStore((s) => s.liveTradingApproved);
  const refreshToken = useSessionStore((s) => s.refreshToken);
  const unread = useNotificationStore((s) => s.unread);
  const feed = useNotificationStore((s) => s.items);
  const markRead = useNotificationStore((s) => s.markRead);
  const clearFeed = useNotificationStore((s) => s.clear);
  const messageUnread = useMessagesStore((s) => s.unread);
  const messages = useMessagesStore((s) => s.items);
  const markMessagesRead = useMessagesStore((s) => s.markRead);
  const clearMessages = useMessagesStore((s) => s.clear);
  const patchProfileFlags = useSessionStore((s) => s.patchProfileFlags);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [messagesOpen, setMessagesOpen] = useState(false);
  const [resendingVerify, setResendingVerify] = useState(false);
  const lastBacktestToastAt = useRef(0);
  const lastVerifyResendAt = useRef(0);
  const queryClient = useQueryClient();

  /** Keep verification/onboarding flags aligned with DB (JWT does not carry them; localStorage can go stale after verify-email). */
  const onboardingSync = useQuery({
    queryKey: ["trader-onboarding-sync", userId],
    queryFn: async () => {
      const res = await api.get("/api/trader/me/onboarding-summary");
      return res.data?.data as Record<string, unknown> | undefined;
    },
    enabled: Boolean(accessToken && userId),
    staleTime: 30_000,
    refetchOnWindowFocus: true,
  });

  useEffect(() => {
    if (!onboardingSync.isSuccess || !onboardingSync.data) return;
    const d = onboardingSync.data;
    const wv = d.whatsAppVerified ?? (d as { whatsappVerified?: boolean }).whatsappVerified;
    patchProfileFlags({
      emailVerified: Boolean(d.emailVerified),
      telegramVerified: Boolean(d.telegramVerified),
      whatsAppVerified: Boolean(wv),
      onboardingComplete: Boolean(d.onboardingComplete),
      liveTradingApproved: Boolean(d.liveTradingApproved),
    });
  }, [onboardingSync.isSuccess, onboardingSync.data, patchProfileFlags]);

  useOperationalInbox({
    enabled: Boolean(accessToken),
    isAdmin,
    hasTraderAccess,
  });

  const portfolioSnapshot = useQuery({
    queryKey: ["sidebar-portfolio-snapshot"],
    queryFn: async () => {
      const [dashboardRes, brokerRes, brokerStatusRes] = await Promise.allSettled([
        api.get("/api/portfolio/dashboard?equityPoints=8"),
        api.get("/api/trader/broker/accounts"),
        api.get("/api/trader/broker/status"),
      ]);

      let equityValue = "-";
      let marginValue = "-";
      let brokerConnected = false;
      const toNumber = (v: unknown): number | null => {
        if (typeof v === "number" && Number.isFinite(v)) return v;
        if (typeof v === "string") {
          const n = Number(v.replace(/[^0-9.-]/g, ""));
          return Number.isFinite(n) ? n : null;
        }
        return null;
      };
      const toInr = (n: number) =>
        new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2 }).format(n);

      if (dashboardRes.status === "fulfilled") {
        const overview = dashboardRes.value.data?.data?.overview as
          | { totalEquity?: number; netWorth?: number; accountValue?: number }
          | undefined;
        const rawEquity = overview?.totalEquity ?? overview?.netWorth ?? overview?.accountValue;
        const n = toNumber(rawEquity);
        if (n != null) {
          equityValue = toInr(n);
        }
      }

      if (brokerRes.status === "fulfilled") {
        const rows = brokerRes.value.data?.data;
        if (Array.isArray(rows) && rows.length > 0) {
          brokerConnected = true;
          const first = rows[0] as { cashAvailable?: number | string; availableMargin?: number | string };
          const rawMargin = first.availableMargin ?? first.cashAvailable;
          const n = toNumber(rawMargin);
          if (n != null) {
            marginValue = toInr(n);
          }
        }
      }

      if (marginValue === "-" && dashboardRes.status === "fulfilled") {
        const overview = dashboardRes.value.data?.data?.overview as
          | { availableMargin?: number | string; cashAvailable?: number | string }
          | undefined;
        const n = toNumber(overview?.availableMargin ?? overview?.cashAvailable);
        if (n != null) marginValue = toInr(n);
      }

      if (marginValue === "-" && brokerStatusRes.status === "fulfilled") {
        const statusData = brokerStatusRes.value?.data?.data as
          | { marginSummary?: string; connected?: boolean }
          | undefined;
        if (statusData?.connected) brokerConnected = true;
        const m = statusData?.marginSummary ?? "";
        const match = /available\.cash=([0-9.,-]+)/i.exec(m);
        const n = match ? toNumber(match[1]) : null;
        if (n != null) marginValue = toInr(n);
      }

      return { equityValue, marginValue, brokerConnected };
    },
    enabled: hasTraderAccess,
    refetchInterval: 30000,
  });

  const adminHealth = useQuery({
    queryKey: ["admin-health"],
    queryFn: async () => (await api.get("/api/admin/health")).data?.data as Record<string, unknown>,
    enabled: isAdmin,
    refetchInterval: 15_000,
    retry: 2,
  });

  const mainLinks: SidebarLink[] = useMemo(
    () => [
      { to: "/dashboard", end: true, label: "Dashboard", icon: LayoutDashboard },
      { to: "/adv-dashboard", label: "ADV Dashboard", icon: Cpu },
      { to: "/strategies", label: "Strategies", icon: Wallet },
      { to: "/positions", label: "Positions", icon: Layers },
      { to: "/orders", label: "Orders", icon: TrendingUp },
      { to: "/executions", label: "Executions", icon: ActivitySquare },
      { to: "/watchlist", label: "Watchlist", icon: Star },
      { to: "/terminal", label: "Analytics", icon: BarChart3, matchPrefix: "/terminal" },
      { to: "/backtests/launch", label: "Backtests", icon: Settings2, matchPrefix: "/backtests" },
    ],
    [],
  );

  const intradayLinks: SidebarLink[] = useMemo(
    () => [
      { to: "/intraday", label: "Intraday Terminal", icon: Radar, matchPrefix: "/intraday" },
    ],
    [],
  );

  const integrationLinks: SidebarLink[] = useMemo(() => {
    const links: SidebarLink[] = [];
    if (hasTraderAccess) {
      links.push({ to: "/brokers", label: "Broker Connect", icon: Shield });
      links.push({ to: "/strategy-settings", label: "Strategy Settings", icon: SlidersHorizontal });
      links.push({ to: "/profile", label: "Profile", icon: UserRound });
      links.push({ to: "/signals", label: "Signals", icon: MessageSquare });
    }
    links.push({ to: "/intraday", label: "Ops readiness", icon: Bell });
    return links;
  }, [hasTraderAccess]);

  async function resendVerificationEmail() {
    const now = Date.now();
    if (now - lastVerifyResendAt.current < 30_000) {
      toast.message("Please wait before requesting another verification email.", { duration: 4000 });
      return;
    }
    lastVerifyResendAt.current = now;
    setResendingVerify(true);
    try {
      const res = await api.post<{ data?: { status?: string } }>("/api/auth/resend-verification");
      const status = res.data?.data?.status;
      if (status === "SENT") {
        toast.success("Verification email sent - check your inbox.");
      } else if (status === "NOT_CONFIGURED") {
        toast.message("SMTP not configured. Use verification URL from logs.", { duration: 12_000 });
      } else if (status === "SEND_FAILED") {
        toast.error("Verification email could not be sent. Try again later or check SMTP settings.");
      } else {
        toast.message("Verification request completed.");
      }
    } catch (e: unknown) {
      const msg = parseAxiosMessage(e);
      if (msg.toLowerCase().includes("already verified")) {
        try {
          const res = await api.get("/api/trader/me/onboarding-summary");
          const d = res.data?.data as Record<string, unknown> | undefined;
          if (d) {
            const wv = d.whatsAppVerified ?? (d as { whatsappVerified?: boolean }).whatsappVerified;
            patchProfileFlags({
              emailVerified: Boolean(d.emailVerified),
              telegramVerified: Boolean(d.telegramVerified),
              whatsAppVerified: Boolean(wv),
              onboardingComplete: Boolean(d.onboardingComplete),
              liveTradingApproved: Boolean(d.liveTradingApproved),
            });
          }
        } catch {
          /* ignore */
        }
        toast.success("Email already verified - profile synced.");
      } else {
        toast.error(msg);
      }
    } finally {
      setResendingVerify(false);
    }
  }

  useEffect(() => {
    if (!accessToken || !userId || !hasTraderAccess) return;
    const off = connectStomp(accessToken, userId, {
      onOrder: (m) => {
        invalidateBrokerPositionQueries(queryClient);
        try {
          const raw = m.body;
          const parsed = JSON.parse(raw) as Record<string, string>;
          useNotificationStore.getState().push({
            severity: "success",
            title: `Order ${parsed.state ?? "update"}`,
            detail: `${parsed.symbol ?? ""} ${parsed.orderId ?? ""}`,
            topic: "orders",
          });
          toast.message("Order update", { description: `${parsed.symbol ?? ""} ${parsed.state ?? ""}` });
        } catch {
          useNotificationStore.getState().push({ severity: "info", title: "Order event", topic: "orders" });
        }
      },
      onPnl: () => {
        invalidateBrokerPositionQueries(queryClient);
        useNotificationStore.getState().push({ severity: "info", title: "PnL snapshot refreshed", topic: "pnl" });
      },
      onPosition: () => {
        invalidateBrokerPositionQueries(queryClient);
      },
      onStrategy: (m) => {
        try {
          const parsed = JSON.parse(m.body) as Record<string, string>;
          useNotificationStore.getState().push({
            severity: "info",
            title: `Strategy ${parsed.runtimeState ?? ""}`,
            detail: parsed.instanceId,
            topic: "strategy",
          });
        } catch {
          useNotificationStore.getState().push({ severity: "info", title: "Strategy runtime", topic: "strategy" });
        }
      },
      onBacktestJob: (m) => {
        try {
          const parsed = JSON.parse(m.body) as Record<string, unknown>;
          const status = String(parsed.status ?? "");
          const pct = typeof parsed.progressPct === "number" ? parsed.progressPct : Number(parsed.progressPct);
          const now = Date.now();
          if (status === "COMPLETED" || status === "CANCELLED") {
            toast.message("Backtest job", {
              description: `${status}${Number.isFinite(pct) ? `  ·  ${pct}%` : ""}`,
            });
            return;
          }
          if (now - lastBacktestToastAt.current < 10_000) return;
          lastBacktestToastAt.current = now;
          const eta = parsed.etaSecondsRemaining;
          const etaTxt =
            typeof eta === "number" && Number.isFinite(eta) ? `  ·  ~${Math.round(eta)}s remaining` : "";
          toast.message("Backtest progress", {
            description: `${Number.isFinite(pct) ? `${pct}%` : "running"}${etaTxt}`,
          });
        } catch {
          /* ignore malformed realtime payloads */
        }
      },
    });
    return () => off();
  }, [accessToken, userId, hasTraderAccess]);

  async function logout() {
    await performLogout({
      remoteLogout: () => api.post("/api/auth/logout", { refreshToken }),
    });
    navigate("/login", { replace: true });
  }

  const sidebar = (
    <div className="flex min-h-0 flex-1 flex-col">
      <SidebarBrand title="Stokr Platform" subtitle={hasTraderAccess ? "Trader workspace" : "Console"} />
      <div
        className={cn(
          "min-h-0 flex-1 overflow-y-auto overflow-x-hidden pr-0.5 [scrollbar-gutter:stable]",
          "[scrollbar-width:thin] [-ms-overflow-style:auto]",
          "[&::-webkit-scrollbar]:w-2 [&::-webkit-scrollbar-track]:rounded-full [&::-webkit-scrollbar-track]:bg-transparent",
          "dark:[&::-webkit-scrollbar-thumb]:rounded-full dark:[&::-webkit-scrollbar-thumb]:bg-neutral-700/90",
          "dark:[&::-webkit-scrollbar-thumb:hover]:bg-neutral-600",
          "[&::-webkit-scrollbar-thumb]:rounded-full [&::-webkit-scrollbar-thumb]:bg-neutral-300",
          "[&::-webkit-scrollbar-thumb:hover]:bg-neutral-400",
        )}
      >
        <div className="flex min-h-full flex-col">
          <div className="min-h-0 flex-1">
            {hasTraderAccess ? (
              <>
                <SidebarSection title="MAIN" first>
                  <SidebarLinks links={mainLinks} />
                </SidebarSection>
                <SidebarSection title="INTRADAY">
                  <SidebarLinks links={intradayLinks} />
                </SidebarSection>
                <SidebarSection title="INTEGRATIONS">
                  <SidebarLinks links={integrationLinks} />
                </SidebarSection>
              </>
            ) : null}
            {isAdmin ? (
              <SidebarSection title="Institutional Console" first={!hasTraderAccess}>
                <AdminInstitutionalSidebar isLight={isLightUi} killActive={Boolean(adminHealth.data?.killSwitch)} />
              </SidebarSection>
            ) : null}
          </div>
          <div
            className={cn(
              "mt-auto border-t pt-4",
              isLightUi ? "border-neutral-200" : "border-white/[0.06]",
            )}
          >
            {hasTraderAccess ? (
              <TraderAccountCard
                equityDisplay={portfolioSnapshot.data?.equityValue ?? "-"}
                marginDisplay={portfolioSnapshot.data?.marginValue ?? "-"}
                brokerConnected={Boolean(portfolioSnapshot.data?.brokerConnected)}
                onRefreshRequested={() => {
                  void portfolioSnapshot.refetch();
                }}
              />
            ) : null}
            <SidebarAppearanceRow />
          </div>
        </div>
      </div>
    </div>
  );

  const banners = (
    <>
      {!onboardingComplete ? (
        <div
          className={cn(
            "flex flex-col gap-2 rounded-xl border px-4 py-3 text-sm sm:flex-row sm:items-center sm:justify-between",
            isLightUi
              ? "border-blue-200 bg-blue-50/90 text-blue-950"
              : "border-blue-500/25 bg-blue-500/5 text-blue-100",
          )}
        >
          <span>
            <span className={cn("font-semibold", isLightUi ? "text-neutral-950" : "text-white")}>
              Operational checklist  · {" "}
            </span>
            Finish onboarding to unlock gated LIVE routing and broker ergonomics.
          </span>
          <button
            type="button"
            onClick={() => navigate("/onboarding")}
            className={cn(
              "rounded-lg border px-3 py-1 text-xs font-semibold transition",
              isLightUi
                ? "border-blue-400/55 text-blue-800 hover:bg-blue-100"
                : "border-blue-400/35 text-blue-50 hover:bg-blue-500/15",
            )}
          >
            Open onboarding
          </button>
        </div>
      ) : null}
      {accessToken && !emailVerified ? (
        <div
          className={cn(
            "flex flex-col gap-3 rounded-xl border px-4 py-3 text-sm md:flex-row md:items-center md:justify-between",
            isLightUi
              ? "border-amber-200 bg-amber-50/95 text-amber-950"
              : "border-amber-500/30 bg-amber-500/5 text-amber-100",
          )}
        >
          <div>
            <div className={cn("font-semibold", isLightUi ? "text-amber-950" : "text-amber-50")}>Email verification pending</div>
            <div className={cn("mt-1 text-xs", isLightUi ? "text-amber-900/85" : "text-amber-200/85")}>
              Required before broker onboarding and escalation to operations review.
            </div>
          </div>
          <button
            type="button"
            disabled={resendingVerify}
            onClick={() => void resendVerificationEmail()}
            className={cn(
              "shrink-0 rounded-lg border px-3 py-1.5 text-xs font-semibold transition disabled:opacity-50",
              isLightUi
                ? "border-amber-500/55 text-amber-950 hover:bg-amber-100"
                : "border-amber-500/45 text-amber-50 hover:bg-amber-500/10",
            )}
          >
            {resendingVerify ? "Sending..." : "Resend email"}
          </button>
        </div>
      ) : null}
    </>
  );

  return (
    <>
      <AppShell
        sidebar={sidebar}
        topNav={
          <WorkspaceTopNav
            displayName={displayName}
            username={username ?? undefined}
            unread={unread}
            messageUnread={messageUnread}
            liveApproved={liveTradingApproved}
            onNotificationClick={() => {
              setDrawerOpen(true);
              markRead();
            }}
            onMessageClick={() => {
              setMessagesOpen(true);
              markMessagesRead();
            }}
            rightExtra={
              <motion.button
                type="button"
                whileHover={{ y: -1, scale: 1.01 }}
                whileTap={{ scale: 0.98 }}
                onClick={() => void logout()}
                className={cn(
                  "inline-flex items-center gap-2 rounded-2xl border px-4 py-2 text-[11px] font-bold uppercase tracking-[0.14em] transition-colors duration-300",
                  isLightUi
                    ? "border-neutral-200/90 bg-white/90 text-neutral-700 shadow-[0_10px_30px_-22px_rgba(15,23,42,0.22)] hover:border-rose-200 hover:bg-rose-50 hover:text-rose-700"
                    : "border-white/[0.1] bg-neutral-900/60 text-neutral-300 hover:border-rose-500/30 hover:bg-rose-500/10 hover:text-rose-200",
                )}
              >
                <LogOut className="h-3.5 w-3.5" />
                Sign out
              </motion.button>
            }
          />
        }
        banners={banners}
      >
        <AnimatePresence mode="wait">
          <AnimatedPage pageKey={location.pathname}>
            <Outlet />
          </AnimatedPage>
        </AnimatePresence>
      </AppShell>
      <NotificationDrawer
        open={drawerOpen}
        onOpenChange={setDrawerOpen}
        items={feed}
        onClear={() => {
          clearFeed();
          toast.message("Notifications cleared");
        }}
      />
      <MessagesDrawer
        open={messagesOpen}
        onOpenChange={setMessagesOpen}
        items={messages}
        onClear={() => {
          clearMessages();
          toast.message("Messages cleared");
        }}
      />
    </>
  );
}




