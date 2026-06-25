import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import client from '../../api/client';

export default function AdminOrders() {
  const [page, setPage] = useState(0);
  const [tab, setTab] = useState('all');

  const { data: pendingOrders } = useQuery({
    queryKey: ['admin-orders-pending'],
    queryFn: () => client.get('/admin/orders/pending').then((r) => r.data),
    refetchInterval: 15000,
  });

  const { data, isLoading } = useQuery({
    queryKey: ['admin-orders', page],
    queryFn: () => client.get('/admin/orders', { params: { page, size: 30 } }).then((r) => r.data),
  });

  const orders = data?.content || data || [];
  const totalPages = data?.totalPages || 1;

  return (
    <div className="animate-fade-in-up">
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <div className="w-1 h-7 rounded-full bg-gradient-to-b from-indigo-500 to-violet-500" />
          <h1 className="text-3xl font-bold text-slate-800">Orders</h1>
        </div>
        <div className="flex gap-2">
          <button onClick={() => { setTab('all'); setPage(0); }}
            className={`px-4 py-2 rounded-xl text-sm font-medium transition-all ${tab === 'all' ? 'bg-indigo-50 text-indigo-600' : 'text-slate-500 hover:text-slate-700'}`}>All Orders</button>
          <button onClick={() => { setTab('pending'); setPage(0); }}
            className={`px-4 py-2 rounded-xl text-sm font-medium transition-all ${tab === 'pending' ? 'bg-indigo-50 text-indigo-600' : 'text-slate-500 hover:text-slate-700'}`}>
            Pending {pendingOrders?.length > 0 && <span className="ml-1.5 px-1.5 py-0.5 bg-amber-100 text-amber-700 rounded-full text-xs">{pendingOrders.length}</span>}
          </button>
        </div>
      </div>

      {tab === 'pending' && pendingOrders?.length > 0 && (
        <div className="card-crystal overflow-hidden mb-6 border-l-4 border-l-amber-400">
          <div className="px-5 py-3 bg-amber-50/50 border-b border-slate-100">
            <h2 className="text-sm font-semibold text-amber-800">Pending Orders ({pendingOrders.length})</h2>
          </div>
          <table className="w-full text-sm table-crystal">
            <thead>
              <tr>
                <th>Time</th><th>User</th><th>Symbol</th><th>Side</th><th>Qty</th><th>Price</th><th>Status</th>
              </tr>
            </thead>
            <tbody>
              {pendingOrders.map((o) => (
                <tr key={o.id} className="border-t border-slate-50 hover:bg-slate-50/50">
                  <td className="p-3 text-xs text-slate-500">{new Date(o.createdAt).toLocaleString()}</td>
                  <td className="p-3 text-slate-700">{o.userEmail || `#${o.userId}`}</td>
                  <td className="p-3 font-medium text-slate-800">{o.symbol}</td>
                  <td className="p-3">
                    <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${o.side === 'BUY' ? 'bg-emerald-50 text-emerald-600' : 'bg-rose-50 text-rose-600'}`}>{o.side}</span>
                  </td>
                  <td className="p-3 text-slate-700">{o.quantity}</td>
                  <td className="p-3 text-slate-700">{o.price ? `₹${o.price}` : '-'}</td>
                  <td className="p-3">
                    <span className="px-2 py-0.5 rounded-md text-[10px] font-semibold uppercase bg-amber-50 text-amber-600">{o.status}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {tab === 'pending' && (!pendingOrders || pendingOrders.length === 0) && (
        <div className="card-crystal p-12 text-center mb-6 border-l-4 border-l-amber-400">
          <div className="text-3xl mb-3">✅</div>
          <p className="text-slate-500 text-sm">No pending orders</p>
        </div>
      )}

      <div className="card-crystal overflow-hidden">
        {isLoading ? (
          <div className="flex items-center justify-center h-64">
            <div className="animate-spin w-6 h-6 border-2 border-indigo-500 border-t-transparent rounded-full" />
          </div>
        ) : (
          <>
            <table className="w-full text-sm table-crystal">
              <thead>
                <tr>
                  <th>Time</th><th>User</th><th>Symbol</th><th>Side</th><th>Qty</th><th>Price</th><th>Status</th>
                </tr>
              </thead>
              <tbody>
                {orders.length === 0 && (
                  <tr><td colSpan="7" className="p-8 text-center text-slate-400">No orders found</td></tr>
                )}
                {orders.map((o) => (
                  <tr key={o.id} className="border-t border-slate-50 hover:bg-slate-50/50 transition-colors">
                    <td className="p-3 text-xs text-slate-500 whitespace-nowrap">{new Date(o.createdAt).toLocaleString()}</td>
                    <td className="p-3 text-slate-700">{o.userEmail || `#${o.userId}`}</td>
                    <td className="p-3 font-medium text-slate-800">{o.symbol}</td>
                    <td className="p-3">
                      <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${o.side === 'BUY' ? 'bg-emerald-50 text-emerald-600 border border-emerald-200' : 'bg-rose-50 text-rose-600 border border-rose-200'}`}>{o.side}</span>
                    </td>
                    <td className="p-3 text-slate-700">{o.quantity}</td>
                    <td className="p-3 text-slate-700">{o.price ? `₹${o.price}` : '-'}</td>
                    <td className="p-3">
                      <span className={`px-2 py-0.5 rounded-md text-[10px] font-semibold uppercase ${o.status === 'COMPLETE' || o.status === 'FILLED' ? 'bg-emerald-50 text-emerald-600' : o.status === 'REJECTED' ? 'bg-rose-50 text-rose-600' : 'bg-amber-50 text-amber-600'}`}>{o.status}</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            {totalPages > 1 && (
              <div className="flex justify-between items-center px-5 py-4 border-t border-slate-100">
                <button onClick={() => setPage(Math.max(0, page - 1))} disabled={page === 0}
                  className="text-sm text-indigo-600 hover:text-indigo-700 disabled:text-slate-400 font-medium transition-colors px-3 py-1.5 rounded-lg hover:bg-indigo-50">Previous</button>
                <span className="text-sm text-slate-500 font-medium">Page {page + 1} of {totalPages}</span>
                <button onClick={() => setPage(page + 1)} disabled={page >= totalPages - 1}
                  className="text-sm text-indigo-600 hover:text-indigo-700 disabled:text-slate-400 font-medium transition-colors px-3 py-1.5 rounded-lg hover:bg-indigo-50">Next</button>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
