import { api } from "./client";

export type ReplayValidationReport = {
  deterministic: boolean;
  signalMismatchCount: number;
  pnlMismatch: string;
  executionMismatch: number;
  replayHash: string;
  /** Present on API >= fix; older servers omit these. */
  strategySignalCount?: number;
  executionEventCount?: number;
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
  loopTelemetry?: {
    candlesExpected: number;
    candlesProcessed: number;
    signalsEmitted: number;
    loopStartedAt: string | null;
  } | null;
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

/** Async worker replay - avoids blocking the browser on long 1m candle walks (see POST /api/backtest/jobs). */
export async function enqueueReplayJob(body: ExecutionRequest): Promise<string> {
  const res = await api.post<ApiEnvelope<string>>("/api/backtest/jobs", body);
  const env = res.data;
  const raw = env?.data;
  if (raw == null || raw === "") {
    throw new Error("Backtest job was not assigned an id");
  }
  return typeof raw === "string" ? raw : String(raw);
}

export type BacktestJobStatus = {
  id: string;
  status: string;
  progress: number;
  totalBars: number;
  processedBars: number;
  runId: string | null;
  message: string | null;
  cancelled: boolean;
  metadataSchemaVersion: number | null;
  strategyDefinitionVersion: number | null;
  createdAt: string;
  updatedAt: string;
  startedAt: string | null;
  etaSecondsRemaining: number | null;
  replayDiagnosis?: string | null;
  replayCandlesExpected?: number | null;
  replayCandlesProcessed?: number | null;
  replaySignalsEmitted?: number | null;
  replayExecutionEvents?: number | null;
  replayDurationMs?: number | null;
};

export async function getReplayJobStatus(jobId: string): Promise<BacktestJobStatus> {
  const res = await api.get<ApiEnvelope<BacktestJobStatus>>(`/api/backtest/jobs/${jobId}`);
  const env = res.data;
  if (!env?.data) {
    throw new Error("Job status response missing data");
  }
  return env.data as BacktestJobStatus;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export async function pollReplayJobUntilTerminal(
  jobId: string,
  opts?: { intervalMs?: number; maxWaitMs?: number; onProgress?: (s: BacktestJobStatus) => void },
): Promise<BacktestJobStatus> {
  const intervalMs = opts?.intervalMs ?? 2000;
  const maxWaitMs = opts?.maxWaitMs ?? 3 * 60 * 60 * 1000;
  const deadline = Date.now() + maxWaitMs;
  while (Date.now() < deadline) {
    const s = await getReplayJobStatus(jobId);
    opts?.onProgress?.(s);
    const st = String(s.status).toUpperCase();
    if (st === "COMPLETED" || st === "FAILED" || st === "CANCELLED") {
      return s;
    }
    await sleep(intervalMs);
  }
  throw new Error("Replay job timed out - try a shorter date range or check API logs.");
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
