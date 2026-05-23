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
    <div className="rounded-lg border border-gray-200 bg-white p-6">
      <h2 className="mb-6 text-lg font-bold">Execution Mode Control</h2>

      <div className="mb-6 grid grid-cols-2 gap-4">
        <div>
          <label className="block text-sm font-medium text-gray-700">Current Mode</label>
          <div
            className={cn(
              'mt-2 rounded-lg border-2 p-4 text-lg font-bold',
              modes.find((m) => m.value === currentMode)?.color || 'bg-gray-100'
            )}
          >
            {currentMode}
          </div>
          {lastSwitchTime && (
            <div className="mt-2 text-xs text-gray-600">
              <p>Last switched: {lastSwitchTime}</p>
              <p>By: {lastSwitchedBy}</p>
            </div>
          )}
        </div>

        <div>
          <label className="block text-sm font-medium text-gray-700">Target Mode</label>
          <select
            value={targetMode}
            onChange={(e: React.ChangeEvent<HTMLSelectElement>) => setTargetMode(e.target.value)}
            className="mt-2 w-full rounded border border-gray-300 px-3 py-2"
          >
            {modes.map((mode) => (
              <option key={mode.value} value={mode.value}>
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
