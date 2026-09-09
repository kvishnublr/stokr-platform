import React, { useState } from 'react';
import client from '../api/client';
import { useGlobalExecutionBroker } from '../context/ExecutionBrokerContext';

export default function GlobalExecutionBar({ showToast, title, subtitle }) {
  const { executionBroker, changeExecutionBroker, mofslModalOpen, setMofslModalOpen } = useGlobalExecutionBroker();
  const [isTestingBroker, setIsTestingBroker] = useState(false);
  
  const [mofslForm, setMofslForm] = useState({ clientCode: '', password: '', totpSecret: '', apiKey: '', apiSecret: '', panNumber: '' });
  const [mofslSaving, setMofslSaving] = useState(false);
  const [mofslError, setMofslError] = useState(null);

  const handleTradeModeChange = (mode) => {
    changeExecutionBroker(mode);
    if (mode === 'PAPER') {
      showToast('Switched to Paper Trading mode.', 'info');
    } else {
      showToast('Switched to Live Trading mode. Ensure your broker is connected.', 'warning');
    }
  };

  const handleLiveBrokerChange = (broker) => {
    changeExecutionBroker(broker);
  };

  const handleMofslConnect = async () => {
    if (!mofslForm.clientCode || !mofslForm.password || !mofslForm.totpSecret || !mofslForm.apiKey || !mofslForm.apiSecret || !mofslForm.panNumber) {
      setMofslError('All fields including PAN Number are required for TOTP authentication.');
      return;
    }
    setMofslSaving(true);
    setMofslError(null);
    try {
        await client.post('/brokers/motilaloswal/connect', mofslForm);
        if (showToast) showToast('Motilal Oswal connected successfully!', 'success');
        setMofslModalOpen(false);
    } catch(e) {
        setMofslError(e.response?.data?.error || e.message || 'Connection failed');
    } finally {
        setMofslSaving(false);
    }
  };

  const testBrokerConnection = async () => {
    setIsTestingBroker(true);
    try {
      const res = await client.post('/brokers/test-connection', { broker: executionBroker });
      if (res.data?.ok) {
        if (showToast) showToast(res.data.message, 'success');
      } else {
        const msg = res.data?.message || 'Broker test failed';
        if ((msg.includes('No active') || msg.includes('access token is missing')) && executionBroker === 'MOTILALOSWAL') {
            setMofslModalOpen(true);
        } else {
            if (showToast) showToast(msg, 'error');
        }
      }
    } catch (e) {
      if (showToast) showToast('Broker connection test error: ' + e.message, 'error');
    } finally {
      setIsTestingBroker(false);
    }
  };

  return (
    <>
      {mofslModalOpen && (
        <div className="fixed inset-0 z-[60] bg-slate-900/60 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-white rounded-2xl shadow-2xl w-full max-w-md border border-slate-200 overflow-hidden animate-fade-in-up">
            <div className="bg-gradient-to-r from-violet-600 to-indigo-600 p-5 text-white flex justify-between items-center">
              <div>
                <h3 className="font-black text-lg tracking-tight">Connect Motilal Oswal</h3>
                <p className="text-violet-200 text-xs mt-0.5">TOTP-based API Authentication</p>
              </div>
              <button onClick={() => setMofslModalOpen(false)} className="text-white/60 hover:text-white text-xl">✕</button>
            </div>
            
            <div className="p-6 space-y-5">
              <div className="space-y-4">
                <div>
                  <label className="text-xs font-bold text-slate-600 uppercase tracking-wide block mb-1.5">Client Code</label>
                  <input type="text" value={mofslForm.clientCode} onChange={e => setMofslForm({...mofslForm, clientCode: e.target.value})} 
                    className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-3 text-sm font-bold outline-none focus:ring-2 focus:ring-violet-500 focus:bg-white transition-all" placeholder="MOFSL Client ID" />
                </div>
                <div>
                  <label className="text-xs font-bold text-slate-600 uppercase tracking-wide block mb-1.5">Password</label>
                  <input type="password" value={mofslForm.password} onChange={e => setMofslForm({...mofslForm, password: e.target.value})} 
                    className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-3 text-sm font-bold outline-none focus:ring-2 focus:ring-violet-500 focus:bg-white transition-all" placeholder="Login Password" />
                </div>
                <div>
                  <label className="text-xs font-bold text-slate-600 uppercase tracking-wide block mb-1.5">TOTP Secret (Base32)</label>
                  <input type="text" value={mofslForm.totpSecret} onChange={e => setMofslForm({...mofslForm, totpSecret: e.target.value})} 
                    className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-3 text-sm font-bold outline-none focus:ring-2 focus:ring-violet-500 focus:bg-white transition-all" placeholder="Paste TOTP Secret Key" />
                </div>
                <div>
                  <label className="text-xs font-bold text-slate-600 uppercase tracking-wide block mb-1.5">API Key</label>
                  <input type="text" value={mofslForm.apiKey} onChange={e => setMofslForm({...mofslForm, apiKey: e.target.value})} 
                    className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-3 text-sm font-bold outline-none focus:ring-2 focus:ring-violet-500 focus:bg-white transition-all" placeholder="Your MOFSL API Key" />
                </div>
                <div>
                  <label className="text-xs font-bold text-slate-600 uppercase tracking-wide block mb-1.5">API Secret</label>
                  <input type="text" value={mofslForm.apiSecret} onChange={e => setMofslForm({...mofslForm, apiSecret: e.target.value})} 
                    className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-3 text-sm font-bold outline-none focus:ring-2 focus:ring-violet-500 focus:bg-white transition-all" placeholder="Your MOFSL API Secret" />
                </div>
                <div>
                  <label className="text-xs font-bold text-slate-600 uppercase tracking-wide block mb-1.5">PAN NUMBER (Required)</label>
                  <input type="text" value={mofslForm.panNumber} onChange={e => setMofslForm({...mofslForm, panNumber: e.target.value})} 
                    className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-3 text-sm font-bold outline-none focus:ring-2 focus:ring-violet-500 focus:bg-white transition-all uppercase" placeholder="Your PAN Number" />
                </div>
              </div>
              
              {mofslError && (
                <div className="bg-red-50 text-red-600 border border-red-200 rounded-xl p-3 text-xs font-bold flex items-center gap-2">
                  <span className="text-base">⚠️</span> {mofslError}
                </div>
              )}
              
              <button onClick={handleMofslConnect} disabled={mofslSaving} className="w-full bg-violet-600 hover:bg-violet-500 text-white rounded-xl py-3.5 font-black text-sm uppercase tracking-wide shadow-lg shadow-violet-500/30 transition-all disabled:opacity-50">
                {mofslSaving ? 'Connecting...' : 'Secure Connect'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Top Header Card */}
      <div className="bg-gradient-to-r from-slate-900 via-indigo-950 to-slate-900 text-white rounded-2xl p-4 md:p-5 shadow-xl border border-slate-800 flex flex-wrap items-center justify-between gap-4 mb-5">
        <div className="space-y-1">
          <div className="flex items-center gap-3">
            <div className="p-2 bg-indigo-600/30 rounded-xl border border-indigo-400/30">
              <span className="text-xl">⚡</span>
            </div>
            <div>
              <h1 className="text-xl font-black tracking-tight text-white">{title || 'Global Execution Control'}</h1>
              {subtitle && <p className="text-xs text-indigo-200/80 font-medium">{subtitle}</p>}
            </div>
          </div>
        </div>

        {/* Live Router & Execution Control */}
        <div className="flex items-center gap-2.5 flex-wrap">
          <div className="bg-slate-800/80 backdrop-blur-md px-3 py-1.5 rounded-xl border border-slate-700/80 flex items-center gap-2 text-xs">
            <span className="w-2.5 h-2.5 rounded-full bg-emerald-400 animate-pulse" />
            <span className="text-slate-300 font-medium">Data Feed:</span>
            <span className="font-bold text-white">Zerodha Kite Connect</span>
          </div>

          {/* Modern Trade Mode Toggle */}
          <div className="flex bg-slate-900/60 p-1 rounded-xl border border-slate-700/60 backdrop-blur-sm shadow-inner">
            <button 
              onClick={() => handleTradeModeChange('PAPER')}
              className={`px-4 py-1.5 rounded-lg text-[11px] uppercase tracking-wide font-black transition-all duration-300 ${executionBroker === 'PAPER' ? 'bg-indigo-600 text-white shadow-md' : 'text-slate-400 hover:text-white hover:bg-slate-800'}`}
            >
              📝 Paper Mode
            </button>
            <button 
              onClick={() => handleTradeModeChange('LIVE')}
              className={`px-4 py-1.5 rounded-lg text-[11px] uppercase tracking-wide font-black transition-all duration-300 flex items-center gap-1.5 ${executionBroker !== 'PAPER' ? 'bg-red-600 text-white shadow-[0_0_15px_rgba(220,38,38,0.4)] shadow-red-500/20' : 'text-slate-400 hover:text-white hover:bg-slate-800'}`}
            >
              {executionBroker !== 'PAPER' && <span className="w-1.5 h-1.5 rounded-full bg-white animate-pulse" />}
              Live Mode
            </button>
          </div>

          {/* Live Broker Selection */}
          {executionBroker !== 'PAPER' && (
            <div className="bg-red-950/40 backdrop-blur-md px-3 py-1.5 rounded-xl border border-red-500/30 flex items-center gap-2 text-xs transition-all shadow-[0_0_10px_rgba(239,68,68,0.1)]">
              <span className="text-red-300/80 font-semibold uppercase text-[10px] tracking-wider">Broker:</span>
              <select
                value={executionBroker}
                onChange={(e) => handleLiveBrokerChange(e.target.value)}
                className="bg-transparent text-red-400 font-bold outline-none text-xs cursor-pointer focus:ring-0 appearance-none pr-4"
              >
                <option value="ZERODHA">Zerodha Kite Connect</option>
                <option value="NAVIA">Navia Markets</option>
                <option value="MOTILALOSWAL">Motilal Oswal</option>
                <option value="ICICIDIRECT">ICICI Direct Breeze</option>
                <option value="DHAN">DhanHQ</option>
                <option value="FYERS">Fyers API</option>
              </select>
            </div>
          )}

          <button
            onClick={testBrokerConnection}
            disabled={isTestingBroker}
            className="px-3 py-1.5 bg-indigo-600 hover:bg-indigo-500 text-white font-bold rounded-xl text-xs transition shadow-lg disabled:opacity-50"
          >
            {isTestingBroker ? 'Testing...' : '⚡ Test Connection'}
          </button>
        </div>
      </div>
    </>
  );
}
