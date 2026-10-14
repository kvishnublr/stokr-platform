import React, { useEffect } from 'react';
import TradeSentinelPanel from './TradeSentinelPanel';

export default function TradeSentinelModal({ isOpen, onClose, showToast }) {
  useEffect(() => {
    const handleKeyDown = (e) => {
      if (e.key === 'Escape') onClose();
    };
    if (isOpen) {
      window.addEventListener('keydown', handleKeyDown);
      return () => window.removeEventListener('keydown', handleKeyDown);
    }
  }, [isOpen]);

  if (!isOpen) return null;

  return (
    <div
      className="fixed inset-0 z-[100] bg-slate-900/60 backdrop-blur-sm flex items-center justify-center p-3 sm:p-5"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="bg-white rounded-3xl shadow-2xl w-full max-w-6xl max-h-[92vh] flex flex-col overflow-hidden text-slate-800 border border-slate-200">
        
        {/* Modal Top Bar */}
        <div className="bg-slate-100/80 px-5 py-2.5 border-b border-slate-200 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span className="text-base">🛡️</span>
            <span className="text-xs font-black uppercase tracking-wider text-slate-800">
              Trade Sentinel &amp; Reconciliation Audit
            </span>
          </div>
          <button
            onClick={onClose}
            className="w-7 h-7 rounded-lg bg-slate-200 hover:bg-slate-300 text-slate-700 flex items-center justify-center font-black text-xs transition cursor-pointer"
          >
            ✕
          </button>
        </div>

        {/* Modal Content */}
        <div className="overflow-y-auto flex-1 custom-scrollbar">
          <TradeSentinelPanel showToast={showToast} inline={true} />
        </div>

        {/* Modal Bottom Bar */}
        <div className="bg-slate-50 px-5 py-3 border-t border-slate-200 flex items-center justify-end">
          <button
            onClick={onClose}
            className="px-4 py-1.5 bg-slate-800 hover:bg-slate-700 text-white font-bold text-xs rounded-xl transition cursor-pointer shadow-xs"
          >
            Close
          </button>
        </div>

      </div>
    </div>
  );
}
