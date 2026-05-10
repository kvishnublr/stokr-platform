import { Outlet, useNavigate } from "react-router-dom";
import { useEffect, useMemo, useState } from "react";
import {
  ActivitySquare,
  BarChart3,
  Bell,
  CircleUser,
  ClipboardList,
  FlaskConical,
  LayoutDashboard,
  LineChart,
  LogOut,
  PanelsLeftRight,
  Settings2,
  Shield,
  TrendingUp,
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
import { SidebarBrand, SidebarLinks, SidebarSection } from "./Sidebar";
import { TopNav } from "./TopNav";
import { NotificationDrawer } from "../components/ds/NotificationDrawer";

export function ShellLayout() {
  const navigate = useNavigate();
  const username = useSessionStore((s) => s.username);
  const accessToken = useSessionStore((s) => s.accessToken);
  const userId = useSessionStore((s) => s.userId);
  const emailVerified = useSessionStore((s) => s.emailVerified);
  const onboardingComplete = useSessionStore((s) => s.onboardingComplete);
  const isAdmin = useSessionStore((s) => s.hasRole("ROLE_ADMIN"));
  const clearSession = useSessionStore((s) => s.clearSession);
  const refreshToken = useSessionStore((s) => s.refreshToken);
  const pushNotification = useNotificationStore((s) => s.push);
  const unread = useNotificationStore((s) => s.unread);
  const feed = useNotificationStore((s) => s.items);
  const markRead = useNotificationStore((s) => s.markRead);
  const clearFeed = useNotificationStore((s) => s.clear);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [resendingVerify, setResendingVerify] = useState(false);

  const primaryLinks: SidebarLink[] = useMemo(
    () => [
      { to: "/", end: true, label: "Workstation", icon: LayoutDashboard },
      { to: "/terminal", label: "Terminal", icon: PanelsLeftRight },
      { to: "/onboarding", label: "Onboarding", icon: ClipboardList },
      { to: "/orders", label: "Orders", icon: TrendingUp },
      { to: "/trades", label: "Trades", icon: ActivitySquare },
      { to: "/executions", label: "Executions", icon: LineChart },
      { to: "/positions", label: "Positions", icon: LineChart },
      { to: "/strategies", label: "Strategies", icon: Wallet },
      {
        to: "/research/leaderboard",
        label: "Research",
        icon: BarChart3,
        matchPrefix: "/research",
      },
      {
        to: "/backtests/launch",
        label: "Backtests",
        icon: Settings2,
        matchPrefix: "/backtests",
      },
      { to: "/brokers", label: "Broker connect", icon: Shield },
      { to: "/paper", label: "Paper trading", icon: Wallet },
      { to: "/debug", label: "Diagnostics", icon: FlaskConical },
    ],
    [],
  );

  const adminLinks: SidebarLink[] = useMemo(
    () => [
      { to: "/admin", end: true, label: "Overview", icon: Shield },
      { to: "/admin/users", label: "Traders", icon: Users },
      { to: "/admin/strategies", label: "Catalog", icon: Settings2 },
      { to: "/admin/oms", label: "OMS monitor", icon: TrendingUp },
      { to: "/admin/ops", label: "Operations", icon: ActivitySquare },
    ],
    [],
  );

  async function resendVerificationEmail() {
    setResendingVerify(true);
    try {
      await api.post("/api/auth/resend-verification");
      toast.success("Verification email sent — check your inbox.");
    } catch (e: unknown) {
      toast.error(parseAxiosMessage(e));
    } finally {
      setResendingVerify(false);
    }
  }

  useEffect(() => {
    if (!accessToken || !userId) return;
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
  }, [accessToken, userId, pushNotification]);

  async function logout() {
    try {
      await api.post("/api/auth/logout", { refreshToken });
    } catch {
      /* ignore */
    }
    clearSession();
    navigate("/login", { replace: true });
  }

  const sidebar = (
    <div className="flex flex-col">
      <SidebarBrand />
      <SidebarLinks links={primaryLinks} />
      {isAdmin ? (
        <SidebarSection title="Operations">
          <SidebarLinks links={adminLinks} />
        </SidebarSection>
      ) : null}
      <button
        type="button"
        onClick={() => navigate("/")}
        className="mt-8 hidden items-center gap-2 rounded-xl border border-neutral-800 px-3 py-2 text-left text-[12px] text-neutral-500 hover:bg-neutral-900 lg:flex"
      >
        <CircleUser className="h-4 w-4 shrink-0" />
        Trader profile cues live in onboarding & brokers.
      </button>
    </div>
  );

  const banners = (
    <>
      {!onboardingComplete ? (
        <div className="flex flex-col gap-2 rounded-xl border border-blue-500/25 bg-blue-500/5 px-4 py-3 text-sm text-blue-100 sm:flex-row sm:items-center sm:justify-between">
          <span>
            <span className="font-semibold text-white">Operational checklist · </span>
            Finish onboarding to unlock gated LIVE routing and broker ergonomics.
          </span>
          <button
            type="button"
            onClick={() => navigate("/onboarding")}
            className="rounded-lg border border-blue-400/35 px-3 py-1 text-xs font-semibold text-blue-50 hover:bg-blue-500/15"
          >
            Open onboarding
          </button>
        </div>
      ) : null}
      {accessToken && !emailVerified ? (
        <div className="flex flex-col gap-3 rounded-xl border border-amber-500/30 bg-amber-500/5 px-4 py-3 text-sm text-amber-100 md:flex-row md:items-center md:justify-between">
          <div>
            <div className="font-semibold text-amber-50">Email verification pending</div>
            <div className="mt-1 text-xs text-amber-200/85">
              Required before broker onboarding and escalation to operations review.
            </div>
          </div>
          <button
            type="button"
            disabled={resendingVerify}
            onClick={() => void resendVerificationEmail()}
            className="shrink-0 rounded-lg border border-amber-500/45 px-3 py-1.5 text-xs font-semibold text-amber-50 transition hover:bg-amber-500/10 disabled:opacity-50"
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
          <TopNav
            right={
              <>
                <button
                  type="button"
                  onClick={() => {
                    setDrawerOpen(true);
                    markRead();
                  }}
                  aria-label={`Notifications ${unread > 0 ? `(${unread} unread)` : ""}`}
                  className="relative flex h-10 w-10 items-center justify-center rounded-xl border border-neutral-800 bg-neutral-950/70 text-neutral-200 shadow-inner hover:bg-neutral-900"
                >
                  <Bell className="h-4 w-4" />
                  {unread > 0 ? (
                    <span className="absolute -right-0.5 -top-0.5 flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-blue-600 px-1 text-[10px] font-black text-white shadow-lg">
                      {unread > 9 ? "9+" : unread}
                    </span>
                  ) : null}
                </button>
                <button
                  type="button"
                  onClick={() => void logout()}
                  className="inline-flex items-center gap-2 rounded-xl border border-neutral-800 px-4 py-2 text-xs font-bold uppercase tracking-wide text-neutral-300 hover:bg-neutral-900"
                >
                  <LogOut className="h-3.5 w-3.5" />
                  Sign out
                </button>
              </>
            }
            displayNameFallback={username ?? undefined}
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
