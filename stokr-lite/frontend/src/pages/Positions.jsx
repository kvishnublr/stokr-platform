import { useState, useEffect } from 'react';
import client from '../api/client';
import { LivePositionsSection, BrokerPositionsPanel } from './OptionArbitrage';

export default function Positions() {
  const [executionBroker, setExecutionBroker] = useState('PAPER');

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

      <LivePositionsSection executionBroker={executionBroker} />
      <BrokerPositionsPanel executionBroker={executionBroker} />
    </div>
  );
}
