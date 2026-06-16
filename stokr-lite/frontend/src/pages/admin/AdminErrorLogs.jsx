import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import client from '../../api/client';

export default function AdminErrorLogs() {
  const [page, setPage] = useState(0);

  const { data, isLoading } = useQuery({
    queryKey: ['admin-errors', page],
    queryFn: () => client.get('/admin/errors', { params: { page, size: 20 } }).then((r) => r.data),
  });

  const errors = data?.content || data || [];

  return (
    <div>
      <h1 className="text-2xl font-bold mb-6">Error Logs</h1>
      <div className="bg-white rounded-lg shadow overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50">
            <tr className="text-left text-gray-500">
              <th className="p-4">Time</th>
              <th className="p-4">Severity</th>
              <th className="p-4">Type</th>
              <th className="p-4">Message</th>
              <th className="p-4">Deployment</th>
            </tr>
          </thead>
          <tbody>
            {errors.length === 0 && (
              <tr><td colSpan="5" className="p-8 text-center text-gray-400">No errors found</td></tr>
            )}
            {errors.map((e) => (
              <tr key={e.id} className="border-t hover:bg-gray-50">
                <td className="p-4 text-xs text-gray-500 whitespace-nowrap">{new Date(e.createdAt).toLocaleString()}</td>
                <td className="p-4">
                  <span className={`px-2 py-1 rounded text-xs font-medium ${
                    e.severity === 'ERROR' || e.severity === 'CRITICAL' ? 'bg-red-100 text-red-700' :
                    e.severity === 'WARN' ? 'bg-yellow-100 text-yellow-700' : 'bg-gray-100 text-gray-600'
                  }`}>{e.severity}</span>
                </td>
                <td className="p-4 text-xs font-mono">{e.errorType}</td>
                <td className="p-4 max-w-md">
                  <p className="truncate" title={e.message}>{e.message}</p>
                </td>
                <td className="p-4 text-xs text-gray-500">{e.deploymentId ? `#${e.deploymentId}` : '-'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      <div className="flex justify-between items-center mt-4">
        <button onClick={() => setPage(Math.max(0, page - 1))} disabled={page === 0}
          className="text-sm text-blue-600 hover:text-blue-800 disabled:text-gray-400">Previous</button>
        <span className="text-sm text-gray-500">Page {page + 1}</span>
        <button onClick={() => setPage(page + 1)} disabled={errors.length < 20}
          className="text-sm text-blue-600 hover:text-blue-800 disabled:text-gray-400">Next</button>
      </div>
    </div>
  );
}
