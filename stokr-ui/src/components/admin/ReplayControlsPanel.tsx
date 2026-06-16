import React, { useState, useEffect } from 'react';
import { fmtDateTime } from '../../lib/dateUtils';
import { cn } from '../../lib/utils';

interface ReplayStatus {
  status: 'RUNNING' | 'PAUSED' | 'STOPPED';
  currentTime: string;
  progress: number;
  speed: number;
}

export function ReplayControlsPanel() {
  const [symbol, setSymbol] = useState('SBIN');
  const [startDate, setStartDate] = useState('2025-01-01');
  const [endDate, setEndDate] = useState('2025-12-31');
  const [speed, setSpeed] = useState(1.0);
  const [replayStatus, setReplayStatus] = useState<ReplayStatus | null>(null);
  const [loading, setLoading] = useState(false);

  const speedOptions = [
    { label: '0.5x (slow)', value: 0.5 },
    { label: '1x (normal)', value: 1.0 },
    { label: '2x (fast)', value: 2.0 },
    { label: '5x (very fast)', value: 5.0 },
    { label: '10x (ultra fast)', value: 10.0 },
  ];

  useEffect(() => {
    const pollReplayStatus = setInterval(async () => {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 1000);

        const response = await fetch('/api/admin/execution/replay/status', {
          signal: controller.signal
        });
        clearTimeout(timeoutId);

        if (response.ok) {
          const data = await response.json();
          setReplayStatus(data);
        }
      } catch (error) {
        // Silently fail - API not available yet
        console.debug('Replay status API not available');
      }
    }, 1000);

    return () => clearInterval(pollReplayStatus);
  }, []);

  const handleStartReplay = async () => {
    setLoading(true);
    try {
      const response = await fetch('/api/admin/execution/replay/start', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          symbol,
          startTime: new Date(startDate).toISOString(),
          endTime: new Date(endDate).toISOString(),
          speed,
        }),
      });

      if (response.ok) {
        const data = await response.json();
        console.log('Replay started:', data);
      } else {
        alert('Failed to start replay');
      }
    } catch (error) {
      console.error('Start replay error:', error);
      alert('Error starting replay');
    } finally {
      setLoading(false);
    }
  };

  const handlePauseReplay = async () => {
    try {
      const response = await fetch('/api/admin/execution/replay/pause', {
        method: 'POST',
      });
      if (!response.ok) alert('Failed to pause replay');
    } catch (error) {
      console.error('Pause replay error:', error);
    }
  };

  const handleResumeReplay = async () => {
    try {
      const response = await fetch('/api/admin/execution/replay/resume', {
        method: 'POST',
      });
      if (!response.ok) alert('Failed to resume replay');
    } catch (error) {
      console.error('Resume replay error:', error);
    }
  };

  const handleStopReplay = async () => {
    try {
      const response = await fetch('/api/admin/execution/replay/stop', {
        method: 'POST',
      });
      if (!response.ok) alert('Failed to stop replay');
    } catch (error) {
      console.error('Stop replay error:', error);
    }
  };

  const statusColor: Record<string, string> = {
    RUNNING: 'bg-green-100 text-green-800',
    PAUSED: 'bg-yellow-100 text-yellow-800',
    STOPPED: 'bg-gray-100 text-gray-800',
  };

  return (
    <div className="rounded-lg border border-gray-200 bg-white p-6">
      <div className="mb-6">
        <h2 className="text-lg font-bold">Replay Controls</h2>
      </div>
      <div className="space-y-6">
        {/* Setup Section */}
        <div className="space-y-4">
          <h3 className="font-semibold text-sm">Setup Replay</h3>

          <div className="grid grid-cols-3 gap-4">
            <div className="space-y-1">
              <label className="block text-xs font-medium">Symbol</label>
              <input
                type="text"
                value={symbol}
                onChange={(e: React.ChangeEvent<HTMLInputElement>) => setSymbol(e.target.value.toUpperCase())}
                placeholder="e.g., SBIN"
                disabled={replayStatus?.status === 'RUNNING'}
                className="mt-1 w-full rounded border border-gray-300 px-3 py-2"
              />
            </div>

            <div className="space-y-1">
              <label className="block text-xs font-medium">Start Date</label>
              <input
                type="date"
                value={startDate}
                onChange={(e: React.ChangeEvent<HTMLInputElement>) => setStartDate(e.target.value)}
                disabled={replayStatus?.status === 'RUNNING'}
                className="mt-1 w-full rounded border border-gray-300 px-3 py-2"
              />
            </div>

            <div className="space-y-1">
              <label className="block text-xs font-medium">End Date</label>
              <input
                type="date"
                value={endDate}
                onChange={(e: React.ChangeEvent<HTMLInputElement>) => setEndDate(e.target.value)}
                disabled={replayStatus?.status === 'RUNNING'}
                className="mt-1 w-full rounded border border-gray-300 px-3 py-2"
              />
            </div>
          </div>

          <div className="space-y-2">
            <label className="block text-xs font-medium">Replay Speed</label>
            <div className="flex items-center gap-4">
              <input
                type="range"
                min={0.5}
                max={10.0}
                step={0.5}
                value={speed}
                onChange={(e: React.ChangeEvent<HTMLInputElement>) => setSpeed(parseFloat(e.target.value))}
                disabled={replayStatus?.status === 'RUNNING'}
                className="flex-1"
              />
              <span className="text-sm font-semibold w-16">{speed.toFixed(1)}x</span>
            </div>
            <div className="flex flex-wrap gap-2">
              {speedOptions.map((option) => (
                <button
                  key={option.value}
                  onClick={() => setSpeed(option.value)}
                  disabled={replayStatus?.status === 'RUNNING'}
                  className={cn(
                    'rounded px-3 py-1 text-sm font-medium',
                    speed === option.value
                      ? 'bg-blue-600 text-white'
                      : 'border border-gray-300 bg-white hover:bg-gray-50 disabled:opacity-50'
                  )}
                >
                  {option.label}
                </button>
              ))}
            </div>
          </div>

          <button
            onClick={handleStartReplay}
            disabled={loading || replayStatus?.status === 'RUNNING'}
            className="w-full rounded bg-blue-600 px-4 py-2 font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {loading ? 'Starting...' : 'Start Replay'}
          </button>
        </div>

        {/* Status Section */}
        {replayStatus && (
          <div className="space-y-4">
            <h3 className="font-semibold text-sm">Replay Status</h3>

            <div className="flex items-center justify-between">
              <span className="text-sm text-gray-600">Status:</span>
              <span className={cn('rounded px-3 py-1 text-xs font-medium', statusColor[replayStatus.status] || '')}>
                {replayStatus.status}
              </span>
            </div>

            <div className="space-y-1">
              <div className="flex justify-between text-xs text-gray-600">
                <span>Progress</span>
                <span>{(replayStatus.progress * 100).toFixed(1)}%</span>
              </div>
              <div className="h-2 w-full overflow-hidden rounded-full bg-gray-200">
                <div
                  className="h-full bg-blue-600 transition-all"
                  style={{ width: `${replayStatus.progress * 100}%` }}
                />
              </div>
            </div>

            <div className="grid grid-cols-2 gap-4 text-sm">
              <div>
                <p className="text-gray-600">Current Time</p>
                <p className="font-mono">
                  {fmtDateTime(replayStatus.currentTime)}
                </p>
              </div>
              <div>
                <p className="text-gray-600">Speed</p>
                <p className="font-mono">{replayStatus.speed.toFixed(1)}x</p>
              </div>
            </div>

            {/* Control Buttons */}
            <div className="flex gap-2">
              {replayStatus.status === 'RUNNING' && (
                <button
                  onClick={handlePauseReplay}
                  className="flex-1 rounded border border-gray-300 px-3 py-2 font-medium hover:bg-gray-50"
                >
                  Pause
                </button>
              )}

              {replayStatus.status === 'PAUSED' && (
                <button
                  onClick={handleResumeReplay}
                  className="flex-1 rounded bg-green-600 px-3 py-2 font-medium text-white hover:bg-green-700"
                >
                  Resume
                </button>
              )}

              {replayStatus.status !== 'STOPPED' && (
                <button
                  onClick={handleStopReplay}
                  className="flex-1 rounded bg-red-600 px-3 py-2 font-medium text-white hover:bg-red-700"
                >
                  Stop
                </button>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
