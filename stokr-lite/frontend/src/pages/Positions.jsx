import { useState, useEffect } from 'react';
import client from '../api/client';
import { LivePositionsSection, BrokerPositionsPanel } from './OptionArbitrage';

const TABS = [
  { id: 'mine', label: '📊 My Positions' },
  { id: 'broker', label: '🏦 Broker Positions (Ground Truth)' },
];

export default function Positions() {
  const [executionBroker, setExecutionBroker] = useState('PAPER');
  const [activeTab, setActiveTab] = useState('mine');

  useEffect(() => {
    client.get('/brokers/decoupled-routing')
      .then((res) => { if (res.data?.executionBroker) setExecutionBroker(res.data.executionBroker); })
      .catch(() => {});
  }, []);

  return (
    <div className="space-y-4">
      <div style={{ marginBottom: '8px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '4px' }}>
          <div style={{ width: '4px', height: '28px', borderRadius: '999px', background: 'linear-gradient(180deg, #6366f1, #7c3aed)' }} />
          <h1 style={{ fontSize: '28px', fontWeight: 800, color: '#0f172a', margin: 0, letterSpacing: '-0.5px' }}>Positions & P&L</h1>
        </div>
        <p style={{ color: '#94a3b8', fontSize: '14px', margin: 0, paddingLeft: '16px' }}>Live option-arbitrage positions across every strategy, plus real broker positions for reconciliation</p>
      </div>

      <div className="flex items-center gap-2 bg-white p-1.5 rounded-2xl border border-slate-200 shadow-sm w-fit">
        {TABS.map(t => (
          <button
            key={t.id}
            onClick={() => setActiveTab(t.id)}
            className={`px-4 py-2 rounded-xl text-sm font-bold transition ${
              activeTab === t.id
                ? 'bg-gradient-to-r from-indigo-600 to-violet-600 text-white shadow-md'
                : 'text-slate-500 hover:bg-slate-100 hover:text-slate-800'
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {activeTab === 'mine' && <LivePositionsSection executionBroker={executionBroker} defaultExpanded />}
      {activeTab === 'broker' && <BrokerPositionsPanel executionBroker={executionBroker} defaultExpanded />}
    </div>
  );
}
