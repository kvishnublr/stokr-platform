import type { ReactNode } from "react";
import { Suspense, useState } from "react";
import { Navigate, Outlet, Route, Routes, useNavigate, useLocation } from "react-router-dom";
import { AppShell } from "./layout/AppShell";
import { LoginPage } from "./pages/LoginPage";
import { SplitLayoutLoginPage } from "./pages/SplitLayoutLoginPage";
import { RegisterPage } from "./pages/RegisterPage";
import { ForgotPasswordPage } from "./pages/ForgotPasswordPage";
import { ResetPasswordPage } from "./pages/ResetPasswordPage";
import { VerifyEmailPage } from "./pages/VerifyEmailPage";
import { ProfilePage } from "./pages/ProfilePage";
import { BrokersPage } from "./pages/BrokersPage";
import { ZerodhaOauthCompletePage } from "./pages/ZerodhaOauthCompletePage";
import { V5DashboardPage } from "./pages/V5DashboardPage";
import { AdminDashboard } from "./pages/admin/AdminDashboard";
import { AdminOpsPage } from "./pages/admin/AdminOpsPage";
import { AdminExecutionPage } from "./pages/admin/AdminExecutionPage";
import { AdminSignalsPage } from "./pages/admin/AdminSignalsPage";
import { AdminStrategyCatalogPage } from "./pages/admin/AdminStrategyCatalogPage";
import { AdminAlertCenterPage } from "./pages/admin/AdminAlertCenterPage";
import { AdminBrokerInfrastructurePage } from "./pages/admin/AdminBrokerInfrastructurePage";
import { AdminCapitalPage } from "./pages/admin/AdminCapitalPage";
import { AdminBackfillPage } from "./pages/admin/AdminBackfillPage";
import { AdminUniverseGroupsPage } from "./pages/admin/AdminUniverseGroupsPage";
import { AdminRuntimeBindingsPage } from "./pages/admin/AdminRuntimeBindingsPage";
import { AdminExecutionConfigPage } from "./pages/admin/AdminExecutionConfigPage";
import { AdminLogsPage } from "./pages/admin/AdminLogsPage";
import { AdminAuditPage } from "./pages/admin/AdminAuditPage";
import { AdminMarketSimulationPage } from "./pages/admin/AdminMarketSimulationPage";
import { AdminSignalLabPage } from "./pages/admin/AdminSignalLabPage";
import { AdminSignalReplayPage } from "./pages/admin/AdminSignalReplayPage";
import { AdminCommandCenterPage } from "./pages/admin/AdminCommandCenterPage";
import { AdminInfrastructurePage } from "./pages/admin/AdminInfrastructurePage";
import { AdminInfrastructureHealthCenterPage } from "./pages/admin/AdminInfrastructureHealthCenterPage";
import { AdminIntradayOpsPage } from "./pages/admin/AdminIntradayOpsPage";
import { AdminMarketIntelPage } from "./pages/admin/AdminMarketIntelPage";
import { AdminBacktestDataPage } from "./pages/admin/AdminBacktestDataPage";
import { AdminProtectionDiagnosticsPage } from "./pages/admin/AdminProtectionDiagnosticsPage";
import { AdminStrategyDiagnosticsPage } from "./pages/admin/AdminStrategyDiagnosticsPage";
import { AdminStrategyEffectivenessPage } from "./pages/admin/AdminStrategyEffectivenessPage";
import { AdminReplayInfraPage } from "./pages/admin/AdminReplayInfraPage";
import { AdminPipelineHealthPage } from "./pages/admin/AdminPipelineHealthPage";
import { AdminTraderHealthPage } from "./pages/admin/AdminTraderHealthPage";
import { AdminRiskDashboardPage } from "./pages/admin/AdminRiskDashboardPage";
import { AdminFailureAnalysisConsolePage } from "./pages/admin/AdminFailureAnalysisConsolePage";
import { AdminHistoryPage } from "./pages/admin/AdminHistoryPage";
import { AdminTestSignalLabPage } from "./pages/admin/AdminTestSignalLabPage";
import { AdminTestExecutionMonitorPage } from "./pages/admin/AdminTestExecutionMonitorPage";
import { AdminControlsPage } from "./pages/admin/AdminControlsPage";
import { AdminResearchLabPage } from "./pages/admin/AdminResearchLabPage";
import AdminSignalTracePage from "./pages/admin/AdminSignalTracePage";
import { AdminSafetyDiagnosticsPage } from "./pages/admin/AdminSafetyDiagnosticsPage";
import { useSessionStore } from "./state/session";
import { ErrorBoundary } from "./components/ds/ErrorBoundary";
import { ThemeHtmlSync } from "./components/theme/ThemeHtmlSync";
import { SyncedToaster } from "./components/theme/SyncedToaster";
import { PageSkeleton } from "./components/ds/SkeletonLoader";
import { cn } from "./lib/utils";
import { useUiThemeStore } from "./state/uiTheme";

function Protected({ children }: { children: ReactNode }) {
  const token = useSessionStore((s) => s.accessToken);
  if (!token) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

const adminNavItems = [
  { label: "Dashboard", path: "/admin" },
  { label: "Command Center", path: "/admin/command-center" },
  { label: "Operations", path: "/admin/ops" },
  { label: "OMS", path: "/admin/oms" },
  { label: "Signals", path: "/admin/signals" },
  { label: "Strategies", path: "/admin/strategies" },
  { label: "Universe Groups", path: "/admin/universe-groups" },
  { label: "Runtime Bindings", path: "/admin/runtime-bindings" },
  { label: "Risk Dashboard", path: "/admin/risk-dashboard" },
  { label: "Users", path: "/admin/users" },
  { label: "Broker Infra", path: "/admin/brokers" },
  { label: "Capital", path: "/admin/capital" },
  { label: "Execution Config", path: "/admin/execution-config" },
  { label: "Backfill", path: "/admin/backfill" },
  { label: "Simulation", path: "/admin/simulation" },
  { label: "Market Intel", path: "/admin/market-intel" },
  { label: "Logs", path: "/admin/logs" },
  { label: "Audit", path: "/admin/audit" },
  { label: "Alerts", path: "/admin/alerts" },
  { label: "Infrastructure", path: "/admin/infrastructure" },
  { label: "Health Center", path: "/admin/health-center" },
  { label: "Pipeline Health", path: "/admin/pipeline-health" },
  { label: "Trader Health", path: "/admin/trader-health" },
  { label: "Replay Infra", path: "/admin/replay-infra" },
  { label: "Signal Lab", path: "/admin/signal-lab" },
  { label: "Signal Replay", path: "/admin/signal-replay" },
  { label: "Test Signal Lab", path: "/admin/test-signal-lab" },
  { label: "Test Execution", path: "/admin/test-execution-monitor" },
  { label: "Strategy Diagnostics", path: "/admin/strategy-diagnostics" },
  { label: "Protection Diagnostics", path: "/admin/protection-diagnostics" },
  { label: "Strategy Effectiveness", path: "/admin/strategy-effectiveness" },
  { label: "Safety Diagnostics", path: "/admin/safety-diagnostics" },
  { label: "Failure Analysis", path: "/admin/failure-analysis" },
  { label: "Intraday Ops", path: "/admin/intraday-ops" },
  { label: "Research Lab", path: "/admin/research-lab" },
  { label: "Controls", path: "/admin/controls" },
  { label: "History", path: "/admin/history" },
  { label: "Backtest Data", path: "/admin/backtest-data" },
];

function ShellSidebar() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  const location = useLocation();
  const [collapsed, setCollapsed] = useState(false);

  return (
    <div className={cn("flex flex-col gap-1", isLight ? "text-neutral-700" : "text-neutral-300")}>
      <div className="mb-4 flex items-center justify-between">
        <span className="text-[11px] font-bold uppercase tracking-widest text-neutral-500">Stokr v6</span>
        <button onClick={() => setCollapsed(!collapsed)} className="text-[10px] text-neutral-400 hover:text-neutral-600">
          {collapsed ? "+" : "-"}
        </button>
      </div>
      {!collapsed && (
        <div className="space-y-0.5">
          <div className="mb-2 px-2 text-[10px] font-bold uppercase tracking-widest text-neutral-400">Dashboard</div>
          <SidebarLink to="/dashboard" label="Home" currentPath={location.pathname} />
          <div className="mb-2 mt-4 px-2 text-[10px] font-bold uppercase tracking-widest text-neutral-400">Admin</div>
          {adminNavItems.map((item) => (
            <SidebarLink key={item.path} to={item.path} label={item.label} currentPath={location.pathname} />
          ))}
        </div>
      )}
    </div>
  );
}

function SidebarLink({ to, label, currentPath }: { to: string; label: string; currentPath: string }) {
  const navigate = useNavigate();
  const active = currentPath === to || (to !== "/admin" && currentPath.startsWith(to));
  return (
    <div
      onClick={() => navigate(to)}
      className={cn(
        "cursor-pointer rounded px-3 py-1.5 text-xs transition-colors",
        active
          ? "bg-orange-50 font-medium text-orange-600"
          : "text-neutral-500 hover:bg-neutral-100 hover:text-neutral-700",
      )}
    >
      {label}
    </div>
  );
}

function ShellTopNav() {
  const navigate = useNavigate();
  const location = useLocation();
  const isAdmin = useSessionStore((s) => s.hasRole("ROLE_ADMIN"));
  return (
    <div className="flex items-center justify-between px-6 py-3">
      <span className="text-sm font-semibold">Stokr v6</span>
      <div className="flex items-center gap-4">
        {isAdmin && location.pathname !== "/admin" && (
          <button onClick={() => navigate("/admin")} className="rounded border px-3 py-1 text-xs font-semibold hover:bg-neutral-100">
            Admin
          </button>
        )}
      </div>
    </div>
  );
}

function DashboardLayout() {
  return (
    <Protected>
      <AppShell sidebar={<ShellSidebar />} topNav={<ShellTopNav />}>
        <Outlet />
      </AppShell>
    </Protected>
  );
}

export default function App() {
  return (
    <ErrorBoundary>
      <ThemeHtmlSync />
      <SyncedToaster />
      <Routes>
        <Route path="/login" element={<SplitLayoutLoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />
        <Route path="/reset-password" element={<ResetPasswordPage />} />
        <Route path="/verify-email" element={<VerifyEmailPage />} />
        <Route path="/brokers/zerodha-complete" element={<ZerodhaOauthCompletePage />} />

        {/* Admin dashboard has its own layout (AdminSidebar + AdminTopbar) */}
        <Route path="/admin" element={<AdminDashboard />} />

        {/* Standard dashboard layout for non-admin pages */}
        <Route element={<DashboardLayout />}>
          <Route path="/" element={<Navigate to="/dashboard" replace />} />
          <Route path="/dashboard" element={<V5DashboardPage />} />
          <Route path="/profile" element={<ProfilePage />} />
          <Route path="/brokers" element={<Suspense fallback={<PageSkeleton />}><BrokersPage /></Suspense>} />

          {/* Admin pages under standard layout */}
          <Route path="/admin/ops" element={<Suspense fallback={<PageSkeleton />}><AdminOpsPage /></Suspense>} />
          <Route path="/admin/oms" element={<Suspense fallback={<PageSkeleton />}><AdminExecutionPage /></Suspense>} />
          <Route path="/admin/signals" element={<Suspense fallback={<PageSkeleton />}><AdminSignalsPage /></Suspense>} />
          <Route path="/admin/strategies" element={<Suspense fallback={<PageSkeleton />}><AdminStrategyCatalogPage /></Suspense>} />
          <Route path="/admin/alerts" element={<Suspense fallback={<PageSkeleton />}><AdminAlertCenterPage /></Suspense>} />
          <Route path="/admin/users" element={<Suspense fallback={<PageSkeleton />}><AdminTraderHealthPage /></Suspense>} />
          <Route path="/admin/brokers" element={<Suspense fallback={<PageSkeleton />}><AdminBrokerInfrastructurePage /></Suspense>} />
          <Route path="/admin/capital" element={<Suspense fallback={<PageSkeleton />}><AdminCapitalPage /></Suspense>} />
          <Route path="/admin/backfill" element={<Suspense fallback={<PageSkeleton />}><AdminBackfillPage /></Suspense>} />
          <Route path="/admin/universe-groups" element={<Suspense fallback={<PageSkeleton />}><AdminUniverseGroupsPage /></Suspense>} />
          <Route path="/admin/runtime-bindings" element={<Suspense fallback={<PageSkeleton />}><AdminRuntimeBindingsPage /></Suspense>} />
          <Route path="/admin/execution-config" element={<Suspense fallback={<PageSkeleton />}><AdminExecutionConfigPage /></Suspense>} />
          <Route path="/admin/logs" element={<Suspense fallback={<PageSkeleton />}><AdminLogsPage /></Suspense>} />
          <Route path="/admin/audit" element={<Suspense fallback={<PageSkeleton />}><AdminAuditPage /></Suspense>} />
          <Route path="/admin/simulation" element={<Suspense fallback={<PageSkeleton />}><AdminMarketSimulationPage /></Suspense>} />
          <Route path="/admin/signal-lab" element={<Suspense fallback={<PageSkeleton />}><AdminSignalLabPage /></Suspense>} />
          <Route path="/admin/signal-replay" element={<Suspense fallback={<PageSkeleton />}><AdminSignalReplayPage /></Suspense>} />
          <Route path="/admin/command-center" element={<Suspense fallback={<PageSkeleton />}><AdminCommandCenterPage /></Suspense>} />
          <Route path="/admin/infrastructure" element={<Suspense fallback={<PageSkeleton />}><AdminInfrastructurePage /></Suspense>} />
          <Route path="/admin/health-center" element={<Suspense fallback={<PageSkeleton />}><AdminInfrastructureHealthCenterPage /></Suspense>} />
          <Route path="/admin/intraday-ops" element={<Suspense fallback={<PageSkeleton />}><AdminIntradayOpsPage /></Suspense>} />
          <Route path="/admin/market-intel" element={<Suspense fallback={<PageSkeleton />}><AdminMarketIntelPage /></Suspense>} />
          <Route path="/admin/backtest-data" element={<Suspense fallback={<PageSkeleton />}><AdminBacktestDataPage /></Suspense>} />
          <Route path="/admin/protection-diagnostics" element={<Suspense fallback={<PageSkeleton />}><AdminProtectionDiagnosticsPage /></Suspense>} />
          <Route path="/admin/strategy-diagnostics" element={<Suspense fallback={<PageSkeleton />}><AdminStrategyDiagnosticsPage /></Suspense>} />
          <Route path="/admin/strategy-effectiveness" element={<Suspense fallback={<PageSkeleton />}><AdminStrategyEffectivenessPage /></Suspense>} />
          <Route path="/admin/replay-infra" element={<Suspense fallback={<PageSkeleton />}><AdminReplayInfraPage /></Suspense>} />
          <Route path="/admin/pipeline-health" element={<Suspense fallback={<PageSkeleton />}><AdminPipelineHealthPage /></Suspense>} />
          <Route path="/admin/trader-health" element={<Suspense fallback={<PageSkeleton />}><AdminTraderHealthPage /></Suspense>} />
          <Route path="/admin/risk-dashboard" element={<Suspense fallback={<PageSkeleton />}><AdminRiskDashboardPage /></Suspense>} />
          <Route path="/admin/failure-analysis" element={<Suspense fallback={<PageSkeleton />}><AdminFailureAnalysisConsolePage /></Suspense>} />
          <Route path="/admin/history" element={<Suspense fallback={<PageSkeleton />}><AdminHistoryPage /></Suspense>} />
          <Route path="/admin/test-signal-lab" element={<Suspense fallback={<PageSkeleton />}><AdminTestSignalLabPage /></Suspense>} />
          <Route path="/admin/test-execution-monitor" element={<Suspense fallback={<PageSkeleton />}><AdminTestExecutionMonitorPage /></Suspense>} />
          <Route path="/admin/controls" element={<Suspense fallback={<PageSkeleton />}><AdminControlsPage /></Suspense>} />
          <Route path="/admin/research-lab" element={<Suspense fallback={<PageSkeleton />}><AdminResearchLabPage /></Suspense>} />
          <Route path="/admin/signal-trace/:id" element={<Suspense fallback={<PageSkeleton />}><AdminSignalTracePage /></Suspense>} />
          <Route path="/admin/safety-diagnostics" element={<Suspense fallback={<PageSkeleton />}><AdminSafetyDiagnosticsPage /></Suspense>} />
        </Route>

        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </ErrorBoundary>
  );
}
