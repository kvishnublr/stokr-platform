import React, { useState, useEffect } from 'react';
import { cn } from '../../lib/utils';

interface SymbolCoverage {
  symbol: string;
  timeframes: string[];
  startDate: string;
  endDate: string;
  candleCount: number;
  isComplete: boolean;
}

export function MarketDataCoverageMonitor() {
  const [coverage, setCoverage] = useState<SymbolCoverage[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchCoverage = async () => {
      try {
        // Mock data - in production, fetch from API
        const mockData: SymbolCoverage[] = [
          {
            symbol: 'SBIN',
            timeframes: ['1m', '5m', '15m', '1h', '1d'],
            startDate: '2024-01-01',
            endDate: '2025-12-31',
            candleCount: 247680,
            isComplete: true,
          },
          {
            symbol: 'RELIANCE',
            timeframes: ['1m', '5m', '15m', '1h'],
            startDate: '2024-06-01',
            endDate: '2025-12-31',
            candleCount: 98760,
            isComplete: false,
          },
          {
            symbol: 'INFY',
            timeframes: ['1m', '5m', '15m', '1h', '1d'],
            startDate: '2024-01-01',
            endDate: '2025-12-31',
            candleCount: 247680,
            isComplete: true,
          },
          {
            symbol: 'TCS',
            timeframes: ['1d'],
            startDate: '2020-01-01',
            endDate: '2025-12-31',
            candleCount: 1500,
            isComplete: false,
          },
        ];
        setCoverage(mockData);
      } catch (error) {
        console.error('Error fetching coverage:', error);
      } finally {
        setLoading(false);
      }
    };

    fetchCoverage();
  }, []);

  const completeScan = coverage.filter((c) => c.isComplete).length;
  const completionPercentage = (completeScan / coverage.length) * 100;

  return (
    <div className="rounded-lg border border-gray-200 bg-white p-6">
      <div className="mb-6">
        <h2 className="text-lg font-bold">Market Data Coverage</h2>
      </div>
      <div className="space-y-6">
        {/* Summary */}
        <div className="grid grid-cols-3 gap-4">
          <div className="bg-blue-50 p-3 rounded-lg">
            <p className="text-xs text-gray-600">Total Symbols</p>
            <p className="text-2xl font-bold">{coverage.length}</p>
          </div>
          <div className="bg-green-50 p-3 rounded-lg">
            <p className="text-xs text-gray-600">Complete Coverage</p>
            <p className="text-2xl font-bold">{completeScan}</p>
          </div>
          <div className="bg-purple-50 p-3 rounded-lg">
            <p className="text-xs text-gray-600">Completion Rate</p>
            <p className="text-2xl font-bold">{completionPercentage.toFixed(0)}%</p>
          </div>
        </div>

        {loading ? (
          <p className="text-center text-gray-500">Loading coverage data...</p>
        ) : (
          <div className="space-y-3 max-h-96 overflow-y-auto">
            {coverage.map((symbol) => (
              <div key={symbol.symbol} className="space-y-2 rounded border border-gray-200 p-3">
                <div className="flex items-center justify-between">
                  <h4 className="font-semibold">{symbol.symbol}</h4>
                  <span
                    className={cn(
                      'rounded px-2 py-1 text-xs font-medium',
                      symbol.isComplete
                        ? 'bg-green-100 text-green-800'
                        : 'bg-yellow-100 text-yellow-800'
                    )}
                  >
                    {symbol.isComplete ? 'Complete' : 'Partial'}
                  </span>
                </div>

                <div className="space-y-1 text-xs text-gray-600">
                  <p>
                    <strong>Timeframes:</strong> {symbol.timeframes.join(', ')}
                  </p>
                  <p>
                    <strong>Date Range:</strong> {symbol.startDate} to {symbol.endDate}
                  </p>
                  <p>
                    <strong>Candles:</strong> {symbol.candleCount.toLocaleString()}
                  </p>
                </div>

                {!symbol.isComplete && (
                  <div className="rounded border border-yellow-200 bg-yellow-50 p-2">
                    <p className="text-xs text-yellow-800">
                      Insufficient data for certain timeframes. Replay may use synthetic data fallback.
                    </p>
                  </div>
                )}
              </div>
            ))}
          </div>
        )}

        {coverage.length === 0 && !loading && (
          <div className="rounded border border-gray-200 bg-gray-50 p-3">
            <p className="text-sm text-gray-700">No market data coverage available</p>
          </div>
        )}
      </div>
    </div>
  );
}
