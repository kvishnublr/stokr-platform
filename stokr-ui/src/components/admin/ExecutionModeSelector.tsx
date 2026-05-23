import { useState } from 'react';
import { cn } from '../../lib/utils';

interface ExecutionModeOption {
  value: string;
  label: string;
  description: string;
  color: string;
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH';
}

export function ExecutionModeSelector() {
  const [currentMode, setCurrentMode] = useState('PAPER');
  const [targetMode, setTargetMode] = useState('PAPER');
  const [reason, setReason] = useState('');
  const [loading, setLoading] = useState(false);
  const [lastSwitchTime, setLastSwitchTime] = useState<string | null>(null);
  const [lastSwitchedBy, setLastSwitchedBy] = useState<string | null>(null);
  const [confirmDialog, setConfirmDialog] = useState(false);

  const modes: ExecutionModeOption[] = [
    {
      value: 'PAPER',
      label: 'Paper Trading',
      description: 'Simulated trading, no real funds',
      color: 'bg-blue-100',
      riskLevel: 'LOW',
    },
    {
      value: 'LIVE',
      label: 'Live Trading',
      description: 'Real broker connection, real funds',
      color: 'bg-red-100',
      riskLevel: 'HIGH',
    },
    {
      value: 'BOTH',
      label: 'Hybrid Mode',
      description: 'Paper + Live parallel execution',
      color: 'bg-yellow-100',
      riskLevel: 'MEDIUM',
    },
  ];

  const riskColors: Record<string, string> = {
    LOW: 'text-green-700 bg-green-50',
    MEDIUM: 'text-yellow-700 bg-yellow-50',
    HIGH: 'text-red-700 bg-red-50',
  };

  const handleSwitchMode = async () => {
    if (!reason.trim()) {
      alert('Please provide a reason for mode switch');
      return;
    }

    setLoading(true);
    try {
      const response = await fetch(`/api/admin/execution/mode/${targetMode}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ reason, requestedBy: 'ADMIN' }),
      });

      if (response.ok) {
        const data = await response.json();
        setCurrentMode(data.mode);
        setLastSwitchTime(new Date(data.lastSwitchTime).toLocaleString());
        setLastSwitchedBy(data.lastSwitchedBy);
        setConfirmDialog(false);
        setReason('');
      } else {
        alert('Failed to switch mode');
      }
    } catch (error) {
      console.error('Mode switch error:', error);
      alert('Error switching mode');
    } finally {
      setLoading(false);
    }
  };

  const targetModeConfig = modes.find((m) => m.value === targetMode);
  const isLiveModeSelected = targetMode === 'LIVE';

  return (
    <div className="rounded-2xl border border-slate-700/50 bg-slate-800/40 backdrop-blur-xl p-6 space-y-6 hover:border-blue-500/30 transition-all duration-300">
      <div className="space-y-2">
        <div className="flex items-center gap-2">
          <div className="w-1 h-6 bg-gradient-to-b from-blue-500 to-blue-600 rounded-full"></div>
          <h2 className="text-xl font-bold text-white">Execution Mode Control</h2>
        </div>
        <p className="text-xs text-slate-400">Switch between PAPER, LIVE, and BOTH (Hybrid) execution modes</p>
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div className="space-y-3">
          <label className="block text-xs font-semibold text-slate-300 uppercase tracking-wider">Current Mode</label>
          <div className="relative group">
            <div className="absolute -inset-1 bg-gradient-to-r from-blue-600 to-purple-600 rounded-xl blur opacity-20 group-hover:opacity-40 transition duration-300"></div>
            <div
              className="relative rounded-xl border border-slate-600 bg-slate-900/50 p-4 text-center backdrop-blur-sm"
            >
              <span className="text-2xl font-bold bg-gradient-to-r from-blue-400 to-purple-400 bg-clip-text text-transparent">
                {currentMode}
              </span>
            </div>
          </div>
          {lastSwitchTime && (
            <div className="text-xs text-slate-400 space-y-1 px-2">
              <p>Last: {lastSwitchTime}</p>
              <p>By: {lastSwitchedBy}</p>
            </div>
          )}
        </div>

        <div className="space-y-3">
          <label className="block text-xs font-semibold text-slate-300 uppercase tracking-wider">Target Mode</label>
          <select
            value={targetMode}
            onChange={(e: React.ChangeEvent<HTMLSelectElement>) => setTargetMode(e.target.value)}
            className="w-full rounded-xl border border-slate-600 bg-slate-900/50 px-4 py-2.5 text-white font-medium backdrop-blur-sm hover:border-blue-500/50 transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500/50"
          >
            {modes.map((mode) => (
              <option key={mode.value} value={mode.value} className="bg-slate-900">
                {mode.label}
              </option>
            ))}
          </select>
        </div>
      </div>

      {targetModeConfig && (
        <div className={cn('mb-6 rounded-lg p-4', riskColors[targetModeConfig.riskLevel])}>
          <strong>{targetModeConfig.label}</strong>
          <br />
          {targetModeConfig.description}
          <br />
          Risk Level: <strong>{targetModeConfig.riskLevel}</strong>
        </div>
      )}

      {isLiveModeSelected && (
        <div className="mb-6 rounded-lg border border-red-200 bg-red-50 p-4 text-red-700">
          ⚠️ <strong>WARNING</strong>: You are about to switch to LIVE mode. Real funds will be at risk.
          Ensure all safety checks are in place before proceeding.
        </div>
      )}

      <div className="mb-6">
        <label className="block text-sm font-medium text-gray-700">Reason for Switch</label>
        <input
          type="text"
          placeholder="Enter reason for execution mode change..."
          value={reason}
          onChange={(e: React.ChangeEvent<HTMLInputElement>) => setReason(e.target.value)}
          className="mt-2 w-full rounded border border-gray-300 px-3 py-2"
        />
      </div>

      <div className="flex gap-2">
        <button
          onClick={() => {
            if (isLiveModeSelected) {
              setConfirmDialog(true);
            } else {
              handleSwitchMode();
            }
          }}
          disabled={loading || currentMode === targetMode}
          className={cn(
            'rounded px-4 py-2 font-medium text-white disabled:opacity-50',
            isLiveModeSelected ? 'bg-red-600 hover:bg-red-700' : 'bg-blue-600 hover:bg-blue-700'
          )}
        >
          {loading ? 'Switching...' : 'Switch Mode'}
        </button>
        <button
          onClick={() => setReason('')}
          className="rounded border border-gray-300 px-4 py-2 hover:bg-gray-50"
        >
          Clear
        </button>
      </div>

      {confirmDialog && isLiveModeSelected && (
        <div className="mt-6 rounded-lg border border-red-400 bg-red-50 p-4">
          <p className="mb-2 font-bold text-red-700">Confirm LIVE Mode Switch</p>
          <p className="mb-4 text-red-700">
            This action will enable real broker connections and use real funds.
          </p>
          <div className="flex gap-2">
            <button
              onClick={handleSwitchMode}
              disabled={loading}
              className="rounded bg-red-600 px-3 py-2 text-sm text-white hover:bg-red-700 disabled:opacity-50"
            >
              {loading ? 'Processing...' : 'I understand, proceed'}
            </button>
            <button
              onClick={() => setConfirmDialog(false)}
              disabled={loading}
              className="rounded border border-gray-300 px-3 py-2 text-sm hover:bg-gray-50 disabled:opacity-50"
            >
              Cancel
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
