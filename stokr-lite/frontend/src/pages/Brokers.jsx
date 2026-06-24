import { useState, useEffect, useRef, useCallback } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import client from '../api/client';

const BROKER_META = {
  ZERODHA: {
    color: 'linear-gradient(135deg, #f43f5e, #e11d48)',
    letter: 'Z',
    desc: "India's largest stock broker",
    glow: 'rgba(244,63,94,0.3)',
    primary: true,
    note: 'Required — all live data & exits run through Zerodha',
  },
  DHAN: {
    color: 'linear-gradient(135deg, #06b6d4, #3b82f6)',
    letter: 'D',
    desc: 'Modern trading platform',
    glow: 'rgba(6,182,212,0.3)',
    primary: false,
  },
  FYERS: {
    color: 'linear-gradient(135deg, #10b981, #059669)',
    letter: 'F',
    desc: 'Commission-free trading',
    glow: 'rgba(16,185,129,0.3)',
    primary: false,
  },
};

const BROKER_OAUTH_MESSAGE = 'stokr_broker_oauth';
const OAUTH_RESULT_KEY = 'stokr_broker_oauth_result';

export default function Brokers() {
  const queryClient = useQueryClient();
  const [connectingBroker, setConnectingBroker] = useState(null);
  const [oauthResult, setOauthResult] = useState(null);
  const popupRef = useRef(null);
  const messageReceivedRef = useRef(false);

  const { data: brokers, isLoading } = useQuery({
    queryKey: ['brokers'],
    queryFn: () => client.get('/brokers').then((r) => r.data),
  });

  const { data: supported } = useQuery({
    queryKey: ['supported-brokers'],
    queryFn: () => client.get('/brokers/supported').then((r) => r.data),
  });

  const { data: health, refetch: refetchHealth } = useQuery({
    queryKey: ['broker-health'],
    queryFn: () => client.get('/brokers/health').then((r) => r.data),
    refetchInterval: 5 * 60 * 1000, // re-check every 5 min
    staleTime: 60 * 1000,
  });

  const disconnectMutation = useMutation({
    mutationFn: (id) => client.delete(`/brokers/${id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['brokers'] });
      queryClient.invalidateQueries({ queryKey: ['broker-health'] });
    },
  });

  const handleOauthResult = useCallback((result) => {
    setConnectingBroker(null);
    popupRef.current = null;
    messageReceivedRef.current = true;
    if (result.status === 'ok') {
      setOauthResult({ status: 'ok', broker: result.broker });
      queryClient.invalidateQueries({ queryKey: ['brokers'] });
      queryClient.invalidateQueries({ queryKey: ['broker-health'] });
      refetchHealth();
    } else {
      const reason = result.reason || 'unknown';
      const msg = result.message || 'Connection failed';
      setOauthResult({ status: 'error', broker: result.broker, reason, message: decodeURIComponent(msg) });
    }
  }, [queryClient, refetchHealth]);

  useEffect(() => {
    function handleMessage(event) {
      if (event.data?.type === BROKER_OAUTH_MESSAGE) handleOauthResult(event.data);
    }
    window.addEventListener('message', handleMessage);
    return () => window.removeEventListener('message', handleMessage);
  }, [handleOauthResult]);

  useEffect(() => {
    if (!connectingBroker) return;
    const pollKey = setInterval(() => {
      const stored = localStorage.getItem(OAUTH_RESULT_KEY);
      if (stored) {
        localStorage.removeItem(OAUTH_RESULT_KEY);
        try {
          const result = JSON.parse(stored);
          if (result.broker === connectingBroker?.toLowerCase()) handleOauthResult(result);
        } catch { /* ignore */ }
      }
    }, 300);
    return () => clearInterval(pollKey);
  }, [connectingBroker, handleOauthResult]);

  const connectBroker = async (brokerName) => {
    setOauthResult(null);
    setConnectingBroker(brokerName);
    messageReceivedRef.current = false;
    try {
      const { data } = await client.get(`/brokers/${brokerName}/connect`);
      if (data.authUrl) {
        const width = 600, height = 700;
        const left = window.screenX + (window.outerWidth - width) / 2;
        const top  = window.screenY + (window.outerHeight - height) / 2;
        const popup = window.open(
          data.authUrl, `${brokerName}_oauth`,
          `width=${width},height=${height},left=${left},top=${top},scrollbars=yes`
        );
        popupRef.current = popup;
        if (!popup || popup.closed) {
          setConnectingBroker(null);
          setOauthResult({ status: 'error', broker: brokerName, reason: 'popup_blocked',
            message: 'Pop-up was blocked. Allow pop-ups for this site, then try again.' });
          return;
        }
        const checkClosed = setInterval(() => {
          if (popup.closed) {
            clearInterval(checkClosed);
            setTimeout(() => {
              if (!messageReceivedRef.current) {
                setConnectingBroker(null);
                queryClient.invalidateQueries({ queryKey: ['brokers'] });
              }
            }, 500);
          }
        }, 500);
      }
    } catch (err) {
      setConnectingBroker(null);
      setOauthResult({ status: 'error', broker: brokerName, reason: 'connect_failed',
        message: err.response?.data?.error || err.message || 'Failed to get auth URL' });
    }
  };

  if (isLoading) return <LoadingSkeleton />;

  const healthBad = health && !health.ok;
  const healthStatus = health?.status;

  return (
    <div>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        marginBottom: '32px', paddingBottom: '24px', borderBottom: '2px solid rgba(148,163,184,0.08)' }}>
        <div>
          <h1 style={{ fontSize: '32px', fontWeight: 900,
            background: 'linear-gradient(135deg, #0f172a 0%, #4f46e5 100%)',
            WebkitBackgroundClip: 'text', WebkitTextFillColor: 'transparent',
            letterSpacing: '-1px', marginBottom: '6px' }}>Broker Connections</h1>
          <p style={{ fontSize: '13px', color: 'var(--text-muted)' }}>
            Zerodha is the primary broker — live data, exits, and order placement all run through it
          </p>
        </div>
        {/* Health pill */}
        {health && (
          <div style={{
            display: 'flex', alignItems: 'center', gap: '8px',
            padding: '8px 16px', borderRadius: '999px',
            background: health.ok ? 'rgba(16,185,129,0.1)' : 'rgba(239,68,68,0.1)',
            border: `1.5px solid ${health.ok ? 'rgba(16,185,129,0.3)' : 'rgba(239,68,68,0.3)'}`,
          }}>
            <span style={{ width: '8px', height: '8px', borderRadius: '50%',
              background: health.ok ? '#10b981' : '#ef4444',
              boxShadow: health.ok ? '0 0 6px #10b981' : '0 0 6px #ef4444' }} />
            <span style={{ fontSize: '12px', fontWeight: 700,
              color: health.ok ? '#059669' : '#dc2626' }}>
              Zerodha {health.ok ? 'LIVE' : healthStatus === 'TOKEN_EXPIRED' ? 'TOKEN EXPIRED' : 'NOT CONNECTED'}
            </span>
          </div>
        )}
      </div>

      {/* Critical Health Banner */}
      {healthBad && (
        <div className="animate-scale-in" style={{
          marginBottom: '28px', padding: '20px 24px', borderRadius: '16px',
          border: '2px solid rgba(239,68,68,0.3)',
          background: 'linear-gradient(135deg, rgba(239,68,68,0.08), rgba(220,38,38,0.05))',
        }}>
          <div style={{ display: 'flex', alignItems: 'flex-start', gap: '16px' }}>
            <div style={{ fontSize: '28px', flexShrink: 0 }}>🚨</div>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: '15px', fontWeight: 800, color: '#dc2626', marginBottom: '6px' }}>
                ACTION REQUIRED — Live Trading Paused
              </div>
              <div style={{ fontSize: '13px', color: '#7f1d1d', lineHeight: 1.6, marginBottom: '12px' }}>
                {health.message}
              </div>
              <div style={{ fontSize: '12px', color: '#991b1b', marginBottom: '12px' }}>
                {healthStatus === 'TOKEN_EXPIRED'
                  ? 'Zerodha tokens expire daily. Reconnect every morning before market open (9:15 IST).'
                  : 'Without Zerodha: no live price data, no SL/target monitoring, no order placement.'}
              </div>
              <button
                onClick={() => connectBroker('ZERODHA')}
                disabled={connectingBroker === 'ZERODHA'}
                style={{
                  padding: '10px 20px', borderRadius: '10px', border: 'none',
                  background: 'linear-gradient(135deg, #f43f5e, #e11d48)',
                  color: 'white', fontWeight: 700, fontSize: '13px', cursor: 'pointer',
                }}
              >
                {connectingBroker === 'ZERODHA' ? 'Opening...' :
                  healthStatus === 'TOKEN_EXPIRED' ? 'Reconnect Zerodha' : 'Connect Zerodha'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* OAuth Result Banner */}
      {oauthResult && (
        <div className="animate-scale-in" style={{
          marginBottom: '24px', padding: '16px 20px', borderRadius: '16px', border: '2px solid',
          background: oauthResult.status === 'ok' ? 'rgba(16,185,129,0.08)' : 'rgba(239,68,68,0.08)',
          borderColor: oauthResult.status === 'ok' ? 'rgba(16,185,129,0.2)' : 'rgba(239,68,68,0.2)',
          color: oauthResult.status === 'ok' ? '#059669' : '#dc2626',
        }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
              <span style={{ fontSize: '20px' }}>{oauthResult.status === 'ok' ? '✅' : '❌'}</span>
              <span style={{ fontSize: '14px', fontWeight: 600 }}>
                {oauthResult.status === 'ok'
                  ? `${oauthResult.broker} connected — live trading active`
                  : `${oauthResult.broker || 'Broker'} failed: ${oauthResult.message || oauthResult.reason}`}
              </span>
            </div>
            <button onClick={() => setOauthResult(null)}
              style={{ background: 'none', border: 'none', fontSize: '18px', cursor: 'pointer', opacity: 0.5 }}>✕</button>
          </div>
        </div>
      )}

      {/* Connecting indicator */}
      {connectingBroker && (
        <div className="animate-scale-in" style={{
          marginBottom: '24px', padding: '16px 20px', borderRadius: '16px',
          border: '2px solid rgba(99,102,241,0.2)', background: 'rgba(99,102,241,0.08)', color: '#4f46e5',
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
            <div className="animate-spin" style={{ width: '20px', height: '20px',
              border: '2px solid currentColor', borderTopColor: 'transparent', borderRadius: '50%' }} />
            <span style={{ fontSize: '14px', fontWeight: 600 }}>Connecting to {connectingBroker}...</span>
          </div>
        </div>
      )}

      {/* Connected Brokers */}
      {brokers?.length > 0 && (
        <div style={{ marginBottom: '40px' }}>
          <SectionTitle color="linear-gradient(180deg, #10b981, #34d399)">Connected Accounts</SectionTitle>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '18px' }}>
            {brokers.map((b, i) => {
              const meta = BROKER_META[b.brokerName] || {
                color: 'linear-gradient(135deg, #64748b, #475569)', letter: b.brokerName[0], glow: 'rgba(100,116,139,0.3)',
              };
              return (
                <div key={b.id} className="card-crystal animate-fade-in-up"
                  style={{ animationDelay: `${i * 100}ms`, padding: '24px',
                    border: meta.primary && health?.ok ? '2px solid rgba(16,185,129,0.3)' : undefined }}>
                  <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: '16px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                      <div style={{ width: '48px', height: '48px', borderRadius: '14px', background: meta.color,
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        color: 'white', fontWeight: 800, fontSize: '18px', boxShadow: `0 8px 24px ${meta.glow}` }}>
                        {meta.letter}
                      </div>
                      <div>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                          <h3 style={{ fontWeight: 700, color: '#0f172a', fontSize: '15px' }}>{b.brokerName}</h3>
                          {meta.primary && (
                            <span style={{ fontSize: '9px', fontWeight: 800, padding: '2px 6px',
                              borderRadius: '4px', background: 'rgba(244,63,94,0.1)', color: '#e11d48',
                              textTransform: 'uppercase', letterSpacing: '0.5px' }}>PRIMARY</span>
                          )}
                        </div>
                        <p style={{ fontSize: '12px', color: '#94a3b8' }}>{b.clientId}</p>
                      </div>
                    </div>
                    <span style={{ display: 'flex', alignItems: 'center', gap: '6px', padding: '4px 10px',
                      borderRadius: '8px', fontSize: '10px', fontWeight: 800, textTransform: 'uppercase',
                      letterSpacing: '0.6px', background: 'rgba(16,185,129,0.12)', color: '#059669' }}>
                      <span style={{ width: '6px', height: '6px', borderRadius: '50%', background: '#10b981' }} />
                      Active
                    </span>
                  </div>
                  {meta.primary && health?.ok && (
                    <div style={{ marginBottom: '12px', padding: '8px 12px', borderRadius: '8px',
                      background: 'rgba(16,185,129,0.08)', fontSize: '11px', color: '#059669',
                      display: 'flex', alignItems: 'center', gap: '6px' }}>
                      <span>●</span> Live data fetching every minute
                    </div>
                  )}
                  <button onClick={() => disconnectMutation.mutate(b.id)}
                    style={{ background: 'none', border: '2px solid rgba(239,68,68,0.15)', color: '#ef4444',
                      fontSize: '12px', fontWeight: 600, padding: '8px 16px', borderRadius: '10px',
                      cursor: 'pointer', transition: 'all 0.2s', width: '100%' }}
                    onMouseEnter={(e) => { e.currentTarget.style.background = 'rgba(239,68,68,0.08)'; e.currentTarget.style.borderColor = 'rgba(239,68,68,0.3)'; }}
                    onMouseLeave={(e) => { e.currentTarget.style.background = 'none'; e.currentTarget.style.borderColor = 'rgba(239,68,68,0.15)'; }}
                  >Disconnect</button>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* Available Brokers */}
      <div>
        <SectionTitle color="linear-gradient(180deg, #6366f1, #a78bfa)">Available Brokers</SectionTitle>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '18px' }}>
          {(supported || ['ZERODHA', 'DHAN', 'FYERS']).map((name, i) => {
            const brokerName = typeof name === 'string' ? name : name.name;
            const meta = BROKER_META[brokerName] || {
              color: 'linear-gradient(135deg, #64748b, #475569)', letter: brokerName[0],
              desc: 'Trading broker', glow: 'rgba(100,116,139,0.3)', primary: false,
            };
            const isConnected = brokers?.some((b) => b.brokerName === brokerName);
            return (
              <div key={brokerName} className="card-crystal animate-fade-in-up"
                style={{ animationDelay: `${i * 100}ms`, padding: 0, overflow: 'hidden',
                  boxShadow: meta.primary ? `0 0 0 2px rgba(244,63,94,0.2), 0 8px 32px ${meta.glow}` : undefined }}>
                <div style={{ height: '4px', background: meta.color }} />
                <div style={{ padding: '28px' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '14px', marginBottom: '8px' }}>
                    <div style={{ width: '56px', height: '56px', borderRadius: '16px', background: meta.color,
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                      color: 'white', fontWeight: 800, fontSize: '22px', boxShadow: `0 8px 24px ${meta.glow}` }}>
                      {meta.letter}
                    </div>
                    <div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                        <h3 style={{ fontWeight: 800, color: '#0f172a', fontSize: '18px' }}>{brokerName}</h3>
                        {meta.primary && (
                          <span style={{ fontSize: '9px', fontWeight: 800, padding: '3px 7px',
                            borderRadius: '4px', background: 'rgba(244,63,94,0.12)', color: '#e11d48',
                            textTransform: 'uppercase', letterSpacing: '0.5px' }}>DEFAULT</span>
                        )}
                      </div>
                      <p style={{ fontSize: '12px', color: '#94a3b8', marginTop: '2px' }}>{meta.desc}</p>
                    </div>
                  </div>
                  {meta.primary && meta.note && (
                    <p style={{ fontSize: '11px', color: '#64748b', marginBottom: '16px',
                      padding: '8px 12px', background: 'rgba(244,63,94,0.05)',
                      borderRadius: '8px', borderLeft: '3px solid #f43f5e' }}>
                      {meta.note}
                    </p>
                  )}
                  {!meta.primary && <div style={{ marginBottom: '16px' }} />}
                  {isConnected ? (
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px',
                      padding: '12px 16px', borderRadius: '12px', background: 'rgba(16,185,129,0.08)',
                      color: '#059669', fontSize: '14px', fontWeight: 600,
                      border: '2px solid rgba(16,185,129,0.15)' }}>
                      <span>✅</span> Connected
                    </div>
                  ) : (
                    <button onClick={() => connectBroker(brokerName)} disabled={connectingBroker === brokerName}
                      style={{ width: '100%', padding: '14px', borderRadius: '14px',
                        background: meta.color, color: 'white', fontSize: '14px', fontWeight: 700,
                        border: 'none', cursor: 'pointer', transition: 'all 0.3s',
                        boxShadow: `0 8px 24px ${meta.glow}` }}
                      onMouseEnter={(e) => { if (!connectingBroker) e.currentTarget.style.transform = 'scale(1.02)'; }}
                      onMouseLeave={(e) => { e.currentTarget.style.transform = 'scale(1)'; }}
                    >
                      {connectingBroker === brokerName ? (
                        <span style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '8px' }}>
                          <span className="animate-spin" style={{ display: 'inline-block', width: '16px',
                            height: '16px', border: '2px solid white', borderTopColor: 'transparent', borderRadius: '50%' }} />
                          Opening...
                        </span>
                      ) : meta.primary ? 'Connect (Required)' : 'Connect'}
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Info footer */}
      <div style={{ marginTop: '40px', padding: '20px', borderRadius: '12px',
        background: 'rgba(148,163,184,0.06)', border: '1px solid rgba(148,163,184,0.1)' }}>
        <div style={{ fontSize: '12px', color: '#94a3b8', lineHeight: 1.8 }}>
          <strong style={{ color: '#64748b' }}>How live trading works:</strong>
          {' '}Every minute during market hours (9:15–15:30), Zerodha quote data is fetched and stored as 1-min candles.
          {' '}Strategies evaluate on this data and place orders through Zerodha.
          {' '}SL and target are monitored every minute — exits are automatic from the application.
          {' '}<strong style={{ color: '#ef4444' }}>Reconnect Zerodha every morning</strong> — access tokens expire daily.
        </div>
      </div>
    </div>
  );
}

function SectionTitle({ color, children }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '20px' }}>
      <div style={{ width: '4px', height: '20px', borderRadius: '999px', background: color }} />
      <h2 style={{ fontSize: '14px', fontWeight: 800, textTransform: 'uppercase',
        letterSpacing: '1px', color: '#94a3b8' }}>{children}</h2>
    </div>
  );
}

function LoadingSkeleton() {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
      <div className="skeleton" style={{ width: '240px', height: '40px', marginBottom: '16px' }} />
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '18px' }}>
        {[1, 2, 3].map(i => (
          <div key={i} className="card-crystal" style={{ padding: '28px' }}>
            <div className="skeleton" style={{ width: '100%', height: '160px' }} />
          </div>
        ))}
      </div>
    </div>
  );
}
