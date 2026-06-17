import { useQuery } from '@tanstack/react-query';
import client from '../../api/client';

export default function AdminUsers() {
  const { data: users, isLoading } = useQuery({
    queryKey: ['admin-users'],
    queryFn: () => client.get('/admin/users').then((r) => r.data),
  });

  if (isLoading) return (
    <div className="flex items-center justify-center h-64">
      <div className="animate-spin w-6 h-6 border-2 border-indigo-500 border-t-transparent rounded-full" />
    </div>
  );

  return (
    <div className="animate-fade-in-up">
      <div className="flex items-center gap-3 mb-6">
        <div className="w-1 h-7 rounded-full bg-gradient-to-b from-indigo-500 to-violet-500" />
        <h1 className="text-3xl font-bold text-slate-800">User Management</h1>
      </div>

      <div className="card-crystal overflow-hidden">
        <table className="w-full text-sm table-crystal">
          <thead>
            <tr>
              <th>Email</th>
              <th>Role</th>
              <th>Status</th>
              <th>Registered</th>
            </tr>
          </thead>
          <tbody>
            {(!users || users.length === 0) && (
              <tr><td colSpan="4" className="p-8 text-center text-slate-400">No users found</td></tr>
            )}
            {users?.map((u) => (
              <tr key={u.id} className="border-t border-slate-50 hover:bg-slate-50/50 transition-colors">
                <td className="p-4 font-medium text-slate-800">{u.email}</td>
                <td className="p-4">
                  <span className={`inline-flex px-2 py-0.5 rounded-md text-[10px] font-semibold tracking-wide uppercase ${
                    u.role === 'ADMIN' ? 'bg-violet-50 text-violet-600' : 'bg-sky-50 text-sky-600'
                  }`}>{u.role}</span>
                </td>
                <td className="p-4">
                  <span className={`inline-flex px-2 py-0.5 rounded-md text-[10px] font-semibold tracking-wide uppercase ${
                    u.enabled ? 'bg-emerald-50 text-emerald-600' : 'bg-rose-50 text-rose-600'
                  }`}>{u.enabled ? 'Active' : 'Disabled'}</span>
                </td>
                <td className="p-4 text-slate-500">{new Date(u.createdAt).toLocaleDateString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
