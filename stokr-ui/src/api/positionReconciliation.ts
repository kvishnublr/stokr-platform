import { api } from "./client";

const DIAG_TIMEOUT_MS = 15_000;

export type PositionReconciliationSignal = {
  id?: string;
  outcomeStatus?: string;
  signalType?: string;
  symbol?: string;
  strategyName?: string;
  createdAt?: string;
};

export type PositionReconciliationRow = {
  positionId?: string;
  strategyKey?: string | null;
  symbol?: string;
  quantity?: number | string;
  avgPrice?: number | string | null;
  userId?: string;
  userEmail?: string;
  updatedAt?: string;
  brokerQty?: number | string | null;
  brokerMatch?: boolean | null;
  brokerConnected?: boolean;
  signal?: PositionReconciliationSignal | null;
  flags?: string[];
  status?: string;
  maxPositionsForStrategy?: number | null;
  strategyOpenCount?: number | null;
  ghostReasons?: { zeroPrice?: boolean; stale?: boolean; brokerFlat?: boolean };
};

export type PositionReconciliationSummary = {
  totalOpen?: number;
  ghostCount?: number;
  blockingCount?: number;
  strategiesAtCapacity?: number;
  primaryTraderUserId?: string | null;
  brokerConnected?: boolean;
  brokerSyncState?: string | null;
  staleThresholdHours?: number;
};

export type PositionReconciliationDiagnostics = {
  collectedAt?: string;
  summary?: PositionReconciliationSummary;
  rows?: PositionReconciliationRow[];
  clearedGhosts?: number;
};

export async function fetchPositionReconciliation(): Promise<PositionReconciliationDiagnostics> {
  const res = await api.get("/api/admin/oms/position-reconciliation", { timeout: DIAG_TIMEOUT_MS });
  return res.data?.data as PositionReconciliationDiagnostics;
}

export async function clearGhostPositions(): Promise<PositionReconciliationDiagnostics> {
  const res = await api.post("/api/admin/oms/position-reconciliation/clear-ghosts", null, {
    timeout: DIAG_TIMEOUT_MS,
  });
  return res.data?.data as PositionReconciliationDiagnostics;
}
