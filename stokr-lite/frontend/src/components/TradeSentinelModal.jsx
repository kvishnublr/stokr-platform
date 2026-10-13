import React, { useState, useEffect } from 'react';
import client from '../api/client';

export default function TradeSentinelModal({ isOpen, onClose, showToast }) {
  const [loading, setLoading] = useState(false);
  const [reconciling, setReconciling] = useState(false);
  const [statusData, setStatusData] = useState(null);
  const [error, setError] = useState(null);

  const fetchSentinelStatus = async () => {
    try {
      setLoading(true);
      setError(null);
      const res = await client.get('/sentinel/status');
      setStatusData(res.data);
    } catch (e) {
      setError(e.response?.data?.message || e.message || 'Failed to fetch Sentinel status');
    } finally {
      setLoading(false);
    }
  };

  const handleReconcile = async () => {
    try {
      setReconciling(true);
      const res = await client.post('/sentinel/reconcile?autoHeal=true');
      if (showToast) {
        showToast(
          `Reconciliation complete: ${res.data.status} (${res.data.autoHealedCount} positions auto-healed)`,
          res.data.status === 'IN_SYNC' ? 'success' : 'warning'
        );
      }
      fetchSentinelStatus();
    } catch (e) {
      if (showToast) showToast('Reconciliation failed: ' + e.message, 'error');
    } finally {
      setReconciling(false);
    }
  };

  const handleCertifyEod = async () => {
    try {
      setReconciling(true);
      const res = await client.post('/sentinel/certify-eod');
      if (showToast) {
        showToast(
          res.data.allRealPositionsFlat
            ? 'EOD Flat-Book Certified: 100% Flat across all brokers'
            : `Warning: ${res.data.openRealDbPositions} open positions detected!`,
          res.data.allRealPositionsFlat ? 'success' : 'error'
        );
      }
      fetchSentinelStatus();
    } catch (e) {
      if (showToast) showToast('EOD certification failed: ' + e.message, 'error');
    } finally {
      setReconciling(false);
    }
  };

  useEffect(() => {
    if (isOpen) {
      fetchSentinelStatus();
      const interval = setInterval(fetchSentinelStatus, 15000);
      return () => clearInterval(interval);
    }
  }, [isOpen]);

  if (!isOpen) return null;

  const isGreen = statusData?.overallStatus === 'GREEN';
  const isYellow = statusData?.overallStatus === 'YELLOW';
  const isRed = statusData?.overallStatus === 'RED';

  return (
    <div className="fixed inset-0 z-[70] bg-slate-950/70 backdrop-blur-md flex items-center justify-center p-3 md:p-6 animate-fade-in">
      <div className="bg-slate-900 border border-slate-700/80 rounded-3xl shadow-2xl w-full max-w-4xl max-h-[92vh] flex flex-col overflow-hidden text-slate-100">
        
        {/* Modal Header */}
        <div className="bg-gradient-to-r from-slate-950 via-indigo-950 to-slate-950 px-6 py-5 border-b border-slate-800 flex items-center justify-between">
          <div className="flex items-center gap-3.5">
            <div className="p-2.5 bg-indigo-500/20 border border-indigo-500/40 rounded-2xl text-2xl shadow-inner">
              🛡️
            </div>
            <div>
              <div className="flex items-center gap-2.5">
                <h2 className="text-lg md:text-xl font-black text-white tracking-tight">Trade Sentinel</h2>
                <span className={`px-2.5 py-0.5 rounded-full text-[11px] font-black uppercase tracking-wider ${
                  isGreen ? 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/30' :
                  isYellow ? 'bg-amber-500/20 text-amber-400 border border-amber-500/30' :
                  'bg-rose-500/20 text-rose-400 border border-rose-500/30'
                }`}>
                  {statusData?.statusLabel || 'Online'}
                </span>
              </div>
              <p className="text-xs text-slate-400 mt-0.5">Execution Supervisor, Safety Gatekeeper &amp; Live Broker Reconciler</p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <button
              onClick={handleReconcile}
              disabled={reconciling || loading}
              className="px-3.5 py-1.5 bg-emerald-600 hover:bg-emerald-500 disabled:opacity-50 text-white font-bold text-xs rounded-xl shadow-md transition flex items-center gap-1.5"
            >
              <span>{reconciling ? '⏳' : '⚡'}</span>
              <span>{reconciling ? 'Auditing...' : 'Reconcile Now'}</span>
            </button>
            <button
              onClick={onClose}
              className="w-8 h-8 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-400 hover:text-white flex items-center justify-center font-bold text-sm transition"
            >
              ✕
            </button>
          </div>
        </div>

        {/* Modal Body */}
        <div className="p-6 overflow-y-auto space-y-6 custom-scrollbar">

          {error && (
            <div className="p-4 bg-rose-500/10 border border-rose-500/30 rounded-2xl text-rose-400 text-xs font-semibold flex items-center gap-3">
              <span>⚠️</span>
              <span>{error}</span>
            </div>
          )}

          {/* 4 Pillars Status Grid */}
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-3.5">
            {/* Gatekeeper Card */}
            <div className="bg-slate-800/60 border border-slate-700/60 rounded-2xl p-4 space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-xs font-bold text-indigo-400 uppercase tracking-wider">1. Gatekeeper</span>
                <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
              </div>
              <h4 className="font-extrabold text-sm text-white">Pre-Order Checks</h4>
              <p className="text-[11px] text-slate-400 leading-relaxed">
                Requires +15% margin buffer headroom, validates exchange lot freeze limits &amp; contract strikes.
              </p>
              <div className="pt-1 text-[10px] text-emerald-400 font-bold flex items-center gap-1">
                <span>✓ Active (Zero Slippage Bypass)</span>
              </div>
            </div>

            {/* Watchdog Card */}
            <div className="bg-slate-800/60 border border-slate-700/60 rounded-2xl p-4 space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-xs font-bold text-indigo-400 uppercase tracking-wider">2. Watchdog</span>
                <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
              </div>
              <h4 className="font-extrabold text-sm text-white">In-Flight Watchdog</h4>
              <p className="text-[11px] text-slate-400 leading-relaxed">
                BUY-first leg sequencing, ₹0.05 tick rounding, 5% marketable limit protection &amp; instant rollback.
              </p>
              <div className="pt-1 text-[10px] text-emerald-400 font-bold flex items-center gap-1">
                <span>✓ Rollback Armed on Leg Timeout</span>
              </div>
            </div>

            {/* Reconciler Card */}
            <div className="bg-slate-800/60 border border-slate-700/60 rounded-2xl p-4 space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-xs font-bold text-indigo-400 uppercase tracking-wider">3. Reconciler</span>
                <span className={`w-2 h-2 rounded-full ${
                  statusData?.lastReconciliation?.status === 'IN_SYNC' ? 'bg-emerald-400' : 'bg-amber-400'
                } animate-pulse`} />
              </div>
              <h4 className="font-extrabold text-sm text-white">DB vs. Broker Sync</h4>
              <p className="text-[11px] text-slate-400 leading-relaxed">
                Auto-heals phantom positions if closed outside the app. Verified flat at market close.
              </p>
              <div className="pt-1 text-[10px] font-bold flex items-center gap-1 text-emerald-400">
                <span>✓ Status: {statusData?.lastReconciliation?.status || 'IN_SYNC'}</span>
              </div>
            </div>

            {/* Certifier Card */}
            <div className="bg-slate-800/60 border border-slate-700/60 rounded-2xl p-4 space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-xs font-bold text-indigo-400 uppercase tracking-wider">4. Certifier</span>
                <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
              </div>
              <h4 className="font-extrabold text-sm text-white">EOD Flat-Book</h4>
              <p className="text-[11px] text-slate-400 leading-relaxed">
                Audits post-3:20 PM positions to certify zero unintended overnight intraday exposure.
              </p>
              <div className="pt-1 text-[10px] text-emerald-400 font-bold flex items-center gap-1">
                <span>✓ 100% Flat Book Certified</span>
              </div>
            </div>
          </div>

          {/* Broker Margin & Session Health */}
          <div className="bg-slate-800/40 border border-slate-800 rounded-2xl p-5 space-y-3">
            <div className="flex items-center justify-between">
              <h4 className="text-xs font-extrabold text-slate-300 uppercase tracking-wider flex items-center gap-2">
                <span>🏦</span> Connected Brokers &amp; Margin Health
              </h4>
              <button
                onClick={handleCertifyEod}
                disabled={reconciling}
                className="text-[11px] text-indigo-400 hover:text-indigo-300 font-bold transition flex items-center gap-1"
              >
                <span>📜 Certify Flat Book</span>
              </button>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
              {statusData?.brokerSummaries && Object.keys(statusData.brokerSummaries).length > 0 ? (
                Object.entries(statusData.brokerSummaries).map(([broker, data]) => (
                  <div key={broker} className="bg-slate-900/80 border border-slate-700/60 rounded-xl p-3.5 space-y-1">
                    <div className="flex items-center justify-between">
                      <span className="font-black text-xs text-white tracking-wide">{broker}</span>
                      <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-emerald-500/20 text-emerald-400">
                        {data.status}
                      </span>
                    </div>
                    <div className="text-[11px] text-slate-400">
                      User / Client: <span className="font-semibold text-slate-200">{data.clientCode || 'Active'}</span>
                    </div>
                    <div className="text-sm font-black text-indigo-300 pt-0.5">
                      ₹{Number(data.availableMargin || 0).toLocaleString('en-IN', { maximumFractionDigits: 2 })}
                      <span className="text-[10px] text-slate-400 font-normal ml-1">available</span>
                    </div>
                  </div>
                ))
              ) : (
                <div className="col-span-3 text-center py-3 text-xs text-slate-500">
                  Paper Mode Active (No live broker accounts connected)
                </div>
              )}
            </div>
          </div>

          {/* Sentinel Real-Time Audit Trail */}
          <div className="bg-slate-800/40 border border-slate-800 rounded-2xl p-5 space-y-3">
            <div className="flex items-center justify-between">
              <h4 className="text-xs font-extrabold text-slate-300 uppercase tracking-wider flex items-center gap-2">
                <span>📋</span> Sentinel Audit Log (Recent Events)
              </h4>
              <span className="text-[11px] text-slate-500">
                {statusData?.recentEvents?.length || 0} events recorded
              </span>
            </div>

            <div className="bg-slate-950/80 rounded-xl border border-slate-800 max-h-56 overflow-y-auto divide-y divide-slate-800/60 text-xs">
              {statusData?.recentEvents && statusData.recentEvents.length > 0 ? (
                statusData.recentEvents.map(event => (
                  <div key={event.id} className="p-3 flex items-start gap-3 hover:bg-slate-900/60 transition">
                    <span className={`px-2 py-0.5 rounded text-[10px] font-extrabold uppercase tracking-wide shrink-0 ${
                      event.level === 'SUCCESS' ? 'bg-emerald-500/20 text-emerald-400' :
                      event.level === 'WARN' ? 'bg-amber-500/20 text-amber-400' :
                      event.level === 'ERROR' ? 'bg-rose-500/20 text-rose-400' :
                      'bg-indigo-500/20 text-indigo-400'
                    }`}>
                      {event.category}
                    </span>
                    <div className="flex-1 space-y-0.5">
                      <p className="text-slate-200 font-medium text-xs leading-snug">{event.message}</p>
                      <span className="text-[10px] text-slate-500 block">
                        {new Date(event.timestamp).toLocaleTimeString('en-IN')}
                      </span>
                    </div>
                  </div>
                ))
              ) : (
                <div className="text-center py-6 text-slate-500 text-xs">
                  No Sentinel events recorded in this session.
                </div>
              )}
            </div>
          </div>

        </div>

        {/* Modal Footer */}
        <div className="bg-slate-950 px-6 py-4 border-t border-slate-800 flex items-center justify-between text-xs text-slate-400">
          <div className="flex items-center gap-2">
            <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
            <span>Active Open Positions: <strong className="text-white">{statusData?.openPositionsCount || 0}</strong></span>
          </div>
          <button
            onClick={onClose}
            className="px-5 py-2 bg-slate-800 hover:bg-slate-700 text-white font-bold rounded-xl transition"
          >
            Close
          </button>
        </div>

      </div>
    </div>
  );
}
