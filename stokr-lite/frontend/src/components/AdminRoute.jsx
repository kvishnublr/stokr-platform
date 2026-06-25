import { Navigate, Outlet } from 'react-router-dom';

function getRole() {
  try {
    const token = localStorage.getItem('token');
    return JSON.parse(atob(token.split('.')[1])).role;
  } catch { return null; }
}

export default function AdminRoute() {
  return getRole() === 'ADMIN' ? <Outlet /> : <Navigate to="/" replace />;
}
