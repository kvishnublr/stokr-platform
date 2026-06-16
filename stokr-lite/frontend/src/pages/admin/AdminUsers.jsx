import { useQuery } from '@tanstack/react-query';
import client from '../../api/client';

export default function AdminUsers() {
  const { data: users, isLoading } = useQuery({
    queryKey: ['admin-users'],
    queryFn: () => client.get('/admin/users').then((r) => r.data),
  });

  if (isLoading) return <div className="text-gray-500">Loading...</div>;

  return (
    <div>
      <h1 className="text-2xl font-bold mb-6">User Management</h1>
      <div className="bg-white rounded-lg shadow overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50">
            <tr className="text-left text-gray-500">
              <th className="p-4">Email</th>
              <th className="p-4">Role</th>
              <th className="p-4">Status</th>
              <th className="p-4">Registered</th>
            </tr>
          </thead>
          <tbody>
            {users?.map((u) => (
              <tr key={u.id} className="border-t">
                <td className="p-4 font-medium">{u.email}</td>
                <td className="p-4">
                  <span className={`px-2 py-1 rounded text-xs font-medium ${u.role === 'ADMIN' ? 'bg-purple-100 text-purple-700' : 'bg-blue-100 text-blue-700'}`}>{u.role}</span>
                </td>
                <td className="p-4">
                  <span className={`px-2 py-1 rounded text-xs ${u.enabled ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'}`}>{u.enabled ? 'Active' : 'Disabled'}</span>
                </td>
                <td className="p-4 text-gray-500">{new Date(u.createdAt).toLocaleDateString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
