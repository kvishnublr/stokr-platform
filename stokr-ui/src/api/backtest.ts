import { api } from "./client";

export type ReplayValidationReport = {
  deterministic: boolean;
  signalMismatchCount: number;
  pnlMismatch: string;
  executionMismatch: number;
  replayHash: string;
};

export type BacktestReplayOutcome = {
  runId: string;
  runStatus: string;
  strategyKey: string;
  symbol: string;
  rangeStart: string | null;
  rangeEnd: string | null;
  timeframe: string | null;
  executionProfile: string | null;
  materialized: boolean;
  validation: ReplayValidationReport;
  metrics: {
    winRate: string;
    totalTrades: number;
    profitFactor: string;
    sharpeRatio: string;
    maxDrawdown: string;
    avgRr: string;
    expectancy: string;
    totalPnl: string;
    avgHoldingTimeSeconds: number | null;
  };
  trades: {
    id: string;
    symbol: string;
    side: string;
    quantity: string;
    price: string;
    pnl: string;
    openedAt: string | null;
    closedAt: string | null;
    holdingSeconds: number | null;
  }[];
  equityCurve: {
    pointTime: string;
    cumulativePnl: string;
    drawdown: string;
  }[];
};

export type BacktestRunSummary = {
  id: string;
  strategyKey: string;
  symbol: string;
  status: string;
  seed: number;
  timeframe: string | null;
  rangeStart: string | null;
  rangeEnd: string | null;
  createdAt: string;
  replayHashPreview: string | null;
};

export type SpringPage<T> = {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
};

export type ExecutionTimeRange = {
  from: string;
  to: string;
  timezone: string;
};

/** PR-2 unified synchronous backtest envelope (matches `ExecutionRequestDto`). */
export type ExecutionRequest = {
  strategyKey: string;
  symbol: string;
  timeframe: string;
  executionMode: string;
  executionProfile: string;
  capital: number;
  feeModel: string;
  slippageModel: string;
  seed?: number | null;
  range: ExecutionTimeRange;
  strategyParameters: Record<string, unknown>;
};

type ApiEnvelope<T> = { data?: T; correlationId?: string };

export async function launchReplay(body: ExecutionRequest) {
  const res = await api.post<ApiEnvelope<BacktestReplayOutcome>>("/api/backtest/replay", body);
  const env = res.data;
  return {
    outcome: env?.data as BacktestReplayOutcome,
    correlationId: (res.headers["x-correlation-id"] as string | undefined) ?? env?.correlationId,
  };
}

export async function listRuns(page = 0, size = 20) {
  const res = await api.get<ApiEnvelope<SpringPage<BacktestRunSummary>>>(
    `/api/backtest/runs?page=${page}&size=${size}`,
  );
  const env = res.data;
  return {
    page: env?.data as SpringPage<BacktestRunSummary>,
    correlationId: (res.headers["x-correlation-id"] as string | undefined) ?? env?.correlationId,
  };
}

export async function getRunDetail(runId: string) {
  const res = await api.get<ApiEnvelope<BacktestReplayOutcome>>(`/api/backtest/runs/${runId}`);
  const env = res.data;
  return {
    outcome: env?.data as BacktestReplayOutcome,
    correlationId: (res.headers["x-correlation-id"] as string | undefined) ?? env?.correlationId,
  };
}

export async function resumeRun(runId: string) {
  const res = await api.post<ApiEnvelope<BacktestReplayOutcome>>(`/api/backtest/runs/${runId}/resume`);
  const env = res.data;
  return {
    outcome: env?.data as BacktestReplayOutcome,
    correlationId: (res.headers["x-correlation-id"] as string | undefined) ?? env?.correlationId,
  };
}

export type JournalEntry = {
  sequenceNum: number;
  eventType: string;
  payloadJson: string;
  createdAt: string;
  chainHash: string;
  correlationId: string | null;
  strategyKey: string | null;
};

export async function fetchRunJournal(runId: string) {
  const res = await api.get<ApiEnvelope<JournalEntry[]>>(`/api/backtest/runs/${runId}/journal`);
  const env = res.data;
  return {
    entries: (env?.data ?? []) as JournalEntry[],
    correlationId: (res.headers["x-correlation-id"] as string | undefined) ?? env?.correlationId,
  };
}
