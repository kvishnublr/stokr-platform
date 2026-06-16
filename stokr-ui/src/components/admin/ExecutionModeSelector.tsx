import { useCallback, useEffect, useState } from 'react';
import { toast } from 'sonner';
import { api, parseAxiosMessage } from '../../api/client';
import { fmtDateTime } from '../../lib/dateUtils';
import { cn } from '../../lib/utils';
import { useSessionStore } from '../../state/session';

interface ExecutionModeOption {
  value: string;
  label: string;
  description: string;
  color: string;
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH';
}

type ExecutionModePayload = {
  mode?: string;
  lastSwitchTime?: string;
  lastSwitchedBy?: string;
};

export function ExecutionModeSelector() {
  const username = useSessionStore((s) => s.username);
  const [currentMode, setCurrentMode] = useState('PAPER');
  const [targetMode, setTargetMode] = useState('PAPER');
  const [reason, setReason] = useState('');
  const [loading, setLoading] = useState(false);
  const [initialLoading, setInitialLoading] = useState(true);
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

  const applyModePayload = useCallback((data: ExecutionModePayload | undefined) => {
    if (!data?.mode) return;
    setCurrentMode(String(data.mode));
    setTargetMode(String(data.mode));
    if (data.lastSwitchTime) {
      setLastSwitchTime(fmtDateTime(data.lastSwitchTime));
    }
    if (data.lastSwitchedBy) {
      setLastSwitchedBy(String(data.lastSwitchedBy));
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await api.get<ExecutionModePayload>('/api/admin/execution/mode');
        if (!cancelled) applyModePayload(res.data);
      } catch (error) {
        if (!cancelled) {
          console.error('Failed to load execution mode:', error);
        }
      } finally {
        if (!cancelled) setInitialLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [applyModePayload]);

  const handleSwitchMode = async () => {
    if (!reason.trim()) {
      toast.error('Please provide a reason for the mode switch');
      return;
    }

    setLoading(true);
    try {
      const res = await api.post<ExecutionModePayload>(
        `/api/admin/execution/mode/${targetMode}`,
        {
          reason: reason.trim(),
          requestedBy: username || 'ADMIN',
        },
      );
      applyModePayload(res.data);
      setConfirmDialog(false);
      setReason('');
      toast.success(`Execution mode switched to ${res.data?.mode ?? targetMode}`);
    } catch (error) {
      console.error('Mode switch error:', error);
      toast.error(parseAxiosMessage(error));
    } finally {
      setLoading(false);
    }
  };

  const targetModeConfig = modes.find((m) => m.value === targetMode);
  const isLiveModeSelected = targetMode === 'LIVE';

  return (
    <div className="rounded-2xl border border-slate-200 bg-white p-6 space-y-6 hover:border-blue-300 transition-all duration-300 shadow-sm animate-slide-up">
      <div className="space-y-2">
        <div className="flex items-center gap-2">
          <div className="w-1 h-6 bg-gradient-to-b from-blue-500 to-blue-600 rounded-full"></div>
          <h2 className="text-xl font-bold text-slate-900">Execution Mode</h2>
        </div>
        <p className="text-xs text-slate-600">Switch between PAPER, LIVE, and BOTH execution modes</p>
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div className="space-y-3">
          <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider">Current</label>
          <div className="relative group">
            <div className="absolute -inset-1 bg-gradient-to-r from-blue-500 to-purple-500 rounded-xl blur opacity-0 group-hover:opacity-20 transition duration-300"></div>
            <div className="relative rounded-xl border border-blue-200 bg-blue-50 p-4 text-center">
              <span className="text-2xl font-bold text-blue-900">
                {initialLoading ? '…' : currentMode}
              </span>
            </div>
          </div>
          {lastSwitchTime && (
            <div className="text-xs text-slate-500 space-y-1 px-2">
              <p>Last: {lastSwitchTime}</p>
              <p>By: {lastSwitchedBy}</p>
            </div>
          )}
        </div>

        <div className="space-y-3">
          <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider">Target</label>
          <select
            value={targetMode}
            onChange={(e: React.ChangeEvent<HTMLSelectElement>) => setTargetMode(e.target.value)}
            disabled={loading || initialLoading}
            className="w-full rounded-xl border border-slate-200 bg-slate-50 px-4 py-2.5 text-slate-900 font-medium hover:border-blue-300 transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500/30 disabled:opacity-60"
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
        <div className={cn('mb-4 rounded-lg p-3 text-sm', riskColors[targetModeConfig.riskLevel])}>
          <strong>{targetModeConfig.label}</strong> · {targetModeConfig.description}
        </div>
      )}

      {isLiveModeSelected && (
        <div className="mb-4 rounded-lg border border-red-200 bg-red-50 p-3 text-red-700 text-sm">
          ⚠️ <strong>WARNING:</strong> Switching to LIVE mode will use real funds. Ensure all safety checks are in place.
        </div>
      )}

      <div className="space-y-3">
        <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider">Reason</label>
        <input
          type="text"
          placeholder="Why are you switching modes?"
          value={reason}
          onChange={(e: React.ChangeEvent<HTMLInputElement>) => setReason(e.target.value)}
          disabled={loading}
          className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 placeholder-slate-400 hover:border-blue-200 focus:outline-none focus:ring-2 focus:ring-blue-500/30 transition-colors disabled:opacity-60"
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
          disabled={loading || initialLoading || currentMode === targetMode}
          className={cn(
            'flex-1 rounded-lg px-4 py-2 font-medium text-white transition-all disabled:opacity-50 disabled:cursor-not-allowed',
            isLiveModeSelected ? 'bg-red-600 hover:bg-red-700' : 'bg-blue-600 hover:bg-blue-700'
          )}
        >
          {loading ? 'Switching...' : 'Switch Mode'}
        </button>
        <button
          onClick={() => setReason('')}
          disabled={loading}
          className="rounded-lg border border-slate-200 px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 transition-colors disabled:opacity-60"
        >
          Clear
        </button>
      </div>

      {confirmDialog && isLiveModeSelected && (
        <div className="mt-4 rounded-lg border border-red-200 bg-red-50 p-4 animate-scale-in">
          <p className="mb-2 font-bold text-red-700">Confirm LIVE Mode Switch</p>
          <p className="mb-4 text-sm text-red-600">
            This enables real broker connections with real funds at risk.
          </p>
          <div className="flex gap-2">
            <button
              onClick={handleSwitchMode}
              disabled={loading}
              className="flex-1 rounded-lg bg-red-600 px-3 py-2 text-sm font-medium text-white hover:bg-red-700 transition-colors disabled:opacity-50"
            >
              {loading ? 'Processing...' : 'I understand, proceed'}
            </button>
            <button
              onClick={() => setConfirmDialog(false)}
              disabled={loading}
              className="rounded-lg border border-red-200 px-3 py-2 text-sm font-medium text-red-700 hover:bg-red-100 transition-colors disabled:opacity-50"
            >
              Cancel
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
