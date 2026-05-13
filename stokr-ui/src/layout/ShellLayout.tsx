import { Outlet, useNavigate } from "react-router-dom";
import { useEffect, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  ActivitySquare,
  BarChart3,
  Layers,
  LayoutDashboard,
  LogOut,
  MessageSquare,
  Settings2,
  Shield,
  Star,
  TrendingUp,
  UserRound,
  Users,
  Wallet,
} from "lucide-react";
import { toast } from "sonner";
import { api, parseAxiosMessage } from "../api/client";
import { connectStomp } from "../lib/realtime/stomp";
import { useNotificationStore } from "../state/notifications";
import { useSessionStore } from "../state/session";
import { AppShell } from "./AppShell";
import type { SidebarLink } from "./Sidebar";
import { SidebarAppearanceRow, SidebarBrand, SidebarLinks, SidebarSection } from "./Sidebar";
import { cn } from "../lib/utils";
import { useUiThemeStore } from "../state/uiTheme";
import { WorkspaceTopNav } from "../components/workspace/WorkspaceTopNav";
import { TraderAccountCard } from "../components/workspace/TraderAccountCard";
import { NotificationDrawer } from "../components/ds/NotificationDrawer";
import { performLogout } from "../services/auth/logout";

export function ShellLayout() {
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
  const canKillOpsConsole = useSessionStore((s) => s.canAccessKillSwitchOperations());
  const liveTradingApproved = useSessionStore((s) => s.liveTradingApproved);
  const refreshToken = useSessionStore((s) => s.refreshToken);
  const pushNotification = useNotificationStore((s) => s.push);
  const unread = useNotificationStore((s) => s.unread);
  const feed = useNotificationStore((s) => s.items);
  const markRead = useNotificationStore((s) => s.markRead);
  const clearFeed = useNotificationStore((s) => s.clear);
  const patchProfileFlags = useSessionStore((s) => s.patchProfileFlags);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [resendingVerify, setResendingVerify] = useState(false);

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

  const portfolioSnapshot = useQuery({
    queryKey: ["sidebar-portfolio-snapshot"],
    queryFn: async () => {
      const [dashboardRes, brokerRes] = await Promise.allSettled([
        api.get("/api/portfolio/dashboard?equityPoints=8"),
        api.get("/api/trader/broker/accounts"),
      ]);

      let equityValue = "—";
      let marginValue = "—";

      if (dashboardRes.status === "fulfilled") {
        const overview = dashboardRes.value.data?.data?.overview as
          | { totalEquity?: number; netWorth?: number; accountValue?: number }
          | undefined;
        const rawEquity = overview?.totalEquity ?? overview?.netWorth ?? overview?.accountValue;
        if (typeof rawEquity === "number") {
          equityValue = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2 }).format(rawEquity);
        }
      }

      if (brokerRes.status === "fulfilled") {
        const rows = brokerRes.value.data?.data;
        if (Array.isArray(rows) && rows.length > 0) {
          const first = rows[0] as { cashAvailable?: number; availableMargin?: number };
          const rawMargin = first.availableMargin ?? first.cashAvailable;
          if (typeof rawMargin === "number") {
            marginValue = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2 }).format(rawMargin);
          }
        }
      }

      return { equityValue, marginValue };
    },
    enabled: hasTraderAccess,
    refetchInterval: 30000,
  });

  const mainLinks: SidebarLink[] = useMemo(
    () => [
      { to: "/dashboard", end: true, label: "Dashboard", icon: LayoutDashboard },
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

  const integrationLinks: SidebarLink[] = useMemo(() => {
    const links: SidebarLink[] = [];
    if (hasTraderAccess) {
      links.push({ to: "/brokers", label: "Broker Connect", icon: Shield });
      links.push({ to: "/profile", label: "Profile", icon: UserRound });
    }
    links.push({ to: "/terminal", label: "Alerts & notifications", icon: MessageSquare });
    return links;
  }, [hasTraderAccess]);

  const adminLinks: SidebarLink[] = useMemo(() => {
    const core: SidebarLink[] = [
      { to: "/admin", end: true, label: "Overview", icon: Shield },
      { to: "/admin/users", label: "Traders", icon: Users },
      { to: "/admin/strategies", label: "Catalog", icon: Settings2 },
      { to: "/admin/oms", label: "OMS monitor", icon: TrendingUp },
    ];
    if (canKillOpsConsole) {
      core.push({ to: "/admin/ops", label: "Risk & incidents", icon: ActivitySquare });
    }
    return core;
  }, [canKillOpsConsole]);

  async function resendVerificationEmail() {
    setResendingVerify(true);
    try {
      const res = await api.post<{ data?: { status?: string } }>("/api/auth/resend-verification");
      const status = res.data?.data?.status;
      if (status === "SENT") {
        toast.success("Verification email sent — check your inbox.");
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
        toast.success("Email already verified — profile synced.");
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
        try {
          const raw = m.body;
          const parsed = JSON.parse(raw) as Record<string, string>;
          pushNotification({
            severity: "success",
            title: `Order ${parsed.state ?? "update"}`,
            detail: `${parsed.symbol ?? ""} ${parsed.orderId ?? ""}`,
            topic: "orders",
          });
          toast.message("Order update", { description: `${parsed.symbol ?? ""} ${parsed.state ?? ""}` });
        } catch {
          pushNotification({ severity: "info", title: "Order event", topic: "orders" });
        }
      },
      onPnl: () => {
        pushNotification({ severity: "info", title: "PnL snapshot refreshed", topic: "pnl" });
      },
      onStrategy: (m) => {
        try {
          const parsed = JSON.parse(m.body) as Record<string, string>;
          pushNotification({
            severity: "info",
            title: `Strategy ${parsed.runtimeState ?? ""}`,
            detail: parsed.instanceId,
            topic: "strategy",
          });
        } catch {
          pushNotification({ severity: "info", title: "Strategy runtime", topic: "strategy" });
        }
      },
    });
    return () => off();
  }, [accessToken, userId, hasTraderAccess, pushNotification]);

  async function logout() {
    await performLogout({
      remoteLogout: () => api.post("/api/auth/logout", { refreshToken }),
    });
    navigate("/login", { replace: true });
  }

  const sidebar = (
    <div className="flex min-h-0 flex-1 flex-col">
      <SidebarBrand title="Stokr Platform" subtitle={hasTraderAccess ? "Trader workspace" : "Console"} />
      <div className="min-h-0 flex-1 overflow-y-auto overflow-x-hidden pr-1 [-ms-overflow-style:none] [scrollbar-width:thin]">
        {hasTraderAccess ? (
          <>
            <SidebarSection title="MAIN" first>
              <SidebarLinks links={mainLinks} />
            </SidebarSection>
            <SidebarSection title="INTEGRATIONS">
              <SidebarLinks links={integrationLinks} />
            </SidebarSection>
          </>
        ) : null}
        {isAdmin ? (
          <SidebarSection title="Admin console">
            <SidebarLinks links={adminLinks} />
          </SidebarSection>
        ) : null}
      </div>
      {hasTraderAccess ? (
        <TraderAccountCard
          equityDisplay={portfolioSnapshot.data?.equityValue ?? "—"}
          marginDisplay={portfolioSnapshot.data?.marginValue ?? "—"}
        />
      ) : null}
      <SidebarAppearanceRow />
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
              Operational checklist ·{" "}
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
            {resendingVerify ? "Sending…" : "Resend email"}
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
            liveApproved={liveTradingApproved}
            onNotificationClick={() => {
              setDrawerOpen(true);
              markRead();
            }}
            rightExtra={
              <button
                type="button"
                onClick={() => void logout()}
                className={cn(
                  "inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[11px] font-bold uppercase tracking-wide transition",
                  isLightUi
                    ? "border-neutral-200 bg-white text-neutral-700 hover:border-neutral-300 hover:bg-neutral-50"
                    : "border-white/[0.1] bg-neutral-900/60 text-neutral-300 hover:border-neutral-600 hover:bg-neutral-800",
                )}
              >
                <LogOut className="h-3.5 w-3.5" />
                Sign out
              </button>
            }
          />
        }
        banners={banners}
      >
        <Outlet />
      </AppShell>
      <NotificationDrawer
        open={drawerOpen}
        onOpenChange={setDrawerOpen}
        items={feed}
        onClear={() => {
          clearFeed();
          toast.message("Operational feed cleared");
        }}
      />
    </>
  );
}
