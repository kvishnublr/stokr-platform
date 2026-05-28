import { api } from "./client";

export type KillSwitchStatus = {
  active: boolean;
  forcesPaperMode: boolean;
  configFlagEnabled: boolean;
  redisKillSwitch: boolean;
  flattenOnActivateDefault: boolean;
  stoppedStrategyInstances?: number;
  lastEventSource?: string;
  lastEventReason?: string;
  lastEventAt?: string;
};

export type StrategyRuntimeHealthRow = {
  strategyName: string;
  sessionDate: string;
  executionMode: string;
  scansAttempted: number;
  scansBlockedIntegrity: number;
  scansBlockedFeed: number;
  signalsGenerated: number;
  tradesOpened: number;
  tradesClosed: number;
  rejectionRate?: number | null;
  avgHoldSeconds?: number | null;
  lastScanTime?: string | null;
  lastSignalTime?: string | null;
  lastRejectionReason?: string | null;
};

export type OperationalDiagnostics = {
  collectedAt: string;
  feedHealth: Record<string, unknown>;
  safeStartup: Record<string, unknown>;
  strategyModes: Record<string, string>;
  strategyRuntimeHealth: StrategyRuntimeHealthRow[];
  blockedStrategies: Array<{ strategyName: string; reason: string }>;
  integrityFailuresToday: number;
  activeTrades: number;
  staleSymbols: Array<{ symbol: string; latestOpenTime?: string; lagSeconds?: number }>;
};

export type OmsDiagnostics = {
  collectedAt: string;
  killSwitch: KillSwitchStatus;
  brokerConnection: Record<string, unknown>;
  activeLimits: Record<string, unknown>;
  strategyExecutionModes: Record<string, string>;
  marketCloseProtection: Record<string, unknown>;
  dailyPnl?: { todayMtm: number; openPositionCount: number };
  blockedOrdersLast24h: number;
  duplicatePrevention: { dedupeWindowSeconds: number; activeKeysTracked: number };
  executionLatency: { avgAckLatencyMsLast24h: number; telemetryEventsLast24h: number };
};

export async function fetchOperationalDiagnostics(): Promise<OperationalDiagnostics> {
  const res = await api.get("/api/admin/operations/diagnostics");
  return res.data?.data as OperationalDiagnostics;
}

export async function fetchOmsDiagnostics(userId?: string): Promise<OmsDiagnostics> {
  const res = await api.get("/api/admin/oms/diagnostics", {
    params: userId ? { userId } : undefined,
  });
  return res.data?.data as OmsDiagnostics;
}

export async function fetchKillSwitchStatus(): Promise<KillSwitchStatus> {
  const res = await api.get("/api/admin/oms/kill-switch/status");
  return res.data?.data as KillSwitchStatus;
}

export async function activateKillSwitch(reason: string, flatten: boolean): Promise<KillSwitchStatus> {
  const res = await api.post("/api/admin/oms/kill-switch/activate", null, {
    params: { reason, flatten },
  });
  return res.data?.data as KillSwitchStatus;
}

export async function deactivateKillSwitch(reason: string): Promise<KillSwitchStatus> {
  const res = await api.post("/api/admin/oms/kill-switch/deactivate", null, {
    params: { reason },
  });
  return res.data?.data as KillSwitchStatus;
}

export type StrategyValidationRow = {
  strategyKey: string;
  validationStatus: string;
  liveShadowEnabled: boolean;
  enabled: boolean;
  executionConfig?: Record<string, unknown>;
  capitalState?: Record<string, unknown>;
  activeReservations?: number;
};

export type StrategyValidationDiagnostics = {
  strategies: StrategyValidationRow[];
  promotionPath: string[];
  policy: Record<string, unknown>;
};

export async function fetchStrategyValidationDiagnostics(): Promise<StrategyValidationDiagnostics> {
  const res = await api.get("/api/admin/strategy-validation/diagnostics");
  return res.data?.data as StrategyValidationDiagnostics;
}
