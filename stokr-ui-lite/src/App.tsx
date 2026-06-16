import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { useAuthStore } from './state/authStore'
import LoginPage from './pages/LoginPage'
import DashboardPage from './pages/DashboardPage'
import StrategiesPage from './pages/StrategiesPage'
import InstancesPage from './pages/InstancesPage'
import OrdersPage from './pages/OrdersPage'
import PositionsPage from './pages/PositionsPage'

function PrivateRoute({ children }: { children: React.ReactNode }) {
  const { isAuthenticated } = useAuthStore()
  return isAuthenticated ? <>{children}</> : <Navigate to="/login" />
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/" element={
          <PrivateRoute>
            <DashboardPage />
          </PrivateRoute>
        } />
        <Route path="/strategies" element={
          <PrivateRoute>
            <StrategiesPage />
          </PrivateRoute>
        } />
        <Route path="/instances" element={
          <PrivateRoute>
            <InstancesPage />
          </PrivateRoute>
        } />
        <Route path="/orders" element={
          <PrivateRoute>
            <OrdersPage />
          </PrivateRoute>
        } />
        <Route path="/positions" element={
          <PrivateRoute>
            <PositionsPage />
          </PrivateRoute>
        } />
      </Routes>
    </BrowserRouter>
  )
}
