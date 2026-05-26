import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { api } from "../../api/client";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "../ui/card";
import { Button } from "../ui/button";
import { Badge } from "../ui/badge";
import { AlertCircle, CheckCircle2, Clock, Download, Play, RefreshCw, TrendingUp } from "lucide-react";

type ProgressData = {
  status: "loading" | "completed";
  progress: {
    running: boolean;
    total_candles_loaded: number;
    symbols_completed: number;
    failed_symbols: number;
    elapsed_seconds: number;
    symbol_progress: Record<string, number>;
    failed_list: string[];
  };
  last_updated: string;
};

export function BacktestHistoricalDataLoader() {
  const [pollingInterval, setPollingInterval] = useState<number>(5000); // 5 seconds
  const [selectedSymbols, setSelectedSymbols] = useState<string>("");
  const queryClient = useQueryClient();

  // Fetch progress
  const { data: progressData, isLoading: isLoadingProgress } = useQuery({
    queryKey: ["backtest-data-progress"],
    queryFn: async () => {
      const response = await api.get<ProgressData>(
        "/api/v1/admin/backtest-data/progress"
      );
      return response.data;
    },
    refetchInterval: (data) => {
      // Poll more frequently when loading, less when done
      return data?.progress.running ? 5000 : 30000;
    },
  });

  // Start load mutation
  const { mutate: startLoad, isPending: isStarting } = useMutation({
    mutationFn: async () => {
      const params = new URLSearchParams();
      if (selectedSymbols.trim()) {
        const symbols = selectedSymbols
          .split(",")
          .map((s) => s.trim())
          .filter((s) => s);
        params.append("symbols", symbols.join(","));
      }

      const response = await api.post<{
        status: string;
        message: string;
        total_loaded_so_far?: number;
        expected_duration?: string;
      }>(
        "/api/v1/admin/backtest-data/start-load",
        {},
        { params }
      );
      return response.data;
    },
    onSuccess: (data) => {
      toast.success(data.message);
      queryClient.invalidateQueries({
        queryKey: ["backtest-data-progress"],
      });
    },
    onError: (error: any) => {
      const message =
        error.response?.data?.message || "Failed to start historical data load";
      toast.error(message);
    },
  });

  const progress = progressData?.progress;
  const isRunning = progress?.running || false;
  const totalCandles = progress?.total_candles_loaded || 0;
  const symbolsCompleted = progress?.symbols_completed || 0;
  const failedSymbols = progress?.failed_symbols || 0;
  const elapsedSeconds = progress?.elapsed_seconds || 0;

  const completionPercentage =
    symbolsCompleted > 0
      ? Math.round((symbolsCompleted / (symbolsCompleted + failedSymbols)) * 100)
      : 0;

  const formatTime = (seconds: number) => {
    if (seconds < 60) return `${seconds}s`;
    if (seconds < 3600) return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
    return `${Math.floor(seconds / 3600)}h ${Math.floor((seconds % 3600) / 60)}m`;
  };

  const formatCandles = (count: number) => {
    if (count >= 1000000) return `${(count / 1000000).toFixed(1)}M`;
    if (count >= 1000) return `${(count / 1000).toFixed(1)}K`;
    return count.toString();
  };

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <div>
              <CardTitle className="flex items-center gap-2">
                <Download className="h-5 w-5" />
                Historical Data Loader for Backtesting
              </CardTitle>
              <CardDescription>
                Load 5 years of NSE historical OHLCV data (2019-2024) for
                strategy validation
              </CardDescription>
            </div>
            <Badge variant={isRunning ? "default" : "secondary"}>
              {isRunning ? "LOADING" : "READY"}
            </Badge>
          </div>
        </CardHeader>

        <CardContent className="space-y-6">
          {/* Status Section */}
          {progress && (
            <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
              <div className="rounded-lg bg-slate-50 p-4">
                <div className="text-sm font-medium text-slate-600">
                  Total Candles
                </div>
                <div className="mt-1 text-2xl font-bold text-slate-900">
                  {formatCandles(totalCandles)}
                </div>
              </div>

              <div className="rounded-lg bg-blue-50 p-4">
                <div className="text-sm font-medium text-blue-600">
                  Symbols Completed
                </div>
                <div className="mt-1 text-2xl font-bold text-blue-900">
                  {symbolsCompleted}
                </div>
              </div>

              <div className="rounded-lg bg-amber-50 p-4">
                <div className="text-sm font-medium text-amber-600">
                  Elapsed Time
                </div>
                <div className="mt-1 text-2xl font-bold text-amber-900">
                  {formatTime(elapsedSeconds)}
                </div>
              </div>

              <div className="rounded-lg bg-red-50 p-4">
                <div className="text-sm font-medium text-red-600">
                  Failed Symbols
                </div>
                <div className="mt-1 text-2xl font-bold text-red-900">
                  {failedSymbols}
                </div>
              </div>
            </div>
          )}

          {/* Progress Bar */}
          {isRunning && (
            <div className="space-y-2">
              <div className="flex justify-between text-sm">
                <span className="font-medium text-slate-700">
                  Load Progress
                </span>
                <span className="text-slate-600">{completionPercentage}%</span>
              </div>
              <div className="h-2 w-full rounded-full bg-slate-200">
                <div
                  className="h-full rounded-full bg-blue-500 transition-all duration-500"
                  style={{ width: `${completionPercentage}%` }}
                />
              </div>
            </div>
          )}

          {/* Completion Message */}
          {!isRunning && totalCandles > 0 && (
            <div className="flex items-center gap-3 rounded-lg bg-green-50 p-4">
              <CheckCircle2 className="h-5 w-5 text-green-600" />
              <div>
                <p className="font-medium text-green-900">Load Complete</p>
                <p className="text-sm text-green-700">
                  Loaded {formatCandles(totalCandles)} candles from{" "}
                  {symbolsCompleted} symbols in {formatTime(elapsedSeconds)}.
                  Ready for backtesting!
                </p>
              </div>
            </div>
          )}

          {/* Control Section */}
          <div className="space-y-4">
            <div>
              <label className="text-sm font-medium text-slate-700">
                Symbols to Load (Optional)
              </label>
              <p className="mb-2 text-xs text-slate-500">
                Leave empty to load all available symbols. Or enter comma-separated symbols: INFY,TCS,WIPRO
              </p>
              <input
                type="text"
                value={selectedSymbols}
                onChange={(e) => setSelectedSymbols(e.target.value)}
                placeholder="e.g., INFY, TCS, WIPRO, RELIANCE"
                className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm placeholder-slate-400 focus:border-blue-500 focus:outline-none"
                disabled={isRunning || isStarting}
              />
            </div>

            <div className="flex gap-2">
              <Button
                onClick={() => startLoad()}
                disabled={isRunning || isStarting}
                className="flex-1 gap-2"
              >
                <Play className="h-4 w-4" />
                {isRunning ? "Loading..." : "Start Background Load"}
              </Button>

              <Button
                variant="outline"
                onClick={() =>
                  queryClient.invalidateQueries({
                    queryKey: ["backtest-data-progress"],
                  })
                }
                disabled={isStarting}
              >
                <RefreshCw className="h-4 w-4" />
              </Button>
            </div>
          </div>

          {/* Info Box */}
          <div className="flex gap-3 rounded-lg bg-blue-50 p-4">
            <AlertCircle className="mt-0.5 h-5 w-5 flex-shrink-0 text-blue-600" />
            <div className="text-sm text-blue-900">
              <p className="font-medium">Expected Duration</p>
              <ul className="mt-2 space-y-1 text-xs">
                <li>• Single symbol: 2-3 minutes</li>
                <li>• 25 symbols (Nifty50 subset): ~60 minutes</li>
                <li>• 50 symbols (Full Nifty50): 2-3 hours</li>
              </ul>
            </div>
          </div>

          {/* Symbol Progress Table */}
          {progress?.symbol_progress &&
            Object.keys(progress.symbol_progress).length > 0 && (
              <div className="space-y-3">
                <h4 className="flex items-center gap-2 font-medium text-slate-900">
                  <TrendingUp className="h-4 w-4" />
                  Symbols Loaded
                </h4>
                <div className="max-h-48 overflow-y-auto rounded-lg border border-slate-200">
                  <table className="w-full text-sm">
                    <thead className="sticky top-0 bg-slate-100">
                      <tr>
                        <th className="px-3 py-2 text-left font-medium text-slate-700">
                          Symbol
                        </th>
                        <th className="px-3 py-2 text-right font-medium text-slate-700">
                          Candles
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {Object.entries(progress.symbol_progress)
                        .sort((a, b) => b[1] - a[1])
                        .map(([symbol, candles]) => (
                          <tr
                            key={symbol}
                            className="border-t border-slate-200 hover:bg-slate-50"
                          >
                            <td className="px-3 py-2 font-medium text-slate-900">
                              {symbol}
                            </td>
                            <td className="px-3 py-2 text-right text-slate-600">
                              {formatCandles(candles)}
                            </td>
                          </tr>
                        ))}
                    </tbody>
                  </table>
                </div>
              </div>
            )}

          {/* Failed Symbols */}
          {progress?.failed_list && progress.failed_list.length > 0 && (
            <div className="space-y-2 rounded-lg bg-red-50 p-4">
              <h4 className="flex items-center gap-2 font-medium text-red-900">
                <AlertCircle className="h-4 w-4" />
                Failed Symbols ({progress.failed_list.length})
              </h4>
              <div className="text-sm text-red-700">
                {progress.failed_list.join(", ")}
              </div>
            </div>
          )}

          {/* Last Updated */}
          {progressData?.last_updated && (
            <p className="flex items-center gap-2 text-xs text-slate-500">
              <Clock className="h-3 w-3" />
              Last updated: {new Date(progressData.last_updated).toLocaleTimeString()}
            </p>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
