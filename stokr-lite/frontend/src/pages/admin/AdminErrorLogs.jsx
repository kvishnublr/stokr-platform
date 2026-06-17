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

  if (isLoading) return (
    <div className="flex items-center justify-center h-64">
      <div className="animate-spin w-6 h-6 border-2 border-indigo-500 border-t-transparent rounded-full" />
    </div>
  );

  const severityBadge = (severity) => {
    const classes = {
      ERROR: 'bg-rose-50 text-rose-600',
      CRITICAL: 'bg-rose-100 text-rose-700',
      WARN: 'bg-amber-50 text-amber-600',
    };
    return classes[severity] || 'bg-slate-100 text-slate-600';
  };

  return (
    <div className="animate-fade-in-up">
      <div className="flex items-center gap-3 mb-6">
        <div className="w-1 h-7 rounded-full bg-gradient-to-b from-rose-500 to-orange-500" />
        <h1 className="text-3xl font-bold text-slate-800">Error Logs</h1>
      </div>

      <div className="card-crystal overflow-hidden">
        <table className="w-full text-sm table-crystal">
          <thead>
            <tr>
              <th>Time</th>
              <th>Severity</th>
              <th>Type</th>
              <th>Message</th>
              <th>Deployment</th>
            </tr>
          </thead>
          <tbody>
            {errors.length === 0 && (
              <tr><td colSpan="5" className="p-8 text-center text-slate-400">No errors found</td></tr>
            )}
            {errors.map((e) => (
              <tr key={e.id} className="border-t border-slate-50 hover:bg-slate-50/50 transition-colors">
                <td className="p-4 text-xs text-slate-500 whitespace-nowrap">{new Date(e.createdAt).toLocaleString()}</td>
                <td className="p-4">
                  <span className={`inline-flex px-2 py-0.5 rounded-md text-[10px] font-semibold tracking-wide uppercase ${severityBadge(e.severity)}`}>{e.severity}</span>
                </td>
                <td className="p-4 text-xs font-mono text-slate-600">{e.errorType}</td>
                <td className="p-4 max-w-md">
                  <p className="truncate text-sm text-slate-700" title={e.message}>{e.message}</p>
                </td>
                <td className="p-4 text-xs text-slate-500">{e.deploymentId ? `#${e.deploymentId}` : '-'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      <div className="flex justify-between items-center mt-4">
        <button onClick={() => setPage(Math.max(0, page - 1))} disabled={page === 0}
          className="text-sm text-indigo-600 hover:text-indigo-700 disabled:text-slate-400 font-medium transition-colors px-3 py-1.5 rounded-lg hover:bg-indigo-50">Previous</button>
        <span className="text-sm text-slate-500 font-medium">Page {page + 1}</span>
        <button onClick={() => setPage(page + 1)} disabled={errors.length < 20}
          className="text-sm text-indigo-600 hover:text-indigo-700 disabled:text-slate-400 font-medium transition-colors px-3 py-1.5 rounded-lg hover:bg-indigo-50">Next</button>
      </div>
    </div>
  );
}
