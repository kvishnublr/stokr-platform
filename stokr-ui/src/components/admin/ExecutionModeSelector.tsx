import React, { useState } from 'react';
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
  Input,
  Alert,
  AlertDescription,
} from '@/components/ui/shared';

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
      value: 'HYBRID',
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
    <Card className="w-full">
      <CardHeader>
        <CardTitle>Execution Mode Control</CardTitle>
      </CardHeader>
      <CardContent className="space-y-6">
        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <label className="block text-sm font-medium">Current Mode</label>
            <div
              className={`p-4 rounded-lg border-2 font-bold text-lg ${
                modes.find((m) => m.value === currentMode)?.color || 'bg-gray-100'
              }`}
            >
              {currentMode}
            </div>
            {lastSwitchTime && (
              <div className="text-xs text-gray-600">
                <p>Last switched: {lastSwitchTime}</p>
                <p>By: {lastSwitchedBy}</p>
              </div>
            )}
          </div>

          <div className="space-y-2">
            <label className="block text-sm font-medium">Target Mode</label>
            <Select value={targetMode} onValueChange={setTargetMode}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {modes.map((mode) => (
                  <SelectItem key={mode.value} value={mode.value}>
                    {mode.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>

        {targetModeConfig && (
          <Alert className={riskColors[targetModeConfig.riskLevel]}>
            <AlertDescription>
              <strong>{targetModeConfig.label}</strong>
              <br />
              {targetModeConfig.description}
              <br />
              Risk Level: <strong>{targetModeConfig.riskLevel}</strong>
            </AlertDescription>
          </Alert>
        )}

        {isLiveModeSelected && (
          <Alert className="bg-red-50 border-red-200">
            <AlertDescription className="text-red-700">
              ⚠️ <strong>WARNING</strong>: You are about to switch to LIVE mode. Real funds will be at risk.
              Ensure all safety checks are in place before proceeding.
            </AlertDescription>
          </Alert>
        )}

        <div className="space-y-2">
          <label className="block text-sm font-medium">Reason for Switch</label>
          <Input
            type="text"
            placeholder="Enter reason for execution mode change..."
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            className="w-full"
          />
        </div>

        <div className="flex gap-2">
          <Button
            onClick={() => {
              if (isLiveModeSelected) {
                setConfirmDialog(true);
              } else {
                handleSwitchMode();
              }
            }}
            disabled={loading || currentMode === targetMode}
            className={isLiveModeSelected ? 'bg-red-600 hover:bg-red-700' : ''}
          >
            {loading ? 'Switching...' : 'Switch Mode'}
          </Button>
          <Button variant="outline" onClick={() => setReason('')}>
            Clear
          </Button>
        </div>

        {confirmDialog && isLiveModeSelected && (
          <Alert className="bg-red-50 border-red-400">
            <AlertDescription className="space-y-3 text-red-700">
              <p className="font-bold">Confirm LIVE Mode Switch</p>
              <p>This action will enable real broker connections and use real funds.</p>
              <div className="flex gap-2">
                <Button
                  size="sm"
                  onClick={handleSwitchMode}
                  disabled={loading}
                  className="bg-red-600 hover:bg-red-700"
                >
                  {loading ? 'Processing...' : 'I understand, proceed'}
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => setConfirmDialog(false)}
                  disabled={loading}
                >
                  Cancel
                </Button>
              </div>
            </AlertDescription>
          </Alert>
        )}
      </CardContent>
    </Card>
  );
}
