import type { ReactNode } from "react";
import { Suspense } from "react";
import { Navigate, Outlet, Route, Routes } from "react-router-dom";
import { AppShell } from "./layout/AppShell";
import { LoginPage } from "./pages/LoginPage";
import { RegisterPage } from "./pages/RegisterPage";
import { ForgotPasswordPage } from "./pages/ForgotPasswordPage";
import { ResetPasswordPage } from "./pages/ResetPasswordPage";
import { VerifyEmailPage } from "./pages/VerifyEmailPage";
import { ProfilePage } from "./pages/ProfilePage";
import { BrokersPage } from "./pages/BrokersPage";
import { ZerodhaOauthCompletePage } from "./pages/ZerodhaOauthCompletePage";
import { V5DashboardPage } from "./pages/V5DashboardPage";
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

function ShellSidebar() {
  const isLight = useUiThemeStore((s) => s.mode === "light");
  return (
    <nav className={cn("flex flex-col gap-1", isLight ? "text-neutral-700" : "text-neutral-300")}>
      <span className="mb-4 text-[11px] font-bold uppercase tracking-widest text-neutral-500">Stokr v5</span>
    </nav>
  );
}

function ShellTopNav() {
  return (
    <div className="flex items-center justify-between px-6 py-3">
      <span className="text-sm font-semibold">Stokr v5</span>
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
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />
        <Route path="/reset-password" element={<ResetPasswordPage />} />
        <Route path="/verify-email" element={<VerifyEmailPage />} />
        <Route path="/brokers/zerodha-complete" element={<ZerodhaOauthCompletePage />} />
        <Route element={<DashboardLayout />}>
          <Route path="/" element={<Navigate to="/dashboard" replace />} />
          <Route path="/dashboard" element={<V5DashboardPage />} />
          <Route path="/profile" element={<ProfilePage />} />
          <Route path="/brokers" element={<Suspense fallback={<PageSkeleton />}><BrokersPage /></Suspense>} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </ErrorBoundary>
  );
}
