import React, { useState, useEffect } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '../ui/card';
import { Input } from '../ui/input';
import { Button } from '../ui/button';
import { AlertCircle, Save, Plus, Trash2 } from 'lucide-react';

interface AssetClassAllocation {
  assetClass: 'CASH' | 'F&O' | 'CURRENCY' | 'MCX';
  allocatedCapital: number;
  maxConcurrentPositions: number;
  perTradeLimitAmount: number;
  perTradeMinQty: number;
  enabled: boolean;
}

interface CapitalAllocationConfig {
  totalCapital: number;
  allocations: AssetClassAllocation[];
  lastUpdated: string;
}

export function CapitalAllocationManager() {
  const [config, setConfig] = useState<CapitalAllocationConfig>({
    totalCapital: 100000,
    allocations: [
      { assetClass: 'CASH', allocatedCapital: 50000, maxConcurrentPositions: 10, perTradeLimitAmount: 5000, perTradeMinQty: 0, enabled: true },
      { assetClass: 'F&O', allocatedCapital: 30000, maxConcurrentPositions: 5, perTradeLimitAmount: 6000, perTradeMinQty: 0, enabled: true },
      { assetClass: 'CURRENCY', allocatedCapital: 15000, maxConcurrentPositions: 3, perTradeLimitAmount: 5000, perTradeMinQty: 0, enabled: true },
      { assetClass: 'MCX', allocatedCapital: 5000, maxConcurrentPositions: 2, perTradeLimitAmount: 2500, perTradeMinQty: 0, enabled: false },
    ],
    lastUpdated: new Date().toISOString(),
  });

  const [isEditing, setIsEditing] = useState(false);
  const [selectedAssetClass, setSelectedAssetClass] = useState<'CASH' | 'F&O' | 'CURRENCY' | 'MCX' | null>(null);

  // Calculate per-trade limits automatically
  const calculatePerTradeLimits = (allocation: AssetClassAllocation) => {
    const perTrade = allocation.allocatedCapital / Math.max(allocation.maxConcurrentPositions, 1);
    return Math.floor(perTrade);
  };

  const updateAllocation = (assetClass: 'CASH' | 'F&O' | 'CURRENCY' | 'MCX', field: keyof AssetClassAllocation, value: any) => {
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

  const handleSave = async () => {
    try {
      // Save to backend
      console.log('Saving capital allocation:', config);
      setIsEditing(false);
      // TODO: Call API endpoint to save configuration
    } catch (error) {
      console.error('Failed to save allocation:', error);
    }
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
          onClick={() => setIsEditing(!isEditing)}
          variant={isEditing ? "default" : "outline"}
        >
          {isEditing ? 'Cancel' : 'Edit Configuration'}
        </Button>
      </div>

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
                    <Input
                      type="number"
                      value={allocation.allocatedCapital}
                      onChange={(e) => updateAllocation(allocation.assetClass, 'allocatedCapital', parseInt(e.target.value))}
                      className="font-mono text-lg"
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
                    <Input
                      type="number"
                      value={allocation.maxConcurrentPositions}
                      onChange={(e) => updateAllocation(allocation.assetClass, 'maxConcurrentPositions', parseInt(e.target.value))}
                      className="font-mono text-lg"
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
                  {allocation.enabled ? '✅ Disable This Asset Class' : '🔓 Enable This Asset Class'}
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
          <Button variant="outline" onClick={() => setIsEditing(false)}>
            Cancel
          </Button>
          <Button onClick={handleSave} className="bg-green-600 hover:bg-green-700">
            <Save className="w-4 h-4 mr-2" />
            Save Configuration
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
