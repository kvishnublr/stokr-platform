import { Outlet, NavLink, useNavigate } from 'react-router-dom';

const traderLinks = [
  { to: '/', label: 'Dashboard', icon: '📊' },
  { to: '/strategies', label: 'Strategies', icon: '🧠' },
  { to: '/deployments', label: 'Deployments', icon: '🚀' },
  { to: '/brokers', label: 'Brokers', icon: '🏦' },
  { to: '/orders', label: 'Orders', icon: '📋' },
  { to: '/positions', label: 'Positions', icon: '📈' },
  { to: '/settings', label: 'Settings', icon: '⚙️' },
];

const adminLinks = [
  { to: '/admin', label: 'Overview', icon: '👑' },
  { to: '/admin/users', label: 'Users', icon: '👥' },
  { to: '/admin/deployments', label: 'All Deploys', icon: '🔧' },
  { to: '/admin/brokers', label: 'Broker Health', icon: '❤️' },
  { to: '/admin/kill-switch', label: 'Kill Switch', icon: '🛑' },
  { to: '/admin/errors', label: 'Error Logs', icon: '🐛' },
];

function getUserRole() {
  try {
    const token = localStorage.getItem('token');
    const payload = JSON.parse(atob(token.split('.')[1]));
    return payload.role;
  } catch { return null; }
}

export default function Layout() {
  const navigate = useNavigate();
  const role = getUserRole();
  const isAdmin = role === 'ADMIN';

  const handleLogout = () => {
    localStorage.clear();
    navigate('/login');
  };

  return (
    <div className="flex h-screen">
      {/* Sidebar */}
      <aside className="w-60 bg-gray-900 text-white flex flex-col">
        <div className="p-4 border-b border-gray-700">
          <h1 className="text-xl font-bold">Stokr Lite</h1>
          <p className="text-xs text-gray-400 mt-1">Algo Trading Platform</p>
        </div>
        <nav className="flex-1 overflow-y-auto py-2">
          <div className="px-3 py-2 text-xs font-semibold text-gray-500 uppercase">Trading</div>
          {traderLinks.map((link) => (
            <NavLink
              key={link.to}
              to={link.to}
              end={link.to === '/'}
              className={({ isActive }) =>
                `flex items-center gap-3 px-4 py-2.5 text-sm transition ${
                  isActive ? 'bg-blue-600 text-white' : 'text-gray-300 hover:bg-gray-800'
                }`
              }
            >
              <span>{link.icon}</span>
              <span>{link.label}</span>
            </NavLink>
          ))}
          {isAdmin && (
            <>
              <div className="px-3 py-2 mt-4 text-xs font-semibold text-gray-500 uppercase">Admin</div>
              {adminLinks.map((link) => (
                <NavLink
                  key={link.to}
                  to={link.to}
                  end={link.to === '/admin'}
                  className={({ isActive }) =>
                    `flex items-center gap-3 px-4 py-2.5 text-sm transition ${
                      isActive ? 'bg-red-600 text-white' : 'text-gray-300 hover:bg-gray-800'
                    }`
                  }
                >
                  <span>{link.icon}</span>
                  <span>{link.label}</span>
                </NavLink>
              ))}
            </>
          )}
        </nav>
        <div className="p-4 border-t border-gray-700">
          <button onClick={handleLogout} className="w-full text-sm text-gray-400 hover:text-white transition">
            Logout
          </button>
        </div>
      </aside>
      {/* Main content */}
      <main className="flex-1 overflow-y-auto p-6 bg-gray-50">
        <Outlet />
      </main>
    </div>
  );
}
