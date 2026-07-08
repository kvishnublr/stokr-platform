import { lazy, Suspense } from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import Layout from './components/Layout';
import ProtectedRoute from './components/ProtectedRoute';
import AdminRoute from './components/AdminRoute';

// Eager load auth pages (small, needed immediately)
import Login from './pages/Login';
import Register from './pages/Register';
import BrokerCallback from './pages/BrokerCallback';

// Lazy load all other pages
const Dashboard = lazy(() => import('./pages/Dashboard'));
const Signals = lazy(() => import('./pages/Signals'));
const AdvancedBacktest = lazy(() => import('./pages/AdvancedBacktest'));
const Strategies = lazy(() => import('./pages/Strategies'));
const Deployments = lazy(() => import('./pages/Deployments'));
const Brokers = lazy(() => import('./pages/Brokers'));
const Orders = lazy(() => import('./pages/Orders'));
const Positions = lazy(() => import('./pages/Positions'));
const Settings = lazy(() => import('./pages/Settings'));
const TraderDashboard = lazy(() => import('./pages/TraderDashboard'));
const AdminDashboard = lazy(() => import('./pages/admin/AdminDashboard'));
const AdminUsers = lazy(() => import('./pages/admin/AdminUsers'));
const AdminDeployments = lazy(() => import('./pages/admin/AdminDeployments'));
const AdminBrokerHealth = lazy(() => import('./pages/admin/AdminBrokerHealth'));
const AdminKillSwitch = lazy(() => import('./pages/admin/AdminKillSwitch'));
const AdminErrorLogs = lazy(() => import('./pages/admin/AdminErrorLogs'));
const AdminUniverseGroups = lazy(() => import('./pages/admin/AdminUniverseGroups'));
const AdminStrategyMappings = lazy(() => import('./pages/admin/AdminStrategyMappings'));
const AdminStrategyConfigs = lazy(() => import('./pages/admin/AdminStrategyConfigs'));
const AdminOrders = lazy(() => import('./pages/admin/AdminOrders'));
const AdminAuditLog = lazy(() => import('./pages/admin/AdminAuditLog'));

function PageLoader() {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '60vh' }}>
      <div style={{ width: 32, height: 32, border: '3px solid #e5e7eb', borderTop: '3px solid #6366f1', borderRadius: '50%', animation: 'spin 0.7s linear infinite' }} />
      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  );
}

export default function App() {
  return (
    <Suspense fallback={<PageLoader />}>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/register" element={<Register />} />
        <Route path="/brokers/callback" element={<BrokerCallback />} />
        <Route element={<ProtectedRoute />}>
          <Route element={<Layout />}>
            <Route path="/" element={<Dashboard />} />
            <Route path="/signals" element={<Signals />} />
            <Route path="/backtest" element={<AdvancedBacktest />} />
            <Route path="/strategies" element={<Strategies />} />
            <Route path="/deployments" element={<Deployments />} />
            <Route path="/trader" element={<TraderDashboard />} />
            <Route path="/brokers" element={<Brokers />} />
            <Route path="/orders" element={<Orders />} />
            <Route path="/positions" element={<Positions />} />
            <Route path="/settings" element={<Settings />} />
            <Route element={<AdminRoute />}>
              <Route path="/admin" element={<AdminDashboard />} />
              <Route path="/admin/users" element={<AdminUsers />} />
              <Route path="/admin/deployments" element={<AdminDeployments />} />
              <Route path="/admin/brokers" element={<AdminBrokerHealth />} />
              <Route path="/admin/kill-switch" element={<AdminKillSwitch />} />
              <Route path="/admin/errors" element={<AdminErrorLogs />} />
              <Route path="/admin/universe-groups" element={<AdminUniverseGroups />} />
              <Route path="/admin/strategy-mappings" element={<AdminStrategyMappings />} />
              <Route path="/admin/strategy-configs" element={<AdminStrategyConfigs />} />
              <Route path="/admin/orders" element={<AdminOrders />} />
              <Route path="/admin/audit-log" element={<AdminAuditLog />} />
            </Route>
          </Route>
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </Suspense>
  );
}
