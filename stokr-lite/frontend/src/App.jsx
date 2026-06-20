import { Routes, Route, Navigate } from 'react-router-dom';
import Layout from './components/Layout';
import ProtectedRoute from './components/ProtectedRoute';
import Login from './pages/Login';
import Register from './pages/Register';
import BrokerCallback from './pages/BrokerCallback';
import Dashboard from './pages/Dashboard';
import Signals from './pages/Signals';
import AdvancedBacktest from './pages/AdvancedBacktest';
import Strategies from './pages/Strategies';
import Deployments from './pages/Deployments';
import Brokers from './pages/Brokers';
import Orders from './pages/Orders';
import Positions from './pages/Positions';
import Settings from './pages/Settings';
import AdminDashboard from './pages/admin/AdminDashboard';
import AdminUsers from './pages/admin/AdminUsers';
import AdminDeployments from './pages/admin/AdminDeployments';
import AdminBrokerHealth from './pages/admin/AdminBrokerHealth';
import AdminKillSwitch from './pages/admin/AdminKillSwitch';
import AdminErrorLogs from './pages/admin/AdminErrorLogs';
import AdminUniverseGroups from './pages/admin/AdminUniverseGroups';
import AdminStrategyMappings from './pages/admin/AdminStrategyMappings';
import AdminStrategyConfigs from './pages/admin/AdminStrategyConfigs';

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/register" element={<Register />} />
      {/* Broker OAuth callback - outside ProtectedRoute since broker redirects here directly */}
      <Route path="/brokers/callback" element={<BrokerCallback />} />
      <Route element={<ProtectedRoute />}>
        <Route element={<Layout />}>
          <Route path="/" element={<Dashboard />} />
          <Route path="/signals" element={<Signals />} />
          <Route path="/backtest" element={<AdvancedBacktest />} />
          <Route path="/strategies" element={<Strategies />} />
          <Route path="/deployments" element={<Deployments />} />
          <Route path="/brokers" element={<Brokers />} />
          <Route path="/orders" element={<Orders />} />
          <Route path="/positions" element={<Positions />} />
          <Route path="/settings" element={<Settings />} />
          {/* Admin routes */}
          <Route path="/admin" element={<AdminDashboard />} />
          <Route path="/admin/users" element={<AdminUsers />} />
          <Route path="/admin/deployments" element={<AdminDeployments />} />
          <Route path="/admin/brokers" element={<AdminBrokerHealth />} />
          <Route path="/admin/kill-switch" element={<AdminKillSwitch />} />
          <Route path="/admin/errors" element={<AdminErrorLogs />} />
          <Route path="/admin/universe-groups" element={<AdminUniverseGroups />} />
          <Route path="/admin/strategy-mappings" element={<AdminStrategyMappings />} />
          <Route path="/admin/strategy-configs" element={<AdminStrategyConfigs />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
