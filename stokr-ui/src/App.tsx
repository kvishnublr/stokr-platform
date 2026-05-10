import type { ReactNode } from "react";
import { lazy, Suspense } from "react";
import { Navigate, Outlet, Route, Routes } from "react-router-dom";
import { ShellLayout } from "./layout/ShellLayout";
import { LoginPage } from "./pages/LoginPage";
import { RegisterPage } from "./pages/RegisterPage";
import { DashboardPage } from "./pages/DashboardPage";
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
import { AdminOverviewPage } from "./pages/AdminOverviewPage";
import { AdminUsersPage } from "./pages/AdminUsersPage";
import { AdminStrategiesPage } from "./pages/AdminStrategiesPage";
import { AdminOmsMonitorPage } from "./pages/AdminOmsMonitorPage";
import { AdminOpsPage } from "./pages/AdminOpsPage";
import { useSessionStore } from "./state/session";
import { VerifyEmailPage } from "./pages/VerifyEmailPage";
import { OnboardingWizardPage } from "./pages/OnboardingWizardPage";
import { TerminalPage } from "./pages/TerminalPage";
import { PageSkeleton } from "./components/ds/SkeletonLoader";
import { ErrorBoundary } from "./components/ds/ErrorBoundary";

/** Heavy chart surface — defer initial JS until navigation. */
const BacktestReplayPage = lazy(async () => {
  const m = await import("./pages/BacktestReplayPage");
  return { default: m.BacktestReplayPage };
});

function Protected({ children }: { children: ReactNode }) {
  const token = useSessionStore((s) => s.accessToken);
  if (!token) {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
}

function AdminGate() {
  const ok = useSessionStore((s) => s.hasRole("ROLE_ADMIN"));
  if (!ok) {
    return <Navigate to="/" replace />;
  }
  return <Outlet />;
}

export default function App() {
  return (
    <ErrorBoundary>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/verify-email" element={<VerifyEmailPage />} />
        <Route
          path="/"
          element={
            <Protected>
              <ShellLayout />
            </Protected>
          }
        >
          <Route index element={<DashboardPage />} />
          <Route path="terminal" element={<TerminalPage />} />
          <Route path="onboarding" element={<OnboardingWizardPage />} />
          <Route path="orders" element={<OrdersPage />} />
          <Route path="trades" element={<TradesPage />} />
          <Route path="executions" element={<ExecutionsPage />} />
          <Route path="positions" element={<PositionsPage />} />
          <Route path="strategies" element={<StrategiesPage />} />
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
          <Route path="brokers" element={<BrokersPage />} />
          <Route path="admin" element={<AdminGate />}>
            <Route index element={<AdminOverviewPage />} />
            <Route path="users" element={<AdminUsersPage />} />
            <Route path="strategies" element={<AdminStrategiesPage />} />
            <Route path="oms" element={<AdminOmsMonitorPage />} />
            <Route path="ops" element={<AdminOpsPage />} />
          </Route>
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </ErrorBoundary>
  );
}
