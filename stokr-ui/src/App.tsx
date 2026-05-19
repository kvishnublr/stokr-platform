import type { ReactNode } from "react";
import { lazy, Suspense } from "react";
import { Navigate, Outlet, Route, Routes } from "react-router-dom";
import { AdminConsoleLayout } from "./layout/AdminConsoleLayout";
import { ShellLayout } from "./layout/ShellLayout";
import { LoginPage } from "./pages/LoginPage";
import { RegisterPage } from "./pages/RegisterPage";
import { ForgotPasswordPage } from "./pages/ForgotPasswordPage";
import { ResetPasswordPage } from "./pages/ResetPasswordPage";
import { OrdersPage } from "./pages/OrdersPage";
import { TradesPage } from "./pages/TradesPage";
import { ExecutionsPage } from "./pages/ExecutionsPage";
import { PositionsPage } from "./pages/PositionsPage";
import { StrategiesPage } from "./pages/StrategiesPage";
import { BacktestsLayout } from "./layout/BacktestsLayout";
import { BacktestLauncherPage } from "./pages/BacktestLauncherPage";
import { BacktestHistoryPage } from "./pages/BacktestHistoryPage";
import { BacktestRunDetailsPage } from "./pages/BacktestRunDetailsPage";
import { StrategyResearchLayout } from "./layout/StrategyResearchLayout";
import { ResearchLeaderboardPage } from "./pages/ResearchLeaderboardPage";
import { PaperTradingPage } from "./pages/PaperTradingPage";
import { DebugToolsPage } from "./pages/DebugToolsPage";
import { BrokersPage } from "./pages/BrokersPage";
import { AdminUsersPage } from "./pages/AdminUsersPage";
import { AdminStrategiesPage } from "./pages/AdminStrategiesPage";
import { AdminOmsMonitorPage } from "./pages/AdminOmsMonitorPage";
import { AdminOpsPage } from "./pages/AdminOpsPage";
import { AdminSectionPage } from "./pages/AdminSectionPage";
import { AdminBrokerInfrastructurePage } from "./pages/admin/AdminBrokerInfrastructurePage";
import { AdminAuditPage } from "./pages/admin/AdminAuditPage";
import { AdminBackfillPage } from "./pages/admin/AdminBackfillPage";
import { AdminControlsPage } from "./pages/admin/AdminControlsPage";
import { AdminExecutionPage } from "./pages/admin/AdminExecutionPage";
import { AdminHistoryPage } from "./pages/admin/AdminHistoryPage";
import { AdminInfrastructurePage } from "./pages/admin/AdminInfrastructurePage";
import { AdminMarketIntelPage } from "./pages/admin/AdminMarketIntelPage";
import { AdminReplayInfraPage } from "./pages/admin/AdminReplayInfraPage";
import { AdminSignalsPage } from "./pages/admin/AdminSignalsPage";
import { AdminTraderHealthPage } from "./pages/admin/AdminTraderHealthPage";
import { AdminStrategyCatalogPage } from "./pages/admin/AdminStrategyCatalogPage";
import { AdminUniverseGroupsPage } from "./pages/admin/AdminUniverseGroupsPage";
import { AdminRuntimeBindingsPage } from "./pages/admin/AdminRuntimeBindingsPage";
import { AdminPipelineHealthPage } from "./pages/admin/AdminPipelineHealthPage";
import { useSessionStore } from "./state/session";
import { VerifyEmailPage } from "./pages/VerifyEmailPage";
import { OnboardingWizardPage } from "./pages/OnboardingWizardPage";
import { TerminalPage } from "./pages/TerminalPage";
import { SignalsPage } from "./pages/SignalsPage";
import { PageSkeleton } from "./components/ds/SkeletonLoader";
import { ErrorBoundary } from "./components/ds/ErrorBoundary";
import { ThemeHtmlSync } from "./components/theme/ThemeHtmlSync";
import { SyncedToaster } from "./components/theme/SyncedToaster";
import { TraderDashboard } from "./pages/trader/TraderDashboard";
import { AdminOverviewPage } from "./pages/AdminOverviewPage";
import { ProfilePage } from "./pages/ProfilePage";

/** Heavy chart surface - defer initial JS until navigation. */
const BacktestReplayPage = lazy(async () => {
  const m = await import("./pages/BacktestReplayPage");
  return { default: m.BacktestReplayPage };
});

const IntradayTraderPage = lazy(async () => {
  const m = await import("./pages/IntradayTraderPage");
  return { default: m.IntradayTraderPage };
});

const AdminIntradayOpsPage = lazy(async () => {
  const m = await import("./pages/admin/AdminIntradayOpsPage");
  return { default: m.AdminIntradayOpsPage };
});

function Protected({ children }: { children: ReactNode }) {
  const token = useSessionStore((s) => s.accessToken);
  if (!token) {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
}

function AdminGate() {
  const isAdmin = useSessionStore((s) => s.hasRole("ROLE_ADMIN"));
  const isTrader = useSessionStore((s) => s.hasTraderAccess());
  const ok = isAdmin && !isTrader;
  if (!ok) {
    return <Navigate to="/dashboard" replace />;
  }
  return <Outlet />;
}

function TraderDashboardRoute() {
  const isTrader = useSessionStore((s) => s.hasTraderAccess());
  const isAdmin = useSessionStore((s) => s.hasRole("ROLE_ADMIN"));
  if (!isTrader) {
    return <Navigate to={isAdmin ? "/admin" : "/login"} replace />;
  }
  return <TraderDashboard />;
}

function RootEntryRedirect() {
  const isTrader = useSessionStore((s) => s.hasTraderAccess());
  const isAdmin = useSessionStore((s) => s.hasRole("ROLE_ADMIN"));
  if (isTrader) {
    return <Navigate to="/dashboard" replace />;
  }
  return <Navigate to={isAdmin ? "/admin" : "/login"} replace />;
}

/** Kill-switch / emergency ops UI is not exposed to ROLE_TRADER. */
function AdminOpsGate() {
  const ok = useSessionStore((s) => s.canAccessKillSwitchOperations());
  if (!ok) {
    return <Navigate to="/admin" replace />;
  }
  return <AdminOpsPage />;
}

/** Broker Connect is trader-only; platform admins without trader role are redirected. */
function TraderBrokerRoute() {
  const hasTrader = useSessionStore((s) => s.hasTraderAccess());
  const isAdmin = useSessionStore((s) => s.hasRole("ROLE_ADMIN"));
  if (!hasTrader) {
    return <Navigate to={isAdmin ? "/admin" : "/dashboard"} replace />;
  }
  return <BrokersPage />;
}

function TraderIntradayRoute() {
  const hasTrader = useSessionStore((s) => s.hasTraderAccess());
  const isAdmin = useSessionStore((s) => s.hasRole("ROLE_ADMIN"));
  if (!hasTrader) {
    return <Navigate to={isAdmin ? "/admin" : "/dashboard"} replace />;
  }
  return (
    <Suspense fallback={<PageSkeleton />}>
      <IntradayTraderPage />
    </Suspense>
  );
}

export default function App() {
  return (
    <ErrorBoundary>
      <ThemeHtmlSync />
      <SyncedToaster />
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />
        <Route path="/reset-password" element={<ResetPasswordPage />} />
        <Route path="/verify-email" element={<VerifyEmailPage />} />
        <Route
          path="/"
          element={
            <Protected>
              <ShellLayout />
            </Protected>
          }
        >
          <Route index element={<RootEntryRedirect />} />
          <Route path="dashboard" element={<TraderDashboardRoute />} />
          <Route path="watchlist" element={<Navigate to="/strategies" replace />} />
          <Route path="terminal" element={<TerminalPage />} />
          <Route path="signals" element={<SignalsPage />} />
          <Route path="onboarding" element={<OnboardingWizardPage />} />
          <Route path="orders" element={<OrdersPage />} />
          <Route path="trades" element={<TradesPage />} />
          <Route path="executions" element={<ExecutionsPage />} />
          <Route path="positions" element={<PositionsPage />} />
          <Route path="strategies" element={<StrategiesPage />} />
          <Route path="intraday/*" element={<TraderIntradayRoute />} />
          <Route path="backtests" element={<BacktestsLayout />}>
            <Route index element={<Navigate to="launch" replace />} />
            <Route path="launch" element={<BacktestLauncherPage />} />
            <Route path="history" element={<BacktestHistoryPage />} />
            <Route
              path=":runId/replay"
              element={
                <Suspense fallback={<PageSkeleton />}>
                  <BacktestReplayPage />
                </Suspense>
              }
            />
            <Route path=":runId" element={<BacktestRunDetailsPage />} />
          </Route>
          <Route path="research" element={<StrategyResearchLayout />}>
            <Route index element={<Navigate to="leaderboard" replace />} />
            <Route path="leaderboard" element={<ResearchLeaderboardPage />} />
          </Route>
          <Route path="paper" element={<PaperTradingPage />} />
          <Route path="debug" element={<DebugToolsPage />} />
          <Route path="brokers" element={<TraderBrokerRoute />} />
          <Route path="profile" element={<ProfilePage />} />
          <Route path="admin" element={<AdminGate />}>
            <Route element={<AdminConsoleLayout />}>
              <Route index element={<AdminOverviewPage />} />
              <Route path="broker-infrastructure" element={<AdminBrokerInfrastructurePage />} />
              <Route path="market-connectivity" element={<Navigate to="../broker-infrastructure" replace />} />
              <Route path="market" element={<AdminMarketIntelPage />} />
              <Route
                path="intraday/*"
                element={
                  <Suspense fallback={<PageSkeleton />}>
                    <AdminIntradayOpsPage />
                  </Suspense>
                }
              />
              <Route path="replay" element={<AdminReplayInfraPage />} />
              <Route path="signals" element={<AdminSignalsPage />} />
              <Route path="execution" element={<AdminExecutionPage />} />
              <Route path="traders-health" element={<AdminTraderHealthPage />} />
              <Route path="backfill" element={<AdminBackfillPage />} />
              <Route path="infrastructure" element={<AdminInfrastructurePage />} />
              <Route path="history" element={<AdminHistoryPage />} />
              <Route path="audit" element={<AdminAuditPage />} />
              <Route path="controls" element={<AdminControlsPage />} />
              <Route path="users" element={<AdminUsersPage />} />
              <Route path="strategies" element={<AdminStrategiesPage />} />
              <Route path="strategy-catalog" element={<AdminStrategyCatalogPage />} />
              <Route path="universe-groups" element={<AdminUniverseGroupsPage />} />
              <Route path="runtime-bindings" element={<AdminRuntimeBindingsPage />} />
              <Route path="oms" element={<AdminOmsMonitorPage />} />
              <Route path="settings" element={<AdminSectionPage section="settings" />} />
              <Route path="security" element={<AdminSectionPage section="security" />} />
              <Route path="reports" element={<AdminSectionPage section="reports" />} />
              <Route path="alerts" element={<AdminSectionPage section="alerts" />} />
              <Route path="ops" element={<AdminOpsGate />} />
              <Route path="pipeline-health" element={<AdminPipelineHealthPage />} />
            </Route>
          </Route>
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </ErrorBoundary>
  );
}
