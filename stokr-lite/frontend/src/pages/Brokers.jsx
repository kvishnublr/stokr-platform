import { useState, useEffect, useRef, useCallback } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import client from '../api/client';

const BROKER_META = {
  ZERODHA: { color: 'from-rose-500 to-red-600', letter: 'Z', desc: 'India\'s largest stock broker', glow: 'shadow-rose-500/20' },
  DHAN: { color: 'from-cyan-500 to-blue-600', letter: 'D', desc: 'Modern trading platform', glow: 'shadow-cyan-500/20' },
  FYERS: { color: 'from-emerald-500 to-green-600', letter: 'F', desc: 'Commission-free trading', glow: 'shadow-emerald-500/20' },
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

  const disconnectMutation = useMutation({
    mutationFn: (id) => client.delete(`/brokers/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['brokers'] }),
  });

  const handleOauthResult = useCallback((result) => {
    setConnectingBroker(null);
    popupRef.current = null;
    messageReceivedRef.current = true;
    if (result.status === 'ok') {
      setOauthResult({ status: 'ok', broker: result.broker });
      queryClient.invalidateQueries({ queryKey: ['brokers'] });
    } else {
      const reason = result.reason || 'unknown';
      const msg = result.message || 'Connection failed';
      setOauthResult({ status: 'error', broker: result.broker, reason, message: decodeURIComponent(msg) });
    }
  }, [queryClient]);

  useEffect(() => {
    function handleMessage(event) {
      if (event.data?.type === BROKER_OAUTH_MESSAGE) {
        handleOauthResult(event.data);
      }
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
          if (result.broker === connectingBroker?.toLowerCase()) {
            handleOauthResult(result);
          }
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
        const width = 600;
        const height = 700;
        const left = window.screenX + (window.outerWidth - width) / 2;
        const top = window.screenY + (window.outerHeight - height) / 2;
        const popup = window.open(
          data.authUrl,
          `${brokerName}_oauth`,
          `width=${width},height=${height},left=${left},top=${top},scrollbars=yes`
        );
        popupRef.current = popup;

        if (!popup || popup.closed) {
          setConnectingBroker(null);
          setOauthResult({ status: 'error', broker: brokerName, reason: 'popup_blocked', message: 'Pop-up was blocked. Allow pop-ups for this site, then try again.' });
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
      setOauthResult({ status: 'error', broker: brokerName, reason: 'connect_failed', message: err.response?.data?.error || err.message || 'Failed to get auth URL' });
    }
  };

  if (isLoading) return <LoadingSkeleton />;

  return (
    <div>
      {/* Header */}
      <div className="mb-8 animate-fade-in-up">
        <h1 className="text-3xl font-bold text-slate-800 tracking-tight">Broker Connections</h1>
        <p className="text-slate-500 text-sm mt-2">Connect your trading accounts to enable live execution</p>
      </div>

      {/* OAuth Result Banner */}
      {oauthResult && (
        <div className={`mb-6 p-4 rounded-2xl border animate-scale-in ${
          oauthResult.status === 'ok'
            ? 'bg-emerald-50 border-emerald-200 text-emerald-700'
            : 'bg-rose-50 border-rose-200 text-rose-700'
        }`}>
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              {oauthResult.status === 'ok' ? (
                <div className="w-8 h-8 rounded-lg bg-emerald-100 flex items-center justify-center">
                  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                  </svg>
                </div>
              ) : (
                <div className="w-8 h-8 rounded-lg bg-rose-100 flex items-center justify-center">
                  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
                  </svg>
                </div>
              )}
              <span className="text-sm font-medium">
                {oauthResult.status === 'ok'
                  ? `${(BROKER_META[oauthResult.broker?.toUpperCase()]?.letter || oauthResult.broker)} connected successfully!`
                  : `${oauthResult.broker || 'Broker'} link failed: ${oauthResult.message || oauthResult.reason}`}
              </span>
            </div>
            <button onClick={() => setOauthResult(null)} className="text-current opacity-50 hover:opacity-100 transition-opacity p-1 rounded-lg hover:bg-slate-100">
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
        </div>
      )}

      {/* Connecting indicator */}
      {connectingBroker && (
        <div className="mb-6 p-4 rounded-2xl border bg-indigo-50 border-indigo-200 text-indigo-700 animate-scale-in">
          <div className="flex items-center gap-3">
            <svg className="animate-spin w-5 h-5" fill="none" viewBox="0 0 24 24">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
            </svg>
            <span className="text-sm font-medium">Connecting to {connectingBroker}...</span>
          </div>
        </div>
      )}

      {/* Connected Brokers */}
      {brokers?.length > 0 && (
        <div className="mb-8 animate-fade-in-up delay-100">
          <div className="flex items-center gap-3 mb-5">
            <div className="w-1 h-6 rounded-full bg-gradient-to-b from-emerald-500 to-teal-500" />
            <h2 className="text-sm font-semibold text-slate-500 uppercase tracking-wider">Connected Accounts</h2>
          </div>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
            {brokers?.map((b, i) => {
              const meta = BROKER_META[b.brokerName] || { color: 'from-slate-500 to-slate-600', letter: b.brokerName[0], glow: 'shadow-slate-500/20' };
              return (
                <div key={b.id} className={`card-light p-5 hover-lift hover-glow animate-fade-in-up delay-${Math.min((i+2)*100, 600)}`}>
                  <div className="flex items-start justify-between mb-4">
                    <div className="flex items-center gap-3">
                      <div className={`w-11 h-11 rounded-xl bg-gradient-to-br ${meta.color} flex items-center justify-center text-white font-bold text-sm shadow-lg ${meta.glow}`}>
                        {meta.letter}
                      </div>
                      <div>
                        <h3 className="font-semibold text-slate-700 text-sm">{b.brokerName}</h3>
                        <p className="text-xs text-slate-400">{b.clientId}</p>
                      </div>
                    </div>
                    <span className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-[10px] font-bold uppercase tracking-wider bg-emerald-50 text-emerald-700 border border-emerald-200">
                      <span className="w-1.5 h-1.5 rounded-full bg-emerald-500" />
                      Active
                    </span>
                  </div>
                  <button onClick={() => disconnectMutation.mutate(b.id)}
                    className="text-rose-500/70 hover:text-rose-600 text-xs font-medium transition-colors px-3 py-1.5 rounded-lg hover:bg-rose-50 border border-transparent hover:border-rose-200">
                    Disconnect
                  </button>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* Available Brokers */}
      <div className="animate-fade-in-up delay-200">
        <div className="flex items-center gap-3 mb-5">
          <div className="w-1 h-6 rounded-full bg-gradient-to-b from-indigo-500 to-violet-500" />
          <h2 className="text-sm font-semibold text-slate-500 uppercase tracking-wider">Available Brokers</h2>
        </div>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-5">
          {(supported || ['ZERODHA', 'DHAN', 'FYERS']).map((name, i) => {
            const brokerName = typeof name === 'string' ? name : name.name;
            const meta = BROKER_META[brokerName] || { color: 'from-slate-500 to-slate-600', letter: brokerName[0], desc: 'Trading broker', glow: 'shadow-slate-500/20' };
            const isConnected = brokers?.some((b) => b.brokerName === brokerName);
            return (
              <div key={brokerName} className={`card-light overflow-hidden hover-lift hover-glow animate-fade-in-up delay-${Math.min((i+3)*100, 600)} group`}>
                <div className={`h-1.5 bg-gradient-to-r ${meta.color}`} />
                <div className="p-6">
                  <div className="flex items-center gap-4 mb-5">
                    <div className={`w-14 h-14 rounded-2xl bg-gradient-to-br ${meta.color} flex items-center justify-center text-white font-bold text-xl shadow-lg ${meta.glow} group-hover:scale-110 transition-transform duration-300`}>
                      {meta.letter}
                    </div>
                    <div>
                      <h3 className="font-bold text-slate-800 text-lg">{brokerName}</h3>
                      <p className="text-xs text-slate-400 mt-0.5">{meta.desc}</p>
                    </div>
                  </div>
                  {isConnected ? (
                    <div className="flex items-center gap-2 py-3 px-4 rounded-xl bg-emerald-50 text-emerald-700 text-sm font-medium border border-emerald-200">
                      <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                        <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                      </svg>
                      Connected
                    </div>
                  ) : (
                    <button onClick={() => connectBroker(brokerName)} disabled={connectingBroker === brokerName}
                      className={`btn-shimmer w-full py-3 rounded-xl bg-gradient-to-r ${meta.color} text-white text-sm font-medium transition-all shadow-lg ${meta.glow} hover:shadow-xl hover:scale-[1.02] disabled:opacity-50 disabled:cursor-not-allowed`}>
                      {connectingBroker === brokerName ? (
                        <span className="flex items-center justify-center gap-2">
                          <svg className="animate-spin w-4 h-4" fill="none" viewBox="0 0 24 24">
                            <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                            <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                          </svg>
                          Opening...
                        </span>
                      ) : 'Connect'}
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

function LoadingSkeleton() {
  return (
    <div className="space-y-4">
      <div className="skeleton w-80 h-10 mb-8" />
      <div className="grid grid-cols-1 md:grid-cols-3 gap-5">
        {[1,2,3].map(i => (
          <div key={i} className="card-light p-5">
            <div className="skeleton w-full h-40" />
          </div>
        ))}
      </div>
    </div>
  );
}
