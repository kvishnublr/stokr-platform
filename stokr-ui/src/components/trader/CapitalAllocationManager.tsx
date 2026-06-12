import React, { useState, useEffect } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Card, CardContent, CardHeader, CardTitle } from '../ui/card';
import { ModernInput } from '../modern/ModernInput';
import { Button } from '../ui/button';
import { AlertCircle, Save } from 'lucide-react';
import { parseAxiosMessage } from '../../api/client';
import {
  fetchCapitalAllocation,
  saveCapitalAllocation,
  type AssetClassAllocationDto,
  type TraderCapitalAllocationView,
} from '../../api/traderCapitalAllocation';

interface AssetClassAllocation {
  assetClass: 'CASH' | 'F&O' | 'CURRENCY' | 'MCX';
  allocatedCapital: number;
  maxConcurrentPositions: number;
  perTradeLimitAmount: number;
  perTradeMinQty: number;
  enabled: boolean;
  dailyLossLimit?: number;
  dailyLossLimitEnabled?: boolean;
  memberStrategyKeys?: string[];
}

interface CapitalAllocationConfig {
  totalCapital: number;
  allocations: AssetClassAllocation[];
  lastUpdated: string;
}

const EMPTY_CONFIG: CapitalAllocationConfig = {
  totalCapital: 100000,
  allocations: [],
  lastUpdated: new Date().toISOString(),
};

function viewToConfig(view: TraderCapitalAllocationView): CapitalAllocationConfig {
  return {
    totalCapital: view.totalCapital,
    allocations: view.allocations.map((a) => ({
      assetClass: a.assetClass,
      allocatedCapital: a.allocatedCapital,
      maxConcurrentPositions: a.maxConcurrentPositions,
      perTradeLimitAmount: a.perTradeLimitAmount,
      perTradeMinQty: 0,
      enabled: a.enabled,
      dailyLossLimit: a.dailyLossLimit ?? undefined,
      dailyLossLimitEnabled: a.dailyLossLimitEnabled,
      memberStrategyKeys: a.memberStrategyKeys,
    })),
    lastUpdated: new Date().toISOString(),
  };
}

function configToView(config: CapitalAllocationConfig): TraderCapitalAllocationView {
  return {
    totalCapital: config.totalCapital,
    allocations: config.allocations.map((a): AssetClassAllocationDto => ({
      assetClass: a.assetClass,
      allocatedCapital: a.allocatedCapital,
      maxConcurrentPositions: a.maxConcurrentPositions,
      perTradeLimitAmount: a.perTradeLimitAmount,
      enabled: a.enabled,
      dailyLossLimit: a.dailyLossLimitEnabled ? (a.dailyLossLimit ?? null) : null,
      dailyLossLimitEnabled: a.dailyLossLimitEnabled ?? false,
      memberStrategyKeys: a.memberStrategyKeys ?? [],
    })),
  };
}

export function CapitalAllocationManager() {
  const queryClient = useQueryClient();
  const [config, setConfig] = useState<CapitalAllocationConfig>(EMPTY_CONFIG);
  const [isEditing, setIsEditing] = useState(false);

  const allocationQuery = useQuery({
    queryKey: ['trader-capital-allocation'],
    queryFn: fetchCapitalAllocation,
  });

  // Sync server state into the editable local copy whenever it loads/refreshes,
  // unless the trader is mid-edit (don't clobber unsaved changes).
  useEffect(() => {
    if (allocationQuery.data && !isEditing) {
      setConfig(viewToConfig(allocationQuery.data));
    }
  }, [allocationQuery.data, isEditing]);

  const saveMut = useMutation({
    mutationFn: (cfg: CapitalAllocationConfig) => saveCapitalAllocation(configToView(cfg)),
    onSuccess: (saved) => {
      setConfig(viewToConfig(saved));
      setIsEditing(false);
      void queryClient.invalidateQueries({ queryKey: ['trader-capital-allocation'] });
      void queryClient.invalidateQueries({ queryKey: ['trader-exec-configs'] });
      toast.success('Capital allocation saved');
    },
    onError: (err) => toast.error(parseAxiosMessage(err)),
  });

  // Calculate per-trade limits automatically
  const calculatePerTradeLimits = (allocation: AssetClassAllocation) => {
    const perTrade = allocation.allocatedCapital / Math.max(allocation.maxConcurrentPositions, 1);
    return Math.floor(perTrade);
  };

  const updateAllocation = (
    assetClass: 'CASH' | 'F&O' | 'CURRENCY' | 'MCX',
    field: keyof AssetClassAllocation,
    value: string | number | boolean,
  ) => {
    setConfig(prev => ({
      ...prev,
      allocations: prev.allocations.map(a =>
        a.assetClass === assetClass
          ? {
              ...a,
              [field]: value,
              // Auto-calculate per-trade limit when allocation or max positions change
              ...(field === 'allocatedCapital' || field === 'maxConcurrentPositions') && {
                perTradeLimitAmount: calculatePerTradeLimits({
                  ...a,
                  [field]: value
                })
              }
            }
          : a
      ),
      lastUpdated: new Date().toISOString(),
    }));
  };

  const getTotalAllocated = () => {
    return config.allocations.reduce((sum, a) => sum + a.allocatedCapital, 0);
  };

  const getUnallocated = () => {
    return Math.max(0, config.totalCapital - getTotalAllocated());
  };

  const handleSave = () => {
    saveMut.mutate(config);
  };

  const handleCancel = () => {
    if (allocationQuery.data) {
      setConfig(viewToConfig(allocationQuery.data));
    }
    setIsEditing(false);
  };

  const assetClassLabels = {
    CASH: '💰 Equity (Cash)',
    'F&O': '📊 Futures & Options',
    'CURRENCY': '💱 Currency',
    'MCX': '⚡ Commodities (MCX)',
  };

  const assetClassColors = {
    CASH: 'bg-blue-50 border-blue-200',
    'F&O': 'bg-purple-50 border-purple-200',
    'CURRENCY': 'bg-green-50 border-green-200',
    'MCX': 'bg-orange-50 border-orange-200',
  };

  return (
    <div className="w-full space-y-6 p-6">
      {/* Header */}
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-3xl font-bold text-gray-900">💼 Capital Allocation Manager</h1>
          <p className="text-gray-600 mt-1">Configure capital allocation across asset classes and trading strategies</p>
        </div>
        <Button
          onClick={() => (isEditing ? handleCancel() : setIsEditing(true))}
          variant={isEditing ? "default" : "outline"}
          disabled={saveMut.isPending || allocationQuery.isLoading}
        >
          {isEditing ? 'Cancel' : 'Edit Configuration'}
        </Button>
      </div>

      {allocationQuery.isLoading && config.allocations.length === 0 && (
        <div className="p-6 text-center text-gray-500">Loading capital allocation…</div>
      )}
      {allocationQuery.isError && (
        <div className="flex items-start gap-3 p-4 bg-red-50 border border-red-200 rounded-lg">
          <AlertCircle className="w-5 h-5 text-red-600 mt-0.5 flex-shrink-0" />
          <div>
            <p className="font-semibold text-red-900">Could not load capital allocation</p>
            <p className="text-sm text-red-700 mt-1">Check API connectivity and refresh.</p>
          </div>
        </div>
      )}

      {/* Total Capital Overview */}
      <Card className="bg-gradient-to-r from-blue-50 to-indigo-50 border-2 border-blue-200">
        <CardHeader>
          <CardTitle>Total Capital Allocation</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-4 gap-4">
            <div>
              <p className="text-sm text-gray-600">Total Capital</p>
              <p className="text-2xl font-bold text-gray-900">₹{config.totalCapital.toLocaleString()}</p>
            </div>
            <div>
              <p className="text-sm text-gray-600">Allocated</p>
              <p className="text-2xl font-bold text-green-600">₹{getTotalAllocated().toLocaleString()}</p>
            </div>
            <div>
              <p className="text-sm text-gray-600">Unallocated</p>
              <p className="text-2xl font-bold text-orange-600">₹{getUnallocated().toLocaleString()}</p>
            </div>
            <div>
              <p className="text-sm text-gray-600">Allocation %</p>
              <p className="text-2xl font-bold text-blue-600">{Math.round((getTotalAllocated() / config.totalCapital) * 100)}%</p>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Asset Class Allocations */}
      <div className="grid gap-4">
        {config.allocations.map(allocation => (
          <Card key={allocation.assetClass} className={`border-2 ${assetClassColors[allocation.assetClass]}`}>
            <CardHeader>
              <div className="flex justify-between items-center">
                <CardTitle className="text-lg">{assetClassLabels[allocation.assetClass]}</CardTitle>
                <div className="flex items-center gap-2">
                  <div className={`px-3 py-1 rounded-full text-sm font-semibold ${allocation.enabled ? 'bg-green-200 text-green-800' : 'bg-gray-200 text-gray-800'}`}>
                    {allocation.enabled ? '✅ Enabled' : '❌ Disabled'}
                  </div>
                </div>
              </div>
            </CardHeader>
            <CardContent className="space-y-4">
              {/* Allocation Amount */}
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-sm font-semibold text-gray-700 mb-1">
                    Allocated Capital
                  </label>
                  {isEditing ? (
                    <ModernInput
                      type="number"
                      value={allocation.allocatedCapital}
                      onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                        updateAllocation(allocation.assetClass, 'allocatedCapital', Number.parseInt(e.target.value, 10))
                      }
                      className="font-mono text-lg text-black bg-white border border-gray-300"
                    />
                  ) : (
                    <p className="text-2xl font-bold text-gray-900">₹{allocation.allocatedCapital.toLocaleString()}</p>
                  )}
                </div>

                <div>
                  <label className="block text-sm font-semibold text-gray-700 mb-1">
                    Max Concurrent Positions
                  </label>
                  {isEditing ? (
                    <ModernInput
                      type="number"
                      value={allocation.maxConcurrentPositions}
                      onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                        updateAllocation(
                          allocation.assetClass,
                          'maxConcurrentPositions',
                          Number.parseInt(e.target.value, 10),
                        )
                      }
                      className="font-mono text-lg text-black bg-white border border-gray-300"
                    />
                  ) : (
                    <p className="text-2xl font-bold text-gray-900">{allocation.maxConcurrentPositions}</p>
                  )}
                </div>
              </div>

              {/* Per-Trade Calculation */}
              <div className="bg-gray-50 p-4 rounded-lg border border-gray-200">
                <p className="text-sm text-gray-600 mb-2">📐 Per-Trade Allocation Formula:</p>
                <div className="bg-white p-3 rounded font-mono text-sm">
                  <p>Per Trade Amount = ₹{allocation.allocatedCapital.toLocaleString()} ÷ {allocation.maxConcurrentPositions}</p>
                  <p className="font-bold text-green-600 mt-1">= ₹{allocation.perTradeLimitAmount.toLocaleString()} per trade</p>
                </div>
              </div>

              {/* Metrics */}
              <div className="grid grid-cols-3 gap-3">
                <div className="bg-white p-3 rounded border border-gray-200">
                  <p className="text-xs text-gray-600">Per Trade Limit</p>
                  <p className="text-lg font-bold text-gray-900">₹{allocation.perTradeLimitAmount.toLocaleString()}</p>
                </div>

                <div className="bg-white p-3 rounded border border-gray-200">
                  <p className="text-xs text-gray-600">Allocation %</p>
                  <p className="text-lg font-bold text-blue-600">
                    {Math.round((allocation.allocatedCapital / config.totalCapital) * 100)}%
                  </p>
                </div>

                <div className="bg-white p-3 rounded border border-gray-200">
                  <p className="text-xs text-gray-600">Status</p>
                  <p className={`text-lg font-bold ${allocation.enabled ? 'text-green-600' : 'text-gray-500'}`}>
                    {allocation.enabled ? 'ACTIVE' : 'INACTIVE'}
                  </p>
                </div>
              </div>

              {/* Daily Loss Limit (Optional) */}
              <div className="bg-red-50 p-4 rounded-lg border-2 border-red-200">
                <div className="flex items-center justify-between mb-3">
                  <label className="block text-sm font-semibold text-red-900 flex items-center gap-2">
                    🛑 Max Daily Loss Limit (Optional)
                  </label>
                  {isEditing ? (
                    <label className="flex items-center gap-2 cursor-pointer">
                      <input
                        type="checkbox"
                        checked={allocation.dailyLossLimitEnabled || false}
                        onChange={(e) =>
                          updateAllocation(allocation.assetClass, 'dailyLossLimitEnabled', e.target.checked)
                        }
                        className="w-4 h-4"
                      />
                      <span className="text-xs font-semibold text-red-700">
                        {allocation.dailyLossLimitEnabled ? 'Enabled' : 'Disabled'}
                      </span>
                    </label>
                  ) : (
                    <span className={`text-xs font-semibold px-2 py-1 rounded ${
                      allocation.dailyLossLimitEnabled ? 'bg-red-200 text-red-800' : 'bg-gray-200 text-gray-700'
                    }`}>
                      {allocation.dailyLossLimitEnabled ? '✅ Enabled' : '⏸️ Disabled'}
                    </span>
                  )}
                </div>

                {allocation.dailyLossLimitEnabled && (
                  <div className="text-sm text-red-700 mb-3 p-2 bg-white rounded border border-red-200">
                    <p className="font-semibold">⚠️ Stop trading when daily loss reaches:</p>
                    <p className="text-xs text-red-500 mt-1">
                      Applied <strong>per strategy</strong> in this class (no single shared cap). The risk engine
                      blocks new entries once a strategy's realized loss breaches this and auto-resets next trading day.
                    </p>
                    {isEditing ? (
                      <div className="mt-2">
                        <ModernInput
                          type="number"
                          value={allocation.dailyLossLimit || 0}
                          onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                            updateAllocation(allocation.assetClass, 'dailyLossLimit', Number.parseInt(e.target.value, 10))
                          }
                          className="font-mono text-lg text-black bg-white border border-red-300 w-full"
                          placeholder="Enter max loss amount"
                        />
                      </div>
                    ) : (
                      <p className="text-lg font-bold text-red-600 mt-1">₹{(allocation.dailyLossLimit || 0).toLocaleString()}</p>
                    )}
                    {allocation.dailyLossLimit && (
                      <p className="text-xs text-red-600 mt-2">
                        Loss limit as % of allocation: {Math.round((allocation.dailyLossLimit / allocation.allocatedCapital) * 100)}%
                      </p>
                    )}
                  </div>
                )}

                {!allocation.dailyLossLimitEnabled && (
                  <p className="text-xs text-gray-600 italic">Daily loss limit is optional. Enable to activate automatic stop-loss at daily level.</p>
                )}
              </div>

              {/* Member strategies this bucket controls */}
              {allocation.memberStrategyKeys && allocation.memberStrategyKeys.length > 0 && (
                <div className="text-xs text-gray-500">
                  <span className="font-semibold">Applies to {allocation.memberStrategyKeys.length} strategies:</span>{' '}
                  {allocation.memberStrategyKeys.join(', ')}
                </div>
              )}

              {/* Enable/Disable Toggle */}
              {isEditing && (
                <button
                  onClick={() => updateAllocation(allocation.assetClass, 'enabled', !allocation.enabled)}
                  className={`w-full py-2 px-3 rounded font-semibold transition ${
                    allocation.enabled
                      ? 'bg-green-100 text-green-700 hover:bg-green-200'
                      : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
                  }`}
                >
                  {allocation.enabled ? '✅ Capital sizing ON (click to revert to fixed qty)' : '🔓 Enable capital sizing'}
                </button>
              )}
            </CardContent>
          </Card>
        ))}
      </div>

      {/* Summary Table */}
      <Card>
        <CardHeader>
          <CardTitle>📊 Allocation Summary</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="bg-gray-100 border-b-2 border-gray-300">
                  <th className="px-4 py-3 text-left font-bold">Asset Class</th>
                  <th className="px-4 py-3 text-right font-bold">Allocated</th>
                  <th className="px-4 py-3 text-right font-bold">Max Positions</th>
                  <th className="px-4 py-3 text-right font-bold">Per Trade Amount</th>
                  <th className="px-4 py-3 text-right font-bold">Daily Loss Limit</th>
                  <th className="px-4 py-3 text-center font-bold">Status</th>
                </tr>
              </thead>
              <tbody>
                {config.allocations.map(a => (
                  <tr key={a.assetClass} className="border-b border-gray-200 hover:bg-gray-50">
                    <td className="px-4 py-3 font-semibold">{assetClassLabels[a.assetClass]}</td>
                    <td className="px-4 py-3 text-right">₹{a.allocatedCapital.toLocaleString()}</td>
                    <td className="px-4 py-3 text-right font-bold">{a.maxConcurrentPositions}</td>
                    <td className="px-4 py-3 text-right text-green-600 font-bold">₹{a.perTradeLimitAmount.toLocaleString()}</td>
                    <td className="px-4 py-3 text-right">
                      {a.dailyLossLimitEnabled ? (
                        <span className="text-red-600 font-bold">₹{(a.dailyLossLimit || 0).toLocaleString()}</span>
                      ) : (
                        <span className="text-gray-500 text-xs italic">Not set</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-center">
                      <span className={`px-3 py-1 rounded-full text-xs font-bold ${a.enabled ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600'}`}>
                        {a.enabled ? '✅ Active' : '⏸️ Paused'}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </CardContent>
      </Card>

      {/* Action Buttons */}
      {isEditing && (
        <div className="flex gap-3 justify-end">
          <Button variant="outline" onClick={handleCancel} disabled={saveMut.isPending}>
            Cancel
          </Button>
          <Button onClick={handleSave} disabled={saveMut.isPending} className="bg-green-600 hover:bg-green-700">
            <Save className="w-4 h-4 mr-2" />
            {saveMut.isPending ? 'Saving…' : 'Save Configuration'}
          </Button>
        </div>
      )}

      {/* Warning Messages */}
      {getTotalAllocated() > config.totalCapital && (
        <div className="flex items-start gap-3 p-4 bg-red-50 border border-red-200 rounded-lg">
          <AlertCircle className="w-5 h-5 text-red-600 mt-0.5 flex-shrink-0" />
          <div>
            <p className="font-semibold text-red-900">⚠️ Over-allocation Detected</p>
            <p className="text-sm text-red-700 mt-1">
              Total allocated (₹{getTotalAllocated().toLocaleString()}) exceeds total capital (₹{config.totalCapital.toLocaleString()})
            </p>
          </div>
        </div>
      )}

      {getUnallocated() > 0 && (
        <div className="flex items-start gap-3 p-4 bg-orange-50 border border-orange-200 rounded-lg">
          <AlertCircle className="w-5 h-5 text-orange-600 mt-0.5 flex-shrink-0" />
          <div>
            <p className="font-semibold text-orange-900">📌 Unallocated Capital</p>
            <p className="text-sm text-orange-700 mt-1">
              You have ₹{getUnallocated().toLocaleString()} unallocated. Edit to allocate remaining capital.
            </p>
          </div>
        </div>
      )}
    </div>
  );
}
