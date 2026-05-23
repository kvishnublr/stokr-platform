import React, { useState, useEffect } from 'react';
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  Button,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Slider,
  Input,
  Progress,
  Badge,
} from '@/components/ui/shared';

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
        const response = await fetch('/api/admin/execution/replay/status');
        if (response.ok) {
          const data = await response.json();
          setReplayStatus(data);
        }
      } catch (error) {
        console.error('Error fetching replay status:', error);
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
    <Card className="w-full">
      <CardHeader>
        <CardTitle>Replay Controls</CardTitle>
      </CardHeader>
      <CardContent className="space-y-6">
        {/* Setup Section */}
        <div className="space-y-4">
          <h3 className="font-semibold text-sm">Setup Replay</h3>

          <div className="grid grid-cols-3 gap-4">
            <div className="space-y-1">
              <label className="block text-xs font-medium">Symbol</label>
              <Input
                type="text"
                value={symbol}
                onChange={(e) => setSymbol(e.target.value.toUpperCase())}
                placeholder="e.g., SBIN"
                disabled={replayStatus?.status === 'RUNNING'}
              />
            </div>

            <div className="space-y-1">
              <label className="block text-xs font-medium">Start Date</label>
              <Input
                type="date"
                value={startDate}
                onChange={(e) => setStartDate(e.target.value)}
                disabled={replayStatus?.status === 'RUNNING'}
              />
            </div>

            <div className="space-y-1">
              <label className="block text-xs font-medium">End Date</label>
              <Input
                type="date"
                value={endDate}
                onChange={(e) => setEndDate(e.target.value)}
                disabled={replayStatus?.status === 'RUNNING'}
              />
            </div>
          </div>

          <div className="space-y-2">
            <label className="block text-xs font-medium">Replay Speed</label>
            <div className="flex items-center gap-4">
              <Slider
                min={0.5}
                max={10.0}
                step={0.5}
                value={[speed]}
                onValueChange={(value) => setSpeed(value[0])}
                disabled={replayStatus?.status === 'RUNNING'}
                className="flex-1"
              />
              <span className="text-sm font-semibold w-16">{speed.toFixed(1)}x</span>
            </div>
            <div className="flex flex-wrap gap-2">
              {speedOptions.map((option) => (
                <Button
                  key={option.value}
                  size="sm"
                  variant={speed === option.value ? 'default' : 'outline'}
                  onClick={() => setSpeed(option.value)}
                  disabled={replayStatus?.status === 'RUNNING'}
                >
                  {option.label}
                </Button>
              ))}
            </div>
          </div>

          <Button
            onClick={handleStartReplay}
            disabled={loading || replayStatus?.status === 'RUNNING'}
            className="w-full bg-blue-600 hover:bg-blue-700"
          >
            {loading ? 'Starting...' : 'Start Replay'}
          </Button>
        </div>

        {/* Status Section */}
        {replayStatus && (
          <div className="space-y-4">
            <h3 className="font-semibold text-sm">Replay Status</h3>

            <div className="flex items-center justify-between">
              <span className="text-sm text-gray-600">Status:</span>
              <Badge className={statusColor[replayStatus.status] || ''}>
                {replayStatus.status}
              </Badge>
            </div>

            <div className="space-y-1">
              <div className="flex justify-between text-xs text-gray-600">
                <span>Progress</span>
                <span>{(replayStatus.progress * 100).toFixed(1)}%</span>
              </div>
              <Progress value={replayStatus.progress * 100} className="w-full" />
            </div>

            <div className="grid grid-cols-2 gap-4 text-sm">
              <div>
                <p className="text-gray-600">Current Time</p>
                <p className="font-mono">
                  {new Date(replayStatus.currentTime).toLocaleString()}
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
                <Button
                  onClick={handlePauseReplay}
                  variant="outline"
                  className="flex-1"
                >
                  Pause
                </Button>
              )}

              {replayStatus.status === 'PAUSED' && (
                <Button
                  onClick={handleResumeReplay}
                  className="flex-1 bg-green-600 hover:bg-green-700"
                >
                  Resume
                </Button>
              )}

              {replayStatus.status !== 'STOPPED' && (
                <Button
                  onClick={handleStopReplay}
                  variant="destructive"
                  className="flex-1"
                >
                  Stop
                </Button>
              )}
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
