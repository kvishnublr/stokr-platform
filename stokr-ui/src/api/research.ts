import { api } from "./client";

export type LeaderboardRow = {
  strategyKey: string;
  avgSharpeRatio: string;
  avgWinRate: string;
  avgMaxDrawdown: string;
  sampleRuns: number;
};

type ApiEnvelope<T> = { data?: T; correlationId?: string };

export async function fetchStrategyLeaderboard() {
  const res = await api.get<ApiEnvelope<LeaderboardRow[]>>("/api/backtest/research/leaderboard");
  const env = res.data;
  return {
    rows: (env?.data ?? []) as LeaderboardRow[],
    correlationId: (res.headers["x-correlation-id"] as string | undefined) ?? env?.correlationId,
  };
}
