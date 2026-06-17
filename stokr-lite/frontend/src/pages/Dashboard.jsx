import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import client from '../api/client';

export default function Dashboard() {
  const { data: deployments } = useQuery({
    queryKey: ['deployments'],
    queryFn: () => client.get('/deployments').then((r) => r.data),
  });

  const active = deployments?.filter((d) => d.status === 'ACTIVE') || [];
  const stopped = deployments?.filter((d) => d.status !== 'ACTIVE') || [];
  const paper = deployments?.filter((d) => d.mode === 'PAPER') || [];

  return (
    <div>
      <div className="mb-8">
        <h1 className="text-2xl font-bold text-slate-800">Dashboard</h1>
        <p className="text-slate-500 text-sm mt-1">Overview of your trading activity</p>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-5 mb-8">
        <StatCard title="Total Deployments" value={deployments?.length || 0} icon="M13 10V3L4 14h7v7l9-11h-7z" gradient="from-indigo-500 to-violet-500" />
        <StatCard title="Active" value={active.length} icon="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" gradient="from-emerald-500 to-teal-500" />
        <StatCard title="Stopped" value={stopped.length} icon="M10 9v6m4-6v6m7-3a9 9 0 11-18 0 9 9 0 0118 0z" gradient="from-slate-400 to-slate-500" />
        <StatCard title="Paper Mode" value={paper.length} icon="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" gradient="from-amber-400 to-orange-500" />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Active Deployments */}
        <div className="lg:col-span-2 bg-white rounded-2xl border border-slate-200/60 shadow-sm">
          <div className="px-6 py-5 border-b border-slate-100 flex items-center justify-between">
            <h2 className="font-semibold text-slate-800">Active Deployments</h2>
            <Link to="/deployments" className="text-sm text-indigo-600 hover:text-indigo-700 font-medium">View all</Link>
          </div>
          {active.length === 0 ? (
            <div className="p-8 text-center">
              <div className="w-12 h-12 rounded-xl bg-slate-100 flex items-center justify-center mx-auto mb-3">
                <svg className="w-6 h-6 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M13 10V3L4 14h7v7l9-11h-7z" />
                </svg>
              </div>
              <p className="text-slate-500 text-sm">No active deployments</p>
              <Link to="/deployments" className="text-indigo-600 text-sm font-medium mt-2 inline-block hover:text-indigo-700">Deploy a strategy</Link>
            </div>
          ) : (
            <div className="divide-y divide-slate-100">
              {active.map((d) => (
                <div key={d.id} className="px-6 py-4 flex items-center justify-between">
                  <div>
                    <p className="font-medium text-slate-800 text-sm">{d.strategyName || `Strategy #${d.strategyId}`}</p>
                    <p className="text-xs text-slate-400 mt-0.5">{new Date(d.createdAt).toLocaleDateString()}</p>
                  </div>
                  <div className="flex items-center gap-3">
                    <ModeBadge mode={d.mode} />
                    <span className="text-sm font-medium text-slate-600">&#8377;{d.capital?.toLocaleString()}</span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Market Status */}
        <div className="bg-white rounded-2xl border border-slate-200/60 shadow-sm">
          <div className="px-6 py-5 border-b border-slate-100">
            <h2 className="font-semibold text-slate-800">Market Status</h2>
          </div>
          <div className="p-6">
            <MarketStatus />
            <div className="mt-6 space-y-3">
              <InfoRow label="Session" value="09:15 - 15:30 IST" />
              <InfoRow label="EOD Square-off" value="15:15 IST" />
              <InfoRow label="Scan Interval" value="60 seconds" />
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function StatCard({ title, value, icon, gradient }) {
  return (
    <div className="bg-white rounded-2xl border border-slate-200/60 shadow-sm p-5 flex items-start justify-between">
      <div>
        <p className="text-xs font-medium text-slate-500 uppercase tracking-wide">{title}</p>
        <p className="text-3xl font-bold text-slate-800 mt-1">{value}</p>
      </div>
      <div className={`w-10 h-10 rounded-xl bg-gradient-to-br ${gradient} flex items-center justify-center shadow-lg shadow-${gradient.split('-')[1]}-500/20`}>
        <svg className="w-5 h-5 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
          <path strokeLinecap="round" strokeLinejoin="round" d={icon} />
        </svg>
      </div>
    </div>
  );
}

function ModeBadge({ mode }) {
  return mode === 'LIVE'
    ? <span className="px-2.5 py-1 rounded-full text-xs font-semibold bg-rose-50 text-rose-600 ring-1 ring-rose-200">LIVE</span>
    : <span className="px-2.5 py-1 rounded-full text-xs font-semibold bg-amber-50 text-amber-600 ring-1 ring-amber-200">PAPER</span>;
}

function InfoRow({ label, value }) {
  return (
    <div className="flex justify-between text-sm">
      <span className="text-slate-500">{label}</span>
      <span className="text-slate-700 font-medium">{value}</span>
    </div>
  );
}

function MarketStatus() {
  const { data } = useQuery({
    queryKey: ['market-status'],
    queryFn: () => client.get('/market/status').then((r) => r.data),
  });
  return (
    <div className="flex items-center gap-3 p-4 rounded-xl bg-slate-50">
      <div className={`w-3 h-3 rounded-full ${data?.isOpen ? 'bg-emerald-500 animate-pulse' : 'bg-rose-500'}`} />
      <div>
        <p className="text-sm font-semibold text-slate-700">{data?.isOpen ? 'Market is Open' : 'Market is Closed'}</p>
        <p className="text-xs text-slate-400">NSE/BSE</p>
      </div>
    </div>
  );
}
