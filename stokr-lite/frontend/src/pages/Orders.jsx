import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import client from '../api/client';

const STATUS_STYLE = {
  COMPLETE: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  REJECTED: 'bg-rose-50 text-rose-600 border-rose-200',
  OPEN: 'bg-amber-50 text-amber-600 border-amber-200',
  UNKNOWN: 'bg-slate-50 text-slate-500 border-slate-200',
};

const FILTERS = [
  { id: 'ALL', label: 'All' },
  { id: 'COMPLETE', label: 'Executed' },
  { id: 'REJECTED', label: 'Rejected' },
  { id: 'OPEN', label: 'Pending' },
];

function fmtTime(iso) {
  if (!iso) return '--';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '--';
  return d.toLocaleString('en-IN', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true });
}

export default function Orders() {
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [brokerFilter, setBrokerFilter] = useState('ALL');

  const { data, isLoading, isError, refetch, isFetching } = useQuery({
    queryKey: ['optionArbOrders', statusFilter],
    queryFn: () => client.get('/option-arbitrage/orders', {
      params: { status: statusFilter === 'ALL' ? undefined : statusFilter, limit: 500 },
    }).then((r) => r.data),
    refetchInterval: 10000,
  });

  const allOrders = data?.orders || [];
  const orders = brokerFilter === 'ALL' ? allOrders : allOrders.filter(o => (o.broker || 'PAPER') === brokerFilter);
  const brokers = [...new Set(allOrders.map(o => o.broker || 'PAPER'))];

  return (
    <div>
      <div className="mb-6">
        <div className="flex items-center gap-3 mb-1">
          <div className="w-1 h-7 rounded-full bg-gradient-to-b from-indigo-500 to-violet-500" />
          <h1 className="text-3xl font-bold text-slate-900 tracking-tight">Orders</h1>
        </div>
        <p className="text-slate-400 text-sm ml-4">Every order this platform has placed -- executed, rejected, or pending -- across all strategies</p>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
        <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
          {FILTERS.map(f => (
            <button key={f.id} onClick={() => setStatusFilter(f.id)}
              className={`px-3 py-1.5 rounded-lg text-xs font-bold transition ${statusFilter === f.id ? 'bg-indigo-600 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-200'}`}>
              {f.label}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-2">
          {brokers.length > 1 && (
            <div className="flex items-center gap-1.5 bg-slate-100 p-1.5 rounded-xl">
              <button onClick={() => setBrokerFilter('ALL')}
                className={`px-2.5 py-1 rounded-lg text-[11px] font-bold transition ${brokerFilter === 'ALL' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>All</button>
              {brokers.map(b => (
                <button key={b} onClick={() => setBrokerFilter(b)}
                  className={`px-2.5 py-1 rounded-lg text-[11px] font-bold transition ${brokerFilter === b ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>{b}</button>
              ))}
            </div>
          )}
          <button onClick={() => refetch()} className="px-3 py-1.5 bg-white border border-slate-300 text-slate-600 text-xs font-bold rounded-lg hover:bg-slate-50">
            {isFetching ? '...' : '↻ Refresh'}
          </button>
        </div>
      </div>

      <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm">
        {isLoading && <div className="p-12 text-center text-slate-400 text-sm font-semibold">Loading orders...</div>}
        {isError && <div className="p-12 text-center text-rose-500 text-sm font-semibold">Could not load orders</div>}
        {!isLoading && !isError && orders.length === 0 && (
          <div className="p-12 text-center text-slate-400 text-sm font-semibold">No orders match this filter</div>
        )}
        {!isLoading && !isError && orders.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-[13px] text-left border-collapse">
              <thead className="bg-slate-50 border-b border-slate-200 text-slate-500 uppercase tracking-tight font-bold text-[11px]">
                <tr>
                  <th className="px-4 py-3">Time</th>
                  <th className="px-4 py-3">Type</th>
                  <th className="px-4 py-3">Instrument</th>
                  <th className="px-4 py-3">Product</th>
                  <th className="px-4 py-3">Broker</th>
                  <th className="px-4 py-3 text-right">Qty</th>
                  <th className="px-4 py-3 text-right">Price</th>
                  <th className="px-4 py-3 text-center">Status</th>
                  <th className="px-4 py-3">Reason</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {orders.map((o, i) => (
                  <tr key={i} className={`hover:bg-slate-50 ${o.reason ? 'bg-rose-50/30' : ''}`}>
                    <td className="px-4 py-2.5 font-mono text-[11px] text-slate-500">{fmtTime(o.time)}</td>
                    <td className="px-4 py-2.5">
                      <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold border ${o.side === 'BUY' ? 'bg-emerald-50 text-emerald-600 border-emerald-200' : 'bg-rose-50 text-rose-600 border-rose-200'}`}>
                        {o.side}
                      </span>
                    </td>
                    <td className="px-4 py-2.5 font-bold text-slate-800">
                      {o.symbol} <span className="text-slate-400 font-normal text-[10px]">NFO</span>
                    </td>
                    <td className="px-4 py-2.5 text-slate-500 text-[11px]">{o.product}</td>
                    <td className="px-4 py-2.5 text-slate-500 text-[11px]">{o.broker}</td>
                    <td className="px-4 py-2.5 text-right font-mono">{o.qty}</td>
                    <td className="px-4 py-2.5 text-right font-mono">₹{Number(o.price || 0).toFixed(2)}</td>
                    <td className="px-4 py-2.5 text-center">
                      <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold border ${STATUS_STYLE[o.status] || STATUS_STYLE.UNKNOWN}`}>
                        {o.status}
                      </span>
                    </td>
                    <td className="px-4 py-2.5 text-[11px] text-rose-700 max-w-[320px]" title={o.reason || ''}>
                      {o.reason || <span className="text-slate-300">--</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
